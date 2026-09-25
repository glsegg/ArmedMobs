import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Reads Minecraft Anvil region files directly and reports which blocks are actually stored in a box.
 *
 * <p>This is the "block statistics" evidence for the city save: it never asks the server anything,
 * it decodes the saved region data, so it proves what a second server start (or the user's client)
 * will load.</p>
 *
 * <pre>
 *   RegionStat count &lt;worldDir&gt; &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt;
 *   RegionStat find  &lt;worldDir&gt; &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt; &lt;blockId&gt;
 * </pre>
 */
public final class RegionStat {

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path world = Path.of(args[1]);
        int x1 = Integer.parseInt(args[2]), y1 = Integer.parseInt(args[3]), z1 = Integer.parseInt(args[4]);
        int x2 = Integer.parseInt(args[5]), y2 = Integer.parseInt(args[6]), z2 = Integer.parseInt(args[7]);
        String filter = args.length > 8 ? args[8] : null;

        Map<String, Integer> histogram = new TreeMap<>();
        Map<String, int[]> bounds = new LinkedHashMap<>();
        int chunksRead = 0, chunksAbsent = 0;

        for (int cx = x1 >> 4; cx <= (x2 >> 4); cx++) {
            for (int cz = z1 >> 4; cz <= (z2 >> 4); cz++) {
                NbtRewriter.Compound chunk = readChunk(world, cx, cz);
                if (chunk == null) { chunksAbsent++; continue; }
                chunksRead++;
                countChunk(chunk, cx, cz, x1, y1, z1, x2, y2, z2, histogram, bounds);
            }
        }

        System.out.println("world=" + world);
        System.out.println("box=x[" + x1 + ".." + x2 + "] y[" + y1 + ".." + y2 + "] z[" + z1 + ".." + z2 + "]");
        System.out.println("chunksRead=" + chunksRead + " chunksAbsent=" + chunksAbsent);

        if (mode.equals("find")) {
            int[] b = bounds.get(filter);
            if (b == null) {
                System.out.println("block " + filter + " NOT FOUND in box");
            } else {
                System.out.println("block " + filter + " count=" + b[6]);
                System.out.println("  x[" + b[0] + ".." + b[3] + "] y[" + b[1] + ".." + b[4] + "] z[" + b[2] + ".." + b[5] + "]");
            }
            return;
        }

        int total = 0;
        for (int v : histogram.values()) { total += v; }
        System.out.println("distinctBlocks=" + histogram.size() + " totalBlocksInBox=" + total);
        for (Map.Entry<String, Integer> e : histogram.entrySet()) {
            System.out.println("  " + e.getKey() + " x" + e.getValue());
        }
    }

    // ------------------------------------------------------------------ anvil

    static NbtRewriter.Compound readChunk(Path world, int chunkX, int chunkZ) throws IOException {
        int rx = Math.floorDiv(chunkX, 32), rz = Math.floorDiv(chunkZ, 32);
        Path file = world.resolve("region").resolve("r." + rx + "." + rz + ".mca");
        if (!Files.isRegularFile(file)) { return null; }
        int slot = (chunkX & 31) + (chunkZ & 31) * 32;
        byte[] header = new byte[4096];
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (channel.read(ByteBuffer.wrap(header), 0) < 4096) { return null; }
            int offset = ((header[slot * 4] & 0xFF) << 16) | ((header[slot * 4 + 1] & 0xFF) << 8) | (header[slot * 4 + 2] & 0xFF);
            int sectors = header[slot * 4 + 3] & 0xFF;
            if (offset == 0 || sectors == 0) { return null; }
            long byteOffset = (long) offset * 4096L;
            ByteBuffer head = ByteBuffer.allocate(5);
            if (channel.read(head, byteOffset) < 5) { return null; }
            head.flip();
            int length = head.getInt();
            int compression = head.get() & 0xFF;
            if (length <= 1) { return null; }
            ByteBuffer payload = ByteBuffer.allocate(length - 1);
            channel.read(payload, byteOffset + 5);
            payload.flip();
            byte[] raw = new byte[length - 1];
            payload.get(raw);
            return parse(raw, compression);
        }
    }

    static NbtRewriter.Compound parse(byte[] raw, int compression) throws IOException {
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

    // ------------------------------------------------------------------ block decoding

    static void countChunk(NbtRewriter.Compound chunk, int chunkX, int chunkZ,
                           int x1, int y1, int z1, int x2, int y2, int z2,
                           Map<String, Integer> histogram, Map<String, int[]> bounds) {
        NbtRewriter.Tag sectionsTag = chunk.get("sections");
        if (sectionsTag == null) { return; }
        for (NbtRewriter.Tag sectionTag : sectionsTag.asList().items) {
            NbtRewriter.Compound section = sectionTag.asCompound();
            NbtRewriter.Tag yTag = section.get("Y");
            if (yTag == null) { continue; }
            int sectionY = ((Number) yTag.value).intValue();
            NbtRewriter.Tag statesTag = section.get("block_states");
            if (statesTag == null) { continue; }
            NbtRewriter.Compound states = statesTag.asCompound();
            NbtRewriter.Tag paletteTag = states.get("palette");
            if (paletteTag == null) { continue; }
            List<NbtRewriter.Tag> palette = paletteTag.asList().items;
            String[] names = new String[palette.size()];
            for (int i = 0; i < palette.size(); i++) {
                names[i] = String.valueOf(palette.get(i).asCompound().get("Name").value);
            }
            long[] data = null;
            NbtRewriter.Tag dataTag = states.get("data");
            if (dataTag != null) { data = (long[]) dataTag.value; }
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
            int perLong = 64 / bits;
            long mask = (1L << bits) - 1L;
            for (int y = 0; y < 16; y++) {
                int worldY = sectionY * 16 + y;
                if (worldY < y1 || worldY > y2) { continue; }
                for (int z = 0; z < 16; z++) {
                    int worldZ = chunkZ * 16 + z;
                    if (worldZ < z1 || worldZ > z2) { continue; }
                    for (int x = 0; x < 16; x++) {
                        int worldX = chunkX * 16 + x;
                        if (worldX < x1 || worldX > x2) { continue; }
                        int index = (y << 8) | (z << 4) | x;
                        int state;
                        if (data == null) {
                            state = 0;
                        } else {
                            int longIndex = index / perLong;
                            int offset = (index % perLong) * bits;
                            state = (int) ((data[longIndex] >>> offset) & mask);
                        }
                        if (state < 0 || state >= names.length) { state = 0; }
                        String name = names[state];
                        histogram.merge(name, 1, Integer::sum);
                        int[] b = bounds.get(name);
                        if (b == null) {
                            bounds.put(name, new int[]{worldX, worldY, worldZ, worldX, worldY, worldZ, 1});
                        } else {
                            b[0] = Math.min(b[0], worldX); b[1] = Math.min(b[1], worldY); b[2] = Math.min(b[2], worldZ);
                            b[3] = Math.max(b[3], worldX); b[4] = Math.max(b[4], worldY); b[5] = Math.max(b[5], worldZ);
                            b[6]++;
                        }
                    }
                }
            }
        }
    }
}
