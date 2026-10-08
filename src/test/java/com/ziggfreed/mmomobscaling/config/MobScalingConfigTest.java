package com.ziggfreed.mmomobscaling.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.mmomobscaling.MobScalingGate;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.Clamps;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.Difficulty;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.DistanceEscalation;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.EscalationOrigin;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.OpenWorld;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.StatCurve;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold;

/**
 * Unit tests for {@link MobScalingConfig} and the {@link MobScalingGate} registration gate.
 *
 * <p>The config is driven entirely by the {@link com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset}
 * codec: defaults decode from the jar-bundled {@code Server/MmoMobScaling/Settings/Default.json}
 * (on the test classpath via main resources), and a partial owner file overlays it. There are no
 * Java-baked config values to assert against - these tests verify the CODEC-decoded defaults + the
 * owner-over-default fold + the gate.
 *
 * <p>No test here restates a BALANCE number out of the shipped file. A balance leaf is asserted as a
 * relationship: the fold carries the value the codec read (both sides read the same shipped file, so a
 * retune moves both), a structural invariant the code needs whatever the tuning, or a lower layer's live
 * value captured BEFORE an override is applied. A balancing pass on {@code Default.json} must never need a
 * test edit.
 *
 * <p>The gate is exercised through {@link MobScalingGate} rather than
 * {@code MobScalingPlugin.shouldRegisterSystems}: loading the {@code JavaPlugin}-extending plugin
 * class in a unit JVM fails (its {@code PluginBase} -> {@code MetricsRegistry} static-init chain
 * throws without a running server). {@code MobScalingPlugin} delegates to this same predicate.
 */
class MobScalingConfigTest {

    /** Fresh singleton loaded from the jar defaults only (no owner file). */
    private static MobScalingConfig freshDefaults() {
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        cfg.setConfigPath(null);
        cfg.load();
        return cfg;
    }

    /**
     * The shipped {@code Settings/Default.json} decoded through the REAL codec, the same read production
     * makes: the raw leaves a test compares the folded config against, so no shipped number is retyped.
     */
    @Nonnull
    private static MobScalingSettingsAsset shippedAsset() {
        try (InputStream in = MobScalingConfigTest.class.getResourceAsStream(
                "/Server/MmoMobScaling/Settings/Default.json")) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return MobScalingSettingsAsset.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
        } catch (Exception e) {
            throw new IllegalStateException("shipped Settings/Default.json missing or undecodable on the test classpath", e);
        }
    }

    /**
     * The fold is FAITHFUL for one leaf: what the config exposes is exactly what the codec read out of the
     * shipped file. A fold that walked the wrong leaf, dropped a nesting level, fell through to the
     * broken-jar fail-safe or silently clamped a shipped value fails here, with no shipped number restated.
     */
    private static void carries(@Nullable Double authored, double folded, @Nonnull String leaf) {
        assertNotNull(authored, leaf + " is authored in the shipped Default.json (an absent leaf falls to the fail-safe)");
        assertEquals(authored, folded, 1e-9, leaf + " folds through unchanged (a mismatch means the fold read the"
                + " wrong leaf, dropped a nesting level, fell to the fail-safe or clamped a shipped value)");
    }

    /**
     * Top-level codec keys that are not settings leaves and are never authored in a body: {@code Tags}, the
     * store's own tagset field {@code AssetBuilderCodec} injects into every asset, and {@code Name}, this
     * schema's optional echo of the asset key (its setter is a no-op and the filename is authoritative).
     */
    private static final Set<String> NOT_BODY_LEAVES = Set.of("Tags", "Name");

    /**
     * Leaves whose ABSENCE is the shipped value rather than a fall-through to a fail-safe: an unset
     * escalation-origin axis means "the world's spawn point", and no number can stand for that, so the
     * shipped Default authors the {@code Origin} group (for its owner-facing comment) and leaves both axes
     * out on purpose. {@link #shippedDefaultAuthorsEveryLeaf} asserts that they ARE unset there.
     */
    private static final Set<String> OPTIONAL_LEAVES = Set.of(
            "Difficulty.DistanceEscalation.Origin.X", "Difficulty.DistanceEscalation.Origin.Z");

    /**
     * Walk every key {@code codec} DECLARES, read it off {@code node} through its conventional {@code get<Key>()},
     * and record the path of each that reads {@code null}, recursing into a nested group through that group's
     * own static {@code CODEC}. The codec is the one schema authority, so asking it (rather than the Java
     * getters) is what keeps an inherited getter the codec never decodes - {@code InspectorHud}'s three
     * location-name leaves - out of the walk, and picks up a leaf added to the schema later without anyone
     * listing it here.
     */
    private static void collectNullLeaves(@Nonnull BuilderCodec<?> codec, @Nonnull Object node,
            @Nonnull String path, @Nonnull List<String> out) throws Exception {
        for (String key : codec.getEntries().keySet()) {
            if (path.isEmpty() && NOT_BODY_LEAVES.contains(key)) {
                continue;
            }
            Object value = node.getClass().getMethod("get" + key).invoke(node);
            String leaf = path + key;
            if (value == null) {
                if (!OPTIONAL_LEAVES.contains(leaf)) {
                    out.add(leaf);
                }
            } else if (value.getClass().getEnclosingClass() == MobScalingSettingsAsset.class) {
                BuilderCodec<?> nested = (BuilderCodec<?>) value.getClass().getField("CODEC").get(null);
                collectNullLeaves(nested, value, leaf + ".", out);
            }
        }
    }

    @AfterEach
    void resetWorlds() {
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        worlds.setOwnerDir(null);
        worlds.applyPackLayer(Map.of());
    }

    /**
     * Feed the jar-bundled {@code Server/MmoMobScaling/Worlds/*.json} payloads into
     * {@link WorldSettingsConfig}'s pack layer (in production the engine store delivers them on
     * {@code LoadedAssetsEvent}; a unit JVM loads them straight off the test classpath). This
     * exercises the REAL shipped files (flat, self-contained; no shared Parent).
     */
    private static void loadJarWorlds() {
        Map<String, JsonObject> bodies = new LinkedHashMap<>();
        for (String name : List.of("DungeonOfFear_I", "DungeonOfFear_II",
                "DungeonOfFear_III", "KweebecNightmare")) {
            try (InputStream in = MobScalingConfigTest.class.getResourceAsStream(
                    "/Server/MmoMobScaling/Worlds/" + name + ".json")) {
                JsonObject root = JsonParser.parseString(
                        new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                bodies.put(name, root.getAsJsonObject("Payload"));
            } catch (Exception e) {
                throw new IllegalStateException("jar world file missing on the test classpath: " + name, e);
            }
        }
        WorldSettingsConfig.getInstance().applyPackLayer(bodies);
    }

    /** Write one OWNER world file (bare body) into {@code <tmp>/worlds/} and refold. */
    private static void ownerWorld(Path tmp, String id, String body) throws Exception {
        Path dir = tmp.resolve("worlds");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".json"), body, StandardCharsets.UTF_8);
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        worlds.setOwnerDir(dir);
        worlds.refold();
    }

    @Test
    void shippedDefaultAuthorsEveryLeaf() throws Exception {
        // One assertion, no magnitudes: the shipped Default.json decodes through the real codec with every
        // leaf present, so nothing the fold reads can fall through to the broken-jar fail-safe.
        List<String> missing = new ArrayList<>();
        MobScalingSettingsAsset shipped = shippedAsset();
        collectNullLeaves(MobScalingSettingsAsset.CODEC, shipped, "", missing);
        assertTrue(missing.isEmpty(), "leaves the shipped Default.json does not author: " + missing);
        // The one deliberate gap: the escalation origin ships UNSET, so every server keeps measuring from
        // its world's own spawn point. The group is authored (it carries the owner-facing comment); the axes
        // are not, because a number there would move every server's difficulty gradient onto that number.
        EscalationOrigin origin = shipped.getDifficulty().getDistanceEscalation().getOrigin();
        assertNotNull(origin, "the shipped Default authors the Origin group (empty, with its comment)");
        assertNull(origin.getX(), "the shipped Default pins no origin X: the world spawn point stands");
        assertNull(origin.getZ(), "the shipped Default pins no origin Z: the world spawn point stands");
    }

    @Test
    void defaultsDecodeFromTheCodecAsset() {
        MobScalingConfig cfg = freshDefaults();
        MobScalingSettingsAsset shipped = shippedAsset();

        assertTrue(cfg.isEnabled(), "Enabled default");
        assertTrue(cfg.isPlayerScalingEnabled(), "PlayerScalingEnabled default (global, 1.0.1)");
        assertEquals("AVERAGE", cfg.getOpenWorldAggregationMode(), "OpenWorld.AggregationMode default");
        assertEquals(3, cfg.getRegionSizeChunks(), "OpenWorld.RegionSizeChunks default");
        assertTrue(cfg.isDistanceEscalationEnabled(), "Difficulty.DistanceEscalation.Enabled default");

        // THE FOLD IS FAITHFUL. Every balance leaf is compared against the raw leaf the codec read out of the
        // same shipped file, walked group by group here so the equality also proves the fold walked the
        // NESTING (a flattened or mis-routed leaf reads a sibling's value, or the fail-safe, and fails).
        OpenWorld openWorld = shipped.getOpenWorld();
        Difficulty difficulty = shipped.getDifficulty();
        assertNotNull(openWorld, "the OpenWorld group is authored");
        assertNotNull(difficulty, "the Difficulty group is authored");
        DistanceEscalation escalation = difficulty.getDistanceEscalation();
        StatCurve curve = difficulty.getStatCurve();
        Clamps clamps = difficulty.getClamps();
        assertNotNull(escalation, "the doubly-nested Difficulty.DistanceEscalation group is authored");
        assertNotNull(curve, "the doubly-nested Difficulty.StatCurve group is authored");
        assertNotNull(clamps, "the doubly-nested Difficulty.Clamps group is authored");

        carries(shipped.getRaritySpawnChance(), cfg.getRaritySpawnChance(), "RaritySpawnChance");
        carries(openWorld.getGroupDeltaBandWidth(), cfg.getGroupDeltaBandWidth(), "OpenWorld.GroupDeltaBandWidth");
        carries(openWorld.getPlayerScalingStartRingBlocks(), cfg.getPlayerScalingStartRingBlocks(),
                "OpenWorld.PlayerScalingStartRingBlocks");
        carries(difficulty.getFloor(), cfg.getDifficultyFloor(), "Difficulty.Floor");
        carries(difficulty.getMinCap(), cfg.getDifficultyMinCap(), "Difficulty.MinCap");
        carries(difficulty.getMaxCap(), cfg.getDifficultyMaxCap(), "Difficulty.MaxCap");
        carries(escalation.getStartDistanceBlocks(), cfg.getEscalationStartDistanceBlocks(),
                "Difficulty.DistanceEscalation.StartDistanceBlocks");
        carries(escalation.getBlocksPerPoint(), cfg.getEscalationBlocksPerPoint(),
                "Difficulty.DistanceEscalation.BlocksPerPoint");
        carries(escalation.getMaxBonus(), cfg.getEscalationMaxBonus(), "Difficulty.DistanceEscalation.MaxBonus");
        carries(escalation.getRarityChancePerPoint(), cfg.getEscalationRarityChancePerPoint(),
                "Difficulty.DistanceEscalation.RarityChancePerPoint");
        carries(curve.getEffectiveHpPerPoint(), cfg.getStatCurveEffectiveHpPerPoint(),
                "Difficulty.StatCurve.EffectiveHpPerPoint");
        carries(curve.getVisibleHpShare(), cfg.getStatCurveVisibleHpShare(), "Difficulty.StatCurve.VisibleHpShare");
        carries(curve.getOutDamageScale(), cfg.getStatCurveOutDamageScale(), "Difficulty.StatCurve.OutDamageScale");
        carries(curve.getOutDamageShape(), cfg.getStatCurveOutDamageShape(), "Difficulty.StatCurve.OutDamageShape");
        carries(curve.getMaxEffectiveHpMult(), cfg.getStatCurveMaxEffectiveHpMult(),
                "Difficulty.StatCurve.MaxEffectiveHpMult");
        carries(curve.getMaxOutDamageMult(), cfg.getStatCurveMaxOutDamageMult(), "Difficulty.StatCurve.MaxOutDamageMult");
        carries(clamps.getMinHpMult(), cfg.getClampMinHpMult(), "Difficulty.Clamps.MinHpMult");
        carries(clamps.getMaxInDamageMult(), cfg.getClampMaxInDamageMult(), "Difficulty.Clamps.MaxInDamageMult");
        carries(clamps.getMinOutDamageMult(), cfg.getClampMinOutDamageMult(), "Difficulty.Clamps.MinOutDamageMult");
        carries(clamps.getMinLootMult(), cfg.getClampMinLootMult(), "Difficulty.Clamps.MinLootMult");
        carries(clamps.getMaxLootMult(), cfg.getClampMaxLootMult(), "Difficulty.Clamps.MaxLootMult");

        // STRUCTURE, whatever the tuning. Each invariant is what the consuming code needs, and the message
        // says why; a retune that keeps these holds, a retune that breaks one has broken the mechanic.
        assertTrue(cfg.getRaritySpawnChance() > 0.0 && cfg.getRaritySpawnChance() <= 1.0,
                "RaritySpawnChance is the probability the resolver adds the escalation bonus to and clamps to [0,1]:"
                        + " the shipped default must roll SOME rarity (0.0 is the broken-jar fail-safe, and a mod that"
                        + " never rolls a rarity is inert)");
        assertTrue(!cfg.isPlayerScalingEnabled() || cfg.getGroupDeltaBandWidth() > 0.0,
                "ScalingEngine clamps the participant delta to [-band, band], so player scaling that ships enabled"
                        + " needs a positive band or it can never move a region's difficulty");
        assertTrue(cfg.getDifficultyMinCap() < cfg.getDifficultyMaxCap(),
                "the resolver clamps every spawn's difficulty to [MinCap, MaxCap] and the fold collapses an inverted"
                        + " pair onto MinCap, so the caps must be strictly ordered or every mob spawns at ONE difficulty");
        assertTrue(cfg.getDifficultyFloor() >= cfg.getDifficultyMinCap()
                        && cfg.getDifficultyFloor() <= cfg.getDifficultyMaxCap(),
                "Difficulty.Floor is the baseline a chunk with no zone or biome mapping resolves to, and base + bonus"
                        + " is clamped to the caps, so a floor outside them is dead weight that never applies as written");
        // The escalation start is BALANCE, so this asserts the leaf decoded to something usable rather than
        // restating the shipped number (a retune must never need a test edit). It has to be positive, or
        // escalation would begin at the world spawn, and it has to sit inside the range players reach, or
        // escalation never engages at all - which is the failure a server owner actually reports.
        assertTrue(cfg.getEscalationStartDistanceBlocks() > 0.0
                        && cfg.getEscalationStartDistanceBlocks() < 100_000.0,
                "escalation start decoded to a reachable positive distance");
        assertTrue(cfg.getEscalationBlocksPerPoint() > 0.0,
                "BlocksPerPoint divides (distance - start) into difficulty points: escalationBonus returns 0 for a"
                        + " non-positive divisor and the fold floors it at 1, so a non-positive value is a ramp that"
                        + " never climbs or a shipped number silently rewritten");
        assertTrue(!cfg.isDistanceEscalationEnabled() || cfg.getEscalationMaxBonus() > 0.0,
                "escalationBonus returns 0 for a non-positive cap, so a ramp that ships enabled needs a positive"
                        + " MaxBonus or it never climbs");
        assertTrue(cfg.getEscalationRarityChancePerPoint() >= 0.0 && cfg.getEscalationRarityChancePerPoint() <= 1.0,
                "RarityChancePerPoint is a probability delta per escalation point, summed with the base chance and"
                        + " clamped to [0,1]: negative would make the frontier SAFER, above 1 saturates on the first point");
        // Difficulty.StatCurve: the per-difficulty stat curve every hostile mob is folded through. Both
        // slopes are BALANCE, derived from a matched player's own growth, so they move whenever that
        // derivation moves; assert the SHAPE the fold needs, never the number.
        assertTrue(cfg.getStatCurveEffectiveHpPerPoint() > 0.0,
                "EffectiveHpPerPoint is the slope of ehp = 1 + (d - 1) * slope: zero is the identity curve (the"
                        + " broken-jar fallback, indistinguishable from a dropped leaf) and negative would SHRINK a mob"
                        + " as difficulty rises");
        assertTrue(cfg.getStatCurveOutDamageScale() > 0.0,
                "OutDamageScale is the coefficient of out = 1 + scale * (d - 1) ^ shape: zero is the identity curve"
                        + " (the broken-jar fallback) and negative would make a mob hit SOFTER as difficulty rises");
        assertTrue(cfg.getStatCurveOutDamageShape() > 0.0,
                "OutDamageShape is the exponent on the difficulty distance: buildCurve reads a non-positive one as"
                        + " the straight line, so a shipped file authoring one is a shipped number silently rewritten");
        assertTrue(cfg.getStatCurveVisibleHpShare() > 0.0 && cfg.getStatCurveVisibleHpShare() <= 1.0,
                "VisibleHpShare is the exponent that splits the effective HP between the bar and silent damage"
                        + " reduction: 0 would put nothing on the health bar, above 1 would make a mob take MORE than"
                        + " normal damage as it gets tougher");
        assertTrue(cfg.getStatCurveMaxEffectiveHpMult() > 1.0,
                "the composite rail bounds hp / in from above, so a rail of 1 leaves the tank axis unable to rise at all");
        assertTrue(cfg.getStatCurveMaxOutDamageMult() > 1.0,
                "outFactor clamps to [1, MaxOutDamageMult] and the fold's damage axis is capped by it, so a ceiling"
                        + " of 1 leaves the damage axis unable to rise at all");
        // The rails must sit ABOVE the curve's own top of the shipped band, or the ladder flattens there: a
        // Legendary and a Boss at MaxCap would both read the rail. The relationship, not the numbers.
        double topEhp = 1.0 + (cfg.getDifficultyMaxCap() - 1.0) * cfg.getStatCurveEffectiveHpPerPoint();
        double topOut = 1.0 + cfg.getStatCurveOutDamageScale()
                * Math.pow(cfg.getDifficultyMaxCap() - 1.0, cfg.getStatCurveOutDamageShape());
        assertTrue(cfg.getStatCurveMaxEffectiveHpMult() > topEhp,
                "MaxEffectiveHpMult sits above the plain curve's value at MaxCap, so a tier still reads further along");
        assertTrue(cfg.getStatCurveMaxOutDamageMult() > topOut,
                "MaxOutDamageMult sits above the plain curve's value at MaxCap, so a tier still reads further along");
        // Difficulty.Clamps: the safety rails, each in the direction the fold applies it.
        assertTrue(cfg.getClampMinHpMult() > 0.0 && cfg.getClampMinHpMult() <= 1.0,
                "MinHpMult floors the visible health: zero would allow a health-less mob, above 1 would lift a plain one");
        assertTrue(cfg.getClampMaxInDamageMult() >= 1.0,
                "MaxInDamageMult ceilings the damage taken: below 1 would make every plain mob tougher than the engine made it");
        assertTrue(cfg.getClampMinOutDamageMult() > 0.0 && cfg.getClampMinOutDamageMult() <= 1.0,
                "MinOutDamageMult floors the hit: zero would allow a harmless mob, above 1 would lift a plain one");
        assertTrue(cfg.getClampMinLootMult() >= 0.0 && cfg.getClampMinLootMult() <= 1.0,
                "MinLootMult floors the pass count at or under one pass");
        assertTrue(cfg.getClampMaxLootMult() >= cfg.getClampMinLootMult(),
                "the loot band is ordered (the fold collapses an inverted pair onto the floor)");
        assertTrue(cfg.isZoneHudEnabled(), "ZoneHudEnabled default");
        assertEquals("TOP_LEFT", cfg.getZoneHudPosition(), "ZoneHudPosition default");
        assertEquals(16, cfg.getZoneHudOffsetX(), "ZoneHudOffsetX default");
        assertEquals(90, cfg.getZoneHudOffsetY(), "ZoneHudOffsetY default");
        assertTrue(cfg.isInspectorHudEnabled(), "InspectorHudEnabled default");
        assertEquals("TOP_LEFT", cfg.getInspectorHudPosition(), "InspectorHudPosition default (tucked under the zone HUD)");
        assertEquals(16, cfg.getInspectorHudOffsetX(), "InspectorHudOffsetX default");
        assertEquals(216, cfg.getInspectorHudOffsetY(), "InspectorHudOffsetY default");
        assertEquals(12.0, cfg.getInspectorRangeBlocks(), 1e-9, "InspectorRangeBlocks default");
    }

    @Test
    void gateReflectsEnabledFlag() {
        MobScalingConfig cfg = freshDefaults();
        assertTrue(MobScalingGate.shouldRegisterSystems(cfg), "enabled config should register");
        assertFalse(MobScalingGate.shouldRegisterSystems(null), "null config should not register");
    }

    @Test
    void ownerFileOverlaysOnlyItsKeys(@TempDir Path tmp) throws Exception {
        // Capture the lower layer LIVE before the owner file goes on, so "unset key falls back" is asserted
        // against whatever the asset ships rather than a number retyped here that a retune would invalidate.
        MobScalingConfig cfg = freshDefaults();
        double shippedRaritySpawnChance = cfg.getRaritySpawnChance();
        int shippedRegionSizeChunks = cfg.getRegionSizeChunks();

        Path configFile = tmp.resolve("mob-scaling.json");
        // A PARTIAL owner file (PascalCase codec shape): flips Enabled + the floor, leaves everything else default.
        Files.writeString(configFile, "{\n  \"Enabled\": false,\n  \"Difficulty\": { \"Floor\": 12.0 }\n}\n");

        cfg.setConfigPath(configFile);
        cfg.load();

        assertFalse(cfg.isEnabled(), "owner Enabled override applied");
        assertEquals(12.0, cfg.getDifficultyFloor(), 1e-9, "owner Difficulty.Floor override applied");
        // Unset owner keys fall back to the codec default, NOT a neutral zero.
        assertEquals(shippedRaritySpawnChance, cfg.getRaritySpawnChance(), 1e-9, "unset key falls back to codec default");
        assertEquals(shippedRegionSizeChunks, cfg.getRegionSizeChunks(), "unset key falls back to codec default");
        assertFalse(MobScalingGate.shouldRegisterSystems(cfg), "disabled owner config should not register");
    }

    @Test
    void partiallyFilledNestedGroupFoldsPerLeaf(@TempDir Path tmp) throws Exception {
        Path configFile = tmp.resolve("mob-scaling.json");
        // Nested groups may be PARTIALLY filled: only the set LEAF overrides; sibling leaves in the
        // same group (and the doubly-nested escalation) keep their codec defaults.
        Files.writeString(configFile, """
                {
                  "OpenWorld": { "RegionSizeChunks": 5 },
                  "Difficulty": {
                    "MaxCap": 150.0,
                    "DistanceEscalation": { "MaxBonus": 40.0 },
                    "StatCurve": { "EffectiveHpPerPoint": 0.2 },
                    "Clamps": { "MaxLootMult": 9.0 }
                  },
                  "ZoneHud": { "Enabled": false }
                }
                """);

        MobScalingConfig cfg = MobScalingConfig.getInstance();
        // Read the shipped sibling leaves from a defaults-only load FIRST, so every "sibling leaf keeps its
        // default" assertion below compares against whatever the asset actually ships rather than restating
        // the number here. Restating it makes a balance retune look like a test failure, which is exactly
        // what this file must not do.
        cfg.setConfigPath(tmp.resolve("absent.json"));
        cfg.load();
        double shippedGroupDeltaBandWidth = cfg.getGroupDeltaBandWidth();
        double shippedMinCap = cfg.getDifficultyMinCap();
        double shippedEscalationStart = cfg.getEscalationStartDistanceBlocks();
        double shippedMaxEffectiveHpMult = cfg.getStatCurveMaxEffectiveHpMult();
        double shippedMinLootMult = cfg.getClampMinLootMult();

        cfg.setConfigPath(configFile);
        cfg.load();

        assertEquals(5, cfg.getRegionSizeChunks(), "owner nested leaf applied");
        assertEquals("AVERAGE", cfg.getOpenWorldAggregationMode(), "sibling leaf in the same group stays default");
        assertEquals(shippedGroupDeltaBandWidth, cfg.getGroupDeltaBandWidth(), 1e-9,
                "sibling leaf in the same group stays default");
        assertEquals(150.0, cfg.getDifficultyMaxCap(), 1e-9, "owner nested cap applied");
        assertEquals(shippedMinCap, cfg.getDifficultyMinCap(), 1e-9, "sibling cap stays default");
        assertEquals(40.0, cfg.getEscalationMaxBonus(), 1e-9, "doubly-nested owner leaf applied");
        assertEquals(shippedEscalationStart, cfg.getEscalationStartDistanceBlocks(), 1e-9,
                "doubly-nested sibling leaf stays default");
        // Doubly-nested StatCurve + Clamps: the owner sets one leaf in each; sibling leaves keep the Default.
        assertEquals(0.2, cfg.getStatCurveEffectiveHpPerPoint(), 1e-9, "doubly-nested StatCurve owner leaf applied");
        assertEquals(shippedMaxEffectiveHpMult, cfg.getStatCurveMaxEffectiveHpMult(), 1e-9,
                "StatCurve sibling leaf stays default");
        assertEquals(9.0, cfg.getClampMaxLootMult(), 1e-9, "doubly-nested Clamps owner leaf applied");
        assertEquals(shippedMinLootMult, cfg.getClampMinLootMult(), 1e-9, "Clamps sibling leaf stays default");
        assertFalse(cfg.isZoneHudEnabled(), "owner ZoneHud.Enabled applied");
        assertEquals("TOP_LEFT", cfg.getZoneHudPosition(), "ZoneHud.Position stays default");
        assertTrue(cfg.isInspectorHudEnabled(), "untouched group stays default");
    }

    @Test
    void playerScalingRingAndEscalationStartAreIndependentLeaves(@TempDir Path tmp) throws Exception {
        Path configFile = tmp.resolve("mob-scaling.json");
        // The player-scaling protected ring and the distance-escalation start radius are separate knobs:
        // moving one must never move the other (they were one field, which silently disabled group scaling).
        Files.writeString(configFile, """
                {
                  "OpenWorld": { "PlayerScalingStartRingBlocks": 0.0 },
                  "Difficulty": { "DistanceEscalation": { "StartDistanceBlocks": 1234.0 } }
                }
                """);

        MobScalingConfig cfg = MobScalingConfig.getInstance();
        cfg.setConfigPath(configFile);
        cfg.load();

        assertEquals(0.0, cfg.getPlayerScalingStartRingBlocks(), 1e-9, "owner ring override applied");
        assertEquals(1234.0, cfg.getEscalationStartDistanceBlocks(), 1e-9,
                "the escalation start radius is its own leaf, untouched by the ring");
    }

    @Test
    void escalationOriginIsUnsetByDefaultAndFoldsPerAxis(@TempDir Path tmp) throws Exception {
        // UNSET at every layer folds to null on both axes: the resolver then reads the world spawn point, which
        // is the behaviour every existing server has. There is no fail-safe number to fall into here.
        MobScalingConfig cfg = freshDefaults();
        assertNull(cfg.getEscalationOriginX(), "no layer authors an origin X: null, the spawn point");
        assertNull(cfg.getEscalationOriginZ(), "no layer authors an origin Z: null, the spawn point");

        // An owner may pin ONE axis: the other stays null (per-leaf fold, like every nested group here).
        Path configFile = tmp.resolve("mob-scaling.json");
        Files.writeString(configFile, """
                { "Difficulty": { "DistanceEscalation": { "Origin": { "Z": -250.0 } } } }
                """);
        cfg.setConfigPath(configFile);
        cfg.load();
        assertNull(cfg.getEscalationOriginX(), "an unauthored axis stays unset (the spawn point on that axis)");
        assertEquals(-250.0, cfg.getEscalationOriginZ(), 1e-9, "a negative coordinate is a legal origin");

        // Both axes, and the sibling escalation leaves are untouched by the nested group.
        double shippedStart = cfg.getEscalationStartDistanceBlocks();
        Files.writeString(configFile, """
                { "Difficulty": { "DistanceEscalation": { "Origin": { "X": 1200.5, "Z": -250.0 } } } }
                """);
        cfg.load();
        assertEquals(1200.5, cfg.getEscalationOriginX(), 1e-9, "owner origin X applied");
        assertEquals(-250.0, cfg.getEscalationOriginZ(), 1e-9, "owner origin Z applied");
        assertEquals(shippedStart, cfg.getEscalationStartDistanceBlocks(), 1e-9,
                "the Origin group is nested INSIDE DistanceEscalation and touches no sibling leaf");

        // Removing the group again (the admin page blanks a field -> the leaf is removed) returns to the spawn point.
        Files.writeString(configFile, "{ }");
        cfg.load();
        assertNull(cfg.getEscalationOriginX(), "cleared: back to the spawn point");
        assertNull(cfg.getEscalationOriginZ(), "cleared: back to the spawn point");
    }

    @Test
    void perWorldEscalationOriginOverlaysPerAxis(@TempDir Path tmp) throws Exception {
        MobScalingConfig cfg = freshDefaults();
        // A world file pins X alone: its X applies, its Z falls through to the global, which is unset.
        ownerWorld(tmp, "frontier", """
                { "Where": { "Match": ["frontier_*"] },
                  "Difficulty": { "DistanceEscalation": { "Origin": { "X": -4000.0 } } } }
                """);
        SpawnScalingSettings frontier = cfg.spawnSettingsFor("frontier_1");
        assertEquals(-4000.0, frontier.getEscalationOriginX(), 1e-9, "the world's own origin X");
        assertNull(frontier.getEscalationOriginZ(), "unset in the world AND globally: the spawn point on Z");
        assertNull(cfg.getEscalationOriginX(), "the world file never leaks into the global view");

        // A global Z now exists: the world inherits it on the axis it left unset, keeps its own X.
        Path configFile = tmp.resolve("mob-scaling.json");
        Files.writeString(configFile, """
                { "Difficulty": { "DistanceEscalation": { "Origin": { "Z": 900.0 } } } }
                """);
        cfg.setConfigPath(configFile);
        cfg.load();
        frontier = cfg.spawnSettingsFor("frontier_1");
        assertEquals(-4000.0, frontier.getEscalationOriginX(), 1e-9, "the world's X still wins");
        assertEquals(900.0, frontier.getEscalationOriginZ(), 1e-9, "the unset world axis reads the global");
        // A world matching no rule reads the global view itself.
        assertNull(cfg.spawnSettingsFor("world").getEscalationOriginX(), "global X: unset");
        assertEquals(900.0, cfg.spawnSettingsFor("world").getEscalationOriginZ(), 1e-9, "global Z");
    }

    @Test
    void perWorldPlayerScalingRingOverlaysTheGlobal(@TempDir Path tmp) throws Exception {
        MobScalingConfig cfg = freshDefaults();
        ownerWorld(tmp, "ringed", """
                {
                    "Where": { "Match": ["ringed_*"] },
                    "OpenWorld": { "PlayerScalingStartRingBlocks": 0.0 }
                }
                """);

        assertEquals(0.0, cfg.spawnSettingsFor("ringed_1").getPlayerScalingStartRingBlocks(), 1e-9,
                "the per-world OpenWorld leaf overlays the global ring");
        assertEquals(cfg.getPlayerScalingStartRingBlocks(),
                cfg.spawnSettingsFor("elsewhere").getPlayerScalingStartRingBlocks(), 1e-9,
                "an unmatched world keeps the global ring");
    }

    @Test
    void perWorldAggregationModeAndRegionSizeOverlayTheGlobal(@TempDir Path tmp) throws Exception {
        // Both leaves are read off the per-world view by the presence tick, which declares them to the
        // region tracker, so a world file's own fold mode and grid size are what its buckets use.
        MobScalingConfig cfg = freshDefaults();
        int globalRegionSize = cfg.getRegionSizeChunks();
        String globalMode = cfg.getOpenWorldAggregationMode();
        ownerWorld(tmp, "raid", """
                {
                    "Where": { "Match": ["raid_*"] },
                    "OpenWorld": { "AggregationMode": "PEAK", "RegionSizeChunks": 7 }
                }
                """);

        SpawnScalingSettings raid = cfg.spawnSettingsFor("raid_1");
        assertEquals("PEAK", raid.getOpenWorldAggregationMode(), "the per-world OpenWorld.AggregationMode applies");
        assertEquals(7, raid.getRegionSizeChunks(), "the per-world OpenWorld.RegionSizeChunks applies");
        SpawnScalingSettings elsewhere = cfg.spawnSettingsFor("elsewhere");
        assertEquals(globalMode, elsewhere.getOpenWorldAggregationMode(), "an unmatched world keeps the global mode");
        assertEquals(globalRegionSize, elsewhere.getRegionSizeChunks(), "an unmatched world keeps the global grid size");
    }

    @Test
    void perWorldHudLeavesOverlayTheGlobalPerLeaf(@TempDir Path tmp) throws Exception {
        // Every HUD leaf is per-world: the authored ones apply, the unauthored ones (InspectorHud.Enabled,
        // the inspector offsets) inherit the global. A blank position falls through too (it is not a corner).
        MobScalingConfig cfg = freshDefaults();
        ownerWorld(tmp, "instance", """
                {
                    "Where": { "Match": ["instance_*"] },
                    "ZoneHud": { "Position": "BOTTOM_RIGHT", "OffsetX": 4, "OffsetY": 8, "ShowLocationName": false,
                                 "ZoneNameKeyPrefix": "my.zone.", "BiomeNameKeyPrefix": "" },
                    "InspectorHud": { "Position": "", "RangeBlocks": 20.0, "PortraitEnabled": false }
                }
                """);

        SpawnScalingSettings view = cfg.spawnSettingsFor("instance_7");
        assertEquals("BOTTOM_RIGHT", view.getZoneHudPosition(), "world ZoneHud.Position applied");
        assertEquals(4, view.getZoneHudOffsetX(), "world ZoneHud.OffsetX applied");
        assertEquals(8, view.getZoneHudOffsetY(), "world ZoneHud.OffsetY applied");
        assertFalse(view.isZoneShowLocationName(), "world ZoneHud.ShowLocationName applied");
        assertEquals("my.zone.", view.getZoneNameKeyPrefix(), "world ZoneHud.ZoneNameKeyPrefix applied");
        assertEquals("", view.getBiomeNameKeyPrefix(), "an authored EMPTY prefix is a value (no biome line), not an absence");
        assertEquals(cfg.isZoneHudEnabled(), view.isZoneHudEnabled(), "unauthored ZoneHud.Enabled inherits the global");
        assertEquals(cfg.getInspectorHudPosition(), view.getInspectorHudPosition(),
                "a blank InspectorHud.Position is not a corner and falls through to the global");
        assertEquals(cfg.getInspectorHudOffsetX(), view.getInspectorHudOffsetX(), "unauthored inspector offset inherits");
        assertEquals(20.0, view.getInspectorRangeBlocks(), 1e-9, "world InspectorHud.RangeBlocks applied");
        assertFalse(view.isInspectorPortraitEnabled(), "world InspectorHud.PortraitEnabled applied");
        assertEquals(cfg.isInspectorHudEnabled(), view.isInspectorHudEnabled(), "unauthored InspectorHud.Enabled inherits");
        assertEquals(cfg.getZoneHudPosition(), cfg.spawnSettingsFor("elsewhere").getZoneHudPosition(),
                "an unmatched world keeps the global corner");
    }

    @Test
    void applyStoreLayerFoldsTheDecodedAssetOverOwner() throws Exception {
        // The async store layer in production is the engine-folded jar Default.json. Capture the jar layer's
        // live values first, decode that same bundled codec asset as the store, and confirm applyStoreLayer
        // yields them (no owner) rather than the fail-safes the store path could fall to.
        MobScalingConfig cfg = freshDefaults();
        double jarRaritySpawnChance = cfg.getRaritySpawnChance();
        int jarRegionSizeChunks = cfg.getRegionSizeChunks();

        MobScalingSettingsAsset store = shippedAsset();
        cfg.setConfigPath(null); // no owner overlay
        cfg.applyStoreLayer(store);

        assertTrue(cfg.isEnabled(), "store-layer Enabled");
        assertEquals(jarRaritySpawnChance, cfg.getRaritySpawnChance(), 1e-9, "store-layer RaritySpawnChance");
        assertEquals(jarRegionSizeChunks, cfg.getRegionSizeChunks(), "store-layer RegionSizeChunks");
    }

    // ---------------------------------------------------------------------
    // The curve + clamps models and the per-world overlay (Worlds/*.json files)
    // ---------------------------------------------------------------------

    @Test
    void theCurveAndClampsModelsCarryTheFoldedLeaves() {
        MobScalingConfig cfg = freshDefaults();
        MobScaleFold.DifficultyStatCurve curve = cfg.statCurveModel();
        MobScaleFold.Clamps clamps = cfg.clampsModel();
        // The RELATIONSHIP: the records the fold reads are built from exactly the leaves the getters expose,
        // whatever the asset ships.
        assertEquals(cfg.getStatCurveEffectiveHpPerPoint(), curve.effectiveHpPerPoint(), 1e-9);
        assertEquals(cfg.getStatCurveVisibleHpShare(), curve.visibleHpShare(), 1e-9);
        assertEquals(cfg.getStatCurveOutDamageScale(), curve.outDamageScale(), 1e-9);
        assertEquals(cfg.getStatCurveOutDamageShape(), curve.outDamageShape(), 1e-9);
        assertEquals(cfg.getStatCurveMaxEffectiveHpMult(), curve.maxEffectiveHpMult(), 1e-9);
        assertEquals(cfg.getStatCurveMaxOutDamageMult(), curve.maxOutDamageMult(), 1e-9);
        assertEquals(cfg.getClampMinHpMult(), clamps.minHpMult(), 1e-9);
        assertEquals(cfg.getClampMaxInDamageMult(), clamps.maxInDamageMult(), 1e-9);
        assertEquals(cfg.getClampMinOutDamageMult(), clamps.minOutDamageMult(), 1e-9);
        assertEquals(cfg.getClampMinLootMult(), clamps.minLootMult(), 1e-9);
        assertEquals(cfg.getClampMaxLootMult(), clamps.maxLootMult(), 1e-9);
    }

    @Test
    void buildCurveAndBuildClampsApplyTheOneSetOfSanityClamps() {
        // A share outside [0, 1], a ceiling under 1, a negative slope, a non-positive shape: every layer folds
        // them the same way.
        MobScaleFold.DifficultyStatCurve curve = MobScalingConfig.buildCurve(-0.5, 1.7, -1.0, 0.0, 0.2, 0.0);
        assertEquals(0.0, curve.effectiveHpPerPoint(), 1e-12, "a negative slope folds to flat");
        assertEquals(1.0, curve.visibleHpShare(), 1e-12, "a share above 1 folds to all-visible");
        assertEquals(0.0, curve.outDamageScale(), 1e-12);
        assertEquals(1.0, curve.outDamageShape(), 1e-12, "a non-positive shape is not a curve and reads as the straight line");
        assertEquals(1.4, MobScalingConfig.buildCurve(0.1, 0.5, 0.1, 1.4, 2.0, 2.0).outDamageShape(), 1e-12,
                "a positive shape passes through untouched");
        assertEquals(1.0, curve.maxEffectiveHpMult(), 1e-12, "a rail under 1 folds to 1");
        assertEquals(1.0, curve.maxOutDamageMult(), 1e-12);
        MobScaleFold.Clamps clamps = MobScalingConfig.buildClamps(-1.0, 0.0, -2.0, 3.0, 1.0);
        assertEquals(0.0, clamps.minHpMult(), 1e-12, "a negative floor folds to 0");
        assertEquals(MobScaleFold.Clamps.NONE.maxInDamageMult(), clamps.maxInDamageMult(), 0.0,
                "a non-positive incoming ceiling folds to no ceiling rather than a mob that takes no damage");
        assertEquals(0.0, clamps.minOutDamageMult(), 1e-12);
        assertEquals(3.0, clamps.minLootMult(), 1e-12);
        assertEquals(3.0, clamps.maxLootMult(), 1e-12, "an inverted loot band collapses onto its floor");
    }

    @Test
    void aWorldFileWithoutACurveInheritsTheGlobalCurveAndClampsLive(@TempDir Path tmp) throws Exception {
        MobScalingConfig cfg = freshDefaults();
        ownerWorld(tmp, "arena", "{ \"Where\": { \"Match\": [\"arena_*\"] } }");
        assertEquals(cfg.statCurveModel(), cfg.spawnSettingsFor("arena_1").statCurveModel(),
                "a world file authoring no StatCurve leaf resolves the global curve leaf by leaf");
        assertEquals(cfg.clampsModel(), cfg.spawnSettingsFor("arena_1").clampsModel(),
                "and the global clamps");

        // A global owner edit re-resolves the cached per-world view (the cache clears on refold).
        Path configFile = tmp.resolve("mob-scaling.json");
        Files.writeString(configFile, "{ \"Difficulty\": { \"StatCurve\": { \"OutDamageScale\": 0.9 } } }");
        cfg.setConfigPath(configFile);
        cfg.load();
        assertEquals(0.9, cfg.spawnSettingsFor("arena_1").statCurveModel().outDamageScale(), 1e-9,
                "the per-world view follows the refolded global");
    }

    @Test
    void shippedDungeonWorldsDecodeToTheirFlatPolicies() {
        MobScalingConfig cfg = freshDefaults();
        loadJarWorlds(); // the jar Worlds/*.json files (flat, self-contained; no shared Parent)

        // Dungeon of Fear I and II simply turn open-world scaling OFF in their instances. The world names
        // are the engine's own instance format, instance-<name>-<uuid>, which the shipped patterns target.
        assertFalse(cfg.spawnSettingsFor("instance-dungeon_of_fear_i-9f3a").isWorldScalingEnabled(),
                "Dungeon of Fear I turns scaling off");
        assertFalse(cfg.spawnSettingsFor("instance-dungeon_of_fear_ii-9f3a").isWorldScalingEnabled(),
                "Dungeon of Fear II turns scaling off");

        // Dungeon of Fear III keeps scaling ON (player scaling on, global default) but with distance escalation off.
        SpawnScalingSettings iii = cfg.spawnSettingsFor("instance-dungeon_of_fear_iii-9f3a");
        assertTrue(iii.isWorldScalingEnabled(), "Dungeon of Fear III keeps scaling on");
        assertTrue(iii.isPlayerScalingEnabled(), "Dungeon of Fear III keeps player scaling on (global default)");
        assertFalse(iii.isDistanceEscalationEnabled(), "Dungeon of Fear III turns distance escalation off");

        // The Kweebec Nightmare world file is the per-world kill-switch (absorbed from hyMMO WorldRules).
        assertFalse(cfg.spawnSettingsFor("KweebecNightmare_run7").isWorldScalingEnabled(),
                "the Kweebec world file turns scaling off there");

        // A non-dungeon world matches nothing -> the global config itself (player scaling on, escalation on).
        SpawnScalingSettings overworld = cfg.spawnSettingsFor("world");
        assertTrue(overworld.isPlayerScalingEnabled(), "the overworld keeps global player scaling");
        assertTrue(overworld.isDistanceEscalationEnabled(), "the overworld keeps global escalation");
        assertTrue(overworld == cfg, "no-match returns the global config itself (zero-alloc)");
    }

    @Test
    void theDelimitedCoresKeepTheThreeDungeonsApart() {
        MobScalingConfig cfg = freshDefaults();
        loadJarWorlds();
        // Each tier's instance world (instance-<name>-<uuid>) resolves to its OWN entry and to nothing else:
        // the '-' the engine puts after the instance name closes each core, so the _i rule's core is not the
        // start of the _ii rule's core and no rule shadows another. I and II turn scaling off; III keeps it on,
        // so a _iii mis-resolved to the _i or _ii rule would read scaling OFF.
        assertFalse(cfg.spawnSettingsFor("instance-dungeon_of_fear_i-ab12").isWorldScalingEnabled(),
                "the _i instance matches the _i entry (scaling off)");
        assertFalse(cfg.spawnSettingsFor("instance-dungeon_of_fear_ii-ab12").isWorldScalingEnabled(),
                "the _ii instance matches the _ii entry (scaling off)");
        assertTrue(cfg.spawnSettingsFor("instance-dungeon_of_fear_iii-ab12").isWorldScalingEnabled(),
                "the _iii instance matches the _iii entry (scaling ON), not the shorter _i / _ii");
        assertTrue(cfg.spawnSettingsFor("instance-dungeon_of_fear_iii-ab12") != cfg.spawnSettingsFor("instance-dungeon_of_fear_i-ab12"),
                "the three rules resolve to three distinct views");
        assertTrue(cfg.spawnSettingsFor("instance-dungeon_of_fear_iv-ab12") == cfg,
                "a name that only begins like a rule's core matches no rule at all (the global config answers)");
    }

    @Test
    void perWorldFileInheritsUnsetLeavesAndOverridesSetOnes(@TempDir Path tmp) throws Exception {
        MobScalingConfig cfg = freshDefaults();
        // The GLOBAL layer, captured live before the world file goes on: an unset per-world leaf must read
        // exactly this, whatever the asset ships.
        double globalMaxCap = cfg.getDifficultyMaxCap();
        double globalEhpSlope = cfg.getStatCurveEffectiveHpPerPoint();
        double globalOutShape = cfg.getStatCurveOutDamageShape();
        double globalMaxLoot = cfg.getClampMaxLootMult();
        ownerWorld(tmp, "raid", """
                {
                    "Where": { "Match": ["raid_*"] },
                    "Enabled": true,
                    "RaritySpawnChance": 0.5,
                    "OpenWorld": { "PlayerScalingEnabled": false },
                    "Difficulty": { "Floor": 55.0, "MinCap": 40.0,
                                    "StatCurve": { "OutDamageScale": 0.2 },
                                    "Clamps": { "MinLootMult": 0.25 } },
                    "Pool": { "Rarities": { "Deny": ["legendary"] },
                              "Variants": { "ChanceMultiplier": 2.0 },
                              "Affixes": { "ExtraSlots": 1 } }
                }
                """);

        SpawnScalingSettings raid = cfg.spawnSettingsFor("raid_alpha");
        assertEquals(0.5, raid.getRaritySpawnChance(), 1e-9, "world RaritySpawnChance applied");
        assertFalse(raid.isPlayerScalingEnabled(), "world OpenWorld.PlayerScalingEnabled applied");
        assertEquals(55.0, raid.getDifficultyFloor(), 1e-9, "world Difficulty.Floor applied");
        assertEquals(40.0, raid.getDifficultyMinCap(), 1e-9, "world Difficulty.MinCap applied");
        assertEquals(globalMaxCap, raid.getDifficultyMaxCap(), 1e-9, "unset MaxCap inherits the global");
        assertTrue(raid.isWorldScalingEnabled(), "world Enabled=true keeps scaling on");
        // The per-world Pool gates + dials (1.0.2).
        assertFalse(raid.isRarityAllowed("Legendary"), "denied rarity is gated out (case-insensitive)");
        assertTrue(raid.isRarityAllowed("rare"), "an unlisted rarity still rolls (deny-list only)");
        assertEquals(2.0, raid.getVariantChanceMultiplier(), 1e-9, "world variant chance multiplier");
        assertEquals(1, raid.getExtraAffixSlots(), "world extra affix slots");
        // StatCurve: OutDamageScale overridden; EffectiveHpPerPoint and OutDamageShape inherit the global
        // (captured above). Clamps: MinLootMult overridden; MaxLootMult inherits the global.
        MobScaleFold.DifficultyStatCurve curve = raid.statCurveModel();
        assertEquals(0.2, curve.outDamageScale(), 1e-9, "the world's own scale");
        assertEquals(globalEhpSlope, curve.effectiveHpPerPoint(), 1e-9, "the inherited slope");
        assertEquals(globalOutShape, curve.outDamageShape(), 1e-9, "the inherited shape");
        assertEquals(0.25, raid.clampsModel().minLootMult(), 1e-9, "the world's own clamp leaf");
        assertEquals(globalMaxLoot, raid.clampsModel().maxLootMult(), 1e-9, "the inherited clamp leaf");
        // A non-matching world is untouched (global: allow-all pool, neutral dials).
        assertTrue(cfg.spawnSettingsFor("world") == cfg, "non-match returns the global config");
        assertTrue(cfg.isRarityAllowed("legendary"), "the global view has no pool gate");
    }

    @Test
    void ownerWorldFileReplacesTheShippedFileByIdAndAddsNewOnes(@TempDir Path tmp) throws Exception {
        MobScalingConfig cfg = freshDefaults();
        loadJarWorlds();
        // The owner ADDS a new world file AND replaces one shipped dungeon file BY ID (the owner file
        // stem matches the jar file stem case-insensitively; layering is id-replace, not per-leaf merge).
        Path dir = tmp.resolve("worlds");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("myworld.json"),
                "{ \"Where\": { \"Match\": [\"myworld_*\"] }, \"OpenWorld\": { \"PlayerScalingEnabled\": false } }");
        Files.writeString(dir.resolve("dungeonoffear_iii.json"),
                "{ \"Where\": { \"Match\": [\"instance-dungeon_of_fear_iii*\"] }, \"OpenWorld\": { \"PlayerScalingEnabled\": false } }");
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        worlds.setOwnerDir(dir);
        worlds.refold();

        // Owner's new world takes effect.
        assertFalse(cfg.spawnSettingsFor("myworld_1").isPlayerScalingEnabled(), "owner's new world file applies");
        // The shipped dungeon files NOT touched by the owner still resolve.
        assertFalse(cfg.spawnSettingsFor("instance-dungeon_of_fear_i-9f3a").isWorldScalingEnabled(),
                "shipped _i file survives an owner adding other files (still scaling-off)");
        // Owner's same-id file REPLACES the shipped _iii wholesale: player scaling now off there, and the
        // shipped file's escalation-off policy is GONE because the owner body does not carry it.
        assertFalse(cfg.spawnSettingsFor("instance-dungeon_of_fear_iii").isPlayerScalingEnabled(),
                "owner same-id file beats the shipped _iii file");
        assertTrue(cfg.spawnSettingsFor("instance-dungeon_of_fear_iii").isDistanceEscalationEnabled(),
                "the replace is WHOLESALE: the shipped file's escalation-off does not leak into the owner body");
    }

    @Test
    void legacyInlineWorldOverridesMigrateToOwnerWorldFiles(@TempDir Path tmp) throws Exception {
        // The shipped slopes, captured from a defaults-only load: an Intensity is REPORTED and never applied,
        // so both files must still fold to exactly these afterwards.
        MobScalingConfig cfg = freshDefaults();
        double shippedEhpSlope = cfg.getStatCurveEffectiveHpPerPoint();
        double shippedOutScale = cfg.getStatCurveOutDamageScale();

        // A SHIPPED-1.0.1 owner file: inline WorldOverrides (top-level PlayerScalingEnabled included).
        Path configFile = tmp.resolve("mob-scaling.json");
        Files.writeString(configFile, """
                { "Intensity": 2.0, "WorldOverrides": [
                    { "Match": "arena_*", "Intensity": 3.0, "PlayerScalingEnabled": false }
                ] }
                """);
        cfg.setConfigPath(configFile);
        cfg.load();
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        worlds.setOwnerDir(tmp.resolve("worlds"));
        assertTrue(worlds.migrateLegacyOwnerOverrides(configFile), "a legacy array triggers the migration");
        worlds.refold();
        cfg.refreshFromDisk();

        // The entry became its own file with the 1.0.2 schema (PlayerScalingEnabled under OpenWorld).
        assertTrue(Files.exists(tmp.resolve("worlds").resolve("arena.json")),
                "the sanitized match (wildcard + trailing separators dropped) becomes the file stem");
        SpawnScalingSettings arena = cfg.spawnSettingsFor("arena_1");
        assertFalse(arena.isPlayerScalingEnabled(), "migrated toggle moved under OpenWorld");
        // The owner file keeps its other keys but the array is stripped; a second boot is a no-op.
        String body = Files.readString(configFile, StandardCharsets.UTF_8);
        assertTrue(body.contains("\"Intensity\": 2.0"), "sibling owner keys survive the strip: " + body);
        assertFalse(body.contains("WorldOverrides"), "the legacy array is stripped: " + body);
        assertFalse(worlds.migrateLegacyOwnerOverrides(configFile), "idempotent: nothing left to migrate");

        // The Intensity each file carried is REPORTED, one notice per file, and nothing is applied or
        // rewritten: the world file's 3.0 and the owner file's 2.0 are both named, both files still carry
        // the key, and both layers fold to the plain shipped slopes.
        Path arenaFile = tmp.resolve("worlds").resolve("arena.json");
        String arenaBefore = Files.readString(arenaFile, StandardCharsets.UTF_8);
        String ownerBefore = Files.readString(configFile, StandardCharsets.UTF_8);
        List<String> notices = LegacyIntensityReport.run(List.of());
        assertEquals(2, notices.size(), "one notice per file carrying an Intensity: " + notices);
        assertTrue(notices.get(0).contains("Intensity 2.0") && notices.get(0).contains(configFile.toString()),
                "the owner file's notice names the file and the value: " + notices.get(0));
        assertTrue(notices.get(1).contains("Intensity 3.0") && notices.get(1).contains(arenaFile.toString()),
                "the world file's notice names the file and the value: " + notices.get(1));
        assertEquals(ownerBefore, Files.readString(configFile, StandardCharsets.UTF_8), "the owner file is untouched");
        assertEquals(arenaBefore, Files.readString(arenaFile, StandardCharsets.UTF_8), "the world file is untouched");
        SpawnScalingSettings reportedArena = cfg.spawnSettingsFor("arena_1");
        assertEquals(shippedEhpSlope, reportedArena.statCurveModel().effectiveHpPerPoint(), 1e-9,
                "the world's Intensity is not applied to the slope it inherits");
        assertEquals(shippedOutScale, reportedArena.statCurveModel().outDamageScale(), 1e-9);
        assertEquals(shippedEhpSlope, cfg.getStatCurveEffectiveHpPerPoint(), 1e-9,
                "the owner file's Intensity is not applied either");
        assertEquals(notices, LegacyIntensityReport.run(List.of()),
                "the report says the same thing at every boot until the owner acts");
    }


    @Test
    void firstRunScaffoldsTheOwnerFileAndSchemaReferenceWithoutClobberingEdits(@TempDir Path tmp)
            throws Exception {
        // Structure only (does the scaffold exist, is it idempotent) - never any shipped default VALUE.
        Path owner = tmp.resolve("MmoMobScaling").resolve("mob-scaling.json");
        Path reference = owner.getParent().resolve("_reference").resolve("defaults-mob-scaling.json");
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        try {
            cfg.setConfigPath(owner);
            cfg.load();

            assertTrue(Files.exists(owner), "first run seeds the owner override file");
            assertTrue(Files.exists(reference), "and writes the full schema beside it for copy-paste");

            // A hand-edited owner file must survive a second boot AND take effect.
            String edited = "{ \"Difficulty\": { \"Floor\": 3.0 } }";
            Files.writeString(owner, edited, StandardCharsets.UTF_8);
            cfg.load();
            assertEquals(edited, Files.readString(owner, StandardCharsets.UTF_8),
                    "the scaffold never clobbers an existing owner file");
            assertEquals(3.0, cfg.getDifficultyFloor(), 1e-9, "the hand-edited override folds over the defaults");
        } finally {
            cfg.setConfigPath(null);
            cfg.load();
        }
    }
}
