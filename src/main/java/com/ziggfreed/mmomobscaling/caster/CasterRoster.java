package com.ziggfreed.mmomobscaling.caster;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A resolved caster roster (the runtime model decoded from a
 * {@code Server/MmoMobScaling/CasterRosters/*.json}
 * {@link com.ziggfreed.mmomobscaling.asset.CasterRosterAsset}; {@link #id} is the asset filename).
 * Immutable, pure data - binds a {@code Role} selector ({@link #roleId} exact, XOR {@link #roleGlob})
 * to a list of {@link CasterEntry} abilities a matching mob may arm at spawn.
 *
 * <p>Exactly one of {@link #roleId} / {@link #roleGlob} is the valid authoring shape
 * ({@link #hasValidRoleSelector()}); the alternative (neither or both) is validator-flagged content,
 * not a decode failure - matching {@code ScalingContentValidator}'s existing "surface as a finding,
 * degrade at the consumption site" convention (a roster with no valid selector simply never matches
 * via {@code CasterRosterMatcher}).
 */
public record CasterRoster(
        @Nonnull String id,
        @Nullable String roleId,
        @Nullable String roleGlob,
        @Nonnull List<CasterEntry> abilities) {

    public CasterRoster {
        abilities = List.copyOf(abilities);
    }

    /** True when exactly one of {@link #roleId} / {@link #roleGlob} is authored (non-blank). */
    public boolean hasValidRoleSelector() {
        return hasRoleId() != hasRoleGlob();
    }

    public boolean hasRoleId() {
        return roleId != null && !roleId.isBlank();
    }

    public boolean hasRoleGlob() {
        return roleGlob != null && !roleGlob.isBlank();
    }
}
