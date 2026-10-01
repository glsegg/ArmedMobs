package com.gfl.tarkovscav.entity;

import com.gfl.tarkovscav.Config;
import com.gfl.tarkovscav.faction.Faction;
import com.gfl.tarkovscav.grenade.GrenadeKind;
import com.gfl.tarkovscav.grenade.MobGrenades;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;

/** Hostile Black Fox squad members share the scav's weapon, navigation and save handling. */
public class BlackFoxEntity extends ScavEntity {
    public enum Role {
        ASSAULT("assault"),
        HEAVY("heavy"),
        DEMOLITION("demolition"),
        COMMANDER("commander");

        private final String id;

        Role(String id) {
            this.id = id;
        }

        public String id() {
            return this.id;
        }
    }

    private static final String TAG_VARIANT = "BlackFoxVariant";
    private static final String TAG_AMMO = "BlackFoxAmmo";
    private static final EntityDataAccessor<Integer> VARIANT =
            SynchedEntityData.defineId(BlackFoxEntity.class, EntityDataSerializers.INT);
    private final Role role;

    public BlackFoxEntity(EntityType<? extends BlackFoxEntity> type, Level level, Role role) {
        super(type, level);
        this.role = role;
        this.xpReward = role == Role.HEAVY || role == Role.COMMANDER ? 18 : 14;
    }

    public Role role() {
        return this.role;
    }

    public int getCosmeticVariant() {
        return this.entityData.get(VARIANT);
    }

    @Override
    protected float getStandingEyeHeight(Pose pose, EntityDimensions dimensions) {
        // The helmet extends past the doorway-friendly hitbox. Keep sight and firing rays at face height
        // instead of inheriting the Monster's lower 85%-of-hitbox eye point. No constructor-time role read.
        return Math.min(1.8F, dimensions.height - 0.05F);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        super.registerControllers(controllers);
        // Accessory-only tracks are applied last, leaving the retargeted movement/gun clips intact.
        if (this.role == Role.ASSAULT || this.role == Role.COMMANDER) {
            controllers.add(new AnimationController<>(this, "cosmetics", 0,
                    state -> state.setAndContinue(RawAnimation.begin()
                            .thenLoop("accessory_variant_" + getCosmeticVariant()))));
        }
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(VARIANT, 0);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        // This runs from Mob's constructor; role and other subclass fields are not initialized yet.
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Mob.class, 10, true, false,
                candidate -> candidate != this && Faction.of(candidate) == Faction.SCAV
                        && this.canAttack(candidate)));
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        return !(target instanceof BlackFoxEntity) && !Faction.allies(this, target)
                && super.canAttack(target);
    }

    @Override
    public void setTarget(@Nullable LivingEntity target) {
        // Retaliation and other mods can assign a target directly, including in the bow fallback path.
        super.setTarget(target != null && !canAttack(target) ? null : target);
    }

    @Override
    protected ScavTier forcedSpawnTier() {
        return ScavTier.RIFLE;
    }

    @Override
    public void setScavTier(ScavTier tier) {
        super.setScavTier(ScavTier.RIFLE);
    }

    @Override
    public java.util.Set<String> preferredGunTypes() {
        return java.util.Set.of(this.role == Role.HEAVY ? "mg" : "rifle");
    }

    @Override
    protected void applyTierAttributes(boolean heal) {
        Config.BlackFoxSettings settings = Config.blackFox(this.role);
        if (this.getAttribute(Attributes.MAX_HEALTH) != null) {
            this.getAttribute(Attributes.MAX_HEALTH).setBaseValue(settings.health.get());
        }
        this.setHealth(heal ? this.getMaxHealth() : this.getHealth());
        if (this.getAttribute(Attributes.ARMOR) != null) {
            this.getAttribute(Attributes.ARMOR).setBaseValue(settings.armor.get());
        }
        if (this.getAttribute(Attributes.MOVEMENT_SPEED) != null) {
            this.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(settings.movementSpeed.get());
        }
        if (this.getAttribute(Attributes.ATTACK_DAMAGE) != null) {
            this.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(4.0D);
        }
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                       MobSpawnType reason, @Nullable SpawnGroupData spawnData,
                                       @Nullable CompoundTag dataTag) {
        SpawnGroupData result = super.finalizeSpawn(level, difficulty, reason, spawnData, dataTag);
        this.entityData.set(VARIANT,
                this.role == Role.ASSAULT || this.role == Role.COMMANDER ? this.getRandom().nextInt(2) : 0);
        if (Config.GRENADES_ENABLED.get() && Config.MOB_GRENADES_ENABLED.get()) {
            // The shared pouch respects its global capacity and persists. Reloading never refills it.
            this.getPersistentData().putBoolean("tarkovscav:grenadeRolled", true);
            int count = Config.blackFox(this.role).grenades.get();
            for (int i = 0; i < count; i++) {
                GrenadeKind kind = this.role == Role.DEMOLITION ? GrenadeKind.HE
                        : this.role == Role.COMMANDER && i == 0 ? GrenadeKind.FLASH : GrenadeKind.FRAG;
                if (!MobGrenades.add(this, kind)) {
                    break;
                }
            }
        }
        return result;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt(TAG_VARIANT, getCosmeticVariant());
        ListTag ammo = new ListTag();
        for (int slot = 0; slot < this.ammoInventory().getContainerSize(); slot++) {
            ItemStack stack = this.ammoInventory().getItem(slot);
            if (!stack.isEmpty()) {
                CompoundTag item = stack.save(new CompoundTag());
                item.putInt("Slot", slot);
                ammo.add(item);
            }
        }
        tag.put(TAG_AMMO, ammo);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.entityData.set(VARIANT, Math.max(0, Math.min(1, tag.getInt(TAG_VARIANT))));
        if (com.gfl.tarkovscav.gun.TaczPresence.loaded() && !this.level().isClientSide
                && tag.contains(TAG_AMMO, Tag.TAG_LIST) && this.gunBrain().loadout() != null
                && this.gunBrain().loadout().gunId().toString().equals(tag.getString(TAG_GUN))) {
            // Restore after the inherited weapon initialization. If the gun pack disappeared and the
            // brain issued a different gun, retain that gun's matching ammunition instead.
            this.ammoInventory().clear();
            ListTag ammo = tag.getList(TAG_AMMO, Tag.TAG_COMPOUND);
            for (int i = 0; i < ammo.size(); i++) {
                CompoundTag item = ammo.getCompound(i);
                int slot = item.getInt("Slot");
                if (slot >= 0 && slot < this.ammoInventory().getContainerSize()) {
                    this.ammoInventory().setItem(slot, ItemStack.of(item));
                }
            }
        }
    }

    @Override
    public String toString() {
        return "BlackFox[" + this.role.id() + "]";
    }
}
