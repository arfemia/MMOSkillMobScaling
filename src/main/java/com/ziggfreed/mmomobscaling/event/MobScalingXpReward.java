package com.ziggfreed.mmomobscaling.event;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.mmoskilltree.api.MMOSkillTreeAPI;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;

/**
 * The kill-XP reward: a {@link MMOSkillTreeAPI.MobKillXpMultiplier} the plugin registers so a scaled/rare mob
 * pays MORE kill XP through the MMO's own kill path (the jar reads {@code damage}-based kill XP, then multiplies
 * by this). Reading the victim's own {@link ScaledMobComponent} keeps the dependency pointing mod -&gt; MMO (the
 * jar never imports the component). This is the reward half of the risk/reward loop - without it a rarity kill
 * pays nothing.
 *
 * <p>The multiplier is the rarity's authored {@code XpMult} (plain mobs = {@code 1.0}, unchanged), plus an
 * UNDERDOG bonus for a RARITY kill whose difficulty exceeds the killer's power (fighting above your weight
 * pays). The whole thing is clamped to {@link #MAX_XP_MULT} so it can never runaway. Applies to KILL XP only:
 * the jar consults this once per kill in {@code MobKillEventSystem}, never on per-hit XP (which already scales
 * with the mob's extra HP).
 */
public final class MobScalingXpReward implements MMOSkillTreeAPI.MobKillXpMultiplier {

    /** Anti-runaway hard ceiling on the total kill-XP multiplier. */
    private static final double MAX_XP_MULT = 3.0;
    /** Underdog bonus per point the mob's difficulty exceeds the killer's power level. */
    private static final double UNDERDOG_PER_POINT = 0.01;
    /** Cap on the underdog term alone (so it composes with XpMult under the hard ceiling). */
    private static final double UNDERDOG_MAX = 0.5;

    @Override
    public double multiplier(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> victimRef,
            @Nonnull Ref<EntityStore> killerRef) {
        ScaledMobComponent comp = store.getComponent(victimRef, ScaledMobComponent.getComponentType());
        if (comp == null) {
            return 1.0; // not a scaled mob
        }
        MobScaleResult r = comp.result();
        double mult = r.xpMult() <= 0f ? 1.0 : r.xpMult();

        // Underdog bonus only for an actual rarity kill (a plain floor mob stays at 1.0).
        if (r.hasRarity()) {
            double gap = r.difficulty() - MMOSkillTreeAPI.getPowerLevel(store, killerRef);
            if (gap > 0.0) {
                mult += Math.min(UNDERDOG_MAX, gap * UNDERDOG_PER_POINT);
            }
        }
        return Math.min(MAX_XP_MULT, Math.max(1.0, mult));
    }
}
