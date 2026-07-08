package com.ziggfreed.mmomobscaling.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.Difficulty;
import com.ziggfreed.mmomobscaling.asset.WorldSettings;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.world.DifficultyMapping;

/**
 * Value-sanity validation over the FOLDED rarity/affix content (jar + pack + owner), run once per
 * {@code LoadedAssetsEvent} fold by {@code MobScalingAssetRegistrar} and logged as warnings; a finding
 * never blocks the load (bad content degrades, it does not kill the mod). Pure logic (no engine types),
 * unit-tested. EXISTENCE checks that need live asset maps stay at their consumption sites on purpose
 * (an unresolvable {@code AuraEffectId}/affix {@code EffectId} warns once in the effect-apply system, an
 * unresolvable {@code BonusDropList} warns once in the loot-drop system) - this class covers the pure
 * value ranges + shape rules those sites cannot see.
 */
public final class ScalingContentValidator {

    private ScalingContentValidator() {
    }

    /** Validate folded rarities; one human-readable finding per violation (empty = clean). */
    @Nonnull
    public static List<String> validateRarities(@Nonnull Collection<Rarity> rarities) {
        List<String> findings = new ArrayList<>();
        for (Rarity r : rarities) {
            String at = "rarity '" + r.id() + "'";
            if (r.weight() < 0) {
                findings.add(at + ": Weight must be >= 0 (0 = not rollable, force-only)");
            }
            if (r.minDifficulty() < 0) {
                findings.add(at + ": MinDifficulty must be >= 0");
            }
            if (r.hpMult() <= 0) {
                findings.add(at + ": HpMult must be > 0");
            }
            if (r.outDamageMult() <= 0 || r.inDamageMult() <= 0) {
                findings.add(at + ": damage multipliers must be > 0");
            }
            if (r.lootMult() < 0 || r.xpMult() < 0) {
                findings.add(at + ": LootMult/XpMult must be >= 0");
            }
            if (r.affixSlots() < 0) {
                findings.add(at + ": AffixSlots must be >= 0");
            }
            // NameColor is hand-authored JSON consumed verbatim as a UI TextColor; a malformed value
            // fails silently in-game, so surface it here (absent/empty = the white fallback, fine).
            if (!r.nameColor().isBlank() && !r.nameColor().matches("#[0-9a-fA-F]{6}")) {
                findings.add(at + ": NameColor must be #rrggbb or absent (got '" + r.nameColor() + "')");
            }
            // A rollable tier (Weight > 0) whose family filter can NEVER match is dead content: it costs a
            // roster slot but can never be picked. Only the PURE self-contradictions are checkable here
            // (a "*" deny nukes everything; an id in both allow + deny is a dead allow entry since deny wins).
            // Whether a referenced NPCGroup id EXISTS needs the live asset map, so that check is warn-once in
            // MobFamilyMatcher at resolve time (mirrors the AuraEffectId/BonusDropList existence checks).
            if (r.weight() > 0) {
                findings.addAll(familyFilterFindings(at, r.familyFilter()));
            }
        }
        return findings;
    }

    /** Pure self-contradiction findings for a rarity's family filter (deny-all / allow-entry-also-denied). */
    @Nonnull
    private static List<String> familyFilterFindings(@Nonnull String at,
            @Nonnull com.ziggfreed.mmomobscaling.family.FamilyFilter filter) {
        List<String> findings = new ArrayList<>();
        if (filter.denyRoles().contains("*") || filter.denyGroups().contains("*")) {
            findings.add(at + ": Families deny list contains '*' - this tier can never roll (denies everything)");
        }
        for (String id : filter.allowGroups()) {
            if (filter.denyGroups().contains(id)) {
                findings.add(at + ": Families group '" + id + "' is in both AllowGroups and DenyGroups"
                        + " (deny wins, so the allow entry is dead)");
            }
        }
        for (String pattern : filter.allowRoles()) {
            if (filter.denyRoles().contains(pattern)) {
                findings.add(at + ": Families role pattern '" + pattern + "' is in both AllowRoles and DenyRoles"
                        + " (deny wins, so the allow entry is dead)");
            }
        }
        return findings;
    }

    /** Validate folded variants; one human-readable finding per violation (empty = clean). */
    @Nonnull
    public static List<String> validateVariants(
            @Nonnull Collection<com.ziggfreed.mmomobscaling.variant.Variant> variants) {
        List<String> findings = new ArrayList<>();
        for (com.ziggfreed.mmomobscaling.variant.Variant v : variants) {
            String at = "variant '" + v.id() + "'";
            if (v.chance() < 0 || v.chance() > 1) {
                findings.add(at + ": Chance must be in [0,1] (0 = not rollable)");
            }
            if (v.minDifficulty() < 0) {
                findings.add(at + ": MinDifficulty must be >= 0");
            }
            if (v.hpMult() <= 0) {
                findings.add(at + ": HpMult must be > 0");
            }
            if (v.outDamageMult() <= 0 || v.inDamageMult() <= 0) {
                findings.add(at + ": damage multipliers must be > 0");
            }
            if (v.lootMult() < 0 || v.xpMult() < 0) {
                findings.add(at + ": LootMult/XpMult must be >= 0");
            }
            if (v.affixSlots() < 0) {
                findings.add(at + ": AffixSlots must be >= 0");
            }
            if (!v.nameColor().isBlank() && !v.nameColor().matches("#[0-9a-fA-F]{6}")) {
                findings.add(at + ": NameColor must be #rrggbb or absent (got '" + v.nameColor() + "')");
            }
            // Same dead-filter self-contradiction check as rarities, for a rollable (Chance > 0) variant.
            if (v.chance() > 0) {
                findings.addAll(familyFilterFindings(at, v.familyFilter()));
                // An explicit empty AllowedRarities means "overlays no base rarity" - the variant can never
                // roll (absent defaults to ["*"], so only an authored [] hits this).
                if (v.allowedRarities().isEmpty()) {
                    findings.add(at + ": Roll.AllowedRarities is empty - this variant can never roll"
                            + " (use [\"*\"] for any base, or list the base rarities it may overlay)");
                }
            }
        }
        return findings;
    }

    /** Validate folded affixes; one human-readable finding per violation (empty = clean). */
    @Nonnull
    public static List<String> validateAffixes(@Nonnull Collection<Affix> affixes) {
        List<String> findings = new ArrayList<>();
        for (Affix a : affixes) {
            String at = "affix '" + a.id() + "'";
            if (a.spawnWeight() < 0) {
                findings.add(at + ": Weight must be >= 0");
            }
            if (a.minDifficulty() < 0) {
                findings.add(at + ": MinDifficulty must be >= 0");
            }
            boolean stat = Affix.KIND_STAT.equals(a.kind());
            boolean behavioral = Affix.KIND_BEHAVIORAL.equals(a.kind());
            boolean hybrid = Affix.KIND_HYBRID.equals(a.kind());
            if (!stat && !behavioral && !hybrid) {
                findings.add(at + ": unknown Kind '" + a.kind() + "' (STAT | BEHAVIORAL | HYBRID)");
            }
            // A pure-STAT affix with no native effect AND no fold deltas does literally nothing.
            if (stat && isBlank(a.effectId())
                    && a.hpDelta() == 0 && a.outDamageDelta() == 0 && a.inDamageDelta() == 0 && a.lootBonus() == 0) {
                findings.add(at + ": STAT affix with no EffectId and no fold deltas is a no-op");
            }
            // A behavioral/hybrid affix without a BehaviorId never dispatches its on-hit policy.
            if ((behavioral || hybrid) && isBlank(a.behaviorId())) {
                findings.add(at + ": " + a.kind() + " affix needs a BehaviorId to dispatch");
            }
        }
        return findings;
    }

    /**
     * Validate folded difficulty mappings; one human-readable finding per violation (empty = clean).
     * Native-name EXISTENCE cannot be checked statically (zone/biome names come from the live
     * worldgen), so this covers the pure value/shape rules; a mapping whose TargetId never matches
     * simply never fires.
     */
    @Nonnull
    public static List<String> validateDifficultyMappings(@Nonnull Collection<DifficultyMapping> mappings) {
        List<String> findings = new ArrayList<>();
        for (DifficultyMapping m : mappings) {
            String at = "difficulty mapping '" + m.id() + "'";
            if (m.floor() < 0) {
                findings.add(at + ": Floor must be >= 0");
            }
            if (m.targetId().isBlank()) {
                findings.add(at + ": TargetId must be a native zone/biome name or '*'");
            }
        }
        return findings;
    }

    /**
     * Cross-check this mod's difficulty caps against the MMO jar's PowerLevel clamp
     * ({@code MMOSkillTreeAPI.getPowerLevelMin()/Max()}, passed in by the caller so this
     * stays pure). The scaling fold subtracts {@code aggregatedPower - baseDifficulty}
     * directly, which is only calibrated when the two configs share one scale - a
     * retune of either side without the other silently miscalibrates every group
     * delta. Null power bounds (MMO clamp unreadable) validate as clean.
     */
    @Nonnull
    public static List<String> validateDifficultyCaps(double difficultyMinCap, double difficultyMaxCap,
                                                      @Nullable Double powerMin, @Nullable Double powerMax) {
        List<String> findings = new ArrayList<>();
        if (powerMax != null && Math.abs(difficultyMaxCap - powerMax) > 1e-9) {
            findings.add("Difficulty.MaxCap (" + difficultyMaxCap + ") != MMO PowerLevel Clamp.MaxPower ("
                    + powerMax + "): the power-minus-difficulty group delta miscalibrates;"
                    + " align mob-scaling.json Difficulty.MaxCap with the MMO's power-level.json Clamp.MaxPower");
        }
        if (powerMin != null && Math.abs(difficultyMinCap - powerMin) > 1e-9) {
            findings.add("Difficulty.MinCap (" + difficultyMinCap + ") != MMO PowerLevel Clamp.MinPower ("
                    + powerMin + "): the two scales should share a floor;"
                    + " align mob-scaling.json Difficulty.MinCap with the MMO's power-level.json Clamp.MinPower");
        }
        return findings;
    }

    /**
     * Validate one folded settings asset: the top-level {@code Intensity} multiplier ({@code >= 0})
     * and a DEPRECATION warning when the preset still carries the removed 1.0.1 inline
     * {@code WorldOverrides} array key is handled at decode (the codec no longer declares the key, so
     * the engine's unused-key warning fires). Empty = clean. Findings are prefixed with the preset
     * name so an admin can locate the offending file.
     */
    @Nonnull
    public static List<String> validateSettings(@Nonnull String presetName,
            @Nonnull MobScalingSettingsAsset asset) {
        List<String> findings = new ArrayList<>();
        String pfx = "preset '" + presetName + "' ";
        Double intensity = asset.getIntensity();
        if (intensity != null && intensity < 0) {
            findings.add(pfx + "Intensity must be >= 0 (got " + intensity + ")");
        }
        return findings;
    }

    /**
     * Validate the FOLDED per-world settings (1.0.2, {@code Worlds/*.json} across jar + pack + owner
     * dir, Parent-merged): a DUPLICATE {@code Match} across two ids (matcher precedence silently picks
     * one - ambiguous authoring), an authored {@code Parent} that resolved to nothing, negative
     * {@code Intensity}/{@code Floor}, an out-of-range {@code RaritySpawnChance}, an inverted
     * {@code Difficulty.MinCap > MaxCap}, and a pool id present in both {@code Allow} and {@code Deny}
     * (deny wins, the allow entry is dead). Pool id EXISTENCE stays at the roll sites (the rarity /
     * variant / affix stores fold on their own events, so a static cross-check would race the load).
     */
    @Nonnull
    public static List<String> validateWorldSettings(@Nonnull WorldSettingsConfig worlds) {
        List<String> findings = new ArrayList<>();
        Set<String> seenMatch = new HashSet<>();
        for (var e : worlds.foldedView().entrySet()) {
            String id = e.getKey();
            WorldSettings ws = e.getValue();
            String at = "world '" + id + "'";
            String parent = worlds.parentOf(id);
            if (parent != null && !worlds.foldedView().containsKey(parent.trim().toLowerCase(Locale.ROOT))) {
                findings.add(at + ": Parent '" + parent + "' not found (the file resolved standalone)");
            }
            String match = ws.getMatch();
            if (match != null && !match.isBlank()
                    && !seenMatch.add(match.trim().toLowerCase(Locale.ROOT))) {
                findings.add(at + ": duplicate Match '" + match + "' across world files"
                        + " (matcher precedence silently picks one)");
            }
            Double intensity = ws.getIntensity();
            if (intensity != null && intensity < 0) {
                findings.add(at + ": Intensity must be >= 0");
            }
            Double chance = ws.getRaritySpawnChance();
            if (chance != null && (chance < 0 || chance > 1)) {
                findings.add(at + ": RaritySpawnChance must be in [0,1]");
            }
            Difficulty d = ws.getDifficulty();
            if (d != null) {
                if (d.getFloor() != null && d.getFloor() < 0) {
                    findings.add(at + ": Difficulty.Floor must be >= 0");
                }
                if (d.getMinCap() != null && d.getMaxCap() != null && d.getMinCap() > d.getMaxCap()) {
                    findings.add(at + ": Difficulty.MinCap (" + d.getMinCap() + ") > MaxCap ("
                            + d.getMaxCap() + ")");
                }
            }
            WorldSettings.Pool pool = ws.getPool();
            if (pool != null) {
                findings.addAll(gateFindings(at + " Pool.Rarities", pool.getRarities()));
                findings.addAll(gateFindings(at + " Pool.Variants", pool.getVariants()));
                findings.addAll(gateFindings(at + " Pool.Affixes", pool.getAffixes()));
                WorldSettings.VariantGate vg = pool.getVariants();
                if (vg != null && vg.getChanceMultiplier() != null && vg.getChanceMultiplier() < 0) {
                    findings.add(at + ": Pool.Variants.ChanceMultiplier must be >= 0");
                }
                WorldSettings.AffixGate ag = pool.getAffixes();
                if (ag != null && ag.getExtraSlots() != null && ag.getExtraSlots() < 0) {
                    findings.add(at + ": Pool.Affixes.ExtraSlots must be >= 0");
                }
            }
        }
        return findings;
    }

    /** Dead-allow-entry findings for a pool gate: an id in both Allow and Deny can never roll (deny wins). */
    @Nonnull
    private static List<String> gateFindings(@Nonnull String at, @Nullable WorldSettings.IdGate gate) {
        List<String> findings = new ArrayList<>();
        if (gate == null || gate.getAllow() == null || gate.getDeny() == null) {
            return findings;
        }
        Set<String> deny = new HashSet<>();
        for (String s : gate.getDeny()) {
            if (s != null && !s.isBlank()) {
                deny.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
        for (String s : gate.getAllow()) {
            if (s != null && !s.isBlank() && deny.contains(s.trim().toLowerCase(Locale.ROOT))) {
                findings.add(at + ": id '" + s + "' is in both Allow and Deny (deny wins, the allow entry is dead)");
            }
        }
        return findings;
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
