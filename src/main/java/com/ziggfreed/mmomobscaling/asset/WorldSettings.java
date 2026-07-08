package com.ziggfreed.mmomobscaling.asset;

import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
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
 *   <li>{@code Match} - the world selector (exact name, trailing-{@code *} prefix, or bare
 *       {@code *}; resolved by the common {@code WorldNameMatcher}, exact > longest prefix > *).
 *       Blank/absent = a pool-only BASE (a {@code Parent} target, never matched).</li>
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
 *       {@code LateArrivalBumpFactor}, {@code CompositionEnabled}, {@code PlayerScalingEnabled}.
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
            // The world selector: an exact world name, a trailing-"*" prefix, or bare "*".
            // Blank/absent = a pool-only BASE other world files inherit from via Parent.
            .append(new KeyedCodec<>("Match", Codec.STRING, false),
                    (w, v) -> w.match = v, w -> w.match)
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

    @Nullable private String match;
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

    @Nullable public String getMatch() { return match; }
    @Nullable public Boolean getEnabled() { return enabled; }
    @Nullable public Double getIntensity() { return intensity; }
    @Nullable public Double getRaritySpawnChance() { return raritySpawnChance; }
    @Nullable public Difficulty getDifficulty() { return difficulty; }
    @Nullable public OpenWorld getOpenWorld() { return openWorld; }
    @Nullable public Hud getZoneHud() { return zoneHud; }
    @Nullable public InspectorHud getInspectorHud() { return inspectorHud; }
    @Nullable public Pool getPool() { return pool; }

    /** True when this body carries a non-blank {@code Match} (a matchable rule, not a pool-only base). */
    public boolean isMatchable() {
        return match != null && !match.isBlank();
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
