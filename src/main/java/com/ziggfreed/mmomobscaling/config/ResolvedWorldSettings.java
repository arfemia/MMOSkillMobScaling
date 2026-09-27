package com.ziggfreed.mmomobscaling.config;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.Clamps;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.Difficulty;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.DistanceEscalation;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.Hud;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.InspectorHud;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.OpenWorld;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.StatCurve;
import com.ziggfreed.mmomobscaling.asset.WorldSettings;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold;

/**
 * A per-world overlay view over the global config: every EXPOSED leaf is
 * {@code world-file-leaf ?? global} (the world file is already {@code Parent}-merged by
 * {@code WorldSettingsConfig}, so the chain-then-global fall-through happens per leaf). Every leaf
 * overlays the same way, {@code RegionSizeChunks}, the two HUD groups, the stat curve and the clamps
 * included: a region bucket is keyed by world, so the proximity grid only has to agree within one,
 * and a HUD reads the view of the world its player stands in.
 *
 * <p>The {@code Pool} allow/deny lists are compiled ONCE at construction into lower-cased
 * {@code Set}s, and the curve and clamps records are built once too (this object is cached per world
 * in {@code MobScalingConfig.worldViewCache}), so the hot spawn path does a set-contains and a field
 * read, never a list scan or a rebuild. Immutable + stateless beyond its references, safe to cache +
 * read cross-thread.
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
    // The per-world curve and rails, resolved once per leaf against the global.
    @Nonnull private final MobScaleFold.DifficultyStatCurve statCurve;
    @Nonnull private final MobScaleFold.Clamps clamps;

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

        StatCurve c = ws.getDifficulty() == null ? null : ws.getDifficulty().getStatCurve();
        this.statCurve = MobScalingConfig.buildCurve(
                c != null && c.getEffectiveHpPerPoint() != null ? c.getEffectiveHpPerPoint() : g.getStatCurveEffectiveHpPerPoint(),
                c != null && c.getVisibleHpShare() != null ? c.getVisibleHpShare() : g.getStatCurveVisibleHpShare(),
                c != null && c.getOutDamageScale() != null ? c.getOutDamageScale() : g.getStatCurveOutDamageScale(),
                c != null && c.getOutDamageShape() != null ? c.getOutDamageShape() : g.getStatCurveOutDamageShape(),

                c != null && c.getMaxEffectiveHpMult() != null ? c.getMaxEffectiveHpMult() : g.getStatCurveMaxEffectiveHpMult(),
                c != null && c.getMaxOutDamageMult() != null ? c.getMaxOutDamageMult() : g.getStatCurveMaxOutDamageMult());
        Clamps k = ws.getDifficulty() == null ? null : ws.getDifficulty().getClamps();
        this.clamps = MobScalingConfig.buildClamps(
                k != null && k.getMinHpMult() != null ? k.getMinHpMult() : g.getClampMinHpMult(),
                k != null && k.getMaxInDamageMult() != null ? k.getMaxInDamageMult() : g.getClampMaxInDamageMult(),
                k != null && k.getMinOutDamageMult() != null ? k.getMinOutDamageMult() : g.getClampMinOutDamageMult(),
                k != null && k.getMinLootMult() != null ? k.getMinLootMult() : g.getClampMinLootMult(),
                k != null && k.getMaxLootMult() != null ? k.getMaxLootMult() : g.getClampMaxLootMult());
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

    @Override public int getRegionSizeChunks() {
        OpenWorld o = ow();
        return o != null && o.getRegionSizeChunks() != null
                ? Math.max(1, o.getRegionSizeChunks()) : g.getRegionSizeChunks();
    }

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

    @Override public double getPlayerScalingStartRingBlocks() {
        OpenWorld o = ow();
        return o != null && o.getPlayerScalingStartRingBlocks() != null
                ? Math.max(0.0, o.getPlayerScalingStartRingBlocks()) : g.getPlayerScalingStartRingBlocks();
    }

    @Nonnull @Override public String getOpenWorldAggregationMode() {
        OpenWorld o = ow();
        String v = o == null ? null : o.getAggregationMode();
        return v != null && !v.isBlank() ? v : g.getOpenWorldAggregationMode();
    }

    @Nullable private Hud zoneHud() {
        return ws.getZoneHud();
    }

    @Nullable private InspectorHud inspectorHud() {
        return ws.getInspectorHud();
    }

    @Override public boolean isZoneHudEnabled() {
        Hud h = zoneHud();
        return h != null && h.getEnabled() != null ? h.getEnabled() : g.isZoneHudEnabled();
    }

    @Override public boolean isInspectorHudEnabled() {
        InspectorHud h = inspectorHud();
        return h != null && h.getEnabled() != null ? h.getEnabled() : g.isInspectorHudEnabled();
    }

    @Nonnull @Override public String getZoneHudPosition() {
        Hud h = zoneHud();
        String v = h == null ? null : h.getPosition();
        return v != null && !v.isBlank() ? v : g.getZoneHudPosition();
    }

    @Override public int getZoneHudOffsetX() {
        Hud h = zoneHud();
        return h != null && h.getOffsetX() != null ? h.getOffsetX() : g.getZoneHudOffsetX();
    }

    @Override public int getZoneHudOffsetY() {
        Hud h = zoneHud();
        return h != null && h.getOffsetY() != null ? h.getOffsetY() : g.getZoneHudOffsetY();
    }

    @Override public boolean isZoneShowLocationName() {
        Hud h = zoneHud();
        return h != null && h.getShowLocationName() != null ? h.getShowLocationName() : g.isZoneShowLocationName();
    }

    // An EMPTY prefix is a real value (prettify the raw id), so only an absent leaf inherits.
    @Nonnull @Override public String getZoneNameKeyPrefix() {
        Hud h = zoneHud();
        return h != null && h.getZoneNameKeyPrefix() != null ? h.getZoneNameKeyPrefix() : g.getZoneNameKeyPrefix();
    }

    @Nonnull @Override public String getBiomeNameKeyPrefix() {
        Hud h = zoneHud();
        return h != null && h.getBiomeNameKeyPrefix() != null ? h.getBiomeNameKeyPrefix() : g.getBiomeNameKeyPrefix();
    }

    @Nonnull @Override public String getInspectorHudPosition() {
        InspectorHud h = inspectorHud();
        String v = h == null ? null : h.getPosition();
        return v != null && !v.isBlank() ? v : g.getInspectorHudPosition();
    }

    @Override public int getInspectorHudOffsetX() {
        InspectorHud h = inspectorHud();
        return h != null && h.getOffsetX() != null ? h.getOffsetX() : g.getInspectorHudOffsetX();
    }

    @Override public int getInspectorHudOffsetY() {
        InspectorHud h = inspectorHud();
        return h != null && h.getOffsetY() != null ? h.getOffsetY() : g.getInspectorHudOffsetY();
    }

    // The same sane raycast band the global fold applies, so a world file cannot author a zero or a
    // whole-map reach.
    @Override public double getInspectorRangeBlocks() {
        InspectorHud h = inspectorHud();
        return h != null && h.getRangeBlocks() != null
                ? Math.max(2.0, Math.min(32.0, h.getRangeBlocks())) : g.getInspectorRangeBlocks();
    }

    @Override public boolean isInspectorPortraitEnabled() {
        InspectorHud h = inspectorHud();
        return h != null && h.getPortraitEnabled() != null ? h.getPortraitEnabled() : g.isInspectorPortraitEnabled();
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
        return statCurve;
    }

    @Nonnull
    @Override
    public MobScaleFold.Clamps clampsModel() {
        return clamps;
    }
}
