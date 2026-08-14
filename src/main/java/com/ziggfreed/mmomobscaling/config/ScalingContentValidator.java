package com.ziggfreed.mmomobscaling.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.loot.Roll;
import com.ziggfreed.common.match.NamePattern.Kind;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.world.MatchRank;
import com.ziggfreed.common.world.WorldNameMatcher.Pattern;
import com.ziggfreed.common.world.WhereValidator;
import com.ziggfreed.common.world.WorldSelector;
import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset;
import com.ziggfreed.mmomobscaling.caster.CasterCadence;
import com.ziggfreed.mmomobscaling.caster.CasterEntry;
import com.ziggfreed.mmomobscaling.caster.CasterRoster;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.Difficulty;
import com.ziggfreed.mmomobscaling.asset.WorldSettings;
import com.ziggfreed.mmomobscaling.family.FamilyFilter;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.variant.Variant;
import com.ziggfreed.mmomobscaling.world.DifficultyMapping;

/**
 * Value-sanity validation over the FOLDED rarity/affix content (jar + pack + owner), run once per
 * {@code LoadedAssetsEvent} fold by {@code MobScalingAssetRegistrar} and logged as warnings; a finding
 * never blocks the load (bad content degrades, it does not kill the mod). Pure logic (no engine types),
 * unit-tested.
 *
 * <p>Two families of check live here:
 * <ul>
 *   <li><b>Value + shape</b> ({@code validateRarities}, {@code validateAffixes}, ...) - pure range and
 *       self-contradiction rules, run per store fold.</li>
 *   <li><b>Reference EXISTENCE</b> ({@code validate*References}, taking a {@link ReferenceResolvers}) -
 *       "does the id this content points at actually resolve to an asset". These stay pure by taking the
 *       live asset lookups as injected predicates (the same shape {@link #validateDifficultyCaps} uses for
 *       the MMO power bounds), and they run ONCE at boot, after every store has loaded, rather than per
 *       fold: a per-fold check would race the load order of the stores it reads. The per-spawn warn-once
 *       sites (the effect-apply system, the loot-drop system, the family matcher) stay exactly as they
 *       are - this is an EARLIER, whole-catalog report of the same class of typo, not a replacement.</li>
 * </ul>
 */
public final class ScalingContentValidator {

    /**
     * The exact {@code AnimationSlot} enum names the engine's {@code AnimationSlot.valueOf} accepts
     * (case-sensitive, mirrored here rather than depending on the engine enum so this class stays
     * pure/engine-decoupled like the rest of this validator).
     */
    private static final Set<String> KNOWN_ANIMATION_SLOTS =
            Set.of("Movement", "Status", "Action", "Face", "Emote");

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
            // A REACHABLE tier whose family filter can NEVER match is dead content: it costs a roster slot
            // but can never be picked. Reachable = rollable (Weight > 0) OR force-only (Weight 0 with a
            // Force list, the boss-tier shape). Only the PURE self-contradictions are checkable here (a "*"
            // deny nukes everything; an id in both allow + deny is a dead allow entry since deny wins; an id
            // in both force + deny is a dead DENY entry since force wins). Whether a referenced NPCGroup id
            // EXISTS needs the live asset map, so that check is warn-once in MobFamilyMatcher at resolve
            // time (mirrors the AuraEffectId / Loot existence checks below).
            if (r.weight() > 0 || r.familyFilter().hasForce()) {
                findings.addAll(familyFilterFindings(at, r.familyFilter()));
            }
        }
        return findings;
    }

    /** Pure self-contradiction findings for a rarity's family filter (deny-all / allow-entry-also-denied). */
    @Nonnull
    private static List<String> familyFilterFindings(@Nonnull String at, @Nonnull FamilyFilter filter) {
        List<String> findings = new ArrayList<>();
        // A deny-all is only dead content when nothing forces the entry (force outranks deny).
        if (!filter.hasForce() && (filter.denyRoles().contains("*") || filter.denyGroups().contains("*"))) {
            findings.add(at + ": Families deny list contains '*' - this tier can never roll (denies everything)");
        }
        for (String id : filter.forceGroups()) {
            if (filter.denyGroups().contains(id)) {
                findings.add(at + ": Families group '" + id + "' is in both ForceGroups and DenyGroups"
                        + " (force wins, so the deny entry is dead)");
            }
        }
        for (String pattern : filter.forceRoles()) {
            if (filter.denyRoles().contains(pattern)) {
                findings.add(at + ": Families role pattern '" + pattern + "' is in both ForceRoles and DenyRoles"
                        + " (force wins, so the deny entry is dead)");
            }
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
    public static List<String> validateVariants(@Nonnull Collection<Variant> variants) {
        List<String> findings = new ArrayList<>();
        for (Variant v : variants) {
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

    // ==================== Reference EXISTENCE (boot-time, injected resolvers) ====================

    /**
     * The live-asset existence lookups the {@code validate*References} checks need, injected so this class
     * stays pure and engine-decoupled (the same shape {@link #validateDifficultyCaps} uses for the MMO
     * power bounds). Each predicate answers "does an asset with this id exist"; an implementation that
     * CANNOT answer (store not loaded yet, engine class absent in a unit JVM) MUST return {@code true}, so
     * an unknown degrades to silence instead of a false warning.
     *
     * <p>Deliberately independent predicates rather than one lookup object: a caller may be able to
     * answer some questions and not others, and a new reference kind adds a field without disturbing the
     * rest.
     */
    public record ReferenceResolvers(
            @Nonnull Predicate<String> effectExists,
            @Nonnull Predicate<String> dropListExists,
            @Nonnull Predicate<String> npcGroupExists,
            @Nonnull Predicate<String> roleExists,
            @Nonnull Predicate<String> interactionExists,
            @Nonnull Predicate<String> lootableExists) {

        /** Resolvers that answer "exists" to everything - the engine-absent / unit-test no-op. */
        @Nonnull
        public static ReferenceResolvers permissive() {
            Predicate<String> yes = id -> true;
            return new ReferenceResolvers(yes, yes, yes, yes, yes, yes);
        }
    }

    /**
     * Existence-check every asset id a folded rarity points at: its {@code AuraEffectId}, everything its
     * {@code Loot} block references, and the native {@code NPCGroup} ids / exact role names in its
     * {@code Families} allow / deny / force lists. WARN-level findings only: a dangling id degrades (the
     * tier still rolls, it just applies nothing), it never blocks the load.
     */
    @Nonnull
    public static List<String> validateRarityReferences(@Nonnull Collection<Rarity> rarities,
            @Nonnull ReferenceResolvers resolvers) {
        List<String> findings = new ArrayList<>();
        for (Rarity r : rarities) {
            String at = "rarity '" + r.id() + "'";
            referenceFinding(findings, at, "AuraEffectId", r.auraEffectId(), resolvers.effectExists(),
                    "EntityEffect asset", "the tier rolls but applies no aura");
            findings.addAll(lootReferenceFindings(at, r.loot(), resolvers, "the tier"));
            findings.addAll(familyReferenceFindings(at, r.familyFilter(), resolvers));
        }
        return findings;
    }

    /** Existence-check a folded variant's references; same rules as {@link #validateRarityReferences}. */
    @Nonnull
    public static List<String> validateVariantReferences(@Nonnull Collection<Variant> variants,
            @Nonnull ReferenceResolvers resolvers) {
        List<String> findings = new ArrayList<>();
        for (Variant v : variants) {
            String at = "variant '" + v.id() + "'";
            referenceFinding(findings, at, "AuraEffectId", v.auraEffectId(), resolvers.effectExists(),
                    "EntityEffect asset", "the variant rolls but applies no fallback aura");
            findings.addAll(lootReferenceFindings(at, v.loot(), resolvers, "the variant"));
            findings.addAll(familyReferenceFindings(at, v.familyFilter(), resolvers));
        }
        return findings;
    }

    /**
     * Existence findings for one authored {@code Loot} block: every shared table it names by id, and every
     * native drop table any of its inline rolls grants (top level and ladder floors alike). Both are the
     * silent-failure shape this sweep exists for - a mistyped id costs the player loot with no error
     * anywhere - so each is named at boot rather than at whatever future kill first touches it.
     *
     * @param subject how to refer to the owner in the consequence clause, e.g. {@code "the tier"}
     */
    @Nonnull
    private static List<String> lootReferenceFindings(@Nonnull String at, @Nullable LootRef loot,
            @Nonnull ReferenceResolvers resolvers, @Nonnull String subject) {
        List<String> findings = new ArrayList<>();
        if (loot == null) {
            return findings;
        }
        String[] tables = loot.getLootables();
        if (tables != null) {
            for (String tableId : tables) {
                referenceFinding(findings, at, "Loot.Lootables", tableId, resolvers.lootableExists(),
                        "Lootable asset", subject + " rolls but that table contributes nothing");
            }
        }
        Roll[] rolls = loot.getRolls();
        if (rolls != null) {
            for (Roll roll : rolls) {
                if (roll == null) {
                    continue;
                }
                dropListFindings(findings, at, roll.getGrants(), resolvers, subject);
                Roll.Ladder ladder = roll.getLadder();
                if (ladder == null || ladder.getFloors() == null) {
                    continue;
                }
                for (Roll.Ladder.Floor floor : ladder.getFloors()) {
                    if (floor != null) {
                        dropListFindings(findings, at, floor.getGrants(), resolvers, subject);
                    }
                }
            }
        }
        return findings;
    }

    /** One finding per native drop-table id in a grants group that names no {@code ItemDropList}. */
    private static void dropListFindings(@Nonnull List<String> out, @Nonnull String at,
            @Nullable LootGrants grants, @Nonnull ReferenceResolvers resolvers, @Nonnull String subject) {
        if (grants == null || grants.getDropLists() == null) {
            return;
        }
        for (String dropListId : grants.getDropLists()) {
            referenceFinding(out, at, "Loot.Rolls[].Grants.DropLists", dropListId,
                    resolvers.dropListExists(), "ItemDropList asset",
                    subject + " rolls but that grant drops nothing");
        }
    }

    /**
     * Existence-check a folded affix's {@code EffectId}. A blank id is a legitimate shape (a pure fold-delta
     * or BEHAVIORAL affix); only an AUTHORED id that resolves to nothing is a finding.
     */
    @Nonnull
    public static List<String> validateAffixReferences(@Nonnull Collection<Affix> affixes,
            @Nonnull ReferenceResolvers resolvers) {
        List<String> findings = new ArrayList<>();
        for (Affix a : affixes) {
            referenceFinding(findings, "affix '" + a.id() + "'", "EffectId", a.effectId(),
                    resolvers.effectExists(), "EntityEffect asset",
                    "the affix rolls onto mobs but applies nothing");
        }
        return findings;
    }

    /**
     * Existence-check a folded caster roster's {@code NativeChain} ids against the native
     * {@code RootInteraction} store. An {@code AbilityId} is deliberately NOT checked here: abilities live
     * in the MMO jar's own catalog, which this mod reaches only through the frozen API, and that entry
     * already degrades with its own one-shot warning when the ability is missing.
     */
    @Nonnull
    public static List<String> validateCasterRosterReferences(@Nonnull Collection<CasterRoster> rosters,
            @Nonnull ReferenceResolvers resolvers) {
        List<String> findings = new ArrayList<>();
        for (CasterRoster r : rosters) {
            String at = "caster roster '" + r.id() + "'";
            for (int i = 0; i < r.abilities().size(); i++) {
                CasterEntry e = r.abilities().get(i);
                if (e.kind() != CasterEntry.Kind.NATIVE_CHAIN) {
                    continue;
                }
                referenceFinding(findings, at + " Abilities[" + i + "]", "NativeChain", e.nativeChain(),
                        resolvers.interactionExists(), "RootInteraction asset",
                        "the entry never arms, so the mob never fires that attack");
            }
        }
        return findings;
    }

    /** Existence findings for one {@code Families} block's group ids + EXACT (non-glob) role names. */
    @Nonnull
    private static List<String> familyReferenceFindings(@Nonnull String at, @Nonnull FamilyFilter filter,
            @Nonnull ReferenceResolvers resolvers) {
        List<String> findings = new ArrayList<>();
        groupReferenceFindings(findings, at, "AllowGroups", filter.allowGroups(), resolvers);
        groupReferenceFindings(findings, at, "DenyGroups", filter.denyGroups(), resolvers);
        groupReferenceFindings(findings, at, "ForceGroups", filter.forceGroups(), resolvers);
        roleReferenceFindings(findings, at, "AllowRoles", filter.allowRoles(), resolvers);
        roleReferenceFindings(findings, at, "DenyRoles", filter.denyRoles(), resolvers);
        roleReferenceFindings(findings, at, "ForceRoles", filter.forceRoles(), resolvers);
        return findings;
    }

    /** One finding per {@code Families} group id that names no {@code NPCGroup} tagset ({@code "*"} exempt). */
    private static void groupReferenceFindings(@Nonnull List<String> out, @Nonnull String at,
            @Nonnull String field, @Nonnull List<String> ids, @Nonnull ReferenceResolvers resolvers) {
        for (String id : ids) {
            if (isBlank(id) || "*".equals(id.trim()) || resolvers.npcGroupExists().test(id.trim())) {
                continue;
            }
            out.add(at + ": Families." + field + " names NPCGroup '" + id
                    + "', which has no tagset asset (the entry can never match; author"
                    + " Server/NPC/Groups/" + id + ".json or fix the id)");
        }
    }

    /**
     * One finding per EXACT role name in a {@code Families} role list that names no NPC role. A pattern
     * containing {@code *} is a glob (it is meant to match a FAMILY of roles, several of which may not
     * exist on a given server) and is deliberately never existence-checked.
     */
    private static void roleReferenceFindings(@Nonnull List<String> out, @Nonnull String at,
            @Nonnull String field, @Nonnull List<String> patterns, @Nonnull ReferenceResolvers resolvers) {
        for (String pattern : patterns) {
            if (isBlank(pattern) || pattern.indexOf('*') >= 0 || resolvers.roleExists().test(pattern.trim())) {
                continue;
            }
            out.add(at + ": Families." + field + " names role '" + pattern
                    + "', which is not a loaded NPC role (the entry can never match; use a '*' glob if"
                    + " you meant a family of roles, or fix the id)");
        }
    }

    /** Add one dangling-reference finding when an AUTHORED (non-blank) id fails its existence predicate. */
    private static void referenceFinding(@Nonnull List<String> out, @Nonnull String at,
            @Nonnull String field, @Nullable String id, @Nonnull Predicate<String> exists,
            @Nonnull String assetKind, @Nonnull String consequence) {
        if (isBlank(id) || exists.test(id.trim())) {
            return;
        }
        out.add(at + ": " + field + " '" + id + "' does not resolve to a " + assetKind
                + " (" + consequence + ")");
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
     *
     * <p>Also reports the two SILENT ambiguities the matcher resolves without telling anyone: a
     * {@code Match} that fully SHADOWS a more specific one (correct today, but it captures that rule's
     * worlds the moment the more specific rule is deleted, disabled by removal, or renamed) and a pair of
     * rules the matcher can only separate by file order. See {@link #matchAmbiguityFindings}.
     */
    @Nonnull
    public static List<String> validateWorldSettings(@Nonnull WorldSettingsConfig worlds) {
        List<String> findings = new ArrayList<>();
        Set<String> seenMatch = new HashSet<>();
        List<MatchRule> rules = new ArrayList<>();
        for (var e : worlds.foldedView().entrySet()) {
            String id = e.getKey();
            WorldSettings ws = e.getValue();
            String at = "world '" + id + "'";
            String parent = worlds.parentOf(id);
            if (parent != null && !worlds.foldedView().containsKey(parent.trim().toLowerCase(Locale.ROOT))) {
                findings.add(at + ": Parent '" + parent + "' not found (the file resolved standalone)");
            }
            WorldSelector where = ws.getWhere();
            if (where != null) {
                findings.addAll(selectorFindings(at, where));
            }
            for (String pattern : patternsOf(ws)) {
                if (!seenMatch.add(pattern.trim().toLowerCase(Locale.ROOT))) {
                    findings.add(at + ": duplicate Where.Match '" + pattern + "' across world files"
                            + " (the selectors tie, so one silently wins on authoring order)");
                } else {
                    rules.add(new MatchRule(id, pattern.trim(), Pattern.parse(pattern)));
                }
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
        findings.addAll(matchAmbiguityFindings(rules));
        return findings;
    }

    // ==================== Where selector shape ====================

    /** Every {@code Where.Match} pattern a rule authors, blanks dropped. */
    @Nonnull
    private static List<String> patternsOf(@Nonnull WorldSettings ws) {
        List<String> out = new ArrayList<>();
        String[] patterns = ws.getWhere() == null ? null : ws.getWhere().getMatch();
        if (patterns == null) {
            return out;
        }
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank()) {
                out.add(pattern);
            }
        }
        return out;
    }

    /** The shared Where audit, flattened into this validator's string-finding shape. */
    @Nonnull
    private static List<String> selectorFindings(@Nonnull String at, @Nonnull WorldSelector where) {
        List<String> out = new ArrayList<>();
        for (Finding finding : WhereValidator.validateSelector(where, at + " Where")) {
            out.add(at + ": " + finding.message());
        }
        return out;
    }

    // ==================== Match-pattern ambiguity ====================

    /**
     * One matchable world rule, pre-parsed by the SHARED pattern parser rather than a copy of it.
     * The parse and the runtime's own scoring then cannot drift, which is the whole point: a
     * validator reasoning about precedence from its own parse would eventually reassure an author
     * about an ordering the engine does not actually use.
     */
    private record MatchRule(@Nonnull String worldId, @Nonnull String pattern, @Nonnull Pattern parsed) {

        @Nonnull
        Kind kind() {
            return parsed.kind();
        }

        @Nonnull
        String core() {
            return parsed.core();
        }

        /** This pattern's place on the shared specificity ladder - what actually decides a match. */
        @Nonnull
        MatchRank rank() {
            return MatchRank.ofNamePattern(parsed);
        }
    }

    /**
     * Report the two ambiguities the shared match ladder resolves SILENTLY.
     *
     * <ol>
     *   <li><b>Shadowing.</b> A wildcard rule whose literal core is strictly contained in a longer rule's
     *       core matches EVERYTHING that longer rule matches ({@code dungeon_i*} matches every world
     *       {@code dungeon_ii*} does). Today the longer core outranks it, so the pair behaves; the
     *       moment the longer rule is deleted or renamed, the short one silently inherits its worlds. One
     *       finding per rule, naming only the CLOSEST rule it shadows, so a family of three nested
     *       patterns reports two lines rather than every pair.</li>
     *   <li><b>Order-decided ties.</b> Two patterns whose {@link MatchRank}s COMPARE EQUAL cannot be
     *       separated by specificity at all, so a world both match is decided by authoring order.
     *       Asking the rank itself is what keeps this honest: a tie is exactly "the ladder has
     *       nothing left to say", whichever kinds and cores produced it.</li>
     * </ol>
     */
    @Nonnull
    private static List<String> matchAmbiguityFindings(@Nonnull List<MatchRule> rules) {
        List<String> findings = new ArrayList<>();
        for (MatchRule rule : rules) {
            MatchRule closest = null;
            for (MatchRule other : rules) {
                if (other == rule || !subsumes(rule, other)) {
                    continue;
                }
                if (closest == null || other.core().length() < closest.core().length()) {
                    closest = other;
                }
            }
            if (closest != null) {
                findings.add("world '" + rule.worldId() + "': Where.Match '" + rule.pattern()
                        + "' also matches every world '" + closest.pattern() + "' (world '"
                        + closest.worldId() + "') matches, so it shadows that rule as soon as the more"
                        + " specific one is removed or renamed - consider a delimiter before the wildcard");
            }
        }
        for (int i = 0; i < rules.size(); i++) {
            MatchRule a = rules.get(i);
            for (int j = i + 1; j < rules.size(); j++) {
                MatchRule b = rules.get(j);
                // Equal ranks only tie in reality when both patterns CAN match one world. Two
                // equal-core prefixes cannot (their cores would have to be identical, which the
                // duplicate check already reports), so only overlapping cores are worth a line.
                if (a.rank().compareTo(b.rank()) == 0 && coresOverlap(a, b)) {
                    findings.add("world '" + a.worldId() + "': Where.Match '" + a.pattern() + "' and world '"
                            + b.worldId() + "' Where.Match '" + b.pattern() + "' are equally specific -"
                            + " a world matching both is decided by authoring order, not specificity;"
                            + " lengthen one core to make the intent explicit");
                }
            }
        }
        return findings;
    }

    /**
     * True when EVERY world {@code specific} matches is also matched by {@code general}, with a strictly
     * shorter (so strictly less specific) core. Same-anchoring only, plus the contains-over-anything case:
     * a prefix core can only subsume another prefix, a suffix another suffix, while a contains core
     * subsumes any rule whose core contains it.
     */
    private static boolean subsumes(@Nonnull MatchRule general, @Nonnull MatchRule specific) {
        if (general.core().length() >= specific.core().length()) {
            return false;
        }
        return switch (general.kind()) {
            case PREFIX -> specific.kind() == Kind.PREFIX && specific.core().startsWith(general.core());
            case SUFFIX -> specific.kind() == Kind.SUFFIX && specific.core().endsWith(general.core());
            case CONTAINS -> (specific.kind() == Kind.PREFIX || specific.kind() == Kind.SUFFIX
                    || specific.kind() == Kind.CONTAINS) && specific.core().contains(general.core());
            default -> false;
        };
    }

    /**
     * Could one world name satisfy both patterns? Only a CONTAINS pattern can float, so two rules
     * of equal rank overlap when at least one of them is a contains form - an equal-core prefix
     * pair or suffix pair would need identical cores, which is the duplicate finding instead.
     */
    private static boolean coresOverlap(@Nonnull MatchRule a, @Nonnull MatchRule b) {
        return a.kind() == Kind.CONTAINS || b.kind() == Kind.CONTAINS;
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

    /**
     * Validate folded caster rosters: {@code Role.Id} XOR {@code Role.Glob} (a roster with neither/both
     * never matches anything - silently dead content), an {@code Abilities[]} entry missing exactly one
     * of {@code AbilityId}/{@code NativeChain} ({@link CasterEntry.Kind#INVALID}, never armed), an
     * unrecognised {@code Scope} value, a {@code CadenceSeconds} below the {@link CasterCadence#MIN_CADENCE_MS}
     * floor (including an absent/zero value), a negative {@code JitterSeconds}, a negative
     * {@code MinDifficulty}, two DIFFERENT rosters authoring the exact same {@code Role.Glob}
     * pattern OR the exact same {@code Role.Id} (matcher precedence silently picks one - the same
     * "duplicate Match" shape as {@link #validateWorldSettings}), a blank {@code Windup.Animation} on an
     * otherwise-present {@code Windup} group, a {@code Windup} authored on a {@code NativeChain} entry
     * (wind-ups only apply to {@code AbilityId} entries - a native chain arms once at spawn and carries
     * its own animation nodes), and an unrecognised {@code Windup.Slot} name. Empty = clean.
     *
     * <p>NOT validated: whether a {@code Windup.Animation} model-level key actually exists in the
     * matching role's model {@code AnimationSets} - model assets are engine-side and read only at
     * animation-play time, so a bad key degrades to a per-minute engine warning + no-op instead of a
     * content-audit finding.
     */
    @Nonnull
    public static List<String> validateCasterRosters(@Nonnull Collection<CasterRoster> rosters) {
        List<String> findings = new ArrayList<>();
        Set<String> seenGlob = new HashSet<>();
        Set<String> seenRoleId = new HashSet<>();
        for (CasterRoster r : rosters) {
            String at = "caster roster '" + r.id() + "'";
            if (!r.hasValidRoleSelector()) {
                findings.add(at + ": Role needs exactly one of Id or Glob (got Id='" + nullToEmpty(r.roleId())
                        + "', Glob='" + nullToEmpty(r.roleGlob()) + "') - this roster will never match any mob");
            }
            if (r.hasRoleGlob()) {
                String glob = r.roleGlob().trim().toLowerCase(Locale.ROOT);
                if (!seenGlob.add(glob)) {
                    findings.add(at + ": duplicate Role.Glob '" + r.roleGlob()
                            + "' across roster files (matcher precedence silently picks one)");
                }
            }
            if (r.hasRoleId()) {
                String id = r.roleId().trim().toLowerCase(Locale.ROOT);
                if (!seenRoleId.add(id)) {
                    findings.add(at + ": duplicate Role.Id '" + r.roleId()
                            + "' across roster files (matcher precedence silently picks one)");
                }
            }
            for (int i = 0; i < r.abilities().size(); i++) {
                findings.addAll(casterEntryFindings(at + " Abilities[" + i + "]", r.abilities().get(i)));
            }
        }
        return findings;
    }

    /** Per-entry findings for one {@code Abilities[]} element of a caster roster. */
    @Nonnull
    private static List<String> casterEntryFindings(@Nonnull String at, @Nonnull CasterEntry e) {
        List<String> findings = new ArrayList<>();
        if (e.kind() == CasterEntry.Kind.INVALID) {
            findings.add(at + ": needs exactly one of AbilityId or NativeChain (got neither or both)"
                    + " - this entry will never arm");
        }
        if (e.scopeUnknown()) {
            findings.add(at + ": unknown Scope (HOSTILE | BOSS | ANY expected) - falls back to ANY");
        }
        if (e.minDifficulty() < 0) {
            findings.add(at + ": MinDifficulty must be >= 0");
        }
        if (e.cadenceMs() < CasterCadence.MIN_CADENCE_MS) {
            findings.add(at + ": CadenceSeconds must be >= " + (CasterCadence.MIN_CADENCE_MS / 1000.0)
                    + " (got " + (e.cadenceMs() / 1000.0) + "s; a too-low/absent value is clamped at runtime"
                    + " but should be fixed in content)");
        }
        if (e.jitterMs() < 0) {
            findings.add(at + ": JitterSeconds must be >= 0");
        }
        CasterEntry.Windup windup = e.windup();
        if (windup != null) {
            if (windup.animation().isBlank()) {
                findings.add(at + ": Windup.Animation is blank - the Windup group is present but does nothing");
            }
            if (e.kind() == CasterEntry.Kind.NATIVE_CHAIN) {
                findings.add(at + ": Windup only applies to AbilityId entries - a NativeChain entry arms once"
                        + " at spawn and its own chain carries its own animation nodes, so this Windup never plays");
            }
            String slot = windup.slot();
            if (slot != null && !slot.isBlank() && !KNOWN_ANIMATION_SLOTS.contains(slot)) {
                findings.add(at + ": unknown Windup.Slot '" + slot
                        + "' (Movement | Status | Action | Face | Emote expected) - falls back to the default slot");
            }
        }
        return findings;
    }

    @Nonnull
    private static String nullToEmpty(@Nullable String s) {
        return s != null ? s : "";
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
