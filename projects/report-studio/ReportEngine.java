import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;

final class ReportEngine {
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)");
    private final Path workspace;

    record Template(String name, String group, String measure) {
        Template {
            name = required(name, "Название шаблона");
            group = required(group, "Столбец группировки");
            measure = required(measure, "Числовой столбец");
            if (group.equals(measure)) {
                throw new IllegalArgumentException("Выберите разные столбцы для группы и значения.");
            }
        }

        private static String required(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " нужно заполнить.");
            }
            return value.strip();
        }
    }

    record Total(long count, BigDecimal amount) {
        Total plus(Total other) {
            return new Total(count + other.count, amount.add(other.amount));
        }
    }

    record Result(Map<String, Total> groups, int processed, int reused, List<String> errors) {
        boolean successful() {
            return errors.isEmpty();
        }
    }

    private record CsvRow(int line, List<String> cells) {}

    ReportEngine(Path workspace) {
        this.workspace = workspace;
    }

    Result build(Template template, List<Path> paths) {
        Map<String, Total> groups = new TreeMap<>();
        List<String> errors = new ArrayList<>();
        int processed = 0;
        int reused = 0;
        for (Path file : paths.stream().map(path -> path.toAbsolutePath().normalize())
                .distinct().toList()) {
            try {
                byte[] bytes = Files.readAllBytes(file);
                String digest = hash(bytes);
                String key = key(file.toString(), template.group(), template.measure());
                Path cache = workspace.resolve("cache").resolve(key + ".properties");
                Map<String, Total> partial = fromCache(cache, digest);
                if (partial == null) {
                    partial = aggregate(template, bytes);
                    saveCache(cache, digest, partial);
                    processed++;
                } else {
                    reused++;
                }
                partial.forEach((group, total) -> groups.merge(group, total, Total::plus));
            } catch (IOException | IllegalArgumentException error) {
                errors.add(file.getFileName() + " · " + error.getMessage());
            }
        }
        return new Result(java.util.Collections.unmodifiableMap(groups), processed, reused,
                List.copyOf(errors));
    }

    private static Map<String, Total> aggregate(Template template, byte[] bytes) throws IOException {
        String content = StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(bytes)).toString();
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        List<CsvRow> rows = parse(content.replace("\r\n", "\n").replace('\r', '\n'));
        if (rows.isEmpty()) {
            throw new IOException("файл пуст");
        }
        List<String> header = rows.getFirst().cells().stream().map(String::strip).toList();
        if (header.contains("") || header.stream().distinct().count() != header.size()) {
            throw new IOException("заголовки должны быть непустыми и уникальными");
        }
        int groupIndex = column(header, template.group());
        int amountIndex = column(header, template.measure());
        Map<String, Total> groups = new TreeMap<>();
        for (int i = 1; i < rows.size(); i++) {
            CsvRow row = rows.get(i);
            if (row.cells().size() != header.size()) {
                throw new IOException("строка " + row.line() + " · неверное число полей");
            }
            String group = row.cells().get(groupIndex).strip();
            String amount = row.cells().get(amountIndex).strip();
            if (group.isEmpty() || !DECIMAL.matcher(amount).matches()) {
                throw new IOException("строка " + row.line()
                        + " · нужны непустая группа и число с точкой в столбце «"
                        + template.measure() + "»");
            }
            groups.merge(group, new Total(1, new BigDecimal(amount)), Total::plus);
        }
        return groups;
    }

    private static int column(List<String> header, String name) throws IOException {
        int index = header.indexOf(name);
        if (index < 0) {
            throw new IOException("нет столбца «" + name + "»");
        }
        return index;
    }

    private enum Field { START, TEXT, QUOTED, CLOSED }

    private static List<CsvRow> parse(String text) throws IOException {
        List<CsvRow> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        Field field = Field.START;
        int line = 1;
        int rowLine = 1;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (field == Field.QUOTED) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        field = Field.CLOSED;
                    }
                } else {
                    cell.append(ch);
                    if (ch == '\n') {
                        line++;
                    }
                }
            } else if (ch == ',' || ch == '\n') {
                cells.add(cell.toString());
                cell.setLength(0);
                field = Field.START;
                if (ch == '\n') {
                    if (cells.size() > 1 || !cells.getFirst().isBlank()) {
                        rows.add(new CsvRow(rowLine, List.copyOf(cells)));
                    }
                    cells.clear();
                    rowLine = ++line;
                }
            } else if (ch == '"' && field == Field.START) {
                field = Field.QUOTED;
            } else if (ch == '"' || field == Field.CLOSED) {
                throw new IOException("строка " + line + " · неверные кавычки в CSV");
            } else {
                cell.append(ch);
                field = Field.TEXT;
            }
        }
        if (field == Field.QUOTED) {
            throw new IOException("строка " + rowLine + " · незакрытое поле в кавычках");
        }
        if (!cells.isEmpty() || field != Field.START) {
            cells.add(cell.toString());
            if (cells.size() > 1 || !cells.getFirst().isBlank()) {
                rows.add(new CsvRow(rowLine, List.copyOf(cells)));
            }
        }
        return rows;
    }

    private static Map<String, Total> fromCache(Path cache, String digest) {
        try {
            Properties properties = readProperties(cache);
            if (!digest.equals(properties.getProperty("digest"))) {
                return null;
            }
            Map<String, Total> groups = new TreeMap<>();
            for (String property : properties.stringPropertyNames()) {
                if (!property.startsWith("group.")) {
                    continue;
                }
                String[] parts = properties.getProperty(property).split(":", 2);
                long count = Long.parseLong(parts[0]);
                if (count < 1) {
                    return null;
                }
                groups.put(property.substring(6), new Total(count, new BigDecimal(parts[1])));
            }
            return Integer.parseInt(properties.getProperty("groups")) == groups.size() ? groups : null;
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException error) {
            return null;
        }
    }

    private static void saveCache(Path cache, String digest, Map<String, Total> groups)
            throws IOException {
        Properties properties = new Properties();
        properties.setProperty("digest", digest);
        properties.setProperty("groups", Integer.toString(groups.size()));
        groups.forEach((group, total) -> properties.setProperty("group." + group,
                total.count() + ":" + total.amount().toPlainString()));
        writeProperties(cache, properties);
    }

    static String html(Template template, Result result) {
        if (!result.successful()) {
            throw new IllegalArgumentException("Экспорт требует успешной сборки.");
        }
        String title = escape(template.name());
        StringBuilder report = new StringBuilder("""
                <!doctype html>
                <html lang="ru"><meta charset="UTF-8"><title>%s</title>
                <style>
                body{font:16px system-ui;max-width:760px;margin:56px auto;padding:0 18px;color:#192d44}
                table{width:100%%;border-collapse:collapse}
                th,td{text-align:left;padding:12px;border-bottom:1px solid #dbe4ee}
                th{background:#eef4fa}
                </style>
                <h1>%s</h1>
                <table><thead><tr><th>%s</th><th>Строки</th><th>Сумма · %s</th></tr></thead><tbody>
                """.formatted(title, title, escape(template.group()), escape(template.measure())));
        result.groups().entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> report.append("<tr><td>").append(escape(entry.getKey()))
                        .append("</td><td>").append(entry.getValue().count())
                        .append("</td><td>").append(entry.getValue().amount().toPlainString())
                        .append("</td></tr>\n"));
        return report.append("</tbody></table></html>\n").toString();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String key(String... fields) {
        StringBuilder encoded = new StringBuilder("v1");
        for (String field : fields) {
            encoded.append(':').append(field.length()).append(':').append(field);
        }
        return hash(encoded.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static Properties readProperties(Path path) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(Files.readString(path)));
        return properties;
    }

    static void writeProperties(Path path, Properties properties) throws IOException {
        StringWriter output = new StringWriter();
        properties.store(output, null);
        atomicWrite(path, output.toString());
    }

    static void atomicWrite(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".report-", ".tmp");
        try {
            Files.writeString(temporary, content);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
