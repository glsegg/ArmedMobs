package com.gfl.tarkovscav.command;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The thrown signal stick: lands, plants a mark that lives {@code command.stickDurationTicks} (5 minutes),
 * and drops itself so it can be picked back up.
 *
 * <h2>A tool, not a charge</h2>
 * <p>The design doc is explicit: all three command implements are tools. So the stick is never consumed -
 * it is thrown, it lands, it drops its own item at the landing spot with a short pickup delay, and the
 * player can walk over and take it back. That is also what makes "throw a mark now, retrieve the stick
 * later" a real tactic.</p>
 *
 * <h2>Where the mark goes</h2>
 * <p>On a block hit, the marked block is the one the stick <b>hit</b> (or the cell above it when it hit a
 * face) - never the cell it happens to be inside when the physics settles, which would put a mark inside a
 * wall. The mark itself is created by {@link CommandMarks#create}, so the letter, the cap and the expiry
 * rule are the same ones every other source uses.</p>
 *
 * <p>Nothing here damages anything: the entity overrides {@code onHit} instead of the item-projectile
 * default of hitting like a snowball.</p>
 */
public class SignalStickEntity extends ThrowableItemProjectile {
    /** The item is dropped with this pickup delay, so the thrower does not instantly re-absorb it. */
    private static final int PICKUP_DELAY_TICKS = 20;

    /** Guards against a double hit report (block + entity on the same tick) making two marks. */
    private boolean landed;

    public SignalStickEntity(EntityType<? extends SignalStickEntity> type, Level level) {
        super(type, level);
    }

    public SignalStickEntity(Level level, LivingEntity thrower) {
        super(com.gfl.tarkovscav.registry.ModEntities.SIGNAL_STICK.get(), thrower, level);
    }

    @Override
    protected Item getDefaultItem() {
        return com.gfl.tarkovscav.registry.ModItems.SIGNAL_STICK.get();
    }

    @Override
    protected void onHit(HitResult result) {
        if (this.landed) {
            return;
        }
        this.landed = true;
        if (this.level() instanceof ServerLevel level) {
            place(level, result);
        }
        this.discard();
    }

    private void place(ServerLevel level, HitResult result) {
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(
                this.getX(), this.getY(), this.getZ());
        if (result instanceof BlockHitResult blockHit) {
            pos = blockHit.getBlockPos();
            // Standing the mark on the surface that was hit reads far better than in the wall behind it.
            if (!level.getBlockState(pos).isAir()) {
                pos = pos.relative(blockHit.getDirection());
            }
        }
        CommandMark mark = CommandMarks.create(level, pos, CommandMark.Source.STICK, level.getGameTime());

        // Particle + sound feedback: the design doc asks for the countdown to be visible when it ends, and
        // the flight itself is visible from the trail below.
        level.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                24, 0.4D, 0.4D, 0.4D, 0.02D);
        level.sendParticles(ParticleTypes.FLAME, pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D,
                8, 0.15D, 0.15D, 0.15D, 0.01D);
        level.playSound(null, pos, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 1.0F, 1.3F);

        // The stick is a tool: it drops where it landed and can be picked back up.
        ItemStack stack = this.getItem().copy();
        if (stack.isEmpty()) {
            stack = new ItemStack(com.gfl.tarkovscav.registry.ModItems.SIGNAL_STICK.get());
        }
        ItemEntity dropped = new ItemEntity(level, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, stack);
        dropped.setPickUpDelay(PICKUP_DELAY_TICKS);
        level.addFreshEntity(dropped);
    }

    @Override
    public void tick() {
        super.tick();
        // A short trail, so a thrown stick is visible in flight.
        if (this.level().isClientSide) {
            this.level().addParticle(ParticleTypes.SMOKE, this.getX(), this.getY(), this.getZ(),
                    0.0D, 0.0D, 0.0D);
            return;
        }
        // Safety net: a stick that never reports a hit (unloaded chunk, a hit it slept through) must not
        // orbit forever and must not silently lose its mark.
        if (this.tickCount > 200) {
            if (!this.landed) {
                this.landed = true;
                if (this.level() instanceof ServerLevel level) {
                    place(level, null);
                }
            }
            this.discard();
        }
    }
}
