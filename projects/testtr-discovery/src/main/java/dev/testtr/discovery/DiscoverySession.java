package dev.testtr.discovery;

import com.microsoft.playwright.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class DiscoverySession {
    private final Map<Page, String> pages = new IdentityHashMap<>();
    private final Map<Frame, String> frames = new IdentityHashMap<>();
    public DiscoverySession(BrowserContext context, Recorder recorder) throws IOException {
        String script;
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/discovery.js"))) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        context.exposeBinding("__testtrEmit", (source, args) -> {
            recorder.accept(pages.computeIfAbsent(source.page(), p -> "page-" + (pages.size()+1)),
                frames.computeIfAbsent(source.frame(), f -> "frame-" + (frames.size()+1)), source.frame().url(), source.frame() == source.page().mainFrame(), args[0]);
            return null;
        });
        context.addInitScript(script);
        for (Page page : context.pages()) for (Frame frame : page.frames()) {
            frame.evaluate(script);
        }
        recorder.save();
    }
}
