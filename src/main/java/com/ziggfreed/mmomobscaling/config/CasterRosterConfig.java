package com.ziggfreed.mmomobscaling.config;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.mmomobscaling.caster.CasterRoster;

/**
 * The {@code defaults < pack < owner} fold authority for {@link CasterRoster}s, keyed on EVERY layer by
 * the one owner-file id key ({@link OwnerFiles#idKey}), so a shipped roster and the owner file that
 * overlays it meet at one entry however either is spelled (the {@link DifficultyConfig} keying). The fold
 * mechanics (three layers, idempotent re-import, resolve order) live in the shared ziggfreed-common
 * {@link AbstractKeyedAssetConfig} base; the two layer mutators here re-key what they are handed before
 * the base fold sees it, and {@link #resolve} keys its argument the same way.
 *
 * <p>The PACK layer is the jar's own files plus every pack's, folded LAZILY by the {@code LoadedAssetsEvent}
 * listener in {@code MobScalingAssetRegistrar} AFTER plugin {@code setup()}; the OWNER layer is
 * {@link CasterOwnerLayer}'s {@code mods/MmoMobScaling/casters/} folder, refolded right after it. Read
 * (via {@code roster/Rosters.casterRosters()}) at spawn time.
 */
public final class CasterRosterConfig extends AbstractKeyedAssetConfig<CasterRoster> {

    private static final CasterRosterConfig INSTANCE = new CasterRosterConfig();

    @Nonnull
    public static CasterRosterConfig getInstance() {
        return INSTANCE;
    }

    /**
     * The jar + pack rosters as last folded, by id key: what an owner file overlays
     * ({@link CasterOwnerLayer}), read separately from {@link #resolve} because that answer already has the
     * owner layer on top.
     */
    @Nonnull private volatile Map<String, CasterRoster> packLayer = Map.of();

    private CasterRosterConfig() {
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, CasterRoster> layer) {
        Map<String, CasterRoster> keyed = keyed(layer);
        super.mergePackLayer(keyed);
        this.packLayer = Map.copyOf(keyed);
    }

    /** The shipped (jar + pack) roster under {@code id}, BEFORE any owner overlay; {@code null} when none ships. */
    @Nullable
    public CasterRoster packRoster(@Nonnull String id) {
        return packLayer.get(OwnerFiles.idKey(id));
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, CasterRoster> layer) {
        super.mergeOwnerLayer(keyed(layer));
    }

    /** Resolve by the one id key, so a shipped id and the owner file's stem meet one entry. */
    @Nullable
    @Override
    public CasterRoster resolve(@Nonnull String id) {
        return super.resolve(OwnerFiles.idKey(id));
    }

    /** {@code layer} re-keyed by {@link OwnerFiles#idKey}, null values dropped; two ids meeting at one key keep the later one. */
    @Nonnull
    private static Map<String, CasterRoster> keyed(@Nonnull Map<String, CasterRoster> layer) {
        Map<String, CasterRoster> out = new LinkedHashMap<>();
        for (Map.Entry<String, CasterRoster> e : layer.entrySet()) {
            if (e.getValue() != null) {
                out.put(OwnerFiles.idKey(e.getKey()), e.getValue());
            }
        }
        return out;
    }
}
