package dev.incidentforge.repo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Read-only access to one allowlisted source repository. Every path is resolved inside the
 * repository root (including after symlink resolution); there is no write method.
 */
public final class RepositoryReader {

    private static final Pattern SAFE_PATH = Pattern.compile("^[A-Za-z0-9_/.-]{1,200}$");
    private static final int MAX_READ_LINES = 200;

    private final Path root;

    public RepositoryReader(Path root) {
        try {
            this.root = root.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("Repository root does not exist: " + root, e);
        }
    }

    public record Excerpt(String path, int startLine, int endLine, int totalLines, String content) {
    }

    public record Match(String path, int line, String text) {
    }

    public Path root() {
        return root;
    }

    public Excerpt read(String relativePath, int startLine, int endLine) {
        Path file = resolveExisting(relativePath);
        List<String> lines = readLines(file);
        int start = Math.max(1, startLine);
        int end = Math.min(lines.size(), Math.max(start, endLine));
        if (end - start + 1 > MAX_READ_LINES) {
            end = start + MAX_READ_LINES - 1;
        }
        String content = start > lines.size() ? "" : String.join("\n", lines.subList(start - 1, end));
        return new Excerpt(normalize(relativePath), start, end, lines.size(), content);
    }

    public List<Match> search(String literal, int limit) {
        List<Match> matches = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root.resolve("src"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                List<String> lines = readLines(file);
                for (int i = 0; i < lines.size() && matches.size() < limit; i++) {
                    if (lines.get(i).contains(literal)) {
                        matches.add(new Match(relative(file), i + 1, lines.get(i).strip()));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return matches;
    }

    public boolean exists(String relativePath) {
        try {
            resolveExisting(relativePath);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public String readAll(String relativePath) {
        return String.join("\n", readLines(resolveExisting(relativePath)));
    }

    /** Resolves a repository-relative path, rejecting traversal, absolute paths and symlink escapes. */
    public Path resolveExisting(String relativePath) {
        String normalized = normalize(relativePath);
        Path candidate = root.resolve(normalized).normalize();
        if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
            throw new IllegalArgumentException("Path is outside the repository or does not exist: " + relativePath);
        }
        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(root)) {
                throw new IllegalArgumentException("Path escapes the repository: " + relativePath);
            }
            return real;
        } catch (IOException e) {
            throw new IllegalArgumentException("Unreadable path: " + relativePath, e);
        }
    }

    public static String normalize(String relativePath) {
        if (relativePath == null || !SAFE_PATH.matcher(relativePath).matches() || relativePath.startsWith("/")
                || relativePath.contains("..") || relativePath.contains("//")) {
            throw new IllegalArgumentException("Unsafe repository path: " + relativePath);
        }
        return relativePath;
    }

    private String relative(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /** Lines without terminators, independent of CRLF/LF checkouts. */
    private static List<String> readLines(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
            if (text.endsWith("\n")) {
                text = text.substring(0, text.length() - 1);
            }
            return List.of(text.split("\n", -1));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
