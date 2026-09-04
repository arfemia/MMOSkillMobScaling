package com.ziggfreed.mmomobscaling.factor;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.seam.EncounterPowerSource;
import com.ziggfreed.mmomobscaling.config.MobScalingConfig;
import com.ziggfreed.mmomobscaling.scaling.RegionPowerTracker;
import com.ziggfreed.mmomobscaling.world.RegionKeys;

/**
 * What this mod hands the boss framework: the one number it cannot know for itself. A binding
 * row's {@code Scale.HealthPerPowerPoint} multiplies a fight's power, and the framework leaves the
 * reading to a companion; this mod tracks player power per region already, so it answers.
 *
 * <p><b>A fight's power is the power of the region the SUBJECT stands in</b>, read at the boss's
 * own world and chunk through the same {@link RegionKeys} the presence tick writes with. It is not
 * an aggregate walked over the member refs: the tracker is region-keyed and has no per-player read,
 * the members are already paid for by the row's {@code Scale.HealthPerMember} (summing them here
 * would count the party twice), and the subject is the one ref the seam guarantees a position for.
 *
 * <p><b>Null means "nothing is known", never zero.</b> A region no player is tracked in answers
 * null, so {@code HealthPerPowerPoint} contributes nothing rather than a confident zero; a subject
 * with no ref, no world or no position answers null too. Whole body fail-soft: a throw reads as
 * unknown.
 */
public final class EncounterPowerFill implements EncounterPowerSource {

    @Nullable
    @Override
    public Double aggregatedPower(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> subjectRef,
            @Nonnull List<Ref<EntityStore>> members) {
        try {
            if (subjectRef == null || !subjectRef.isValid()) {
                return null;
            }
            World world = store.getExternalData().getWorld();
            if (world == null) {
                return null;
            }
            RegionPowerTracker.RegionKey key = RegionKeys.of(store, subjectRef, world,
                    MobScalingConfig.getInstance().getRegionSizeChunks());
            if (key == null) {
                return null;
            }
            return RegionPowerTracker.get().scalarIfTracked(world.getName(), key);
        } catch (Throwable t) {
            return null;
        }
    }
}
