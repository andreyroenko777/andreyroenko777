import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class NotesSearchTest {
    private record Result(int exit, String output, String diagnostics) {
        void expectStats(int indexed, int reused, int removed) {
            require(exit == 0, diagnostics);
            String expected = "indexed=%d reused=%d removed=%d".formatted(indexed, reused, removed);
            require(diagnostics.contains(expected), diagnostics);
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("notes-search-test-");
        try {
            Path edited = root.resolve("edited.md");
            Path unchanged = root.resolve("unchanged.txt");
            Path deleted = root.resolve("deleted.md");
            Files.writeString(edited, "Резервная копия storage alpha\n");
            Files.writeString(unchanged, "резервная копия\n");
            Files.writeString(deleted, "копия резервная копия\n");

            Result cold = run(root, false);
            cold.expectStats(3, 0, 0);
            require(cold.output().contains("unchanged.txt:1"), cold.output());
            Result warm = run(root, false);
            warm.expectStats(0, 3, 0);
            require(warm.output().equals(cold.output()), warm.output());

            FileTime originalTime = Files.getLastModifiedTime(edited);
            long originalSize = Files.size(edited);
            Files.writeString(edited, "Резервная Ветка storage alpha\n");
            Files.setLastModifiedTime(edited, originalTime);
            require(Files.size(edited) == originalSize, "The fixture must keep its size");
            Files.delete(deleted);
            Files.writeString(root.resolve("new.txt"), "РЕЗЕРВНАЯ КОПИЯ копия копия\n");

            Result incremental = run(root, false);
            incremental.expectStats(2, 1, 1);
            require(incremental.output().contains("new.txt:1"), incremental.output());
            require(incremental.output().lines().count() == 2, incremental.output());
            Result full = run(root, true);
            full.expectStats(3, 0, 0);
            require(full.output().equals(incremental.output()), full.output());

            Path snapshot = root.resolve(".notes-search").resolve("index.tsv");
            Files.writeString(snapshot, "notes-search-v1\nbroken\n");
            Result recovered = run(root, false);
            recovered.expectStats(3, 0, 0);
            require(recovered.output().equals(full.output()), recovered.output());

            String goodSnapshot = Files.readString(snapshot);
            Path brokenFile = root.resolve("bad.txt");
            Files.write(brokenFile, new byte[] {(byte) 0xff});
            Result failed = run(root, false);
            require(failed.exit() == 2 && failed.diagnostics().contains("bad.txt"), failed.toString());
            require(Files.readString(snapshot).equals(goodSnapshot), "A failed refresh changed the index");
            Files.delete(brokenFile);

            Files.writeString(root.resolve("parallel.txt"), "резервная копия\n");
            Process first = start(root, false);
            Process second = start(root, false);
            Result firstResult = finish(first);
            Result secondResult = finish(second);
            require(firstResult.exit() == 0 && secondResult.exit() == 0,
                    firstResult.diagnostics() + secondResult.diagnostics());
            require(firstResult.output().equals(secondResult.output()), "Concurrent search results differ");
            require(firstResult.output().contains("parallel.txt:1"), firstResult.output());
            run(root, false).expectStats(0, 4, 0);
            System.out.println("Notes Search integration checks passed");
        } finally {
            List<Path> paths;
            try (var tree = Files.walk(root)) {
                paths = tree.sorted(Comparator.reverseOrder()).toList();
            }
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Result run(Path root, boolean rebuild) throws Exception {
        return finish(start(root, rebuild));
    }

    private static Process start(Path root, boolean rebuild) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(List.of(java.toString(), "-cp",
                System.getProperty("java.class.path"), "NotesSearch"));
        if (rebuild) {
            command.add("--rebuild");
        }
        command.add(root.toString());
        command.add("резервная копия");
        return new ProcessBuilder(command).start();
    }

    private static Result finish(Process process) throws Exception {
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Notes Search did not exit");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String diagnostics = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.exitValue(), output, diagnostics);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
