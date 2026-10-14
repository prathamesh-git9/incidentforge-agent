package dev.incidentforge.patch;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import dev.incidentforge.domain.Artifacts.FileEdit;
import dev.incidentforge.domain.Artifacts.NewFile;
import dev.incidentforge.domain.Artifacts.ProposedPatch;
import dev.incidentforge.repo.RepositoryReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Applies a policy-checked patch in memory, renders a unified diff that {@code git apply} accepts,
 * and materializes scratch copies of the repository for validation. The source repository is
 * never written to.
 */
public final class PatchWorkspace {

    public record Rendered(Map<String, String> changedFiles, String unifiedDiff, int changedLines) {
    }

    private PatchWorkspace() {
    }

    public static Rendered render(ProposedPatch patch, RepositoryReader repo) {
        Map<String, String> changed = new LinkedHashMap<>();
        StringBuilder diff = new StringBuilder();
        for (FileEdit edit : patch.edits()) {
            String original = changed.getOrDefault(edit.path(), repo.readAll(edit.path()));
            String updated = original.replace(PatchPolicy.normalize(edit.search()), PatchPolicy.normalize(edit.replace()));
            changed.put(edit.path(), updated);
        }
        for (Map.Entry<String, String> entry : changed.entrySet()) {
            appendDiff(diff, entry.getKey(), repo.readAll(entry.getKey()), entry.getValue(), false);
        }
        for (NewFile file : patch.newFiles()) {
            String content = PatchPolicy.normalize(file.content());
            changed.put(file.path(), content);
            appendDiff(diff, file.path(), "", content, true);
        }
        int changedLines = (int) diff.toString().lines()
                .filter(l -> (l.startsWith("+") && !l.startsWith("+++")) || (l.startsWith("-") && !l.startsWith("---")))
                .count();
        return new Rendered(changed, diff.toString(), changedLines);
    }

    private static void appendDiff(StringBuilder out, String path, String original, String revised, boolean newFile) {
        List<String> before = original.isEmpty() ? List.of() : lines(original);
        List<String> after = lines(revised);
        Patch<String> patch = DiffUtils.diff(before, after);
        out.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
        if (newFile) {
            out.append("new file mode 100644\n");
        }
        List<String> unified = UnifiedDiffUtils.generateUnifiedDiff(newFile ? "/dev/null" : "a/" + path,
                "b/" + path, before, patch, 3);
        unified.forEach(line -> out.append(line).append('\n'));
    }

    private static List<String> lines(String text) {
        String normalized = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        return List.of(normalized.split("\n", -1));
    }

    /** Copies the repository (excluding build output and VCS metadata) into {@code target}. */
    public static void copyRepository(Path source, Path target) {
        try {
            deleteRecursively(target);
            Files.createDirectories(target);
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(source) && (name.equals("target") || name.equals(".git"))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isRegularFile()) {
                        Files.copy(file, target.resolve(source.relativize(file).toString()));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void writeFiles(Path root, Map<String, String> files) {
        try {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                Path target = root.resolve(RepositoryReader.normalize(entry.getKey())).normalize();
                if (!target.startsWith(root)) {
                    throw new IllegalArgumentException("refusing to write outside workspace: " + entry.getKey());
                }
                Files.createDirectories(target.getParent());
                String content = entry.getValue().endsWith("\n") ? entry.getValue() : entry.getValue() + "\n";
                Files.writeString(target, content, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(paths::add);
        }
        for (Path p : paths) {
            Files.deleteIfExists(p);
        }
    }
}
