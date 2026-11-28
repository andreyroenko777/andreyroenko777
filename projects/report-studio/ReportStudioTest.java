import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

public final class ReportStudioTest {
    public static void main(String[] args) throws Exception {
        Path scratch = Files.createTempDirectory("report-studio-test-");
        try {
            uiScenario(scratch, args.length == 1 ? Path.of(args[0]) : null);
            csvEdges(scratch);
            System.out.println("GUI scenario and CSV edge cases passed");
        } finally {
            onUi(() -> {
                Arrays.stream(Window.getWindows()).filter(ReportStudio.class::isInstance)
                        .forEach(Window::dispose);
                return null;
            });
            java.util.List<Path> paths;
            try (var tree = Files.walk(scratch)) {
                paths = tree.sorted(Comparator.reverseOrder()).toList();
            }
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void uiScenario(Path scratch, Path screenshot) throws Exception {
        Path workspace = scratch.resolve("saved");
        Path example = Files.createDirectories(scratch.resolve("examples"));
        Path a = Files.copy(Path.of("examples", "wave-a.csv"), example.resolve("wave-a.csv"));
        Path b = Files.copy(Path.of("examples", "wave-b.csv"), example.resolve("wave-b.csv"));
        String good = Files.readString(b);
        ReportStudio first = open(workspace, example);
        require(onUi(first::isShowing), "The application window did not open");
        click(first, "demo");
        click(first, "save");
        build(first);
        require(onUi(() -> button(first, "export").isEnabled()), diagnostic(first));
        String initialPreview = preview(first);
        require(initialPreview.contains("A|3|22.5") && initialPreview.contains("B|1|8"), initialPreview);
        if (screenshot != null) {
            onUi(() -> {
                BufferedImage image = new BufferedImage(first.getWidth(), first.getHeight(),
                        BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                try {
                    first.printAll(graphics);
                } finally {
                    graphics.dispose();
                }
                ImageIO.write(image, "png", screenshot.toFile());
                return null;
            });
        }
        click(first, "export");
        String exported;
        try (var reports = Files.list(workspace.resolve("exports"))) {
            exported = Files.readString(reports.findFirst().orElseThrow());
        }
        require(exported.contains("<tr><td>A</td><td>3</td><td>22.5</td></tr>"), exported);

        Files.writeString(b, "category,units\nA,broken\n");
        build(first);
        require(!onUi(() -> button(first, "export").isEnabled()), "Export allowed after error");
        require(diagnostic(first).contains("wave-b.csv") && diagnostic(first).contains("строка 2"),
                diagnostic(first));

        onUi(() -> { first.dispose(); return null; });
        ReportStudio reopened = open(workspace, example);
        String history = onUi(() -> component(reopened, "history", JTextArea.class).getText());
        require(history.contains("Ошибка импорта") && history.contains("Готово"), history);
        require(onUi(() -> component(reopened, "templates", JComboBox.class).getItemCount()) == 1,
                "The saved template did not survive restart");

        Files.writeString(b, good.replace("7.5", "9.5"));
        click(reopened, "demo");
        onUi(() -> {
            component(reopened, "group", JTextField.class).setText("wrong");
            return null;
        });
        click(reopened, "load");
        require(onUi(() -> component(reopened, "group", JTextField.class).getText()).equals("category"),
                "Opening a template did not restore its fields");
        build(reopened);
        require(diagnostic(reopened).contains("Обработано: 1")
                && diagnostic(reopened).contains("Из кэша: 1"), diagnostic(reopened));

        ReportEngine.Template template = new ReportEngine.Template("test", "category", "units");
        ReportEngine.Result fresh = new ReportEngine(scratch.resolve("clean"))
                .build(template, java.util.List.of(a, b));
        String expected = fresh.groups().entrySet().stream()
                .map(entry -> "%s|%d|%s".formatted(entry.getKey(), entry.getValue().count(),
                        entry.getValue().amount().toPlainString()))
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
        require(preview(reopened).equals(expected) && expected.contains("A|3|24.5"),
                preview(reopened));
    }

    private static void csvEdges(Path scratch) throws IOException {
        ReportEngine engine = new ReportEngine(scratch.resolve("edge-cache"));
        ReportEngine.Template template = new ReportEngine.Template("<sample>", "group", "value");
        Path file = scratch.resolve("edge.csv");
        Files.writeString(file, "\uFEFFgroup,value\r\n\"<A, \"\"name\"\">\r\nsecond line\",0.1\r\n"
                + "\"<A, \"\"name\"\">\r\nsecond line\",0.2\r\n");
        ReportEngine.Result result = engine.build(template, java.util.List.of(file));
        require(result.successful(), result.errors().toString());
        var total = result.groups().values().iterator().next();
        require(total.count() == 2 && total.amount().toPlainString().equals("0.3"), total.toString());
        String html = ReportEngine.html(template, result);
        require(html.contains("&lt;sample&gt;") && html.contains("&lt;A, &quot;name&quot;&gt;")
                && !html.contains("<sample>"), html);
        Files.writeString(file, "group,value\nX,1\n\"unclosed\n");
        require(engine.build(template, java.util.List.of(file)).errors().getFirst().contains("строка 3"),
                "A malformed record must report its physical line");
    }

    private static ReportStudio open(Path workspace, Path examples) throws Exception {
        ReportStudio.main(new String[] {workspace.toString(), examples.toString()});
        return onUi(() -> Arrays.stream(Window.getWindows())
                .filter(ReportStudio.class::isInstance).filter(Window::isShowing)
                .map(ReportStudio.class::cast).findFirst().orElseThrow());
    }

    private static void build(ReportStudio app) throws Exception {
        click(app, "build");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        while (!onUi(() -> button(app, "build").isEnabled()) && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        require(onUi(() -> button(app, "build").isEnabled()), "Background run did not finish");
    }

    private static String preview(ReportStudio app) throws Exception {
        return onUi(() -> {
            JTable table = component(app, "preview", JTable.class);
            StringBuilder result = new StringBuilder();
            for (int row = 0; row < table.getRowCount(); row++) {
                result.append(table.getValueAt(row, 0)).append('|').append(table.getValueAt(row, 1))
                        .append('|').append(table.getValueAt(row, 2)).append('\n');
            }
            return result.toString();
        });
    }

    private static JButton button(ReportStudio app, String name) {
        return component(app, name, JButton.class);
    }

    private static void click(ReportStudio app, String name) throws Exception {
        onUi(() -> { button(app, name).doClick(); return null; });
    }

    private static String diagnostic(ReportStudio app) throws Exception {
        return onUi(() -> component(app, "diagnostics", JTextArea.class).getText());
    }

    private static <T extends Component> T component(Component current, String name, Class<T> type) {
        if (name.equals(current.getName()) && type.isInstance(current)) {
            return type.cast(current);
        }
        if (current instanceof Container container) {
            for (Component child : container.getComponents()) {
                T match = component(child, name, type);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static <T> T onUi(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
