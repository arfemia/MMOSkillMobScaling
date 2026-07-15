package com.ziggfreed.mmomobscaling.caster;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.mmomobscaling.family.FamilyGlob;

/**
 * Resolves the best-matching {@link CasterRoster} for a spawning mob's role name. Pure logic (no
 * engine coupling), so it is directly unit-testable without a live asset store - the first
 * implementation of the planned {@code BossCurve} keying pattern this mod will reuse.
 *
 * <p>Precedence (most specific wins), matching the family-filter glob policy
 * ({@link FamilyGlob}, native {@code IncludeRoles} semantics, case-insensitive):
 * <ol>
 *   <li><b>exact {@code Role.Id}</b> - an exact (case-insensitive) role-name match wins outright</li>
 *   <li><b>longest matching {@code Role.Glob}</b> - among rosters whose glob matches the role name,
 *       the one with the LONGEST authored pattern string wins (a more specific pattern beats a
 *       broader one, e.g. {@code "Dragon_Fire"} exact beats {@code "Dragon_*"} beats {@code "*"})</li>
 *   <li><b>first</b> - a tie in glob length keeps whichever roster was encountered FIRST in the
 *       supplied (caller-ordered) list; callers pass a stable, deterministic order (e.g. id-sorted)
 *       so a tie resolves the same way every time</li>
 * </ol>
 *
 * <p>A roster with neither/both of {@code Role.Id}/{@code Role.Glob} authored
 * ({@code !roster.hasValidRoleSelector()}, a validator-flagged content bug) never matches anything
 * here - it is silently skipped, not an error.
 */
public final class CasterRosterMatcher {

    private CasterRosterMatcher() {
    }

    /**
     * Resolve the best-matching roster for {@code roleName} among {@code rosters}, or {@code null}
     * when none match. {@code rosters} should be in a stable, deterministic order (the tie-break for
     * equal-length glob matches is "first in this list").
     */
    @Nullable
    public static CasterRoster match(@Nonnull String roleName, @Nonnull List<CasterRoster> rosters) {
        CasterRoster exact = null;
        CasterRoster bestGlob = null;
        int bestGlobLen = -1;

        for (CasterRoster r : rosters) {
            if (r == null) {
                continue;
            }
            if (r.hasRoleId() && r.roleId().equalsIgnoreCase(roleName)) {
                if (exact == null) {
                    exact = r;
                }
                continue;
            }
            if (!r.hasRoleGlob()) {
                continue;
            }
            String glob = r.roleGlob();
            if (!FamilyGlob.matches(glob, roleName)) {
                continue;
            }
            int len = glob.length();
            if (len > bestGlobLen) {
                bestGlobLen = len;
                bestGlob = r;
            }
        }

        return exact != null ? exact : bestGlob;
    }
}
