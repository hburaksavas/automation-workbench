package dev.testtr.discovery;

import java.nio.file.*;
import java.util.*;

final class OutputPaths {
    static Path html(Path output) {
        String name = output.getFileName().toString();
        Path html = output.resolveSibling(name.replaceFirst("\\.json$", "") + ".html");
        return html.equals(output) ? output.resolveSibling(name + ".html") : html;
    }
    static Path resolved(Path path) throws Exception {
        path = path.toAbsolutePath().normalize(); Path existing = path;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        return existing.toRealPath().resolve(existing.relativize(path)).normalize();
    }
    static void protect(Path output, List<Path> inputs) throws Exception {
        for (Path destination : List.of(output, html(output))) for (Path input : inputs) {
            Path target = resolved(destination), source = resolved(input);
            if (target.equals(source) || (Files.isDirectory(input) && target.startsWith(source))
                || (Files.exists(destination) && Files.exists(input) && Files.isSameFile(destination, input)))
                throw new IllegalArgumentException("Output would overwrite an input: " + input);
        }
    }
}
