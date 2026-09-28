package com.ziggfreed.mmomobscaling.asset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.hypixel.hytale.assetstore.JsonAsset;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.NumberSchema;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.loot.Roll;
import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.caster.CasterEntry;
import com.ziggfreed.mmomobscaling.caster.CasterRoster;
import com.ziggfreed.mmomobscaling.config.AffixConfig;
import com.ziggfreed.mmomobscaling.config.RarityConfig;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.variant.Variant;
import com.ziggfreed.mmomobscaling.world.DifficultyMapping;
import com.ziggfreed.common.icon.IconSpec;

/**
 * Guards the Rarity/Affix codecs: static-init succeeds (a lower-case-first PascalCase key would throw here),
 * the shipped starter JSON decodes to the expected typed values, and the {@code AbstractKeyedAssetConfig}
 * fold lower-cases + resolves ids. The static-init assertion is this mod's equivalent of ziggfreed-common's
 * {@code AssetCodecInitTest}.
 */
class MobScalingAssetCodecTest {

    @Test
    void codecsStaticInitializeWithoutThrowing() {
        assertNotNull(RarityAsset.CODEC, "RarityAsset.CODEC static-init (PascalCase key guard)");
        assertNotNull(VariantAsset.CODEC, "VariantAsset.CODEC static-init (PascalCase key guard)");
        assertNotNull(AffixAsset.CODEC, "AffixAsset.CODEC static-init (PascalCase key guard)");
        assertNotNull(MobScalingSettingsAsset.CODEC, "MobScalingSettingsAsset.CODEC static-init");
        assertNotNull(DifficultyMappingAsset.CODEC, "DifficultyMappingAsset.CODEC static-init");
        assertNotNull(IconSpec.CODEC, "IconSpec.CODEC static-init (PascalCase key guard)");
        assertNotNull(WorldSettingsAsset.CODEC, "WorldSettingsAsset.CODEC static-init (raw Name+Payload)");
        // WorldSettings.CODEC is a plain BuilderCodec (the lowercase-key guard only fires for an
        // AssetBuilderCodec), so touch its class-init explicitly to keep the PascalCase guarantee.
        assertNotNull(WorldSettings.CODEC, "WorldSettings.CODEC static-init (the per-world body schema)");
        assertNotNull(WorldSettings.Pool.CODEC, "WorldSettings.Pool.CODEC static-init");
        assertNotNull(CasterRosterAsset.CODEC, "CasterRosterAsset.CODEC static-init (PascalCase key guard)");
    }

    @Test
    void decodesShippedDungeonWorldFile() throws Exception {
        WorldSettingsAsset asset = decode("/Server/MmoMobScaling/Worlds/DungeonOfFear_I.json",
                WorldSettingsAsset.CODEC);
        JsonObject body = asset.getPayloadAsJsonObject();
        assertNotNull(body, "raw Payload survives for the pre-merge");
        // Dungeon of Fear I ships as a flat, self-contained file (no Parent): it simply turns
        // open-world mob scaling OFF in its instance worlds.
        WorldSettings ws = WorldSettings.CODEC.decodeJson(
                RawJsonReader.fromJsonString(body.toString()), new ExtraInfo());
        assertEquals("*dungeon_of_fear_i-*", ws.firstMatchPattern(), "Where.Match");
        assertEquals(Boolean.FALSE, ws.getEnabled(), "Enabled kill-switch off");
    }

    @Test
    void escalationOriginDecodesWithNullableAxes() throws Exception {
        // The Origin group nests under Difficulty.DistanceEscalation with two nullable Double leaves: a
        // half-authored group keeps the other axis null, an empty group (the shipped shape) keeps both null,
        // and an absent group decodes to a null group. A negative coordinate is a legal value.
        MobScalingSettingsAsset half = decodeJson(
                "{ \"Difficulty\": { \"DistanceEscalation\": { \"Origin\": { \"X\": -1500.25 } } } }",
                MobScalingSettingsAsset.CODEC);
        MobScalingSettingsAsset.EscalationOrigin origin = half.getDifficulty().getDistanceEscalation().getOrigin();
        assertNotNull(origin, "the authored group decodes");
        assertEquals(-1500.25, origin.getX(), 1e-9, "a negative coordinate decodes as authored");
        assertNull(origin.getZ(), "the unauthored axis stays null");

        MobScalingSettingsAsset empty = decodeJson(
                "{ \"Difficulty\": { \"DistanceEscalation\": { \"Origin\": { \"$Comment\": \"note\" } } } }",
                MobScalingSettingsAsset.CODEC);
        MobScalingSettingsAsset.EscalationOrigin none = empty.getDifficulty().getDistanceEscalation().getOrigin();
        assertNotNull(none, "an empty group (comment only) still decodes to a group");
        assertNull(none.getX(), "empty group: X null");
        assertNull(none.getZ(), "empty group: Z null");

        MobScalingSettingsAsset absent = decodeJson(
                "{ \"Difficulty\": { \"DistanceEscalation\": { \"Enabled\": true } } }", MobScalingSettingsAsset.CODEC);
        assertNull(absent.getDifficulty().getDistanceEscalation().getOrigin(), "an absent group is null");

        // A whole-number literal (what the admin page writes for "-500") decodes through the DOUBLE leaf too.
        MobScalingSettingsAsset whole = decodeJson(
                "{ \"Difficulty\": { \"DistanceEscalation\": { \"Origin\": { \"X\": -500, \"Z\": 20 } } } }",
                MobScalingSettingsAsset.CODEC);
        assertEquals(-500.0, whole.getDifficulty().getDistanceEscalation().getOrigin().getX(), 1e-9);
        assertEquals(20.0, whole.getDifficulty().getDistanceEscalation().getOrigin().getZ(), 1e-9);
    }

    @Test
    void decodesShippedZoneMapping() throws Exception {
        DifficultyMappingAsset asset = decode("/Server/MmoMobScaling/Difficulty/Zone2.json",
                DifficultyMappingAsset.CODEC);
        DifficultyMapping m = asset.toMapping("zone2");
        assertNotNull(m, "shipped mapping resolves");
        assertEquals(DifficultyMapping.TargetType.ZONE, m.targetType(), "TargetType");
        assertTrue(m.matches("Zone2") && m.matches("zone2"), "TargetId matches case-insensitively");
        assertEquals(5.0, m.floor(), 1e-9, "Floor");
    }

    @Test
    void malformedMappingResolvesNull() {
        DifficultyMappingAsset blank = new DifficultyMappingAsset();
        assertTrue(blank.toMapping("broken") == null, "unknown TargetType/blank TargetId folds to null (skipped)");
    }

    @Test
    void decodesShippedEpicRarity() throws Exception {
        RarityAsset asset = decode("/Server/MmoMobScaling/Rarities/Epic.json", RarityAsset.CODEC);
        Rarity r = asset.toRarity();
        assertTrue(r.difficultyMultiplier() > 1.0,
                "DifficultyMultiplier decodes and a rollable tier reads the curve further along than a plain mob");
        assertEquals(25.0, r.weight(), 1e-9, "Weight");
        assertEquals(25.0, r.minDifficulty(), 1e-9, "MinDifficulty");
        assertEquals(2, r.affixSlots(), "AffixSlots");
        assertEquals("Mmoscaling_Aura_Epic", r.auraEffectId(), "AuraEffectId");
        assertEquals(List.of("Mmoscaling_Drops_Epic"), grantedDropLists(r.loot()),
                "Loot grants the tier's native drop table");
        assertEquals("mmomobscaling.rarity.epic.name", r.displayNameKey(), "DisplayNameKey");
        assertTrue(r.allowsAffix("armored"), "wildcard AllowedAffixes allows any affix");
        assertEquals("#b388ff", r.nameColor(), "NameColor (the inspector HUD tint)");
        assertEquals("#b388ff", r.displayColor(), "displayColor passes an authored colour through");
        assertTrue(r.familyFilter().isUnrestricted(), "no Families block -> allow-all (every mob eligible)");
    }

    @Test
    void decodesShippedHorrificVariantFamilyGate() throws Exception {
        Variant v = decode("/Server/MmoMobScaling/Variants/Horrific.json", VariantAsset.CODEC).toVariant();
        assertEquals(0.15, v.chance(), 1e-9, "Roll.Chance");
        assertEquals(20.0, v.minDifficulty(), 1e-9, "Roll.MinDifficulty");
        assertTrue(v.difficultyMultiplier() >= 1.0, "DifficultyMultiplier decodes; an overlay never softens its base");
        assertTrue(v.allowsAffix("venomous"), "the variant grants its unique affix");
        assertTrue(!v.familyFilter().isUnrestricted(), "the Families block makes it restricted");
        assertTrue(v.familyFilter().allowGroups().contains("Spiders"), "AllowGroups decoded");
        assertTrue(v.familyFilter().allowRoles().contains("Spider*"), "AllowRoles decoded");
        assertEquals("mmomobscaling.variant.horrific.name", v.displayNameKey(), "DisplayNameKey");
        assertEquals("Mmoscaling_Aura_Horrific", v.auraEffectId(), "AuraEffectId (fallback tint)");
        assertEquals(List.of("Mmoscaling_Drops_Horrific"), grantedDropLists(v.loot()),
                "the overlay's own Loot stacks on the base rarity's");
        assertTrue(v.allowsRarity("epic"), "AllowedRarities ['*'] overlays any base rarity");
        assertTrue(v.allowsRarity(""), "['*'] also overlays a plain-base mob");
    }

    @Test
    void decodesShippedVenomousVariantGate() throws Exception {
        Affix a = decode("/Server/MmoMobScaling/Affixes/Venomous.json", AffixAsset.CODEC).toAffix();
        assertTrue(a.allowsVariant("horrific"), "venomous is granted by the horrific variant");
        assertTrue(!a.allowsVariant("other"), "venomous is not granted by any other variant");
        assertTrue(!a.allowsRarity("epic"), "venomous is variant-exclusive (AllowedRarities [] -> no rarity)");
    }

    @Test
    void rarityFamilyForceListsDecode() throws Exception {
        // FIXTURE asset (not a shipped file): pins the ForceGroups/ForceRoles keys and their precedence
        // data, without asserting anything about shipped balance content.
        RarityAsset asset = decodeJson("""
                { "Name": "fixture_forced",
                  "Roll": { "Weight": 0, "MinDifficulty": 0 },
                  "Families": { "ForceGroups": ["Fixture_Group"], "ForceRoles": ["Fixture_*"],
                                "DenyRoles": ["Fixture_Excluded"] } }
                """, RarityAsset.CODEC);
        com.ziggfreed.mmomobscaling.family.FamilyFilter f = asset.toRarity().familyFilter();
        assertTrue(f.hasForce(), "the Force lists decode");
        assertTrue(f.forceGroups().contains("Fixture_Group"), "ForceGroups decoded");
        assertTrue(f.forcesRole("Fixture_Boss"), "ForceRoles glob matches the family");
        assertTrue(!f.forcesRole("Other_Role"), "and leaves everything else alone");
        assertTrue(!f.isUnrestricted(), "the Deny leaf still constrains the allow/deny gate");
    }

    @Test
    void rarityWithoutForceListsHasNone() throws Exception {
        RarityAsset asset = decodeJson("{ \"Name\": \"fixture_plain\", \"Roll\": { \"Weight\": 1 } }",
                RarityAsset.CODEC);
        assertTrue(!asset.toRarity().familyFilter().hasForce(), "no Families block -> nothing forced");
        assertEquals(1.0, asset.toRarity().difficultyMultiplier(), 1e-9,
                "an absent DifficultyMultiplier is the plain 1.0 (the tier reads the curve where a plain mob does)");
    }

    @Test
    void theShippedLadderIncreasesInDifficultyMultiplier() throws Exception {
        // A relative-ordering invariant, not a number: the roster orders tiers by DifficultyMultiplier
        // (forced-tier resolution, the ladder position other mods read), so the shipped ladder must climb.
        Rarity rare = decode("/Server/MmoMobScaling/Rarities/Rare.json", RarityAsset.CODEC).toRarity();
        Rarity epic = decode("/Server/MmoMobScaling/Rarities/Epic.json", RarityAsset.CODEC).toRarity();
        Rarity legendary = decode("/Server/MmoMobScaling/Rarities/Legendary.json", RarityAsset.CODEC).toRarity();
        Rarity boss = decode("/Server/MmoMobScaling/Rarities/Boss.json", RarityAsset.CODEC).toRarity();
        assertTrue(rare.difficultyMultiplier() > 1.0, "rare reads the curve past a plain mob");
        assertTrue(epic.compareStrength(rare) > 0, "epic outranks rare");
        assertTrue(legendary.compareStrength(epic) > 0, "legendary outranks epic");
        assertTrue(boss.compareStrength(legendary) > 0, "the forced boss tier is the strongest, so it wins a force tie");
    }

    @Test
    void perStatMultipliersAreNotLeavesAndAFileStillAuthoringThemFoldsOnItsDifficultyMultiplier() throws Exception {
        // Hp / OutDamage / InDamage are not declared under Multipliers: the codec skips them as unknown
        // keys (the server's own "Unused key(s)" load warning names the file and the key), the reward leaves
        // beside them still decode, and the tier folds on whatever DifficultyMultiplier it authors.
        RarityAsset legacy = decodeJson("""
                { "Name": "fixture_legacy", "Roll": { "Weight": 1 },
                  "DifficultyMultiplier": 1.5,
                  "Multipliers": { "Hp": 2.2, "OutDamage": 1.9, "Loot": 1.5, "Xp": 1.3 } }
                """, RarityAsset.CODEC);
        Rarity r = legacy.toRarity();
        assertEquals(1.5, r.difficultyMultiplier(), 1e-9, "the tier folds on its DifficultyMultiplier");
        assertEquals(1.5, r.lootMult(), 1e-9, "the reward leaves beside the unknown keys still decode");
        assertEquals(1.3, r.xpMult(), 1e-9);

        VariantAsset legacyVariant = decodeJson("""
                { "Name": "fixture_legacy_variant", "Roll": { "Chance": 0.1 },
                  "Multipliers": { "InDamage": 0.9, "Loot": 1.3 } }
                """, VariantAsset.CODEC);
        assertEquals(1.0, legacyVariant.toVariant().difficultyMultiplier(), 1e-9,
                "an unknown per-stat key never becomes a difficulty multiplier - there is no honest conversion");
        assertEquals(1.3, legacyVariant.toVariant().lootMult(), 1e-9, "the reward leaf beside it still decodes");
    }

    @Test
    void rarityWithoutNameColorFallsBackToWhite() {
        Rarity plain = new Rarity("test", "", 1, 0, 1, 1, 1, 0, null, java.util.List.of("*"));
        assertEquals("", plain.nameColor(), "convenience constructor leaves NameColor empty");
        assertEquals(Rarity.DEFAULT_NAME_COLOR, plain.displayColor(), "empty NameColor renders white");
    }

    @Test
    void rarityWithoutLootBlockFoldsToNothing() throws Exception {
        // A tier that only changes stats is a legitimate shape: an absent (or empty) Loot block must fold
        // to nothing rather than to an empty ref the death path would still walk.
        RarityAsset none = decodeJson("{ \"Name\": \"fixture_lootless\", \"Roll\": { \"Weight\": 1 } }",
                RarityAsset.CODEC);
        assertNull(none.toRarity().loot(), "no Loot key -> nothing to roll");

        RarityAsset empty = decodeJson(
                "{ \"Name\": \"fixture_emptyloot\", \"Roll\": { \"Weight\": 1 }, \"Loot\": {} }",
                RarityAsset.CODEC);
        assertNull(empty.toRarity().loot(), "an empty Loot block reads the same as an absent one");
    }

    @Test
    void rarityLootTakesTheWholeSharedVocabulary() throws Exception {
        // FIXTURE asset: pins that the shared loot vocabulary decodes here in full, so a tier can write a
        // gated, chance-scaled roll granting several kinds of thing at once.
        //
        // The sibling Lootables leaf (shared tables by id) is deliberately NOT exercised here: its codec
        // refuses to decode outside a live AssetStore, so the in-game pass is the only place that can
        // cover it. Everything below is the half a bare JVM can genuinely check.
        RarityAsset asset = decodeJson("""
                { "Name": "fixture_loot",
                  "Loot": {
                    "Rolls": [
                      { "Chance": { "Base": 5 },
                        "Conditions": [ { "Factor": "fixture:flag", "Min": 1 } ],
                        "Grants": { "Items": [ { "Item": "Fixture_Gem", "Count": 2 } ],
                                    "Commands": ["/say {player} got lucky"] } }
                    ] } }
                """, RarityAsset.CODEC);
        LootRef loot = asset.toRarity().loot();
        assertNotNull(loot, "the Loot block decodes");
        assertNotNull(loot.getRolls(), "the inline roll decodes");
        assertEquals(1, loot.getRolls().length, "one inline roll");
        Roll roll = loot.getRolls()[0];
        assertNotNull(roll.getChance(), "Chance decodes as the shared formula");
        assertNotNull(roll.getConditions(), "Conditions decode as shared factor gates");
        assertNotNull(roll.getGrants(), "Grants decode");
        assertEquals(1, roll.getGrants().itemsOrEmpty().size(), "an exact item grant decodes");
        assertNotNull(roll.getGrants().getCommands(), "a command grant decodes");
    }

    /** Every native drop-table id an authored loot block's inline rolls grant, in authored order. */
    @Nonnull
    private static List<String> grantedDropLists(@Nullable LootRef loot) {
        List<String> out = new java.util.ArrayList<>();
        if (loot == null || loot.getRolls() == null) {
            return out;
        }
        for (Roll roll : loot.getRolls()) {
            if (roll == null || roll.getGrants() == null || roll.getGrants().getDropLists() == null) {
                continue;
            }
            out.addAll(java.util.List.of(roll.getGrants().getDropLists()));
        }
        return out;
    }

    @Test
    void decodesShippedArmoredAffix() throws Exception {
        Affix a = decode("/Server/MmoMobScaling/Affixes/Armored.json", AffixAsset.CODEC).toAffix();
        assertEquals("Mmoscaling_Affix_Armored", a.effectId(), "EffectId");
        assertEquals(Affix.KIND_STAT, a.kind(), "Kind");
        assertTrue(a.resistanceBearing(), "ResistanceBearing");
        assertEquals(0.0, a.inDamageDelta(), 1e-9, "mitigation is native (no pipeline delta)");
        assertTrue(a.allowsRarity("legendary"), "wildcard AllowedRarities allows any rarity");
        // Icon (shared IconSpec): the item-id form decodes to iconItemId, no texture path.
        assertTrue(a.hasIcon(), "Armored ships an Icon");
        assertEquals("Armor_Bronze_Chest", a.iconItemId(), "Icon.ItemId");
        assertNull(a.iconTexturePath(), "item-id icon has no texture path");
    }

    @Test
    void decodesShippedWardAffixes() throws Exception {
        // The six Fire/Ice/Arcane/Void/Lightning/Water ward affixes mirror Armored 1:1 (STAT +
        // ResistanceBearing), so they share Armored's single-resistance-affix-per-mob cap (AffixRoster)
        // without any roster code change. Rarer than Armored (Weight 1.5 < 3.0) and gated to a higher
        // MinDifficulty (10 > 5). Lightning/Water are the maintainer's six-school-roster expansion
        // (Phase I), the same degraded-mode-until-Phase-I-ships story as Arcane/Void.
        assertWardAffix("/Server/MmoMobScaling/Affixes/Ward_Fire.json", "Mmoscaling_Ward_Fire");
        assertWardAffix("/Server/MmoMobScaling/Affixes/Ward_Ice.json", "Mmoscaling_Ward_Ice");
        assertWardAffix("/Server/MmoMobScaling/Affixes/Ward_Arcane.json", "Mmoscaling_Ward_Arcane");
        assertWardAffix("/Server/MmoMobScaling/Affixes/Ward_Void.json", "Mmoscaling_Ward_Void");
        assertWardAffix("/Server/MmoMobScaling/Affixes/Ward_Lightning.json", "Mmoscaling_Ward_Lightning");
        assertWardAffix("/Server/MmoMobScaling/Affixes/Ward_Water.json", "Mmoscaling_Ward_Water");
    }

    private static void assertWardAffix(@Nonnull String resource, @Nonnull String expectedEffectId) throws Exception {
        Affix a = decode(resource, AffixAsset.CODEC).toAffix();
        assertEquals(expectedEffectId, a.effectId(), resource + ": EffectId");
        assertEquals(Affix.KIND_STAT, a.kind(), resource + ": Kind");
        assertTrue(a.resistanceBearing(), resource + ": ResistanceBearing");
        assertEquals(1.5, a.spawnWeight(), 1e-9, resource + ": Weight rarer than Armored's 3.0");
        assertEquals(10.0, a.minDifficulty(), 1e-9, resource + ": MinDifficulty higher than Armored's 5");
        assertTrue(a.allowsRarity("legendary"), resource + ": wildcard AllowedRarities allows any rarity");
        assertTrue(a.hasIcon(), resource + ": ships an Icon");
    }

    @Test
    void decodesTexturePathAffixIcon() throws Exception {
        // Swift authors the TEXTURE-path icon form (exercises the other IconSpec branch).
        Affix a = decode("/Server/MmoMobScaling/Affixes/Swift.json", AffixAsset.CODEC).toAffix();
        assertTrue(a.hasIcon(), "Swift ships an Icon");
        assertEquals("UI/StatusEffects/Stamina.png", a.iconTexturePath(), "Icon.TexturePath");
        assertNull(a.iconItemId(), "texture-path icon has no item id");
    }

    @Test
    void decodesShippedDemoBossCasterRoster() throws Exception {
        CasterRoster r = decode("/Server/MmoMobScaling/CasterRosters/Demo_Boss_Caster.json", CasterRosterAsset.CODEC)
                .toDomain();
        assertEquals("Dragon_Fire", r.roleId(), "Role.Id");
        assertNull(r.roleGlob(), "no Role.Glob authored");
        assertTrue(r.hasValidRoleSelector(), "exactly one of Id/Glob authored");
        assertEquals(3, r.abilities().size(), "two ABILITY entries + one NATIVE_CHAIN entry");

        CasterEntry ability = r.abilities().get(0);
        assertEquals(CasterEntry.Kind.ABILITY, ability.kind());
        assertEquals("fireball", ability.abilityId());
        assertNull(ability.nativeChain());
        assertEquals(CasterEntry.Scope.BOSS, ability.scope());
        assertTrue(!ability.scopeUnknown());
        assertEquals(14_000L, ability.cadenceMs(), "CadenceSeconds 14.0 -> 14000ms");
        assertEquals(3_000L, ability.jitterMs(), "JitterSeconds 3.0 -> 3000ms");
        // 1.1.0: the fireball entry's Windup plays the Dragon_Fire model's own "Hurt" AnimationSets key
        // (a model-level cue, no ItemAnimations pair, no Slot override -> default Status slot at play time).
        assertNotNull(ability.windup(), "fireball entry carries a Windup");
        assertEquals("Hurt", ability.windup().animation(), "Windup.Animation");
        assertNull(ability.windup().itemAnimations(), "no ItemAnimations authored (model-level key)");
        assertNull(ability.windup().slot(), "no Slot override authored (defaults to Status at play time)");
        assertTrue(!ability.windup().isItemAnim(), "a bare model-level Animation is not an item-anim pair");

        CasterEntry chain = r.abilities().get(1);
        assertEquals(CasterEntry.Kind.NATIVE_CHAIN, chain.kind());
        assertEquals("Mmoscaling_Demo_Dodge", chain.nativeChain(),
                "retargeted to this mod's own Attack-tagged NPC-only demo root, not the MMO's player-facing MMO_Dodge");
        assertNull(chain.abilityId());
        assertEquals(CasterEntry.Scope.BOSS, chain.scope());
        assertEquals(6_000L, chain.cadenceMs());
        assertEquals(2_000L, chain.jitterMs());
        assertNull(chain.windup(), "the NATIVE_CHAIN entry authors no Windup (its own chain carries its own nodes)");

        // 1.6.0 Phase H: dragon_arcana, the MMO's NPC-only NATIVE_CHAIN exemplar - a second
        // ABILITY entry, rarer cadence than the fireball.
        CasterEntry arcana = r.abilities().get(2);
        assertEquals(CasterEntry.Kind.ABILITY, arcana.kind());
        assertEquals("dragon_arcana", arcana.abilityId());
        assertNull(arcana.nativeChain());
        assertEquals(CasterEntry.Scope.BOSS, arcana.scope());
        assertTrue(!arcana.scopeUnknown());
        assertEquals(20_000L, arcana.cadenceMs(), "CadenceSeconds 20.0 -> 20000ms");
        assertEquals(3_000L, arcana.jitterMs(), "JitterSeconds 3.0 -> 3000ms");
        assertNull(arcana.windup(), "dragon_arcana authors no Windup (its own NativeChain step carries its own nodes)");
    }

    @Test
    void casterRosterEntryXorViolationsFoldToInvalid() throws Exception {
        CasterRosterAsset asset = decodeJson("""
                { "Role": { "Id": "Test_Role" },
                  "Abilities": [
                    { "MinDifficulty": 5.0 },
                    { "AbilityId": "fireball", "NativeChain": "MMO_Dodge" }
                  ] }
                """, CasterRosterAsset.CODEC);
        CasterRoster r = asset.toDomain();
        assertEquals(2, r.abilities().size(), "both malformed entries are KEPT (not dropped) for the validator to see");
        assertEquals(CasterEntry.Kind.INVALID, r.abilities().get(0).kind(), "neither AbilityId nor NativeChain");
        assertEquals(CasterEntry.Kind.INVALID, r.abilities().get(1).kind(), "both AbilityId and NativeChain");
    }

    @Test
    void windupGroupAbsentFoldsToNull() throws Exception {
        CasterRosterAsset asset = decodeJson("""
                { "Role": { "Id": "Test_Role" },
                  "Abilities": [
                    { "AbilityId": "fireball" }
                  ] }
                """, CasterRosterAsset.CODEC);
        CasterEntry entry = asset.toDomain().abilities().get(0);
        assertNull(entry.windup(), "no Windup key authored -> null, zero-cost for every entry that opts out");
    }

    @Test
    void windupItemAnimationPairAndSlotOverrideRoundTrip() throws Exception {
        CasterRosterAsset asset = decodeJson("""
                { "Role": { "Id": "Test_Role" },
                  "Abilities": [
                    { "AbilityId": "fireball",
                      "Windup": { "Animation": "Throw", "ItemAnimations": "Goblin_Item_Anims", "Slot": "Action" } }
                  ] }
                """, CasterRosterAsset.CODEC);
        CasterEntry.Windup w = asset.toDomain().abilities().get(0).windup();
        assertNotNull(w, "Windup group decodes");
        assertEquals("Throw", w.animation(), "Animation (the item-anim pair's animation id)");
        assertEquals("Goblin_Item_Anims", w.itemAnimations(), "ItemAnimations");
        assertEquals("Action", w.slot(), "Slot override");
        assertTrue(w.isItemAnim(), "ItemAnimations authored -> an item-anim pair");
    }

    @Test
    void windupBlankAnimationIsKeptNotDropped() throws Exception {
        // Mirrors the CasterEntry.Kind.INVALID precedent: a malformed Windup group is preserved as a
        // domain object (not silently null) so ScalingContentValidator can flag it as content.
        CasterRosterAsset asset = decodeJson("""
                { "Role": { "Id": "Test_Role" },
                  "Abilities": [
                    { "AbilityId": "fireball", "Windup": { "Slot": "Status" } }
                  ] }
                """, CasterRosterAsset.CODEC);
        CasterEntry.Windup w = asset.toDomain().abilities().get(0).windup();
        assertNotNull(w, "the Windup group is present (even without Animation) so it survives to the validator");
        assertEquals("", w.animation(), "absent Animation folds to blank, not null");
        assertTrue(w.animation().isBlank());
    }

    @Test
    void casterRosterRoleSelectorViolationsPreserveRawValues() throws Exception {
        CasterRosterAsset neither = decodeJson("{ \"Abilities\": [] }", CasterRosterAsset.CODEC);
        CasterRoster neitherRoster = neither.toDomain();
        assertNull(neitherRoster.roleId());
        assertNull(neitherRoster.roleGlob());
        assertTrue(!neitherRoster.hasValidRoleSelector());

        CasterRosterAsset both = decodeJson(
                "{ \"Role\": { \"Id\": \"Dragon_Fire\", \"Glob\": \"Dragon_*\" }, \"Abilities\": [] }",
                CasterRosterAsset.CODEC);
        CasterRoster bothRoster = both.toDomain();
        assertEquals("Dragon_Fire", bothRoster.roleId());
        assertEquals("Dragon_*", bothRoster.roleGlob());
        assertTrue(!bothRoster.hasValidRoleSelector(), "both authored is ALSO invalid (XOR), even though both values decode");
    }

    @Test
    void affixWithoutIconHasNoIcon() {
        Affix plain = new Affix("x", "", "", null, 1, 0, java.util.List.of("*"), 0, 0, 0, 0,
                Affix.KIND_STAT, null, false);
        assertTrue(!plain.hasIcon(), "the pre-icon convenience constructor yields no icon");
        assertNull(plain.iconItemId(), "no item id");
        assertNull(plain.iconTexturePath(), "no texture path");
    }

    @Test
    void decodesBehavioralAffixWithoutEffect() throws Exception {
        Affix a = decode("/Server/MmoMobScaling/Affixes/Vampiric.json", AffixAsset.CODEC).toAffix();
        assertEquals(Affix.KIND_BEHAVIORAL, a.kind(), "Vampiric is behavioral");
        assertEquals("vampiric", a.behaviorId(), "BehaviorId dispatches to the mod-side registry");
        assertTrue(a.allowsRarity("epic") && a.allowsRarity("legendary"), "gated to epic/legendary");
        assertTrue(!a.allowsRarity("rare"), "not allowed on rare");
    }

    @Test
    void keyedConfigFoldIsCaseInsensitive() {
        RarityConfig cfg = RarityConfig.getInstance();
        Rarity epic = new Rarity("Epic", "", 25, 25, 1.8, 1.5, 1.3, 2, "aura", java.util.List.of("*"));
        cfg.mergePackLayer(Map.of("Epic", epic));
        assertNotNull(cfg.resolve("epic"), "ids are lower-cased by the fold");
        assertEquals(1.8, cfg.resolve("EPIC").difficultyMultiplier(), 1e-9, "resolve is case-insensitive");

        AffixConfig acfg = AffixConfig.getInstance();
        Affix armored = new Affix("Armored", "", "", "eff", 3, 5, java.util.List.of("*"), 0, 0, 0, 0, Affix.KIND_STAT, null, true);
        acfg.mergePackLayer(Map.of("Armored", armored));
        assertNotNull(acfg.resolve("armored"), "affix fold lower-cases too");
    }

    private static <T extends JsonAsset<String>> T decode(String resource, AssetBuilderCodec<String, T> codec) throws Exception {
        try (InputStream in = MobScalingAssetCodecTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "resource on classpath: " + resource);
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return codec.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
        }
    }

    private static <T extends JsonAsset<String>> T decodeJson(String json, AssetBuilderCodec<String, T> codec)
            throws Exception {
        return codec.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Test
    void schemaDeclaresTheNeutralMultiplierDefaultAndTheClosedAggregationModes() {
        ObjectSchema multipliers = RarityAsset.Multipliers.CODEC.toSchema(new SchemaContext());
        NumberSchema loot = (NumberSchema) multipliers.getProperties().get("Loot");
        assertEquals(Double.valueOf(1.0), loot.getDefault(),
                "an absent multiplier leaf is the plain 1.0 baseline, and the exported schema must "
                        + "say so or the editor renders 0 and lies about the effective value");

        ObjectSchema openWorld = MobScalingSettingsAsset.OpenWorld.CODEC.toSchema(new SchemaContext());
        StringSchema mode = (StringSchema) openWorld.getProperties().get("AggregationMode");
        assertArrayEquals(new String[] {"SOLO", "AVERAGE", "PEAK", "WEIGHTED", "DISABLED"}, mode.getEnum(),
                "the five fold modes are the whole vocabulary, so the editor may offer a dropdown");
    }
}
