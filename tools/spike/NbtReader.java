import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A minimal NBT reader - just enough to inspect what {@link CityStructureGen} wrote (and, more to
 * the point, to prove that the file a Minecraft server will read is well formed and complete).
 *
 * <p>Types are mapped to: {@code Byte}, {@code Short}, {@code Integer}, {@code Long}, {@code Float},
 * {@code Double}, {@code byte[]}, {@code String}, {@code List<Object>}, {@code Map<String,Object>},
 * {@code int[]}, {@code long[]}.</p>
 */
public final class NbtReader {
    private final DataInputStream in;

    private NbtReader(InputStream in) {
        this.in = new DataInputStream(in);
    }

    /** Reads a gzipped, named root tag from the given stream. */
    public static Object readGzip(InputStream raw) throws IOException {
        try (DataInputStream data = new DataInputStream(new GZIPInputStream(raw))) {
            NbtReader reader = new NbtReader(data);
            int type = data.readUnsignedByte();
            data.readUTF(); // root name
            return reader.readPayload(type);
        }
    }

    private Object readPayload(int type) throws IOException {
        return switch (type) {
            case 1 -> this.in.readByte();
            case 2 -> this.in.readShort();
            case 3 -> this.in.readInt();
            case 4 -> this.in.readLong();
            case 5 -> this.in.readFloat();
            case 6 -> this.in.readDouble();
            case 7 -> {
                byte[] array = new byte[this.in.readInt()];
                this.in.readFully(array);
                yield array;
            }
            case 8 -> this.in.readUTF();
            case 9 -> this.readList();
            case 10 -> this.readCompound();
            case 11 -> {
                int[] array = new int[this.in.readInt()];
                for (int i = 0; i < array.length; i++) {
                    array[i] = this.in.readInt();
                }
                yield array;
            }
            case 12 -> {
                long[] array = new long[this.in.readInt()];
                for (int i = 0; i < array.length; i++) {
                    array[i] = this.in.readLong();
                }
                yield array;
            }
            default -> throw new IOException("unsupported NBT tag type " + type);
        };
    }

    private List<Object> readList() throws IOException {
        int elementType = this.in.readUnsignedByte();
        int length = this.in.readInt();
        List<Object> list = new ArrayList<>(Math.min(length, 1 << 20));
        for (int i = 0; i < length; i++) {
            list.add(this.readPayload(elementType));
        }
        return list;
    }

    private Map<String, Object> readCompound() throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            int type = this.in.readUnsignedByte();
            if (type == 0) {
                return map;
            }
            String name = this.in.readUTF();
            map.put(name, this.readPayload(type));
        }
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> compound, String key) {
        return (List<Object>) compound.get(key);
    }

    public static int intOf(Map<String, Object> compound, String key) {
        return ((Number) compound.get(key)).intValue();
    }

    public static String string(Map<String, Object> compound, String key) {
        return (String) compound.get(key);
    }

    public static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            return "compound(" + map.size() + " keys)";
        }
        if (value instanceof List<?> list) {
            return "list(" + list.size() + ")";
        }
        return value.getClass().getSimpleName() + "=" + value;
    }

    static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
