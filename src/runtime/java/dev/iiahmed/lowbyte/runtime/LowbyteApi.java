package dev.iiahmed.lowbyte.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Replacements for JDK calls that cannot be rebuilt inline.
 * <p>
 * Written in Java and compiled at release 8, so these are ordinary methods
 * rather than hand-emitted bytecode. That is the point: anything needing a loop,
 * a decoder or a helper type is writable here and simply is not writable as a
 * generated method body with hand-written stack frames.
 * <p>
 * Only the methods a jar actually uses are injected into it, and the annotations
 * are stripped on the way. Simple calls with a one-instruction equivalent, like
 * {@code Optional.isEmpty}, are still rewritten inline and are deliberately
 * absent here.
 */
public final class LowbyteApi {

    private LowbyteApi() {
    }

    /**
     * {@code String.isBlank()}.
     * <p>
     * Not {@code trim().isEmpty()}: {@code trim} cuts everything below U+0020
     * and nothing above it, so the two disagree on U+00A0, which is not
     * whitespace, and on U+2028, which is.
     */
    @LowbyteInfo(
            owner = "java/lang/String", name = "isBlank",
            descriptor = "()Z", introducedIn = 11, instance = true
    )
    public static boolean isBlank(String value) {
        return value.codePoints().allMatch(Character::isWhitespace);
    }

    /**
     * {@code String.strip()}, by {@code Character.isWhitespace} rather than by code point value.
     */
    @LowbyteInfo(
            owner = "java/lang/String", name = "strip",
            descriptor = "()Ljava/lang/String;", introducedIn = 11, instance = true
    )
    public static String strip(String value) {
        int start = firstNonWhitespace(value);
        int end = lastNonWhitespace(value);
        // The two scans are independent, so on an all-whitespace string they
        // cross: " " gives start 1 and end 0. Everything was whitespace.
        return start >= end ? "" : value.substring(start, end);
    }

    /**
     * {@code String.stripLeading()}, the front half of {@link #strip}.
     */
    @LowbyteInfo(
            owner = "java/lang/String", name = "stripLeading",
            descriptor = "()Ljava/lang/String;", introducedIn = 11, instance = true
    )
    public static String stripLeading(String value) {
        return value.substring(firstNonWhitespace(value));
    }

    /**
     * {@code String.stripTrailing()}, the back half.
     */
    @LowbyteInfo(
            owner = "java/lang/String", name = "stripTrailing",
            descriptor = "()Ljava/lang/String;", introducedIn = 11, instance = true
    )
    public static String stripTrailing(String value) {
        return value.substring(0, lastNonWhitespace(value));
    }

    /** Index of the first character that is not whitespace, or the length. */
    private static int firstNonWhitespace(String value) {
        int start = 0;
        while (start < value.length() && Character.isWhitespace(value.charAt(start))) start++;
        return start;
    }

    /** Index just past the last character that is not whitespace, or zero. */
    private static int lastNonWhitespace(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) end--;
        return end;
    }

    /**
     * {@code String.repeat(int)}.
     * <p>
     * A loop, which is exactly the sort of thing that belongs here rather than
     * in hand-emitted bytecode.
     */
    @LowbyteInfo(
            owner = "java/lang/String", name = "repeat",
            descriptor = "(I)Ljava/lang/String;", introducedIn = 11, instance = true
    )
    public static String repeat(String value, int count) {
        if (count < 0) throw new IllegalArgumentException("count is negative: " + count);
        StringBuilder out = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }

    /**
     * {@code String.lines()}.
     * <p>
     * Not {@code split("\n")}, which keeps carriage returns, and not
     * {@code split("\R")} either, which drops every trailing empty line where
     * this drops only the one implied by a final terminator.
     */
    @LowbyteInfo(
            owner = "java/lang/String", name = "lines",
            descriptor = "()Ljava/util/stream/Stream;", introducedIn = 11, instance = true
    )
    public static Stream<String> lines(String value) {
        return splitLines(value).stream();
    }

    /**
     * The lines of a string, by the three terminators the JDK recognises.
     * <p>
     * A terminator ends a line rather than starting one, so a trailing terminator
     * adds no line and an empty string has none at all.
     */
    private static List<String> splitLines(String value) {
        List<String> lines = new ArrayList<>();
        int length = value.length();
        int start = 0;
        while (start < length) {
            int end = start;
            while (end < length && value.charAt(end) != '\n' && value.charAt(end) != '\r') end++;
            lines.add(value.substring(start, end));
            if (end == length) break;
            // CR LF is one terminator, not two.
            boolean pair = value.charAt(end) == '\r' && end + 1 < length && value.charAt(end + 1) == '\n';
            start = end + (pair ? 2 : 1);
        }
        return lines;
    }

    /**
     * {@code String.indent(int)}, which also normalises the line terminators and
     * gives every line a trailing line feed.
     */
    @LowbyteInfo(
            owner = "java/lang/String", name = "indent",
            descriptor = "(I)Ljava/lang/String;", introducedIn = 12, instance = true
    )
    public static String indent(String value, int n) {
        if (value.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String line : splitLines(value)) {
            if (n > 0) {
                for (int i = 0; i < n; i++) out.append(' ');
                out.append(line);
            } else if (n < 0) {
                int available = firstNonWhitespace(line);
                // Negating Integer.MIN_VALUE stays negative, and the JDK spells
                // that case out as stripLeading rather than letting it wrap.
                int drop = n == Integer.MIN_VALUE ? available : Math.min(-n, available);
                out.append(line, drop, line.length());
            } else {
                out.append(line);
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * {@code String.stripIndent()}.
     * <p>
     * Java 13 had this deprecated for removal, Java 14 had it behind preview, and
     * Java 15 settled it. Thirteen is the lowest release where it exists at
     * all, so that is where replacing it stops.
     */
    @LowbyteInfo(
            owner = "java/lang/String", name = "stripIndent",
            descriptor = "()Ljava/lang/String;", introducedIn = 13, instance = true
    )
    public static String stripIndent(String value) {
        int length = value.length();
        if (length == 0) return "";

        // A string already ending in a terminator opts out of the common-prefix
        // calculation, and keeps that terminator.
        char last = value.charAt(length - 1);
        boolean optOut = last == '\n' || last == '\r';

        List<String> lines = splitLines(value);
        int outdent = optOut ? 0 : commonIndent(lines);

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append('\n');
            String line = lines.get(i);
            int first = firstNonWhitespace(line);
            int end = lastNonWhitespace(line);
            // A blank line contributes nothing but its terminator.
            if (first < end) out.append(line, Math.min(outdent, first), end);
        }
        if (optOut) out.append('\n');
        return out.toString();
    }

    /** The incidental indentation shared by every non-blank line. */
    private static int commonIndent(List<String> lines) {
        int outdent = Integer.MAX_VALUE;
        for (String line : lines) {
            int leading = firstNonWhitespace(line);
            if (leading != line.length()) outdent = Math.min(outdent, leading);
        }
        // A blank closing line is the one blank line that counts, since it is
        // where the closing delimiter of a text block sat.
        String closing = lines.get(lines.size() - 1);
        if (isBlank(closing)) outdent = Math.min(outdent, closing.length());
        return outdent;
    }

    /**
     * {@code String.transform(Function)}, which is the function applied to the
     * receiver and nothing more.
     */
    @LowbyteInfo(
            owner = "java/lang/String", name = "transform",
            descriptor = "(Ljava/util/function/Function;)Ljava/lang/Object;",
            introducedIn = 12, instance = true
    )
    public static <R> R transform(String value, Function<? super String, ? extends R> function) {
        return function.apply(value);
    }

    /**
     * {@code String.formatted(Object...)}, which is {@code String.format} with
     * the receiver as the format, default locale and all.
     */
    @LowbyteInfo(
            owner = "java/lang/String", name = "formatted", descriptor = "([Ljava/lang/Object;)Ljava/lang/String;",
            introducedIn = 13, instance = true
    )
    public static String formatted(String value, Object... arguments) {
        return String.format(value, arguments);
    }

    /**
     * {@code String.translateEscapes()}, the escapes a Java source literal has,
     * which is not the set any of the older helpers cover.
     */
    @LowbyteInfo(
            owner = "java/lang/String", name = "translateEscapes", descriptor = "()Ljava/lang/String;",
            introducedIn = 13, instance = true
    )
    public static String translateEscapes(String value) {
        if (value.isEmpty()) return "";
        char[] chars = value.toCharArray();
        int length = chars.length;
        int from = 0;
        int to = 0;
        while (from < length) {
            char ch = chars[from++];
            if (ch == '\\') {
                // A trailing backslash reads as NUL, which is not the digit '0'
                // and so falls to the default and is refused.
                ch = from < length ? chars[from++] : '\0';
                switch (ch) {
                    case 'b': ch = '\b'; break;
                    case 'f': ch = '\f'; break;
                    case 'n': ch = '\n'; break;
                    case 'r': ch = '\r'; break;
                    case 's': ch = ' '; break;
                    case 't': ch = '\t'; break;
                    case '\'':
                    case '"':
                    case '\\':
                        break;
                    case '0': case '1': case '2': case '3':
                    case '4': case '5': case '6': case '7': {
                        // Three octal digits only when the first is 3 or less,
                        // which is what keeps the value inside a byte.
                        int limit = Math.min(from + (ch <= '3' ? 2 : 1), length);
                        int code = ch - '0';
                        while (from < limit) {
                            ch = chars[from];
                            if (ch < '0' || '7' < ch) break;
                            from++;
                            code = (code << 3) | (ch - '0');
                        }
                        ch = (char) code;
                        break;
                    }
                    case '\n':
                        continue;
                    case '\r':
                        if (from < length && chars[from] == '\n') from++;
                        continue;
                    default:
                        throw new IllegalArgumentException(
                                String.format("Invalid escape sequence: \\%c \\\\u%04X", ch, (int) ch));
                }
            }
            chars[to++] = ch;
        }
        return new String(chars, 0, to);
    }

    /**
     * {@code Objects.requireNonNullElse}.
     * <p>
     * The default is null-checked too, under the same parameter name the JDK
     * uses in its message, so both being null reads the same either way.
     */
    @LowbyteInfo(
            owner = "java/util/Objects", name = "requireNonNullElse", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            introducedIn = 9
    )
    public static <T> T requireNonNullElse(T value, T defaultValue) {
        return value != null ? value : Objects.requireNonNull(defaultValue, "defaultObj");
    }

    /**
     * {@code Map.of}, one overload per arity as the JDK has them.
     * <p>
     * Nulls and a repeated key are refused, exactly as the factory does. The
     * iteration order is documented as unspecified and the JDK randomises it per
     * run; insertion order is inside that contract, it simply does not shuffle.
     */
    @LowbyteInfo(owner = "java/util/Map", name = "of", descriptor = "()Ljava/util/Map;", introducedIn = 9)
    public static <K, V> Map<K, V> mapOf() {
        return newMap();
    }

    @LowbyteInfo(owner = "java/util/Map", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;", introducedIn = 9)
    public static <K, V> Map<K, V> mapOf(K k1, V v1) {
        return newMap(k1, v1);
    }

    @LowbyteInfo(owner = "java/util/Map", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;", introducedIn = 9)
    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2) {
        return newMap(k1, v1, k2, v2);
    }

    @LowbyteInfo(owner = "java/util/Map", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;", introducedIn = 9)
    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3) {
        return newMap(k1, v1, k2, v2, k3, v3);
    }

    @LowbyteInfo(owner = "java/util/Map", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;", introducedIn = 9)
    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
        return newMap(k1, v1, k2, v2, k3, v3, k4, v4);
    }

    @LowbyteInfo(owner = "java/util/Map", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;", introducedIn = 9)
    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5) {
        return newMap(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5);
    }

    @LowbyteInfo(owner = "java/util/Map", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;", introducedIn = 9)
    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6) {
        return newMap(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6);
    }

    @LowbyteInfo(owner = "java/util/Map", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;", introducedIn = 9)
    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7) {
        return newMap(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7);
    }

    @LowbyteInfo(owner = "java/util/Map", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;", introducedIn = 9)
    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7, K k8, V v8) {
        return newMap(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8);
    }

    @LowbyteInfo(owner = "java/util/Map", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;", introducedIn = 9)
    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7, K k8, V v8, K k9, V v9) {
        return newMap(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9);
    }

    @LowbyteInfo(owner = "java/util/Map", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;", introducedIn = 9)
    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7, K k8, V v8, K k9, V v9, K k10, V v10) {
        return newMap(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10);
    }

    /**
     * {@code Map.ofEntries}, which is where {@code Map.of} stops.
     * <p>
     * A null entry is refused before its key and value are, so passing a null
     * entry reads as one rather than as a null key.
     */
    @LowbyteInfo(
            owner = "java/util/Map", name = "ofEntries",
            descriptor = "([Ljava/util/Map$Entry;)Ljava/util/Map;", introducedIn = 9
    )
    public static <K, V> Map<K, V> ofEntries(Map.Entry<? extends K, ? extends V>[] entries) {
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        for (Map.Entry<? extends K, ? extends V> entry : Objects.requireNonNull(entries)) {
            Objects.requireNonNull(entry);
            Object key = Objects.requireNonNull(entry.getKey());
            Object value = Objects.requireNonNull(entry.getValue());
            if (map.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate key: " + key);
            }
        }
        return unmodifiable(map);
    }

    /**
     * {@code Map.copyOf}, a snapshot with every key and value checked.
     * <p>
     * Not {@code ofEntries} over the entry set: a map cannot hold a repeated
     * key, so there is nothing here to refuse as a duplicate.
     */
    @LowbyteInfo(
            owner = "java/util/Map", name = "copyOf",
            descriptor = "(Ljava/util/Map;)Ljava/util/Map;", introducedIn = 10
    )
    public static <K, V> Map<K, V> copyOf(Map<? extends K, ? extends V> source) {
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : Objects.requireNonNull(source).entrySet()) {
            map.put(Objects.requireNonNull(entry.getKey()), Objects.requireNonNull(entry.getValue()));
        }
        return unmodifiable(map);
    }

    /** Shared by every {@code mapOf} arity. */
    private static <K, V> Map<K, V> newMap(Object... keysAndValues) {
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            Object key = Objects.requireNonNull(keysAndValues[i]);
            Object value = Objects.requireNonNull(keysAndValues[i + 1]);
            if (map.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate key: " + key);
            }
        }
        return unmodifiable(map);
    }

    /** The one cast, in the one place, rather than at each of the three callers. */
    private static <K, V> Map<K, V> unmodifiable(LinkedHashMap<Object, Object> map) {
        @SuppressWarnings("unchecked")
        Map<K, V> result = (Map<K, V>) Collections.unmodifiableMap(map);
        return result;
    }
}
