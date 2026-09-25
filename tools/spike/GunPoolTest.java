import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Head-less check that the gun pools really resolve against the TaCZ gun pack the user has installed.
 *
 * <p>It reads two things and makes them agree:</p>
 * <ul>
 *   <li>the shipped rules - {@code ScavTier.java}'s per-tier gun types and {@code Config.java}'s
 *       default blacklist / excluded types, extracted from the source text so this test cannot drift
 *       away from what the mod actually does;</li>
 *   <li>the real data - every {@code index/guns/*.json} and {@code index/ammo/*.json} inside the TaCZ
 *       jar in {@code libs/}.</li>
 * </ul>
 *
 * <p>What it proves: every tier ends up with a non-empty pool, every gun type TaCZ ships is either
 * routed to a tier or explicitly excluded, no blacklisted gun can be picked, every blacklist entry
 * names a gun that exists (a typo would silently do nothing in game), and - the part that matters
 * most for "a mob gets a gun <em>and</em> ammo" - every gun's {@code ammo} id has a matching ammo
 * index and a magazine size.</p>
 *
 * <p>Run from the repository root: {@code java GunPoolTest [projectDir]}. Exits non-zero with the
 * offending gun ids listed.</p>
 */
public final class GunPoolTest {
    private static final Pattern TIER = Pattern.compile(
            "(\\w+)\\(\"(\\w+)\",\\s*Set\\.of\\(([^)]*)\\),\\s*[\\d.]+D\\)");
    private static final Pattern BLACKLIST = Pattern.compile(
            "defaultGunBlacklist\\(\\)\\s*\\{[^}]*?return\\s+List\\.of\\(([^)]*)\\)", Pattern.DOTALL);
    private static final Pattern EXCLUDED_TYPES = Pattern.compile(
            "List\\.of\\(\"excludedGunTypes\"\\),\\s*\\(\\)\\s*->\\s*List\\.of\\(([^)]*)\\)");

    public static void main(String[] args) throws Exception {
        Path root = args.length > 0 ? Path.of(args[0]) : Path.of(".");
        Path tierSource = root.resolve("src/main/java/com/gfl/tarkovscav/entity/ScavTier.java");
        Path configSource = root.resolve("src/main/java/com/gfl/tarkovscav/Config.java");
        Path jar = findTaczJar(root);
        System.out.println("taCZ jar : " + jar);

        Map<String, List<String>> tierTypes = new LinkedHashMap<>();
        Map<String, String> tierTierId = new LinkedHashMap<>();
        Matcher tierMatcher = TIER.matcher(Files.readString(tierSource));
        while (tierMatcher.find()) {
            String constant = tierMatcher.group(1);
            List<String> types = splitQuoted(tierMatcher.group(3));
            tierTypes.put(constant, types);
            tierTierId.put(constant, tierMatcher.group(2));
        }
        if (tierTypes.isEmpty()) {
            throw new IllegalStateException("could not read the tiers out of " + tierSource);
        }
        System.out.println("tiers    : " + tierTypes);

        Set<String> blacklist = new LinkedHashSet<>(splitQuoted(firstGroup(BLACKLIST, Files.readString(configSource))));
        Set<String> excludedTypes = new LinkedHashSet<>(
                splitQuoted(firstGroup(EXCLUDED_TYPES, Files.readString(configSource))));
        System.out.println("blacklist: " + blacklist);
        System.out.println("excluded types: " + excludedTypes);

        // ---------------------------------------------------------------- the gun pack
        Map<String, String> gunType = new LinkedHashMap<>();
        Map<String, String> gunAmmo = new LinkedHashMap<>();
        Map<String, Integer> gunMagazine = new LinkedHashMap<>();
        Set<String> ammoIds = new LinkedHashSet<>();
        List<String> problemsPre = new ArrayList<>();

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            // gun index -> which data file describes it
            Map<String, String> gunDataFile = new LinkedHashMap<>();
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                String path = entry.getName();
                if (path.endsWith("/")) {
                    continue;
                }
                Matcher ammo = Pattern.compile("index/ammo/([a-z0-9_]+)\\.json$").matcher(path);
                if (ammo.find()) {
                    ammoIds.add("tacz:" + ammo.group(1));
                    continue;
                }
                Matcher gun = Pattern.compile("data/tacz/index/guns/([a-z0-9_]+)\\.json$").matcher(path);
                if (!gun.find()) {
                    continue;
                }
                String id = gun.group(1);
                String text;
                try (InputStream in = zip.getInputStream(entry)) {
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                gunType.put(id, field(text, "type"));
                gunDataFile.put(id, field(text, "data"));
            }

            // The index file only names the gun; ammo, magazine size and bolt live in its data file
            // (data/<namespace>/data/guns/<name>.json). This mirrors what CommonGunIndex merges at runtime.
            for (Map.Entry<String, String> gun : gunDataFile.entrySet()) {
                String reference = gun.getValue();
                if (reference == null || !reference.contains(":")) {
                    problemsPre.add("gun '" + gun.getKey() + "' has no 'data' file reference");
                    continue;
                }
                String[] parts = reference.split(":", 2);
                String wanted = "data/" + parts[0] + "/data/guns/" + parts[1] + ".json";
                ZipEntry dataEntry = null;
                for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                    ZipEntry candidate = entries.nextElement();
                    if (candidate.getName().endsWith(wanted)) {
                        dataEntry = candidate;
                        break;
                    }
                }
                if (dataEntry == null) {
                    problemsPre.add("gun '" + gun.getKey() + "' points at " + reference
                            + " but no " + wanted + " is in the pack");
                    continue;
                }
                String text;
                try (InputStream in = zip.getInputStream(dataEntry)) {
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                gunAmmo.put(gun.getKey(), field(text, "ammo"));
                String amount = field(text, "ammo_amount");
                gunMagazine.put(gun.getKey(), amount == null ? -1 : (int) Double.parseDouble(amount));
            }
        }

        System.out.println("guns     : " + gunType.size());
        System.out.println("ammo ids : " + ammoIds.size());

        List<String> problems = new ArrayList<>(problemsPre);
        if (gunAmmo.isEmpty()) {
            throw new IllegalStateException("could not read any gun data file - the TaCZ pack layout changed");
        }

        // ---------------------------------------------------------------- 1. every type is routed
        Set<String> allTypes = new LinkedHashSet<>(gunType.values());
        Set<String> routed = new LinkedHashSet<>();
        tierTypes.values().forEach(routed::addAll);
        for (String type : allTypes) {
            if (!routed.contains(type) && !excludedTypes.contains(type)) {
                problems.add("TaCZ gun type '" + type + "' is neither assigned to a tier nor listed in "
                        + "excludedGunTypes - those guns can never be issued");
            }
        }

        // ---------------------------------------------------------------- 2. pools are non-empty
        for (Map.Entry<String, List<String>> tier : tierTypes.entrySet()) {
            List<String> pool = new ArrayList<>();
            for (Map.Entry<String, String> gun : gunType.entrySet()) {
                String id = "tacz:" + gun.getKey();
                if (tier.getValue().contains(gun.getValue())
                        && !blacklist.contains(id)
                        && !excludedTypes.contains(gun.getValue())) {
                    pool.add(gun.getKey());
                }
            }
            System.out.println("  " + tier.getValue() + " tier (" + tier.getKey() + "): "
                    + pool.size() + " guns " + pool);
            if (pool.isEmpty()) {
                problems.add("tier '" + tier.getKey() + "' has an empty gun pool");
            }
        }

        // ---------------------------------------------------------------- 3. blacklist is real
        for (String blocked : blacklist) {
            String bare = blocked.startsWith("tacz:") ? blocked.substring("tacz:".length()) : blocked;
            if (!gunType.containsKey(bare)) {
                problems.add("gunBlacklist entry '" + blocked + "' does not name a gun in the TaCZ pack"
                        + " (typo? the entry would silently do nothing)");
            }
        }

        // ---------------------------------------------------------------- 4. gun -> ammo -> magazine
        for (Map.Entry<String, String> gun : gunType.entrySet()) {
            // A gun the filters already exclude can never reach a mob, so its data file is not our
            // problem (the default pack's minigun, for instance, ships without an ammo_amount).
            if (blacklist.contains("tacz:" + gun.getKey()) || excludedTypes.contains(gun.getValue())) {
                continue;
            }
            String ammo = gunAmmo.get(gun.getKey());
            if (ammo == null || ammo.isBlank()) {
                problems.add("gun '" + gun.getKey() + "' has no 'ammo' id - a mob could not be issued ammo for it");
                continue;
            }
            if (!ammoIds.contains(ammo)) {
                problems.add("gun '" + gun.getKey() + "' wants ammo '" + ammo
                        + "' but there is no index/ammo/" + ammo.replace("tacz:", "") + ".json in the pack");
            }
            int magazine = gunMagazine.getOrDefault(gun.getKey(), -1);
            if (magazine <= 0) {
                problems.add("gun '" + gun.getKey() + "' has no usable ammo_amount (magazine size)");
            }
        }

        // ---------------------------------------------------------------- 5. sanity on the defaults
        long allowed = gunType.entrySet().stream()
                .filter(gun -> !blacklist.contains("tacz:" + gun.getKey()))
                .filter(gun -> !excludedTypes.contains(gun.getValue()))
                .count();
        System.out.println("issuable guns after the default filters: " + allowed);
        if (allowed < 8) {
            problems.add("only " + allowed + " guns survive the default filters - that is suspiciously few");
        }

        if (!problems.isEmpty()) {
            System.out.println();
            problems.forEach(problem -> System.out.println("FAIL: " + problem));
            throw new IllegalStateException(problems.size() + " gun pool problem(s)");
        }
        System.out.println("gun pools OK: every tier resolves against the real TaCZ pack, and every gun has ammo");
    }

    // ------------------------------------------------------------------ helpers

    private static Path findTaczJar(Path root) throws IOException {
        Path libs = root.resolve("libs");
        Map<String, String> properties = new LinkedHashMap<>();
        for (String line : Files.readAllLines(root.resolve("gradle.properties"))) {
            int equals = line.indexOf('=');
            if (equals > 0 && !line.startsWith("#")) {
                properties.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
            }
        }
        String module = properties.getOrDefault("tacz_module", "tacz");
        String version = properties.getOrDefault("tacz_version", "");
        Path exact = libs.resolve(module + "-" + version + ".jar");
        if (Files.isRegularFile(exact)) {
            return exact;
        }
        try (var stream = Files.list(libs)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no TaCZ jar in " + libs + " - see libs/README.md"));
        }
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            throw new IllegalStateException("could not find " + pattern + " in the source");
        }
        return matcher.group(1);
    }

    private static List<String> splitQuoted(String text) {
        List<String> values = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(text);
        while (matcher.find()) {
            values.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return values;
    }

    private static String field(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"?([^,\"}\\s]+)\"?").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
