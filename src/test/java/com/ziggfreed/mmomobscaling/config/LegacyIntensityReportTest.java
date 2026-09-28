package com.ziggfreed.mmomobscaling.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The boot report over the retired {@code Intensity} multiplier and the retired curve leaves: every layer
 * that can carry one is found (the owner file, an owner world file, a pack world body no owner file
 * shadows, a pack's settings preset or override of {@code Default.json}), each gets ONE notice naming the
 * file, the value and the leaves to author instead, and NOTHING on disk or in the fold is changed. The
 * suggested damage-axis value is computed off the live shipped scale, never retyped.
 */
class LegacyIntensityReportTest {

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4g", v);
    }

    /** The owner file written and folded, the worlds dir created and adopted. */
    private static MobScalingConfig owning(Path tmp, String ownerJson) throws Exception {
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        Path owner = tmp.resolve("mob-scaling.json");
        Files.writeString(owner, ownerJson, StandardCharsets.UTF_8);
        cfg.setConfigPath(owner);
        cfg.load();
        Path worldsDir = tmp.resolve("worlds");
        Files.createDirectories(worldsDir);
        WorldSettingsConfig.getInstance().setOwnerDir(worldsDir);
        return cfg;
    }

    private static void world(Path tmp, String file, String json) throws Exception {
        Files.writeString(tmp.resolve("worlds").resolve(file), json, StandardCharsets.UTF_8);
        WorldSettingsConfig.getInstance().refold();
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @AfterEach
    void reset() {
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        worlds.setOwnerDir(null);
        worlds.applyPackLayer(Map.of());
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        cfg.setConfigPath(null);
        cfg.load();
    }

    @Test
    void anOwnerFileIntensityIsNamedWithTheLeavesToAuthorAndTheFileIsLeftAsItIs(@TempDir Path tmp) throws Exception {
        MobScalingConfig cfg = owning(tmp, """
                { "$Comment": "keep me", "Intensity": 2.0, "RaritySpawnChance": 0.3,
                  "Difficulty": { "StatCurve": { "HpPerPoint": 0.08, "MinInDamageMult": 0.5 } } }
                """);
        double shippedScale = cfg.getStatCurveOutDamageScale();
        double shippedEhp = cfg.getStatCurveEffectiveHpPerPoint();
        Path owner = tmp.resolve("mob-scaling.json");
        String before = Files.readString(owner, StandardCharsets.UTF_8);

        List<String> notices = LegacyIntensityReport.run(List.of());

        assertEquals(1, notices.size(), "ONE notice for the one file: " + notices);
        String n = notices.get(0);
        assertTrue(n.contains(owner.toString()), "names the file: " + n);
        assertTrue(n.contains("Intensity 2.0"), "names the value found: " + n);
        assertTrue(n.contains("left as it is"), "says nothing was rewritten: " + n);
        assertTrue(n.contains("EffectiveHpPerPoint") && n.contains("VisibleHpShare")
                && n.contains("OutDamageScale") && n.contains("OutDamageShape"), "names the replacement leaves: " + n);
        assertTrue(n.contains("OutDamageScale " + fmt(shippedScale * 2.0)),
                "the damage-axis starting point is the live scale times the Intensity: " + n);
        assertTrue(n.contains("suggestion and not a conversion"), "and it is marked as a suggestion: " + n);
        assertTrue(n.contains("HpPerPoint=0.08") && n.contains("MinInDamageMult=0.5"),
                "the retired leaves are named WITH their values so the owner keeps the numbers: " + n);

        assertEquals(before, Files.readString(owner, StandardCharsets.UTF_8), "the file is byte-for-byte untouched");
        assertEquals(shippedEhp, cfg.getStatCurveEffectiveHpPerPoint(), 1e-12, "no slope was multiplied");
        assertEquals(shippedScale, cfg.getStatCurveOutDamageScale(), 1e-12);
        assertEquals(0.3, cfg.getRaritySpawnChance(), 1e-12, "the file's live leaves still fold");
        assertEquals(notices, LegacyIntensityReport.run(List.of()), "the report repeats until the owner acts");
    }

    @Test
    void anOwnerWorldFileIsNamedByItsPathAndLeftAsItIs(@TempDir Path tmp) throws Exception {
        MobScalingConfig cfg = owning(tmp, "{ \"RaritySpawnChance\": 0.2 }");
        double shippedEhp = cfg.getStatCurveEffectiveHpPerPoint();
        world(tmp, "Arena.json", "{ \"Where\": { \"Match\": [\"arena_*\"] }, \"Intensity\": 3.0,"
                + " \"Difficulty\": { \"StatCurve\": { \"OutDamageScale\": 0.05, \"MaxHpMult\": 20.0 } } }");
        world(tmp, "plain.json", "{ \"Where\": { \"Match\": [\"plain_*\"] }, \"Difficulty\": { \"Floor\": 9.0 } }");
        Path arena = tmp.resolve("worlds").resolve("Arena.json");
        String before = Files.readString(arena, StandardCharsets.UTF_8);

        List<String> notices = LegacyIntensityReport.run(List.of());

        assertEquals(1, notices.size(), "the arena file only; the plain world and the owner file carry nothing: " + notices);
        String n = notices.get(0);
        assertTrue(n.contains(arena.toString()), "names the hand-cased file the id resolves to: " + n);
        assertTrue(n.contains("Intensity 3.0") && n.contains("MaxHpMult=20.0"), n);
        assertTrue(n.contains("OutDamageScale " + fmt(0.05 * 3.0)),
                "the suggestion is over the scale the world itself authors, times its Intensity: " + n);
        assertFalse(n.contains("jar or pack"), "an owner file is not told to copy itself: " + n);
        assertEquals(before, Files.readString(arena, StandardCharsets.UTF_8), "the world file is untouched");
        assertEquals(shippedEhp, cfg.spawnSettingsFor("arena_1").statCurveModel().effectiveHpPerPoint(), 1e-12,
                "the Intensity is not applied to the slope the world inherits");
        assertEquals(0.05, cfg.spawnSettingsFor("arena_1").statCurveModel().outDamageScale(), 1e-12,
                "nor to the scale it authors");
    }

    @Test
    void aPackWorldBodyIsNamedAsAPackFileUnlessAnOwnerFileShadowsIt(@TempDir Path tmp) throws Exception {
        MobScalingConfig cfg = owning(tmp, "{}");
        double shippedScale = cfg.getStatCurveOutDamageScale();
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        world(tmp, "shipped.json", "{ \"Where\": { \"Match\": [\"shipped_*\"] } }");
        worlds.applyPackLayer(Map.of(
                "Pack_Base", json("{ \"Intensity\": 1.5 }"),
                "Shipped", json("{ \"Where\": { \"Match\": [\"shipped_*\"] }, \"Intensity\": 4.0 }"),
                "Clean", json("{ \"Where\": { \"Match\": [\"clean_*\"] } }")));

        List<String> notices = LegacyIntensityReport.run(List.of());

        assertEquals(1, notices.size(), "the pack base only: the shadowed pack body is inert, the clean one carries nothing: "
                + notices);
        String n = notices.get(0);
        assertTrue(n.contains("world 'pack_base'") && n.contains("Intensity 1.5"), n);
        assertTrue(n.contains("jar or pack file") && n.contains("mods/MmoMobScaling/worlds/pack_base.json"),
                "a pack body is named as one, with the owner-copy route: " + n);
        assertTrue(n.contains("OutDamageScale " + fmt(shippedScale * 1.5)), n);
        assertFalse(Files.exists(tmp.resolve("worlds").resolve("pack_base.json")), "no owner file is conjured for it");
    }

    @Test
    void aPackSettingsPresetOrOverrideOfDefaultIsNamed() {
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        cfg.setConfigPath(null);
        cfg.load();
        double shippedShape = cfg.getStatCurveOutDamageShape();
        List<LegacyIntensityReport.PackSettingsFile> files = List.of(
                new LegacyIntensityReport.PackSettingsFile("My Pack", "Server/MmoMobScaling/Settings/Default.json",
                        json("{ \"Intensity\": 2.5, \"Difficulty\": { \"StatCurve\": { \"OutDamageScale\": 0.05 } } }")),
                new LegacyIntensityReport.PackSettingsFile("My Pack", "Server/MmoMobScaling/Settings/Brutal.json",
                        json("{ \"Difficulty\": { \"StatCurve\": { \"InDamageReductionPerPoint\": 0.002 } } }")),
                new LegacyIntensityReport.PackSettingsFile("My Pack", "Server/MmoMobScaling/Settings/Casual.json",
                        json("{ \"RaritySpawnChance\": 0.05 }")));

        List<String> notices = LegacyIntensityReport.settingsNotices(files, cfg);

        assertEquals(2, notices.size(), "the override of Default and the preset with a retired leaf; the clean preset is silent: "
                + notices);
        String override = notices.get(0);
        assertTrue(override.contains("pack 'My Pack' file Server/MmoMobScaling/Settings/Default.json"), override);
        assertTrue(override.contains("Intensity 2.5"), override);
        assertTrue(override.contains("OutDamageScale " + fmt(0.05 * 2.5)),
                "the suggestion is over the scale the preset itself authors: " + override);
        assertTrue(override.contains("OutDamageShape left at " + fmt(shippedShape)),
                "and the shape it inherits from the jar Default: " + override);
        assertTrue(override.contains("jar or pack file") && override.contains("mods/MmoMobScaling/mob-scaling.json"),
                "a pack settings file is named as one, with the owner-file route: " + override);
        String preset = notices.get(1);
        assertTrue(preset.contains("Settings/Brutal.json") && preset.contains("InDamageReductionPerPoint=0.002"), preset);
        assertFalse(preset.contains("Intensity "), "no Intensity, so none is named: " + preset);
    }

    @Test
    void inspectReadsOnlyWhatTheBodyAuthors() {
        assertTrue(LegacyIntensityReport.inspect(json("{ \"Enabled\": true, \"Difficulty\": { \"StatCurve\": {"
                + " \"OutDamageScale\": 0.3 } } }")).isEmpty(), "a body with nothing retired reports nothing");
        LegacyIntensityReport.Finding retired = LegacyIntensityReport.inspect(json(
                "{ \"Difficulty\": { \"StatCurve\": { \"InDamageReductionPerPoint\": 0.002, \"HpPerPoint\": 0.08 } } }"));
        assertNull(retired.intensity(), "no Intensity to name");
        assertEquals(List.of("HpPerPoint=0.08", "InDamageReductionPerPoint=0.002"), retired.retired(),
                "the retired leaves, in schema order, with their values");
        LegacyIntensityReport.Finding text = LegacyIntensityReport.inspect(json("{ \"Intensity\": \"high\" }"));
        assertFalse(text.isEmpty(), "a non-numeric Intensity is still the retired key sitting in the file");
        assertNull(text.intensityNumber(), "but it carries no number to build a starting point from");
        assertTrue(LegacyIntensityReport.inspect(json("{ \"Intensity\": null }")).isEmpty(),
                "an explicit JSON null is an absent key");
        assertEquals(-2.0, LegacyIntensityReport.inspect(json("{ \"Intensity\": -2.0 }")).intensityNumber(), 1e-12,
                "a negative value is reported as written");
    }

    @Test
    void aStringIntensityIsNamedAsTheRetiredKeyWithNoStartingPoint() {
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        cfg.setConfigPath(null);
        cfg.load();
        List<String> notices = LegacyIntensityReport.settingsNotices(List.of(
                new LegacyIntensityReport.PackSettingsFile("My Pack", "Server/MmoMobScaling/Settings/Default.json",
                        json("{ \"Intensity\": \"high\", \"Difficulty\": { \"StatCurve\": { \"MaxHpMult\": 20.0 } } }"))), cfg);

        assertEquals(1, notices.size(), notices.toString());
        String n = notices.get(0);
        assertTrue(n.contains("Intensity \"high\""), "names the value as written: " + n);
        assertTrue(n.contains("not a number"), "says why nothing can be suggested: " + n);
        assertTrue(n.contains("left as it is") && n.contains("Remove it"), n);
        assertFalse(n.contains("suggestion"), "no damage-axis starting point is conjured from a word: " + n);
        assertTrue(n.contains("It also authors") && n.contains("MaxHpMult=20.0"),
                "the retired leaves still ride the same notice: " + n);
        assertTrue(n.contains("jar or pack file") && n.contains("mods/MmoMobScaling/mob-scaling.json"),
                "a pack file keeps its owner-file route: " + n);
    }

    @Test
    void nothingRetiredAnywhereMeansNoNotice(@TempDir Path tmp) throws Exception {
        owning(tmp, "{ \"RaritySpawnChance\": 0.2, \"Difficulty\": { \"StatCurve\": { \"OutDamageShape\": 1.2 } } }");
        world(tmp, "arena.json", "{ \"Where\": { \"Match\": [\"arena_*\"] }, \"Difficulty\": { \"Floor\": 9.0 } }");
        WorldSettingsConfig.getInstance().applyPackLayer(Map.of("Clean", json("{ \"Where\": { \"Match\": [\"c_*\"] } }")));
        assertTrue(LegacyIntensityReport.run(List.of(new LegacyIntensityReport.PackSettingsFile("P",
                "Server/MmoMobScaling/Settings/Default.json", json("{ \"Enabled\": true }")))).isEmpty());
    }
}
