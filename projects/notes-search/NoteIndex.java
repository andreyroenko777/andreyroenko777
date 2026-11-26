import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.stream.Stream;

final class NoteIndex {
    private static final String VERSION = "notes-search-v1";
    private static final String DIRECTORY = ".notes-search";

    record Document(String path, String digest, int words, Map<String, Integer> terms) {}
    record Refresh(List<Document> documents, int indexed, int reused, int removed) {}

    private NoteIndex() {}

    static Refresh refresh(Path root, boolean rebuild) throws IOException {
        Path directory = root.resolve(DIRECTORY);
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Index directory is a symbolic link: " + directory);
        }
        Path index = directory.resolve("index.tsv");
        try (FileChannel channel = FileChannel.open(directory.resolve("index.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock lock = channel.lock()) {
            if (!lock.isValid()) {
                throw new IOException("Could not lock the index");
            }
            Map<String, Document> previous = rebuild ? Map.of() : readSnapshot(index);
            Map<String, Document> current = new TreeMap<>();
            int indexed = 0;
            int reused = 0;
            for (Path file : files(root, directory)) {
                String path = root.relativize(file).toString();
                byte[] content = Files.readAllBytes(file);
                String digest = digest(content);
                Document cached = previous.get(path);
                if (cached != null && cached.digest().equals(digest)) {
                    current.put(path, cached);
                    reused++;
                } else {
                    current.put(path, indexDocument(path, digest, content));
                    indexed++;
                }
            }

            int removed = previous.size() - (int) previous.keySet().stream()
                    .filter(current::containsKey).count();
            if (indexed > 0 || removed > 0 || rebuild || !Files.exists(index) || previous.isEmpty()) {
                writeSnapshot(index, current);
            }
            return new Refresh(List.copyOf(current.values()), indexed, reused, removed);
        }
    }

    private static List<Path> files(Path root, Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> !path.startsWith(directory))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return name.endsWith(".txt") || name.endsWith(".md");
                    })
                    .sorted()
                    .toList();
        }
    }

    private static Document indexDocument(String path, String digest, byte[] content)
            throws IOException {
        String text;
        try {
            text = decode(content);
        } catch (CharacterCodingException error) {
            throw new IOException("File is not valid UTF-8: " + path, error);
        }
        Matcher words = NotesSearch.words(text);
        Map<String, Integer> terms = new HashMap<>();
        int length = 0;
        while (words.find()) {
            terms.merge(NotesSearch.term(words.group()), 1, Integer::sum);
            length++;
        }
        return new Document(path, digest, length, Map.copyOf(terms));
    }

    static List<String> lines(Path root, Document document) throws IOException {
        byte[] content = Files.readAllBytes(root.resolve(document.path()));
        if (!digest(content).equals(document.digest())) {
            throw new IOException("File changed during search, retry the query: " + document.path());
        }
        return decode(content).lines().toList();
    }

    private static String decode(byte[] content) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
    }

    private static String digest(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static Map<String, Document> readSnapshot(Path index) throws IOException {
        if (Files.notExists(index, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }
        if (!Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Index path is not a regular file: " + index);
        }
        try (BufferedReader reader = Files.newBufferedReader(index, StandardCharsets.UTF_8)) {
            if (!VERSION.equals(reader.readLine())) {
                return Map.of();
            }
            Map<String, Document> documents = new HashMap<>();
            String header;
            while ((header = reader.readLine()) != null) {
                if (header.equals("END")) {
                    return reader.readLine() == null ? documents : Map.of();
                }
                Document document = readDocument(reader, header);
                if (documents.putIfAbsent(document.path(), document) != null) {
                    return Map.of();
                }
            }
            return Map.of();
        } catch (IllegalArgumentException | ArithmeticException | CharacterCodingException error) {
            return Map.of();
        }
    }

    private static Document readDocument(BufferedReader reader, String header) throws IOException {
        String[] fields = header.split("\t", -1);
        if (fields.length != 3 || !fields[1].matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid index header");
        }
        String path = decode(Base64.getDecoder().decode(fields[0]));
        int termCount = Integer.parseInt(fields[2]);
        if (path.isEmpty() || termCount < 0) {
            throw new IllegalArgumentException("Invalid index document");
        }
        int length = 0;
        Map<String, Integer> terms = new HashMap<>();
        for (int index = 0; index < termCount; index++) {
            String row = reader.readLine();
            if (row == null) {
                throw new IllegalArgumentException("Incomplete index document");
            }
            int separator = row.indexOf('\t');
            if (separator <= 0 || separator == row.length() - 1) {
                throw new IllegalArgumentException("Invalid index term");
            }
            int count = Integer.parseInt(row.substring(0, separator));
            String term = row.substring(separator + 1);
            if (count <= 0 || term.indexOf('\t') >= 0 || terms.putIfAbsent(term, count) != null) {
                throw new IllegalArgumentException("Invalid term frequency");
            }
            length = Math.addExact(length, count);
        }
        return new Document(path, fields[1], length, Map.copyOf(terms));
    }

    private static void writeSnapshot(Path index, Map<String, Document> documents) throws IOException {
        Path temporary = Files.createTempFile(index.getParent(), "snapshot-", ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writer.write(VERSION);
                writer.newLine();
                for (Document document : documents.values()) {
                    String path = Base64.getEncoder().encodeToString(
                            document.path().getBytes(StandardCharsets.UTF_8));
                    writer.write(path + "\t" + document.digest() + "\t" + document.terms().size());
                    writer.newLine();
                    for (var entry : new TreeMap<>(document.terms()).entrySet()) {
                        writer.write(entry.getValue() + "\t" + entry.getKey());
                        writer.newLine();
                    }
                }
                writer.write("END");
                writer.newLine();
            }
            Files.move(temporary, index, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
