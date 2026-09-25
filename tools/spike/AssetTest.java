import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the repository and fails when something the mod promises is not actually there.
 *
 * <p>Every check here exists because the failure mode is invisible until a player hits it:</p>
 * <ol>
 *   <li>every {@code ITEMS.register("x")} has {@code assets/tarkovscav/models/item/x.json};</li>
 *   <li>every asset path named in the client sources through {@code TarkovScav.id("...")} exists on
 *       disk - that is the geometry, animation and texture files the renderers load, so a typo here
 *       is a purple-and-black mob or a crash at render time;</li>
 *   <li>every translation key used from Java exists in both {@code en_us.json} and
 *       {@code zh_cn.json} (this mod ships both, and a missing key shows as a raw
 *       {@code tarkovscav.command...} in chat);</li>
 *   <li>{@code mods.toml} declares TaCZ as a mandatory dependency and {@code build.gradle} does
 *       <b>not</b> jarJar it - bundling a 57 MB third-party mod is not allowed, so the build script
 *       itself is checked;</li>
 *   <li>every configuration key defined in {@code Config.java} is documented in {@code README.md};
 *       a config nobody can find is a config that does not exist.</li>
 * </ol>
 *
 * <p>Run from the repository root: {@code java AssetTest [projectDir]}.</p>
 */
public final class AssetTest {
    private static final Pattern ITEM_REGISTER = Pattern.compile("ITEMS\\.register\\(\"([a-z0-9_]+)\"");
    private static final Pattern ASSET_ID = Pattern.compile("TarkovScav\\.id\\(\"([^\"]+)\"\\)");
    private static final Pattern TRANSLATABLE = Pattern.compile("translatable\\(\"([^\"]+)\"");
    private static final Pattern CONFIG_DEFINE = Pattern.compile("\\.define(?:InRange|ListAllowEmpty)?\\(\\s*(?:List\\.of\\()?\"([A-Za-z0-9_]+)\"");
    private static final String MOD_ID = "tarkovscav";

    public static void main(String[] args) throws Exception {
        Path root = args.length > 0 ? Path.of(args[0]) : Path.of(".");
        Path assets = root.resolve("src/main/resources/assets/" + MOD_ID);
        Path javaDir = root.resolve("src/main/java/com/gfl/tarkovscav");
        System.out.println("asset check: " + assets.toAbsolutePath());

        List<String> problems = new ArrayList<>();

        // ---------------------------------------------------------------- 1. item models
        Set<String> items = new LinkedHashSet<>();
        Matcher itemMatcher = ITEM_REGISTER.matcher(Files.readString(javaDir.resolve("registry/ModItems.java")));
        while (itemMatcher.find()) {
            items.add(itemMatcher.group(1));
        }
        if (items.isEmpty()) {
            problems.add("could not read any item registry names out of ModItems.java");
        }
        for (String item : items) {
            Path model = assets.resolve("models/item/" + item + ".json");
            if (!Files.isRegularFile(model)) {
                problems.add("item '" + item + "' has no model (" + rel(root, model) + ")");
                continue;
            }
            String text = Files.readString(model);
            Matcher parent = Pattern.compile("\"parent\"\\s*:\\s*\"([^\"]+)\"").matcher(text);
            if (parent.find() && parent.group(1).startsWith(MOD_ID + ":")) {
                String parentPath = parent.group(1).substring(MOD_ID.length() + 1);
                if (parentPath.startsWith("item/") || parentPath.startsWith("block/")) {
                    Path parentFile = assets.resolve("models/" + parentPath + ".json");
                    if (!Files.isRegularFile(parentFile)) {
                        problems.add("model for item '" + item + "' points at missing parent " + parentFile);
                    }
                }
            }
        }
        System.out.println("items with models: " + items.size());

        // ---------------------------------------------------------------- 2. geo/anim/texture paths
        Set<String> referenced = new LinkedHashSet<>();
        try (var sources = Files.walk(javaDir)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = ASSET_ID.matcher(Files.readString(source));
                while (matcher.find()) {
                    referenced.add(matcher.group(1));
                }
            }
        }
        for (String path : referenced) {
            if (path.endsWith(".json") || path.endsWith(".png")) {
                Path file = assets.resolve(path);
                if (!Files.isRegularFile(file)) {
                    problems.add("a client source references " + path + " but " + rel(root, file) + " does not exist");
                }
            }
        }
        System.out.println("asset paths referenced from code: " + referenced);

        // ---------------------------------------------------------------- 3. translation keys
        Set<String> keys = new LinkedHashSet<>();
        try (var sources = Files.walk(javaDir)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = TRANSLATABLE.matcher(Files.readString(source));
                while (matcher.find()) {
                    keys.add(matcher.group(1));
                }
            }
        }
        for (String lang : List.of("en_us", "zh_cn")) {
            Path langFile = assets.resolve("lang/" + lang + ".json");
            if (!Files.isRegularFile(langFile)) {
                problems.add("missing language file " + rel(root, langFile));
                continue;
            }
            String text = Files.readString(langFile);
            for (String key : keys) {
                if (!text.contains("\"" + key + "\"")) {
                    problems.add(lang + ".json is missing the translation key '" + key + "'");
                }
            }
            for (String required : List.of("itemGroup." + MOD_ID,
                    "entity." + MOD_ID + ".scav", "entity." + MOD_ID + ".gunner_pillager")) {
                if (!text.contains("\"" + required + "\"")) {
                    problems.add(lang + ".json is missing '" + required + "'");
                }
            }
        }
        System.out.println("translation keys used from code: " + keys.size());

        // ---------------------------------------------------------------- 4. TaCZ is declared, not bundled
        String modsToml = Files.readString(root.resolve("src/main/resources/META-INF/mods.toml"));
        if (!modsToml.contains("modId=\"tacz\"")) {
            problems.add("mods.toml does not declare the tacz dependency");
        } else {
            int index = modsToml.indexOf("modId=\"tacz\"");
            String block = modsToml.substring(index, Math.min(modsToml.length(), index + 300));
            if (!block.contains("mandatory=true")) {
                problems.add("the tacz dependency in mods.toml is not mandatory - a missing TaCZ would"
                        + " only show up as broken gun AI at runtime");
            }
        }
        String buildGradle = Files.readString(root.resolve("build.gradle"));
        Matcher jarJar = Pattern.compile("jarJar\\s*\\(([^)]*)\\)").matcher(buildGradle);
        while (jarJar.find()) {
            if (jarJar.group(1).toLowerCase().contains("tacz")) {
                problems.add("build.gradle embeds TaCZ with jarJar - third-party mods must never be bundled");
            }
        }
        if (!buildGradle.contains("compileOnly") || !buildGradle.contains("runtimeOnly")) {
            problems.add("build.gradle should pull TaCZ in as compileOnly + runtimeOnly");
        }
        System.out.println("mods.toml declares tacz mandatory, build.gradle does not bundle it");

        // ---------------------------------------------------------------- 5. config keys are documented
        Path readme = root.resolve("README.md");
        if (!Files.isRegularFile(readme)) {
            problems.add("README.md is missing - the config keys have to be documented somewhere");
        } else {
            String readmeText = Files.readString(readme);
            Matcher define = CONFIG_DEFINE.matcher(Files.readString(javaDir.resolve("Config.java")));
            Set<String> undocumented = new LinkedHashSet<>();
            while (define.find()) {
                String key = define.group(1);
                if (!readmeText.contains(key)) {
                    undocumented.add(key);
                }
            }
            if (!undocumented.isEmpty()) {
                problems.add("README.md does not document these config keys: " + undocumented);
            } else {
                System.out.println("every config key is documented in README.md");
            }
        }

        // ---------------------------------------------------------------- 6. rig integrity
        // The animation controllers ask for clips by name; a missing one shows up as a mob frozen in
        // its bind pose, and a track pointing at a bone that is not in the geometry is silently
        // ignored by GeckoLib (that is how the author's LeftFoot/leftfoot mismatch hid itself).
        Path rig = root.resolve("assets_source/scav/models/main.json");
        Path animations = assets.resolve("animations/scav.animation.json");
        if (Files.isRegularFile(animations)) {
            problems.addAll(rigCheck(animations, root));
        } else {
            problems.add("missing " + rel(root, animations) + " - run tools/import_scav_assets.js");
        }
        if (!Files.isRegularFile(rig)) {
            problems.add("missing " + rel(root, rig) + " - the YSM model source should stay in assets_source/");
        }

        // ---------------------------------------------------------------- 7. spawn gate config sanity
        Path layout = root.resolve("tools/city-layout.json");
        if (!Files.isRegularFile(layout)) {
            problems.add("tools/city-layout.json is missing - the city preset cannot be regenerated");
        }

        if (!problems.isEmpty()) {
            System.out.println();
            problems.forEach(problem -> System.out.println("FAIL: " + problem));
            throw new IllegalStateException(problems.size() + " asset problem(s)");
        }
        System.out.println("assets OK");
    }

    /**
     * Checks the imported rig: every clip the Java code names exists, and every bone an animation
     * track touches actually exists in the geometry.
     *
     * <p>The second half is not hypothetical. The author's own files disagreed about capitalisation
     * ({@code LeftFoot} in every clip, {@code leftfoot} in the geometry), and GeckoLib matches bone
     * names exactly - so the feet silently never animated until the importer reconciled them.
     * Anything else that does not match is caught here instead of in game.</p>
     */
    private static List<String> rigCheck(Path animationsFile, Path root) throws Exception {
        List<String> problems = new ArrayList<>();

        Map<String, Object> animations = Json.object(Json.parse(animationsFile));
        Map<String, Object> clips = Json.object(animations.get("animations"));
        Set<String> clipNames = clips == null ? Set.of() : new LinkedHashSet<>(clips.keySet());

        // ---- the clips the code plays ------------------------------------------------
        Set<String> expected = new LinkedHashSet<>();
        String clipsSource = Files.readString(root.resolve("src/main/java/com/gfl/tarkovscav/gun/GunClips.java"));
        Matcher literal = Pattern.compile(
                "public static final String (?:IDLE|WALK|RUN|DEATH|ARMED_IDLE|ARMED_WALK|ARMED_RUN)\\s*=\\s*\"([^\"]+)\"")
                .matcher(clipsSource);
        while (literal.find()) {
            expected.add(literal.group(1));
        }
        for (String family : List.of("pistol", "rifle")) {
            for (String action : List.of("hold", "aim", "aim:fire", "reload", "melee")) {
                expected.add("tac:" + action + ":" + family);
            }
        }
        for (String clip : expected) {
            if (!clipNames.contains(clip)) {
                problems.add("the animation file has no clip '" + clip + "' that GunClips plays");
            }
        }
        System.out.println("rig clips: " + clipNames.size() + " present, " + expected.size() + " required by code");

        // ---- every animated bone exists ----------------------------------------------
        Path geoFile = root.resolve("src/main/resources/assets/tarkovscav/geo/scav.geo.json");
        if (!Files.isRegularFile(geoFile)) {
            problems.add("missing " + rel(root, geoFile) + " - run tools/import_scav_assets.js");
            return problems;
        }
        Map<String, Object> geo = Json.object(Json.parse(geoFile));
        List<Object> geometry = Json.array(geo, "minecraft:geometry");
        Set<String> bones = new LinkedHashSet<>();
        for (Object raw : Json.array(Json.object(geometry.get(0)), "bones")) {
            bones.add(Json.string(Json.object(raw), "name", "?"));
        }
        System.out.println("rig bones: " + bones.size());

        int tracks = 0;
        for (Map.Entry<String, Object> clip : clips.entrySet()) {
            Map<String, Object> bonesInClip = Json.object(Json.object(clip.getValue()).get("bones"));
            if (bonesInClip == null) {
                continue;
            }
            for (String bone : bonesInClip.keySet()) {
                tracks++;
                if (!bones.contains(bone)) {
                    problems.add("clip '" + clip.getKey() + "' animates bone '" + bone
                            + "' which is not in the geometry - GeckoLib would ignore that track");
                }
            }
        }
        System.out.println("rig animation tracks checked: " + tracks);

        return problems;
    }

    private static String rel(Path root, Path path) {
        return root.toAbsolutePath().relativize(path.toAbsolutePath()).toString();
    }
}
