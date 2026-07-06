package com.ziggfreed.mmomobscaling.family;

import java.util.List;

import javax.annotation.Nonnull;

/**
 * A resolved mob-FAMILY filter: which mob families a rollable content entry (a rarity today; a future
 * variant/flavor overlay axis tomorrow - this type is deliberately axis-neutral) may or may not apply to.
 * Immutable, pure data, no engine coupling.
 *
 * <p>Two independent, composable match mechanisms per side:
 * <ul>
 *   <li><b>Groups</b> ({@code allowGroups}/{@code denyGroups}) - native {@code NPCGroup} tagset ids, resolved
 *       to role membership by {@link MobFamilyMatcher} (the engine-coupled half).</li>
 *   <li><b>Roles</b> ({@code allowRoles}/{@code denyRoles}) - role-name globs matched here (pure) via
 *       {@link FamilyGlob}, mirroring native {@code IncludeRoles} semantics ({@code Spider*}), case-insensitive.</li>
 * </ul>
 *
 * <p><b>Eligibility (deny wins):</b>
 * <pre>{@code
 *   allowed  = (allowGroups empty AND allowRoles empty)          // unrestricted allow side = allow all
 *              OR matchesAnyAllowGroup OR matchesAnyAllowRole;
 *   denied   = matchesAnyDenyGroup OR matchesAnyDenyRole;
 *   eligible = allowed AND NOT denied;
 * }</pre>
 * The "empty allow side = allow all" test spans BOTH allow lists together: {@code allowGroups=["Spiders"]}
 * with an empty {@code allowRoles} still means "Spiders only" (an empty list adds no patterns, it does not
 * widen). An entirely empty filter is {@link #ALLOW_ALL} ({@link #isUnrestricted()} short-circuits to
 * eligible, preserving the pre-feature behavior). This type owns only the ROLE-glob half of the decision;
 * the GROUP half needs the live asset map, so the full evaluation lives in {@link MobFamilyMatcher}.
 */
public record FamilyFilter(
        @Nonnull List<String> allowGroups,
        @Nonnull List<String> denyGroups,
        @Nonnull List<String> allowRoles,
        @Nonnull List<String> denyRoles) {

    /** The neutral filter: no restriction, every mob is eligible (the absent-{@code Families} default). */
    public static final FamilyFilter ALLOW_ALL = new FamilyFilter(List.of(), List.of(), List.of(), List.of());

    public FamilyFilter {
        allowGroups = List.copyOf(allowGroups);
        denyGroups = List.copyOf(denyGroups);
        allowRoles = List.copyOf(allowRoles);
        denyRoles = List.copyOf(denyRoles);
    }

    /** True when no list constrains anything - every mob is eligible, the matcher can short-circuit. */
    public boolean isUnrestricted() {
        return allowGroups.isEmpty() && denyGroups.isEmpty() && allowRoles.isEmpty() && denyRoles.isEmpty();
    }

    /** True when the allow SIDE places no role/group constraint (both allow lists empty) - i.e. allow all. */
    public boolean allowsAll() {
        return allowGroups.isEmpty() && allowRoles.isEmpty();
    }

    /** True when {@code roleName} matches any {@code allowRoles} glob (pure; the GROUP half is the matcher's). */
    public boolean allowsRole(@Nonnull String roleName) {
        return matchesAny(allowRoles, roleName);
    }

    /** True when {@code roleName} matches any {@code denyRoles} glob (pure; the GROUP half is the matcher's). */
    public boolean deniesRole(@Nonnull String roleName) {
        return matchesAny(denyRoles, roleName);
    }

    private static boolean matchesAny(@Nonnull List<String> patterns, @Nonnull String roleName) {
        for (String p : patterns) {
            if (p != null && !p.isBlank() && FamilyGlob.matches(p, roleName)) {
                return true;
            }
        }
        return false;
    }
}
