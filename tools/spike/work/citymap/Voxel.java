import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * A dense voxel volume of block STATES, loaded either from a shipped structure NBT file or from a
 * box of a save's overworld region files. Parts 2 and 3 of the city survey share it, so the obstacle
 * inventory and the rendered maps can never disagree about what is where.
 *
 * <p>Only the block id and its properties are kept; a state is stored as "name" or
 * "name[k=v,k=v]" so a caller can read a door's facing without a full property tree.</p>
 *
 * <p>Read-only: it opens region files with READ only and never writes to a save.</p>
 */
public final class Voxel {

    final String label;
    final int x0;
    final int y0;
    final int z0;
    final int sx;
    final int sy;
    final int sz;
    private final short[] grid;
    final String[] states;
    private final Map<String, Integer> stateIndex = new HashMap<>();
    public int missingChunks;

    private Voxel(String label, int x0, int y0, int z0, int sx, int sy, int sz) {
        this.label = label;
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.grid = new short[sx * sy * sz];
        java.util.Arrays.fill(this.grid, (short) -1);
        this.states = new String[1024];
    }

    // ------------------------------------------------------------------ access

    public int index(int x, int y, int z) {
        return ((y - y0) * sz + (z - z0)) * sx + (x - x0);
    }

    public boolean inside(int x, int y, int z) {
        return x >= x0 && x < x0 + sx && y >= y0 && y < y0 + sy && z >= z0 && z < z0 + sz;
    }

    /** Raw state index at a world position, -1 when outside the volume or in an absent chunk. */
    public int stateAt(int x, int y, int z) {
        if (!inside(x, y, z)) {
            return -1;
        }
        return grid[index(x, y, z)];
    }

    /** Block id at a world position without the namespace, or "?" when unknown. */
    public String at(int x, int y, int z) {
        int s = stateAt(x, y, z);
        if (s < 0) {
            return "?";
        }
        String state = states[s];
        int b = state.indexOf('[');
        return b < 0 ? state : state.substring(0, b);
    }

    public String stateString(int x, int y, int z) {
        int s = stateAt(x, y, z);
        return s < 0 ? "?" : states[s];
    }

    /** Value of one property of the block at a position, or null. */
    public String prop(int x, int y, int z, String key) {
        int s = stateAt(x, y, z);
        if (s < 0) {
            return null;
        }
        String state = states[s];
        int b = state.indexOf('[');
        if (b < 0) {
            return null;
        }
        String body = state.substring(b + 1, state.length() - 1);
        for (String pair : body.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    private int intern(String state) {
        Integer known = stateIndex.get(state);
        if (known != null) {
            return known;
        }
        for (int i = 0; i < states.length; i++) {
            if (states[i] == null) {
                states[i] = state;
                stateIndex.put(state, i);
                return i;
            }
        }
        throw new IllegalStateException("state palette overflow");
    }

    private void set(int x, int y, int z, int state) {
        grid[index(x, y, z)] = (short) state;
    }

    public int blockCount() {
        int n = 0;
        for (short s : grid) {
            if (s >= 0) {
                n++;
            }
        }
        return n;
    }

    /** Positions of every state whose id matches one of the given ids, as int[3] arrays. */
    public List<int[]> find(String... ids) {
        java.util.Set<String> want = new java.util.HashSet<>(List.of(ids));
        List<int[]> out = new ArrayList<>();
        for (int y = y0; y < y0 + sy; y++) {
            for (int z = z0; z < z0 + sz; z++) {
                for (int x = x0; x < x0 + sx; x++) {
                    String id = at(x, y, z);
                    if (want.contains(id)) {
                        out.add(new int[]{x, y, z});
                    }
                }
            }
        }
        return out;
    }

    /** name -> count over the whole volume. */
    public Map<String, Integer> histogram() {
        Map<String, Integer> hist = new java.util.TreeMap<>();
        for (int y = y0; y < y0 + sy; y++) {
            for (int z = z0; z < z0 + sz; z++) {
                for (int x = x0; x < x0 + sx; x++) {
                    String id = at(x, y, z);
                    if (!id.equals("?")) {
                        hist.merge(id, 1, Integer::sum);
                    }
                }
            }
        }
        return hist;
    }

    // ------------------------------------------------------------------ structure NBT

    @SuppressWarnings("unchecked")
    public static Voxel fromStructure(Path file, String label) throws IOException {
        Object root;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            root = NbtReader.readGzip(in);
        }
        Map<String, Object> structure = NbtReader.compound(root);
        List<Object> size = NbtReader.list(structure, "size");
        int cx = ((Number) size.get(0)).intValue();
        int cy = ((Number) size.get(1)).intValue();
        int cz = ((Number) size.get(2)).intValue();
        Voxel voxel = new Voxel(label == null ? file.getFileName().toString() : label,
                0, 0, 0, Math.max(1, cx), Math.max(1, cy), Math.max(1, cz));
        List<Object> palette = NbtReader.list(structure, "palette");
        int[] local = new int[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            Map<String, Object> state = NbtReader.compound(palette.get(i));
            String name = String.valueOf(state.get("Name"));
            if (name.startsWith("minecraft:")) {
                name = name.substring("minecraft:".length());
            }
            Object props = state.get("Properties");
            StringBuilder sb = new StringBuilder(name);
            if (props instanceof Map<?, ?> map && !map.isEmpty()) {
                Map<String, String> sorted = new java.util.TreeMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    sorted.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
                sb.append('[');
                boolean first = true;
                for (Map.Entry<String, String> e : sorted.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append(e.getKey()).append('=').append(e.getValue());
                    first = false;
                }
                sb.append(']');
            }
            local[i] = voxel.intern(sb.toString());
        }
        for (Object entry : NbtReader.list(structure, "blocks")) {
            Map<String, Object> block = NbtReader.compound(entry);
            List<Object> pos = NbtReader.list(block, "pos");
            int state = NbtReader.intOf(block, "state");
            int bx = ((Number) pos.get(0)).intValue();
            int by = ((Number) pos.get(1)).intValue();
            int bz = ((Number) pos.get(2)).intValue();
            if (bx < 0 || by < 0 || bz < 0 || bx >= voxel.sx || by >= voxel.sy || bz >= voxel.sz) {
                continue;
            }
            if (state >= 0 && state < local.length) {
                voxel.set(bx, by, bz, local[state]);
            }
        }
        return voxel;
    }

    // ------------------------------------------------------------------ save region

    public static Voxel fromRegion(Path worldDir, int x1, int y1, int z1, int x2, int y2, int z2,
                                   String label) throws IOException {
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minY = Math.max(-64, Math.min(y1, y2));
        int maxY = Math.min(319, Math.max(y1, y2));
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        Voxel voxel = new Voxel(label, minX, minY, minZ,
                maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
        Path regionDir = worldDir.resolve("region");
        for (int cx = minX >> 4; cx <= (maxX >> 4); cx++) {
            for (int cz = minZ >> 4; cz <= (maxZ >> 4); cz++) {
                int rx = Math.floorDiv(cx, 32);
                int rz = Math.floorDiv(cz, 32);
                Path file = regionDir.resolve("r." + rx + "." + rz + ".mca");
                if (!Files.isRegularFile(file)) {
                    voxel.missingChunks++;
                    continue;
                }
                int slot = (cx & 31) + (cz & 31) * 32;
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                    byte[] header = new byte[4096];
                    if (channel.read(ByteBuffer.wrap(header), 0) < 4096) {
                        voxel.missingChunks++;
                        continue;
                    }
                    int offset = ((header[slot * 4] & 0xFF) << 16)
                            | ((header[slot * 4 + 1] & 0xFF) << 8) | (header[slot * 4 + 2] & 0xFF);
                    int sectors = header[slot * 4 + 3] & 0xFF;
                    if (offset == 0 || sectors == 0) {
                        voxel.missingChunks++;
                        continue;
                    }
                    NbtRewriter.Compound chunk = readChunk(channel, offset, sectors);
                    if (chunk == null) {
                        voxel.missingChunks++;
                        continue;
                    }
                    voxel.loadChunkSections(chunk, cx, cz, minX, maxX, minY, maxY, minZ, maxZ);
                }
            }
        }
        return voxel;
    }

    private void loadChunkSections(NbtRewriter.Compound chunk, int chunkX, int chunkZ,
                                   int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        NbtRewriter.Tag sectionsTag = chunk.get("sections");
        if (sectionsTag == null) {
            return;
        }
        for (NbtRewriter.Tag sectionTag : sectionsTag.asList().items) {
            NbtRewriter.Compound section = sectionTag.asCompound();
            NbtRewriter.Tag yTag = section.get("Y");
            if (yTag == null) {
                continue;
            }
            int sectionY = ((Number) yTag.value).intValue();
            if (sectionY * 16 + 15 < minY || sectionY * 16 > maxY) {
                continue;
            }
            NbtRewriter.Tag statesTag = section.get("block_states");
            if (statesTag == null) {
                continue;
            }
            NbtRewriter.Compound statesC = statesTag.asCompound();
            NbtRewriter.Tag paletteTag = statesC.get("palette");
            if (paletteTag == null) {
                continue;
            }
            List<NbtRewriter.Tag> palette = paletteTag.asList().items;
            int n = palette.size();
            int[] local = new int[n];
            for (int i = 0; i < n; i++) {
                local[i] = intern(renderState(palette.get(i).asCompound()));
            }
            long[] data = null;
            NbtRewriter.Tag dataTag = statesC.get("data");
            if (dataTag != null) {
                data = (long[]) dataTag.value;
            }
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(Math.max(1, n - 1)));
            int perLong = 64 / bits;
            long mask = (1L << bits) - 1L;
            for (int y = 0; y < 16; y++) {
                int worldY = sectionY * 16 + y;
                if (worldY < minY || worldY > maxY) {
                    continue;
                }
                for (int z = 0; z < 16; z++) {
                    int worldZ = chunkZ * 16 + z;
                    if (worldZ < minZ || worldZ > maxZ) {
                        continue;
                    }
                    for (int x = 0; x < 16; x++) {
                        int worldX = chunkX * 16 + x;
                        if (worldX < minX || worldX > maxX) {
                            continue;
                        }
                        int index = (y << 8) | (z << 4) | x;
                        int state;
                        if (data == null) {
                            state = 0;
                        } else {
                            int longIndex = index / perLong;
                            int off = (index % perLong) * bits;
                            state = (int) ((data[longIndex] >>> off) & mask);
                        }
                        if (state < 0 || state >= n) {
                            state = 0;
                        }
                        set(worldX, worldY, worldZ, local[state]);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    static String renderState(NbtRewriter.Compound state) {
        NbtRewriter.Tag nameTag = state.get("Name");
        String name = String.valueOf(nameTag.value);
        if (name.startsWith("minecraft:")) {
            name = name.substring("minecraft:".length());
        }
        NbtRewriter.Tag propsTag = state.get("Properties");
        if (propsTag == null || propsTag.type != NbtRewriter.COMPOUND) {
            return name;
        }
        Map<String, String> sorted = new java.util.TreeMap<>();
        for (Map.Entry<String, NbtRewriter.Tag> e : propsTag.asCompound().entrySet()) {
            sorted.put(e.getKey(), String.valueOf(e.getValue().value));
        }
        StringBuilder sb = new StringBuilder(name);
        sb.append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        sb.append(']');
        return sb.toString();
    }

    static NbtRewriter.Compound readChunk(FileChannel channel, int offset, int sectors)
            throws IOException {
        long byteOffset = (long) offset * 4096L;
        ByteBuffer head = ByteBuffer.allocate(5);
        if (channel.read(head, byteOffset) < 5) {
            return null;
        }
        head.flip();
        int length = head.getInt();
        int compression = head.get() & 0xFF;
        if (length <= 1 || length > sectors * 4096) {
            return null;
        }
        ByteBuffer payload = ByteBuffer.allocate(length - 1);
        int read = 0;
        while (read < length - 1) {
            int got = channel.read(payload, byteOffset + 5 + read);
            if (got <= 0) {
                break;
            }
            read += got;
        }
        payload.flip();
        byte[] raw = new byte[length - 1];
        payload.get(raw);
        InputStream in = switch (compression) {
            case 1 -> new GZIPInputStream(new ByteArrayInputStream(raw));
            case 2 -> new InflaterInputStream(new ByteArrayInputStream(raw), new Inflater());
            case 3 -> new ByteArrayInputStream(raw);
            default -> throw new IOException("unknown compression " + compression);
        };
        try (DataInputStream data = new DataInputStream(in)) {
            int type = data.readUnsignedByte();
            data.readUTF();
            NbtRewriter.Tag tag = NbtRewriter.readPayload(data, type);
            return tag.asCompound();
        }
    }

    /** All distinct block ids present, in a stable order. */
    public List<String> distinctIds() {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (String s : states) {
            if (s == null) {
                continue;
            }
            int b = s.indexOf('[');
            seen.putIfAbsent(b < 0 ? s : s.substring(0, b), Boolean.TRUE);
        }
        return new ArrayList<>(seen.keySet());
    }

    /** The palette state array, for callers that want to walk it directly. */
    public String[] paletteStates() {
        return states;
    }
}
