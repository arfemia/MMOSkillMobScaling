package com.ziggfreed.mmomobscaling.config;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.Difficulty;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.DistanceEscalation;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.OpenWorld;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.StatCurve;
import com.ziggfreed.mmomobscaling.asset.WorldSettings;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold;

/**
 * A per-world overlay view over the global config (1.0.2, extracted from
 * {@code MobScalingConfig}'s old inner class): every EXPOSED leaf is
 * {@code world-file-leaf ?? global} (the world file is already {@code Parent}-merged by
 * {@code WorldSettingsConfig}, so the chain-then-global fall-through happens per leaf), and
 * {@link #statCurveModel()} multiplies the resolved slopes by the effective intensity.
 * {@code RegionSizeChunks} delegates straight to the global config (region-grid consistency).
 *
 * <p>The {@code Pool} allow/deny lists are compiled ONCE at construction into lower-cased
 * {@code Set}s (this object is cached per world in {@code MobScalingConfig.worldViewCache}), so
 * the hot spawn path does a set-contains, never a list scan. Immutable + stateless beyond its
 * references, safe to cache + read cross-thread.
 */
final class ResolvedWorldSettings implements SpawnScalingSettings {

    @Nonnull private final MobScalingConfig g;
    @Nonnull private final WorldSettings ws;

    // Pool gates compiled once (null = no gate authored on that side).
    @Nullable private final Set<String> allowRarities;
    @Nullable private final Set<String> denyRarities;
    @Nullable private final Set<String> allowVariants;
    @Nullable private final Set<String> denyVariants;
    @Nullable private final Set<String> allowAffixes;
    @Nullable private final Set<String> denyAffixes;
    private final double variantChanceMultiplier;
    private final int extraAffixSlots;

    ResolvedWorldSettings(@Nonnull MobScalingConfig g, @Nonnull WorldSettings ws) {
        this.g = g;
        this.ws = ws;
        WorldSettings.Pool pool = ws.getPool();
        WorldSettings.IdGate rarities = pool == null ? null : pool.getRarities();
        WorldSettings.VariantGate variants = pool == null ? null : pool.getVariants();
        WorldSettings.AffixGate affixes = pool == null ? null : pool.getAffixes();
        this.allowRarities = toSet(rarities == null ? null : rarities.getAllow());
        this.denyRarities = toSet(rarities == null ? null : rarities.getDeny());
        this.allowVariants = toSet(variants == null ? null : variants.getAllow());
        this.denyVariants = toSet(variants == null ? null : variants.getDeny());
        this.allowAffixes = toSet(affixes == null ? null : affixes.getAllow());
        this.denyAffixes = toSet(affixes == null ? null : affixes.getDeny());
        Double mult = variants == null ? null : variants.getChanceMultiplier();
        this.variantChanceMultiplier = mult != null ? Math.max(0.0, mult) : g.getVariantChanceMultiplier();
        Integer extra = affixes == null ? null : affixes.getExtraSlots();
        this.extraAffixSlots = extra != null ? Math.max(0, extra) : g.getExtraAffixSlots();
    }

    /** Lower-cased, blank-filtered gate set; {@code null} for an absent/empty authored list (no gate). */
    @Nullable
    private static Set<String> toSet(@Nullable String[] arr) {
        if (arr == null || arr.length == 0) {
            return null;
        }
        Set<String> out = new HashSet<>(arr.length * 2);
        for (String s : arr) {
            if (s != null && !s.isBlank()) {
                out.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static boolean gate(@Nullable Set<String> allow, @Nullable Set<String> deny, @Nonnull String id) {
        String key = id.toLowerCase(Locale.ROOT);
        if (deny != null && deny.contains(key)) {
            return false; // deny wins
        }
        return allow == null || allow.contains(key);
    }

    @Nullable private DistanceEscalation esc() {
        Difficulty d = ws.getDifficulty();
        return d == null ? null : d.getDistanceEscalation();
    }

    @Nullable private OpenWorld ow() {
        return ws.getOpenWorld();
    }

    @Override public boolean isWorldScalingEnabled() {
        Boolean v = ws.getEnabled();
        return v != null ? v : g.isWorldScalingEnabled();
    }

    @Override public double getDifficultyFloor() {
        Difficulty d = ws.getDifficulty();
        return d != null && d.getFloor() != null ? Math.max(0.0, d.getFloor()) : g.getDifficultyFloor();
    }

    @Override public double getRaritySpawnChance() {
        Double v = ws.getRaritySpawnChance();
        return v != null ? Math.max(0.0, Math.min(1.0, v)) : g.getRaritySpawnChance();
    }

    @Override public boolean isDistanceEscalationEnabled() {
        DistanceEscalation e = esc();
        return e != null && e.getEnabled() != null ? e.getEnabled() : g.isDistanceEscalationEnabled();
    }

    @Override public double getEscalationStartDistanceBlocks() {
        DistanceEscalation e = esc();
        return e != null && e.getStartDistanceBlocks() != null
                ? Math.max(0.0, e.getStartDistanceBlocks()) : g.getEscalationStartDistanceBlocks();
    }

    @Override public double getEscalationBlocksPerPoint() {
        DistanceEscalation e = esc();
        return e != null && e.getBlocksPerPoint() != null
                ? Math.max(1.0, e.getBlocksPerPoint()) : g.getEscalationBlocksPerPoint();
    }

    @Override public double getEscalationMaxBonus() {
        DistanceEscalation e = esc();
        return e != null && e.getMaxBonus() != null
                ? Math.max(0.0, e.getMaxBonus()) : g.getEscalationMaxBonus();
    }

    @Override public double getEscalationRarityChancePerPoint() {
        DistanceEscalation e = esc();
        return e != null && e.getRarityChancePerPoint() != null
                ? Math.max(0.0, e.getRarityChancePerPoint()) : g.getEscalationRarityChancePerPoint();
    }

    @Override public double getDifficultyMinCap() {
        Difficulty d = ws.getDifficulty();
        return d != null && d.getMinCap() != null ? d.getMinCap() : g.getDifficultyMinCap();
    }

    @Override public double getDifficultyMaxCap() {
        double min = getDifficultyMinCap();
        Difficulty d = ws.getDifficulty();
        double max = d != null && d.getMaxCap() != null ? d.getMaxCap() : g.getDifficultyMaxCap();
        return Math.max(min, max); // an inverted cap pair is a footgun
    }

    /** GLOBAL always: the region grid must stay consistent across worlds/regions. */
    @Override public int getRegionSizeChunks() { return g.getRegionSizeChunks(); }

    @Override public double getGroupDeltaBandWidth() {
        OpenWorld o = ow();
        return o != null && o.getGroupDeltaBandWidth() != null
                ? Math.max(0.0, o.getGroupDeltaBandWidth()) : g.getGroupDeltaBandWidth();
    }

    @Override public boolean isOnlyRaiseDifficulty() {
        OpenWorld o = ow();
        return o != null && o.getOnlyRaiseDifficulty() != null
                ? o.getOnlyRaiseDifficulty() : g.isOnlyRaiseDifficulty();
    }

    @Override public boolean isPlayerScalingEnabled() {
        OpenWorld o = ow();
        return o != null && o.getPlayerScalingEnabled() != null
                ? o.getPlayerScalingEnabled() : g.isPlayerScalingEnabled();
    }

    @Nonnull @Override public String getOpenWorldAggregationMode() {
        OpenWorld o = ow();
        String v = o == null ? null : o.getAggregationMode();
        return v != null && !v.isBlank() ? v : g.getOpenWorldAggregationMode();
    }

    @Override public boolean isAllowDifficultyIncreaseOnPartyJoin() {
        OpenWorld o = ow();
        return o != null && o.getAllowDifficultyIncreaseOnPartyJoin() != null
                ? o.getAllowDifficultyIncreaseOnPartyJoin() : g.isAllowDifficultyIncreaseOnPartyJoin();
    }

    @Override public double getLateArrivalBumpFactor() {
        OpenWorld o = ow();
        return o != null && o.getLateArrivalBumpFactor() != null
                ? o.getLateArrivalBumpFactor() : g.getLateArrivalBumpFactor();
    }

    @Override public boolean isCompositionEnabled() {
        OpenWorld o = ow();
        return o != null && o.getCompositionEnabled() != null
                ? o.getCompositionEnabled() : g.isCompositionEnabled();
    }

    @Override public boolean isZoneHudEnabled() {
        Boolean v = ws.getZoneHud() == null ? null : ws.getZoneHud().getEnabled();
        return v != null ? v : g.isZoneHudEnabled();
    }

    @Override public boolean isInspectorHudEnabled() {
        Boolean v = ws.getInspectorHud() == null ? null : ws.getInspectorHud().getEnabled();
        return v != null ? v : g.isInspectorHudEnabled();
    }

    @Override public boolean isRarityAllowed(@Nonnull String rarityId) {
        return gate(allowRarities, denyRarities, rarityId);
    }

    @Override public boolean isVariantAllowed(@Nonnull String variantId) {
        return gate(allowVariants, denyVariants, variantId);
    }

    @Override public boolean isAffixAllowed(@Nonnull String affixId) {
        return gate(allowAffixes, denyAffixes, affixId);
    }

    @Override public double getVariantChanceMultiplier() {
        return variantChanceMultiplier;
    }

    @Override public int getExtraAffixSlots() {
        return extraAffixSlots;
    }

    @Nonnull
    @Override
    public MobScaleFold.DifficultyStatCurve statCurveModel() {
        StatCurve c = ws.getDifficulty() == null ? null : ws.getDifficulty().getStatCurve();
        double hp = c != null && c.getHpPerPoint() != null
                ? Math.max(0.0, c.getHpPerPoint()) : g.getStatCurveHpPerPoint();
        double out = c != null && c.getOutDamagePerPoint() != null
                ? Math.max(0.0, c.getOutDamagePerPoint()) : g.getStatCurveOutDamagePerPoint();
        double in = c != null && c.getInDamageReductionPerPoint() != null
                ? Math.max(0.0, c.getInDamageReductionPerPoint()) : g.getStatCurveInDamageReductionPerPoint();
        double maxHp = c != null && c.getMaxHpMult() != null
                ? Math.max(1.0, c.getMaxHpMult()) : g.getStatCurveMaxHpMult();
        double maxOut = c != null && c.getMaxOutDamageMult() != null
                ? Math.max(1.0, c.getMaxOutDamageMult()) : g.getStatCurveMaxOutDamageMult();
        double minIn = c != null && c.getMinInDamageMult() != null
                ? Math.max(0.01, Math.min(1.0, c.getMinInDamageMult())) : g.getStatCurveMinInDamageMult();
        double eff = ws.getIntensity() != null ? Math.max(0.0, ws.getIntensity()) : g.getIntensity();
        return MobScalingConfig.buildCurve(hp, out, in, maxHp, maxOut, minIn, eff);
    }
}
