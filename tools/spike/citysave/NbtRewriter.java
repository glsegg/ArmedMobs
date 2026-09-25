import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A faithful NBT tree reader/writer used to change exactly one field of a level.dat: Data.LevelName.
 *
 * <p>It exists because the dedicated server reads server.properties as ISO-8859-1, so a Chinese
 * level-name cannot survive that file - the name has to be patched into level.dat afterwards.</p>
 *
 * <p>Modes:</p>
 * <pre>
 *   NbtRewriter verify &lt;file&gt;                 read, re-serialise, compare the UNCOMPRESSED bytes
 *   NbtRewriter get    &lt;file&gt; &lt;path&gt;          print a value, e.g. Data.LevelName
 *   NbtRewriter set    &lt;in&gt; &lt;out&gt; &lt;path&gt; &lt;v&gt;    write a copy with one string field replaced
 * </pre>
 */
public final class NbtRewriter {

    static final int END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6,
            BYTE_ARRAY = 7, STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

    static final class Tag {
        final int type;
        Object value;
        Tag(int type, Object value) { this.type = type; this.value = value; }
        Compound asCompound() { return (Compound) this.value; }
        ListTag asList() { return (ListTag) this.value; }
    }

    static final class Compound extends LinkedHashMap<String, Tag> {}

    static final class ListTag {
        final int elementType;
        final List<Tag> items = new ArrayList<>();
        ListTag(int elementType) { this.elementType = elementType; }
    }

    // ------------------------------------------------------------------ read

    static final class Root {
        String name;
        Tag tag;
    }

    static Root readRaw(InputStream raw) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(raw))) {
            Root root = new Root();
            int type = in.readUnsignedByte();
            root.name = in.readUTF();
            root.tag = readPayload(in, type);
            return root;
        }
    }

    static Tag readPayload(DataInputStream in, int type) throws IOException {
        return switch (type) {
            case BYTE -> new Tag(BYTE, in.readByte());
            case SHORT -> new Tag(SHORT, in.readShort());
            case INT -> new Tag(INT, in.readInt());
            case LONG -> new Tag(LONG, in.readLong());
            case FLOAT -> new Tag(FLOAT, in.readFloat());
            case DOUBLE -> new Tag(DOUBLE, in.readDouble());
            case BYTE_ARRAY -> {
                byte[] a = new byte[in.readInt()];
                in.readFully(a);
                yield new Tag(BYTE_ARRAY, a);
            }
            case STRING -> new Tag(STRING, in.readUTF());
            case LIST -> {
                int elementType = in.readUnsignedByte();
                int size = in.readInt();
                ListTag list = new ListTag(elementType);
                for (int i = 0; i < size; i++) { list.items.add(readPayload(in, elementType)); }
                yield new Tag(LIST, list);
            }
            case COMPOUND -> {
                Compound c = new Compound();
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == END) { break; }
                    String name = in.readUTF();
                    c.put(name, readPayload(in, t));
                }
                yield new Tag(COMPOUND, c);
            }
            case INT_ARRAY -> {
                int[] a = new int[in.readInt()];
                for (int i = 0; i < a.length; i++) { a[i] = in.readInt(); }
                yield new Tag(INT_ARRAY, a);
            }
            case LONG_ARRAY -> {
                long[] a = new long[in.readInt()];
                for (int i = 0; i < a.length; i++) { a[i] = in.readLong(); }
                yield new Tag(LONG_ARRAY, a);
            }
            default -> throw new IOException("unsupported NBT tag type " + type);
        };
    }

    // ------------------------------------------------------------------ write

    static byte[] writeRaw(String name, Tag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(tag.type);
            out.writeUTF(name);
            writePayload(out, tag);
        }
        return bytes.toByteArray();
    }

    static void writePayload(DataOutputStream out, Tag tag) throws IOException {
        switch (tag.type) {
            case BYTE -> out.writeByte((Byte) tag.value);
            case SHORT -> out.writeShort((Short) tag.value);
            case INT -> out.writeInt((Integer) tag.value);
            case LONG -> out.writeLong((Long) tag.value);
            case FLOAT -> out.writeFloat((Float) tag.value);
            case DOUBLE -> out.writeDouble((Double) tag.value);
            case BYTE_ARRAY -> {
                byte[] a = (byte[]) tag.value;
                out.writeInt(a.length);
                out.write(a);
            }
            case STRING -> out.writeUTF((String) tag.value);
            case LIST -> {
                ListTag list = tag.asList();
                out.writeByte(list.elementType);
                out.writeInt(list.items.size());
                for (Tag item : list.items) { writePayload(out, item); }
            }
            case COMPOUND -> {
                for (Map.Entry<String, Tag> e : ((Compound) tag.value).entrySet()) {
                    out.writeByte(e.getValue().type);
                    out.writeUTF(e.getKey());
                    writePayload(out, e.getValue());
                }
                out.writeByte(END);
            }
            case INT_ARRAY -> {
                int[] a = (int[]) tag.value;
                out.writeInt(a.length);
                for (int v : a) { out.writeInt(v); }
            }
            case LONG_ARRAY -> {
                long[] a = (long[]) tag.value;
                out.writeInt(a.length);
                for (long v : a) { out.writeLong(v); }
            }
            default -> throw new IOException("cannot write tag type " + tag.type);
        }
    }

    static void writeGzip(Path target, String name, Tag tag) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(target)))) {
            out.writeByte(tag.type);
            out.writeUTF(name);
            writePayload(out, tag);
        }
    }

    // ------------------------------------------------------------------ helpers

    static Tag walk(Tag root, String path) {
        Tag current = root;
        for (String part : path.split("\\.")) {
            if (current.type != COMPOUND) { return null; }
            current = current.asCompound().get(part);
            if (current == null) { return null; }
        }
        return current;
    }

    static byte[] uncompress(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            return in.readAllBytes();
        }
    }

    /**
     * Turns backslash-u-XXXX sequences into characters, so a Chinese value can be passed on a
     * command line that the Windows shell would otherwise mangle through the ANSI code page.
     * (Java source cannot contain the escape sequence literally, not even in a comment.)
     */
    static String unescape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 5 < text.length() && (text.charAt(i + 1) == 'u' || text.charAt(i + 1) == 'U')) {
                out.append((char) Integer.parseInt(text.substring(i + 2, i + 6), 16));
                i += 5;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if (mode.equals("verify")) {
            Path file = Path.of(args[1]);
            Root root = readRaw(Files.newInputStream(file));
            byte[] original = uncompress(file);
            byte[] rewritten = writeRaw(root.name, root.tag);
            boolean same = java.util.Arrays.equals(original, rewritten);
            System.out.println("file=" + file);
            System.out.println("rootName='" + root.name + "' rootType=" + root.tag.type);
            System.out.println("uncompressed sizes: original=" + original.length + " rewritten=" + rewritten.length);
            System.out.println("byteIdentical=" + same);
            if (!same) {
                int limit = Math.min(original.length, rewritten.length);
                for (int i = 0; i < limit; i++) {
                    if (original[i] != rewritten[i]) {
                        System.out.println("first difference at byte " + i
                                + " original=" + (original[i] & 0xFF) + " rewritten=" + (rewritten[i] & 0xFF));
                        break;
                    }
                }
            }
            return;
        }
        if (mode.equals("get")) {
            Root root = readRaw(Files.newInputStream(Path.of(args[1])));
            Tag tag = walk(root.tag, args[2]);
            System.out.println(args[2] + " = " + (tag == null ? "<absent>" : tag.value));
            return;
        }
        if (mode.equals("set")) {
            Path in = Path.of(args[1]);
            Path out = Path.of(args[2]);
            String path = args[3];
            String value = unescape(args[4]);   // backslash-u escapes keep the command line pure ASCII
            Root root = readRaw(Files.newInputStream(in));
            Tag tag = walk(root.tag, path);
            if (tag == null || tag.type != STRING) {
                throw new IllegalStateException("no string tag at " + path);
            }
            String before = (String) tag.value;
            tag.value = value;
            writeGzip(out, root.name, root.tag);
            System.out.println("path=" + path);
            System.out.println("before='" + before + "' (" + before.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + " utf8 bytes)");
            System.out.println("after ='" + value + "' (" + value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + " utf8 bytes)");
            Root check = readRaw(Files.newInputStream(out));
            System.out.println("readback=" + walk(check.tag, path).value);
            return;
        }
        if (mode.equals("setbyte")) {
            Path in = Path.of(args[1]);
            Path out = Path.of(args[2]);
            String path = args[3];
            int value = Integer.parseInt(args[4]);
            Root root = readRaw(Files.newInputStream(in));
            Tag tag = walk(root.tag, path);
            if (tag == null || tag.type != BYTE) {
                throw new IllegalStateException("no byte tag at " + path);
            }
            byte before = (Byte) tag.value;
            tag.value = (byte) value;
            writeGzip(out, root.name, root.tag);
            System.out.println("path=" + path);
            System.out.println("before=" + before);
            System.out.println("after =" + value);
            Root check = readRaw(Files.newInputStream(out));
            System.out.println("readback=" + walk(check.tag, path).value);
            return;
        }
        if (mode.equals("hex")) {
            // ASCII-only proof of a string value: the console code page cannot mangle U+XXXX.
            Root root = readRaw(Files.newInputStream(Path.of(args[1])));
            Tag tag = walk(root.tag, args[2]);
            String value = String.valueOf(tag.value);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < value.length(); i++) {
                sb.append(String.format("U+%04X ", (int) value.charAt(i)));
            }
            System.out.println(args[2] + " asCodePoints = " + sb.toString().trim());
            System.out.println(args[2] + " utf8Bytes = " + value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            System.out.println(args[2] + " codePointCount = " + value.codePointCount(0, value.length()));
            return;
        }
        throw new IllegalArgumentException("unknown mode " + mode);
    }
}
