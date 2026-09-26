package com.gfl.tarkovscav;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;

/** Preserves legacy settings before Forge correction and applies one-time configuration upgrades. */
public final class ConfigMigration {
    private static final List<String> LADDER_SECTIONS = List.of(
            "capture", "command", "client", "killFeed", "troops", "ricochet", "shield", "grenades");
    private static final List<String> RETIRED_CLIENT_KEYS = List.of(
            "leanEnabled", "leanMaxOffset", "leanRollDegrees", "leanInvertOffset", "leanInvertRoll",
            "leanSpeedTicks", "leanSuppressVanillaKeys", "tapThresholdTicks", "startMode", "replayVanillaOnTap");

    private ConfigMigration() {
    }

    /** @return true only when an existing file was migrated; a new installation writes nothing here. */
    public static boolean migrate(Path file) {
        if (Files.notExists(file)) {
            return false;
        }
        Path temporary = null;
        try {
            Path target = file.toRealPath();
            byte[] original = Files.readAllBytes(target);
            CommentedConfig config;
            try (var reader = new InputStreamReader(new ByteArrayInputStream(original),
                    StandardCharsets.UTF_8.newDecoder())) {
                config = new TomlParser().parse(reader);
            }
            // Existing canonical values win, including an explicit value equal to its default.
            boolean changed = moveSection(config, "headAccessories", "client.headAccessories");
            for (String section : LADDER_SECTIONS) {
                changed |= moveSection(config, "ladder." + section, section);
            }
            changed |= moveSection(config, "ladder.headAccessories", "client.headAccessories");
            changed |= migrateGunMount(config);
            for (String key : RETIRED_CLIENT_KEYS) {
                String path = "client." + key;
                if (config.contains(path)) {
                    config.remove(path);
                    config.removeComment(path);
                    changed = true;
                }
            }
            if (!changed) {
                return false;
            }

            StringWriter text = new StringWriter();
            new TomlWriter().write(config, text);
            // Finish writing and parsing the replacement before either the backup or original is changed.
            temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".migration-", ".tmp");
            Files.writeString(temporary, text.toString(), StandardCharsets.UTF_8);
            try (var reader = Files.newBufferedReader(temporary, StandardCharsets.UTF_8)) {
                new TomlParser().parse(reader);
            }
            if (!Arrays.equals(original, Files.readAllBytes(target))) {
                throw new IOException("Config changed during migration; refusing to overwrite it");
            }
            Path backup = Files.createTempFile(target.getParent(),
                    target.getFileName() + ".before-path-migration-", ".bak");
            Files.write(backup, original);
            // No non-atomic fallback: an unsupported move must leave the user's original file intact.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            TarkovScav.LOGGER.info("[config] migrated legacy settings in {}; original backed up to {}",
                    target, backup);
            return true;
        } catch (IOException | RuntimeException error) {
            TarkovScav.LOGGER.error("[config] could not migrate {}; configuration loading stopped to preserve"
                    + " the original file", file, error);
            throw new IllegalStateException("Could not migrate config " + file
                    + "; original file retained. See the preceding error.", error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupError) {
                    TarkovScav.LOGGER.warn("[config] could not remove migration temporary file {}",
                            temporary, cleanupError);
                }
            }
        }
    }

    private static boolean migrateGunMount(CommentedConfig config) {
        String revisionPath = "client.gunMountRevision";
        Object revision = config.get(revisionPath);
        // A completed (or future) revision preserves later user edits, including the old offset.
        // Leave malformed revision values to Forge's validation instead of guessing their history.
        if (config.contains(revisionPath)
                && (!(revision instanceof Number number) || number.doubleValue() >= 1
                        || !Double.isFinite(number.doubleValue()))) {
            return false;
        }
        for (String path : List.of("client.gunMountRifleOffset", "client.gunMountPistolOffset")) {
            Object value = config.get(path);
            if (value instanceof List<?> offset && offset.size() == 3
                    && decimalEquals(offset.get(0), BigDecimal.ZERO)
                    && decimalEquals(offset.get(1), BigDecimal.ZERO)
                    && decimalEquals(offset.get(2), new BigDecimal("-0.7"))) {
                config.set(path, List.of("0", "0", "0"));
            }
        }
        // Only replace complete historical calibration presets. A partly customised profile keeps
        // its values; changing a mount default must never reset unrelated client preferences.
        boolean oldDefaults = decimalEquals(config.get("client.gunnerVillagerAimArmPitch"), new BigDecimal("-57"))
                && tripleEquals(config.get("client.gunnerVillagerGunRotation"), "5", "0", "0")
                && tripleEquals(config.get("client.gunnerVillagerIdleGunRotation"), "-2", "0", "0")
                && tripleEquals(config.get("client.gunnerVillagerGunOffset"), "0", "0.06", "-0.09");
        boolean oldLocalPreset = decimalEquals(config.get("client.gunnerVillagerAimArmPitch"), BigDecimal.ZERO)
                && tripleEquals(config.get("client.gunnerVillagerGunRotation"), "-40", "0", "0")
                && tripleEquals(config.get("client.gunnerVillagerIdleGunRotation"), "-47", "0", "0")
                && tripleEquals(config.get("client.gunnerVillagerGunOffset"), "0", "0.06", "0");
        if (oldDefaults || oldLocalPreset) {
            config.set("client.gunnerVillagerAimArmPitch", -57.0);
            config.set("client.gunnerVillagerGunRotation", List.of("10", "0", "0"));
            config.set("client.gunnerVillagerIdleGunRotation", List.of("-12", "0", "0"));
            for (String pose : List.of("Reload", "Hunker")) {
                String path = "client.gunnerVillager" + pose + "GunRotation";
                if (tripleEquals(config.get(path), "0", "0", "0")) {
                    config.set(path, List.of("-12", "0", "0"));
                }
            }
            config.set("client.gunnerVillagerGunOffset", List.of("0", "0", "0"));
        }
        config.set(revisionPath, 1);
        return true;
    }

    private static boolean tripleEquals(Object value, String x, String y, String z) {
        return value instanceof List<?> triple && triple.size() == 3
                && decimalEquals(triple.get(0), new BigDecimal(x))
                && decimalEquals(triple.get(1), new BigDecimal(y))
                && decimalEquals(triple.get(2), new BigDecimal(z));
    }

    private static boolean decimalEquals(Object value, BigDecimal expected) {
        if (!(value instanceof Number || value instanceof String)) {
            return false;
        }
        try {
            return new BigDecimal(value.toString().trim()).compareTo(expected) == 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean moveSection(CommentedConfig config, String from, String to) {
        if (!config.contains(from)) {
            return false;
        }
        Object old = config.get(from);
        if (!config.contains(to)) {
            config.set(to, old);
            String comment = config.getComment(from);
            if (comment != null) {
                config.setComment(to, comment);
            }
        } else if (old instanceof Config source && config.get(to) instanceof Config destination) {
            mergeMissing(source, destination);
        }
        config.remove(from);
        config.removeComment(from);
        return true;
    }

    private static void mergeMissing(Config source, Config destination) {
        for (var entry : source.valueMap().entrySet()) {
            List<String> path = List.of(entry.getKey());
            if (!destination.contains(path)) {
                destination.set(path, entry.getValue());
                if (source instanceof CommentedConfig commentedSource
                        && destination instanceof CommentedConfig commentedDestination) {
                    String comment = commentedSource.getComment(path);
                    if (comment != null) {
                        commentedDestination.setComment(path, comment);
                    }
                }
            } else if (entry.getValue() instanceof Config nestedSource
                    && destination.get(path) instanceof Config nestedDestination) {
                mergeMissing(nestedSource, nestedDestination);
            }
        }
    }
}
