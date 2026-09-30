package local.archi.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON reader/writer for the Claude Code stream-json protocol. */
final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        return p.value();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o, String key) {
        if (o instanceof Map<?, ?> m) {
            Object v = m.get(key);
            if (v instanceof Map<?, ?>) return (Map<String, Object>) v;
        }
        return null;
    }

    static String str(Object o, String key) {
        if (o instanceof Map<?, ?> m) {
            Object v = m.get(key);
            if (v instanceof String sv) return sv;
        }
        return null;
    }

    static List<?> list(Object o, String key) {
        if (o instanceof Map<?, ?> m) {
            Object v = m.get(key);
            if (v instanceof List<?> l) return l;
        }
        return List.of();
    }

    static String quote(String v) {
        StringBuilder b = new StringBuilder(v.length() + 16).append('"');
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    /** Serializes Map / List / String / Number / Boolean / null to JSON. */
    static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(v, b);
        return b.toString();
    }

    private static void write(Object v, StringBuilder b) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String s) {
            b.append(quote(s));
        } else if (v instanceof Boolean bo) {
            b.append(bo.booleanValue());
        } else if (v instanceof Double d) {
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) b.append(d.longValue());
            else b.append(d);
        } else if (v instanceof Number n) {
            b.append(n);
        } else if (v instanceof Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                b.append(quote(String.valueOf(e.getKey()))).append(':');
                write(e.getValue(), b);
            }
            b.append('}');
        } else if (v instanceof List<?> l) {
            b.append('[');
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) b.append(',');
                write(l.get(k), b);
            }
            b.append(']');
        } else {
            b.append(quote(String.valueOf(v)));
        }
    }

    /** Small builder for JSON objects: Json.map("a", 1, "b", "x"). */
    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k + 1 < kv.length; k += 2) m.put(String.valueOf(kv[k]), kv[k + 1]);
        return m;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object value() {
        if (i >= s.length()) throw new IllegalArgumentException("unexpected end of JSON");
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> { i += 4; yield Boolean.TRUE; }
            case 'f' -> { i += 5; yield Boolean.FALSE; }
            case 'n' -> { i += 4; yield null; }
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            i++; // ':'
            ws();
            m.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') return m;
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') return l;
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++; // opening quote
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> b.append(e);
                }
            } else {
                b.append(c);
            }
        }
    }

    private Object number() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        return Double.parseDouble(s.substring(st, i));
    }
}
