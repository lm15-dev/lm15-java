package dev.lm15.cloud;

import java.util.ArrayList;
import java.util.List;

/** Python {@code shlex.split} (POSIX mode): whitespace-separated words with single/double quotes and backslash escapes. */
final class Shlex {
    private Shlex() {}

    static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder word = null;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                if (word != null) { out.add(word.toString()); word = null; }
                i++;
            } else if (c == '\'') {
                if (word == null) word = new StringBuilder();
                int end = text.indexOf('\'', i + 1);
                if (end < 0) throw new IllegalArgumentException("No closing quotation");
                word.append(text, i + 1, end);
                i = end + 1;
            } else if (c == '"') {
                if (word == null) word = new StringBuilder();
                i++;
                while (i < text.length() && text.charAt(i) != '"') {
                    char d = text.charAt(i);
                    if (d == '\\' && i + 1 < text.length() && "\"\\$`\n".indexOf(text.charAt(i + 1)) >= 0) {
                        word.append(text.charAt(i + 1));
                        i += 2;
                    } else {
                        word.append(d);
                        i++;
                    }
                }
                if (i >= text.length()) throw new IllegalArgumentException("No closing quotation");
                i++;
            } else if (c == '\\') {
                if (word == null) word = new StringBuilder();
                if (i + 1 < text.length()) word.append(text.charAt(i + 1));
                i += 2;
            } else {
                if (word == null) word = new StringBuilder();
                word.append(c);
                i++;
            }
        }
        if (word != null) out.add(word.toString());
        return out;
    }
}
