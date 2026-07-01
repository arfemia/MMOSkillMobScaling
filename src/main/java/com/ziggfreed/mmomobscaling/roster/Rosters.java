package com.ziggfreed.mmomobscaling.roster;

import java.util.List;

import javax.annotation.Nonnull;

import com.ziggfreed.mmomobscaling.affix.AffixRoster;
import com.ziggfreed.mmomobscaling.config.AffixConfig;
import com.ziggfreed.mmomobscaling.config.RarityConfig;
import com.ziggfreed.mmomobscaling.rarity.RarityRoster;

/**
 * The prepared rarity/affix rosters the spawn hook rolls, rebuilt from the folded configs after each
 * {@code LoadedAssetsEvent} (so a pack/owner override or a hot reload re-buckets the pools). Reads are
 * lock-free off {@code volatile} refs; the spawn hook never rebuilds on the hot path. Starts EMPTY so a
 * spawn before assets load simply rolls plain (no rarity), never NPEs.
 */
public final class Rosters {

    private static volatile RarityRoster rarity = RarityRoster.build(List.of());
    private static volatile AffixRoster affix = AffixRoster.build(List.of());

    private Rosters() {
    }

    /** Rebuild both rosters from the current folded configs (called from the asset load listeners). */
    public static void rebuild() {
        rarity = RarityRoster.build(RarityConfig.getInstance().all().values());
        affix = AffixRoster.build(AffixConfig.getInstance().all().values());
    }

    @Nonnull
    public static RarityRoster rarity() {
        return rarity;
    }

    @Nonnull
    public static AffixRoster affix() {
        return affix;
    }
}
