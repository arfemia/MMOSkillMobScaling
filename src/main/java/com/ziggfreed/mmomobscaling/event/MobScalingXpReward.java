package com.ziggfreed.mmomobscaling.event;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.progress.runtime.KillRewardScale;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.config.PlayerPowerReads;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;

/**
 * The kill reward: a ziggfreed-common {@link KillRewardScale} the plugin contributes so a scaled/rare mob pays MORE
 * wherever a payer of kill rewards reads the composed scale (MMO Skill Tree multiplies its kill XP by it). Reading the
 * victim's own {@link ScaledMobComponent} keeps either mod from importing the other. This is the reward half of the
 * risk/reward loop - without it a rarity kill pays nothing extra.
 *
 * <p>The multiplier is the rarity's authored {@code XpMult} (plain mobs = {@code 1.0}, unchanged), plus an
 * UNDERDOG bonus for a RARITY kill whose difficulty exceeds the killer's power (fighting above your weight
 * pays), the power read through ziggfreed-common's player power seam. The whole thing is clamped to
 * {@link #MAX_XP_MULT} so it can never run away. A payer asks it once per kill, never per hit (per-hit rewards
 * already scale with the mob's extra health).
 */
public final class MobScalingXpReward implements KillRewardScale {

    /** Anti-runaway hard ceiling on the total kill-XP multiplier. */
    private static final double MAX_XP_MULT = 3.0;
    /** Underdog bonus per point the mob's difficulty exceeds the killer's power level. */
    private static final double UNDERDOG_PER_POINT = 0.01;
    /** Cap on the underdog term alone (so it composes with XpMult under the hard ceiling). */
    private static final double UNDERDOG_MAX = 0.5;

    @Override
    public double scaleFor(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> victimRef,
            @Nonnull Ref<EntityStore> killerRef) {
        ScaledMobComponent comp = store.getComponent(victimRef, ScaledMobComponent.getComponentType());
        if (comp == null) {
            return 1.0; // not a scaled mob
        }
        MobScaleResult r = comp.result();
        double mult = r.xpMult() <= 0f ? 1.0 : r.xpMult();

        // Underdog bonus only for an actual rarity kill (a plain floor mob stays at 1.0).
        if (r.hasRarity()) {
            Double power = PlayerPowerReads.of(store, killerRef);
            double gap = power == null ? 0.0 : r.difficulty() - power;
            if (gap > 0.0) {
                mult += Math.min(UNDERDOG_MAX, gap * UNDERDOG_PER_POINT);
            }
        }
        return Math.min(MAX_XP_MULT, Math.max(1.0, mult));
    }
}
