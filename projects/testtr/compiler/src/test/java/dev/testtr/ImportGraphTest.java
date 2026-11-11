package dev.testtr;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ImportGraphTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    private void file(String path, String text) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
    private ImportGraph.Result graph(String options) throws Exception {
        return ImportGraph.build(root, json.readTree(options), Map.of("A", List.of(Pattern.compile("src/Page\\.[^.]+"))));
    }

    @Test void followsTransitiveReExportsDirectoryIndexesAndDirectStyles() throws Exception {
        file("src/Page.js", "import Card from './components'; import './style.css'; export default Card;");
        file("src/components/index.jsx", "export { default } from './Card';");
        file("src/components/Card.tsx", "export default () => null;");
        file("src/style.css", "body { color: red; }");
        var graph = graph("{\"roots\":[\"src\"]}");
        assertTrue(graph.uncertainties().isEmpty());
        assertEquals("src/Page.js → src/components/index.jsx → src/components/Card.tsx", graph.pages().get("A").get("src/components/Card.tsx"));
        assertTrue(graph.pages().get("A").containsKey("src/style.css"));
    }

    @Test void handlesJsJsxTsTsxMultilineTypeImportsAndLiteralDynamicCalls() throws Exception {
        for (String extension : List.of("js", "jsx", "ts", "tsx")) {
            file("src/Page." + extension, "import type {\n Props\n} from './Props'; const x=require('./data.json'); const y=import('./Lazy');");
        }
        file("src/Props.ts", "export type Props = {};");
        file("src/data.json", "{}");
        file("src/Lazy.jsx", "export default () => null;");
        var graph = graph("{\"roots\":[\"src\"]}");
        assertTrue(graph.uncertainties().isEmpty(), graph.uncertainties().toString());
        assertTrue(graph.pages().get("A").containsKey("src/Lazy.jsx"));
        assertTrue(graph.pages().get("A").containsKey("src/Props.ts"));
    }

    @Test void aliasesAndExplicitExternalPackagesResolveWithoutExecutingJavaScript() throws Exception {
        file("src/Page.js", "import React from 'react'; import x from '@/shared/Card'; import data from './icon.svg?raw';");
        file("src/shared/Card.ts", "export default 1;");
        file("src/icon.svg", "<svg/>");
        var graph = graph("{\"roots\":[\"src\"],\"aliases\":{\"@/\":\"src/\"},\"external\":[\"react\"]}");
        assertTrue(graph.uncertainties().isEmpty());
        assertTrue(graph.pages().get("A").containsKey("src/shared/Card.ts"));
        assertTrue(graph.pages().get("A").containsKey("src/icon.svg"));
    }

    @Test void unresolvedComputedAndBundlerSpecificImportsAreUncertain() throws Exception {
        for (String code : List.of("import('./Missing')", "import(name)", "import(`./${name}.js`)",
                "import.meta.glob('./*.js')", "require.context('./parts')", "import x from 'unknown-alias/Card'",
                "export { X } from /* comment */ './unsupported'")) {
            file("src/Page.js", code);
            assertFalse(graph("{\"roots\":[\"src\"]}").uncertainties().isEmpty(), code);
        }
    }

    @Test void ambiguousResolutionAndStylesheetDependenciesWidenSelection() throws Exception {
        file("src/Page.js", "import x from './Card'; import './style.css';");
        file("src/Card.js", "export default 1;");
        file("src/Card.ts", "export default 2;");
        file("src/style.css", "@import './shared.css';");
        var graph = graph("{\"roots\":[\"src\"]}");
        assertTrue(graph.uncertainties().stream().anyMatch(s -> s.contains("belirsiz")));
        assertTrue(graph.uncertainties().stream().anyMatch(s -> s.contains("Stil")));
    }

    @Test void cyclesTerminateAndCommentsCanOnlyBroadenSelection() throws Exception {
        file("src/Page.js", "import A from './A';");
        file("src/A.js", "import Page from './Page'; // import X from './not-real';");
        var graph = graph("{\"roots\":[\"src\"]}");
        assertEquals(2, graph.pages().get("A").size());
        assertFalse(graph.uncertainties().isEmpty());
    }

    @Test void invalidConfigurationAndOutOfRepositoryImportsAreRejectedOrUncertain() throws Exception {
        file("src/Page.js", "import X from '../../outside';");
        assertFalse(graph("{\"roots\":[\"src\"]}").uncertainties().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> graph("{\"roots\":[\"../outside\"]}"));
        assertThrows(java.io.IOException.class, () -> graph("{\"roots\":[\"missing\"]}"));
        assertThrows(IllegalArgumentException.class, () -> graph("{\"roots\":[\"src\"],\"aliases\":{\"@\":\"../outside\"}}"));
        Files.delete(root.resolve("src/Page.js"));
        assertTrue(graph("{\"roots\":[\"src\"]}").uncertainties().stream().anyMatch(s -> s.contains("giriş dosyası")));
    }
}
