package com.gfl.tarkovscav.killfeed;

import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;
import java.util.Optional;

/**
 * The kill feed's packet (README 5u): <b>server -&gt; one player, one already-decided line</b>.
 *
 * <p>Everything that decides <em>whether</em> a player may see a line (the visibility mode, the radius, the
 * throttle, the dedup window) happens on the server, so the client cannot be used to learn about deaths it was
 * not meant to see. What travels is only data: two names and - for an item kill - the weapon stack, plus a
 * category and a timestamp. The client renders it with its own font and its own language; a name that is too
 * long is truncated rather than wrapped, and nothing is clickable.</p>
 */
public final class KillFeedNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            TarkovScav.id("killfeed"), () -> VERSION, VERSION::equals, VERSION::equals);

    private KillFeedNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, KillFeedMessage.class, KillFeedMessage::encode, KillFeedMessage::decode,
                KillFeedMessage::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        // The flashbang overlay rides the same server -> client channel (README 5v): it is the other thing this
        // mod has to tell one player's screen about, and it is the same kind of data (two numbers).
        CHANNEL.registerMessage(1, FlashMessage.class, FlashMessage::encode, FlashMessage::decode,
                FlashMessage::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** Server: one line to one player. */
    public static void send(ServerPlayer player, KillFeedMessage message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    /** Server: blind one player (README 5v). */
    public static void sendFlash(ServerPlayer player, double intensity, int ticks) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new FlashMessage(intensity, ticks));
    }

    /** A flashbang going off in your face: how bright, and for how long. */
    public record FlashMessage(double intensity, int ticks) {
        static void encode(FlashMessage message, FriendlyByteBuf buffer) {
            buffer.writeDouble(message.intensity());
            buffer.writeVarInt(message.ticks());
        }

        static FlashMessage decode(FriendlyByteBuf buffer) {
            return new FlashMessage(buffer.readDouble(), buffer.readVarInt());
        }

        static void handle(FlashMessage message, Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(() -> com.gfl.tarkovscav.client.FlashOverlay.accept(message.intensity(),
                    message.ticks()));
            ctx.setPacketHandled(true);
        }
    }

    /** The line: names + weapon + category + the tick it happened. */
    public record KillFeedMessage(String killer, String victim, ItemStack weapon, KillFeedSource source,
                                  long gameTime) {
        public KillFeedMessage {
            killer = boundedName(killer);
            victim = boundedName(victim);
        }

        private static String boundedName(String name) {
            if (name.length() <= 256) {
                return name;
            }
            // Respect FriendlyByteBuf's UTF-16 length limit without splitting an emoji pair.
            int end = Character.isHighSurrogate(name.charAt(255)) ? 255 : 256;
            return name.substring(0, end);
        }

        static void encode(KillFeedMessage message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.killer(), 256);
            buffer.writeUtf(message.victim(), 256);
            buffer.writeEnum(message.source());
            // Only an item kill carries a stack: that keeps an environment death a handful of bytes.
            buffer.writeBoolean(message.source() == KillFeedSource.ITEM);
            if (message.source() == KillFeedSource.ITEM) {
                buffer.writeItem(message.weapon());
            }
            buffer.writeVarLong(message.gameTime());
        }

        static KillFeedMessage decode(FriendlyByteBuf buffer) {
            String killer = buffer.readUtf(256);
            String victim = buffer.readUtf(256);
            KillFeedSource source = buffer.readEnum(KillFeedSource.class);
            ItemStack weapon = buffer.readBoolean() ? buffer.readItem() : ItemStack.EMPTY;
            return new KillFeedMessage(killer, victim, weapon, source, buffer.readVarLong());
        }

        static void handle(KillFeedMessage message, Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(() -> com.gfl.tarkovscav.client.KillFeedHud.accept(message));
            ctx.setPacketHandled(true);
        }
    }
}
