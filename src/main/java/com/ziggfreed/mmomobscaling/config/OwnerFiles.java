package com.ziggfreed.mmomobscaling.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What every ONE-FILE-PER-ID owner folder under {@code mods/MmoMobScaling/} shares: the filename
 * stem is the id, a bare codec body is canonical and a pack-style {@code {"Payload": {...}}} wrapper
 * is accepted (peeled), a malformed file is skipped with a warning and never poisons the fold, the
 * folder is scaffolded up front with a {@code README.txt} the scan ignores, and a file is written
 * atomically (temp sibling + move). {@link WorldSettingsConfig} ({@code worlds/}),
 * {@link DifficultyOwnerLayer} ({@code difficulty/}) and {@link CasterOwnerLayer} ({@code casters/}) all
 * read through here (the first two write through it too), so an owner who has learned one folder has
 * learned the others.
 *
 * <p><b>ONE keying function.</b> {@link #idKey} turns a raw id, a match pattern or a filename stem
 * into the key a body is filed under: lower-cased, the trailing {@code *} dropped, every character
 * outside {@code [a-z0-9._-]} rewritten to {@code _}. The scan ({@link #scanJsonBodies}) keys a file
 * by the key of its stem, and a read, write or delete ({@link #resolveFile}) finds the existing file
 * whose stem has the same key before falling back to the canonical name {@code <key>.json} a new file
 * is created at. So an owner may hand-name a file {@code Arena.json} or {@code Arena Big.json}, and
 * the file the fold READ is the file a save WRITES, on a case-sensitive filesystem too; keying the two
 * differently is what would fork one rule into two files. Should a folder hold two files whose stems
 * share a key (a case-sensitive folder allows {@code Arena.json} beside {@code arena.json}), the one
 * spelled exactly the canonical way is used, else the first in code-point order, and the choice is
 * warned naming both.
 *
 * <p>Pure Gson + {@code java.nio}; every failure is reported through the caller's {@code warn}
 * consumer (each config keeps its own unit-JVM-safe logger) and never thrown.
 */
public final class OwnerFiles {

    /** Filename of the seeded owner-dir readme (deliberately not a {@code *.json}, so a scan skips it). */
    static final String README = "README.txt";

    private static final String JSON = ".json";

    private OwnerFiles() {
    }

    /**
     * Sanitize a display id / match pattern into an owner-dir filename stem: lower-cased, the
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

    /**
     * THE key a raw id, a {@code Parent} reference or a filename stem is filed under, and the one
     * function every owner-folder read and write keys by: {@link #sanitizeFileId}, so {@code Arena},
     * {@code arena}, {@code Arena Big.json}'s stem and {@code arena_big} all meet at the same key.
     */
    @Nonnull
    public static String idKey(@Nonnull String rawIdOrStem) {
        return sanitizeFileId(rawIdOrStem);
    }

    /** The filename stem of a {@code *.json} path ({@code Arena Big.json} -> {@code Arena Big}). */
    @Nonnull
    private static String stemOf(@Nonnull Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(JSON) ? name.substring(0, name.length() - JSON.length()) : name;
    }

    /**
     * The file in {@code dir} that carries {@code id}: the existing {@code *.json} whose stem keys to
     * {@link #idKey idKey(id)}, else the canonical path {@code <key>.json} a new file is created at.
     * Several existing files keying the same are settled by {@link #chooseAmongSameIdFiles}. Never
     * {@code null}; a missing or unreadable dir resolves to the canonical path.
     */
    @Nonnull
    static Path resolveFile(@Nonnull Path dir, @Nonnull String id, @Nonnull Consumer<String> warn) {
        String key = idKey(id);
        String canonicalName = key + JSON;
        Path canonical = dir.resolve(canonicalName);
        if (!Files.isDirectory(dir)) {
            return canonical;
        }
        List<Path> sameId = new ArrayList<>(1);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : files) {
                if (idKey(stemOf(file)).equals(key)) {
                    sameId.add(file);
                }
            }
        } catch (Exception e) {
            warn.accept("could not scan the owner dir " + dir + ": " + e.getMessage());
            return canonical;
        }
        Path chosen = chooseAmongSameIdFiles(sameId, canonicalName, warn);
        return chosen != null ? chosen : canonical;
    }

    /**
     * Settle which of several existing files keying to the same id is THE file for it: the one named
     * exactly {@code canonicalName} when present, else the first in code-point order of the filename,
     * warned once naming every candidate and the winner. {@code null} for no candidates; a single
     * candidate is returned as is with no warning. Pure (touches no filesystem), so the policy is
     * unit-testable on a case-insensitive filesystem that cannot hold {@code Arena.json} beside
     * {@code arena.json}.
     */
    @Nullable
    static Path chooseAmongSameIdFiles(@Nonnull List<Path> candidates, @Nonnull String canonicalName,
            @Nonnull Consumer<String> warn) {
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        List<Path> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparing(p -> p.getFileName().toString()));
        Path chosen = sorted.get(0);
        for (Path p : sorted) {
            if (p.getFileName().toString().equals(canonicalName)) {
                chosen = p;
                break;
            }
        }
        List<String> names = new ArrayList<>(sorted.size());
        for (Path p : sorted) {
            names.add(p.getFileName().toString());
        }
        warn.accept("owner files " + String.join(", ", names) + " in " + chosen.getParent()
                + " all name the same id '" + canonicalName.substring(0, canonicalName.length() - JSON.length())
                + "'; " + chosen.getFileName() + " is the one read and written, remove the other"
                + (sorted.size() > 2 ? "s" : ""));
        return chosen;
    }

    /**
     * Create {@code dir} up front and seed a one-time {@link #README} carrying {@code readmeText}, so
     * an owner who reads the docs and goes looking for the folder finds it on a fresh install. Never
     * clobbers an existing readme; a read-only mods dir only warns.
     */
    static void ensureDir(@Nonnull Path dir, @Nonnull String readmeText, @Nonnull Consumer<String> warn) {
        try {
            Files.createDirectories(dir);
            Path readme = dir.resolve(README);
            if (!Files.exists(readme)) {
                Files.writeString(readme, readmeText, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            warn.accept("could not create the owner dir " + dir + ": " + e.getMessage());
        }
    }

    /**
     * Every {@code *.json} body in {@code dir}, keyed by {@link #idKey the key of its stem}, in
     * code-point order of the filename; a {@code Payload} wrapper is peeled, a malformed or non-object
     * file is skipped with a warning, and several files keying the same yield ONE body, the file
     * {@link #chooseAmongSameIdFiles} settles on (the same file {@link #resolveFile} writes). Empty
     * when the dir is unset or missing.
     */
    @Nonnull
    static Map<String, JsonObject> scanJsonBodies(@Nonnull Path dir, @Nonnull Consumer<String> warn) {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        Map<String, List<Path>> byKey = new LinkedHashMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            List<Path> sorted = new ArrayList<>();
            for (Path file : files) {
                sorted.add(file);
            }
            sorted.sort(Comparator.comparing(p -> p.getFileName().toString()));
            for (Path file : sorted) {
                String stem = stemOf(file);
                if (!stem.isBlank()) {
                    byKey.computeIfAbsent(idKey(stem), k -> new ArrayList<>(1)).add(file);
                }
            }
        } catch (Exception e) {
            warn.accept("could not scan the owner dir " + dir + ": " + e.getMessage());
            return out;
        }
        for (Map.Entry<String, List<Path>> e : byKey.entrySet()) {
            Path file = chooseAmongSameIdFiles(e.getValue(), e.getKey() + JSON, warn);
            if (file == null) {
                continue;
            }
            try {
                JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) {
                    warn.accept("owner file " + file + " is not a JSON object; skipped");
                    continue;
                }
                JsonObject body = parsed.getAsJsonObject();
                if (body.has("Payload") && body.get("Payload").isJsonObject()) {
                    body = body.getAsJsonObject("Payload"); // pack-style wrapper accepted
                }
                out.put(e.getKey(), body);
            } catch (Exception ex) {
                warn.accept("owner file " + file + " is malformed and was skipped: " + ex.getMessage());
            }
        }
        return out;
    }

    /** Pretty-print a body to a file atomically (temp sibling + move); guarded, false on failure. */
    static boolean writeJson(@Nonnull Path target, @Nonnull JsonObject body, @Nonnull Consumer<String> warn) {
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
            warn.accept("could not write owner file " + target + ": " + e.getMessage());
            return false;
        }
    }
}
