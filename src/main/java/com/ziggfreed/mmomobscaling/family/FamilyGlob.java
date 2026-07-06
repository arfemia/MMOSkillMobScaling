package com.ziggfreed.mmomobscaling.family;

import java.util.Locale;

import javax.annotation.Nonnull;

import com.hypixel.hytale.common.util.StringUtil;

/**
 * Case-insensitive role-name glob matching, the single home for the family-filter glob policy. Delegates to
 * the NATIVE matcher {@link StringUtil#isGlobMatching(String, String)} (the same routine Hytale's
 * {@code TagSetLookupTable} uses to resolve an {@code NPCGroup} {@code IncludeRoles} wildcard), so the inline
 * {@code AllowRoles}/{@code DenyRoles} patterns behave EXACTLY like native {@code IncludeRoles}: {@code *}
 * matches any run, {@code ?} matches one character. The native routine is case-SENSITIVE, so both sides are
 * lower-cased here to get the case-insensitive match the family filter wants (so {@code "spider*"} matches
 * {@code "Spider_Cave"}).
 *
 * <p>Pure String logic (no engine state), so the role side of the filter is unit-testable without a live
 * asset store. The {@code StringUtil} class is a pure static in the server jar's {@code Common} module
 * (a {@code compileOnly} dependency), reachable from the test JVM.
 */
public final class FamilyGlob {

    private FamilyGlob() {
    }

    /**
     * True when {@code text} matches the glob {@code pattern}, case-insensitively. A pattern with no
     * wildcard is a plain case-insensitive equality (the native routine's fast path). Never throws for a
     * malformed pattern - a stray wildcard simply widens the match.
     */
    public static boolean matches(@Nonnull String pattern, @Nonnull String text) {
        return StringUtil.isGlobMatching(pattern.toLowerCase(Locale.ROOT), text.toLowerCase(Locale.ROOT));
    }
}
