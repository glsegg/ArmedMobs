package com.gfl.tarkovscav.lean;

import com.gfl.tarkovscav.TarkovScav;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * The one packet the lean needs (README 5s): <b>client -&gt; server, "I am leaning this much"</b>.
 *
 * <p>The camera effect itself never leaves the client; the server only needs the number because the muzzle of
 * a shot has to move with it ({@link LeanServerEvents}) and because the item-toss guard has to know that the
 * player is holding a lean key. Nothing is sent while the value is unchanged, so holding a lean costs exactly
 * one packet per tick of the 5-tick ramp and then nothing at all.</p>
 */
public final class LeanNetwork {
    private static final String VERSION = "2";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            TarkovScav.id("lean"), () -> VERSION, VERSION::equals, VERSION::equals);

    private LeanNetwork() {
    }

    /** Called from the mod constructor (both sides register; only the client ever sends). */
    public static void register() {
        CHANNEL.registerMessage(0, ServerboundLean.class, ServerboundLean::encode, ServerboundLean::decode,
                ServerboundLean::handle);
    }

    /**
     * Client only: tells the server the current lean amount AND whether a lean key is being held.
     *
     * <p>The second field is what the item-toss guard reads (README 5t). It has to be a separate question from
     * the amount: with {@code startMode = immediate} a tap produces a brief non-zero lean, and the guard must
     * not swallow the vanilla action we replay on release.</p>
     */
    public static void send(float lean, boolean holding) {
        CHANNEL.sendToServer(new ServerboundLean(LeanMath.clamp(lean), holding));
    }

    /** The message: one float, clamped on arrival. */
    public record ServerboundLean(float lean, boolean holding) {
        static void encode(ServerboundLean message, FriendlyByteBuf buffer) {
            buffer.writeFloat(message.lean());
            buffer.writeBoolean(message.holding());
        }

        static ServerboundLean decode(FriendlyByteBuf buffer) {
            return new ServerboundLean(buffer.readFloat(), buffer.readBoolean());
        }

        static void handle(ServerboundLean message, Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(() -> {
                ServerPlayer player = ctx.getSender();
                if (player != null) {
                    // Clamped here as well as on the client: a modified client cannot move its own shots
                    // further than leanMaxOffset, whatever it sends.
                    LeanState.set(player.getUUID(), message.lean());
                    LeanState.setHolding(player.getUUID(), message.holding());
                }
            });
            ctx.setPacketHandled(true);
        }
    }
}
