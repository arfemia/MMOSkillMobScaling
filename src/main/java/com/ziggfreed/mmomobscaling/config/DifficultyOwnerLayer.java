package com.ziggfreed.mmomobscaling.config;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.logger.HytaleLogger;
import com.ziggfreed.mmomobscaling.asset.DifficultyMappingAsset;
import com.ziggfreed.mmomobscaling.world.DifficultyMapping;

/**
 * The OWNER layer of the zone/biome difficulty floors: {@code mods/MmoMobScaling/difficulty/<id>.json},
 * one file per mapping, scanned into {@link DifficultyConfig#mergeOwnerLayer} so a server owner
 * retunes a floor without a content pack. The folder follows the {@code worlds/} convention exactly
 * (the filename stem is the id, a bare {@code DifficultyMappingAsset} body is canonical, a
 * {@code Payload} wrapper is peeled, a {@code README.txt} is seeded), and a file lays out like a
 * shipped {@code Server/MmoMobScaling/Difficulty/<id>.json}, so one copies over verbatim.
 *
 * <p><b>An owner file overlays the same-id shipped mapping PER LEAF.</b> {@code Zone2.json} holding
 * only {@code {"Floor": 60.0}} retunes the shipped Zone2 floor and inherits its {@code TargetType}
 * and {@code TargetId}; a file naming an id nothing ships must carry all three leaves, and one that
 * does not is skipped with a warning that says which leaf is missing. Delete a file and the shipped
 * mapping stands again on the next refold. The layer refolds on the difficulty store's
 * {@code LoadedAssetsEvent} (the shipped mappings a partial file inherits from arrive there) and
 * after every {@link MobScalingOwnerWriter} write, so a runtime edit applies live; the derived
 * zone/biome index rebuilds inside {@link DifficultyConfig}.
 */
public final class DifficultyOwnerLayer {

    /** Body of the seeded owner-dir readme: what goes in this folder and how it layers. */
    private static final String README_TEXT = """
            MMO Mob Scaling - zone and biome difficulty floors
            ==================================================

            One file per mapping. The filename (without .json) is the mapping id, and the body uses
            the same PascalCase keys as the shipped mappings under Server/MmoMobScaling/Difficulty/:

              {
                "TargetType": "Zone",
                "TargetId": "Zone2",
                "Floor": 12.0
              }

            Key points:
              - "TargetType" is Zone or Biome; "TargetId" is the game's own zone or biome name, or "*"
                for every zone / every biome that has no mapping of its own. A zone mapping also covers
                its sub-zones: "Zone2" floors Zone2_Tier1 and Zone2_Shore unless a longer mapping names
                them.
              - "Floor" is the difficulty a spawn in that zone or biome starts from, before distance
                escalation and nearby player power are added.
              - A file named after a shipped mapping OVERRIDES it leaf by leaf: a file holding only
                "Floor" retunes that mapping and keeps its target. A file with a new name must carry
                all three keys.
              - Delete a file to get the shipped mapping back.
              - Changes are picked up on server restart.

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

    private static final DifficultyOwnerLayer INSTANCE = new DifficultyOwnerLayer();

    @Nonnull
    public static DifficultyOwnerLayer getInstance() {
        return INSTANCE;
    }

    /** Owner-dir path ({@code mods/MmoMobScaling/difficulty}); {@code null} = no owner layer (tests). */
    @Nullable private Path ownerDir;

    /** Ids the owner dir published this refold (lower-cased), for a listing's override badge. */
    @Nonnull private volatile Set<String> ownerIds = Set.of();

    private DifficultyOwnerLayer() {
    }

    /**
     * The scanned owner directory; {@code null} = none. Setting a non-null dir SCAFFOLDS it (folder +
     * readme) so a fresh install shows the folder the docs point at; it does not scan. Call
     * {@link #refold()} once the shipped mappings a partial file inherits from are loaded.
     */
    public void setOwnerDir(@Nullable Path ownerDir) {
        this.ownerDir = ownerDir;
        if (ownerDir != null) {
            OwnerFiles.ensureDir(ownerDir, README_TEXT, DifficultyOwnerLayer::warn);
        }
    }

    @Nullable
    public Path getOwnerDir() {
        return ownerDir;
    }

    /**
     * The owner-dir file a given mapping id maps to, found ignoring case among the files already there
     * ({@link OwnerFiles#resolveFile}), else the canonical lower-cased name a new one is created at;
     * {@code null} when no owner dir is set.
     */
    @Nullable
    public Path ownerFileFor(@Nonnull String id) {
        Path dir = this.ownerDir;
        return dir == null ? null : OwnerFiles.resolveFile(dir, id, DifficultyOwnerLayer::warn);
    }

    /** Ids whose mapping the owner dir published this refold (lower-cased). */
    @Nonnull
    public Set<String> ownerAuthoredIds() {
        return ownerIds;
    }

    /**
     * Re-scan the owner dir, overlay each file per leaf on the same-id shipped mapping
     * ({@link DifficultyConfig#packMapping}), and publish the result as the owner layer (the derived
     * index rebuilds there). With no owner dir the layer is published EMPTY, so a dropped dir folds
     * back to the shipped mappings.
     */
    public synchronized void refold() {
        Path dir = this.ownerDir;
        Map<String, JsonObject> bodies = dir == null ? Map.of() : OwnerFiles.scanJsonBodies(dir, DifficultyOwnerLayer::warn);
        DifficultyConfig config = DifficultyConfig.getInstance();
        Map<String, DifficultyMapping> layer = new LinkedHashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Map.Entry<String, JsonObject> e : bodies.entrySet()) {
            DifficultyMapping mapping = overlay(e.getKey(), e.getValue(), config.packMapping(e.getKey()));
            if (mapping != null) {
                layer.put(e.getKey(), mapping);
                ids.add(e.getKey());
            }
        }
        this.ownerIds = Collections.unmodifiableSet(ids);
        config.mergeOwnerLayer(layer);
    }

    /**
     * One owner body decoded through the ONE schema authority ({@code DifficultyMappingAsset.CODEC})
     * and overlaid per leaf on {@code base}, the same-id shipped mapping (null when nothing ships
     * under this id). An authored leaf wins; an absent leaf inherits; a leaf absent on both sides is
     * a warning + skip, as is an authored {@code TargetType} the codec does not know.
     */
    @Nullable
    static DifficultyMapping overlay(@Nonnull String id, @Nonnull JsonObject body, @Nullable DifficultyMapping base) {
        DifficultyMappingAsset asset;
        try {
            asset = DifficultyMappingAsset.CODEC.decodeJson(RawJsonReader.fromJsonString(body.toString()), new ExtraInfo());
        } catch (Exception e) {
            warn("difficulty mapping '" + id + "' is malformed and was skipped: " + e.getMessage());
            return null;
        }
        DifficultyMapping.TargetType type;
        if (asset.getTargetType() != null) {
            type = DifficultyMapping.TargetType.parse(asset.getTargetType());
            if (type == null) {
                warn("difficulty mapping '" + id + "' skipped: TargetType must be Zone or Biome, not '"
                        + asset.getTargetType() + "'");
                return null;
            }
        } else {
            type = base == null ? null : base.targetType();
        }
        String targetId = asset.getTargetId() != null && !asset.getTargetId().isBlank()
                ? asset.getTargetId().trim() : (base == null ? null : base.targetId());
        Double floor = asset.getFloor() != null ? asset.getFloor() : (base == null ? null : base.floor());
        if (type == null || targetId == null || floor == null) {
            StringJoiner missing = new StringJoiner(", ");
            if (type == null) {
                missing.add("TargetType");
            }
            if (targetId == null) {
                missing.add("TargetId");
            }
            if (floor == null) {
                missing.add("Floor");
            }
            warn("difficulty mapping '" + id + "' skipped: no shipped mapping of that id to inherit from, so the"
                    + " file must carry TargetType, TargetId and Floor (missing: " + missing + ")");
            return null;
        }
        return new DifficultyMapping(id, type, targetId, floor);
    }

    /** Guarded warn (own logger, unit-JVM safe - the MobScalingConfig pattern). */
    private static void warn(@Nonnull String message) {
        if (LOGGER == null) {
            return;
        }
        try {
            LOGGER.atWarning().log("[DifficultyOwnerLayer] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM
        }
    }
}
