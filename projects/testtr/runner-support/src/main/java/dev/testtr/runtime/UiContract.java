package dev.testtr.runtime;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Shared observation and comparison rules for all generated browser targets. */
public final class UiContract {
    private UiContract() {}

    public static final String SNAPSHOT = """
            selector => {
              const elements = document.querySelectorAll(selector);
              if (elements.length !== 1) return {count: elements.length};
              const element = elements[0];
              const style = getComputedStyle(element);
              const rect = element.getBoundingClientRect();
              const tag = element.tagName.toLowerCase();
              let kind = tag;
              if (tag === 'textarea') kind = 'input';
              if (tag === 'input' && element.type === 'checkbox') kind = 'checkbox';
              if (tag === 'input' && ['button','submit','reset'].includes(element.type)) kind = 'button';
              return {
                count: 1,
                kind,
                visible: rect.width > 0 && rect.height > 0 &&
                  style.visibility !== 'hidden' && style.visibility !== 'collapse',
                enabled: !element.matches(':disabled') && element.getAttribute('aria-disabled') !== 'true',
                readonly: Boolean(element.readOnly),
                text: element.innerText ?? element.textContent ?? '',
                value: typeof element.value === 'string' ? element.value : '',
                checked: Boolean(element.checked),
                multiple: Boolean(element.multiple),
                options: tag === 'select' ? Array.from(element.options, option => ({
                  label: option.text, index: option.index,
                  disabled: option.disabled || (option.parentElement.tagName === 'OPTGROUP' && option.parentElement.disabled)
                })) : [],
                selected: tag === 'select' && element.selectedIndex >= 0
                  ? element.options[element.selectedIndex].text : ''
              };
            }
            """;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    public static long timeoutMillis() {
        long timeout = Long.parseLong(System.getProperty("testtr.timeoutMillis", "5000"));
        if (timeout < 1 || timeout > 300_000) throw new IllegalArgumentException("testtr.timeoutMillis 1 ile 300000 arasında olmalıdır.");
        return timeout;
    }

    public static String text(String value) {
        return WHITESPACE.matcher(value).replaceAll(" ").strip();
    }

    public static String resolveUrl(String base, String page) {
        URI target = URI.create(page);
        if (target.isAbsolute()) return target.normalize().toString();
        URI baseUri = URI.create(base.replace('\\', '/'));
        if (!baseUri.isAbsolute()) throw new IllegalArgumentException("Göreli sayfa için mutlak testtr.baseUrl gereklidir.");
        return baseUri.resolve(target).normalize().toString();
    }

    public static boolean sameUrl(String expected, String actual) {
        URI left = URI.create(expected).normalize();
        URI right = URI.create(actual).normalize();
        if (left.isOpaque() || right.isOpaque()) return left.equals(right);
        return Objects.equals(lower(left.getScheme()), lower(right.getScheme()))
                && Objects.equals(lower(left.getHost()), lower(right.getHost()))
                && port(left) == port(right)
                && Objects.equals(left.getRawUserInfo(), right.getRawUserInfo())
                && Objects.equals(left.getRawPath(), right.getRawPath())
                && Objects.equals(left.getRawQuery(), right.getRawQuery())
                && Objects.equals(left.getRawFragment(), right.getRawFragment());
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        if ("https".equalsIgnoreCase(uri.getScheme())) return 443;
        if ("http".equalsIgnoreCase(uri.getScheme())) return 80;
        return -1;
    }

    public static void expectUrl(Supplier<String> current, String expected) {
        await("Adres " + expected, () -> {
            String actual = current.get();
            return new Observation(sameUrl(expected, actual), actual);
        });
    }

    public static void expect(Supplier<Object> snapshot, String operation, String expected) {
        boolean redact = Boolean.getBoolean("testtr.redactDiagnostics");
        await(redact ? operation : operation + " beklenen=" + expected, () -> {
            Map<?, ?> state = state(snapshot.get());
            return new Observation(matches(state, operation, expected),
                    redact ? safeState(state) : state.toString());
        }, redact);
    }

    private static String safeState(Map<?, ?> state) {
        return "count=" + count(state) + ", visible=" + flag(state, "visible")
                + ", enabled=" + flag(state, "enabled") + ", checked=" + flag(state, "checked");
    }

    public static void ready(Supplier<Object> snapshot, String operation) {
        expect(snapshot, "ready-" + operation, "");
    }

    public static String capture(Supplier<Object> snapshot) {
        String[] value = new String[1];
        await("Alan değeri okunabilmelidir", () -> {
            Map<?, ?> state = state(snapshot.get());
            boolean present = count(state) == 1;
            if (present) {
                requireKind(state, "input");
                value[0] = string(state, "value");
            }
            return new Observation(present, state.toString());
        });
        return value[0];
    }

    public static int selectionIndex(Supplier<Object> snapshot, String label) {
        int[] index = {-1};
        await("Seçenek " + label, () -> {
            Map<?, ?> state = state(snapshot.get());
            if (count(state) == 0) return new Observation(false, "Liste bulunamadı");
            requireKind(state, "select");
            List<?> options = (List<?>) state.get("options");
            List<?> matches = options.stream().filter(item -> text(string((Map<?, ?>) item, "label")).equals(text(label))).toList();
            if (matches.size() > 1) throw new AssertionError("Seçenek adı benzersiz değil: " + label);
            if (matches.isEmpty()) return new Observation(false, "Seçenek bulunamadı");
            Map<?, ?> option = (Map<?, ?>) matches.get(0);
            index[0] = ((Number) option.get("index")).intValue();
            return new Observation(!flag(option, "disabled"), option.toString());
        });
        return index[0];
    }

    private static Map<?, ?> state(Object value) {
        if (!(value instanceof Map<?, ?> state)) throw new IllegalStateException("Geçersiz DOM gözlemi.");
        if (count(state) > 1) throw new AssertionError("Locator benzersiz değil. Eşleşme sayısı: " + count(state));
        return state;
    }

    private static int count(Map<?, ?> state) {
        return ((Number) state.get("count")).intValue();
    }

    private static boolean flag(Map<?, ?> state, String name) { return Boolean.TRUE.equals(state.get(name)); }
    private static String string(Map<?, ?> state, String name) { return (String) state.get(name); }

    private static void requireKind(Map<?, ?> state, String kind) {
        if (!kind.equals(state.get("kind"))) throw new AssertionError("DOM obje tipi uyuşmuyor. Beklenen: " + kind);
        if (kind.equals("select") && flag(state, "multiple")) throw new AssertionError("Tek seçimli liste bekleniyor.");
    }

    private static boolean matches(Map<?, ?> state, String operation, String expected) {
        if (count(state) == 0) return operation.equals("hidden");
        if (operation.startsWith("ready-")) {
            String action = operation.substring("ready-".length());
            String kind = switch (action) {
                case "fill", "clear", "tab" -> "input";
                case "click" -> "button";
                case "select" -> "select";
                case "check", "uncheck" -> "checkbox";
                default -> throw new IllegalArgumentException(action);
            };
            requireKind(state, kind);
            boolean editable = !(action.equals("fill") || action.equals("clear")) || !flag(state, "readonly");
            return flag(state, "visible") && flag(state, "enabled") && editable;
        }
        switch (operation) {
            case "value" -> requireKind(state, "input");
            case "checked", "unchecked" -> requireKind(state, "checkbox");
            case "enabled", "disabled" -> requireKind(state, "button");
            case "selected" -> requireKind(state, "select");
            default -> { }
        }
        return switch (operation) {
            case "text" -> flag(state, "visible") && text(string(state, "text")).equals(text(expected));
            case "contains" -> flag(state, "visible") && text(string(state, "text")).contains(text(expected));
            case "value" -> string(state, "value").equals(expected);
            case "visible" -> flag(state, "visible");
            case "hidden" -> !flag(state, "visible");
            case "enabled" -> flag(state, "enabled");
            case "disabled" -> !flag(state, "enabled");
            case "checked" -> flag(state, "checked");
            case "unchecked" -> !flag(state, "checked");
            case "selected" -> text(string(state, "selected")).equals(text(expected));
            default -> throw new IllegalArgumentException(operation);
        };
    }

    private record Observation(boolean matched, String actual) {}

    static final class SafeObservationFailure extends AssertionError {
        private SafeObservationFailure(String message) { super(message); }
    }

    private static void await(String description, Supplier<Observation> check) {
        await(description, check, false);
    }

    private static void await(String description, Supplier<Observation> check, boolean safeObservation) {
        long deadline = System.nanoTime() + timeoutMillis() * 1_000_000;
        Observation observation;
        do {
            observation = check.get();
            if (observation.matched()) return;
            if (System.nanoTime() >= deadline) break;
            try {
                Thread.sleep(25);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Doğrulama kesildi.", exception);
            }
        } while (true);
        String message = description + "; gözlenen=" + observation.actual();
        if (safeObservation) throw new SafeObservationFailure(message);
        throw new AssertionError(message);
    }
}
