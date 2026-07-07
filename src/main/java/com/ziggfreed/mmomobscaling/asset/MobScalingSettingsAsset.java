package com.ziggfreed.mmomobscaling.asset;

import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;

/**
 * The mob-scaling settings, authored as a PROPER Hytale asset codec (Pattern A,
 * {@link AssetBuilderCodec}, PascalCase keys) - the ONE schema authority for the config, exactly
 * like the MMO's {@code WorldRulesAsset}. The jar ships the authoritative defaults as a codec asset
 * ({@code Server/MmoMobScaling/Settings/Default.json}); owners override any key in
 * {@code mods/MmoMobScaling/mob-scaling.json} (the SAME PascalCase codec shape, partial allowed).
 *
 * <p><b>Cohesive knob groups are NESTED sub-objects, not flat prefixed keys</b> (the schema-design
 * rule; a nested group keeps the schema navigable and future-proof - a new open-world knob lands
 * inside {@code OpenWorld} instead of growing a flat 25-key soup). The nested classes each carry
 * their own {@link BuilderCodec} (the {@code QuestGiverAsset.Offset}/{@code Match} pattern):
 * {@link OpenWorld} (group-power aggregation), {@link Difficulty} (caps + the nested
 * {@link DistanceEscalation}), {@link Hud} ({@code ZoneHud}) and {@link InspectorHud}.
 *
 * <p><b>Fields are NULLABLE wrappers on purpose, at every nesting level.</b> {@code decodeJson}
 * calls a field's setter ONLY for a key present in the JSON, so decoding the jar Default.json
 * yields all-non-null (authoritative defaults), decoding a partial owner file yields non-null only
 * for owner-set keys - including a partially-filled nested group ({@code "OpenWorld": {"RegionSizeChunks": 5}}
 * leaves every other {@code OpenWorld} leaf {@code null}) - and {@code MobScalingConfig} folds
 * owner-over-store-over-jar PER LEAF with NO values baked into Java.
 *
 * <p>Decoded SYNCHRONOUSLY at plugin {@code setup()} via {@code CODEC.decodeJson(...)} (the
 * {@code WorldRulesConfig.decodeOwnerRule} pattern), so the zero-cost registration gate can read
 * {@code Enabled} before {@code LoadedAssetsEvent} would populate an async asset store.
 *
 * <p>Map-shaped SIMPLE-preset knobs (rarity weights, zone difficulty overrides) are deliberately NOT
 * here: their canonical home is the per-type keyed assets ({@code Rarities/*.json},
 * {@code Difficulty/*.json}).
 */
public final class MobScalingSettingsAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, MobScalingSettingsAsset>> {

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String activePreset;
    @Nullable private Boolean enabled;
    @Nullable private String presetMode;
    @Nullable private Double intensity;
    @Nullable private Double raritySpawnChance;
    @Nullable private OpenWorld openWorld;
    @Nullable private Difficulty difficulty;
    @Nullable private Hud zoneHud;
    @Nullable private InspectorHud inspectorHud;
    @Nullable private WorldOverride[] worldOverrides;

    public static final AssetBuilderCodec<String, MobScalingSettingsAsset> CODEC = AssetBuilderCodec.builder(
                    MobScalingSettingsAsset.class,
                    MobScalingSettingsAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            // Optional human-readable echo of the asset key (the filename is authoritative).
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* no-op - id comes from the filename */ },
                    a -> a.id)
            .add()
            // Which settings preset (a Server/MmoMobScaling/Settings/*.json key) folds over the jar
            // Default at runtime; owner-set here or swapped live via /mobscaling preset. Defaults "Default".
            .append(new KeyedCodec<>("ActivePreset", Codec.STRING, false),
                    (a, v) -> a.activePreset = v, a -> a.activePreset)
            .add()
            // Master toggle: the zero-cost registration gate reads this at setup().
            .append(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled)
            .add()
            // Customization tier: "SIMPLE" | "TUNED" | "ADVANCED".
            .append(new KeyedCodec<>("PresetMode", Codec.STRING, false),
                    (a, v) -> a.presetMode = v, a -> a.presetMode)
            .add()
            // Intensity dial (1.0.1): a numeric multiplier (default 1.0) on the difficulty->stat curve
            // SLOPES (how tanky mobs are + how hard they hit). 1.0 neutral; 0 = no difficulty-based stat
            // boost. Bounded by the curve's own per-factor caps. Runtime-tunable via /mobscaling intensity.
            .append(new KeyedCodec<>("Intensity", Codec.DOUBLE, false),
                    (a, v) -> a.intensity = v, a -> a.intensity)
            .add()
            // Chance a hostile mob rolls a non-plain rarity (before the distance-escalation bonus).
            .append(new KeyedCodec<>("RaritySpawnChance", Codec.DOUBLE, false),
                    (a, v) -> a.raritySpawnChance = v, a -> a.raritySpawnChance)
            .add()
            // Open-world group-power aggregation (region buckets, fold mode, late-arrival policy).
            .append(new KeyedCodec<>("OpenWorld", OpenWorld.CODEC, false),
                    (a, v) -> a.openWorld = v, a -> a.openWorld)
            .add()
            // Effective-difficulty clamps + the distance-from-spawn escalation curve.
            .append(new KeyedCodec<>("Difficulty", Difficulty.CODEC, false),
                    (a, v) -> a.difficulty = v, a -> a.difficulty)
            .add()
            // Zone-difficulty HUD (per-player overlay: effective local difficulty + own/group power).
            .append(new KeyedCodec<>("ZoneHud", Hud.CODEC, false),
                    (a, v) -> a.zoneHud = v, a -> a.zoneHud)
            .add()
            // Mob-inspector HUD (per-player overlay: name/rarity/affixes/health of the look-at target).
            .append(new KeyedCodec<>("InspectorHud", InspectorHud.CODEC, false),
                    (a, v) -> a.inspectorHud = v, a -> a.inspectorHud)
            .add()
            // Per-world settings overlays (1.0.1): each entry is a world-name Match pattern (same fuzzy
            // matching as the MMO WorldRulesMatcher) bound to a partial settings body that overlays the
            // global fold for matching worlds at spawn time. Layers CONCATENATE (deduped by Match), so an
            // owner file ADDS/overrides worlds without clobbering the jar-shipped dungeon defaults.
            .append(new KeyedCodec<>("WorldOverrides",
                    new ArrayCodec<>(WorldOverride.CODEC, WorldOverride[]::new), false),
                    (a, v) -> a.worldOverrides = v, a -> a.worldOverrides)
            .add()
            .build();

    public MobScalingSettingsAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    @Nullable public String getActivePreset() { return activePreset; }
    @Nullable public Boolean getEnabled() { return enabled; }
    @Nullable public String getPresetMode() { return presetMode; }
    @Nullable public Double getIntensity() { return intensity; }
    @Nullable public Double getRaritySpawnChance() { return raritySpawnChance; }
    @Nullable public OpenWorld getOpenWorld() { return openWorld; }
    @Nullable public Difficulty getDifficulty() { return difficulty; }
    @Nullable public Hud getZoneHud() { return zoneHud; }
    @Nullable public InspectorHud getInspectorHud() { return inspectorHud; }
    @Nullable public WorldOverride[] getWorldOverrides() { return worldOverrides; }

    /** Open-world group-power aggregation: how nearby players fold into a region's difficulty delta. */
    public static final class OpenWorld {
        public static final BuilderCodec<OpenWorld> CODEC = BuilderCodec.builder(OpenWorld.class, OpenWorld::new)
                // How a region's participant powers fold: SOLO | AVERAGE | PEAK | WEIGHTED | DISABLED.
                .append(new KeyedCodec<>("AggregationMode", Codec.STRING, false),
                        (o, v) -> o.aggregationMode = v, o -> o.aggregationMode)
                .add()
                // Proximity sub-grid size (chunks per side) WITHIN a native zone; also the whole
                // region key in a world without zone data (the chunk-grid fallback).
                .append(new KeyedCodec<>("RegionSizeChunks", Codec.INTEGER, false),
                        (o, v) -> o.regionSizeChunks = v, o -> o.regionSizeChunks)
                .add()
                // Max absolute difficulty swing the region-power delta may add over the floor.
                .append(new KeyedCodec<>("GroupDeltaBandWidth", Codec.DOUBLE, false),
                        (o, v) -> o.groupDeltaBandWidth = v, o -> o.groupDeltaBandWidth)
                .add()
                // One-shot additive difficulty bump when a stronger player/party arrives in a region.
                .append(new KeyedCodec<>("AllowDifficultyIncreaseOnPartyJoin", Codec.BOOLEAN, false),
                        (o, v) -> o.allowDifficultyIncreaseOnPartyJoin = v, o -> o.allowDifficultyIncreaseOnPartyJoin)
                .add()
                // Size (flat additive difficulty) of the late-arrival bump.
                .append(new KeyedCodec<>("LateArrivalBumpFactor", Codec.DOUBLE, false),
                        (o, v) -> o.lateArrivalBumpFactor = v, o -> o.lateArrivalBumpFactor)
                .add()
                // Open-world density/composition scaling toggle (gated at registration).
                .append(new KeyedCodec<>("CompositionEnabled", Codec.BOOLEAN, false),
                        (o, v) -> o.compositionEnabled = v, o -> o.compositionEnabled)
                .add()
                // When true, the group-power delta may only RAISE a region's difficulty over the floor,
                // never lower it (a weak lone arrival never softens a zone below its authored baseline).
                .append(new KeyedCodec<>("OnlyRaiseDifficulty", Codec.BOOLEAN, false),
                        (o, v) -> o.onlyRaiseDifficulty = v, o -> o.onlyRaiseDifficulty)
                .add()
                // Whether player/group-based scaling (the region-power group delta) applies at all (1.0.1;
                // default true). false pins difficulty to the escalated floor regardless of nearby player
                // power - the per-world toggle a fixed-difficulty authored dungeon overrides to false.
                .append(new KeyedCodec<>("PlayerScalingEnabled", Codec.BOOLEAN, false),
                        (o, v) -> o.playerScalingEnabled = v, o -> o.playerScalingEnabled)
                .add()
                .build();

        @Nullable private String aggregationMode;
        @Nullable private Integer regionSizeChunks;
        @Nullable private Double groupDeltaBandWidth;
        @Nullable private Boolean allowDifficultyIncreaseOnPartyJoin;
        @Nullable private Double lateArrivalBumpFactor;
        @Nullable private Boolean compositionEnabled;
        @Nullable private Boolean onlyRaiseDifficulty;
        @Nullable private Boolean playerScalingEnabled;

        @Nullable public String getAggregationMode() { return aggregationMode; }
        @Nullable public Integer getRegionSizeChunks() { return regionSizeChunks; }
        @Nullable public Double getGroupDeltaBandWidth() { return groupDeltaBandWidth; }
        @Nullable public Boolean getAllowDifficultyIncreaseOnPartyJoin() { return allowDifficultyIncreaseOnPartyJoin; }
        @Nullable public Double getLateArrivalBumpFactor() { return lateArrivalBumpFactor; }
        @Nullable public Boolean getCompositionEnabled() { return compositionEnabled; }
        @Nullable public Boolean getOnlyRaiseDifficulty() { return onlyRaiseDifficulty; }
        @Nullable public Boolean getPlayerScalingEnabled() { return playerScalingEnabled; }
    }

    /** Effective-difficulty clamps + the nested distance-from-spawn escalation curve. */
    public static final class Difficulty {
        public static final BuilderCodec<Difficulty> CODEC = BuilderCodec.builder(Difficulty.class, Difficulty::new)
                // Lower clamp on the resolved effective difficulty (floor + escalation + group delta).
                .append(new KeyedCodec<>("MinCap", Codec.DOUBLE, false),
                        (d, v) -> d.minCap = v, d -> d.minCap)
                .add()
                // Upper clamp on the resolved effective difficulty.
                .append(new KeyedCodec<>("MaxCap", Codec.DOUBLE, false),
                        (d, v) -> d.maxCap = v, d -> d.maxCap)
                .add()
                // The farther from world spawn, the harder: an additive bonus on the zone/biome floor.
                .append(new KeyedCodec<>("DistanceEscalation", DistanceEscalation.CODEC, false),
                        (d, v) -> d.distanceEscalation = v, d -> d.distanceEscalation)
                .add()
                // The base difficulty -> stat curve (per-point HP/out-damage growth + incoming reduction).
                .append(new KeyedCodec<>("StatCurve", StatCurve.CODEC, false),
                        (d, v) -> d.statCurve = v, d -> d.statCurve)
                .add()
                .build();

        @Nullable private Double minCap;
        @Nullable private Double maxCap;
        @Nullable private DistanceEscalation distanceEscalation;
        @Nullable private StatCurve statCurve;

        @Nullable public Double getMinCap() { return minCap; }
        @Nullable public Double getMaxCap() { return maxCap; }
        @Nullable public DistanceEscalation getDistanceEscalation() { return distanceEscalation; }
        @Nullable public StatCurve getStatCurve() { return statCurve; }
    }

    /**
     * Distance-from-spawn escalation: past {@code StartDistanceBlocks} (XZ Euclidean from the world
     * spawn point) every extra {@code BlocksPerPoint} blocks adds +1 difficulty on top of the
     * zone/biome floor, up to {@code MaxBonus}. The SAME bonus also raises the rarity spawn chance by
     * {@code RarityChancePerPoint} per point (clamped to 1.0), so the deep frontier is not just
     * higher-band - it is DENSER with scaled mobs. Far enough out, every zone is deadly.
     */
    public static final class DistanceEscalation {
        public static final BuilderCodec<DistanceEscalation> CODEC = BuilderCodec
                .builder(DistanceEscalation.class, DistanceEscalation::new)
                .append(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                        (e, v) -> e.enabled = v, e -> e.enabled)
                .add()
                // Escalation-free radius around the world spawn (blocks, XZ Euclidean).
                .append(new KeyedCodec<>("StartDistanceBlocks", Codec.DOUBLE, false),
                        (e, v) -> e.startDistanceBlocks = v, e -> e.startDistanceBlocks)
                .add()
                // Blocks per +1 difficulty past the start radius.
                .append(new KeyedCodec<>("BlocksPerPoint", Codec.DOUBLE, false),
                        (e, v) -> e.blocksPerPoint = v, e -> e.blocksPerPoint)
                .add()
                // Ceiling on the additive difficulty bonus.
                .append(new KeyedCodec<>("MaxBonus", Codec.DOUBLE, false),
                        (e, v) -> e.maxBonus = v, e -> e.maxBonus)
                .add()
                // Rarity-spawn-chance bonus per escalation point (chance clamps to 1.0).
                .append(new KeyedCodec<>("RarityChancePerPoint", Codec.DOUBLE, false),
                        (e, v) -> e.rarityChancePerPoint = v, e -> e.rarityChancePerPoint)
                .add()
                .build();

        @Nullable private Boolean enabled;
        @Nullable private Double startDistanceBlocks;
        @Nullable private Double blocksPerPoint;
        @Nullable private Double maxBonus;
        @Nullable private Double rarityChancePerPoint;

        @Nullable public Boolean getEnabled() { return enabled; }
        @Nullable public Double getStartDistanceBlocks() { return startDistanceBlocks; }
        @Nullable public Double getBlocksPerPoint() { return blocksPerPoint; }
        @Nullable public Double getMaxBonus() { return maxBonus; }
        @Nullable public Double getRarityChancePerPoint() { return rarityChancePerPoint; }
    }

    /**
     * The base difficulty -> stat curve applied to every hostile mob: per-point HP and out-damage
     * growth plus a per-point incoming-damage reduction, each with a safety cap ({@code MaxHpMult},
     * {@code MaxOutDamageMult}, {@code MinInDamageMult}). Fed to {@code MobScaleFold.DifficultyStatCurve}
     * so a mob's stats rise smoothly with its effective difficulty (floor + escalation + group delta).
     *
     * <p>Every leaf is a NULLABLE wrapper so a preset overlay may partially fill the group (an
     * unset leaf folds through to the jar Default).
     */
    public static final class StatCurve {
        public static final BuilderCodec<StatCurve> CODEC = BuilderCodec.builder(StatCurve.class, StatCurve::new)
                // HP multiplier gained per difficulty point above 1 (before the MaxHpMult cap).
                .append(new KeyedCodec<>("HpPerPoint", Codec.DOUBLE, false),
                        (c, v) -> c.hpPerPoint = v, c -> c.hpPerPoint)
                .add()
                // Outgoing-damage multiplier gained per difficulty point above 1 (before MaxOutDamageMult).
                .append(new KeyedCodec<>("OutDamagePerPoint", Codec.DOUBLE, false),
                        (c, v) -> c.outDamagePerPoint = v, c -> c.outDamagePerPoint)
                .add()
                // Incoming-damage reduction gained per difficulty point above 1 (floored at MinInDamageMult).
                .append(new KeyedCodec<>("InDamageReductionPerPoint", Codec.DOUBLE, false),
                        (c, v) -> c.inDamageReductionPerPoint = v, c -> c.inDamageReductionPerPoint)
                .add()
                // Safety cap on the HP multiplier the curve may reach.
                .append(new KeyedCodec<>("MaxHpMult", Codec.DOUBLE, false),
                        (c, v) -> c.maxHpMult = v, c -> c.maxHpMult)
                .add()
                // Safety cap on the outgoing-damage multiplier the curve may reach.
                .append(new KeyedCodec<>("MaxOutDamageMult", Codec.DOUBLE, false),
                        (c, v) -> c.maxOutDamageMult = v, c -> c.maxOutDamageMult)
                .add()
                // Safety floor on the incoming-damage multiplier (the most a mob can shrug off).
                .append(new KeyedCodec<>("MinInDamageMult", Codec.DOUBLE, false),
                        (c, v) -> c.minInDamageMult = v, c -> c.minInDamageMult)
                .add()
                .build();

        @Nullable private Double hpPerPoint;
        @Nullable private Double outDamagePerPoint;
        @Nullable private Double inDamageReductionPerPoint;
        @Nullable private Double maxHpMult;
        @Nullable private Double maxOutDamageMult;
        @Nullable private Double minInDamageMult;

        @Nullable public Double getHpPerPoint() { return hpPerPoint; }
        @Nullable public Double getOutDamagePerPoint() { return outDamagePerPoint; }
        @Nullable public Double getInDamageReductionPerPoint() { return inDamageReductionPerPoint; }
        @Nullable public Double getMaxHpMult() { return maxHpMult; }
        @Nullable public Double getMaxOutDamageMult() { return maxOutDamageMult; }
        @Nullable public Double getMinInDamageMult() { return minInDamageMult; }
    }

    /**
     * A screen-anchored HUD overlay: enabled flag + named corner preset + pixel offsets. Positions
     * are the {@code hud/HudPosition.parse} corner names (TOP_LEFT | TOP_CENTER | ... | BOTTOM_RIGHT).
     */
    public static class Hud {
        public static final BuilderCodec<Hud> CODEC = BuilderCodec.builder(Hud.class, Hud::new)
                .append(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                        (h, v) -> h.enabled = v, h -> h.enabled)
                .add()
                .append(new KeyedCodec<>("Position", Codec.STRING, false),
                        (h, v) -> h.position = v, h -> h.position)
                .add()
                // Pixel offset from the anchored horizontal edge (or centre shift for a *_CENTER position).
                .append(new KeyedCodec<>("OffsetX", Codec.INTEGER, false),
                        (h, v) -> h.offsetX = v, h -> h.offsetX)
                .add()
                // Pixel offset from the anchored vertical edge (or centre shift for a CENTER_* position).
                .append(new KeyedCodec<>("OffsetY", Codec.INTEGER, false),
                        (h, v) -> h.offsetY = v, h -> h.offsetY)
                .add()
                // Whether the zone-difficulty HUD shows the current native zone/biome location name line.
                // (Meaningful only on ZoneHud; InspectorHud has its own codec and never decodes this leaf.)
                .append(new KeyedCodec<>("ShowLocationName", Codec.BOOLEAN, false),
                        (h, v) -> h.showLocationName = v, h -> h.showLocationName)
                .add()
                // Lang-key prefix for the friendly ZONE name: the raw zone id (Zone.name(), e.g. Zone4_Tier5)
                // is suffixed onto this and client-resolved. Default "server.map.region." reuses the base
                // game's own region names ("Cinder Wastes") with zero keys to author. A BLANK prefix prettifies
                // the raw id instead (the safe fallback for a modded world with no matching lang key).
                // (Meaningful only on ZoneHud.)
                .append(new KeyedCodec<>("ZoneNameKeyPrefix", Codec.STRING, false),
                        (h, v) -> h.zoneNameKeyPrefix = v, h -> h.zoneNameKeyPrefix)
                .add()
                // Lang-key prefix for the friendly BIOME name. Vanilla ships NO biome name key, so this
                // defaults BLANK (the raw Biome.getName() id is prettified). An owner who authors biome keys
                // sets this (e.g. "scaling.biome."). (Meaningful only on ZoneHud.)
                .append(new KeyedCodec<>("BiomeNameKeyPrefix", Codec.STRING, false),
                        (h, v) -> h.biomeNameKeyPrefix = v, h -> h.biomeNameKeyPrefix)
                .add()
                .build();

        @Nullable protected Boolean enabled;
        @Nullable protected String position;
        @Nullable protected Integer offsetX;
        @Nullable protected Integer offsetY;
        @Nullable protected Boolean showLocationName;
        @Nullable protected String zoneNameKeyPrefix;
        @Nullable protected String biomeNameKeyPrefix;

        @Nullable public Boolean getEnabled() { return enabled; }
        @Nullable public String getPosition() { return position; }
        @Nullable public Integer getOffsetX() { return offsetX; }
        @Nullable public Integer getOffsetY() { return offsetY; }
        @Nullable public Boolean getShowLocationName() { return showLocationName; }
        @Nullable public String getZoneNameKeyPrefix() { return zoneNameKeyPrefix; }
        @Nullable public String getBiomeNameKeyPrefix() { return biomeNameKeyPrefix; }
    }

    /** The mob-inspector overlay: the shared {@link Hud} anchor plus the crosshair raycast range. */
    public static final class InspectorHud extends Hud {
        public static final BuilderCodec<InspectorHud> CODEC = BuilderCodec
                .builder(InspectorHud.class, InspectorHud::new)
                .append(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                        (h, v) -> h.enabled = v, h -> h.enabled)
                .add()
                .append(new KeyedCodec<>("Position", Codec.STRING, false),
                        (h, v) -> h.position = v, h -> h.position)
                .add()
                .append(new KeyedCodec<>("OffsetX", Codec.INTEGER, false),
                        (h, v) -> h.offsetX = v, h -> h.offsetX)
                .add()
                .append(new KeyedCodec<>("OffsetY", Codec.INTEGER, false),
                        (h, v) -> h.offsetY = v, h -> h.offsetY)
                .add()
                // Crosshair-target search radius in blocks for the inspector raycast.
                .append(new KeyedCodec<>("RangeBlocks", Codec.DOUBLE, false),
                        (h, v) -> h.rangeBlocks = v, h -> h.rangeBlocks)
                .add()
                // Whether the inspector card shows the target mob's generated PORTRAIT
                // (Icons/ModelsGenerated/<role>.png). Default on; a role with no portrait falls back gracefully.
                .append(new KeyedCodec<>("PortraitEnabled", Codec.BOOLEAN, false),
                        (h, v) -> h.portraitEnabled = v, h -> h.portraitEnabled)
                .add()
                .build();

        @Nullable private Double rangeBlocks;
        @Nullable private Boolean portraitEnabled;

        @Nullable public Double getRangeBlocks() { return rangeBlocks; }
        @Nullable public Boolean getPortraitEnabled() { return portraitEnabled; }
    }

    /**
     * One per-world settings overlay (1.0.1): a world-name {@code Match} pattern (exact, a trailing-{@code *}
     * prefix, or bare {@code *}, resolved by {@code world/WorldOverrideMatcher} with the SAME precedence as
     * the MMO {@code WorldRulesMatcher}) bound to a PARTIAL settings body. Every leaf is a NULLABLE wrapper:
     * an unset leaf inherits the global fold for a matching world. Exposed knobs are exactly the ones that
     * take effect at spawn - {@code Intensity}, {@code RaritySpawnChance}, {@code PlayerScalingEnabled}, and
     * the full {@code Difficulty} group (caps + {@code DistanceEscalation} + {@code StatCurve}, REUSING the
     * top-level {@link Difficulty} codec). {@code RegionSizeChunks} is deliberately NOT here (it stays global
     * for {@code RegionPowerTracker} grid consistency).
     */
    public static final class WorldOverride {
        public static final BuilderCodec<WorldOverride> CODEC = BuilderCodec
                .builder(WorldOverride.class, WorldOverride::new)
                // The world selector: an exact world name, a trailing-"*" prefix, or bare "*".
                .append(new KeyedCodec<>("Match", Codec.STRING, false),
                        (w, v) -> w.match = v, w -> w.match)
                .add()
                // Per-world intensity multiplier on the stat-curve slopes (overrides the global intensity).
                .append(new KeyedCodec<>("Intensity", Codec.DOUBLE, false),
                        (w, v) -> w.intensity = v, w -> w.intensity)
                .add()
                // Per-world rarity spawn chance (overrides the global; clamped [0,1] at resolve).
                .append(new KeyedCodec<>("RaritySpawnChance", Codec.DOUBLE, false),
                        (w, v) -> w.raritySpawnChance = v, w -> w.raritySpawnChance)
                .add()
                // Per-world player/group-scaling toggle (false pins to the escalated floor).
                .append(new KeyedCodec<>("PlayerScalingEnabled", Codec.BOOLEAN, false),
                        (w, v) -> w.playerScalingEnabled = v, w -> w.playerScalingEnabled)
                .add()
                // Per-world difficulty group: caps + distance escalation + stat curve (reuses Difficulty).
                .append(new KeyedCodec<>("Difficulty", Difficulty.CODEC, false),
                        (w, v) -> w.difficulty = v, w -> w.difficulty)
                .add()
                .build();

        @Nullable private String match;
        @Nullable private Double intensity;
        @Nullable private Double raritySpawnChance;
        @Nullable private Boolean playerScalingEnabled;
        @Nullable private Difficulty difficulty;

        @Nullable public String getMatch() { return match; }
        @Nullable public Double getIntensity() { return intensity; }
        @Nullable public Double getRaritySpawnChance() { return raritySpawnChance; }
        @Nullable public Boolean getPlayerScalingEnabled() { return playerScalingEnabled; }
        @Nullable public Difficulty getDifficulty() { return difficulty; }
    }
}
