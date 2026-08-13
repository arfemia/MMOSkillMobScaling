package com.ziggfreed.mmomobscaling.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.loot.Roll;
import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset;
import com.ziggfreed.mmomobscaling.caster.CasterEntry;
import com.ziggfreed.mmomobscaling.caster.CasterRoster;
import com.ziggfreed.mmomobscaling.family.FamilyFilter;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.variant.Variant;

/** Exercises the pure value-sanity checks in {@link ScalingContentValidator}. */
class ScalingContentValidatorTest {

    @Test
    void cleanShippedShapesPass() {
        Rarity epic = new Rarity("epic", "", 25, 25, 2.2, 1.9, 0.7, 1.5, 1.3, 2, "aura", List.of("*"));
        Rarity boss = new Rarity("boss", "", 0, 0, 4.0, 2.2, 0.6, 3.0, 2.0, 2, "aura", List.of("*"));
        assertTrue(ScalingContentValidator.validateRarities(List.of(epic, boss)).isEmpty(),
                "the shipped ladder shapes (incl. the weight-0 force-only boss) are clean");

        Affix armored = new Affix("armored", "", "", "eff", 3, 5, List.of("*"), 0, 0, 0, 0, Affix.KIND_STAT, null, true);
        Affix vampiric = new Affix("vampiric", "", "", null, 2, 20, List.of("*"), 0, 0, 0, 0, Affix.KIND_BEHAVIORAL, "vampiric", false);
        assertTrue(ScalingContentValidator.validateAffixes(List.of(armored, vampiric)).isEmpty(),
                "shipped affix shapes are clean");
    }

    @Test
    void badRarityValuesAreFlagged() {
        Rarity bad = new Rarity("bad", "", -1, -5, 0.0, 0.0, 0.0, -1, -1, -1, null, List.of("*"));
        List<String> findings = ScalingContentValidator.validateRarities(List.of(bad));
        assertEquals(6, findings.size(), "weight, minDifficulty, hp, damage, loot/xp, slots all flagged: " + findings);
    }

    @Test
    void familyFilterSelfContradictionsAreFlagged() {
        // A deny "*" nukes everything -> the tier can never roll.
        Rarity denyAll = new Rarity("denyall", "", 25, 25, 1, 1, 1, 1, 1, 0, null, List.of("*"), "",
                new FamilyFilter(List.of(), List.of(), List.of(), List.of("*")));
        // Same id in AllowGroups + DenyGroups -> deny wins, the allow entry is dead.
        Rarity dead = new Rarity("dead", "", 25, 25, 1, 1, 1, 1, 1, 0, null, List.of("*"), "",
                new FamilyFilter(List.of("Spiders"), List.of("Spiders"), List.of(), List.of()));
        // A weight-0 (force-only) tier with the same contradiction is NOT flagged (it never rolls anyway).
        Rarity forced = new Rarity("forced", "", 0, 0, 1, 1, 1, 1, 1, 0, null, List.of("*"), "",
                new FamilyFilter(List.of(), List.of(), List.of(), List.of("*")));
        // A legitimate spider-only filter is clean.
        Rarity ok = new Rarity("ok", "", 25, 25, 1, 1, 1, 1, 1, 0, null, List.of("*"), "",
                new FamilyFilter(List.of("Spiders"), List.of(), List.of("Spider*"), List.of()));
        List<String> findings = ScalingContentValidator.validateRarities(List.of(denyAll, dead, forced, ok));
        assertEquals(2, findings.size(), "deny-all + dead-allow flagged; weight-0 + valid gate clean: " + findings);
    }

    @Test
    void forceListContradictionsAreFlagged() {
        // A force-only tier (weight 0) is now REACHABLE content, so its filter is validated: an id present
        // in both ForceGroups and DenyGroups is a dead deny entry (force wins).
        Rarity contradiction = new Rarity("contradiction", "", 0, 0, 1, 1, 1, 1, 1, 0, null, List.of("*"), "",
                new FamilyFilter(List.of(), List.of("Bosses"), List.of(), List.of("Dragon_*"),
                        List.of("Bosses"), List.of("Dragon_*")));
        List<String> findings = ScalingContentValidator.validateRarities(List.of(contradiction));
        assertEquals(2, findings.size(), "both the group and the role contradiction are flagged: " + findings);

        // A deny-ALL is not dead content when the tier forces itself onto a family (force outranks deny).
        Rarity forcedDespiteDenyAll = new Rarity("forced", "", 0, 0, 1, 1, 1, 1, 1, 0, null, List.of("*"), "",
                new FamilyFilter(List.of(), List.of(), List.of(), List.of("*"), List.of("Bosses"), List.of()));
        assertTrue(ScalingContentValidator.validateRarities(List.of(forcedDespiteDenyAll)).isEmpty(),
                "a force-only tier that denies the normal roll outright is a legitimate shape");
    }

    @Test
    void malformedNameColorIsFlagged() {
        Rarity noHash = new Rarity("nohash", "", 1, 0, 1, 1, 1, 1, 1, 0, null, List.of("*"), "b388ff");
        Rarity word = new Rarity("word", "", 1, 0, 1, 1, 1, 1, 1, 0, null, List.of("*"), "purple");
        Rarity good = new Rarity("good", "", 1, 0, 1, 1, 1, 1, 1, 0, null, List.of("*"), "#B388FF");
        Rarity absent = new Rarity("absent", "", 1, 0, 1, 1, 1, 1, 1, 0, null, List.of("*"));
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
                List.of(), null, "", FamilyFilter.ALLOW_ALL);
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
    void cleanCasterRosterPasses() {
        CasterEntry ability = new CasterEntry(CasterEntry.Kind.ABILITY, "fireball", null, 20.0, List.of(),
                CasterEntry.Scope.BOSS, false, 14_000L, 3_000L,
                new CasterEntry.Windup("Hurt", null, null));
        CasterEntry chain = new CasterEntry(CasterEntry.Kind.NATIVE_CHAIN, null, "MMO_Dodge", 0.0, List.of(),
                CasterEntry.Scope.BOSS, false, 6_000L, 2_000L, null);
        CasterRoster roster = new CasterRoster("demo_boss_caster", "Dragon_Fire", null, List.of(ability, chain));
        assertTrue(ScalingContentValidator.validateCasterRosters(List.of(roster)).isEmpty(),
                "the shipped demo roster's shape is clean (incl. the fireball entry's Windup)");
    }

    @Test
    void windupBlankAnimationIsFlagged() {
        CasterEntry blank = new CasterEntry(CasterEntry.Kind.ABILITY, "fireball", null, 0.0, List.of(),
                CasterEntry.Scope.ANY, false, 10_000L, 0L, new CasterEntry.Windup("", null, null));
        CasterRoster roster = new CasterRoster("r", "Some_Role", null, List.of(blank));
        List<String> findings = ScalingContentValidator.validateCasterRosters(List.of(roster));
        assertEquals(1, findings.size(), "a Windup group present with a blank Animation is flagged: " + findings);
        assertTrue(findings.get(0).contains("Windup.Animation"), findings.toString());
    }

    @Test
    void windupOnNativeChainEntryIsFlagged() {
        CasterEntry chain = new CasterEntry(CasterEntry.Kind.NATIVE_CHAIN, null, "MMO_Dodge", 0.0, List.of(),
                CasterEntry.Scope.ANY, false, 10_000L, 0L, new CasterEntry.Windup("Hurt", null, null));
        CasterRoster roster = new CasterRoster("r", "Some_Role", null, List.of(chain));
        List<String> findings = ScalingContentValidator.validateCasterRosters(List.of(roster));
        assertEquals(1, findings.size(), "a Windup authored on a NativeChain entry is flagged as ineffective: " + findings);
        assertTrue(findings.get(0).contains("Windup only applies to AbilityId entries"), findings.toString());
    }

    @Test
    void windupUnknownSlotIsFlagged() {
        CasterEntry entry = new CasterEntry(CasterEntry.Kind.ABILITY, "fireball", null, 0.0, List.of(),
                CasterEntry.Scope.ANY, false, 10_000L, 0L, new CasterEntry.Windup("Hurt", null, "Bogus"));
        CasterRoster roster = new CasterRoster("r", "Some_Role", null, List.of(entry));
        List<String> findings = ScalingContentValidator.validateCasterRosters(List.of(roster));
        assertEquals(1, findings.size(), "an unrecognised Windup.Slot name is flagged: " + findings);
        assertTrue(findings.get(0).contains("unknown Windup.Slot"), findings.toString());
    }

    @Test
    void windupKnownSlotPasses() {
        CasterEntry entry = new CasterEntry(CasterEntry.Kind.ABILITY, "fireball", null, 0.0, List.of(),
                CasterEntry.Scope.ANY, false, 10_000L, 0L, new CasterEntry.Windup("Hurt", null, "Status"));
        CasterRoster roster = new CasterRoster("r", "Some_Role", null, List.of(entry));
        assertTrue(ScalingContentValidator.validateCasterRosters(List.of(roster)).isEmpty(),
                "a recognised Windup.Slot name is clean");
    }

    @Test
    void casterRosterRoleSelectorXorIsFlagged() {
        CasterRoster neither = new CasterRoster("neither", null, null, List.of());
        CasterRoster both = new CasterRoster("both", "Dragon_Fire", "Dragon_*", List.of());
        List<String> findings = ScalingContentValidator.validateCasterRosters(List.of(neither, both));
        assertEquals(2, findings.size(), "both the neither-authored and both-authored rosters are flagged: " + findings);
    }

    @Test
    void casterEntryInvalidKindIsFlagged() {
        CasterEntry invalid = new CasterEntry(CasterEntry.Kind.INVALID, null, null, 0.0, List.of(),
                CasterEntry.Scope.ANY, false, 10_000L, 0L, null);
        CasterRoster roster = new CasterRoster("r", "Some_Role", null, List.of(invalid));
        List<String> findings = ScalingContentValidator.validateCasterRosters(List.of(roster));
        assertEquals(1, findings.size(), "the INVALID (neither/both AbilityId+NativeChain) entry is flagged: " + findings);
        assertTrue(findings.get(0).contains("AbilityId"), findings.toString());
    }

    @Test
    void casterEntryUnknownScopeIsFlagged() {
        CasterEntry unknown = new CasterEntry(CasterEntry.Kind.ABILITY, "fireball", null, 0.0, List.of(),
                CasterEntry.Scope.ANY, true, 10_000L, 0L, null);
        CasterRoster roster = new CasterRoster("r", "Some_Role", null, List.of(unknown));
        List<String> findings = ScalingContentValidator.validateCasterRosters(List.of(roster));
        assertEquals(1, findings.size(), "an unrecognised authored Scope is flagged: " + findings);
        assertTrue(findings.get(0).contains("Scope"), findings.toString());
    }

    @Test
    void casterEntryCadenceFloorIsFlagged() {
        // Absent CadenceSeconds folds to 0ms; an authored-too-low value (e.g. 1s) also trips the floor.
        CasterEntry absent = new CasterEntry(CasterEntry.Kind.ABILITY, "fireball", null, 0.0, List.of(),
                CasterEntry.Scope.ANY, false, 0L, 0L, null);
        CasterEntry tooLow = new CasterEntry(CasterEntry.Kind.ABILITY, "fireball", null, 0.0, List.of(),
                CasterEntry.Scope.ANY, false, 1_000L, 0L, null);
        CasterEntry clean = new CasterEntry(CasterEntry.Kind.ABILITY, "fireball", null, 0.0, List.of(),
                CasterEntry.Scope.ANY, false, 2_000L, 0L, null);
        CasterRoster roster = new CasterRoster("r", "Some_Role", null, List.of(absent, tooLow, clean));
        List<String> findings = ScalingContentValidator.validateCasterRosters(List.of(roster));
        assertEquals(2, findings.size(), "absent (0) and 1s both trip the >= 2s floor; 2s is clean: " + findings);
    }

    @Test
    void casterEntryNegativeMinDifficultyAndJitterAreFlagged() {
        CasterEntry bad = new CasterEntry(CasterEntry.Kind.ABILITY, "fireball", null, -5.0, List.of(),
                CasterEntry.Scope.ANY, false, 10_000L, -1_000L, null);
        CasterRoster roster = new CasterRoster("r", "Some_Role", null, List.of(bad));
        List<String> findings = ScalingContentValidator.validateCasterRosters(List.of(roster));
        assertEquals(2, findings.size(), "negative MinDifficulty + negative JitterSeconds both flagged: " + findings);
    }

    @Test
    void duplicateRoleGlobAcrossRostersIsFlagged() {
        CasterRoster a = new CasterRoster("a", null, "Dragon_*", List.of());
        CasterRoster b = new CasterRoster("b", null, "Dragon_*", List.of());
        List<String> findings = ScalingContentValidator.validateCasterRosters(List.of(a, b));
        assertEquals(1, findings.size(), "the second roster's duplicate Glob is flagged: " + findings);
        assertTrue(findings.get(0).contains("duplicate Role.Glob"), findings.toString());
    }

    @Test
    void duplicateRoleIdAcrossRostersIsFlagged() {
        // Case-insensitive, matching CasterRosterMatcher's equalsIgnoreCase exact-Role.Id precedence.
        CasterRoster a = new CasterRoster("a", "Dragon_Fire", null, List.of());
        CasterRoster b = new CasterRoster("b", "dragon_fire", null, List.of());
        List<String> findings = ScalingContentValidator.validateCasterRosters(List.of(a, b));
        assertEquals(1, findings.size(), "the second roster's duplicate Role.Id is flagged: " + findings);
        assertTrue(findings.get(0).contains("duplicate Role.Id"), findings.toString());
    }

    @Test
    void negativeIntensityIsFlagged() throws Exception {
        MobScalingSettingsAsset bad = decodeSettings("{ \"Intensity\": -1.0 }");
        assertEquals(1, ScalingContentValidator.validateSettings("Test", bad).size());
        MobScalingSettingsAsset clean = decodeSettings("{ \"Intensity\": 2.0 }");
        assertTrue(ScalingContentValidator.validateSettings("Test", clean).isEmpty());
    }

    @AfterEach
    void resetWorlds() {
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        worlds.setOwnerDir(null);
        worlds.applyPackLayer(Map.of());
    }

    private static WorldSettingsConfig foldedWorlds(Path tmp, Map<String, String> files) throws Exception {
        Path dir = tmp.resolve("worlds");
        Files.createDirectories(dir);
        for (Map.Entry<String, String> e : files.entrySet()) {
            Files.writeString(dir.resolve(e.getKey() + ".json"), e.getValue(), StandardCharsets.UTF_8);
        }
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        worlds.setOwnerDir(dir);
        worlds.refold();
        return worlds;
    }

    @Test
    void worldSettingsIssuesAreFlagged(@TempDir Path tmp) throws Exception {
        // Duplicate Match across two ids + unknown Parent + negative Intensity + negative Floor +
        // chance > 1 + inverted caps + a pool id in both Allow and Deny + a negative ChanceMultiplier
        // + negative ExtraSlots = 9 findings.
        WorldSettingsConfig worlds = foldedWorlds(tmp, Map.of(
                "a", """
                        { "Where": { "Match": ["dup_*"] }, "Intensity": -0.5, "RaritySpawnChance": 2.0,
                          "Difficulty": { "Floor": -1.0, "MinCap": 100.0, "MaxCap": 50.0 } }
                        """,
                "b", """
                        { "Where": { "Match": ["dup_*"] }, "Parent": "nope",
                          "Pool": { "Rarities": { "Allow": ["epic"], "Deny": ["epic"] },
                                    "Variants": { "ChanceMultiplier": -1.0 },
                                    "Affixes": { "ExtraSlots": -2 } } }
                        """));
        List<String> findings = ScalingContentValidator.validateWorldSettings(worlds);
        assertEquals(9, findings.size(), "all per-world issues flagged: " + findings);
    }

    @Test
    void cleanWorldSettingsPass(@TempDir Path tmp) throws Exception {
        WorldSettingsConfig worlds = foldedWorlds(tmp, Map.of(
                "base", "{ \"Difficulty\": { \"DistanceEscalation\": { \"Enabled\": false } } }",
                "dungeon", """
                        { "Where": { "Match": ["instance-dungeon_*"] }, "Parent": "base", "Enabled": true,
                          "Difficulty": { "Floor": 45.0, "MinCap": 40.0, "MaxCap": 120.0 },
                          "OpenWorld": { "PlayerScalingEnabled": false },
                          "Pool": { "Rarities": { "Deny": ["legendary"] } } }
                        """));
        assertTrue(ScalingContentValidator.validateWorldSettings(worlds).isEmpty(),
                "a clean per-world set (incl. a pool-only base + a Parent chain) passes");
    }

    // ==================== Reference existence ====================

    /** Resolvers that reject exactly the listed ids and accept everything else. */
    private static ScalingContentValidator.ReferenceResolvers rejecting(String... missing) {
        Set<String> gone = new HashSet<>(List.of(missing));
        Predicate<String> p = id -> !gone.contains(id);
        return new ScalingContentValidator.ReferenceResolvers(p, p, p, p, p, p);
    }

    /** A fixture loot block granting one native drop table, the shape the shipped tiers author. */
    private static LootRef dropListLoot(String dropListId) {
        return LootRef.of(null,
                new Roll[] {Roll.of(null, null, null, null, LootGrants.ofDropList(dropListId), null)});
    }

    @Test
    void danglingAffixEffectIdIsFlagged() {
        Affix broken = new Affix("offensive", "", "", "Mmoscaling_Affix_Offensive", 1, 0, List.of("*"),
                1, 0, 0, 0, Affix.KIND_STAT, null, false);
        List<String> findings = ScalingContentValidator.validateAffixReferences(
                List.of(broken), rejecting("Mmoscaling_Affix_Offensive"));
        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.get(0).contains("offensive"), findings.toString());
        assertTrue(findings.get(0).contains("Mmoscaling_Affix_Offensive"), findings.toString());
    }

    @Test
    void resolvableAndBlankAffixEffectIdsAreClean() {
        Affix resolvable = new Affix("armored", "", "", "Mmoscaling_Armored", 1, 0, List.of("*"),
                0, 0, 0, 0, Affix.KIND_STAT, null, true);
        Affix effectless = new Affix("vampiric", "", "", null, 1, 0, List.of("*"),
                0, 0, 0, 0, Affix.KIND_BEHAVIORAL, "vampiric", false);
        // The effectless affix must stay clean even against a resolver that rejects everything.
        Predicate<String> no = id -> false;
        var strict = new ScalingContentValidator.ReferenceResolvers(no, no, no, no, no, no);
        assertTrue(ScalingContentValidator.validateAffixReferences(
                List.of(resolvable), rejecting("SomethingElse")).isEmpty(), "a resolvable EffectId is clean");
        assertTrue(ScalingContentValidator.validateAffixReferences(List.of(effectless), strict).isEmpty(),
                "a null EffectId is a legitimate shape, never a dangling reference");
    }

    @Test
    void danglingRarityAuraDropListAndGroupAreFlagged() {
        Rarity broken = new Rarity("epic", "", 25, 25, 1, 1, 1, 1, 1, 2, "NoSuchAura",
                List.of("*"), "", new FamilyFilter(List.of("NoSuchGroup"), List.of(), List.of(), List.of()),
                dropListLoot("NoSuchDrops"));
        List<String> findings = ScalingContentValidator.validateRarityReferences(
                List.of(broken), rejecting("NoSuchAura", "NoSuchDrops", "NoSuchGroup"));
        assertEquals(3, findings.size(), "aura, drop list and Families group all flagged: " + findings);
    }

    @Test
    void danglingLootTableReferenceIsFlagged() {
        // A shared table named by id is the other half of the Loot block, and a typo there is just as
        // silent as a bad drop list: the tier still rolls and simply hands over nothing.
        Rarity broken = new Rarity("epic", "", 25, 25, 1, 1, 1, 1, 1, 2, null, List.of("*"), "",
                FamilyFilter.ALLOW_ALL, LootRef.of(new String[] {"nosuchtable"}, null));
        List<String> findings = ScalingContentValidator.validateRarityReferences(
                List.of(broken), rejecting("nosuchtable"));
        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.get(0).contains("Loot.Lootables"), findings.toString());
        assertTrue(findings.get(0).contains("nosuchtable"), findings.toString());
    }

    @Test
    void lootlessTierIsNeverADanglingReference() {
        // No Loot block at all is a legitimate shape (a tier that only changes stats), so it must stay
        // clean even against a resolver that rejects every id.
        Predicate<String> no = id -> false;
        var strict = new ScalingContentValidator.ReferenceResolvers(no, no, no, no, no, no);
        Rarity statsOnly = new Rarity("statsonly", "", 25, 25, 1, 1, 1, 1, 1, 0, null, List.of("*"));
        assertTrue(ScalingContentValidator.validateRarityReferences(List.of(statsOnly), strict).isEmpty(),
                "a tier with no Loot block has nothing to dangle");
    }

    @Test
    void danglingForceGroupAndForceRoleAreFlagged() {
        // The force lists are the boss-tier targeting surface, so a typo there silently un-forces the tier.
        Rarity boss = new Rarity("boss", "", 0, 0, 1, 1, 1, 1, 1, 2, null, List.of("*"), "",
                new FamilyFilter(List.of(), List.of(), List.of(), List.of(),
                        List.of("Mmoscaling_Bosses"), List.of("Baron", "Cult_*_Miniboss")));
        List<String> findings = ScalingContentValidator.validateRarityReferences(
                List.of(boss), rejecting("Mmoscaling_Bosses", "Baron", "Cult_*_Miniboss"));
        assertEquals(2, findings.size(),
                "the missing group and the missing EXACT role are flagged; the glob is not: " + findings);
        assertTrue(findings.toString().contains("Mmoscaling_Bosses"), findings.toString());
        assertTrue(findings.toString().contains("Baron"), findings.toString());
    }

    @Test
    void permissiveResolversNeverFlagAnything() {
        Rarity r = new Rarity("epic", "", 25, 25, 1, 1, 1, 1, 1, 2, "Aura", List.of("*"), "",
                new FamilyFilter(List.of("Group"), List.of(), List.of("Role"), List.of()),
                dropListLoot("Drops"));
        assertTrue(ScalingContentValidator.validateRarityReferences(
                List.of(r), ScalingContentValidator.ReferenceResolvers.permissive()).isEmpty(),
                "an engine-absent resolver set must degrade to silence, never to false warnings");
    }

    @Test
    void danglingVariantReferencesAreFlagged() {
        Variant v = new Variant("horrific", "", 0.15, 0, 1, 1, 1, 1, 1, 1, List.of("*"), List.of("*"),
                "NoSuchAura", "", FamilyFilter.ALLOW_ALL, dropListLoot("NoSuchDrops"));
        List<String> findings = ScalingContentValidator.validateVariantReferences(
                List.of(v), rejecting("NoSuchAura", "NoSuchDrops"));
        assertEquals(2, findings.size(), findings.toString());
    }

    @Test
    void danglingCasterNativeChainIsFlaggedButAbilityIdIsNot() {
        CasterEntry chain = new CasterEntry(CasterEntry.Kind.NATIVE_CHAIN, null, "No_Such_Chain", 0.0,
                List.of(), CasterEntry.Scope.ANY, false, 10_000L, 0L, null);
        CasterEntry ability = new CasterEntry(CasterEntry.Kind.ABILITY, "no_such_ability", null, 0.0,
                List.of(), CasterEntry.Scope.ANY, false, 10_000L, 0L, null);
        CasterRoster roster = new CasterRoster("r", "Some_Role", null, List.of(chain, ability));
        List<String> findings = ScalingContentValidator.validateCasterRosterReferences(
                List.of(roster), rejecting("No_Such_Chain", "no_such_ability"));
        assertEquals(1, findings.size(),
                "only the native chain is checkable here; abilities live in the MMO jar: " + findings);
        assertTrue(findings.get(0).contains("NativeChain"), findings.toString());
    }

    // ==================== Match-pattern ambiguity ====================

    @Test
    void nestedPrefixMatchesReportTheirClosestShadow(@TempDir Path tmp) throws Exception {
        WorldSettingsConfig worlds = foldedWorlds(tmp, Map.of(
                "a", "{ \"Where\": { \"Match\": [\"a*\"] } }",
                "aa", "{ \"Where\": { \"Match\": [\"aa*\"] } }",
                "aaa", "{ \"Where\": { \"Match\": [\"aaa*\"] } }"));
        List<String> findings = ScalingContentValidator.validateWorldSettings(worlds);
        assertEquals(2, findings.size(),
                "'a*' shadows 'aa*' and 'aa*' shadows 'aaa*'; only the CLOSEST pair per rule: " + findings);
        assertTrue(findings.toString().contains("shadows"), findings.toString());
    }

    @Test
    void disjointEqualLengthPrefixesAreClean(@TempDir Path tmp) throws Exception {
        WorldSettingsConfig worlds = foldedWorlds(tmp, Map.of(
                "foo", "{ \"Where\": { \"Match\": [\"foo*\"] } }",
                "bar", "{ \"Where\": { \"Match\": [\"bar*\"] } }"));
        assertTrue(ScalingContentValidator.validateWorldSettings(worlds).isEmpty(),
                "two prefixes of equal length can never both match one world name");
    }

    @Test
    void equalLengthContainsCoresReportTheOrderDecidedTie(@TempDir Path tmp) throws Exception {
        WorldSettingsConfig worlds = foldedWorlds(tmp, Map.of(
                "alpha", "{ \"Where\": { \"Match\": [\"*abc*\"] } }",
                "beta", "{ \"Where\": { \"Match\": [\"*xyz*\"] } }"));
        List<String> findings = ScalingContentValidator.validateWorldSettings(worlds);
        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.get(0).contains("authoring order"), findings.toString());
    }

    @Test
    void containsRuleShadowingAPrefixRuleIsFlagged(@TempDir Path tmp) throws Exception {
        WorldSettingsConfig worlds = foldedWorlds(tmp, Map.of(
                "any_fear", "{ \"Where\": { \"Match\": [\"*fear*\"] } }",
                "dungeon", "{ \"Where\": { \"Match\": [\"instance-dungeon_of_fear_i*\"] } }"));
        List<String> findings = ScalingContentValidator.validateWorldSettings(worlds);
        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.get(0).contains("*fear*"), findings.toString());
    }

    private static MobScalingSettingsAsset decodeSettings(String json) throws Exception {
        return MobScalingSettingsAsset.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }
}
