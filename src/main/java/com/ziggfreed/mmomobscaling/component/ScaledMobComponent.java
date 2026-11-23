package com.ziggfreed.mmomobscaling.component;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;

/**
 * The FROZEN per-mob scaling data, stamped once at spawn by {@code MobScalingSpawnHook} and read (never
 * recomputed) by the damage filter + effect-apply + death path. A TRANSIENT component (registered with a
 * {@code Supplier}, no codec): it is re-derived identically on every {@code onEntityAdd} from the stable
 * spawn seed, so a chunk reload reproduces the same rarity/affixes without persistence (the gap-note
 * lifecycle decision).
 *
 * <p>Holds an immutable {@link MobScaleResult}; {@link #clone()} shares that reference (safe - it is
 * immutable). The no-arg constructor (used by the engine's default {@code Supplier}) yields a benign plain
 * result so an unpopulated instance is never harmful; the spawn hook stamps a populated instance via the
 * IDEMPOTENT {@code Holder.putComponent} (never {@code addComponent}, which throws when a re-added holder
 * already carries the type).
 *
 * <p>The ONE mutable field is {@link #bonusLootDropped()}, a death-path latch so the bonus-loot ticking
 * system pays a corpse exactly once (mirrors the vanilla {@code Role.hasDroppedDeathItems} latch);
 * {@link #clone()} copies it so an archetype move mid-death cannot re-arm the drop.
 *
 * <p>{@link #companion()} marks an encounter's boss scaled through his fight's binding row
 * ({@code event/EncounterSubjectScaling}) rather than rolled: he carries a health bar and a hit and nothing
 * else, so the caster arm leaves him alone when an in-place role change re-adds him. Cloned with the rest.
 */
public final class ScaledMobComponent implements Component<EntityStore> {

    @Nonnull
    private final MobScaleResult result;

    /** Whether this is an encounter boss's companion scale, never a roll. */
    private final boolean companion;

    /** Death-path latch: set once by {@code MobScalingLootDropSystem} when the bonus loot has dropped. */
    private volatile boolean bonusLootDropped;

    /**
     * The party this mob counted the first time it turned on a player ({@code event/MobScalingEngageSystem}), 0 until
     * then; and the health multiplier that party gave it on top of {@link MobScaleResult#hpMult()} (1.0 for one player
     * or none, or where the world's party rule is off). Set once, on the world thread, never reset in this life.
     */
    private volatile int partySize;
    private volatile float partyHealthMult = 1f;

    /** Engine default-supplier constructor: a benign plain result (overwritten by the populated add). */
    public ScaledMobComponent() {
        this(MobScaleFold.plain(0.0, MobScaleResult.SCOPE_HOSTILE, MobScaleFold.DifficultyStatCurve.NONE), false);
    }

    public ScaledMobComponent(@Nonnull MobScaleResult result) {
        this(result, false);
    }

    private ScaledMobComponent(@Nonnull MobScaleResult result, boolean companion) {
        this.result = result;
        this.companion = companion;
    }

    /** The stamp for an encounter's boss scaled through his fight's binding row ({@link #companion()}). */
    @Nonnull
    public static ScaledMobComponent ofCompanion(@Nonnull MobScaleResult result) {
        return new ScaledMobComponent(result, true);
    }

    @Nonnull
    public MobScaleResult result() {
        return result;
    }

    /** True for an encounter boss's companion scale: no rarity, no affix, no aura and no caster kit. */
    public boolean companion() {
        return companion;
    }

    /** True once the death bonus loot has been dropped for this mob (one-shot latch). */
    public boolean bonusLootDropped() {
        return bonusLootDropped;
    }

    /** Latch the death bonus loot as dropped (never unlatched; the corpse is removed shortly after). */
    public void markBonusLootDropped() {
        bonusLootDropped = true;
    }

    /** The players this mob counted when it first turned on one (0 until it has). */
    public int partySize() {
        return partySize;
    }

    /** The health multiplier its party gave it, on top of the roll's {@link MobScaleResult#hpMult()}. */
    public float partyHealthMult() {
        return partyHealthMult;
    }

    /**
     * The health multiplier this mob's maximum health carries in all: the place's ({@link MobScaleResult#hpMult()})
     * times its party's ({@link #partyHealthMult()}). What the {@code mmoscaling_hp} reconcile writes once the party
     * is counted.
     */
    public double healthMult() {
        return (double) result.hpMult() * partyHealthMult;
    }

    /** Record the party counted at the first engagement and the multiplier it gave (once per life). */
    public void markParty(int players, double healthMult) {
        partySize = Math.max(0, players);
        partyHealthMult = (float) Math.max(1.0, healthMult);
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        ScaledMobComponent copy = new ScaledMobComponent(result, companion); // result is immutable - safe to share
        copy.bonusLootDropped = bonusLootDropped;
        copy.partySize = partySize;
        copy.partyHealthMult = partyHealthMult;
        return copy;
    }

    /** The registered component type (resolved via the plugin singleton; registered in {@code setup()}). */
    @Nonnull
    public static ComponentType<EntityStore, ScaledMobComponent> getComponentType() {
        return MobScalingPlugin.getInstance().getScaledMobComponentType();
    }
}
