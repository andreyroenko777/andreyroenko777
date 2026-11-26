import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NotesSearch {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}]*");
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private record Hit(NoteIndex.Document document, double score) {}
    private record Preview(int lineNumber, String text) {}

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC);
    }

    static Matcher words(String text) {
        return WORD.matcher(normalize(text));
    }

    static String term(String word) {
        return word.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private static List<Hit> search(List<NoteIndex.Document> documents, Set<String> query) {
        List<NoteIndex.Document> nonempty = documents.stream()
                .filter(document -> document.words() > 0)
                .toList();
        Map<String, Map<Integer, Integer>> postings = new HashMap<>();
        long totalWords = 0;
        for (int documentId = 0; documentId < nonempty.size(); documentId++) {
            NoteIndex.Document document = nonempty.get(documentId);
            totalWords += document.words();
            for (String term : query) {
                Integer frequency = document.terms().get(term);
                if (frequency != null) {
                    postings.computeIfAbsent(term, key -> new HashMap<>()).put(documentId, frequency);
                }
            }
        }

        if (!postings.keySet().containsAll(query)) {
            return List.of();
        }
        List<Map<Integer, Integer>> termPostings = query.stream()
                .map(postings::get)
                .sorted(Comparator.comparingInt(Map::size))
                .toList();
        int documentCount = nonempty.size();
        double averageLength = (double) totalWords / documentCount;
        List<Hit> hits = new ArrayList<>();
        for (int documentId : termPostings.getFirst().keySet()) {
            if (!termPostings.stream().allMatch(posting -> posting.containsKey(documentId))) {
                continue;
            }
            NoteIndex.Document document = nonempty.get(documentId);
            double adjustment = K1 * (1 - B + B * document.words() / averageLength);
            double score = 0;
            for (var posting : termPostings) {
                double inverseFrequency =
                        Math.log1p((documentCount - posting.size() + 0.5) / (posting.size() + 0.5));
                int frequency = posting.get(documentId);
                score += inverseFrequency * frequency * (K1 + 1) / (frequency + adjustment);
            }
            hits.add(new Hit(document, score));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(hit -> hit.document().path()));
        return hits;
    }

    private static Preview preview(List<String> lines, Set<String> query) {
        int bestCount = 0;
        Preview best = new Preview(1, "");
        for (int index = 0; index < lines.size(); index++) {
            String line = normalize(lines.get(index)).strip();
            Matcher words = WORD.matcher(line);
            Set<String> matches = new HashSet<>();
            int firstMatch = -1;
            while (words.find()) {
                String word = term(words.group());
                if (query.contains(word)) {
                    if (matches.isEmpty()) {
                        firstMatch = words.start();
                    }
                    matches.add(word);
                }
            }
            if (matches.size() > bestCount) {
                bestCount = matches.size();
                int match = line.codePointCount(0, firstMatch);
                int start = Math.max(0, match - 35);
                int length = line.codePointCount(0, line.length());
                int end = Math.min(length, start + 140);
                String excerpt = line.substring(line.offsetByCodePoints(0, start),
                        line.offsetByCodePoints(0, end));
                if (start > 0) {
                    excerpt = "…" + excerpt;
                }
                if (end < length) {
                    excerpt += "…";
                }
                best = new Preview(index + 1, excerpt);
            }
        }
        return best;
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 1 && args[0].equals("--help")) {
            usage(out);
            return 0;
        }
        boolean rebuild = args.length > 0 && args[0].equals("--rebuild");
        int first = rebuild ? 1 : 0;
        int positional = args.length - first;
        if (positional < 2 || positional > 3) {
            usage(err);
            return 2;
        }
        try {
            Path root = Path.of(args[first]).toAbsolutePath().normalize();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Folder does not exist or is a symbolic link");
            }
            int limit = positional == 3 ? Integer.parseInt(args[first + 2]) : 10;
            if (limit < 1) {
                throw new IllegalArgumentException("Limit must be positive");
            }
            Set<String> query = new LinkedHashSet<>();
            Matcher words = words(args[first + 1]);
            while (words.find()) {
                query.add(term(words.group()));
            }
            if (query.isEmpty()) {
                throw new IllegalArgumentException("Query must contain a letter or number");
            }

            NoteIndex.Refresh refresh = NoteIndex.refresh(root, rebuild);
            List<Hit> hits = search(refresh.documents(), query);
            List<String> output = new ArrayList<>();
            for (Hit hit : hits.stream().limit(limit).toList()) {
                Preview preview = preview(NoteIndex.lines(root, hit.document()), query);
                output.add(String.format(Locale.ROOT, "%s:%d [%.3f] %s",
                        hit.document().path(), preview.lineNumber(), hit.score(), preview.text()));
            }
            err.printf("Index: indexed=%d reused=%d removed=%d%n",
                    refresh.indexed(), refresh.reused(), refresh.removed());
            if (output.isEmpty()) {
                out.println("No matching documents.");
            }
            output.forEach(out::println);
            return 0;
        } catch (IOException | UncheckedIOException | IllegalArgumentException error) {
            err.println("Search error: " + error.getMessage());
            return 2;
        }
    }

    private static void usage(PrintStream out) {
        out.println("Usage: java NotesSearch [--rebuild] <folder> <query> [limit]");
    }

    public static void main(String[] args) {
        if (System.console() != null) {
            System.exit(run(args, System.out, System.err));
        } else {
            PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
            PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
            System.exit(run(args, out, err));
        }
    }
}
