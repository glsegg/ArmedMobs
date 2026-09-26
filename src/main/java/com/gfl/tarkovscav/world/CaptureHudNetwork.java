package com.gfl.tarkovscav.world;

import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The capture HUD's one packet (README 7p): <b>server -&gt; one player, names and numbers only</b>.
 *
 * <p>Same contract as the kill feed's packet: the server decides what a player is allowed to know (whether
 * they are inside a contested overworld city at all) and sends only data - the city's ledger key and display
 * name, and one bar per faction with its current strength, its opening strength and whether it is spent. No
 * rendering decision, no colours and no layout travel: {@code client/CaptureHud} owns all of that, which is
 * why this needs no resource pack and why a change of look cannot change who wins.</p>
 *
 * <p>{@code hide} exists for the one case a delayed hide would be wrong: the design says the bars vanish
 * <b>immediately</b> when only one faction is left. When a pool is spent the server sends a hide for that
 * city; the client's {@code capture.hudHideDelaySeconds} timer then only covers the "the player walked away"
 * case, where no packet arrives at all.</p>
 */
public final class CaptureHudNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            TarkovScav.id("capture"), () -> VERSION, VERSION::equals, VERSION::equals);

    /** The longest city key / faction name the wire will accept, so a malformed packet cannot blow memory. */
    private static final int MAX_KEY = 256;
    private static final int MAX_NAME = 128;
    private static final int MAX_FACTION = 32;
    private static final int MAX_BARS = 8;

    private CaptureHudNetwork() {
    }

    /** Called from {@code TarkovScav#onCommonSetup} (both sides register; only the server ever sends). */
    public static void register() {
        CHANNEL.registerMessage(0, CaptureHudMessage.class, CaptureHudMessage::encode,
                CaptureHudMessage::decode, CaptureHudMessage::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** Server: the current bars of one city, or a hide. */
    public static void send(ServerPlayer player, CaptureHudMessage message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    /** One faction's bar: the numbers the screen shows, and nothing about how it looks. */
    public record Bar(String faction, int strength, int max, boolean captured) {
        static void encode(Bar bar, FriendlyByteBuf buffer) {
            buffer.writeUtf(bar.faction(), MAX_FACTION);
            buffer.writeVarInt(Math.max(0, bar.strength()));
            buffer.writeVarInt(Math.max(0, bar.max()));
            buffer.writeBoolean(bar.captured());
        }

        static Bar decode(FriendlyByteBuf buffer) {
            return new Bar(buffer.readUtf(MAX_FACTION), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readBoolean());
        }
    }

    /** The message: which city, what to call it, one bar per faction, and whether to hide now. */
    public record CaptureHudMessage(String cityKey, String cityName, List<Bar> bars, boolean hide) {
        static void encode(CaptureHudMessage message, FriendlyByteBuf buffer) {
            if (message.bars().size() > MAX_BARS) {
                throw new IllegalArgumentException("Too many capture HUD bars: " + message.bars().size());
            }
            buffer.writeUtf(message.cityKey(), MAX_KEY);
            buffer.writeUtf(message.cityName(), MAX_NAME);
            buffer.writeVarInt(message.bars().size());
            for (Bar bar : message.bars()) {
                Bar.encode(bar, buffer);
            }
            buffer.writeBoolean(message.hide());
        }

        static CaptureHudMessage decode(FriendlyByteBuf buffer) {
            String cityKey = buffer.readUtf(MAX_KEY);
            String cityName = buffer.readUtf(MAX_NAME);
            int count = buffer.readVarInt();
            if (count < 0 || count > MAX_BARS) {
                throw new io.netty.handler.codec.DecoderException("Invalid capture HUD bar count: " + count);
            }
            List<Bar> bars = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                bars.add(Bar.decode(buffer));
            }
            return new CaptureHudMessage(cityKey, cityName, bars, buffer.readBoolean());
        }

        static void handle(CaptureHudMessage message, Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(() -> com.gfl.tarkovscav.client.CaptureHud.accept(message));
            ctx.setPacketHandled(true);
        }
    }
}
