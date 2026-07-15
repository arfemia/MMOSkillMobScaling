package com.ziggfreed.mmomobscaling.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.JsonAsset;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.caster.CasterEntry;
import com.ziggfreed.mmomobscaling.caster.CasterRoster;
import com.ziggfreed.mmomobscaling.config.AffixConfig;
import com.ziggfreed.mmomobscaling.config.RarityConfig;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.variant.Variant;
import com.ziggfreed.mmomobscaling.world.DifficultyMapping;

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
        com.google.gson.JsonObject body = asset.getPayloadAsJsonObject();
        assertNotNull(body, "raw Payload survives for the pre-merge");
        // Dungeon of Fear I ships as a flat, self-contained file (no Parent): it simply turns
        // open-world mob scaling OFF in its instance worlds.
        WorldSettings ws = WorldSettings.CODEC.decodeJson(
                RawJsonReader.fromJsonString(body.toString()), new ExtraInfo());
        assertEquals("instance-dungeon_of_fear_i*", ws.getMatch(), "Match");
        assertEquals(Boolean.FALSE, ws.getEnabled(), "Enabled kill-switch off");
    }

    @Test
    void decodesShippedZoneMapping() throws Exception {
        DifficultyMappingAsset asset = decode("/Server/MmoMobScaling/Difficulty/Zone2.json",
                DifficultyMappingAsset.CODEC);
        DifficultyMapping m = asset.toMapping("zone2");
        assertNotNull(m, "shipped mapping resolves");
        assertEquals(DifficultyMapping.TargetType.ZONE, m.targetType(), "TargetType");
        assertTrue(m.matches("Zone2") && m.matches("zone2"), "TargetId matches case-insensitively");
        assertEquals(22.0, m.floor(), 1e-9, "Floor");
    }

    @Test
    void malformedMappingResolvesNull() {
        DifficultyMappingAsset blank = new DifficultyMappingAsset();
        assertTrue(blank.toMapping("broken") == null, "unknown TargetType/blank TargetId folds to null (skipped)");
    }

    @Test
    void decodesShippedEpicRarity() throws Exception {
        Rarity r = decode("/Server/MmoMobScaling/Rarities/Epic.json", RarityAsset.CODEC).toRarity();
        assertEquals(2.2, r.hpMult(), 1e-9, "HpMult");
        assertEquals(25.0, r.weight(), 1e-9, "Weight");
        assertEquals(25.0, r.minDifficulty(), 1e-9, "MinDifficulty");
        assertEquals(2, r.affixSlots(), "AffixSlots");
        assertEquals("Mmoscaling_Aura_Epic", r.auraEffectId(), "AuraEffectId");
        assertEquals("Mmoscaling_Drops_Epic", r.bonusDropListId(), "BonusDropList");
        assertEquals("scaling.rarity.epic.name", r.displayNameKey(), "DisplayNameKey");
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
        assertEquals(1.5, v.hpMult(), 1e-9, "Multipliers.Hp");
        assertTrue(v.allowsAffix("venomous"), "the variant grants its unique affix");
        assertTrue(!v.familyFilter().isUnrestricted(), "the Families block makes it restricted");
        assertTrue(v.familyFilter().allowGroups().contains("Spiders"), "AllowGroups decoded");
        assertTrue(v.familyFilter().allowRoles().contains("Spider*"), "AllowRoles decoded");
        assertEquals("scaling.variant.horrific.name", v.displayNameKey(), "DisplayNameKey");
        assertEquals("Mmoscaling_Aura_Horrific", v.auraEffectId(), "AuraEffectId (fallback tint)");
        assertEquals("Mmoscaling_Drops_Horrific", v.bonusDropListId(), "BonusDropList (stacks on rarity drops)");
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
    void rarityWithoutNameColorFallsBackToWhite() {
        Rarity plain = new Rarity("test", "", 1, 0, 1, 1, 1, 1, 1, 0, null, null, java.util.List.of("*"));
        assertEquals("", plain.nameColor(), "convenience constructor leaves NameColor empty");
        assertEquals(Rarity.DEFAULT_NAME_COLOR, plain.displayColor(), "empty NameColor renders white");
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
        Rarity epic = new Rarity("Epic", "", 25, 25, 2.2, 1.9, 0.7, 1.5, 1.3, 2, "aura", null, java.util.List.of("*"));
        cfg.mergePackLayer(Map.of("Epic", epic));
        assertNotNull(cfg.resolve("epic"), "ids are lower-cased by the fold");
        assertEquals(2.2, cfg.resolve("EPIC").hpMult(), 1e-9, "resolve is case-insensitive");

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
}
