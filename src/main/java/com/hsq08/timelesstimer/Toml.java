package com.hsq08.timelesstimer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deliberately small TOML reader covering exactly the subset that TimelessTimer
 * writes to {@code config/timeless_timer/}: tables, bare keys, basic (double quoted)
 * strings, literal (single quoted) strings and booleans.
 *
 * <p>Everything else - arrays, inline tables, integers, floats, dates, multi line
 * strings, duplicate keys, keys outside a table - is reported as a format error with
 * its line number, because the specification requires a broken timer unit file to be
 * rejected instead of being silently reinterpreted.</p>
 */
final class Toml {

    private Toml() {
    }

    /** One parsed TOML file: {@code table name -> key -> string value}. */
    static final class Table {
        /** Keys that appear before any table header live in this pseudo-section. */
        static final String ROOT = "";
        private final Map<String, Map<String, String>> sections = new LinkedHashMap<>();

        String get(String section, String key) {
            Map<String, String> values = sections.get(section);
            return values == null ? null : values.get(key);
        }

        boolean hasSection(String section) {
            return sections.containsKey(section);
        }

        java.util.Set<String> sections() {
            return sections.keySet();
        }

        void put(String section, String key, String value) {
            sections.computeIfAbsent(section, name -> new LinkedHashMap<>()).put(key, value);
        }
    }

    /**
     * @param source          file content
     * @param allowedSections table names that may appear; anything else is an error
     * @param allowedRootKeys keys allowed before the first table header, e.g. {@code version}
     */
    static Table parse(String source, java.util.Set<String> allowedSections, java.util.Set<String> allowedRootKeys)
            throws ParseException {
        Table table = new Table();
        String current = Table.ROOT;
        List<String> seen = new ArrayList<>();

        String[] lines = source.split("\r\n|\n|\r", -1);
        for (int index = 0; index < lines.length; index++) {
            int lineNumber = index + 1;
            String line = stripComment(lines[index]).trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith("[")) {
                if (!line.endsWith("]")) {
                    throw new ParseException(lineNumber, "表头缺少结尾的 ']'：" + line);
                }
                String name = line.substring(1, line.length() - 1).trim();
                if (name.startsWith("[") || name.isEmpty()) {
                    throw new ParseException(lineNumber, "不支持的表头：" + line);
                }
                if (name.contains(".")) {
                    throw new ParseException(lineNumber, "不支持嵌套表头：" + name);
                }
                if (allowedSections != null && !allowedSections.contains(name)) {
                    throw new ParseException(lineNumber, "未知的表名 '[" + name + "]'，只允许 "
                            + String.join("、", allowedSections));
                }
                if (seen.contains(name)) {
                    throw new ParseException(lineNumber, "表 '[" + name + "]' 重复出现");
                }
                seen.add(name);
                current = name;
                table.sections.computeIfAbsent(name, key -> new LinkedHashMap<>());
                continue;
            }

            int equals = indexOfAssignment(line);
            if (equals < 0) {
                throw new ParseException(lineNumber, "不是合法的键值对：" + line);
            }
            String key = line.substring(0, equals).trim();
            String rawValue = line.substring(equals + 1).trim();
            if (key.isEmpty()) {
                throw new ParseException(lineNumber, "键名为空");
            }
            if (key.startsWith("\"") || key.startsWith("'")) {
                throw new ParseException(lineNumber, "不支持带引号的键名：" + key);
            }
            if (!key.matches("[A-Za-z0-9_-]+")) {
                throw new ParseException(lineNumber, "键名只能包含英文、数字、下划线和短横线：" + key);
            }
            if (Table.ROOT.equals(current) && (allowedRootKeys == null || !allowedRootKeys.contains(key))) {
                throw new ParseException(lineNumber, "键 '" + key + "' 必须写在某个表（如 [unit]、[settings]）之内");
            }
            Map<String, String> values = table.sections.computeIfAbsent(current, name -> new LinkedHashMap<>());
            if (values.containsKey(key)) {
                throw new ParseException(lineNumber, "键 '" + key + "' 在 " + describe(current) + " 中重复定义");
            }

            String value = readValue(rawValue, lineNumber);
            if (value == null) {
                throw new ParseException(lineNumber, "不支持的值类型（只支持字符串和 true/false）：" + rawValue);
            }
            values.put(key, value);
        }
        return table;
    }

    private static String describe(String section) {
        return Table.ROOT.equals(section) ? "文件开头" : "[" + section + "]";
    }

    /** Finds the {@code =} that separates key and value, ignoring quoted text. */
    private static int indexOfAssignment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (quote == '"' && c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '=') {
                return i;
            }
        }
        return -1;
    }

    /** Removes a trailing comment, honouring quoted text. */
    private static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (quote == '"' && c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    /** @return the value as a string, or {@code null} when the type is unsupported */
    private static String readValue(String raw, int lineNumber) throws ParseException {
        if (raw.isEmpty()) {
            return null;
        }
        char first = raw.charAt(0);
        if (first == '"' || first == '\'') {
            if (raw.length() < 2 || raw.charAt(raw.length() - 1) != first) {
                throw new ParseException(lineNumber, "字符串缺少结尾的引号");
            }
            if (raw.length() >= 6 && raw.startsWith(String.valueOf(first).repeat(3))) {
                throw new ParseException(lineNumber, "不支持多行字符串");
            }
            String body = raw.substring(1, raw.length() - 1);
            return first == '\'' ? body : unescape(body, lineNumber);
        }
        if (raw.equals("true") || raw.equals("false")) {
            return raw;
        }
        return null;
    }

    private static String unescape(String body, int lineNumber) throws ParseException {
        StringBuilder out = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            i++;
            if (i >= body.length()) {
                throw new ParseException(lineNumber, "字符串以孤立的反斜杠结尾");
            }
            char escape = body.charAt(i);
            switch (escape) {
                case 'b' -> out.append('\b');
                case 't' -> out.append('\t');
                case 'n' -> out.append('\n');
                case 'f' -> out.append('\f');
                case 'r' -> out.append('\r');
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case 'u', 'U' -> {
                    int length = escape == 'u' ? 4 : 8;
                    if (i + length >= body.length()) {
                        throw new ParseException(lineNumber, "\\" + escape + " 转义不完整");
                    }
                    String hex = body.substring(i + 1, i + 1 + length);
                    try {
                        out.appendCodePoint(Integer.parseInt(hex, 16));
                    } catch (NumberFormatException e) {
                        throw new ParseException(lineNumber, "非法的 Unicode 转义：\\" + escape + hex);
                    }
                    i += length;
                }
                default -> throw new ParseException(lineNumber, "不支持的转义字符：\\" + escape);
            }
        }
        return out.toString();
    }

    /** Escapes a Java string into a TOML basic string body (without the quotes). */
    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20 || c == 0x7F) {
                        out.append(String.format("\\u%04X", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
