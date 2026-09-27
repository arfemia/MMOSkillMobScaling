package com.ziggfreed.mmomobscaling.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.AssetModule;

/**
 * Names, at boot, every settings layer that still authors the retired {@code Intensity} multiplier or one
 * of the four retired {@code Difficulty.StatCurve} leaves, and REWRITES NOTHING. One notice per file,
 * naming the file, the value found, the leaves that replaced what it scaled, and what to author instead.
 *
 * <p><b>Why a report and not a carry.</b> {@code Intensity} multiplied the curve slopes of its day, and
 * on the tank axis those slopes ({@code HpPerPoint}, {@code InDamageReductionPerPoint}) no longer exist:
 * the axis is one effective-HP slope split by {@code VisibleHpShare}, and there is no number that
 * reproduces an old {@code Intensity} on it. Writing one would give the owner a third curve that is
 * neither the one they had nor the one they left untouched, so the owner decides, with the facts in
 * front of them. On the damage axis, the surviving one, the notice offers a starting point and says it
 * is a suggestion: multiplying {@code OutDamageScale} by the old {@code Intensity} scales the curve's
 * growth the way {@code Intensity} scaled the old slope.
 *
 * <p><b>The layers it looks at</b> are every place an {@code Intensity} could sit: the owner file
 * ({@code mods/MmoMobScaling/mob-scaling.json}), every owner world file ({@code mods/MmoMobScaling/worlds/}),
 * every jar or pack world body no owner file shadows ({@link WorldSettingsConfig#packOnlyIds}; a shadowed
 * body is inert, since an owner file replaces it wholesale), and every {@code Server/MmoMobScaling/Settings/*.json}
 * in every loaded asset pack, a pack's own preset and a pack's override of {@code Default.json} alike, read
 * raw off the pack because the settings codec keeps no key it does not declare. A file the report cannot
 * read is skipped with a warning.
 *
 * <p>Runs from the boot audit ({@code MobScalingAssetRegistrar.runBootAudit}), enabled or not: a disabled
 * mod must still explain what it is ignoring. It keeps reporting at every boot until the owner acts, since
 * nothing else can make the file stop carrying the key.
 */
public final class LegacyIntensityReport {

    /** The retired top-level slope multiplier, on the owner file, a world file and a settings preset alike. */
    static final String INTENSITY = "Intensity";
    /** The {@code Difficulty.StatCurve} leaves the schema no longer declares. */
    static final List<String> RETIRED_CURVE_LEAVES =
            List.of("HpPerPoint", "InDamageReductionPerPoint", "MaxHpMult", "MinInDamageMult");
    /** Content path of the settings store under a pack's root. */
    static final String SETTINGS_DIR = "Server/MmoMobScaling/Settings";

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

    private LegacyIntensityReport() {
    }

    /**
     * One settings file read raw out of a loaded asset pack: the engine-free input of
     * {@link #settingsNotices}, so the detection is unit-tested on authored bodies.
     *
     * @param pack the pack's display name, as the engine reports it
     * @param path the file's path under the pack root ({@code Server/MmoMobScaling/Settings/<name>.json})
     * @param body the file's raw JSON, exactly as authored
     */
    public record PackSettingsFile(@Nonnull String pack, @Nonnull String path, @Nonnull JsonObject body) {
    }

    /** What one body authors that nothing reads: its {@code Intensity} (null when none) and its retired leaves as {@code name=value}. */
    record Finding(@Nullable Double intensity, @Nonnull List<String> retired) {

        boolean isEmpty() {
            return intensity == null && retired.isEmpty();
        }
    }

    /** The curve a layer folds to today, quoted in its notice so the owner knows what they are replacing. */
    record Current(double effectiveHpPerPoint, double visibleHpShare, double outDamageScale, double outDamageShape) {
    }

    /**
     * Report every layer, logging one WARNING per file, and return the notices (a test reads them back;
     * production ignores the return). {@code packSettings} is what {@link #collectPackSettings} read, or an
     * authored list under test.
     */
    @Nonnull
    public static List<String> run(@Nonnull List<PackSettingsFile> packSettings) {
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        List<String> notices = new ArrayList<>();
        notices.addAll(ownerNotices(cfg.getConfigPath(), cfg));
        notices.addAll(worldNotices(WorldSettingsConfig.getInstance(), cfg));
        notices.addAll(settingsNotices(packSettings, cfg));
        for (String n : notices) {
            warn(n);
        }
        return List.copyOf(notices);
    }

    /** {@link #run(List)} over the settings files of every loaded asset pack. */
    @Nonnull
    public static List<String> run() {
        return run(collectPackSettings());
    }

    /** The owner file's notice, when it authors anything retired; empty otherwise. */
    @Nonnull
    static List<String> ownerNotices(@Nullable Path ownerFile, @Nonnull MobScalingConfig cfg) {
        JsonObject owner = ownerFile == null ? null : readObject(ownerFile);
        if (owner == null) {
            return List.of();
        }
        Finding finding = inspect(owner);
        if (finding.isEmpty()) {
            return List.of();
        }
        return List.of(notice(ownerFile.toString(), finding, currentOf(owner, cfg), null));
    }

    /**
     * One notice per world body that authors anything retired: an owner world file by its file, a jar or
     * pack body no owner file shadows by its id, named as a pack file with the owner-copy route.
     */
    @Nonnull
    static List<String> worldNotices(@Nonnull WorldSettingsConfig worlds, @Nonnull MobScalingConfig cfg) {
        List<String> notices = new ArrayList<>();
        for (String id : worlds.ownerAuthoredIds()) {
            JsonObject own = worlds.authoredRawJsonById(id);
            Finding finding = own == null ? null : inspect(own);
            if (finding == null || finding.isEmpty()) {
                continue;
            }
            Path file = worlds.ownerFileFor(id);
            String where = file != null ? file.toString() : "worlds/" + id + ".json";
            notices.add(notice(where, finding, currentOf(worlds.mergedRawJsonById(id), cfg), null));
        }
        for (String id : worlds.packOnlyIds()) {
            JsonObject own = worlds.authoredRawJsonById(id);
            Finding finding = own == null ? null : inspect(own);
            if (finding == null || finding.isEmpty()) {
                continue;
            }
            notices.add(notice("world '" + id + "'", finding, currentOf(worlds.mergedRawJsonById(id), cfg),
                    "mods/MmoMobScaling/worlds/" + id + ".json"));
        }
        return notices;
    }

    /** One notice per pack settings file (a preset of its own or an override of {@code Default.json}) that authors anything retired. */
    @Nonnull
    static List<String> settingsNotices(@Nonnull List<PackSettingsFile> files, @Nonnull MobScalingConfig cfg) {
        List<String> notices = new ArrayList<>();
        for (PackSettingsFile file : files) {
            Finding finding = inspect(file.body());
            if (finding.isEmpty()) {
                continue;
            }
            notices.add(notice("pack '" + file.pack() + "' file " + file.path(), finding, currentOf(file.body(), cfg),
                    "mods/MmoMobScaling/mob-scaling.json"));
        }
        return notices;
    }

    /** What {@code body} authors that nothing reads: a numeric top-level {@code Intensity} and any retired curve leaf. */
    @Nonnull
    static Finding inspect(@Nonnull JsonObject body) {
        Double intensity = number(body.get(INTENSITY));
        List<String> retired = new ArrayList<>();
        JsonObject curve = curveOf(body);
        if (curve != null) {
            for (String name : RETIRED_CURVE_LEAVES) {
                JsonElement value = curve.get(name);
                if (value != null && !value.isJsonNull()) {
                    retired.add(name + "=" + value);
                }
            }
        }
        return new Finding(intensity, List.copyOf(retired));
    }

    /**
     * The one notice for a file: what it authors, that nothing was changed, the leaves that replaced what
     * {@code Intensity} scaled with the values the file folds to today, the damage-axis starting point
     * marked as a suggestion, and for a jar or pack file ({@code ownerCopy} non-null) where an owner
     * authors the replacement instead.
     */
    @Nonnull
    static String notice(@Nonnull String file, @Nonnull Finding finding, @Nonnull Current now, @Nullable String ownerCopy) {
        StringBuilder sb = new StringBuilder("mob-scaling: ").append(file);
        if (finding.intensity() != null) {
            double k = finding.intensity();
            sb.append(" authors ").append(INTENSITY).append(' ').append(k)
                    .append(", which nothing reads; the file was left as it is. ").append(INTENSITY)
                    .append(" multiplied the old curve slopes, and those leaves are gone: the tank axis is")
                    .append(" Difficulty.StatCurve.EffectiveHpPerPoint with VisibleHpShare (this file folds to ")
                    .append(fmt(now.effectiveHpPerPoint())).append(" and ").append(fmt(now.visibleHpShare()))
                    .append(" today; no number carries over from ").append(INTENSITY)
                    .append(", so tune them by hand), and the damage axis is OutDamageScale with OutDamageShape")
                    .append(" (folding to ").append(fmt(now.outDamageScale())).append(" and ")
                    .append(fmt(now.outDamageShape())).append("). A starting point for the damage axis, as a")
                    .append(" suggestion and not a conversion: OutDamageScale ")
                    .append(fmt(now.outDamageScale() * Math.max(0.0, k))).append(" (the ")
                    .append(fmt(now.outDamageScale())).append(" it folds to, times ").append(k)
                    .append(", which scales the curve's growth the way ").append(INTENSITY)
                    .append(" scaled the old slope) with OutDamageShape left at ").append(fmt(now.outDamageShape()))
                    .append(". Author those under Difficulty.StatCurve and remove ").append(INTENSITY).append('.');
        }
        if (!finding.retired().isEmpty()) {
            sb.append(finding.intensity() != null ? " It also authors" : " authors")
                    .append(" the retired StatCurve leaves ").append(String.join(", ", finding.retired()))
                    .append(", which nothing reads; the file was left as it is. Drop them: the tank axis is")
                    .append(" EffectiveHpPerPoint, VisibleHpShare and MaxEffectiveHpMult now.");
        }
        if (ownerCopy != null) {
            sb.append(" This is a jar or pack file, which is never rewritten: author the replacement in the pack,")
                    .append(" or in ").append(ownerCopy).append(" (an owner file of the same id replaces a pack")
                    .append(" world wholesale; the owner settings file overlays a preset per leaf).");
        }
        return sb.toString();
    }

    /**
     * The curve {@code body} folds to today: its own {@code Difficulty.StatCurve} leaves where authored (for a
     * world, the {@code Parent}-merged view is passed), the global effective values where not.
     */
    @Nonnull
    static Current currentOf(@Nullable JsonObject body, @Nonnull MobScalingConfig cfg) {
        JsonObject curve = curveOf(body);
        return new Current(
                or(curve, "EffectiveHpPerPoint", cfg.getStatCurveEffectiveHpPerPoint()),
                or(curve, "VisibleHpShare", cfg.getStatCurveVisibleHpShare()),
                or(curve, "OutDamageScale", cfg.getStatCurveOutDamageScale()),
                or(curve, "OutDamageShape", cfg.getStatCurveOutDamageShape()));
    }

    /**
     * Every {@code Server/MmoMobScaling/Settings/*.json} in every loaded asset pack, read raw. Fully
     * guarded: an engine surprise degrades to one warning and an empty list, never a throw into the
     * {@code BootEvent} dispatch.
     */
    @Nonnull
    static List<PackSettingsFile> collectPackSettings() {
        List<PackSettingsFile> out = new ArrayList<>();
        try {
            for (AssetPack pack : AssetModule.get().getAssetPacks()) {
                Path dir;
                try {
                    dir = pack.getRoot().resolve("Server").resolve("MmoMobScaling").resolve("Settings");
                } catch (Throwable t) {
                    continue;
                }
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
                    for (Path file : files) {
                        JsonObject body = readObject(file);
                        if (body != null) {
                            out.add(new PackSettingsFile(pack.getName(), SETTINGS_DIR + "/" + file.getFileName(), body));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            warn("could not scan the loaded asset packs for retired settings keys: " + t);
        }
        return out;
    }

    private static double or(@Nullable JsonObject curve, @Nonnull String leaf, double fallback) {
        Double authored = curve == null ? null : number(curve.get(leaf));
        return authored != null ? authored : fallback;
    }

    @Nonnull
    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4g", v);
    }

    @Nullable
    private static JsonObject curveOf(@Nullable JsonObject body) {
        return nested(nested(body, "Difficulty"), "StatCurve");
    }

    @Nullable
    private static JsonObject nested(@Nullable JsonObject parent, @Nonnull String key) {
        if (parent == null) {
            return null;
        }
        JsonElement e = parent.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    @Nullable
    private static Double number(@Nullable JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        double v = e.getAsDouble();
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }

    @Nullable
    private static JsonObject readObject(@Nonnull Path file) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception e) {
            warn("could not read " + file + " to check it for retired settings keys: " + e.getMessage());
            return null;
        }
    }

    private static void warn(@Nonnull String message) {
        if (LOGGER == null) {
            return;
        }
        try {
            LOGGER.atWarning().log("[MobScaling] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM
        }
    }
}
