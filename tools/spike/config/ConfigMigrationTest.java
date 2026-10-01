import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.ConfigMigration;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import net.minecraftforge.common.ForgeConfigSpec;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Real Forge config paths plus real NightConfig/TOML migrations, using only temporary fixture files. */
public final class ConfigMigrationTest {
    private static int checks;
    private static final List<String> SECTIONS = List.of("capture", "command", "client", "killFeed",
            "troops", "ricochet", "shield", "grenades");

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        paths();
        Path root = Files.createTempDirectory(Path.of(args[0]), "config-cases-");
        newConfiguration(root);
        oldConfiguration(root);
        canonicalWins(root);
        retiredLeanSettings(root);
        gunMountUpgrade(root);
        villagerMountUpgrade(root);
        failuresPreserveOriginal(root);
        System.out.println("ConfigMigrationTest: " + checks + " checks passed; fixtures " + root);
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void paths() throws Exception {
        int ladder = 0;
        int fields = 0;
        for (Field field : Config.class.getDeclaredFields()) {
            if (!ForgeConfigSpec.ConfigValue.class.isAssignableFrom(field.getType())) continue;
            fields++;
            field.setAccessible(true);
            var value = (ForgeConfigSpec.ConfigValue<?>) field.get(null);
            if (value.getPath().get(0).equals("ladder")) {
                check(value.getPath().size() == 2, "unrelated section nested under ladder: " + value.getPath());
                ladder++;
            }
        }
        check(ladder == 7, "only the seven ladder settings belong in ladder");
        check(Config.CAPTURE_ENABLED.getPath().equals(List.of("capture", "enabled")), "capture path");
        check(Config.COMMAND_ENABLED.getPath().equals(List.of("command", "enabled")), "command path");
        check(Config.USE_GECKO_MODEL.getPath().equals(List.of("client", "useGeckoModel")), "client path");
        check(Config.GUN_MOUNT_REVISION.getPath().equals(List.of("client", "gunMountRevision")),
                "gun mount revision belongs to the canonical client section");
        check(Config.GUN_MOUNT_REVISION.getDefault() == 1, "new installations start at gun mount revision one");
        check(Config.KILLFEED_ENABLED.getPath().equals(List.of("killFeed", "enabled")), "kill feed path");
        check(Config.ARMOR_ENABLED.getPath().equals(List.of("troops", "armorEnabled")), "troop path");
        check(Config.RICOCHET_ENABLED.getPath().equals(List.of("ricochet", "enabled")), "ricochet path");
        check(Config.SHIELD_ENABLED.getPath().equals(List.of("shield", "enabled")), "shield path");
        check(Config.GRENADES_ENABLED.getPath().equals(List.of("grenades", "enabled")), "grenade path");
        check(Config.HAT_ACCESSORY.getPath().equals(List.of("client", "headAccessories", "hatAccessory")),
                "head accessories retain their documented client parent");
        System.out.println("Config paths: " + fields + " static ConfigValue fields, "
                + countValues(Config.SPEC.getValues()) + " total spec leaves");
    }

    private static int countValues(com.electronwill.nightconfig.core.UnmodifiableConfig config) {
        int count = 0;
        for (Object value : config.valueMap().values()) {
            if (value instanceof com.electronwill.nightconfig.core.UnmodifiableConfig nested) {
                count += countValues(nested);
            } else {
                check(value instanceof ForgeConfigSpec.ConfigValue<?>, "spec leaf must remain a config value");
                count++;
            }
        }
        return count;
    }

    private static CommentedConfig read(Path file) throws Exception {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new TomlParser().parse(reader);
        }
    }

    private static List<Path> backups(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".bak")).toList();
        }
    }

    private static String legacy() {
        return """
                [ladder]
                enabled = false
                climbSpeed = 0.33
                customFlag = "untouched"
                [ladder.capture]
                # Keep this custom pool minimum
                poolMin = 37
                enabled = false
                [ladder.command]
                enabled = false
                [ladder.client]
                useGeckoModel = true
                leanEnabled = false
                gunMountRifleOffset = ["0", "0", "-0.7"]
                gunMountPistolOffset = [0.0, 0.0, -0.7]
                [ladder.killFeed]
                enabled = false
                mode = "global"
                [ladder.troops]
                armorEnabled = false
                [ladder.ricochet]
                enabled = false
                [ladder.shield]
                enabled = false
                [ladder.grenades]
                enabled = false
                [ladder.grenades.mob]
                enabled = false
                [ladder.headAccessories]
                hatAccessory = "hideAll"
                eyeGearAccessory = "none"
                """;
    }

    private static void newConfiguration(Path root) throws Exception {
        Path missing = root.resolve("new-install/config/tarkovscav-common.toml");
        check(!ConfigMigration.migrate(missing), "a new config requires no migration");
        check(!Files.exists(missing.getParent()), "new installation must not write files or directories");
        Path modern = root.resolve("modern.toml");
        String text = "[capture]\nenabled = false\n[client]\ngunMountRevision = 1\n"
                + "[client.headAccessories]\nhatAccessory = \"hideAll\"\n";
        Files.writeString(modern, text);
        check(!ConfigMigration.migrate(modern), "canonical config requires no migration");
        check(Files.readString(modern).equals(text), "canonical formatting remains byte unchanged");
    }

    private static void oldConfiguration(Path root) throws Exception {
        Path directory = Files.createDirectory(root.resolve("legacy"));
        Path file = directory.resolve("tarkovscav-common.toml");
        byte[] original = legacy().getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);
        check(ConfigMigration.migrate(file), "legacy sections are migrated");
        CommentedConfig parsed = read(file);
        for (String section : SECTIONS) {
            check(parsed.contains(section) && !parsed.contains("ladder." + section), "section moved: " + section);
        }
        check(Boolean.FALSE.equals(parsed.get("ladder.enabled")), "actual ladder switch survives");
        check(Double.valueOf(0.33).equals(parsed.get("ladder.climbSpeed")), "actual ladder speed survives");
        check("untouched".equals(parsed.get("ladder.customFlag")), "unknown ladder data is not moved");
        check("hideAll".equals(parsed.get("client.headAccessories.hatAccessory")), "head accessory path migrates");
        check(!parsed.contains("ladder.headAccessories"), "old head accessory path removed");
        check(List.of("0", "0", "0").equals(parsed.get("client.gunMountRifleOffset"))
                        && List.of("0", "0", "0").equals(parsed.get("client.gunMountPistolOffset")),
                "gun offsets upgrade after migrating the legacy client path");
        check(Integer.valueOf(1).equals(parsed.get("client.gunMountRevision")), "legacy client receives revision one");
        check(parsed.getComment("capture.poolMin").contains("custom pool"), "custom field comments survive");
        List<Path> backup = backups(directory);
        check(backup.size() == 1 && Arrays.equals(original, Files.readAllBytes(backup.get(0))),
                "unique backup contains exact original bytes");
        byte[] migrated = Files.readAllBytes(file);
        check(!ConfigMigration.migrate(file), "second migration is a no-op");
        check(Arrays.equals(migrated, Files.readAllBytes(file)) && backups(directory).size() == 1,
                "idempotence preserves file bytes and does not create another backup");
        Config.SPEC.correct(parsed);
        Config.SPEC.setConfig(parsed);
        check(!Config.CAPTURE_ENABLED.get() && Config.CAPTURE_POOL_MIN.get() == 37,
                "Forge sees migrated custom capture settings after its normal correction");
        check(Config.USE_GECKO_MODEL.get() && !Config.COMMAND_ENABLED.get() && !Config.GRENADES_ENABLED.get(),
                "Forge sees migrated custom feature settings");
        check(!parsed.contains("client.leanEnabled"), "nested legacy lean switch is retired");
        check("hideAll".equals(Config.HAT_ACCESSORY.get()), "Forge reads migrated accessories at canonical path");

        Files.write(file, original);
        check(ConfigMigration.migrate(file), "a later restored legacy file can also migrate");
        check(backups(directory).size() == 2 && Arrays.equals(original, Files.readAllBytes(backup.get(0))),
                "later migrations cannot overwrite an earlier backup");
    }

    private static void canonicalWins(Path root) throws Exception {
        Path directory = Files.createDirectory(root.resolve("mixed"));
        Path file = directory.resolve("tarkovscav-common.toml");
        Files.writeString(file, legacy() + """
                [capture]
                enabled = true
                [grenades.mob]
                enabled = true
                [headAccessories]
                hatAccessory = "keepOne"
                extraHiddenBones = ["a_custom_hat"]
                [client.headAccessories]
                hatAccessory = "showAll"
                """);
        check(ConfigMigration.migrate(file), "mixed canonical and legacy config migrates");
        CommentedConfig parsed = read(file);
        check(Boolean.TRUE.equals(parsed.get("capture.enabled")), "explicit canonical default beats legacy custom value");
        check(Integer.valueOf(37).equals(parsed.get("capture.poolMin")), "missing sibling fields are filled from legacy");
        check(Boolean.TRUE.equals(parsed.get("grenades.mob.enabled")), "nested canonical value wins");
        check(Boolean.FALSE.equals(parsed.get("grenades.enabled")), "missing parent value migrates");
        check("showAll".equals(parsed.get("client.headAccessories.hatAccessory")), "canonical accessory value wins");
        check(List.of("a_custom_hat").equals(parsed.get("client.headAccessories.extraHiddenBones")),
                "root legacy accessories are merged, including lists");
        check("none".equals(parsed.get("client.headAccessories.eyeGearAccessory")), "old ladder accessory sibling survives");
        check(!parsed.contains("headAccessories"), "root legacy accessory section is removed");
    }

    private static void retiredLeanSettings(Path root) throws Exception {
        Path directory = Files.createDirectory(root.resolve("retired-lean"));
        Path file = directory.resolve("tarkovscav-common.toml");
        List<String> keys = List.of("leanEnabled", "leanMaxOffset", "leanRollDegrees", "leanInvertOffset",
                "leanInvertRoll", "leanSpeedTicks", "leanSuppressVanillaKeys", "tapThresholdTicks",
                "startMode", "replayVanillaOnTap");
        StringBuilder original = new StringBuilder("[client]\nuseGeckoModel = true\ncustomOption = 7\n");
        for (String key : keys) original.append(key).append(" = false\n");
        Files.writeString(file, original);
        check(ConfigMigration.migrate(file), "retired options alone trigger a backed-up migration");
        CommentedConfig parsed = read(file);
        for (String key : keys) {
            check(!parsed.contains("client." + key), "retired key removed: " + key);
            check(!Config.SPEC.getValues().contains("client." + key), "retired key absent from Forge spec: " + key);
        }
        check(Boolean.TRUE.equals(parsed.get("client.useGeckoModel")), "unrelated renderer option preserved");
        check(Integer.valueOf(7).equals(parsed.get("client.customOption")), "unknown user setting preserved");
        check(backups(directory).size() == 1 && Files.readString(backups(directory).get(0)).equals(original.toString()),
                "retired options retained in exact original backup");
        check(!ConfigMigration.migrate(file), "retired key cleanup is idempotent");
    }

    private static void gunMountUpgrade(Path root) throws Exception {
        Path directory = Files.createDirectory(root.resolve("gun-mount"));
        String[] offsets = {
                "[\"0\", \"0\", \"-0.7\"]", "[0, 0, -0.7]", "[-0.0, 0.0, -7e-1]",
                "[\" 0.00 \", \"-0\", \" -7.00e-1 \"]",
                "[\"0\", \"0\", \"-0.70000000000000000001\"]", "[0.001, 0, -0.7]",
                "[0, -0.001, -0.7]", "[0, 0, -0.6]", "[0, 0]", "[0, 0, -0.7, 0]",
                "[\"0\", \"oops\", \"-0.7\"]", "[\"0\", \"0\", \"NaN\"]", "[false, false, -0.7]"
        };
        for (int i = 0; i < offsets.length; i++) {
            Path file = directory.resolve("offset-" + i + ".toml");
            String original = "[client]\ngunMountRifleOffset = " + offsets[i]
                    + "\ngunMountPistolOffset = [\"0.2\", \"0\", \"-0.7\"]\n"
                    + "gunMountOffhandOffset = [\"0\", \"0\", \"-0.7\"]\n";
            Files.writeString(file, original, StandardCharsets.UTF_8);
            Object before = read(file).get("client.gunMountRifleOffset");
            check(ConfigMigration.migrate(file), "unversioned gun mount records its upgrade: " + i);
            CommentedConfig parsed = read(file);
            check(Integer.valueOf(1).equals(parsed.get("client.gunMountRevision")), "gun mount revision recorded: " + i);
            check((i < 4 ? List.of("0", "0", "0") : before).equals(parsed.get("client.gunMountRifleOffset")),
                    "only exact numerical historical offsets change: " + i);
            check(List.of("0.2", "0", "-0.7").equals(parsed.get("client.gunMountPistolOffset")),
                    "independent custom pistol offset preserved: " + i);
            check(List.of("0", "0", "-0.7").equals(parsed.get("client.gunMountOffhandOffset")),
                    "unrelated offsets are never migrated: " + i);
            byte[] migrated = Files.readAllBytes(file);
            check(!ConfigMigration.migrate(file) && Arrays.equals(migrated, Files.readAllBytes(file)),
                    "offset migration runs only once: " + i);
        }

        for (int revision : List.of(-1, 0, 1, 2)) {
            Path file = directory.resolve("revision-" + revision + ".toml");
            String original = "[client]\ngunMountRevision = " + revision
                    + "\ngunMountRifleOffset = [\"0\", \"0\", \"-0.7\"]\n"
                    + "gunMountPistolOffset = [\"0\", \"0\", \"-0.7\"]\n";
            Files.writeString(file, original, StandardCharsets.UTF_8);
            int beforeBackups = backups(directory).size();
            check(ConfigMigration.migrate(file) == (revision < 1), "revision controls migration: " + revision);
            CommentedConfig parsed = read(file);
            if (revision >= 1) {
                check(Files.readString(file, StandardCharsets.UTF_8).equals(original),
                        "completed or future revision remains byte unchanged: " + revision);
                check(backups(directory).size() == beforeBackups, "modern file needs no backup: " + revision);
            } else {
                check(List.of("0", "0", "0").equals(parsed.get("client.gunMountRifleOffset"))
                                && List.of("0", "0", "0").equals(parsed.get("client.gunMountPistolOffset")),
                        "both historical offsets migrate: " + revision);
                check(Integer.valueOf(1).equals(parsed.get("client.gunMountRevision")), "stale revision advances to one");
                List<Path> matchingBackups = backups(directory).stream()
                        .filter(path -> path.getFileName().toString().startsWith(file.getFileName().toString() + "."))
                        .toList();
                check(matchingBackups.size() == 1
                                && Files.readString(matchingBackups.get(0), StandardCharsets.UTF_8).equals(original),
                        "offset upgrade backs up exact original bytes: " + revision);
                parsed.set("client.gunMountRifleOffset", List.of("0", "0", "-0.7"));
                try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    new TomlWriter().write(parsed, writer);
                }
                byte[] edited = Files.readAllBytes(file);
                check(!ConfigMigration.migrate(file) && Arrays.equals(edited, Files.readAllBytes(file)),
                        "an intentional later return to the old offset survives: " + revision);
                check(backups(directory).size() == beforeBackups + 1, "later edits create no further backup");
            }
        }

        Path mixed = directory.resolve("mixed-paths.toml");
        Files.writeString(mixed, """
                [ladder.client]
                gunMountRevision = 0
                gunMountRifleOffset = ["0", "0", "-0.7"]
                gunMountPistolOffset = ["0", "0", "-0.7"]
                [client]
                gunMountRevision = 1
                gunMountRifleOffset = ["0.5", "0", "0"]
                """, StandardCharsets.UTF_8);
        check(ConfigMigration.migrate(mixed), "old path merges before checking the canonical revision");
        CommentedConfig mixedConfig = read(mixed);
        check(List.of("0.5", "0", "0").equals(mixedConfig.get("client.gunMountRifleOffset")),
                "canonical customized rifle offset wins over old paths");
        check(List.of("0", "0", "-0.7").equals(mixedConfig.get("client.gunMountPistolOffset")),
                "canonical completed revision prevents remigrating merged offsets");
        check(Integer.valueOf(1).equals(mixedConfig.get("client.gunMountRevision"))
                        && !mixedConfig.contains("ladder.client"), "canonical revision wins and old client path is removed");
    }

    private static void villagerMountUpgrade(Path root) throws Exception {
        Path directory = Files.createDirectory(root.resolve("villager-mount"));
        for (boolean local : List.of(false, true)) {
            String original = "[client]\ngunnerVillagerAimArmPitch = " + (local ? "0" : "-57")
                    + "\ngunnerVillagerGunRotation = [\"" + (local ? "-40" : "5") + "\",\"0\",\"0\"]"
                    + "\ngunnerVillagerIdleGunRotation = [\"" + (local ? "-47" : "-2") + "\",\"0\",\"0\"]"
                    + "\ngunnerVillagerGunOffset = [\"0\",\"0.06\",\"" + (local ? "0" : "-0.09") + "\"]\n"
                    + "gunnerVillagerReloadGunRotation = [\"0\",\"0\",\"0\"]\n"
                    + "gunnerVillagerHunkerGunRotation = [\"0\",\"0\",\"0\"]\n";
            for (int variant = 0; variant < 5; variant++) {
                Path file = directory.resolve(local + "-" + variant + ".toml");
                String input = variant == 1 ? original.replace("0.06", "0.061")
                        : variant == 2 ? original + "gunMountRevision = 1\n"
                        : variant == 3 ? original.replace("gunnerVillagerReloadGunRotation = [\"0\",\"0\",\"0\"]",
                                "gunnerVillagerReloadGunRotation = [\"-8\",\"2\",\"1\"]")
                        : variant == 4 ? original.replace("gunnerVillagerHunkerGunRotation = [\"0\",\"0\",\"0\"]",
                                "gunnerVillagerHunkerGunRotation = [\"-7\",\"3\",\"2\"]") : original;
                Files.writeString(file, input, StandardCharsets.UTF_8);
                CommentedConfig before = read(file);
                ConfigMigration.migrate(file);
                CommentedConfig after = read(file);
                for (String key : List.of("gunnerVillagerAimArmPitch", "gunnerVillagerGunRotation",
                        "gunnerVillagerIdleGunRotation", "gunnerVillagerGunOffset")) {
                    String path = "client." + key;
                    Object expected = variant == 0 || variant >= 3 ? switch (key) {
                        case "gunnerVillagerAimArmPitch" -> -57.0;
                        case "gunnerVillagerGunRotation" -> List.of("10", "0", "0");
                        case "gunnerVillagerIdleGunRotation" -> List.of("-12", "0", "0");
                        default -> List.of("0", "0", "0");
                    } : before.get(path);
                    check(expected.equals(after.get(path)), "exact preset only: " + local + "/" + variant + "/" + key);
                }
                for (String pose : List.of("Reload", "Hunker")) {
                    String path = "client.gunnerVillager" + pose + "GunRotation";
                    boolean custom = (variant == 3 && pose.equals("Reload"))
                            || (variant == 4 && pose.equals("Hunker"));
                    Object expected = (variant == 0 || variant >= 3) && !custom
                            ? List.of("-12", "0", "0") : before.get(path);
                    check(expected.equals(after.get(path)),
                            "zero pose delta upgrades, custom and versioned values survive: " + local + "/" + variant + "/" + pose);
                }
                check(!ConfigMigration.migrate(file), "villager calibration migration idempotent");
            }
        }
    }

    private static void failuresPreserveOriginal(Path root) throws Exception {
        Path directory = Files.createDirectory(root.resolve("invalid"));
        Path file = directory.resolve("tarkovscav-common.toml");
        byte[] invalid = "[ladder.capture]\nenabled = [\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, invalid);
        try {
            ConfigMigration.migrate(file);
            throw new AssertionError("invalid TOML was accepted");
        } catch (IllegalStateException expected) {
            check(Arrays.equals(invalid, Files.readAllBytes(file)), "parse failure preserves original bytes");
            check(backups(directory).isEmpty(), "parse failure does not produce a misleading migration backup");
        }
        Path notAFile = Files.createDirectory(root.resolve("directory-instead-of-config.toml"));
        Files.writeString(notAFile.resolve("keep.txt"), "keep");
        try {
            ConfigMigration.migrate(notAFile);
            throw new AssertionError("unreadable config path was accepted");
        } catch (IllegalStateException expected) {
            check("keep".equals(Files.readString(notAFile.resolve("keep.txt"))), "IO failure preserves original directory contents");
        }
    }
}
