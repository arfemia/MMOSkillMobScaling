package com.ziggfreed.mmomobscaling.asset;

import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.hypixel.hytale.codec.builder.BuilderCodec;

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
 * {@link DistanceEscalation} with its own nested {@link EscalationOrigin}, {@link StatCurve} and
 * {@link Clamps}), {@link Hud} ({@code ZoneHud}) and {@link InspectorHud}.
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
 * <p>Map-shaped content (the rarity ladder, the zone and biome floors) is deliberately NOT here:
 * its canonical home is the per-type keyed assets ({@code Rarities/*.json}, {@code Difficulty/*.json}).
 */
public final class MobScalingSettingsAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, MobScalingSettingsAsset>> {

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String activePreset;
    @Nullable private Boolean enabled;
    @Nullable private Double raritySpawnChance;
    @Nullable private OpenWorld openWorld;
    @Nullable private Difficulty difficulty;
    @Nullable private Hud zoneHud;
    @Nullable private InspectorHud inspectorHud;

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
            // Chance a hostile mob rolls a non-plain rarity (before the distance-escalation bonus).
            .append(new KeyedCodec<>("RaritySpawnChance", Codec.DOUBLE, false),
                    (a, v) -> a.raritySpawnChance = v, a -> a.raritySpawnChance)
            .add()
            // Open-world group-power aggregation (region buckets, fold mode, band, the protected ring).
            .append(new KeyedCodec<>("OpenWorld", OpenWorld.CODEC, false),
                    (a, v) -> a.openWorld = v, a -> a.openWorld)
            .add()
            // Effective-difficulty clamps + the distance-from-spawn escalation curve + the stat curve.
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
            .build();

    public MobScalingSettingsAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    @Nullable public String getActivePreset() { return activePreset; }
    @Nullable public Boolean getEnabled() { return enabled; }
    @Nullable public Double getRaritySpawnChance() { return raritySpawnChance; }
    @Nullable public OpenWorld getOpenWorld() { return openWorld; }
    @Nullable public Difficulty getDifficulty() { return difficulty; }
    @Nullable public Hud getZoneHud() { return zoneHud; }
    @Nullable public InspectorHud getInspectorHud() { return inspectorHud; }

    /** Open-world group-power aggregation: how nearby players fold into a region's difficulty delta. */
    public static final class OpenWorld {
        public static final BuilderCodec<OpenWorld> CODEC = BuilderCodec.builder(OpenWorld.class, OpenWorld::new)
                // How a region's participant powers fold: SOLO | AVERAGE | PEAK | WEIGHTED | DISABLED.
                .append(new KeyedCodec<>("AggregationMode", Codec.STRING, false),
                        (o, v) -> o.aggregationMode = v, o -> o.aggregationMode)
                .metadata(EditorSchema.oneOfDocumented(
                        "SOLO", "Each player's own power only",
                        "AVERAGE", "The mean of nearby participants",
                        "PEAK", "The strongest nearby participant",
                        "WEIGHTED", "A proximity-weighted fold",
                        "DISABLED", "No group aggregation"))
                .add()
                // Proximity sub-grid size (chunks per side) WITHIN a native zone; also the whole
                // region key in a world without zone data (the chunk-grid fallback). A world file may
                // set its own: a region bucket is keyed by world, so the grid only has to agree within one.
                .append(new KeyedCodec<>("RegionSizeChunks", Codec.INTEGER, false),
                        (o, v) -> o.regionSizeChunks = v, o -> o.regionSizeChunks)
                .add()
                // Max absolute difficulty swing the region-power delta may add over the floor.
                .append(new KeyedCodec<>("GroupDeltaBandWidth", Codec.DOUBLE, false),
                        (o, v) -> o.groupDeltaBandWidth = v, o -> o.groupDeltaBandWidth)
                .add()
                // When true, the group-power delta may only RAISE a region's difficulty over the floor,
                // never lower it (a weak lone arrival never softens a zone below its authored baseline).
                .append(new KeyedCodec<>("OnlyRaiseDifficulty", Codec.BOOLEAN, false),
                        (o, v) -> o.onlyRaiseDifficulty = v, o -> o.onlyRaiseDifficulty)
                .add()
                // Whether player/group-based scaling (the region-power group delta) applies at all.
                // false pins difficulty to the escalated floor regardless of nearby player power - the
                // per-world toggle a fixed-difficulty authored dungeon overrides to false.
                .append(new KeyedCodec<>("PlayerScalingEnabled", Codec.BOOLEAN, false),
                        (o, v) -> o.playerScalingEnabled = v, o -> o.playerScalingEnabled)
                .add()
                // Protected radius around the world spawn (blocks, XZ Euclidean) inside which the
                // player/group power delta does NOT apply, so a newcomer's home area is never inflated by a
                // passing strong group. Its OWN knob, fully independent of
                // Difficulty.DistanceEscalation.StartDistanceBlocks (which only gates the additive
                // distance bonus). 0 = no protected ring, player/group scaling applies everywhere.
                .append(new KeyedCodec<>("PlayerScalingStartRingBlocks", Codec.DOUBLE, false),
                        (o, v) -> o.playerScalingStartRingBlocks = v, o -> o.playerScalingStartRingBlocks)
                .add()
                .build();

        @Nullable private String aggregationMode;
        @Nullable private Integer regionSizeChunks;
        @Nullable private Double groupDeltaBandWidth;
        @Nullable private Boolean onlyRaiseDifficulty;
        @Nullable private Boolean playerScalingEnabled;
        @Nullable private Double playerScalingStartRingBlocks;

        @Nullable public String getAggregationMode() { return aggregationMode; }
        @Nullable public Integer getRegionSizeChunks() { return regionSizeChunks; }
        @Nullable public Double getGroupDeltaBandWidth() { return groupDeltaBandWidth; }
        @Nullable public Boolean getOnlyRaiseDifficulty() { return onlyRaiseDifficulty; }
        @Nullable public Boolean getPlayerScalingEnabled() { return playerScalingEnabled; }
        @Nullable public Double getPlayerScalingStartRingBlocks() { return playerScalingStartRingBlocks; }
    }

    /**
     * Effective-difficulty clamps + the world-baseline floor + the nested distance-from-spawn escalation,
     * the difficulty-to-stat curve and the safety clamps under it.
     */
    public static final class Difficulty {
        public static final BuilderCodec<Difficulty> CODEC = BuilderCodec.builder(Difficulty.class, Difficulty::new)
                // The WORLD-BASELINE difficulty floor: the LOWEST-precedence floor under the authored
                // zone/biome Difficulty/*.json mappings - used only when no mapping matches. 0.0 = none.
                .append(new KeyedCodec<>("Floor", Codec.DOUBLE, false),
                        (d, v) -> d.floor = v, d -> d.floor)
                .add()
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
                // The difficulty -> stat curve: the slopes and the two ceilings that are the curve's range.
                .append(new KeyedCodec<>("StatCurve", StatCurve.CODEC, false),
                        (d, v) -> d.statCurve = v, d -> d.statCurve)
                .add()
                // The safety rails that are not the curve's shape: per-axis floors and ceilings.
                .append(new KeyedCodec<>("Clamps", Clamps.CODEC, false),
                        (d, v) -> d.clamps = v, d -> d.clamps)
                .add()
                .build();

        @Nullable private Double floor;
        @Nullable private Double minCap;
        @Nullable private Double maxCap;
        @Nullable private DistanceEscalation distanceEscalation;
        @Nullable private StatCurve statCurve;
        @Nullable private Clamps clamps;

        @Nullable public Double getFloor() { return floor; }
        @Nullable public Double getMinCap() { return minCap; }
        @Nullable public Double getMaxCap() { return maxCap; }
        @Nullable public DistanceEscalation getDistanceEscalation() { return distanceEscalation; }
        @Nullable public StatCurve getStatCurve() { return statCurve; }
        @Nullable public Clamps getClamps() { return clamps; }
    }

    /**
     * Distance-from-spawn escalation: past {@code StartDistanceBlocks} (XZ Euclidean from the world
     * spawn point) every extra {@code BlocksPerPoint} blocks adds +1 difficulty on top of the
     * zone/biome floor, up to {@code MaxBonus}. The SAME bonus also raises the rarity spawn chance by
     * {@code RarityChancePerPoint} per point (clamped to 1.0), so the deep frontier is not just
     * higher-band - it is DENSER with scaled mobs. Far enough out, every zone is deadly.
     *
     * <p><b>Both distances are in BLOCKS, measured in 32-block steps.</b> The distance is taken from the
     * centre of the chunk the mob spawned in, not from the mob itself, so every mob in one chunk reads the
     * same distance and the reading moves a chunk at a time. That is immaterial at realistic thresholds
     * (thousands of blocks) and it is what keeps the zone read memoizable per chunk; it is worth knowing
     * only if you are testing at close range and wondering why the number steps.
     *
     * <p><b>These are a SECOND radial ramp on top of the zone gradient.</b> The shipped
     * {@code Difficulty/*.json} mappings already rise outward (Zone1 at 1 to Zone4_Tier5 at 28), and this
     * bonus adds to whichever floor they resolved. Set {@code StartDistanceBlocks} beyond the range your
     * players actually travel and escalation never engages at all, which reads as "the far zones are
     * still low level". {@code /mobscaling inspect} reports the distance from spawn at your feet along
     * with the base floor and the bonus, so stand where you want the ramp to begin and read the number
     * off that rather than guessing.
     *
     * <p><b>Where the distance is measured FROM is the nested {@link EscalationOrigin} ({@code Origin}).</b>
     * Unset (the shipped default), the origin is the world's own spawn point as its spawn provider answers
     * it; an authored {@code X} or {@code Z} pins that axis outright. The protected newcomer ring
     * ({@code OpenWorld.PlayerScalingStartRingBlocks}) is a separate knob measured from the spawn point
     * and does not move with this origin.
     */
    public static final class DistanceEscalation {
        public static final BuilderCodec<DistanceEscalation> CODEC = BuilderCodec
                .builder(DistanceEscalation.class, DistanceEscalation::new)
                .append(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                        (e, v) -> e.enabled = v, e -> e.enabled)
                .add()
                // The point the distance is measured from: unset = the world's spawn point.
                .append(new KeyedCodec<>("Origin", EscalationOrigin.CODEC, false),
                        (e, v) -> e.origin = v, e -> e.origin)
                .add()
                // Escalation-free radius around the origin (blocks, XZ Euclidean).
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
        @Nullable private EscalationOrigin origin;
        @Nullable private Double startDistanceBlocks;
        @Nullable private Double blocksPerPoint;
        @Nullable private Double maxBonus;
        @Nullable private Double rarityChancePerPoint;

        @Nullable public Boolean getEnabled() { return enabled; }
        @Nullable public EscalationOrigin getOrigin() { return origin; }
        @Nullable public Double getStartDistanceBlocks() { return startDistanceBlocks; }
        @Nullable public Double getBlocksPerPoint() { return blocksPerPoint; }
        @Nullable public Double getMaxBonus() { return maxBonus; }
        @Nullable public Double getRarityChancePerPoint() { return rarityChancePerPoint; }
    }

    /**
     * The point distance escalation measures from ({@code Difficulty.DistanceEscalation.Origin}): a block
     * {@code X} and a block {@code Z}, each a NULLABLE leaf. An unset axis reads the world's own spawn point
     * on that axis (the spawn provider's answer for the world), so an absent group, or an empty one, keeps
     * every existing server measuring from exactly where it does today; an authored axis pins it outright,
     * which is how an owner names the origin on a world whose spawn provider holds several spawn points (the
     * engine picks one of them for the resolved origin, not necessarily the first) or whose home city is
     * not where players first appear.
     *
     * <p>There is deliberately NO {@code Y}: the distance is horizontal (XZ Euclidean, from the centre of
     * the chunk a mob spawns in), so a height would be a leaf nothing reads. Coordinates may be negative,
     * and whole blocks are all the precision the measure has, since it steps a chunk at a time.
     */
    public static final class EscalationOrigin {
        public static final BuilderCodec<EscalationOrigin> CODEC = BuilderCodec
                .builder(EscalationOrigin.class, EscalationOrigin::new)
                .append(new KeyedCodec<>("X", Codec.DOUBLE, false),
                        (o, v) -> o.x = v, o -> o.x)
                .documentation("The block X coordinate distance escalation is measured from. Leave it out to"
                        + " measure from the world's spawn point; set it to pin the origin, for instance on a"
                        + " world with several spawn points or a home city away from where players appear."
                        + " May be negative.")
                .add()
                .append(new KeyedCodec<>("Z", Codec.DOUBLE, false),
                        (o, v) -> o.z = v, o -> o.z)
                .documentation("The block Z coordinate distance escalation is measured from. Leave it out to"
                        + " measure from the world's spawn point. There is no Y: the distance is horizontal.")
                .add()
                .build();

        @Nullable private Double x;
        @Nullable private Double z;

        @Nullable public Double getX() { return x; }
        @Nullable public Double getZ() { return z; }
    }

    /**
     * The difficulty -> stat curve applied to every hostile mob, evaluated at the mob's difficulty times
     * its rarity's and variant's {@code DifficultyMultiplier}: ONE effective-HP slope
     * ({@code EffectiveHpPerPoint}) whose result is split between the visible health bar and the invisible
     * incoming-damage multiplier by {@code VisibleHpShare} ({@code hp = ehp ^ share},
     * {@code in = ehp ^ (share - 1)}, so {@code hp / in} is exactly {@code ehp}), the outgoing-damage curve
     * ({@code out = 1 + OutDamageScale * (d - 1) ^ OutDamageShape}: a shape of 1.0 is a straight line whose
     * scale is a plain per-point slope, a shape above 1.0 bends it upward), and the two ceilings that are the
     * curve's own range ({@code MaxEffectiveHpMult}, the composite rail on {@code hp / in};
     * {@code MaxOutDamageMult}). Fed to {@code MobScaleFold.DifficultyStatCurve}.
     *
     * <p>Every leaf is a NULLABLE wrapper so a preset overlay may partially fill the group (an
     * unset leaf folds through to the jar Default).
     */
    public static final class StatCurve {
        public static final BuilderCodec<StatCurve> CODEC = BuilderCodec.builder(StatCurve.class, StatCurve::new)
                // Effective-HP multiplier gained per difficulty point above 1 (the one tank slope).
                .append(new KeyedCodec<>("EffectiveHpPerPoint", Codec.DOUBLE, false),
                        (c, v) -> c.effectiveHpPerPoint = v, c -> c.effectiveHpPerPoint)
                .documentation("How much longer a mob takes to kill for each difficulty point above 1, as a"
                        + " fraction: 0.05 means a mob at difficulty 21 takes twice as long as one at 1. It is"
                        + " the whole tank axis; the share below decides how much of it shows on the health"
                        + " bar and how much is silent damage reduction.")
                .add()
                // The exponent share of the effective HP that shows on the health bar, in [0, 1].
                .append(new KeyedCodec<>("VisibleHpShare", Codec.DOUBLE, false),
                        (c, v) -> c.visibleHpShare = v, c -> c.visibleHpShare)
                .documentation("Which part of the toughness is visible health and which is quiet damage"
                        + " reduction, from 0 to 1. At 1.0 the whole toughness is on the health bar and every"
                        + " hit lands for full damage; at 0.5 the bar shows the square root and the mob shrugs"
                        + " off the rest per hit. The two always multiply back to the same time to kill.")
                .add()
                // The coefficient of the outgoing-damage curve, 1 + Scale * (d - 1) ^ Shape (before MaxOutDamageMult).
                .append(new KeyedCodec<>("OutDamageScale", Codec.DOUBLE, false),
                        (c, v) -> c.outDamageScale = v, c -> c.outDamageScale)
                .documentation("How fast a mob's hit grows with difficulty: the extra fraction of its base hit"
                        + " gained per unit of the shaped distance above difficulty 1. With OutDamageShape at 1.0"
                        + " it is a plain per-point slope, so 0.25 means a mob at difficulty 5 hits for twice its"
                        + " base; with a shape above 1.0 the same scale buys less early and more late.")
                .add()
                // The exponent of the outgoing-damage curve: 1.0 is a straight line.
                .append(new KeyedCodec<>("OutDamageShape", Codec.DOUBLE, false),
                        (c, v) -> c.outDamageShape = v, c -> c.outDamageShape)
                .documentation("How the outgoing-damage curve bends. 1.0 is a straight line: every difficulty"
                        + " point adds the same amount. Above 1.0 the curve starts gently and steepens, so a"
                        + " low-difficulty mob hits a little harder than a plain one while a high-difficulty mob"
                        + " hits a great deal harder; below 1.0 the reverse. It does not touch the tank axis.")
                .add()
                // The composite rail on hp / in (and the curve's own effective-HP ceiling).
                .append(new KeyedCodec<>("MaxEffectiveHpMult", Codec.DOUBLE, false),
                        (c, v) -> c.maxEffectiveHpMult = v, c -> c.maxEffectiveHpMult)
                .documentation("The most times longer than a plain mob at difficulty 1 that any mob may take"
                        + " to kill, health, damage reduction and a resistance affix all counted together."
                        + " When an affix pushes past it the damage reduction gives way, never the visible"
                        + " health. Keep it well above where the curve alone lands at your highest"
                        + " difficulty, or every tier ends up identical there.")
                .add()
                // Safety cap on the outgoing-damage multiplier the curve may reach.
                .append(new KeyedCodec<>("MaxOutDamageMult", Codec.DOUBLE, false),
                        (c, v) -> c.maxOutDamageMult = v, c -> c.maxOutDamageMult)
                .documentation("The most times its base hit any mob may deal. The same advice: keep it well"
                        + " above the curve's own top so the rarity ladder still means something there.")
                .add()
                .build();

        @Nullable private Double effectiveHpPerPoint;
        @Nullable private Double visibleHpShare;
        @Nullable private Double outDamageScale;
        @Nullable private Double outDamageShape;
        @Nullable private Double maxEffectiveHpMult;
        @Nullable private Double maxOutDamageMult;

        @Nullable public Double getEffectiveHpPerPoint() { return effectiveHpPerPoint; }
        @Nullable public Double getVisibleHpShare() { return visibleHpShare; }
        @Nullable public Double getOutDamageScale() { return outDamageScale; }
        @Nullable public Double getOutDamageShape() { return outDamageShape; }

        @Nullable public Double getMaxEffectiveHpMult() { return maxEffectiveHpMult; }
        @Nullable public Double getMaxOutDamageMult() { return maxOutDamageMult; }
    }

    /**
     * The safety rails that are NOT the curve's shape: the floor under the visible health multiplier, the
     * ceiling on the incoming-damage multiplier, the floor under the outgoing multiplier, and the band the
     * loot pass count stays in. Applied per axis after the affix deltas; the curve's own
     * {@code MaxEffectiveHpMult} is enforced after them. Fed to {@code MobScaleFold.Clamps}. Every leaf is
     * a NULLABLE wrapper so a preset overlay may partially fill the group.
     */
    public static final class Clamps {
        public static final BuilderCodec<Clamps> CODEC = BuilderCodec.builder(Clamps.class, Clamps::new)
                .append(new KeyedCodec<>("MinHpMult", Codec.DOUBLE, false),
                        (c, v) -> c.minHpMult = v, c -> c.minHpMult)
                .documentation("The least a mob's health may be scaled to, as a multiple of its base. Only an"
                        + " affix with a negative health fraction can pull below 1.0, and never below this.")
                .add()
                .append(new KeyedCodec<>("MaxInDamageMult", Codec.DOUBLE, false),
                        (c, v) -> c.maxInDamageMult = v, c -> c.maxInDamageMult)
                .documentation("The most damage a mob may be made to TAKE per hit, as a multiple of normal."
                        + " 1.0 means scaling never makes a mob softer than a plain one; above 1.0 allows a"
                        + " glass-cannon affix.")
                .add()
                .append(new KeyedCodec<>("MinOutDamageMult", Codec.DOUBLE, false),
                        (c, v) -> c.minOutDamageMult = v, c -> c.minOutDamageMult)
                .documentation("The least a mob's hit may be scaled to, as a multiple of its base hit.")
                .add()
                .append(new KeyedCodec<>("MinLootMult", Codec.DOUBLE, false),
                        (c, v) -> c.minLootMult = v, c -> c.minLootMult)
                .documentation("The fewest passes any death-loot block is rolled; a fraction is that chance of"
                        + " one pass.")
                .add()
                .append(new KeyedCodec<>("MaxLootMult", Codec.DOUBLE, false),
                        (c, v) -> c.maxLootMult = v, c -> c.maxLootMult)
                .documentation("The most passes any death-loot block is rolled, whatever the rarity, variant"
                        + " and affix multipliers come to. Raise it when a stacked tier should pay out more;"
                        + " a boss carrying an overlay reaches the product of both loot multipliers.")
                .add()
                .build();

        @Nullable private Double minHpMult;
        @Nullable private Double maxInDamageMult;
        @Nullable private Double minOutDamageMult;
        @Nullable private Double minLootMult;
        @Nullable private Double maxLootMult;

        @Nullable public Double getMinHpMult() { return minHpMult; }
        @Nullable public Double getMaxInDamageMult() { return maxInDamageMult; }
        @Nullable public Double getMinOutDamageMult() { return minOutDamageMult; }
        @Nullable public Double getMinLootMult() { return minLootMult; }
        @Nullable public Double getMaxLootMult() { return maxLootMult; }
    }

    /**
     * A screen-anchored HUD overlay: enabled flag + named corner preset + pixel offsets. Positions
     * are the {@code ziggfreed-common ui/hud/HudPosition.parse} corner names (TOP_LEFT | TOP_CENTER | ... | BOTTOM_RIGHT).
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
                // defaults BLANK, and a blank prefix shows no biome line at all (the card names the zone
                // alone; a raw Biome.getName() id is never shown). An owner who authors biome keys sets this
                // (e.g. "mmomobscaling.biome.") with a key for every biome. (Meaningful only on ZoneHud.)
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

}
