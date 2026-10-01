import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.faction.AlertNetwork;
import com.gfl.tarkovscav.killfeed.KillFeed;
import com.gfl.tarkovscav.killfeed.KillFeedNetwork;
import com.gfl.tarkovscav.killfeed.KillFeedSource;
import com.gfl.tarkovscav.world.CaptureHudNetwork;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Executes production codecs and validators with the actual mapped Minecraft/Forge dependencies. */
public class NetworkRegressionTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static Object field(Object owner, String name) throws Exception {
        Class<?> type = owner instanceof Class<?> c ? c : owner.getClass();
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner instanceof Class<?> ? null : owner);
    }

    private static Object codec(Class<?> type, String name, Object... args) throws Exception {
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(name)) continue;
            method.setAccessible(true);
            try {
                return method.invoke(null, args);
            } catch (InvocationTargetException error) {
                if (error.getCause() instanceof Exception cause) throw cause;
                throw error;
            }
        }
        throw new AssertionError("Missing codec " + name);
    }

    private static void configNumbers() {
        float[] triple = Config.triple(List.of("NaN", "Infinity", "1e100"), 1, 2, 3);
        check(java.util.Arrays.equals(triple, new float[]{1, 2, 3}), "non-finite model transforms use defaults");
        check(java.util.Arrays.equals(Config.triple(List.of("-3", "1.5", "0"), 1, 2, 3),
                new float[]{-3, 1.5F, 0}), "finite model transforms are preserved");
        var config = com.electronwill.nightconfig.core.CommentedConfig.inMemory();
        Config.SPEC.correct(config);
        Config.SPEC.setConfig(config);
        for (String value : List.of("NaN", "Infinity", "-Infinity", "invalid")) {
            Config.VOICE_FAMILY_VOLUME.set(List.of("shared=" + value));
            check(Config.familyVolume("shared") == 1.0D, "invalid volume uses finite fallback");
        }
        Config.VOICE_FAMILY_VOLUME.set(List.of("shared=2.5", "usec=8", "bear=-1"));
        check(Config.familyVolume("shared") == 2.5D && Config.familyVolume("usec") == 4.0D
                && Config.familyVolume("bear") == 0.0D, "valid volumes retain configured clamping");
        String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        for (int i = 0; i < 8; i++) {
            int sector = AlertNetwork.sectorOf(-180 + i * 45);
            check(AlertNetwork.sectorName(sector).equals(names[i]), "compass label matches Minecraft yaw");
        }
    }

    private static void names() throws Exception {
        for (String name : List.of("SCAV", "x".repeat(256), "x".repeat(1024),
                "x".repeat(255) + "\uD83D\uDE00", "\uD83D\uDE00".repeat(200))) {
            var message = new KillFeedNetwork.KillFeedMessage(name, name, ItemStack.EMPTY, KillFeedSource.FALL, 42);
            check(message.killer().length() <= 256, "kill-feed name bounded before encoding");
            check(!Character.isHighSurrogate(message.killer().charAt(message.killer().length() - 1)),
                    "truncation does not split a surrogate pair");
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                codec(message.getClass(), "encode", message, buffer);
                check(codec(message.getClass(), "decode", buffer).equals(message), "name packet round trip");
                check(!buffer.isReadable(), "name packet consumed exactly");
            } finally { buffer.release(); }
        }
    }

    private static void bars() throws Exception {
        var bar = new CaptureHudNetwork.Bar("scav", 12, 25, false);
        for (int count : new int[]{0, 1, 8}) {
            var message = new CaptureHudNetwork.CaptureHudMessage("city", "City", Collections.nCopies(count, bar), false);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                codec(message.getClass(), "encode", message, buffer);
                check(codec(message.getClass(), "decode", buffer).equals(message), "capture bars round trip " + count);
                check(!buffer.isReadable(), "capture packet consumed exactly");
            } finally { buffer.release(); }
        }
        for (int count : new int[]{-1, 9, Integer.MAX_VALUE}) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeUtf("city").writeUtf("City").writeVarInt(count);
                try {
                    codec(CaptureHudNetwork.CaptureHudMessage.class, "decode", buffer);
                    throw new AssertionError("invalid bar count accepted");
                } catch (DecoderException expected) {
                    check(expected.getMessage().contains("bar count"), "invalid count rejected before reading bar payload");
                }
            } finally { buffer.release(); }
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var oversized = new CaptureHudNetwork.CaptureHudMessage("city", "City", Collections.nCopies(9, bar), false);
            try {
                codec(oversized.getClass(), "encode", oversized, buffer);
                throw new AssertionError("oversized outgoing bars accepted");
            } catch (IllegalArgumentException expected) {
                check(buffer.writerIndex() == 0, "oversized outgoing message rejected before partial encode");
            }
        } finally { buffer.release(); }
    }

    @SuppressWarnings("unchecked")
    private static void lifecycle() throws Exception {
        Map<UUID, int[]> sent = (Map<UUID, int[]>) field(KillFeed.class, "SENT");
        Map<String, Long> seen = (Map<String, Long>) field(KillFeed.class, "SEEN");
        UUID player = UUID.randomUUID();
        sent.put(player, new int[]{1, 42});
        KillFeed.forget(player);
        check(sent.isEmpty(), "logout clears player throttle");
        sent.put(player, new int[]{1, 42});
        seen.put("old world", 400L);
        KillFeed.onServerStopped(null);
        check(sent.isEmpty() && seen.isEmpty(), "server stop clears cross-world state");
    }

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        configNumbers();
        names();
        bars();
        lifecycle();
        System.out.println("NetworkRegressionTest: " + checks + " checks passed");
    }
}
