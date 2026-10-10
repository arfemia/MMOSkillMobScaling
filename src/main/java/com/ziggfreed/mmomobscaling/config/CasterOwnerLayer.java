package com.ziggfreed.mmomobscaling.config;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.logger.HytaleLogger;
import com.ziggfreed.mmomobscaling.asset.CasterRosterAsset;
import com.ziggfreed.mmomobscaling.caster.CasterRoster;

/**
 * The OWNER layer of the NPC caster rosters: {@code mods/MmoMobScaling/casters/<id>.json}, one file per
 * roster, scanned into {@link CasterRosterConfig#mergeOwnerLayer} so a server owner switches a roster on
 * or off, or adds one, without a content pack. The folder follows the {@code difficulty/} convention
 * exactly ({@link OwnerFiles}: the filename stem is the id, a bare {@code CasterRosterAsset} body is
 * canonical, a {@code Payload} wrapper is peeled, a {@code README.txt} is seeded), and a file lays out
 * like a shipped {@code Server/MmoMobScaling/CasterRosters/<id>.json}, so one copies over verbatim.
 *
 * <p><b>An owner file overlays the same-id shipped roster PER LEAF</b>
 * ({@link CasterRosterAsset#overlayOn}): {@code demo_boss_caster.json} holding only
 * {@code {"Enabled": true}} switches the shipped example on and keeps its {@code Role} and
 * {@code Abilities}; an authored {@code Abilities} replaces the shipped list whole. A file naming an id
 * nothing ships must carry its own {@code Role}, and one that does not is skipped with a warning. Delete a
 * file and the shipped roster stands again on the next refold. The layer refolds on the caster store's
 * {@code LoadedAssetsEvent}, once the shipped rosters a partial file inherits from have landed.
 */
public final class CasterOwnerLayer {

    /** Body of the seeded owner-dir readme: what goes in this folder and how it layers. */
    private static final String README_TEXT = """
            MMO Mob Scaling - caster rosters
            ================================

            One file per roster. The filename (without .json) is the roster id, and the body uses the
            same keys as the shipped rosters under Server/MmoMobScaling/CasterRosters/.

            The mod ships one example, Demo_Boss_Caster, switched off. With it on, the Ember Dragon
            casts Fireball and an ice ball and dodges now and then. To switch it on, save this as
            demo_boss_caster.json in this folder:

              {
                "Enabled": true
              }

            Key points:
              - A file named after a shipped roster OVERRIDES it key by key: a file holding only
                "Enabled" keeps the shipped "Role" and "Abilities". An "Abilities" list you write
                replaces the shipped list whole.
              - "Enabled": false switches a roster off, shipped or yours. A mob it would have armed
                falls to any other roster that matches it.
              - A file with a new name is a new roster and must carry "Role" (one of "Id" for one
                mob role or "Glob" for a family) and its "Abilities".
              - Delete a file to get the shipped roster back.
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

    private static final CasterOwnerLayer INSTANCE = new CasterOwnerLayer();

    @Nonnull
    public static CasterOwnerLayer getInstance() {
        return INSTANCE;
    }

    /** Owner-dir path ({@code mods/MmoMobScaling/casters}); {@code null} = no owner layer (tests). */
    @Nullable private Path ownerDir;

    /** Ids the owner dir published this refold (id keys), for the boot line's count. */
    @Nonnull private volatile Set<String> ownerIds = Set.of();

    private CasterOwnerLayer() {
    }

    /**
     * The scanned owner directory; {@code null} = none. Setting a non-null dir SCAFFOLDS it (folder +
     * readme) so a fresh install shows the folder the docs point at; it does not scan. Call
     * {@link #refold()} once the shipped rosters a partial file inherits from are loaded.
     */
    public void setOwnerDir(@Nullable Path ownerDir) {
        this.ownerDir = ownerDir;
        if (ownerDir != null) {
            OwnerFiles.ensureDir(ownerDir, README_TEXT, CasterOwnerLayer::warn);
        }
    }

    @Nullable
    public Path getOwnerDir() {
        return ownerDir;
    }

    /** Ids whose roster the owner dir published this refold (id keys). */
    @Nonnull
    public Set<String> ownerAuthoredIds() {
        return ownerIds;
    }

    /**
     * Re-scan the owner dir, overlay each file per leaf on the same-id shipped roster
     * ({@link CasterRosterConfig#packRoster}), and publish the result as the owner layer. With no owner
     * dir the layer is published EMPTY, so a dropped dir folds back to the shipped rosters. The caller
     * rebuilds {@code roster/Rosters} after it.
     */
    public synchronized void refold() {
        Path dir = this.ownerDir;
        Map<String, JsonObject> bodies = dir == null ? Map.of() : OwnerFiles.scanJsonBodies(dir, CasterOwnerLayer::warn);
        CasterRosterConfig config = CasterRosterConfig.getInstance();
        Map<String, CasterRoster> layer = new LinkedHashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Map.Entry<String, JsonObject> e : bodies.entrySet()) {
            CasterRoster roster = overlay(e.getKey(), e.getValue(), config.packRoster(e.getKey()));
            if (roster != null) {
                layer.put(e.getKey(), roster);
                ids.add(e.getKey());
            }
        }
        this.ownerIds = Collections.unmodifiableSet(ids);
        config.mergeOwnerLayer(layer);
    }

    /**
     * One owner body decoded through the ONE schema authority ({@code CasterRosterAsset.CODEC}) and
     * overlaid per leaf on {@code base}, the same-id shipped roster (null when nothing ships under this
     * id). A body with no {@code Role} and nothing to inherit one from is a warning + skip.
     */
    @Nullable
    static CasterRoster overlay(@Nonnull String id, @Nonnull JsonObject body, @Nullable CasterRoster base) {
        CasterRosterAsset asset = decode(id, body);
        if (asset == null) {
            return null;
        }
        if (base == null && !asset.authorsRole()) {
            warn("caster roster '" + id + "' skipped: no shipped roster of that id to inherit from, so the"
                    + " file must carry its own Role (and its Abilities)");
            return null;
        }
        return asset.overlayOn(id, base);
    }

    /** One owner body through {@code CasterRosterAsset.CODEC}; {@code null} + a warning when it does not decode. */
    @Nullable
    private static CasterRosterAsset decode(@Nonnull String id, @Nonnull JsonObject body) {
        try {
            return CasterRosterAsset.CODEC.decodeJson(RawJsonReader.fromJsonString(body.toString()), new ExtraInfo());
        } catch (Exception e) {
            warn("caster roster '" + id + "' is malformed and was skipped: " + e.getMessage());
            return null;
        }
    }

    /** Guarded warn (own logger, unit-JVM safe - the MobScalingConfig pattern). */
    private static void warn(@Nonnull String message) {
        if (LOGGER == null) {
            return;
        }
        try {
            LOGGER.atWarning().log("[CasterOwnerLayer] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM
        }
    }
}
