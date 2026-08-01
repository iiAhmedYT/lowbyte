package dev.iiahmed.lowbyte.runtime;

import java.io.Reader;
import java.io.Writer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

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
            descriptor = "()Z", introducedIn = 11
    )
    public static boolean isBlank(String value) {
        for (int i = 0, len = value.length(); i < len; i++) {
            if (!Character.isWhitespace(value.charAt(i))) return false;
        }
        return true;
    }

    /**
     * {@code String.strip()}, by {@code Character.isWhitespace} rather than by code point value.
     */
    @LowbyteInfo(
            owner = "java/lang/String", name = "strip",
            descriptor = "()Ljava/lang/String;", introducedIn = 11
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
            descriptor = "()Ljava/lang/String;", introducedIn = 11
    )
    public static String stripLeading(String value) {
        return value.substring(firstNonWhitespace(value));
    }

    /**
     * {@code String.stripTrailing()}, the back half.
     */
    @LowbyteInfo(
            owner = "java/lang/String", name = "stripTrailing",
            descriptor = "()Ljava/lang/String;", introducedIn = 11
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
            descriptor = "(I)Ljava/lang/String;", introducedIn = 11
    )
    public static String repeat(String value, int count) {
        if (count < 0) throw new IllegalArgumentException("count is negative: " + count);
        if (count == 1) return value;
        int length = value.length();
        if (length == 0 || count == 0) return "";
        if (Integer.MAX_VALUE / count < length) {
            throw new OutOfMemoryError("Required length exceeds implementation limit");
        }
        char[] out = new char[length * count];
        value.getChars(0, length, out, 0);
        int copied = length;
        int total = out.length;
        for (; copied <= total - copied; copied <<= 1) {
            System.arraycopy(out, 0, out, copied, copied);
        }
        System.arraycopy(out, 0, out, copied, total - copied);
        return new String(out);
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
            descriptor = "()Ljava/util/stream/Stream;", introducedIn = 11
    )
    public static Stream<String> lines(String value) {
        // Lazy, as the JDK's is, so findFirst on a large string scans one line
        // rather than all of them. The characteristics are the ones the JDK
        // reports too, so a downstream operation that asks makes the same
        // decisions. The Spliterator is a nested class and travels with this one.
        return StreamSupport.stream(new LineSpliterator(value), false);
    }

    /** One line per {@link #tryAdvance}, by the same rules {@link #splitLines} uses. */
    private static final class LineSpliterator extends Spliterators.AbstractSpliterator<String> {

        private final String value;
        private int start;

        LineSpliterator(String value) {
            // Not SIZED: counting the lines up front is the scan being avoided.
            super(value.length() + 1L,
                    Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.IMMUTABLE);
            this.value = value;
        }

        @Override
        public boolean tryAdvance(Consumer<? super String> action) {
            int length = value.length();
            if (start >= length) return false;

            int end = lineEnd(value, start);
            action.accept(value.substring(start, end));
            start = end == length ? end : nextLineStart(value, end);
            return true;
        }
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
            int end = lineEnd(value, start);
            lines.add(value.substring(start, end));
            if (end == length) break;
            start = nextLineStart(value, end);
        }
        return lines;
    }

    /**
     * Where the line starting at {@code start} ends.
     * <p>
     * Shared with {@link #lines}, so the eager and lazy walks cannot come to
     * different conclusions about where a line stops.
     */
    static int lineEnd(String value, int start) {
        int end = start;
        int length = value.length();
        while (end < length && value.charAt(end) != '\n' && value.charAt(end) != '\r') end++;
        return end;
    }

    /** Where the next line starts, given a terminator at {@code end}. */
    static int nextLineStart(String value, int end) {
        // CR LF is one terminator, not two.
        boolean pair = value.charAt(end) == '\r'
                && end + 1 < value.length() && value.charAt(end + 1) == '\n';
        return end + (pair ? 2 : 1);
    }

    /**
     * {@code String.indent(int)}, which also normalises the line terminators and
     * gives every line a trailing line feed.
     */
    @LowbyteInfo(
            owner = "java/lang/String", name = "indent",
            descriptor = "(I)Ljava/lang/String;", introducedIn = 12
    )
    public static String indent(String value, int n) {
        if (value.isEmpty()) return "";
        List<String> lines = splitLines(value);
        // Every line keeps its content and gains a line feed, plus n spaces when
        // n is positive. A negative n only removes, so this stays an upper bound
        // either way and the builder never grows.
        int padding = Math.max(n, 0);
        StringBuilder out = new StringBuilder(value.length() + lines.size() * (padding + 1));
        for (String line : lines) {
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
            descriptor = "()Ljava/lang/String;", introducedIn = 13
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

        // Only ever removes, so the input length is an upper bound.
        StringBuilder out = new StringBuilder(length);
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
            introducedIn = 12
    )
    public static <R> R transform(String value, Function<? super String, ? extends R> function) {
        return function.apply(value);
    }

    /**
     * {@code String.formatted(Object...)}, which is {@code String.format} with
     * the receiver as the format, default locale and all.
     */
    @LowbyteInfo(
            owner = "java/lang/String", name = "formatted",
            descriptor = "([Ljava/lang/Object;)Ljava/lang/String;", introducedIn = 13
    )
    public static String formatted(String value, Object... arguments) {
        return String.format(value, arguments);
    }

    /**
     * {@code String.translateEscapes()}, the escapes a Java source literal has,
     * which is not the set any of the older helpers cover.
     */
    @LowbyteInfo(
            owner = "java/lang/String", name = "translateEscapes",
            descriptor = "()Ljava/lang/String;", introducedIn = 13
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
     * {@code Collectors.toUnmodifiableList()}.
     * <p>
     * Not {@code collectingAndThen(toList(), Collections::unmodifiableList)} on
     * its own, however close that looks: {@code toList} takes a null element
     * happily and this refuses one, so the check has to be put back.
     */
    @LowbyteInfo(
            owner = "java/util/stream/Collectors", name = "toUnmodifiableList",
            descriptor = "()Ljava/util/stream/Collector;", introducedIn = 10
    )
    public static <T> Collector<T, ?, List<T>> toUnmodifiableList() {
        return Collectors.collectingAndThen(Collectors.<T>toList(), LowbyteApi::sealedList);
    }

    /** {@code Collectors.toUnmodifiableSet()}, the same trade as {@link #toUnmodifiableList}. */
    @LowbyteInfo(
            owner = "java/util/stream/Collectors", name = "toUnmodifiableSet",
            descriptor = "()Ljava/util/stream/Collector;", introducedIn = 10
    )
    public static <T> Collector<T, ?, Set<T>> toUnmodifiableSet() {
        return Collectors.collectingAndThen(Collectors.<T>toSet(), LowbyteApi::sealedSet);
    }

    /**
     * {@code Collectors.toUnmodifiableMap(keyMapper, valueMapper)}.
     * <p>
     * Not built on {@code Collectors.toMap}. That reports a repeated key too,
     * but on Java 8 its message names the value rather than the key
     * (JDK-8040892, fixed in 9), and a Java 8 runtime is the whole point of this
     * class. So the accumulator is written out to get the wording right.
     */
    @LowbyteInfo(owner = "java/util/stream/Collectors", name = "toUnmodifiableMap",
            descriptor = "(Ljava/util/function/Function;Ljava/util/function/Function;)Ljava/util/stream/Collector;",
            introducedIn = 10)
    public static <T, K, U> Collector<T, ?, Map<K, U>> toUnmodifiableMap(
            Function<? super T, ? extends K> keyMapper,
            Function<? super T, ? extends U> valueMapper
    ) {
        Objects.requireNonNull(keyMapper, "keyMapper");
        Objects.requireNonNull(valueMapper, "valueMapper");

        Supplier<Map<K, U>> supplier = LinkedHashMap::new;
        BiConsumer<Map<K, U>, T> accumulator = (map, element) -> {
            K key = keyMapper.apply(element);
            U value = Objects.requireNonNull(valueMapper.apply(element));
            U existing = map.putIfAbsent(key, value);
            if (existing != null) throw duplicateKey(key, existing, value);
        };
        BinaryOperator<Map<K, U>> combiner = (left, right) -> {
            for (Map.Entry<K, U> entry : right.entrySet()) {
                U existing = left.putIfAbsent(entry.getKey(), entry.getValue());
                if (existing != null) throw duplicateKey(entry.getKey(), existing, entry.getValue());
            }
            return left;
        };
        // A null key survives putIfAbsent and is caught by the finisher, which
        // is where the JDK catches it too.
        Function<Map<K, U>, Map<K, U>> finisher = LowbyteApi::sealedMap;
        return Collector.of(supplier, accumulator, combiner, finisher);
    }

    private static IllegalStateException duplicateKey(Object key, Object existing, Object added) {
        return new IllegalStateException(
                "Duplicate key " + key + " (attempted merging values " + existing + " and " + added + ")");
    }

    /** {@code Collectors.toUnmodifiableMap} with a merge function, which is where a repeated key stops being an error. */
    @LowbyteInfo(
            owner = "java/util/stream/Collectors", name = "toUnmodifiableMap", introducedIn = 10,
            descriptor = "(Ljava/util/function/Function;Ljava/util/function/Function;Ljava/util/function/BinaryOperator;)Ljava/util/stream/Collector;"
    )
    public static <T, K, U> Collector<T, ?, Map<K, U>> toUnmodifiableMap(
            Function<? super T, ? extends K> keyMapper,
            Function<? super T, ? extends U> valueMapper,
            BinaryOperator<U> mergeFunction) {
        // Refused when the collector is built rather than when it is used, and
        // named, which is where and how the JDK refuses them too.
        Objects.requireNonNull(keyMapper, "keyMapper");
        Objects.requireNonNull(valueMapper, "valueMapper");
        Objects.requireNonNull(mergeFunction, "mergeFunction");
        Collector<T, ?, Map<K, U>> collected = Collectors.toMap(keyMapper, valueMapper, mergeFunction);
        return Collectors.collectingAndThen(collected, LowbyteApi::sealedMap);
    }

    /**
     * Refuses nulls once at the end rather than wrapping every element.
     * <p>
     * A finisher runs on the finished collection, so this costs one pass instead
     * of an extra function call per element on the way in. What it refuses is the
     * same either way.
     */
    private static <T> List<T> sealedList(List<T> values) {
        for (T value : values) Objects.requireNonNull(value);
        return Collections.unmodifiableList(values);
    }

    private static <T> Set<T> sealedSet(Set<T> values) {
        for (T value : values) Objects.requireNonNull(value);
        return Collections.unmodifiableSet(values);
    }

    private static <K, V> Map<K, V> sealedMap(Map<K, V> values) {
        for (Map.Entry<K, V> entry : values.entrySet()) {
            Objects.requireNonNull(entry.getKey());
            Objects.requireNonNull(entry.getValue());
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * {@code Objects.checkIndex}.
     * <p>
     * Returns the index it was given, so it reads as part of an expression. The
     * message is the JDK's own wording, since a caller matching on it is matching
     * on something the JDK documents by example.
     */
    @LowbyteInfo(owner = "java/util/Objects", name = "checkIndex", descriptor = "(II)I", introducedIn = 9)
    public static int checkIndex(int index, int length) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + length);
        }
        return index;
    }

    /** {@code Objects.checkFromToIndex}, a half-open range, so {@code to} may equal {@code length}. */
    @LowbyteInfo(owner = "java/util/Objects", name = "checkFromToIndex", descriptor = "(III)I", introducedIn = 9)
    public static int checkFromToIndex(int fromIndex, int toIndex, int length) {
        if (fromIndex < 0 || fromIndex > toIndex || toIndex > length) {
            throw new IndexOutOfBoundsException(
                    "Range [" + fromIndex + ", " + toIndex + ") out of bounds for length " + length);
        }
        return fromIndex;
    }

    /** {@code Objects.checkFromIndexSize}, the same range given as a start and a count. */
    @LowbyteInfo(owner = "java/util/Objects", name = "checkFromIndexSize", descriptor = "(III)I", introducedIn = 9)
    public static int checkFromIndexSize(int fromIndex, int size, int length) {
        // One test for all three being non-negative, and then a comparison that
        // cannot overflow the way fromIndex + size can. Both are how the JDK
        // writes it, and the overflow one is not a detail worth rediscovering.
        if ((length | fromIndex | size) < 0 || size > length - fromIndex) {
            throw new IndexOutOfBoundsException(
                    "Range [" + fromIndex + ", " + fromIndex + " + " + size
                            + ") out of bounds for length " + length);
        }
        return fromIndex;
    }

    /**
     * {@code Files.readString(Path)}, which is UTF-8.
     */
    @LowbyteInfo(
            owner = "java/nio/file/Files", name = "readString", introducedIn = 11,
            descriptor = "(Ljava/nio/file/Path;)Ljava/lang/String;"
    )
    public static String readString(Path path) throws IOException {
        return readString(path, StandardCharsets.UTF_8);
    }

    /**
     * {@code Files.readString(Path, Charset)}.
     * <p>
     * Not {@code new String(bytes, charset)}. That substitutes U+FFFD for input
     * the charset cannot decode, where this throws {@code MalformedInputException},
     * so the difference between a corrupt file and a valid one would disappear.
     */
    @LowbyteInfo(
            owner = "java/nio/file/Files", name = "readString", introducedIn = 11,
            descriptor = "(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)Ljava/lang/String;"
    )
    public static String readString(Path path, Charset charset) throws IOException {
        Objects.requireNonNull(path);
        Objects.requireNonNull(charset);

        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString();
    }

    /** {@code Files.writeString(Path, CharSequence, OpenOption...)}, which is UTF-8. */
    @LowbyteInfo(
            owner = "java/nio/file/Files", name = "writeString", introducedIn = 11,
            descriptor = "(Ljava/nio/file/Path;Ljava/lang/CharSequence;[Ljava/nio/file/OpenOption;)Ljava/nio/file/Path;"
    )
    public static Path writeString(Path path, CharSequence text, OpenOption... options) throws IOException {
        return writeString(path, text, StandardCharsets.UTF_8, options);
    }

    /**
     * {@code Files.writeString(Path, CharSequence, Charset, OpenOption...)}.
     * <p>
     * Not {@code text.toString().getBytes(charset)}, for the same reason as
     * {@link #readString}: that writes a question mark for anything the charset
     * cannot represent, where this throws {@code UnmappableCharacterException}.
     */
    @LowbyteInfo(
            owner = "java/nio/file/Files", name = "writeString", introducedIn = 11,
            descriptor = "(Ljava/nio/file/Path;Ljava/lang/CharSequence;Ljava/nio/charset/Charset;[Ljava/nio/file/OpenOption;)Ljava/nio/file/Path;"
    )
    public static Path writeString(Path path, CharSequence text, Charset charset, OpenOption... options)
            throws IOException {
        Objects.requireNonNull(path);
        Objects.requireNonNull(text);
        Objects.requireNonNull(charset);

        CharsetEncoder encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer encoded = encoder.encode(CharBuffer.wrap(text));

        // The buffer's backing array is usually longer than what was written.
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return Files.write(path, bytes, options);
    }

    /**
     * {@code Files.mismatch}, the index of the first differing byte or -1.
     * <p>
     * Compared a block at a time rather than by reading both files in whole, so
     * mismatching two very large files costs what the JDK charges rather than
     * their combined size in heap.
     */
    @LowbyteInfo(
            owner = "java/nio/file/Files", name = "mismatch",
            descriptor = "(Ljava/nio/file/Path;Ljava/nio/file/Path;)J", introducedIn = 12
    )
    public static long mismatch(Path left, Path right) throws IOException {
        if (Files.isSameFile(left, right)) return -1L;

        byte[] leftBlock = new byte[8192];
        byte[] rightBlock = new byte[8192];
        long position = 0L;

        try (InputStream leftStream = Files.newInputStream(left)) {
            try (InputStream rightStream = Files.newInputStream(right)) {
                while (true) {
                    int leftRead = fill(leftStream, leftBlock);
                    int rightRead = fill(rightStream, rightBlock);

                    int shared = Math.min(leftRead, rightRead);
                    for (int i = 0; i < shared; i++) {
                        if (leftBlock[i] != rightBlock[i]) return position + i;
                    }
                    // One ran out first, so the shorter file is a prefix of the
                    // other and the mismatch is where it ended.
                    if (leftRead != rightRead) return position + shared;
                    if (leftRead == 0) return -1L;
                    position += leftRead;
                }
            }
        }
    }

    /** Reads until the block is full or the stream ends, since read may return early. */
    private static int fill(InputStream stream, byte[] block) throws IOException {
        int total = 0;
        while (total < block.length) {
            int read = stream.read(block, total, block.length - total);
            if (read < 0) break;
            total += read;
        }
        return total;
    }

    /**
     * {@code Reader.transferTo}, a loop over {@code read} and {@code write}.
     * <p>
     * {@code Reader} is not final, so forwarding to a static method here loses
     * virtual dispatch and an override would be bypassed. Safe in practice
     * because no JDK {@code Reader} declares its own: the method is declared on
     * {@code Reader} and inherited everywhere, unlike
     * {@code InputStream.readAllBytes}, which {@code ByteArrayInputStream} and
     * {@code FileInputStream} both override and which is therefore reported
     * rather than rewritten.
     */
    @LowbyteInfo(owner = "java/io/Reader", name = "transferTo",
            descriptor = "(Ljava/io/Writer;)J", introducedIn = 10)
    public static long transferTo(Reader reader, Writer out) throws IOException {
        Objects.requireNonNull(out, "out");

        char[] buffer = new char[8192];
        long transferred = 0L;
        int read;
        while ((read = reader.read(buffer, 0, buffer.length)) >= 0) {
            out.write(buffer, 0, read);
            transferred += read;
        }
        return transferred;
    }

    /**
     * {@code Objects.requireNonNullElse}.
     * <p>
     * The default is null-checked too, under the same parameter name the JDK
     * uses in its message, so both being null reads the same either way.
     */
    @LowbyteInfo(
            owner = "java/util/Objects", name = "requireNonNullElse", introducedIn = 9,
            descriptor = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
    )
    public static <T> T requireNonNullElse(T value, T defaultValue) {
        return value != null ? value : Objects.requireNonNull(defaultValue, "defaultObj");
    }

    /**
     * {@code List.of}, one overload per arity as the JDK has them.
     * <p>
     * Nulls are refused, exactly as the factory does. An unmodifiable view over
     * an {@code ArrayList} nobody else holds is observably the same thing: it
     * refuses mutation, keeps its order, and equals an equivalent list.
     */
    @LowbyteInfo(owner = "java/util/List", name = "of", descriptor = "()Ljava/util/List;", introducedIn = 9)
    public static <E> List<E> listOf() {
        return newList();
    }

    @LowbyteInfo(owner = "java/util/List", name = "of", descriptor = "(Ljava/lang/Object;)Ljava/util/List;", introducedIn = 9)
    public static <E> List<E> listOf(E e1) {
        return newList(e1);
    }

    @LowbyteInfo(owner = "java/util/List", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;", introducedIn = 9)
    public static <E> List<E> listOf(E e1, E e2) {
        return newList(e1, e2);
    }

    @LowbyteInfo(owner = "java/util/List", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;", introducedIn = 9)
    public static <E> List<E> listOf(E e1, E e2, E e3) {
        return newList(e1, e2, e3);
    }

    @LowbyteInfo(owner = "java/util/List", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;", introducedIn = 9)
    public static <E> List<E> listOf(E e1, E e2, E e3, E e4) {
        return newList(e1, e2, e3, e4);
    }

    @LowbyteInfo(owner = "java/util/List", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;", introducedIn = 9)
    public static <E> List<E> listOf(E e1, E e2, E e3, E e4, E e5) {
        return newList(e1, e2, e3, e4, e5);
    }

    @LowbyteInfo(owner = "java/util/List", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;", introducedIn = 9)
    public static <E> List<E> listOf(E e1, E e2, E e3, E e4, E e5, E e6) {
        return newList(e1, e2, e3, e4, e5, e6);
    }

    @LowbyteInfo(owner = "java/util/List", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;", introducedIn = 9)
    public static <E> List<E> listOf(E e1, E e2, E e3, E e4, E e5, E e6, E e7) {
        return newList(e1, e2, e3, e4, e5, e6, e7);
    }

    @LowbyteInfo(owner = "java/util/List", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;", introducedIn = 9)
    public static <E> List<E> listOf(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8) {
        return newList(e1, e2, e3, e4, e5, e6, e7, e8);
    }

    @LowbyteInfo(owner = "java/util/List", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;", introducedIn = 9)
    public static <E> List<E> listOf(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8, E e9) {
        return newList(e1, e2, e3, e4, e5, e6, e7, e8, e9);
    }

    @LowbyteInfo(owner = "java/util/List", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;", introducedIn = 9)
    public static <E> List<E> listOf(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8, E e9, E e10) {
        return newList(e1, e2, e3, e4, e5, e6, e7, e8, e9, e10);
    }

    /** Past ten elements javac calls the varargs overload, which arrives as one array. */
    @LowbyteInfo(owner = "java/util/List", name = "of", descriptor = "([Ljava/lang/Object;)Ljava/util/List;", introducedIn = 9)
    public static <E> List<E> listOf(E[] values) {
        return newList((Object[]) values);
    }

    /**
     * {@code Set.of}, which on top of refusing nulls refuses a repeated element.
     * <p>
     * Iteration order is documented as unspecified and the JDK randomises it per
     * run. Insertion order stays inside that contract and simply does not shuffle.
     */
    @LowbyteInfo(owner = "java/util/Set", name = "of", descriptor = "()Ljava/util/Set;", introducedIn = 9)
    public static <E> Set<E> setOf() {
        return newSet();
    }

    @LowbyteInfo(owner = "java/util/Set", name = "of", descriptor = "(Ljava/lang/Object;)Ljava/util/Set;", introducedIn = 9)
    public static <E> Set<E> setOf(E e1) {
        return newSet(e1);
    }

    @LowbyteInfo(owner = "java/util/Set", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Set;", introducedIn = 9)
    public static <E> Set<E> setOf(E e1, E e2) {
        return newSet(e1, e2);
    }

    @LowbyteInfo(owner = "java/util/Set", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Set;", introducedIn = 9)
    public static <E> Set<E> setOf(E e1, E e2, E e3) {
        return newSet(e1, e2, e3);
    }

    @LowbyteInfo(owner = "java/util/Set", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Set;", introducedIn = 9)
    public static <E> Set<E> setOf(E e1, E e2, E e3, E e4) {
        return newSet(e1, e2, e3, e4);
    }

    @LowbyteInfo(owner = "java/util/Set", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Set;", introducedIn = 9)
    public static <E> Set<E> setOf(E e1, E e2, E e3, E e4, E e5) {
        return newSet(e1, e2, e3, e4, e5);
    }

    @LowbyteInfo(owner = "java/util/Set", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Set;", introducedIn = 9)
    public static <E> Set<E> setOf(E e1, E e2, E e3, E e4, E e5, E e6) {
        return newSet(e1, e2, e3, e4, e5, e6);
    }

    @LowbyteInfo(owner = "java/util/Set", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Set;", introducedIn = 9)
    public static <E> Set<E> setOf(E e1, E e2, E e3, E e4, E e5, E e6, E e7) {
        return newSet(e1, e2, e3, e4, e5, e6, e7);
    }

    @LowbyteInfo(owner = "java/util/Set", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Set;", introducedIn = 9)
    public static <E> Set<E> setOf(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8) {
        return newSet(e1, e2, e3, e4, e5, e6, e7, e8);
    }

    @LowbyteInfo(owner = "java/util/Set", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Set;", introducedIn = 9)
    public static <E> Set<E> setOf(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8, E e9) {
        return newSet(e1, e2, e3, e4, e5, e6, e7, e8, e9);
    }

    @LowbyteInfo(owner = "java/util/Set", name = "of", descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Set;", introducedIn = 9)
    public static <E> Set<E> setOf(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8, E e9, E e10) {
        return newSet(e1, e2, e3, e4, e5, e6, e7, e8, e9, e10);
    }

    /** Past ten elements javac calls the varargs overload, which arrives as one array. */
    @LowbyteInfo(owner = "java/util/Set", name = "of", descriptor = "([Ljava/lang/Object;)Ljava/util/Set;", introducedIn = 9)
    public static <E> Set<E> setOf(E[] values) {
        return newSet((Object[]) values);
    }

    /** {@code List.copyOf}, a snapshot refusing a null collection and null elements. */
    @LowbyteInfo(owner = "java/util/List", name = "copyOf",
            descriptor = "(Ljava/util/Collection;)Ljava/util/List;", introducedIn = 10)
    public static <E> List<E> listCopyOf(Collection<? extends E> source) {
        // toArray is contractually a fresh array, but copyOf takes any
        // Collection and one that ignores that clause hands back an array it
        // still holds. Then the null check does not stick: the source mutates a
        // slot afterwards and the "immutable" list has a null in it. The JDK
        // copies for the same reason, its comment naming the race as TOCTOU.
        //
        // A bulk copy rather than a walk into an ArrayList, which is the same
        // guarantee at roughly half the cost.
        Object[] values = Objects.requireNonNull(source).toArray();
        values = Arrays.copyOf(values, values.length);
        for (Object value : values) Objects.requireNonNull(value);

        @SuppressWarnings("unchecked")
        List<E> result = (List<E>) Collections.unmodifiableList(Arrays.asList(values));
        return result;
    }

    /**
     * {@code Set.copyOf}, which is not {@code Set.of} twice over.
     * <p>
     * {@code Set.of} refuses a repeated element. {@code copyOf} keeps one of
     * them and says so: "if the given Collection contains duplicate elements, an
     * arbitrary element of the duplicates is preserved". Keeping the first is
     * inside that, so there is deliberately no duplicate check here.
     */
    @LowbyteInfo(owner = "java/util/Set", name = "copyOf",
            descriptor = "(Ljava/util/Collection;)Ljava/util/Set;", introducedIn = 10)
    public static <E> Set<E> setCopyOf(Collection<? extends E> source) {
        LinkedHashSet<Object> set = new LinkedHashSet<>(capacity(Objects.requireNonNull(source).size()));
        for (Object value : source) set.add(Objects.requireNonNull(value));
        return unmodifiableSet(set);
    }

    /** Shared by every {@code listOf} arity. */
    private static <E> List<E> newList(Object... values) {
        ArrayList<Object> list = new ArrayList<>(values.length);
        for (Object value : values) list.add(Objects.requireNonNull(value));
        @SuppressWarnings("unchecked")
        List<E> result = (List<E>) Collections.unmodifiableList(list);
        return result;
    }

    /** Shared by every {@code setOf} arity, duplicate check and all. */
    private static <E> Set<E> newSet(Object... values) {
        LinkedHashSet<Object> set = new LinkedHashSet<>(capacity(values.length));
        for (Object value : values) {
            if (!set.add(Objects.requireNonNull(value))) {
                throw new IllegalArgumentException("duplicate element: " + value);
            }
        }
        return unmodifiableSet(set);
    }

    /**
     * The table size that holds {@code size} entries without rehashing.
     * <p>
     * A hash table resizes once it is three quarters full, so asking for exactly
     * the count still rehashes on the way. This is what the JDK's own
     * {@code HashMap.newHashMap} computes, spelled out because that arrived in 19.
     */
    private static int capacity(int size) {
        return (int) (size / 0.75f) + 1;
    }

    /** The one cast, in the one place. */
    private static <E> Set<E> unmodifiableSet(LinkedHashSet<Object> set) {
        @SuppressWarnings("unchecked")
        Set<E> result = (Set<E>) Collections.unmodifiableSet(set);
        return result;
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
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>(capacity(Objects.requireNonNull(entries).length));
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
        LinkedHashMap<Object, Object> map =
                new LinkedHashMap<>(capacity(Objects.requireNonNull(source).size()));
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            map.put(Objects.requireNonNull(entry.getKey()), Objects.requireNonNull(entry.getValue()));
        }
        return unmodifiable(map);
    }

    /** Shared by every {@code mapOf} arity. */
    private static <K, V> Map<K, V> newMap(Object... keysAndValues) {
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>(capacity(keysAndValues.length / 2));
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
