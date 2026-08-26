package com.ziggfreed.mmomobscaling.event;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.mmoskilltree.api.MMOSkillTreeAPI;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;

/**
 * The rarity attribution: a {@link MMOSkillTreeAPI.KillRarityProvider} the plugin registers so a
 * kill of a scaled mob carries this mod's rolled rarity id (the asset ids under
 * {@code Server/MmoMobScaling/Rarities}, e.g. {@code "Legendary"}) as the MMO's kill qualifier, which is what
 * a mob-drop command's {@code {tier}} placeholder resolves to. Reading the victim's own
 * {@link ScaledMobComponent} keeps the dependency pointing mod -&gt; MMO (the jar never imports the
 * component), the same shape as {@link MobScalingXpReward}. A plain floor mob (empty rarity id) and
 * an unscaled entity both answer null, the MMO's "unqualified kill".
 *
 * <p>Consulted once per credited player kill on the owning world thread (the component read is one
 * chunk-local lookup); the MMO guards the call, so a throw here can never break its kill handling.
 */
public final class MobScalingRarityAttribution implements MMOSkillTreeAPI.KillRarityProvider {

    @Override
    @Nullable
    public String rarity(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> victimRef) {
        return rarityOf(store.getComponent(victimRef, ScaledMobComponent.getComponentType()));
    }

    /**
     * The rarity id a stamped component attributes, or null for an unstamped entity or a plain
     * (rarity-less) roll. Pure, so the answer is testable without a store or a plugin singleton.
     */
    @Nullable
    public static String rarityOf(@Nullable ScaledMobComponent component) {
        if (component == null) {
            return null;
        }
        MobScaleResult result = component.result();
        return result.hasRarity() ? result.rarityId() : null;
    }
}
