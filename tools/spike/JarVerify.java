import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Verifies a built jar by <b>inflating every single entry</b> and comparing its CRC.
 *
 * <p>This is not paranoia: a sibling project shipped a jar that threw
 * {@code java.util.zip.ZipException: invalid stored block lengths} on load, and nothing in a normal
 * build log shows that - the jar is only broken when something reads it. Reading every entry here
 * (including the entries of the jarJar-nested GeckoLib jar) turns that into a build-time failure.</p>
 *
 * <p>It also asserts the two packaging rules that matter for this mod:</p>
 * <ul>
 *   <li>TaCZ is <b>not</b> inside the jar - no {@code com/tacz/} classes and no
 *       {@code META-INF/jarjar/...tacz...} entry;</li>
 *   <li>the bundled GeckoLib <b>is</b>, as a jarJar entry.</li>
 * </ul>
 *
 * <p>Run: {@code java JarVerify build/libs/armedmobs-0.1.0-all.jar}</p>
 */
public final class JarVerify {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("usage: java JarVerify <jar> [more jars...]");
        }

        List<String> problems = new ArrayList<>();
        for (String arg : args) {
            Path jar = Path.of(arg);
            if (!Files.isRegularFile(jar)) {
                problems.add("no such jar: " + jar);
                continue;
            }

            byte[] bytes = Files.readAllBytes(jar);
            System.out.println("jar        : " + jar.toAbsolutePath());
            System.out.println("size       : " + bytes.length + " bytes");
            System.out.println("sha256     : " + sha256(bytes));

            int entries = 0;
            int nested = 0;
            boolean hasGeckoLib = false;
            boolean hasTaczClasses = false;
            boolean hasTaczJarJar = false;
            boolean hasModsToml = false;
            // The city presets are generated into the workspace by CityStructureGen and read out of the
            // jar by Minecraft's worldgen, so the two copies have to be the same bytes. `-cp tools/spike/out`
            // is run from the project root, so the workspace is simply the current directory.
            java.util.Map<String, Long> structureCrcs = new java.util.LinkedHashMap<>();
            Path workspaceStructures = Path.of("src/main/resources/data/tarkovscav/structures");
            boolean hasWorkspace = Files.isDirectory(workspaceStructures);

            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (Enumeration<? extends ZipEntry> list = zip.entries(); list.hasMoreElements(); ) {
                    ZipEntry entry = list.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    entries++;
                    byte[] content;
                    try (InputStream in = zip.getInputStream(entry)) {
                        content = in.readAllBytes();
                    } catch (IOException broken) {
                        problems.add(jar.getFileName() + ": cannot inflate " + entry.getName() + " - " + broken);
                        continue;
                    }

                    if (entry.getCrc() >= 0) {
                        CRC32 crc = new CRC32();
                        crc.update(content);
                        if (crc.getValue() != entry.getCrc()) {
                            problems.add(jar.getFileName() + ": CRC mismatch for " + entry.getName());
                        }
                    }

                    String name = entry.getName();
                    if (name.equals("META-INF/mods.toml")) {
                        hasModsToml = true;
                    }
                    if (name.startsWith("com/tacz/")) {
                        hasTaczClasses = true;
                    }
                    if (name.startsWith("META-INF/jarjar/")) {
                        if (name.toLowerCase().contains("tacz")) {
                            hasTaczJarJar = true;
                        }
                        if (name.endsWith(".jar")) {
                            nested++;
                            problems.addAll(verifyNested(jar.getFileName().toString(), name, content));
                        }
                    }
                    if (name.startsWith("META-INF/jarjar/") && name.toLowerCase().contains("geckolib")) {
                        hasGeckoLib = true;
                    }
                    if (name.startsWith("data/tarkovscav/structures/") && name.endsWith(".nbt")
                            && hasWorkspace) {
                        String fileName = name.substring("data/tarkovscav/structures/".length());
                        Path workspaceFile = workspaceStructures.resolve(fileName);
                        if (!Files.isRegularFile(workspaceFile)) {
                            problems.add(jar.getFileName() + ": " + name + " is in the jar but not in the"
                                    + " workspace - regenerate the city presets"
                                    + " (java -cp tools/spike/out CityStructureGen)");
                            continue;
                        }
                        byte[] workspaceBytes = Files.readAllBytes(workspaceFile);
                        CRC32 workspaceCrc = new CRC32();
                        workspaceCrc.update(workspaceBytes);
                        if (workspaceCrc.getValue() != crcOf(content)) {
                            problems.add(jar.getFileName() + ": " + name + " differs between the jar and"
                                    + " the workspace (" + workspaceBytes.length + " vs " + content.length
                                    + " bytes) - the jar was built from a stale structure file; rebuild"
                                    + " with gradlew build");
                        }
                        structureCrcs.put(name, workspaceCrc.getValue());
                    }
                }
            }
            if (hasWorkspace) {
                try (var files = Files.list(workspaceStructures)) {
                    for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".nbt"))
                            .toList()) {
                        String entry = "data/tarkovscav/structures/" + file.getFileName();
                        if (!structureCrcs.containsKey(entry)) {
                            problems.add(jar.getFileName() + ": the workspace ships " + entry
                                    + " but the jar does not");
                        }
                    }
                }
                System.out.println("city presets: " + structureCrcs.size()
                        + " structure file(s) compared byte for byte with the workspace");
            }

            System.out.println("entries    : " + entries + " (all inflated, CRCs compared)");
            System.out.println("nested jars: " + nested + " (each verified entry by entry)");
            // The plain jar is the intermediate artifact; only the "-all" classifier is the one that
            // gets shipped, and only that one has to carry the bundled GeckoLib.
            boolean shippingJar = jar.getFileName().toString().contains("-all");
            System.out.println("mods.toml  : " + hasModsToml);
            System.out.println("bundled GeckoLib: " + hasGeckoLib + (shippingJar ? "" : " (not required for this artifact)"));

            if (!hasModsToml) {
                problems.add(jar.getFileName() + ": META-INF/mods.toml is missing - Forge would not load it");
            }
            if (shippingJar && !hasGeckoLib) {
                problems.add(jar.getFileName() + ": the bundled GeckoLib jarJar entry is missing");
            }
            if (hasTaczClasses || hasTaczJarJar) {
                problems.add(jar.getFileName() + ": TaCZ is inside this jar - it must never be redistributed");
            }
        }

        if (!problems.isEmpty()) {
            System.out.println();
            problems.forEach(problem -> System.out.println("FAIL: " + problem));
            throw new IllegalStateException(problems.size() + " jar problem(s)");
        }
        System.out.println("jar OK: every entry inflated, no TaCZ bundled, GeckoLib present");
    }

    private static long crcOf(byte[] content) {
        CRC32 crc = new CRC32();
        crc.update(content);
        return crc.getValue();
    }

    /** Reads a nested jar's entries out of memory, so a corrupt inner jar is caught too. */
    private static List<String> verifyNested(String outer, String entryName, byte[] content) {
        List<String> problems = new ArrayList<>();
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                count++;
                while (zip.read(buffer) >= 0) {
                    // inflating is the point
                }
            }
        } catch (IOException broken) {
            problems.add(outer + ": nested " + entryName + " is corrupt - " + broken);
            return problems;
        }
        System.out.println("  nested " + entryName + ": " + count + " entries inflated OK");
        return problems;
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream stream = new DigestInputStream(new ByteArrayInputStream(bytes), digest)) {
            stream.readAllBytes();
        }
        StringBuilder builder = new StringBuilder();
        for (byte b : digest.digest()) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }
}
