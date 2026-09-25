import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny JSON reader, so the head-less spikes can read the layout/config files without pulling a
 * JSON library onto the classpath. It returns {@code Map<String,Object>}, {@code List<Object>},
 * {@code String}, {@code Double}, {@code Boolean} and {@code null} - enough for every file this
 * project generates.
 */
public final class Json {
    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    public static Object parse(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static Object parse(String text) {
        Json json = new Json(text);
        json.skipWhitespace();
        Object value = json.readValue();
        json.skipWhitespace();
        if (json.pos != text.length()) {
            throw new IllegalArgumentException("trailing content at offset " + json.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Map<String, Object> object, String key) {
        Object value = object.get(key);
        return value == null ? List.of() : (List<Object>) value;
    }

    public static int intOf(Map<String, Object> object, String key, int fallback) {
        Object value = object.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    public static String string(Map<String, Object> object, String key, String fallback) {
        Object value = object.get(key);
        return value instanceof String s ? s : fallback;
    }

    public static boolean bool(Map<String, Object> object, String key, boolean fallback) {
        Object value = object.get(key);
        return value instanceof Boolean b ? b : fallback;
    }

    /** A JSON array of strings as a list of strings; any non-string member is skipped. */
    public static List<String> strings(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String s) {
                out.add(s);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ parser

    private Object readValue() {
        char c = this.text.charAt(this.pos);
        return switch (c) {
            case '{' -> this.readObject();
            case '[' -> this.readArray();
            case '"' -> this.readString();
            case 't' -> this.readLiteral("true", Boolean.TRUE);
            case 'f' -> this.readLiteral("false", Boolean.FALSE);
            case 'n' -> this.readLiteral("null", null);
            default -> this.readNumber();
        };
    }

    private Map<String, Object> readObject() {
        Map<String, Object> result = new LinkedHashMap<>();
        this.pos++; // {
        this.skipWhitespace();
        if (this.text.charAt(this.pos) == '}') {
            this.pos++;
            return result;
        }
        while (true) {
            this.skipWhitespace();
            String key = this.readString();
            this.skipWhitespace();
            this.expect(':');
            this.skipWhitespace();
            result.put(key, this.readValue());
            this.skipWhitespace();
            char c = this.text.charAt(this.pos++);
            if (c == '}') {
                return result;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected , or } at offset " + (this.pos - 1));
            }
        }
    }

    private List<Object> readArray() {
        List<Object> result = new ArrayList<>();
        this.pos++; // [
        this.skipWhitespace();
        if (this.text.charAt(this.pos) == ']') {
            this.pos++;
            return result;
        }
        while (true) {
            this.skipWhitespace();
            result.add(this.readValue());
            this.skipWhitespace();
            char c = this.text.charAt(this.pos++);
            if (c == ']') {
                return result;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected , or ] at offset " + (this.pos - 1));
            }
        }
    }

    private String readString() {
        this.expect('"');
        StringBuilder builder = new StringBuilder();
        while (true) {
            char c = this.text.charAt(this.pos++);
            if (c == '"') {
                return builder.toString();
            }
            if (c == '\\') {
                char escape = this.text.charAt(this.pos++);
                switch (escape) {
                    case 'n' -> builder.append('\n');
                    case 't' -> builder.append('\t');
                    case 'r' -> builder.append('\r');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'u' -> {
                        builder.append((char) Integer.parseInt(this.text.substring(this.pos, this.pos + 4), 16));
                        this.pos += 4;
                    }
                    default -> builder.append(escape);
                }
            } else {
                builder.append(c);
            }
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!this.text.startsWith(literal, this.pos)) {
            throw new IllegalArgumentException("bad literal at offset " + this.pos);
        }
        this.pos += literal.length();
        return value;
    }

    private Double readNumber() {
        int start = this.pos;
        while (this.pos < this.text.length() && "+-.eE0123456789".indexOf(this.text.charAt(this.pos)) >= 0) {
            this.pos++;
        }
        return new BigDecimal(this.text.substring(start, this.pos)).doubleValue();
    }

    private void expect(char expected) {
        if (this.text.charAt(this.pos) != expected) {
            throw new IllegalArgumentException("expected " + expected + " at offset " + this.pos);
        }
        this.pos++;
    }

    private void skipWhitespace() {
        while (this.pos < this.text.length() && Character.isWhitespace(this.text.charAt(this.pos))) {
            this.pos++;
        }
    }
}
