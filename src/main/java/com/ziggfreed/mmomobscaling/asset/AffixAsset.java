package com.ziggfreed.mmomobscaling.asset;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.affix.AffixCry;

import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.icon.IconSpec;

/**
 * A pack-authorable mob AFFIX, loaded from {@code Server/MmoMobScaling/Affixes/*.json}. Pattern A - the
 * {@link #CODEC} decodes directly into typed fields (PascalCase keys, guarded by the mod's
 * {@code AssetCodecInitTest}), mirroring {@link RarityAsset}. An affix's stat magnitude is authored in the
 * referenced native {@code EntityEffect} ({@code EffectId}); this asset holds only mob-scaling POLICY (gating,
 * the pipeline deltas with no native path, kind + behavior dispatch, a war cry).
 *
 * <p><b>Cohesive field groups are NESTED sub-objects</b> (the schema-design rule): the roll gate is
 * {@code Roll} (weight + band + rarity allow-list, the same group shape as {@code RarityAsset.Roll}),
 * the pipeline fold contributions are {@code FoldDeltas}, a war cry is {@code Cry}.
 *
 * <p><b>Every leaf is inherited</b>, the groups' leaves included: a file's {@code Parent}, and the server owner's
 * entry in {@code mods/MmoMobScaling/affixes.json} ({@code config/AffixOwnerLayer}), write only what they change and
 * keep the rest of the affix they build on, leaf by leaf.
 *
 * <p>Pack JSON shape (all fields optional; absent = the documented default):
 * <pre>{@code
 * { "Name": "Armored", "DisplayNameKey": "mmomobscaling.affix.armored.name",
 *   "DescriptionKey": "mmomobscaling.affix.armored.desc",
 *   "EffectId": "Mmoscaling_Affix_Armored",
 *   "Roll": { "Weight": 3.0, "MinDifficulty": 5, "AllowedRarities": ["*"] },
 *   "FoldDeltas": { "ResistancePercent": 0.15 },
 *   "Kind": "STAT", "ResistanceBearing": true,
 *   "Icon": { "ItemId": "Armor_Bronze_Chest" } }
 * }</pre>
 *
 * <p>That is the shipped Armored exactly: its toughness is the effect's own resistance, so it declares
 * only the mirror. {@code FoldDeltas} takes any of {@code Hp}, {@code OutDamage}, {@code InDamage},
 * {@code LootBonus} and {@code ResistancePercent}; an absent leaf contributes nothing.
 *
 * <p>The optional {@code Icon} is the shared {@link IconSpec} (an {@code ItemId} rendered as that item's
 * generated icon, OR a Common-rooted {@code TexturePath} such as {@code "UI/StatusEffects/Stamina.png"});
 * the inspector HUD renders it as the affix chip's leading glyph, absent = the chip shows its label only.
 *
 * <p>The optional {@code Cry} ({@link Cry}) gives the mob a telegraphed war cry that rallies the creatures around it,
 * the shape of the game's goblin scrapper's pack enrage; the shipped Rallying affix is one, and a pack adds another
 * by a file.
 */
public final class AffixAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, AffixAsset>> {

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String displayNameKey;
    @Nullable private String descriptionKey;
    @Nullable private String effectId;
    @Nullable private Roll roll;
    @Nullable private FoldDeltas foldDeltas;
    @Nullable private String kind;
    @Nullable private String behaviorId;
    @Nullable private Boolean resistanceBearing;
    @Nullable private IconSpec icon;
    @Nullable private Cry cry;

    public static final AssetBuilderCodec<String, AffixAsset> CODEC = AssetBuilderCodec.builder(
                    AffixAsset.class,
                    AffixAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* no-op - id comes from the filename */ },
                    a -> a.id)
            .add()
            .appendInherited(new KeyedCodec<>("DisplayNameKey", Codec.STRING, false),
                    (a, v) -> a.displayNameKey = v, a -> a.displayNameKey, (a, p) -> a.displayNameKey = p.displayNameKey)
            .add()
            .appendInherited(new KeyedCodec<>("DescriptionKey", Codec.STRING, false),
                    (a, v) -> a.descriptionKey = v, a -> a.descriptionKey, (a, p) -> a.descriptionKey = p.descriptionKey)
            .add()
            .appendInherited(new KeyedCodec<>("EffectId", Codec.STRING, false),
                    (a, v) -> a.effectId = v, a -> a.effectId, (a, p) -> a.effectId = p.effectId)
            .add()
            // The roll gate: pick weight, difficulty band, and which rarities may draw this affix.
            .appendInherited(new KeyedCodec<>("Roll", Roll.CODEC, false),
                    (a, v) -> a.roll = v, a -> a.roll, (a, p) -> a.roll = p.roll)
            .add()
            // Additive pipeline-fold contributions (ONLY the axes with no native effect path).
            .appendInherited(new KeyedCodec<>("FoldDeltas", FoldDeltas.CODEC, false),
                    (a, v) -> a.foldDeltas = v, a -> a.foldDeltas, (a, p) -> a.foldDeltas = p.foldDeltas)
            .add()
            .appendInherited(new KeyedCodec<>("Kind", Codec.STRING, false),
                    (a, v) -> a.kind = v, a -> a.kind, (a, p) -> a.kind = p.kind)
            .add()
            .appendInherited(new KeyedCodec<>("BehaviorId", Codec.STRING, false),
                    (a, v) -> a.behaviorId = v, a -> a.behaviorId, (a, p) -> a.behaviorId = p.behaviorId)
            .add()
            .appendInherited(new KeyedCodec<>("ResistanceBearing", Codec.BOOLEAN, false),
                    (a, v) -> a.resistanceBearing = v, a -> a.resistanceBearing,
                    (a, p) -> a.resistanceBearing = p.resistanceBearing)
            .add()
            // Optional inspector-HUD chip icon (shared IconSpec: an item id or a Common-rooted texture path).
            .appendInherited(new KeyedCodec<>("Icon", IconSpec.CODEC, false),
                    (a, v) -> a.icon = v, a -> a.icon, (a, p) -> a.icon = p.icon)
            .add()
            // Optional war cry: a telegraphed cry that rallies the creatures around the mob for a while.
            .appendInherited(new KeyedCodec<>("Cry", Cry.CODEC, false),
                    (a, v) -> a.cry = v, a -> a.cry, (a, p) -> a.cry = p.cry)
            .documentation("A war cry this affix gives its mob, the shape of the game's goblin scrapper's pack enrage:"
                    + " once the mob turns on a target it stands and cries, then buffs itself and every creature near"
                    + " it that is hostile to its target for a while, and cries again some time later while it still"
                    + " fights. Leave it out for an affix that does not cry.")
            .add()
            .build();

    public AffixAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Build the runtime {@link Affix} (the map key is the id). Absent groups/leaves take the neutral
     * defaults (weight 1, no band gate, zero deltas). Absent {@code Roll.AllowedRarities} ->
     * {@code ["*"]} (all); absent {@code Kind} -> {@code STAT}; absent display/description keys stay
     * {@code ""} so the text util falls back to a convention key.
     */
    @Nonnull
    public Affix toAffix() {
        return toAffix(id);
    }

    /**
     * {@link #toAffix()} under {@code spelledId}: the fold keeps a pack file's own spelling of the id for an affix the
     * server owner's file retunes, whose entry is filed under the lower-cased key.
     */
    @Nonnull
    public Affix toAffix(@Nonnull String spelledId) {
        double weight = roll != null && roll.weight != null ? roll.weight : 1.0;
        double minDifficulty = roll != null && roll.minDifficulty != null ? roll.minDifficulty : 0.0;
        List<String> allowed = roll != null && roll.allowedRarities != null
                ? List.of(roll.allowedRarities) : List.of("*");
        // AllowedVariants defaults to EMPTY (an affix is variant-granted only when it opts in), NOT ["*"] -
        // otherwise every rarity affix would leak onto every variant's slots.
        List<String> allowedVariants = roll != null && roll.allowedVariants != null
                ? List.of(roll.allowedVariants) : List.of();
        double outDelta = delta(foldDeltas != null ? foldDeltas.outDamage : null);
        double inDelta = delta(foldDeltas != null ? foldDeltas.inDamage : null);
        double hpDelta = delta(foldDeltas != null ? foldDeltas.hp : null);
        double lootBonus = delta(foldDeltas != null ? foldDeltas.lootBonus : null);
        double resistancePercent = delta(foldDeltas != null ? foldDeltas.resistancePercent : null);
        String nameKey = displayNameKey != null ? displayNameKey : "";
        String descKey = descriptionKey != null ? descriptionKey : "";
        String k = kind != null ? kind : Affix.KIND_STAT;
        String iconItemId = icon != null ? icon.itemId() : null;
        String iconTexturePath = icon != null ? icon.texturePath() : null;
        return new Affix(spelledId, nameKey, descKey, effectId, weight, minDifficulty, allowed, allowedVariants,
                outDelta, inDelta, hpDelta, lootBonus, resistancePercent, k, behaviorId,
                resistanceBearing != null && resistanceBearing, iconItemId, iconTexturePath,
                cry != null ? cry.toCry() : null);
    }

    private static double delta(@Nullable Double v) {
        return v != null ? v : 0.0;
    }

    /** The roll gate: pick weight + difficulty band + the rarity allow-list ({@code ["*"]} = any). */
    public static final class Roll {
        public static final BuilderCodec<Roll> CODEC = BuilderCodec.builder(Roll.class, Roll::new)
                .appendInherited(new KeyedCodec<>("Weight", Codec.DOUBLE, false),
                        (r, v) -> r.weight = v, r -> r.weight, (r, p) -> r.weight = p.weight)
                .documentation("How often this affix is picked for a slot against the other affixes that may take"
                        + " it there: an affix at 2 is picked twice as often as one at 1. 0 never picks it.")
                .add()
                .appendInherited(new KeyedCodec<>("MinDifficulty", Codec.DOUBLE, false),
                        (r, v) -> r.minDifficulty = v, r -> r.minDifficulty, (r, p) -> r.minDifficulty = p.minDifficulty)
                .documentation("The lowest spot difficulty this affix may roll at.")
                .add()
                .appendInherited(new KeyedCodec<>("AllowedRarities", Codec.STRING_ARRAY, false),
                        (r, v) -> r.allowedRarities = v, r -> r.allowedRarities,
                        (r, p) -> r.allowedRarities = p.allowedRarities)
                .add()
                // Which VARIANTS may grant this affix (absent/empty = none; a variant-exclusive affix like
                // 'venomous' lists its variant here, mirroring AllowedRarities for the rarity axis).
                .appendInherited(new KeyedCodec<>("AllowedVariants", Codec.STRING_ARRAY, false),
                        (r, v) -> r.allowedVariants = v, r -> r.allowedVariants,
                        (r, p) -> r.allowedVariants = p.allowedVariants)
                .add()
                .build();

        @Nullable private Double weight;
        @Nullable private Double minDifficulty;
        @Nullable private String[] allowedRarities;
        @Nullable private String[] allowedVariants;
    }

    /**
     * The affix's contributions to the frozen spawn result (each absent leaf = 0.0, no contribution). The
     * four deltas are fractions the fold applies as {@code 1 + sum} over the difficulty curve; the fifth,
     * {@code ResistancePercent}, is the DECLARED mirror of the native effect's percent resistance, read by
     * the effective-HP rail alone.
     */
    public static final class FoldDeltas {
        public static final BuilderCodec<FoldDeltas> CODEC = BuilderCodec
                .builder(FoldDeltas.class, FoldDeltas::new)
                .appendInherited(new KeyedCodec<>("Hp", Codec.DOUBLE, false),
                        (d, v) -> d.hp = v, d -> d.hp, (d, p) -> d.hp = p.hp)
                .documentation("Extra health as a fraction of the curve's: 0.15 is fifteen percent more.")
                .add()
                .appendInherited(new KeyedCodec<>("OutDamage", Codec.DOUBLE, false),
                        (d, v) -> d.outDamage = v, d -> d.outDamage, (d, p) -> d.outDamage = p.outDamage)
                .documentation("Extra damage dealt as a fraction of the curve's: 0.2 is twenty percent more.")
                .add()
                .appendInherited(new KeyedCodec<>("InDamage", Codec.DOUBLE, false),
                        (d, v) -> d.inDamage = v, d -> d.inDamage, (d, p) -> d.inDamage = p.inDamage)
                .documentation("A change to the damage taken as a fraction of the curve's: -0.1 is ten"
                        + " percent less taken, 0.1 ten percent more.")
                .add()
                .appendInherited(new KeyedCodec<>("LootBonus", Codec.DOUBLE, false),
                        (d, v) -> d.lootBonus = v, d -> d.lootBonus, (d, p) -> d.lootBonus = p.lootBonus)
                .documentation("Extra loot passes as a fraction: 0.5 rolls the death loot half a pass more.")
                .add()
                .appendInherited(new KeyedCodec<>("ResistancePercent", Codec.DOUBLE, false),
                        (d, v) -> d.resistancePercent = v, d -> d.resistancePercent,
                        (d, p) -> d.resistancePercent = p.resistancePercent)
                .documentation("The percent damage resistance the affix's own EntityEffect grants, stated"
                        + " again here as a fraction (0.15 for fifteen percent) so the effective-HP rail can"
                        + " count it; the fold cannot read the effect file. The rail treats this as the share"
                        + " of every hit the effect turns away, and when the mob's visible health over what"
                        + " gets through would exceed MaxEffectiveHpMult it raises the mob's damage taken, for"
                        + " every cause at once. So a value LARGER than the effect really grants makes the mob"
                        + " take more damage from everything the effect does not resist, and a smaller one"
                        + " lets the mob sit past the rail. Keep it equal to the effect's DamageResistance"
                        + " Percent Amount: the content audit warns when the two drift.")
                .add()
                .build();

        @Nullable private Double hp;
        @Nullable private Double outDamage;
        @Nullable private Double inDamage;
        @Nullable private Double lootBonus;
        @Nullable private Double resistancePercent;
    }

    /**
     * A war cry ({@link AffixCry}), every leaf inherited: the wind-up and its telegraph effect and sound, the buff it
     * puts on, how long and how far, and how soon it cries again. An absent leaf reads 0 or none, so a cry with no
     * {@code BuffEffectId} or no {@code BuffSeconds} rallies no one (the content audit names it).
     */
    public static final class Cry {
        public static final BuilderCodec<Cry> CODEC = BuilderCodec.builder(Cry.class, Cry::new)
                .appendInherited(new KeyedCodec<>("WindupSeconds", Codec.DOUBLE, false),
                        (c, v) -> c.windupSeconds = v, c -> c.windupSeconds, (c, p) -> c.windupSeconds = p.windupSeconds)
                .metadata(EditorSchema.defaultValue(0.0))
                .documentation("How long the mob stands and cries before the buff lands, in seconds: the warning"
                        + " players get to step back or strike first. 0 lands it at once.")
                .add()
                .appendInherited(new KeyedCodec<>("CastEffectId", Codec.STRING, false),
                        (c, v) -> c.castEffectId = v, c -> c.castEffectId, (c, p) -> c.castEffectId = p.castEffectId)
                .metadata(EditorSchema.assetRef(EntityEffect.class))
                .documentation("The EntityEffect the mob wears while it cries, for WindupSeconds: what players see"
                        + " coming (the shipped one roots the mob and shows a mark over its head). Leave it out for"
                        + " none.")
                .add()
                .appendInherited(new KeyedCodec<>("SoundEventId", Codec.STRING, false),
                        (c, v) -> c.soundEventId = v, c -> c.soundEventId, (c, p) -> c.soundEventId = p.soundEventId)
                .metadata(EditorSchema.assetRef("SoundEvent"))
                .documentation("The SoundEvent played where the mob stands as the cry begins. Leave it out for a"
                        + " silent cry.")
                .add()
                .appendInherited(new KeyedCodec<>("BuffEffectId", Codec.STRING, false),
                        (c, v) -> c.buffEffectId = v, c -> c.buffEffectId, (c, p) -> c.buffEffectId = p.buffEffectId)
                .metadata(EditorSchema.assetRef(EntityEffect.class))
                .documentation("The EntityEffect the cry puts on the mob and on every creature within Radius that is"
                        + " hostile to the mob's target. What it does (faster, harder hits) is the effect's own;"
                        + " how long it lasts is BuffSeconds, whatever the effect's own Duration says.")
                .add()
                .appendInherited(new KeyedCodec<>("BuffSeconds", Codec.DOUBLE, false),
                        (c, v) -> c.buffSeconds = v, c -> c.buffSeconds, (c, p) -> c.buffSeconds = p.buffSeconds)
                .metadata(EditorSchema.defaultValue(0.0))
                .documentation("How long the buff lasts, in seconds: the cry's length. The game's pack enrage lasts"
                        + " 8.")
                .add()
                .appendInherited(new KeyedCodec<>("Radius", Codec.DOUBLE, false),
                        (c, v) -> c.radius = v, c -> c.radius, (c, p) -> c.radius = p.radius)
                .metadata(EditorSchema.defaultValue(0.0))
                .documentation("How far from the crying mob, in blocks, a creature is rallied. At 0 only the mob"
                        + " itself is.")
                .add()
                .appendInherited(new KeyedCodec<>("RepeatMinSeconds", Codec.DOUBLE, false),
                        (c, v) -> c.repeatMinSeconds = v, c -> c.repeatMinSeconds,
                        (c, p) -> c.repeatMinSeconds = p.repeatMinSeconds)
                .metadata(EditorSchema.defaultValue(0.0))
                .documentation("The shortest wait after a cry before the mob cries again while it still fights, in"
                        + " seconds. The first cry comes as soon as it turns on a target.")
                .add()
                .appendInherited(new KeyedCodec<>("RepeatMaxSeconds", Codec.DOUBLE, false),
                        (c, v) -> c.repeatMaxSeconds = v, c -> c.repeatMaxSeconds,
                        (c, p) -> c.repeatMaxSeconds = p.repeatMaxSeconds)
                .metadata(EditorSchema.defaultValue(0.0))
                .documentation("The longest wait after a cry before the next; each wait is drawn between the two. A"
                        + " value under RepeatMinSeconds reads as RepeatMinSeconds.")
                .add()
                .build();

        @Nullable private Double windupSeconds;
        @Nullable private String castEffectId;
        @Nullable private String soundEventId;
        @Nullable private String buffEffectId;
        @Nullable private Double buffSeconds;
        @Nullable private Double radius;
        @Nullable private Double repeatMinSeconds;
        @Nullable private Double repeatMaxSeconds;

        @Nonnull
        AffixCry toCry() {
            return new AffixCry(delta(windupSeconds), castEffectId, soundEventId, buffEffectId, delta(buffSeconds),
                    delta(radius), delta(repeatMinSeconds), delta(repeatMaxSeconds));
        }
    }
}
