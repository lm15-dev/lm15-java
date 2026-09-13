package dev.lm15.cloud;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The AWS shared-file INI dialect as Python's {@code configparser} (strict,
 * no interpolation) reads it: {@code [section]} headers, {@code key = value}
 * or {@code key: value} options with lowercased keys, {@code #}/{@code ;}
 * comment lines, indented continuation lines. Duplicate sections or options
 * and option lines outside a section are malformed.
 */
final class Ini {
    private final Map<String, Map<String, String>> sections = new LinkedHashMap<>();

    static final class Malformed extends RuntimeException {
        Malformed(String message) { super(message); }
    }

    static Ini parse(String text) {
        Ini ini = new Ini();
        if (text == null) return ini;
        Map<String, String> current = null;
        String currentKey = null;
        for (String rawLine : text.split("\r?\n", -1)) {
            String stripped = rawLine.strip();
            if (stripped.isEmpty()) { currentKey = null; continue; }
            if (stripped.startsWith("#") || stripped.startsWith(";")) continue;
            boolean indented = Character.isWhitespace(rawLine.charAt(0));
            if (indented && current != null && currentKey != null) {
                current.put(currentKey, current.get(currentKey) + "\n" + stripped);
                continue;
            }
            if (stripped.startsWith("[") && stripped.endsWith("]")) {
                String name = stripped.substring(1, stripped.length() - 1);
                if (ini.sections.containsKey(name)) throw new Malformed("duplicate section " + name);
                current = new LinkedHashMap<>();
                ini.sections.put(name, current);
                currentKey = null;
                continue;
            }
            if (current == null) throw new Malformed("option before any section header");
            int eq = stripped.indexOf('=');
            int colon = stripped.indexOf(':');
            int delimiter = eq < 0 ? colon : (colon < 0 ? eq : Math.min(eq, colon));
            if (delimiter < 0) throw new Malformed("line has no '=' or ':' delimiter");
            String key = stripped.substring(0, delimiter).strip().toLowerCase();
            String value = stripped.substring(delimiter + 1).strip();
            if (current.containsKey(key)) throw new Malformed("duplicate option " + key);
            current.put(key, value);
            currentKey = key;
        }
        return ini;
    }

    boolean hasSection(String name) { return sections.containsKey(name); }

    /** The section's options, or an empty map (never null). */
    Map<String, String> section(String name) {
        Map<String, String> s = sections.get(name);
        return s == null ? Map.of() : s;
    }
}
