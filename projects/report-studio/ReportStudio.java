import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.math.BigDecimal;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

public final class ReportStudio extends JFrame {
    private static final Color INK = new Color(25, 45, 68);
    private static final Color BLUE = new Color(225, 239, 253);
    private final ReportEngine engine;
    private final StudioStore store;
    private final Path examples;
    private final JTextField name = field("name");
    private final JTextField group = field("group");
    private final JTextField measure = field("measure");
    private final JComboBox<String> templates = new JComboBox<>();
    private final DefaultListModel<Path> sources = new DefaultListModel<>();
    private final JList<Path> sourceList = new JList<>(sources) {
        @Override public String getToolTipText(MouseEvent event) {
            int index = locationToIndex(event.getPoint());
            return index >= 0 && getCellBounds(index, index).contains(event.getPoint())
                    ? getModel().getElementAt(index).toString() : null;
        }
    };
    private final DefaultTableModel preview = new DefaultTableModel() {
        @Override public boolean isCellEditable(int row, int column) { return false; }
        @Override public Class<?> getColumnClass(int column) {
            return switch (column) {
                case 1 -> Long.class;
                case 2 -> BigDecimal.class;
                default -> String.class;
            };
        }
    };
    private final JTable table = new JTable(preview);
    private final JTextArea diagnostics = textArea("diagnostics");
    private final JTextArea history = textArea("history");
    private final JLabel status = new JLabel("Выберите шаблон и добавьте CSV.");
    private final JTabbedPane tabs = new JTabbedPane();
    private final List<JButton> controls = new ArrayList<>();
    private final JButton export = button("export", "Экспортировать HTML", this::exportReport);
    private ReportEngine.Result completed;
    private ReportEngine.Template completedTemplate;

    private record Build(ReportEngine.Result result, String history) {}

    private ReportStudio(Path workspace, Path examples) {
        super("Report Studio");
        this.examples = examples;
        this.engine = new ReportEngine(workspace);
        this.store = new StudioStore(workspace);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(810, 525));
        setSize(1070, 670);
        setLocationByPlatform(true);
        setLayout(new BorderLayout());
        add(header(), BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, settings(), output());
        split.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        split.setDividerLocation(380);
        add(split, BorderLayout.CENTER);
        status.setName("status");
        status.setForeground(INK);
        status.setBorder(BorderFactory.createEmptyBorder(0, 15, 13, 15));
        add(status, BorderLayout.SOUTH);
        for (JTextField field : List.of(name, group, measure)) {
            field.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent event) { clearPreview(); }
                @Override public void removeUpdate(DocumentEvent event) { clearPreview(); }
                @Override public void changedUpdate(DocumentEvent event) { clearPreview(); }
            });
        }
        clearPreview();
        try {
            refreshTemplates(null);
            history.setText(store.history());
        } catch (IOException | IllegalArgumentException error) {
            showError(error);
        }
    }

    static ReportStudio open(Path workspace, Path examples) {
        ReportStudio studio = new ReportStudio(workspace, examples);
        studio.setVisible(true);
        return studio;
    }

    private JPanel header() {
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setBackground(INK);
        header.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        JLabel title = new JLabel("Report Studio");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 26f));
        title.setForeground(Color.WHITE);
        JLabel subtitle = new JLabel("Регулярные отчёты из CSV");
        subtitle.setForeground(new Color(198, 215, 235));
        header.add(title, BorderLayout.NORTH);
        header.add(subtitle, BorderLayout.CENTER);
        return header;
    }

    private JPanel settings() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 4, 3, 12));
        JPanel fields = new JPanel(new GridBagLayout());
        templates.setName("templates");
        row(fields, 0, "Сохранённые", templates);
        row(fields, 1, "Название", name);
        row(fields, 2, "Группировка", group);
        row(fields, 3, "Числовой столбец", measure);
        GridBagConstraints buttons = constraints(0, 4);
        buttons.gridwidth = 2;
        buttons.fill = GridBagConstraints.HORIZONTAL;
        fields.add(buttonBar(button("load", "Открыть", this::loadTemplate),
                button("save", "Сохранить", this::saveTemplate)), buttons);
        panel.add(fields, BorderLayout.NORTH);

        JPanel inputs = new JPanel(new BorderLayout(0, 6));
        inputs.add(new JLabel("CSV-файлы"), BorderLayout.NORTH);
        sourceList.setName("sources");
        sourceList.setToolTipText("CSV");
        sourceList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focused) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value,
                        index, selected, focused);
                label.putClientProperty("html.disable", true);
                if (value instanceof Path file) {
                    label.setText(file.getFileName().toString());
                }
                return label;
            }
        });
        inputs.add(new JScrollPane(sourceList), BorderLayout.CENTER);
        inputs.add(buttonBar(button("add", "Добавить CSV", this::chooseFiles),
                button("remove", "Убрать", this::removeSelected)), BorderLayout.SOUTH);
        panel.add(inputs, BorderLayout.CENTER);

        JButton build = button("build", "Собрать отчёт", this::buildReport);
        build.setBackground(BLUE);
        panel.add(buttonBar(button("demo", "Загрузить пример", this::demo), build),
                BorderLayout.SOUTH);
        return panel;
    }

    private JPanel output() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        table.setName("preview");
        table.setRowHeight(26);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer();
        renderer.putClientProperty("html.disable", true);
        table.setDefaultRenderer(String.class, renderer);
        tabs.addTab("Предпросмотр", new JScrollPane(table));
        tabs.addTab("Диагностика", new JScrollPane(diagnostics));
        tabs.addTab("История", new JScrollPane(history));
        panel.add(tabs, BorderLayout.CENTER);
        JPanel actions = new JPanel(new BorderLayout());
        actions.add(export, BorderLayout.EAST);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private static JTextField field(String id) {
        JTextField field = new JTextField();
        field.setName(id);
        return field;
    }

    private static JTextArea textArea(String id) {
        JTextArea area = new JTextArea();
        area.setName(id);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setMargin(new Insets(9, 10, 9, 10));
        return area;
    }

    private JButton button(String id, String label, Runnable action) {
        JButton button = new JButton(label);
        button.setName(id);
        button.addActionListener(event -> action.run());
        controls.add(button);
        return button;
    }

    private static JPanel buttonBar(JButton first, JButton second) {
        JPanel panel = new JPanel(new GridLayout(1, 2, 6, 0));
        panel.add(first);
        panel.add(second);
        return panel;
    }

    private static GridBagConstraints constraints(int x, int y) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = x;
        constraints.gridy = y;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(4, 0, 4, 8);
        return constraints;
    }

    private static void row(JPanel panel, int y, String label, java.awt.Component input) {
        JLabel caption = new JLabel(label);
        caption.setLabelFor(input);
        panel.add(caption, constraints(0, y));
        GridBagConstraints position = constraints(1, y);
        position.fill = GridBagConstraints.HORIZONTAL;
        position.weightx = 1;
        panel.add(input, position);
    }

    private void chooseFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("CSV", "csv"));
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            Arrays.stream(chooser.getSelectedFiles()).map(file -> file.toPath())
                    .map(path -> path.toAbsolutePath().normalize())
                    .filter(path -> !sources.contains(path)).forEach(sources::addElement);
            clearPreview();
        }
    }

    private void removeSelected() {
        sourceList.getSelectedValuesList().forEach(sources::removeElement);
        clearPreview();
    }

    private void demo() {
        name.setText("Сводка симуляции");
        group.setText("category");
        measure.setText("units");
        sources.clear();
        sources.addElement(examples.resolve("wave-a.csv"));
        sources.addElement(examples.resolve("wave-b.csv"));
        clearPreview();
        status.setText("Два синтетических файла готовы к сборке.");
    }

    private ReportEngine.Template template() {
        return new ReportEngine.Template(name.getText(), group.getText(), measure.getText());
    }

    private void refreshTemplates(String selected) throws IOException {
        templates.removeAllItems();
        store.templates().forEach(templates::addItem);
        if (selected != null) {
            templates.setSelectedItem(selected);
        }
    }

    private void saveTemplate() {
        try {
            ReportEngine.Template current = template();
            store.save(current);
            refreshTemplates(current.name());
            status.setText("Шаблон сохранён.");
        } catch (IOException | IllegalArgumentException error) {
            showError(error);
        }
    }

    private void loadTemplate() {
        if (!(templates.getSelectedItem() instanceof String selected)) {
            status.setText("Выберите сохранённый шаблон.");
            return;
        }
        try {
            ReportEngine.Template saved = store.load(selected);
            name.setText(saved.name());
            group.setText(saved.group());
            measure.setText(saved.measure());
            status.setText("Шаблон открыт.");
        } catch (IOException | IllegalArgumentException error) {
            showError(error);
        }
    }

    private void clearPreview() {
        completed = null;
        completedTemplate = null;
        preview.setDataVector(new Object[0][], new String[] {"Группа", "Строки", "Сумма"});
        export.setEnabled(false);
    }

    private void busy(boolean value) {
        controls.forEach(button -> button.setEnabled(!value));
        for (JTextField field : List.of(name, group, measure)) {
            field.setEnabled(!value);
        }
        templates.setEnabled(!value);
        sourceList.setEnabled(!value);
        export.setEnabled(!value && completed != null);
    }

    private void buildReport() {
        ReportEngine.Template current;
        try {
            current = template();
        } catch (IllegalArgumentException error) {
            showError(error);
            return;
        }
        List<Path> files = Collections.list(sources.elements());
        if (files.isEmpty()) {
            showError(new IllegalArgumentException("Добавьте хотя бы один CSV-файл."));
            return;
        }
        clearPreview();
        busy(true);
        status.setText("Обрабатываю файлы…");
        new SwingWorker<Build, Void>() {
            @Override protected Build doInBackground() throws IOException {
                ReportEngine.Result result = engine.build(current, files);
                store.record(current, files.size(), result);
                return new Build(result, store.history());
            }

            @Override protected void done() {
                try {
                    showBuild(current, get());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    showError(error);
                } catch (ExecutionException error) {
                    showError(error.getCause());
                } finally {
                    busy(false);
                }
            }
        }.execute();
    }

    private void showBuild(ReportEngine.Template current, Build build) {
        ReportEngine.Result result = build.result();
        history.setText(build.history());
        String details = "Обработано: %d   Из кэша: %d".formatted(result.processed(), result.reused());
        if (!result.successful()) {
            diagnostics.setText(details + "\n\n" + String.join("\n", result.errors()));
            tabs.setSelectedIndex(1);
            status.setText("Проверьте данные в диагностике и повторите сборку.");
            return;
        }

        preview.setColumnIdentifiers(new String[] {current.group(), "Строки", "Сумма · " + current.measure()});
        result.groups().forEach((key, total) ->
                preview.addRow(new Object[] {key, total.count(), total.amount()}));
        diagnostics.setText(details);
        tabs.setSelectedIndex(0);
        status.setText("Готово. " + details);
        completed = result;
        completedTemplate = current;
    }

    private void exportReport() {
        if (completed == null) {
            return;
        }
        try {
            Path saved = store.export(completedTemplate, completed);
            diagnostics.append("\n\nHTML сохранён в " + saved);
            tabs.setSelectedIndex(1);
            status.setText("HTML-отчёт сохранён. Путь указан в диагностике.");
        } catch (IOException | UncheckedIOException error) {
            showError(error);
        }
    }

    private void showError(Throwable error) {
        diagnostics.setText(error.getMessage());
        tabs.setSelectedIndex(1);
        status.setText("Проверьте диагностику.");
    }

    public static void main(String[] args) {
        if (args.length > 2) {
            System.err.println("Usage: java ReportStudio [workspace] [examples]");
            System.exit(2);
        }
        Path workspace = (args.length == 0 ? Path.of(System.getProperty("user.home"), ".report-studio")
                : Path.of(args[0])).toAbsolutePath().normalize();
        Path examples = (args.length < 2 ? Path.of("examples") : Path.of(args[1]))
                .toAbsolutePath().normalize();
        UIManager.put("defaultFont", new Font(Font.DIALOG, Font.PLAIN, 13));
        SwingUtilities.invokeLater(() -> open(workspace, examples));
    }
}
