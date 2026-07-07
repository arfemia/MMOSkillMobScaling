package com.ziggfreed.mmomobscaling.config;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.JsonOverrideWriter;

/**
 * The mob-scaling WRITE-BACK policy layer: the ONE path both the admin UI and the {@code /mobscaling}
 * command persist a knob through. Maps each setting to its PascalCase codec path in the owner file
 * ({@code mods/MmoMobScaling/mob-scaling.json}), delegates the actual write to the generic
 * {@link JsonOverrideWriter} (a partial-override write preserving other keys + {@code $Comment}; an
 * array-by-{@code Match} upsert for {@code WorldOverrides}), then reconciles the in-memory config via
 * {@link MobScalingConfig#refreshFromDisk()} so the change applies LIVE without a restart. Every method
 * is a no-op returning {@code false} when no owner path is set (defaults-only / unit contexts).
 *
 * <p><b>Type fidelity is enforced HERE</b> (an {@code int} offset autoboxes to {@link Integer} for the
 * {@code Codec.INTEGER} leaves, a {@code double} to {@link Double} for {@code Codec.DOUBLE}), so a
 * persisted value re-decodes through {@code MobScalingSettingsAsset.CODEC} unchanged.
 */
public final class MobScalingOwnerWriter {

    // Top-level leaves.
    private static final String ENABLED = "Enabled";
    private static final String ACTIVE_PRESET = "ActivePreset";
    private static final String INTENSITY = "Intensity";
    private static final String RARITY_SPAWN_CHANCE = "RaritySpawnChance";
    // OpenWorld group.
    private static final String PLAYER_SCALING = "OpenWorld.PlayerScalingEnabled";
    // Difficulty group (+ nested DistanceEscalation).
    private static final String MIN_CAP = "Difficulty.MinCap";
    private static final String MAX_CAP = "Difficulty.MaxCap";
    private static final String ESC_ENABLED = "Difficulty.DistanceEscalation.Enabled";
    private static final String ESC_START = "Difficulty.DistanceEscalation.StartDistanceBlocks";
    private static final String ESC_BLOCKS_PER_POINT = "Difficulty.DistanceEscalation.BlocksPerPoint";
    private static final String ESC_MAX_BONUS = "Difficulty.DistanceEscalation.MaxBonus";
    private static final String ESC_RARITY_PER_POINT = "Difficulty.DistanceEscalation.RarityChancePerPoint";
    // ZoneHud group.
    private static final String ZONE_ENABLED = "ZoneHud.Enabled";
    private static final String ZONE_POSITION = "ZoneHud.Position";
    private static final String ZONE_OFFSET_X = "ZoneHud.OffsetX";
    private static final String ZONE_OFFSET_Y = "ZoneHud.OffsetY";
    private static final String ZONE_SHOW_LOCATION = "ZoneHud.ShowLocationName";
    // InspectorHud group.
    private static final String INSPECTOR_ENABLED = "InspectorHud.Enabled";
    private static final String INSPECTOR_POSITION = "InspectorHud.Position";
    private static final String INSPECTOR_OFFSET_X = "InspectorHud.OffsetX";
    private static final String INSPECTOR_OFFSET_Y = "InspectorHud.OffsetY";
    private static final String INSPECTOR_RANGE = "InspectorHud.RangeBlocks";
    private static final String INSPECTOR_PORTRAIT = "InspectorHud.PortraitEnabled";
    // WorldOverrides array + its match field (also used by the page to assemble an entry).
    public static final String WORLD_OVERRIDES = "WorldOverrides";
    public static final String MATCH = "Match";

    private MobScalingOwnerWriter() {
    }

    @Nullable
    private static Path path() {
        return MobScalingConfig.getInstance().getConfigPath();
    }

    private static void refold() {
        MobScalingConfig.getInstance().refreshFromDisk();
    }

    // ---------------------------------------------------------------------
    // Generic leaf writes (the page uses these for the bulk of its knobs)
    // ---------------------------------------------------------------------

    /** Write one dotted-PascalCase leaf (a null value REMOVES it) then refold; false if no path / write fails. */
    public static boolean saveLeaf(@Nonnull String dottedPath, @Nullable Object value) {
        Path p = path();
        if (p == null || !JsonOverrideWriter.setLeaf(p, dottedPath, value)) {
            return false;
        }
        refold();
        return true;
    }

    /** Write several dotted-PascalCase leaves in ONE file write, then refold. */
    public static boolean saveLeaves(@Nonnull Map<String, Object> leaves) {
        Path p = path();
        if (p == null || !JsonOverrideWriter.setLeaves(p, leaves)) {
            return false;
        }
        refold();
        return true;
    }

    // ---------------------------------------------------------------------
    // Typed global wrappers
    // ---------------------------------------------------------------------

    public static boolean saveEnabled(boolean v) { return saveLeaf(ENABLED, v); }
    public static boolean saveActivePreset(@Nonnull String name) { return saveLeaf(ACTIVE_PRESET, name); }
    public static boolean saveIntensity(double v) { return saveLeaf(INTENSITY, v); }
    public static boolean saveRaritySpawnChance(double v) { return saveLeaf(RARITY_SPAWN_CHANCE, v); }
    public static boolean savePlayerScalingEnabled(boolean v) { return saveLeaf(PLAYER_SCALING, v); }
    public static boolean saveDifficultyMinCap(double v) { return saveLeaf(MIN_CAP, v); }
    public static boolean saveDifficultyMaxCap(double v) { return saveLeaf(MAX_CAP, v); }
    public static boolean saveEscalationEnabled(boolean v) { return saveLeaf(ESC_ENABLED, v); }
    public static boolean saveEscalationStart(double v) { return saveLeaf(ESC_START, v); }
    public static boolean saveEscalationBlocksPerPoint(double v) { return saveLeaf(ESC_BLOCKS_PER_POINT, v); }
    public static boolean saveEscalationMaxBonus(double v) { return saveLeaf(ESC_MAX_BONUS, v); }
    public static boolean saveEscalationRarityPerPoint(double v) { return saveLeaf(ESC_RARITY_PER_POINT, v); }

    // ---------------------------------------------------------------------
    // Typed ZoneHud wrappers
    // ---------------------------------------------------------------------

    public static boolean saveZoneHudEnabled(boolean v) { return saveLeaf(ZONE_ENABLED, v); }
    public static boolean saveZoneShowLocationName(boolean v) { return saveLeaf(ZONE_SHOW_LOCATION, v); }

    /** Persist the zone HUD position preset + pixel offsets (offsets as INTEGER leaves) in one write. */
    public static boolean saveZoneHudPosition(@Nonnull String preset, int x, int y) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(ZONE_POSITION, preset);
        m.put(ZONE_OFFSET_X, x); // int -> Integer (Codec.INTEGER)
        m.put(ZONE_OFFSET_Y, y);
        return saveLeaves(m);
    }

    // ---------------------------------------------------------------------
    // Typed InspectorHud wrappers
    // ---------------------------------------------------------------------

    public static boolean saveInspectorHudEnabled(boolean v) { return saveLeaf(INSPECTOR_ENABLED, v); }
    public static boolean saveInspectorPortraitEnabled(boolean v) { return saveLeaf(INSPECTOR_PORTRAIT, v); }
    public static boolean saveInspectorRange(double v) { return saveLeaf(INSPECTOR_RANGE, v); }

    /** Persist the inspector HUD position preset + pixel offsets (offsets as INTEGER leaves) in one write. */
    public static boolean saveInspectorHudPosition(@Nonnull String preset, int x, int y) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(INSPECTOR_POSITION, preset);
        m.put(INSPECTOR_OFFSET_X, x);
        m.put(INSPECTOR_OFFSET_Y, y);
        return saveLeaves(m);
    }

    // ---------------------------------------------------------------------
    // Per-world WorldOverrides (whole-element upsert / remove by Match)
    // ---------------------------------------------------------------------

    /**
     * Upsert one WHOLE world override by {@code Match} (the fold replaces a {@code WorldOverride} by Match
     * wholesale, so the caller passes the FULL entry it wants to keep), then refold. {@code entryLeaves}
     * are dotted-PascalCase leaves of the entry (e.g. {@code "Intensity"}, {@code "Difficulty.MinCap"}).
     */
    public static boolean upsertWorldOverride(@Nonnull String match, @Nonnull Map<String, Object> entryLeaves) {
        Path p = path();
        if (p == null || !JsonOverrideWriter.upsertArrayEntry(p, WORLD_OVERRIDES, MATCH, match, entryLeaves)) {
            return false;
        }
        refold();
        return true;
    }

    /** Remove the owner world override with this {@code Match} (a jar/preset entry then re-folds as default). */
    public static boolean removeWorldOverride(@Nonnull String match) {
        Path p = path();
        if (p == null || !JsonOverrideWriter.removeArrayEntry(p, WORLD_OVERRIDES, MATCH, match)) {
            return false;
        }
        refold();
        return true;
    }

    /**
     * The lower-cased set of {@code Match} patterns the OWNER file itself authors, so the admin page can
     * badge a folded override row as an owner OVERRIDE (removable) vs a jar/pack DEFAULT (read-only).
     */
    @Nonnull
    public static Set<String> ownerAuthoredMatches() {
        Path p = path();
        if (p == null) {
            return Set.of();
        }
        List<String> raw = JsonOverrideWriter.readArrayKeyValues(p, WORLD_OVERRIDES, MATCH);
        Set<String> out = new HashSet<>(raw.size());
        for (String m : raw) {
            if (m != null && !m.isBlank()) {
                out.add(m.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
