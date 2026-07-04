package com.ziggfreed.mmomobscaling.config;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.mmomobscaling.world.DifficultyMapping;

/**
 * The {@code defaults < pack < owner} fold authority for {@link DifficultyMapping} floors, keyed by
 * lowercase mapping id, plus the DERIVED per-target lookup index the hot resolve path reads. The fold
 * mechanics live in ziggfreed-common's {@link AbstractKeyedAssetConfig}; this singleton overrides the
 * three layer mutators to rebuild a flat {@code (type, nativeName) -> floor} index after every merge,
 * so {@code world/ZoneDifficultyResolver} resolves a floor with two O(1) map reads (exact, then
 * wildcard) instead of scanning mappings per spawn.
 *
 * <p>Resolution splits into SPECIFIC (exact match, else the LONGEST segment-boundary PREFIX; the
 * shipped world's zone names are compound like {@code Zone2_Tier1}, so a {@code "Zone2"} mapping is a
 * prefix family) and WILDCARD ({@code "*"}) queries, so {@code world/ZoneDifficultyResolver} can
 * interleave the layers as zone-specific &gt; biome-specific &gt; zone-wildcard &gt; biome-wildcard.
 *
 * <p>Populated LAZILY by the {@code LoadedAssetsEvent} fold in {@code MobScalingAssetRegistrar} AFTER
 * plugin {@code setup()}; read at spawn / presence / HUD time (well after load). Like the rarity/affix
 * stores, this mod SHIPS jar defaults (the starter zone gradient under
 * {@code Server/MmoMobScaling/Difficulty/}).
 */
public final class DifficultyConfig extends AbstractKeyedAssetConfig<DifficultyMapping> {

    private static final DifficultyConfig INSTANCE = new DifficultyConfig();

    @Nonnull
    public static DifficultyConfig getInstance() {
        return INSTANCE;
    }

    /** Immutable derived index, swapped wholesale on every layer merge (lock-free volatile reads). */
    private volatile Index index = new Index(Map.of(), null, Map.of(), null);

    private DifficultyConfig() {
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, DifficultyMapping> jarDefaults) {
        super.loadDefaults(jarDefaults);
        rebuildIndex();
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, DifficultyMapping> layer) {
        super.mergePackLayer(layer);
        rebuildIndex();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, DifficultyMapping> layer) {
        super.mergeOwnerLayer(layer);
        rebuildIndex();
    }

    /**
     * The authored floor for a native zone name via a SPECIFIC (non-wildcard) mapping only: an exact
     * match, else the LONGEST segment-boundary prefix ({@code "Zone2"} covers {@code Zone2_Tier1}, the
     * longer {@code "Zone2_Tier1"} override wins over it); {@code null} when no specific mapping matches.
     */
    @Nullable
    public Double zoneFloorSpecific(@Nonnull String zoneName) {
        return specificFloor(this.index.zoneByName, zoneName);
    }

    /** The authored floor for a native biome name via a SPECIFIC (non-wildcard) mapping only (exact, else longest prefix). */
    @Nullable
    public Double biomeFloorSpecific(@Nonnull String biomeName) {
        return specificFloor(this.index.biomeByName, biomeName);
    }

    /** The zone type-wide wildcard ({@code TargetId: "*"}) floor, or {@code null} when none is authored. */
    @Nullable
    public Double zoneWildcard() {
        return this.index.zoneWildcard;
    }

    /** The biome type-wide wildcard ({@code TargetId: "*"}) floor, or {@code null} when none is authored. */
    @Nullable
    public Double biomeWildcard() {
        return this.index.biomeWildcard;
    }

    /** The authored floor for a native zone name (specific match, else the zone wildcard); {@code null} = no mapping. */
    @Nullable
    public Double zoneFloor(@Nonnull String zoneName) {
        Double specific = zoneFloorSpecific(zoneName);
        return specific != null ? specific : this.index.zoneWildcard;
    }

    /** The authored floor for a native biome name (specific match, else the biome wildcard); {@code null} = no mapping. */
    @Nullable
    public Double biomeFloor(@Nonnull String biomeName) {
        Double specific = biomeFloorSpecific(biomeName);
        return specific != null ? specific : this.index.biomeWildcard;
    }

    /** Exact lowercase hit, else the LONGEST authored targetId that is a segment-boundary prefix of {@code name}. */
    @Nullable
    private static Double specificFloor(@Nonnull Map<String, Double> byName, @Nonnull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        Double exact = byName.get(lower);
        if (exact != null) {
            return exact;
        }
        Double best = null;
        int bestLen = -1;
        for (Map.Entry<String, Double> e : byName.entrySet()) {
            String key = e.getKey();
            if (key.length() > bestLen && lower.length() > key.length()
                    && lower.charAt(key.length()) == '_' && lower.startsWith(key)) {
                best = e.getValue();
                bestLen = key.length();
            }
        }
        return best;
    }

    private void rebuildIndex() {
        Map<String, Double> zones = new HashMap<>();
        Map<String, Double> biomes = new HashMap<>();
        Double zoneWildcard = null;
        Double biomeWildcard = null;
        for (DifficultyMapping m : all().values()) {
            if (m == null) {
                continue;
            }
            if (m.targetType() == DifficultyMapping.TargetType.ZONE) {
                if (m.isWildcard()) {
                    zoneWildcard = m.floor();
                } else {
                    zones.put(m.targetId().toLowerCase(Locale.ROOT), m.floor());
                }
            } else if (m.targetType() == DifficultyMapping.TargetType.BIOME) {
                if (m.isWildcard()) {
                    biomeWildcard = m.floor();
                } else {
                    biomes.put(m.targetId().toLowerCase(Locale.ROOT), m.floor());
                }
            }
        }
        this.index = new Index(Map.copyOf(zones), zoneWildcard, Map.copyOf(biomes), biomeWildcard);
    }

    private record Index(@Nonnull Map<String, Double> zoneByName, @Nullable Double zoneWildcard,
            @Nonnull Map<String, Double> biomeByName, @Nullable Double biomeWildcard) {
    }
}
