package com.ziggfreed.mmomobscaling.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.codec.JsonParentResolver;
import com.ziggfreed.common.util.JsonOverrideWriter;
import com.ziggfreed.common.util.JsonTreeUtil;
import com.ziggfreed.common.world.MatchRank;
import com.ziggfreed.common.world.WorldSelector;
import com.ziggfreed.mmomobscaling.asset.WorldSettings;

/**
 * The per-world settings pool + fold (1.0.2): owns every {@code Worlds/*.json} body across all
 * three layers and resolves them into matchable {@link WorldSettings}. The layers:
 *
 * <ol>
 *   <li><b>jar + pack</b> - the engine-merged {@code Server/MmoMobScaling/Worlds/*.json} store
 *       (raw bodies captured at {@code LoadedAssetsEvent} via {@link #applyPackLayer}, cached so a
 *       later owner-dir refresh re-folds without an asset reload).</li>
 *   <li><b>owner dir</b> - {@code mods/MmoMobScaling/worlds/*.json}, scanned on every
 *       {@link #refold()}. One file per world rule, filename (sans {@code .json}) = id; a BARE
 *       body is canonical, a pack-style {@code {"Payload":{...}}} wrapper is accepted (peeled).
 *       An owner file REPLACES a jar/pack body wholesale BY ID (layering is id-replace;
 *       inheritance is {@code Parent}'s job).</li>
 * </ol>
 *
 * <p>The fold: pool all bodies by id key ({@code OwnerFiles.idKey}), run the common {@code JsonParentResolver}

 * ({@code Parent} chains merge child-over-parent per leaf, cross-layer, cycle-guarded), decode
 * each resolved body through the ONE schema authority {@link WorldSettings#CODEC}, and publish
 * every body that says WHERE it applies as a matchable rule (a body with no {@code Where} is a
 * pool-only BASE). A malformed file warns and is skipped, never poisoning the fold. Every refold
 * invalidates {@link MobScalingConfig}'s per-world view cache.
 *
 * <p>Selection is the shared world-targeting ladder: each rule's {@code Where} is scored by
 * {@link WorldSelector} into a {@link MatchRank} and the most specific wins, with the FIRST of two
 * equally specific rules keeping the world. That is the same ordering an NPC placement and a world
 * rule sort by, so an author who has learned it once has learned it everywhere - and it is why
 * this file holds no matcher of its own.
 *
 * <p>Also owns the ONE-TIME MIGRATION off the shipped 1.0.1 inline {@code WorldOverrides[]}
 * array: {@link #migrateLegacyOwnerOverrides} lifts each owner-file entry into
 * {@code worlds/<match>.json} and strips the array (see the method doc).
 */
public final class WorldSettingsConfig {

    /** The legacy 1.0.1 inline array key on the owner {@code mob-scaling.json} (migrated + stripped). */
    public static final String LEGACY_WORLD_OVERRIDES_KEY = "WorldOverrides";

    /** The top-level parent-reference key on a world body (stripped by the resolver pre-decode). */
    public static final String PARENT_KEY = "Parent";

    /** Body of the seeded owner-dir readme ({@link OwnerFiles#README}): what goes in this folder and how it layers. */
    private static final String OWNER_DIR_README_TEXT = """
            MMO Mob Scaling - per-world settings
            ====================================

            One file per world rule. The filename (without .json) is the rule id, and the body is a
            bare WorldSettings object using the same PascalCase keys as the shipped rules:

              {
                "Where": { "Match": ["MyWorld*"] },
                "Enabled": true,
                "Difficulty": { "Floor": 45.0 }
              }

            Key points:
              - "Where" selects the worlds, in the same vocabulary every other world-targeting file
                uses:
                  "Match":          world-name patterns - an exact name, "Prefix*", "*Suffix",
                                    "*Contains*", or "*" for every world
                  "GameplayConfig": exact matches on a world's own config key - the only stable
                                    handle on an instance world, whose name carries a fresh uuid
                  "ExcludeMatch":   name patterns, same grammar as "Match", that drop a world even
                                    when a positive axis matched
                The most specific match wins: an exact GameplayConfig, then an exact name, then the
                longest literal pattern core, then a bare "*". Leave "Where" out entirely to make
                the file a pool-only base that other files inherit from but that never matches a
                world on its own.
              - "Parent": "<other-file-id>" inherits every key that file sets; anything still unset
                falls back to the global settings in ../mob-scaling.json.
              - A file here REPLACES a shipped rule of the same name outright. Delete yours to get
                the shipped one back.
              - The full schema, with every key filled in, is in ../_reference/defaults-mob-scaling.json
                and in the shipped rules inside the mod jar under Server/MmoMobScaling/Worlds/.
              - Changes are picked up on server restart, or immediately when saved from
                /mobscaling ui.

            This readme is regenerated when absent and is ignored by the loader (only *.json files
            in this folder are read).
            """;

    /** Same guarded-logger pattern as {@link MobScalingConfig} (this class is unit-tested). */
    @Nullable private static final HytaleLogger LOGGER = initLogger();

    @Nullable
    private static HytaleLogger initLogger() {
        try {
            return HytaleLogger.forEnclosingClass();
        } catch (Throwable t) {
            return null;
        }
    }

    private static WorldSettingsConfig instance;

    /** Owner-dir path ({@code mods/MmoMobScaling/worlds}); {@code null} = pack/jar layers only. */
    @Nullable private Path ownerDir;

    /** Raw jar+pack bodies keyed by lower-cased id, cached at {@code LoadedAssetsEvent}. */
    @Nonnull private volatile Map<String, JsonObject> packBodies = Map.of();

    /** The resolved rules that say WHERE they apply, in fold order (jar/pack first, then owner). */
    @Nonnull private volatile List<WorldSettings> rules = List.of();

    /** Every resolved body by id (INCLUDING pool-only bases), for the admin UI / command list. */
    @Nonnull private volatile Map<String, WorldSettings> byId = Map.of();

    /**
     * The PRE-{@code Parent}-merge raw body per lower-cased id (jar+pack+owner, id-replace layering
     * already applied, but BEFORE {@link JsonParentResolver#resolve} walks the chain). Backs
     * {@link #authoredById}, the admin-UI editor's seed source: {@link #byId} (and therefore both
     * {@link #foldedView()} and {@link #effectiveById}) is the Parent-MERGED view, which is right for
     * spawn-time reads but wrong for an editor - seeding ~40 exposed knobs from it and saving back would
     * materialize every inherited leaf into the child file and silently break inheritance.
     */
    @Nonnull private volatile Map<String, JsonObject> rawBodies = Map.of();

    /**
     * The {@code Parent}-MERGED raw body per id key (every layer, chain walked, {@code Parent}
     * stripped): the JSON {@link #byId} was decoded from. Backs {@link #mergedRawJsonById}, the read for a
     * caller that must see a leaf the codec no longer declares as the fold once saw it, inherited leaves
     * included.
     */
    @Nonnull private volatile Map<String, JsonObject> mergedBodies = Map.of();

    /** The AUTHORED (pre-strip) {@code Parent} reference per id, for display/editing. */
    @Nonnull private volatile Map<String, String> parentById = Map.of();

    /** Ids whose body came from the owner dir this fold (the override-vs-default badge). */
    @Nonnull private volatile Set<String> ownerIds = Set.of();

    private WorldSettingsConfig() {
    }

    @Nonnull
    public static WorldSettingsConfig getInstance() {
        if (instance == null) {
            instance = new WorldSettingsConfig();
        }
        return instance;
    }

    /**
     * The scanned owner directory ({@code mods/MmoMobScaling/worlds}); {@code null} = none (tests).
     * Setting a non-null dir also SCAFFOLDS it (see {@link #ensureOwnerDir}) so an owner who reads the
     * docs and goes looking for the folder finds it on a fresh install, instead of an empty parent that
     * only sprouts a {@code worlds/} the first time something happens to save a per-world rule.
     */
    public void setOwnerDir(@Nullable Path ownerDir) {
        this.ownerDir = ownerDir;
        ensureOwnerDir();
    }

    /**
     * Create the owner dir up front and seed a one-time {@code README.txt} explaining the one-file-per-world
     * convention ({@link OwnerFiles#ensureDir}: the readme is not a {@code *.json}, so the scan never tries
     * to load it; a read-only mods dir only warns; an existing readme is never clobbered).
     */
    private void ensureOwnerDir() {
        Path dir = this.ownerDir;
        if (dir != null) {
            OwnerFiles.ensureDir(dir, OWNER_DIR_README_TEXT, WorldSettingsConfig::warn);
        }
    }

    @Nullable
    public Path getOwnerDir() {
        return ownerDir;
    }

    /**
     * The owner-dir file a given world id maps to: the file already there whose stem keys to the same
     * {@link OwnerFiles#idKey id key} ({@link OwnerFiles#resolveFile}), else the canonical name a new one
     * is created at; {@code null} when no owner dir is set.
     */
    @Nullable
    public Path ownerFileFor(@Nonnull String id) {
        Path dir = this.ownerDir;
        return dir == null ? null : OwnerFiles.resolveFile(dir, id, WorldSettingsConfig::warn);
    }

    /**
     * Capture the engine-merged jar+pack raw bodies (from the Worlds store's
     * {@code LoadedAssetsEvent}) and refold. The map is keyed by asset id; values are the
     * {@code Payload} bodies.
     */
    public synchronized void applyPackLayer(@Nonnull Map<String, JsonObject> bodies) {
        Map<String, JsonObject> norm = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> e : bodies.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                norm.put(OwnerFiles.idKey(e.getKey()), e.getValue());
            }
        }
        this.packBodies = Collections.unmodifiableMap(norm);
        refold();
    }

    /**
     * Re-scan the owner dir over the cached jar+pack bodies, resolve every {@code Parent} chain,
     * decode, and publish. Called at {@code setup()} (owner-only pool until the async store loads),
     * on {@code LoadedAssetsEvent} (via {@link #applyPackLayer}), and after every owner-dir
     * write-back. Ends by invalidating {@link MobScalingConfig}'s per-world view cache.
     */
    public synchronized void refold() {
        LinkedHashMap<String, JsonObject> pool = new LinkedHashMap<>(this.packBodies);
        LinkedHashSet<String> owners = new LinkedHashSet<>();
        scanOwnerDirInto(pool, owners);

        // Every pool key is an id key (OwnerFiles.idKey), so a Parent reference has to be keyed the same
        // way before the resolver looks it up: "Parent": "Arena Big" must reach the body filed under
        // arena_big, exactly as a save for that id reaches its file.
        Map<String, String> parents = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> e : pool.entrySet()) {
            JsonObject body = e.getValue();
            if (body.has(PARENT_KEY) && body.get(PARENT_KEY).isJsonPrimitive()) {
                String authored = body.get(PARENT_KEY).getAsString();
                parents.put(e.getKey(), authored);
                String keyed = OwnerFiles.idKey(authored);
                if (!keyed.equals(authored.trim().toLowerCase(Locale.ROOT))) {
                    JsonObject rekeyed = JsonTreeUtil.deepClone(body);
                    rekeyed.addProperty(PARENT_KEY, keyed);
                    e.setValue(rekeyed);
                }
            }
        }

        // "Where" replaces wholesale under Parent (never per-leaf): a child retargeting its
        // selector must not inherit the parent's Match underneath its own GameplayConfig, or it
        // silently applies in worlds nobody authored it for - the same rule the placement
        // engine's native decode applies to WorldSelector, so a Where means one thing everywhere.
        Map<String, JsonObject> resolved = JsonParentResolver.resolve(
                pool, pool.keySet(), PARENT_KEY, WorldSettingsConfig::warn, Set.of("Where"));

        LinkedHashMap<String, WorldSettings> newById = new LinkedHashMap<>();
        List<WorldSettings> newRules = new ArrayList<>();
        for (Map.Entry<String, JsonObject> e : resolved.entrySet()) {
            WorldSettings ws = decode(e.getKey(), e.getValue());
            if (ws == null) {
                continue;
            }
            newById.put(e.getKey(), ws);
            if (ws.isMatchable()) {
                newRules.add(ws);
            }
        }

        this.parentById = Collections.unmodifiableMap(parents);
        this.ownerIds = Collections.unmodifiableSet(owners);
        this.byId = Collections.unmodifiableMap(newById);
        this.rules = List.copyOf(newRules);
        this.rawBodies = Collections.unmodifiableMap(pool);
        this.mergedBodies = Collections.unmodifiableMap(new LinkedHashMap<>(resolved));
        MobScalingConfig.getInstance().invalidateWorldViews();
    }

    /** The matchable rules, in fold order (jar/pack first, owner additions after). */
    @Nonnull
    public List<WorldSettings> rules() {
        return rules;
    }

    /**
     * The best-matching resolved settings for {@code world}, or {@code null} (use the global). This
     * is the ENGINE-facing form: it can score both axes, including the {@code GameplayConfig} key
     * that is the only stable handle on an instance world.
     */
    @Nullable
    public WorldSettings resolve(@Nullable World world) {
        if (world == null) {
            return null;
        }
        try {
            return resolve(world.getName(), world.getWorldConfig().getGameplayConfig());
        } catch (Throwable t) {
            warn("could not read world identity for a per-world settings lookup: " + t.getMessage());
            return null;
        }
    }

    /**
     * The best-matching settings for a world known only by NAME. The {@code GameplayConfig} axis
     * cannot resolve without the world itself, so a rule written on it will not match here - use
     * {@link #resolve(World)} wherever the world is in hand, and treat this as the pure, testable
     * core.
     */
    @Nullable
    public WorldSettings resolve(@Nullable String worldName) {
        return resolve(worldName, null);
    }

    /**
     * The PURE selection: the most specific matching rule, keeping the FIRST of two equally
     * specific ones so authoring order decides a genuine tie rather than map iteration order.
     */
    @Nullable
    public WorldSettings resolve(@Nullable String worldName, @Nullable String gameplayConfig) {
        MatchRank best = null;
        WorldSettings winner = null;
        for (WorldSettings rule : this.rules) {
            MatchRank rank = rule.selector().match(worldName, gameplayConfig);
            if (rank != null && rank.isMoreSpecificThan(best)) {
                best = rank;
                winner = rule;
            }
        }
        return winner;
    }

    /** Every resolved body by lower-cased id, INCLUDING pool-only bases (admin UI / command list). */
    @Nonnull
    public Map<String, WorldSettings> foldedView() {
        return byId;
    }

    /** The resolved (Parent-merged) settings for a file id, or {@code null}. */
    @Nullable
    public WorldSettings effectiveById(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return byId.get(OwnerFiles.idKey(id));
    }

    /**
     * The AUTHORED body for a file id, decoded straight from its own raw JSON: jar/pack/owner layering
     * still applies (id-replace, so an owner file fully shadows a same-id shipped one), but with NO
     * {@code Parent}-CHAIN merge. This is the admin-UI editor's seed source (see {@link #rawBodies}); a
     * blank field in the editor round-trips as "inherit" instead of baking an ancestor's value into the
     * child file on save. {@code null} when the id has no body at all.
     */
    @Nullable
    public WorldSettings authoredById(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        JsonObject raw = rawBodies.get(OwnerFiles.idKey(id));
        if (raw == null) {
            return null;
        }
        JsonObject body = JsonTreeUtil.deepClone(raw);
        body.remove(PARENT_KEY); // WorldSettings.CODEC has no Parent field; strip it like the resolver does
        return decode(id, body);
    }

    /**
     * The AUTHORED raw JSON body for a file id - a deep clone of whatever {@link #rawBodies} holds
     * (jar/pack/owner, id-replace layering already applied), verbatim: its {@code Parent} key, any
     * {@code $Comment}, and every leaf the body carries, EXPOSED by the admin UI or not. This is the
     * seed a brand-new owner file copies from the first time a save overrides a shipped/pack world
     * (see {@link MobScalingOwnerWriter#saveWorldFile}) - without it, a fresh owner file would carry
     * ONLY the handful of leaves the UI's form exposes, silently dropping everything else the shipped
     * body authored (per-world HUD position/offsets/range/name-key-prefixes, {@code RegionSizeChunks},
     * {@code $Comment}, ...). {@code null} when the id has no body at all.
     */
    @Nullable
    public JsonObject authoredRawJsonById(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        JsonObject raw = rawBodies.get(OwnerFiles.idKey(id));
        return raw == null ? null : JsonTreeUtil.deepClone(raw);
    }

    /**
     * The {@code Parent}-MERGED raw JSON body for a file id, a deep clone: the child's own leaves over
     * every ancestor's, across layers, exactly what the fold decoded. Unlike {@link #effectiveById} it
     * still carries a key the codec does not declare, so a retired leaf an ancestor authored is visible
     * here. {@code null} when the id has no body at all.
     */
    @Nullable
    public JsonObject mergedRawJsonById(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        JsonObject merged = mergedBodies.get(OwnerFiles.idKey(id));
        return merged == null ? null : JsonTreeUtil.deepClone(merged);
    }

    /**
     * The lower-cased ids whose body the jar/pack layer contributes AND no owner file replaces (an owner
     * file of the same id shadows the pack body wholesale, so the pack's is inert). These are the bodies
     * nothing can write back into.
     */
    @Nonnull
    public Set<String> packOnlyIds() {
        Set<String> owners = this.ownerIds;
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String id : this.packBodies.keySet()) {
            if (!owners.contains(id)) {
                out.add(id);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /** The AUTHORED {@code Parent} reference of a file id (pre-strip), or {@code null} when none. */
    @Nullable
    public String parentOf(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return parentById.get(OwnerFiles.idKey(id));
    }

    /** Ids whose body came from the owner dir this fold (lower-cased). */
    @Nonnull
    public Set<String> ownerAuthoredIds() {
        return ownerIds;
    }

    /**
     * One-time migration off the SHIPPED 1.0.1 schema: when the owner {@code mob-scaling.json}
     * still carries an inline {@code WorldOverrides[]} array, lift each entry into its own
     * {@code worlds/<sanitized-match>.json} (bare body; the legacy flat {@code Match} string
     * becomes {@code "Where": {"Match": [...]}}, and the legacy top-level
     * {@code PlayerScalingEnabled} moves into {@code OpenWorld.PlayerScalingEnabled} where the
     * current schema keeps it), then STRIP the array (and its {@code $WorldOverridesComment}) from
     * the owner file via an atomic sibling-preserving rewrite. An entry whose target file already
     * exists is skipped with a warning (never clobber). Idempotent: a second boot finds no array.
     *
     * @return true when a migration ran (any entry written or the key stripped)
     */
    public synchronized boolean migrateLegacyOwnerOverrides(@Nullable Path ownerFile) {
        Path dir = this.ownerDir;
        if (ownerFile == null || dir == null) {
            return false;
        }
        JsonObject root;
        try {
            if (!Files.exists(ownerFile)) {
                return false;
            }
            JsonElement parsed = JsonParser.parseString(Files.readString(ownerFile, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                return false;
            }
            root = parsed.getAsJsonObject();
        } catch (Exception e) {
            warn("legacy WorldOverrides migration skipped (unreadable owner file): " + e.getMessage());
            return false;
        }
        if (!root.has(LEGACY_WORLD_OVERRIDES_KEY)) {
            return false;
        }
        int written = 0;
        JsonElement legacy = root.get(LEGACY_WORLD_OVERRIDES_KEY);
        if (legacy.isJsonArray()) {
            for (JsonElement el : legacy.getAsJsonArray()) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject entry = el.getAsJsonObject();
                String match = entry.has("Match") && entry.get("Match").isJsonPrimitive()
                        ? entry.get("Match").getAsString() : null;
                if (match == null || match.isBlank()) {
                    warn("legacy WorldOverrides entry with blank Match skipped");
                    continue;
                }
                JsonObject body = JsonTreeUtil.deepClone(entry);
                // The flat Match string became the shared Where selector group; rewrite it so a
                // migrated rule keeps matching instead of quietly turning into a pool-only base.
                body.remove("Match");
                JsonObject where = new JsonObject();
                JsonArray patterns = new JsonArray();
                patterns.add(match);
                where.add("Match", patterns);
                body.add("Where", where);
                // The current schema keeps the player-scaling toggle inside the OpenWorld group.
                if (body.has("PlayerScalingEnabled")) {
                    JsonObject ow = body.has("OpenWorld") && body.get("OpenWorld").isJsonObject()
                            ? body.getAsJsonObject("OpenWorld") : new JsonObject();
                    ow.add("PlayerScalingEnabled", body.get("PlayerScalingEnabled"));
                    body.remove("PlayerScalingEnabled");
                    body.add("OpenWorld", ow);
                }
                Path target = OwnerFiles.resolveFile(dir, match, WorldSettingsConfig::warn);
                if (Files.exists(target)) {
                    warn("legacy WorldOverrides entry '" + match + "' NOT migrated: " + target
                            + " already exists (kept as-is)");
                    continue;
                }
                if (writeWorldFile(target, body)) {
                    written++;
                }
            }
        }
        // Strip the migrated array (+ its shipped comment key) preserving every sibling + $Comment.
        boolean stripped = JsonOverrideWriter.setLeaf(ownerFile, LEGACY_WORLD_OVERRIDES_KEY, null);
        JsonOverrideWriter.setLeaf(ownerFile, "$WorldOverridesComment", null);
        info("migrated " + written + " legacy WorldOverrides entr" + (written == 1 ? "y" : "ies")
                + " to " + dir + " (inline array " + (stripped ? "stripped" : "COULD NOT be stripped") + ")");
        return true;
    }

    /** Pretty-print a world body to a file atomically ({@link OwnerFiles#writeJson}); guarded, false on failure. */
    static boolean writeWorldFile(@Nonnull Path target, @Nonnull JsonObject body) {
        return OwnerFiles.writeJson(target, body, WorldSettingsConfig::warn);
    }

    /** Scan the owner dir into the pool (bare body canonical; a {@code Payload} wrapper is peeled). */
    private void scanOwnerDirInto(@Nonnull Map<String, JsonObject> pool, @Nonnull Set<String> idsOut) {
        Path dir = this.ownerDir;
        if (dir == null) {
            return;
        }
        Map<String, JsonObject> bodies = OwnerFiles.scanJsonBodies(dir, WorldSettingsConfig::warn);
        pool.putAll(bodies);
        idsOut.addAll(bodies.keySet());
    }

    /** Decode one resolved body through the schema authority; warn + null on a malformed body. */
    @Nullable
    private static WorldSettings decode(@Nonnull String id, @Nonnull JsonObject body) {
        try {
            return WorldSettings.CODEC.decodeJson(RawJsonReader.fromJsonString(body.toString()), new ExtraInfo());
        } catch (Exception e) {
            warn("world '" + id + "' is malformed and was skipped: " + e.getMessage());
            return null;
        }
    }

    /** Guarded warn (own logger, unit-JVM safe - the MobScalingConfig pattern). */
    private static void warn(@Nonnull String message) {
        if (LOGGER == null) {
            return;
        }
        try {
            LOGGER.atWarning().log("[WorldSettingsConfig] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM
        }
    }

    /** Guarded info (same guard as {@link #warn}). */
    private static void info(@Nonnull String message) {
        if (LOGGER == null) {
            return;
        }
        try {
            LOGGER.atInfo().log("[WorldSettingsConfig] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM
        }
    }
}
