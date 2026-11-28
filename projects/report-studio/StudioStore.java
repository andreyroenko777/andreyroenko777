import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

final class StudioStore {
    private final Path workspace;

    StudioStore(Path workspace) {
        this.workspace = workspace;
    }

    void save(ReportEngine.Template template) throws IOException {
        Properties file = new Properties();
        file.setProperty("name", template.name());
        file.setProperty("group", template.group());
        file.setProperty("measure", template.measure());
        ReportEngine.writeProperties(templatePath(template.name()), file);
    }

    ReportEngine.Template load(String name) throws IOException {
        Properties file = ReportEngine.readProperties(templatePath(name));
        return new ReportEngine.Template(file.getProperty("name"), file.getProperty("group"),
                file.getProperty("measure"));
    }

    List<String> templates() throws IOException {
        List<String> names = new ArrayList<>();
        for (Path path : propertyFiles(workspace.resolve("templates"))) {
            String name = ReportEngine.readProperties(path).getProperty("name");
            if (name != null) {
                names.add(name);
            }
        }
        return names.stream().sorted().toList();
    }

    void record(ReportEngine.Template template, int inputs, ReportEngine.Result result)
            throws IOException {
        Instant now = Instant.now();
        Properties entry = new Properties();
        entry.setProperty("when", now.toString());
        entry.setProperty("template", template.name());
        entry.setProperty("sources", Integer.toString(inputs));
        entry.setProperty("processed", Integer.toString(result.processed()));
        entry.setProperty("reused", Integer.toString(result.reused()));
        entry.setProperty("outcome", result.successful() ? "Готово" : "Ошибка импорта");
        ReportEngine.writeProperties(workspace.resolve("history")
                .resolve(now.toEpochMilli() + "-" + UUID.randomUUID() + ".properties"), entry);
    }

    String history() throws IOException {
        List<String> entries = new ArrayList<>();
        for (Path path : propertyFiles(workspace.resolve("history"))) {
            Properties entry = ReportEngine.readProperties(path);
            entries.add("%s · %s · %s%n  Источники: %s   Обработано: %s   Из кэша: %s"
                    .formatted(entry.getProperty("when"), entry.getProperty("outcome"),
                            entry.getProperty("template"), entry.getProperty("sources"),
                            entry.getProperty("processed"), entry.getProperty("reused")));
        }
        return entries.isEmpty() ? "Запусков пока нет." : String.join("\n\n", entries);
    }

    Path export(ReportEngine.Template template, ReportEngine.Result result) throws IOException {
        String name = "report-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + ".html";
        Path output = workspace.resolve("exports").resolve(name);
        ReportEngine.atomicWrite(output, ReportEngine.html(template, result));
        return output;
    }

    private Path templatePath(String name) {
        return workspace.resolve("templates").resolve(ReportEngine.key(name) + ".properties");
    }

    private static List<Path> propertyFiles(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .filter(Files::isRegularFile).sorted(Comparator.reverseOrder()).toList();
        }
    }
}
