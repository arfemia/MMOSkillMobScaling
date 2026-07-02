package com.ziggfreed.mmomobscaling.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertNotNull(AffixAsset.CODEC, "AffixAsset.CODEC static-init (PascalCase key guard)");
        assertNotNull(MobScalingSettingsAsset.CODEC, "MobScalingSettingsAsset.CODEC static-init");
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
    }

    @Test
    void decodesShippedArmoredAffix() throws Exception {
        Affix a = decode("/Server/MmoMobScaling/Affixes/Armored.json", AffixAsset.CODEC).toAffix();
        assertEquals("Mmoscaling_Affix_Armored", a.effectId(), "EffectId");
        assertEquals(Affix.KIND_STAT, a.kind(), "Kind");
        assertTrue(a.resistanceBearing(), "ResistanceBearing");
        assertEquals(0.0, a.inDamageDelta(), 1e-9, "mitigation is native (no pipeline delta)");
        assertTrue(a.allowsRarity("legendary"), "wildcard AllowedRarities allows any rarity");
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
