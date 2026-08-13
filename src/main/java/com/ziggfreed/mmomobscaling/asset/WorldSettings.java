package com.ziggfreed.mmomobscaling.asset;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.world.WorldSelector;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.Difficulty;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.Hud;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.InspectorHud;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.OpenWorld;

/**
 * The ONE structured schema authority for a per-world settings body (1.0.2): what a
 * {@code Server/MmoMobScaling/Worlds/*.json} {@code Payload} (or an owner-dir
 * {@code mods/MmoMobScaling/worlds/*.json} bare body) decodes through AFTER
 * {@code JsonParentResolver} has merged its {@code Parent} chain. PascalCase, NESTED groups,
 * every leaf a NULLABLE wrapper at every level - an unset leaf falls through the {@code Parent}
 * chain and then to the GLOBAL effective settings (owner > preset > jar), so a file is a partial
 * overlay by default and a full custom definition when every leaf is authored.
 *
 * <p>Fields ({@code Parent} is stripped pre-decode by the resolver, never declared here):
 * <ul>
 *   <li>{@code Where} - which worlds this rule applies to, in the SHARED selector vocabulary
 *       ({@code Names} / {@code Match} / {@code GameplayConfig} / {@code ExcludeNames}), scored by
 *       {@code WorldSelector} on the one specificity ladder: an exact {@code GameplayConfig} beats
 *       an exact name, which beats the longest literal pattern core, which beats a bare {@code *}.
 *       An instance world is the reason {@code GameplayConfig} matters here - its NAME carries a
 *       fresh uuid per instantiation, its config key does not. ABSENT (or an empty group) = a
 *       pool-only BASE (a {@code Parent} target, never matched).</li>
 *   <li>{@code Enabled} - the per-world kill-switch (absorbs the removed hyMMO
 *       {@code WorldRules.MobScaling.Enabled}); {@code false} = no scaling in matching worlds.</li>
 *   <li>{@code Intensity} / {@code RaritySpawnChance} - the existing per-world dials.</li>
 *   <li>{@code Difficulty} - REUSES the settings {@link Difficulty} codec: {@code Floor} (the
 *       world-baseline floor absorbing the removed {@code WorldRules.MobScaling.DifficultyFloor};
 *       lowest precedence under the zone/biome {@code Difficulty/*.json} mappings), caps,
 *       {@code DistanceEscalation}, {@code StatCurve}.</li>
 *   <li>{@code OpenWorld} - REUSES the settings {@link OpenWorld} codec so the whole group is
 *       per-world (1.0.2): {@code AggregationMode}, {@code GroupDeltaBandWidth},
 *       {@code OnlyRaiseDifficulty}, {@code AllowDifficultyIncreaseOnPartyJoin},
 *       {@code LateArrivalBumpFactor}, {@code CompositionEnabled}, {@code PlayerScalingEnabled},
 *       {@code PlayerScalingStartRingBlocks}.
 *       {@code RegionSizeChunks} DECODES but is IGNORED per-world (the region grid must stay
 *       globally consistent).</li>
 *   <li>{@code ZoneHud} / {@code InspectorHud} - REUSE the settings HUD codecs; this cycle only
 *       {@code Enabled} is consumed per-world (hide a HUD inside an instance); position and the
 *       other leaves decode schema-ready but apply globally.</li>
 *   <li>{@code Pool} - the per-world rarity / variant / affix pool control (see {@link Pool}).</li>
 * </ul>
 */
public final class WorldSettings {

    public static final BuilderCodec<WorldSettings> CODEC = BuilderCodec
            .builder(WorldSettings.class, WorldSettings::new)
            // Which worlds this rule applies to, in the shared selector vocabulary. Absent (or an
            // empty group) = a pool-only BASE other world files inherit from via Parent.
            .append(new KeyedCodec<>("Where", WorldSelector.CODEC, false),
                    (w, v) -> w.where = v, w -> w.where)
            .documentation("Which worlds this rule applies to: Names (shared selector names), Match "
                    + "(world-name patterns), GameplayConfig (exact config keys) and ExcludeNames. "
                    + "The most specific match wins. Leave the whole group out to make the file a "
                    + "pool-only base that other files inherit from through Parent.")
            .add()
            // Per-world kill-switch: false = no mob scaling in matching worlds (residue is stripped).
            .append(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (w, v) -> w.enabled = v, w -> w.enabled)
            .add()
            // Per-world intensity multiplier on the stat-curve slopes (overrides the global).
            .append(new KeyedCodec<>("Intensity", Codec.DOUBLE, false),
                    (w, v) -> w.intensity = v, w -> w.intensity)
            .add()
            // Per-world rarity spawn chance (overrides the global; clamped [0,1] at resolve).
            .append(new KeyedCodec<>("RaritySpawnChance", Codec.DOUBLE, false),
                    (w, v) -> w.raritySpawnChance = v, w -> w.raritySpawnChance)
            .add()
            // Per-world difficulty group: Floor (world baseline) + caps + escalation + stat curve.
            .append(new KeyedCodec<>("Difficulty", Difficulty.CODEC, false),
                    (w, v) -> w.difficulty = v, w -> w.difficulty)
            .add()
            // Per-world open-world group (RegionSizeChunks decodes but stays global).
            .append(new KeyedCodec<>("OpenWorld", OpenWorld.CODEC, false),
                    (w, v) -> w.openWorld = v, w -> w.openWorld)
            .add()
            // Per-world zone-difficulty HUD overlay (Enabled consumed per-world this cycle).
            .append(new KeyedCodec<>("ZoneHud", Hud.CODEC, false),
                    (w, v) -> w.zoneHud = v, w -> w.zoneHud)
            .add()
            // Per-world mob-inspector HUD overlay (Enabled consumed per-world this cycle).
            .append(new KeyedCodec<>("InspectorHud", InspectorHud.CODEC, false),
                    (w, v) -> w.inspectorHud = v, w -> w.inspectorHud)
            .add()
            // Per-world rarity/variant/affix pool control.
            .append(new KeyedCodec<>("Pool", Pool.CODEC, false),
                    (w, v) -> w.pool = v, w -> w.pool)
            .add()
            .build();

    @Nullable private WorldSelector where;
    @Nullable private Boolean enabled;
    @Nullable private Double intensity;
    @Nullable private Double raritySpawnChance;
    @Nullable private Difficulty difficulty;
    @Nullable private OpenWorld openWorld;
    @Nullable private Hud zoneHud;
    @Nullable private InspectorHud inspectorHud;
    @Nullable private Pool pool;

    public WorldSettings() {
    }

    @Nullable public WorldSelector getWhere() { return where; }
    @Nullable public Boolean getEnabled() { return enabled; }
    @Nullable public Double getIntensity() { return intensity; }
    @Nullable public Double getRaritySpawnChance() { return raritySpawnChance; }
    @Nullable public Difficulty getDifficulty() { return difficulty; }
    @Nullable public OpenWorld getOpenWorld() { return openWorld; }
    @Nullable public Hud getZoneHud() { return zoneHud; }
    @Nullable public InspectorHud getInspectorHud() { return inspectorHud; }
    @Nullable public Pool getPool() { return pool; }

    /**
     * True when this body says which worlds it applies to, so it is a RULE rather than a pool-only
     * base. The test is deliberately {@link WorldSelector#isBlank()} rather than a null check: an
     * author who writes {@code "Where": {}} while restructuring a file means the same thing as one
     * who left the group out, and an empty selector can never match anything anyway, so treating
     * the two differently would only produce a rule that silently applies nowhere.
     */
    public boolean isMatchable() {
        return where != null && !where.isBlank();
    }

    /**
     * This body's selector, or an EMPTY one for a pool-only base - so a caller can score it without
     * a null check, since an empty selector matches no world.
     */
    @Nonnull
    public WorldSelector selector() {
        WorldSelector authored = where;
        return authored == null ? WorldSelector.of(null, null, null, null) : authored;
    }

    /**
     * The first authored {@code Where.Match} pattern, or {@code null}. The admin form edits ONE
     * pattern, so this is what it seeds from; a rule authored with several patterns keeps them all
     * on disk and only the first is shown, which is why the form writes back rather than replacing
     * a whole selector.
     */
    @Nullable
    public String firstMatchPattern() {
        return first(where == null ? null : where.getMatch());
    }

    /**
     * A short, literal one-line rendering of {@code Where} for a listing: the first authored value
     * from whichever axes are present, each labelled by its axis so a name is never mistaken for a
     * pattern. {@code null} for a pool-only base, which a caller renders in its own words.
     */
    @Nullable
    public String whereSummary() {
        WorldSelector selector = where;
        if (selector == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        appendAxis(sb, "config", first(selector.getGameplayConfig()));
        appendAxis(sb, "name", first(selector.getNames()));
        appendAxis(sb, null, first(selector.getMatch()));
        appendAxis(sb, "not", first(selector.getExcludeNames()));
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void appendAxis(@Nonnull StringBuilder sb, @Nullable String label,
            @Nullable String value) {
        if (value == null) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        if (label != null) {
            sb.append(label).append(':');
        }
        sb.append(value);
    }

    @Nullable
    private static String first(@Nullable String[] values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * Per-world rarity / variant / affix pool control - one cohesive nested group of three
     * sub-groups (the schema-design rule: a new pool knob lands INSIDE its axis group).
     * Allow/deny are id lists (deny WINS over allow; an absent/empty allow = allow-all); the id
     * lists REPLACE wholesale on {@code Parent} inherit (child list wins whole). Deliberately NOT
     * weight-maps - weights belong in the per-rarity/variant/affix assets; the per-world dials are
     * gates + a variant chance scale + extra affix slots.
     */
    public static final class Pool {
        public static final BuilderCodec<Pool> CODEC = BuilderCodec.builder(Pool.class, Pool::new)
                // Which rarity tiers may roll in this world (e.g. an Elite+-only dungeon).
                .append(new KeyedCodec<>("Rarities", IdGate.CODEC, false),
                        (p, v) -> p.rarities = v, p -> p.rarities)
                .add()
                // Which variant overlays may roll + a per-world scale on their absolute chances.
                .append(new KeyedCodec<>("Variants", VariantGate.CODEC, false),
                        (p, v) -> p.variants = v, p -> p.variants)
                .add()
                // Which affixes may roll + extra per-world affix slots.
                .append(new KeyedCodec<>("Affixes", AffixGate.CODEC, false),
                        (p, v) -> p.affixes = v, p -> p.affixes)
                .add()
                .build();

        @Nullable private IdGate rarities;
        @Nullable private VariantGate variants;
        @Nullable private AffixGate affixes;

        @Nullable public IdGate getRarities() { return rarities; }
        @Nullable public VariantGate getVariants() { return variants; }
        @Nullable public AffixGate getAffixes() { return affixes; }
    }

    /** An id allow/deny gate: {@code Deny} wins over {@code Allow}; an absent/empty allow = allow-all. */
    public static class IdGate {
        public static final BuilderCodec<IdGate> CODEC = BuilderCodec.builder(IdGate.class, IdGate::new)
                .append(new KeyedCodec<>("Allow", Codec.STRING_ARRAY, false),
                        (g, v) -> g.allow = v, g -> g.allow)
                .add()
                .append(new KeyedCodec<>("Deny", Codec.STRING_ARRAY, false),
                        (g, v) -> g.deny = v, g -> g.deny)
                .add()
                .build();

        @Nullable protected String[] allow;
        @Nullable protected String[] deny;

        @Nullable public String[] getAllow() { return allow; }
        @Nullable public String[] getDeny() { return deny; }
    }

    /** The variant gate: allow/deny plus a per-world multiplier on the variants' absolute chances. */
    public static final class VariantGate extends IdGate {
        public static final BuilderCodec<VariantGate> CODEC = BuilderCodec
                .builder(VariantGate.class, VariantGate::new)
                .append(new KeyedCodec<>("Allow", Codec.STRING_ARRAY, false),
                        (g, v) -> g.allow = v, g -> g.allow)
                .add()
                .append(new KeyedCodec<>("Deny", Codec.STRING_ARRAY, false),
                        (g, v) -> g.deny = v, g -> g.deny)
                .add()
                // Scales every eligible variant's absolute roll chance in this world (>= 0; 1.0 neutral).
                .append(new KeyedCodec<>("ChanceMultiplier", Codec.DOUBLE, false),
                        (g, v) -> g.chanceMultiplier = v, g -> g.chanceMultiplier)
                .add()
                .build();

        @Nullable private Double chanceMultiplier;

        @Nullable public Double getChanceMultiplier() { return chanceMultiplier; }
    }

    /** The affix gate: allow/deny plus extra per-world affix slots stacked on the rarity/variant slots. */
    public static final class AffixGate extends IdGate {
        public static final BuilderCodec<AffixGate> CODEC = BuilderCodec
                .builder(AffixGate.class, AffixGate::new)
                .append(new KeyedCodec<>("Allow", Codec.STRING_ARRAY, false),
                        (g, v) -> g.allow = v, g -> g.allow)
                .add()
                .append(new KeyedCodec<>("Deny", Codec.STRING_ARRAY, false),
                        (g, v) -> g.deny = v, g -> g.deny)
                .add()
                // Additive extra affix slots rolled in this world (>= 0), on top of rarity/variant slots.
                .append(new KeyedCodec<>("ExtraSlots", Codec.INTEGER, false),
                        (g, v) -> g.extraSlots = v, g -> g.extraSlots)
                .add()
                .build();

        @Nullable private Integer extraSlots;

        @Nullable public Integer getExtraSlots() { return extraSlots; }
    }
}
