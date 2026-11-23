package com.ziggfreed.mmomobscaling.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.owner.OwnerFileBinding;
import com.ziggfreed.common.owner.OwnerFileBindings;
import com.ziggfreed.common.ui.owner.OwnerMenu;
import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.asset.AffixAsset;
import com.ziggfreed.mmomobscaling.roster.Rosters;

/**
 * The server owner's word on affixes, at {@code mods/MmoMobScaling/affixes.json}: a map from an affix id to the
 * leaves that affix should read differently, each decoded leaf by leaf against the pack's own affix through the same
 * codec (CommonLib's one owner reader, {@code OwnerLayerReader}), so a file holding only what it changes keeps the
 * rest:
 *
 * <pre>{@code
 * { "Rallying": { "Roll": { "Weight": 1.0 }, "Cry": { "BuffSeconds": 10, "Radius": 14 } },
 *   "Swift":    { "Roll": { "Weight": 0 } } }   // 0 never picks it
 * }</pre>
 *
 * <p>The file is registered as CommonLib's owner file {@value #BINDING} ({@code OwnerFileBindings}), so the owner form
 * {@code Mmoscaling_Affix_Cries} on {@code /zigadmin} writes it one affix at a time and reads it back through
 * {@link #reload()}. Every read rebuilds the runtime view: the {@code Affix} records in {@link AffixConfig} and the
 * affix roster ({@code roster/Rosters}).
 */
public final class AffixOwnerLayer {

    /** The owner file's name, beside {@code mob-scaling.json}. */
    public static final String FILE = "affixes.json";

    /** The owner file's id as an owner form names it: its path under {@code mods/}. */
    public static final String BINDING = "MmoMobScaling/" + FILE;

    /** This mod's section of the owner menu, by its id: what its owner forms' {@code Section} names. */
    public static final String MENU_SECTION = "mmomobscaling";

    /** The section's place on the owner menu, after the MMO's and the library's. */
    private static final int MENU_ORDER = 300;

    private static final String LOG_TAG = "mobscaling";

    private AffixOwnerLayer() {
    }

    /** Where the owner file lives: beside the settings owner file, else {@code mods/MmoMobScaling/}. */
    @Nonnull
    public static Path file() {
        Path settings = MobScalingConfig.getInstance().getConfigPath();
        Path dir = settings == null ? null : settings.getParent();
        return (dir != null ? dir : Paths.get("mods", "MmoMobScaling")).resolve(FILE);
    }

    /** The binding the owner form writes through (registered once at setup, inside the enabled branch). */
    @Nonnull
    public static OwnerFileBinding binding() {
        return OwnerFileBinding.keyedStore(BINDING, AffixOwnerLayer::file, AffixAssetConfig.getInstance(),
                AffixAsset.CODEC, AffixOwnerLayer::reload);
    }

    /**
     * Register {@link #binding()} with CommonLib, so the owner form can write the file, and open this mod's section of
     * the owner menu ({@code /zigadmin}) its forms sit in, headed with this mod's name.
     */
    public static void register() {
        OwnerFileBindings.register(binding());
        OwnerMenu.section(MENU_SECTION, Message.translation("mmomobscaling.ui.owner.section"), MENU_ORDER);
    }

    /**
     * The packs' affixes landed (the affix store's load event): fold them as the pack layer, then read the owner file
     * over them and rebuild the runtime view ({@link #reload()}).
     */
    public static void mergePackLayer(@Nonnull Map<String, AffixAsset> layer) {
        AffixAssetConfig.getInstance().mergePackLayer(layer);
        reload();
    }

    /** (Re)read the owner file into the owner layer, then rebuild the runtime view ({@link #refold()}). */
    public static void reload() {
        OwnerLayerReader.apply(LOG_TAG, file(), AffixAsset.class, AffixAsset.CODEC, AffixAssetConfig.getInstance(),
                "affix");
        refold();
    }

    /**
     * Rebuild the {@code Affix} records from the folded assets (owner over pack over the jar's own files) into
     * {@link AffixConfig}, and the roster the spawn roll draws from.
     */
    public static void refold() {
        AffixAssetConfig assets = AffixAssetConfig.getInstance();
        Map<String, Affix> records = new LinkedHashMap<>();
        for (Map.Entry<String, AffixAsset> entry : assets.all().entrySet()) {
            AffixAsset asset = entry.getValue();
            if (asset != null) {
                records.put(entry.getKey(), asset.toAffix(spelling(assets, entry.getKey(), asset)));
            }
        }
        AffixConfig.getInstance().mergePackLayer(records);
        Rosters.rebuild();
    }

    /**
     * The id an affix keeps whoever last wrote it: the pack file's own spelling ({@code Rallying}), never the
     * lower-cased key an owner entry is filed under, so a mob's affix ids read the same with or without the owner's
     * file; the entry's own id, then the key, for an affix only the owner's file holds.
     */
    @Nonnull
    private static String spelling(@Nonnull AffixAssetConfig assets, @Nonnull String key, @Nonnull AffixAsset asset) {
        AffixAsset shipped = assets.resolveBelowOwner(key);
        if (shipped != null && shipped.getId() != null && !shipped.getId().isBlank()) {
            return shipped.getId();
        }
        return asset.getId() != null && !asset.getId().isBlank() ? asset.getId() : key;
    }
}
