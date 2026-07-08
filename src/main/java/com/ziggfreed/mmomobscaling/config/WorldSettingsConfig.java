package com.ziggfreed.mmomobscaling.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.logger.HytaleLogger;
import com.ziggfreed.common.codec.JsonParentResolver;
import com.ziggfreed.common.util.JsonOverrideWriter;
import com.ziggfreed.common.util.JsonTreeUtil;
import com.ziggfreed.common.world.WorldNameMatcher;
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
 * <p>The fold: pool all bodies by lower-cased id, run the common {@code JsonParentResolver}
 * ({@code Parent} chains merge child-over-parent per leaf, cross-layer, cycle-guarded), decode
 * each resolved body through the ONE schema authority {@link WorldSettings#CODEC}, and publish
 * the non-blank-{@code Match} bodies as pre-parsed {@code WorldNameMatcher} entries (a blank/no
 * {@code Match} body is a pool-only BASE). A malformed file warns and is skipped, never poisoning
 * the fold. Every refold invalidates {@link MobScalingConfig}'s per-world view cache.
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

    /** The resolved, matchable rules (non-blank {@code Match}), pre-parsed for the matcher. */
    @Nonnull private volatile List<WorldNameMatcher.Entry<WorldSettings>> entries = List.of();

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

    /** The scanned owner directory ({@code mods/MmoMobScaling/worlds}); {@code null} = none (tests). */
    public void setOwnerDir(@Nullable Path ownerDir) {
        this.ownerDir = ownerDir;
    }

    @Nullable
    public Path getOwnerDir() {
        return ownerDir;
    }

    /** The owner-dir file a given world id maps to; {@code null} when no owner dir is set. */
    @Nullable
    public Path ownerFileFor(@Nonnull String id) {
        Path dir = this.ownerDir;
        return dir == null ? null : dir.resolve(sanitizeFileId(id) + ".json");
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
                norm.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
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

        Map<String, String> parents = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> e : pool.entrySet()) {
            JsonObject body = e.getValue();
            if (body.has(PARENT_KEY) && body.get(PARENT_KEY).isJsonPrimitive()) {
                parents.put(e.getKey(), body.get(PARENT_KEY).getAsString());
            }
        }

        Map<String, JsonObject> resolved = JsonParentResolver.resolve(
                pool, pool.keySet(), PARENT_KEY, WorldSettingsConfig::warn);

        LinkedHashMap<String, WorldSettings> newById = new LinkedHashMap<>();
        List<WorldNameMatcher.Entry<WorldSettings>> newEntries = new ArrayList<>();
        for (Map.Entry<String, JsonObject> e : resolved.entrySet()) {
            WorldSettings ws = decode(e.getKey(), e.getValue());
            if (ws == null) {
                continue;
            }
            newById.put(e.getKey(), ws);
            if (ws.isMatchable()) {
                newEntries.add(new WorldNameMatcher.Entry<>(ws.getMatch().trim(), ws));
            }
        }

        this.parentById = Collections.unmodifiableMap(parents);
        this.ownerIds = Collections.unmodifiableSet(owners);
        this.byId = Collections.unmodifiableMap(newById);
        this.entries = List.copyOf(newEntries);
        this.rawBodies = Collections.unmodifiableMap(pool);
        MobScalingConfig.getInstance().invalidateWorldViews();
    }

    /** The pre-parsed matchable rules, in fold order (jar/pack first, owner additions after). */
    @Nonnull
    public List<WorldNameMatcher.Entry<WorldSettings>> entries() {
        return entries;
    }

    /** The best-matching resolved settings for {@code worldName}, or {@code null} (use the global). */
    @Nullable
    public WorldSettings resolve(@Nullable String worldName) {
        return WorldNameMatcher.resolve(this.entries, worldName);
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
        return byId.get(id.trim().toLowerCase(Locale.ROOT));
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
        JsonObject raw = rawBodies.get(id.trim().toLowerCase(Locale.ROOT));
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
        JsonObject raw = rawBodies.get(id.trim().toLowerCase(Locale.ROOT));
        return raw == null ? null : JsonTreeUtil.deepClone(raw);
    }

    /** The AUTHORED {@code Parent} reference of a file id (pre-strip), or {@code null} when none. */
    @Nullable
    public String parentOf(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return parentById.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /** Ids whose body came from the owner dir this fold (lower-cased). */
    @Nonnull
    public Set<String> ownerAuthoredIds() {
        return ownerIds;
    }

    /**
     * One-time migration off the SHIPPED 1.0.1 schema: when the owner {@code mob-scaling.json}
     * still carries an inline {@code WorldOverrides[]} array, lift each entry into its own
     * {@code worlds/<sanitized-match>.json} (bare body; the legacy top-level
     * {@code PlayerScalingEnabled} moves into {@code OpenWorld.PlayerScalingEnabled} where the
     * 1.0.2 schema keeps it), then STRIP the array (and its {@code $WorldOverridesComment}) from
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
                // The 1.0.2 schema keeps the player-scaling toggle inside the OpenWorld group.
                if (body.has("PlayerScalingEnabled")) {
                    JsonObject ow = body.has("OpenWorld") && body.get("OpenWorld").isJsonObject()
                            ? body.getAsJsonObject("OpenWorld") : new JsonObject();
                    ow.add("PlayerScalingEnabled", body.get("PlayerScalingEnabled"));
                    body.remove("PlayerScalingEnabled");
                    body.add("OpenWorld", ow);
                }
                Path target = dir.resolve(sanitizeFileId(match) + ".json");
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

    /**
     * Sanitize a match pattern / display id into an owner-dir filename stem: lower-cased, the
     * trailing {@code *} wildcard dropped, and every character outside {@code [a-z0-9._-]}
     * replaced with {@code _}. Never empty (falls back to {@code "world"}).
     */
    @Nonnull
    public static String sanitizeFileId(@Nonnull String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        while (s.endsWith("*")) {
            s = s.substring(0, s.length() - 1);
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_'
                    ? c : '_');
        }
        String cleaned = out.toString();
        while (cleaned.startsWith("_")) {
            cleaned = cleaned.substring(1);
        }
        while (cleaned.endsWith("_")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned.isEmpty() ? "world" : cleaned;
    }

    /** Pretty-print a world body to a file atomically (temp + move); guarded, false on failure. */
    static boolean writeWorldFile(@Nonnull Path target, @Nonnull JsonObject body) {
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(body);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, json + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            warn("could not write world file " + target + ": " + e.getMessage());
            return false;
        }
    }

    /** Scan the owner dir into the pool (bare body canonical; a {@code Payload} wrapper is peeled). */
    private void scanOwnerDirInto(@Nonnull Map<String, JsonObject> pool, @Nonnull Set<String> idsOut) {
        Path dir = this.ownerDir;
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String id = name.substring(0, name.length() - ".json".length())
                        .trim().toLowerCase(Locale.ROOT);
                if (id.isEmpty()) {
                    continue;
                }
                try {
                    JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                    if (!parsed.isJsonObject()) {
                        warn("world file " + file + " is not a JSON object; skipped");
                        continue;
                    }
                    JsonObject body = parsed.getAsJsonObject();
                    if (body.has("Payload") && body.get("Payload").isJsonObject()) {
                        body = body.getAsJsonObject("Payload"); // pack-style wrapper accepted
                    }
                    pool.put(id, body);
                    idsOut.add(id);
                } catch (Exception e) {
                    warn("world file " + file + " is malformed and was skipped: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            warn("could not scan the worlds owner dir " + dir + ": " + e.getMessage());
        }
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
