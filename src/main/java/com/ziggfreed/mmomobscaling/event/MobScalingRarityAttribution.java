package com.ziggfreed.mmomobscaling.event;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.progress.runtime.KillQualifier;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;

/**
 * The rarity attribution: a ziggfreed-common {@link KillQualifier} the plugin contributes so a kill of a scaled mob
 * carries this mod's rolled rarity id (the asset ids under {@code Server/MmoMobScaling/Rarities}, e.g.
 * {@code "Legendary"}) as the kill moment's qualifier: what quest and achievement criteria naming a rarity match, and
 * what a kill drop command's {@code {tier}} placeholder resolves to, on any server running the library. Reading the
 * victim's own {@link ScaledMobComponent} keeps any other mod from importing this one. A plain floor mob (empty
 * rarity id) and an unscaled entity both answer null, an unqualified kill.
 *
 * <p>Asked once per credited player kill on the owning world thread (the component read is one chunk-local lookup);
 * the library guards the call, so a throw here can never break its kill handling.
 */
public final class MobScalingRarityAttribution implements KillQualifier {

    @Override
    @Nullable
    public String qualifierFor(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> victimRef) {
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
