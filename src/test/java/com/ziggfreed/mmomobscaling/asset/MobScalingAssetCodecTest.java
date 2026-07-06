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
}
