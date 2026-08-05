package com.ziggfreed.mmomobscaling.asset;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.ziggfreed.mmomobscaling.config.MobScalingConfig;

/**
 * Boot-time diagnostic for CONTENT PACKS that author mob-scaling assets: it names, in the server log,
 * every pack whose overrides are being silently ignored and says exactly which manifest line fixes it.
 * Log-only - it never fails a load and never changes what is loaded.
 *
 * <p>Why it exists: Hytale layers asset packs LAST-PACK-WINS in dependency-resolved load order. A pack
 * that authors {@code Server/MmoMobScaling/**} (or one of this mod's {@code Server/NPC/Groups/Mmoscaling_*.json}
 * tagsets) but does NOT declare {@code "Ziggfreed:MmoMobScaling"} in its manifest {@code Dependencies}
 * sorts into an EARLIER load wave than this mod, so the mod's own jar defaults overwrite every one of
 * that pack's overrides. Nothing in the engine logs that, and the pack author sees a pack that "does
 * nothing". This audit is the missing log line.
 *
 * <p>Shape mirrors {@code MobScalingGate}: the DECISION is a pure static function over a small
 * {@link PackInfo} record (unit-testable in a plain JVM, no engine classes), and {@link #run()} is a
 * thin, fully {@code try/catch(Throwable)}-guarded collector that reads the live
 * {@link AssetModule#getAssetPacks()} list and feeds it in.
 */
public final class PackDependencyAudit {

    /** Manifest group of this mod (the {@code Group} in {@code gradle.properties} / {@code manifest.json}). */
    public static final String OWN_GROUP = "Ziggfreed";

    /** Manifest name of this mod. CASE-SENSITIVE in a dependency id - {@code MMOMobScaling} does not resolve. */
    public static final String OWN_NAME = "MmoMobScaling";

    /** The exact dependency id an extension pack must declare, e.g. {@code "Ziggfreed:MmoMobScaling"}. */
    public static final String OWN_DEPENDENCY_ID = OWN_GROUP + ":" + OWN_NAME;

    /** Content root this mod's own asset stores are served from (under a pack's {@code Server/}). */
    private static final String OUR_CONTENT_DIR = "MmoMobScaling";

    /** Filename glob for the native tagset assets this mod owns and a pack may legitimately override. */
    private static final String OUR_GROUPS_GLOB = "Mmoscaling_*.json";

    /**
     * One loaded asset pack, reduced to the three facts the decision needs. Engine-free on purpose so
     * {@link #findings} and {@link #confirmations} stay unit-testable.
     *
     * @param name               the pack's display name, as the engine reports it
     * @param authorsOurAssets   the pack ships {@code Server/MmoMobScaling/**} or a
     *                           {@code Server/NPC/Groups/Mmoscaling_*.json} tagset override
     * @param declaresDependency the pack's manifest {@code Dependencies} carries {@link #OWN_DEPENDENCY_ID}
     */
    public record PackInfo(@Nonnull String name, boolean authorsOurAssets, boolean declaresDependency) {
    }

    private PackDependencyAudit() {
    }

    /**
     * The pure decision: one human-readable WARNING per pack whose mob-scaling content is being ignored
     * or is only working by luck. Empty = every authoring pack layers correctly (or there are none).
     *
     * @param packsInLoadOrder the loaded packs in the engine's resolved load order (earliest first)
     * @param ownPackName      this mod's own pack name, or {@code null} when it could not be identified
     *                         (then the ordering half is skipped and only the missing-dependency half runs,
     *                         so an unknown never produces a wrong "loads before us" claim)
     * @param scalingEnabled   {@code MobScalingConfig.isEnabled()} - a disabled mod registers no asset
     *                         stores at all, so a pack's files are never even visited
     */
    @Nonnull
    public static List<String> findings(@Nonnull List<PackInfo> packsInLoadOrder,
            @Nullable String ownPackName, boolean scalingEnabled) {
        List<String> out = new ArrayList<>();
        int ours = indexOf(packsInLoadOrder, ownPackName);
        for (int i = 0; i < packsInLoadOrder.size(); i++) {
            PackInfo pack = packsInLoadOrder.get(i);
            if (!pack.authorsOurAssets() || isOwnPack(pack, ownPackName)) {
                continue;
            }
            if (!scalingEnabled) {
                out.add("content pack '" + pack.name() + "' authors mob-scaling assets, but mob scaling is"
                        + " DISABLED in mods/MmoMobScaling/mob-scaling.json - no asset store is registered,"
                        + " so those files are not loaded at all. Set Enabled to true and restart.");
                continue;
            }
            if (ours >= 0 && i < ours) {
                out.add("content pack '" + pack.name() + "' authors mob-scaling assets but loads BEFORE"
                        + " MmoMobScaling, so the mod's own defaults overwrite its overrides and they are"
                        + " IGNORED. Add \"" + OWN_DEPENDENCY_ID + "\": \">=1.1.0\" to that pack's"
                        + " manifest.json Dependencies (the id is case-sensitive).");
                continue;
            }
            if (!pack.declaresDependency()) {
                out.add("content pack '" + pack.name() + "' authors mob-scaling assets without declaring"
                        + " MmoMobScaling as a dependency. It happens to load after the mod right now, so"
                        + " its overrides apply, but that ordering is not guaranteed. Add \""
                        + OWN_DEPENDENCY_ID + "\": \">=1.1.0\" to its manifest.json Dependencies.");
            }
        }
        return out;
    }

    /**
     * The positive half: one INFO line per pack that layers correctly (declares the dependency AND loads
     * after this mod), so a correctly authored pack gets confirmation instead of silence. Empty when
     * scaling is disabled, this mod's own pack is unidentifiable, or no pack layers our content.
     */
    @Nonnull
    public static List<String> confirmations(@Nonnull List<PackInfo> packsInLoadOrder,
            @Nullable String ownPackName, boolean scalingEnabled) {
        List<String> out = new ArrayList<>();
        int ours = indexOf(packsInLoadOrder, ownPackName);
        if (!scalingEnabled || ours < 0) {
            return out;
        }
        for (int i = ours + 1; i < packsInLoadOrder.size(); i++) {
            PackInfo pack = packsInLoadOrder.get(i);
            if (pack.authorsOurAssets() && pack.declaresDependency() && !isOwnPack(pack, ownPackName)) {
                out.add("content pack '" + pack.name() + "' layers mob-scaling assets correctly"
                        + " (declares the dependency and loads after the mod).");
            }
        }
        return out;
    }

    /**
     * Collect the live pack list and log the audit. Fully guarded: any engine surprise degrades to one
     * warning, never a throw into the {@code BootEvent} dispatch.
     *
     * <p>Runs at {@code BootEvent}, after every pack has loaded, and is registered OUTSIDE the mod's
     * zero-cost gate - a disabled mod must still be able to explain why a pack's content did nothing.
     */
    public static void run() {
        List<PackInfo> packs;
        String ownPackName;
        boolean enabled;
        try {
            List<AssetPack> loaded = AssetModule.get().getAssetPacks();
            packs = new ArrayList<>(loaded.size());
            ownPackName = null;
            for (AssetPack pack : loaded) {
                if (isOwnManifest(pack)) {
                    ownPackName = pack.getName();
                }
                packs.add(new PackInfo(pack.getName(), authorsOurAssets(pack), declaresOurDependency(pack)));
            }
            enabled = MobScalingConfig.getInstance().isEnabled();
        } catch (Throwable t) {
            MobScalingAssetRegistrar.warnFindings(List.of(
                    "pack-dependency audit skipped (asset pack list unavailable): " + t));
            return;
        }
        MobScalingAssetRegistrar.warnFindings(findings(packs, ownPackName, enabled));
        MobScalingAssetRegistrar.infoLines(confirmations(packs, ownPackName, enabled));
    }

    // ==================== Engine-facing collection (thin, guarded) ====================

    /** True when {@code pack} is this mod's own jar pack (manifest group + name match exactly). */
    private static boolean isOwnManifest(@Nonnull AssetPack pack) {
        try {
            PluginManifest manifest = pack.getManifest();
            return manifest != null
                    && OWN_GROUP.equals(manifest.getGroup())
                    && OWN_NAME.equals(manifest.getName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when {@code pack}'s manifest declares {@link #OWN_DEPENDENCY_ID} as a hard dependency. */
    private static boolean declaresOurDependency(@Nonnull AssetPack pack) {
        try {
            PluginManifest manifest = pack.getManifest();
            return manifest != null
                    && manifest.getDependencies().containsKey(new PluginIdentifier(OWN_GROUP, OWN_NAME));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when {@code pack} ships content this mod claims: its own {@code Server/MmoMobScaling/} tree OR
     * a {@code Server/NPC/Groups/Mmoscaling_*.json} tagset override (the boss / excluded classification
     * lists, which shadow the same way and are just as silent about it).
     */
    private static boolean authorsOurAssets(@Nonnull AssetPack pack) {
        try {
            Path server = pack.getRoot().resolve("Server");
            if (Files.isDirectory(server.resolve(OUR_CONTENT_DIR))) {
                return true;
            }
            Path groups = server.resolve("NPC").resolve("Groups");
            if (!Files.isDirectory(groups)) {
                return false;
            }
            try (DirectoryStream<Path> matches = Files.newDirectoryStream(groups, OUR_GROUPS_GLOB)) {
                return matches.iterator().hasNext();
            }
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== Pure helpers ====================

    private static boolean isOwnPack(@Nonnull PackInfo pack, @Nullable String ownPackName) {
        return ownPackName != null && ownPackName.equals(pack.name());
    }

    /** Index of {@code ownPackName} in the load order, or {@code -1} when absent/unknown. */
    private static int indexOf(@Nonnull List<PackInfo> packs, @Nullable String ownPackName) {
        if (ownPackName == null) {
            return -1;
        }
        for (int i = 0; i < packs.size(); i++) {
            if (ownPackName.equals(packs.get(i).name())) {
                return i;
            }
        }
        return -1;
    }
}
