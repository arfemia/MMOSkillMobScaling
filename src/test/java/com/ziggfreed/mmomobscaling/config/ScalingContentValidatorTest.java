package com.ziggfreed.mmomobscaling.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset;
import com.ziggfreed.mmomobscaling.family.FamilyFilter;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.variant.Variant;

/** Exercises the pure value-sanity checks in {@link ScalingContentValidator}. */
class ScalingContentValidatorTest {

    @Test
    void cleanShippedShapesPass() {
        Rarity epic = new Rarity("epic", "", 25, 25, 2.2, 1.9, 0.7, 1.5, 1.3, 2, "aura", "drops", List.of("*"));
        Rarity boss = new Rarity("boss", "", 0, 0, 4.0, 2.2, 0.6, 3.0, 2.0, 2, "aura", "drops", List.of("*"));
        assertTrue(ScalingContentValidator.validateRarities(List.of(epic, boss)).isEmpty(),
                "the shipped ladder shapes (incl. the weight-0 force-only boss) are clean");

        Affix armored = new Affix("armored", "", "", "eff", 3, 5, List.of("*"), 0, 0, 0, 0, Affix.KIND_STAT, null, true);
        Affix vampiric = new Affix("vampiric", "", "", null, 2, 20, List.of("*"), 0, 0, 0, 0, Affix.KIND_BEHAVIORAL, "vampiric", false);
        assertTrue(ScalingContentValidator.validateAffixes(List.of(armored, vampiric)).isEmpty(),
                "shipped affix shapes are clean");
    }

    @Test
    void badRarityValuesAreFlagged() {
        Rarity bad = new Rarity("bad", "", -1, -5, 0.0, 0.0, 0.0, -1, -1, -1, null, null, List.of("*"));
        List<String> findings = ScalingContentValidator.validateRarities(List.of(bad));
        assertEquals(6, findings.size(), "weight, minDifficulty, hp, damage, loot/xp, slots all flagged: " + findings);
    }

    @Test
    void familyFilterSelfContradictionsAreFlagged() {
        // A deny "*" nukes everything -> the tier can never roll.
        Rarity denyAll = new Rarity("denyall", "", 25, 25, 1, 1, 1, 1, 1, 0, null, null, List.of("*"), "",
                new FamilyFilter(List.of(), List.of(), List.of(), List.of("*")));
        // Same id in AllowGroups + DenyGroups -> deny wins, the allow entry is dead.
        Rarity dead = new Rarity("dead", "", 25, 25, 1, 1, 1, 1, 1, 0, null, null, List.of("*"), "",
                new FamilyFilter(List.of("Spiders"), List.of("Spiders"), List.of(), List.of()));
        // A weight-0 (force-only) tier with the same contradiction is NOT flagged (it never rolls anyway).
        Rarity forced = new Rarity("forced", "", 0, 0, 1, 1, 1, 1, 1, 0, null, null, List.of("*"), "",
                new FamilyFilter(List.of(), List.of(), List.of(), List.of("*")));
        // A legitimate spider-only filter is clean.
        Rarity ok = new Rarity("ok", "", 25, 25, 1, 1, 1, 1, 1, 0, null, null, List.of("*"), "",
                new FamilyFilter(List.of("Spiders"), List.of(), List.of("Spider*"), List.of()));
        List<String> findings = ScalingContentValidator.validateRarities(List.of(denyAll, dead, forced, ok));
        assertEquals(2, findings.size(), "deny-all + dead-allow flagged; weight-0 + valid gate clean: " + findings);
    }

    @Test
    void malformedNameColorIsFlagged() {
        Rarity noHash = new Rarity("nohash", "", 1, 0, 1, 1, 1, 1, 1, 0, null, null, List.of("*"), "b388ff");
        Rarity word = new Rarity("word", "", 1, 0, 1, 1, 1, 1, 1, 0, null, null, List.of("*"), "purple");
        Rarity good = new Rarity("good", "", 1, 0, 1, 1, 1, 1, 1, 0, null, null, List.of("*"), "#B388FF");
        Rarity absent = new Rarity("absent", "", 1, 0, 1, 1, 1, 1, 1, 0, null, null, List.of("*"));
        List<String> findings = ScalingContentValidator.validateRarities(List.of(noHash, word, good, absent));
        assertEquals(2, findings.size(), "missing '#' and a colour word flagged; #rrggbb and absent clean: " + findings);
    }

    @Test
    void noOpAndUndispatchableAffixesAreFlagged() {
        Affix noOp = new Affix("noop", "", "", null, 1, 0, List.of("*"), 0, 0, 0, 0, Affix.KIND_STAT, null, false);
        Affix silent = new Affix("silent", "", "", "eff", 1, 0, List.of("*"), 0, 0, 0, 0, Affix.KIND_HYBRID, null, false);
        Affix weird = new Affix("weird", "", "", "eff", 1, 0, List.of("*"), 0, 0, 0, 0, "MAGICAL", null, false);
        List<String> findings = ScalingContentValidator.validateAffixes(List.of(noOp, silent, weird));
        assertEquals(3, findings.size(), "no-op STAT, BehaviorId-less HYBRID, unknown Kind all flagged: " + findings);
    }

    @Test
    void variantShapesValidated() {
        // A clean spider-only horrific variant.
        Variant ok = new Variant("horrific", "", 0.15, 20, 1.5, 1.4, 0.9, 1.3, 1.2, 1, List.of("venomous"),
                "#7cb342", new FamilyFilter(List.of("Spiders"), List.of(), List.of("Spider*"), List.of()));
        assertTrue(ScalingContentValidator.validateVariants(List.of(ok)).isEmpty(), "clean variant: " + ok);

        // Bad: chance > 1, negative mults, and a self-denying family filter (rollable, so it is checked).
        Variant bad = new Variant("bad", "", 1.5, -1, 0.0, -1, 1, 1, 1, -1, List.of("*"), "",
                new FamilyFilter(List.of(), List.of(), List.of(), List.of("*")));
        List<String> findings = ScalingContentValidator.validateVariants(List.of(bad));
        // chance, minDifficulty, hp, damage, slots, deny-all = 6 findings.
        assertEquals(6, findings.size(), "all bad variant shapes flagged: " + findings);
    }

    @Test
    void variantEmptyAllowedRaritiesFlagged() {
        // A rollable variant whose AllowedRarities is an explicit [] can never overlay any base -> dead.
        Variant dead = new Variant("dead", "", 0.15, 0, 1, 1, 1, 1, 1, 0, List.of("*"),
                List.of(), null, null, "", FamilyFilter.ALLOW_ALL);
        List<String> findings = ScalingContentValidator.validateVariants(List.of(dead));
        assertEquals(1, findings.size(), "empty AllowedRarities flagged: " + findings);
        assertTrue(findings.get(0).contains("AllowedRarities"), findings.toString());
    }

    @Test
    void difficultyCaps_crossCheckAgainstPowerClamp() {
        // Aligned scales (the shipped pairing: both 1..200) are clean.
        assertTrue(ScalingContentValidator.validateDifficultyCaps(1.0, 200.0, 1.0, 200.0).isEmpty(),
                "matching caps are clean");
        // Unreadable MMO clamp (null bounds) validates as clean - advisory only.
        assertTrue(ScalingContentValidator.validateDifficultyCaps(1.0, 200.0, null, null).isEmpty(),
                "null power bounds are clean");
        // A drifted max (MMO retuned to 120 without touching the mod) is flagged; so is a drifted min.
        assertEquals(1, ScalingContentValidator.validateDifficultyCaps(1.0, 200.0, 1.0, 120.0).size());
        assertEquals(1, ScalingContentValidator.validateDifficultyCaps(5.0, 200.0, 1.0, 200.0).size());
        assertEquals(2, ScalingContentValidator.validateDifficultyCaps(5.0, 150.0, 1.0, 200.0).size());
    }

    @Test
    void worldOverrideAndIntensityIssuesAreFlagged() throws Exception {
        // Negative global Intensity + a blank Match + rarity chance > 1 + a negative override Intensity +
        // inverted caps + a duplicate Match within this file = 6 findings (a cross-layer dup is legal).
        MobScalingSettingsAsset a = decodeSettings("""
                { "Intensity": -1.0, "WorldOverrides": [
                    { "Match": "", "RaritySpawnChance": 2.0 },
                    { "Match": "dup_*", "Intensity": -0.5, "Difficulty": { "MinCap": 100.0, "MaxCap": 50.0 } },
                    { "Match": "dup_*" }
                ] }
                """);
        List<String> findings = ScalingContentValidator.validateSettings("Test", a);
        assertEquals(6, findings.size(), "all 1.0.1 issues flagged: " + findings);
    }

    @Test
    void cleanWorldOverridesPass() throws Exception {
        MobScalingSettingsAsset a = decodeSettings("""
                { "Intensity": 2.0, "WorldOverrides": [
                    { "Match": "instance-dungeon_of_fear_i*", "PlayerScalingEnabled": false,
                      "Difficulty": { "MinCap": 40.0, "MaxCap": 120.0, "DistanceEscalation": { "Enabled": false } } } ] }
                """);
        assertTrue(ScalingContentValidator.validateSettings("Test", a).isEmpty(), "a clean overlay passes");
    }

    private static MobScalingSettingsAsset decodeSettings(String json) throws Exception {
        return MobScalingSettingsAsset.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }
}
