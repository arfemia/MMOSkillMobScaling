package com.ziggfreed.mmomobscaling.world;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.WorldOverride;

/**
 * Resolves the best per-world settings {@link WorldOverride} for a world name (1.0.1). Mirrors the MMO's
 * {@code WorldRulesMatcher} precedence EXACTLY so authoring is consistent across the two systems: instance
 * worlds spawn with a random suffix ({@code instance-dungeon_of_fear_i_asdf334rf}), so matching supports a
 * trailing {@code *} prefix.
 *
 * <p>Precedence (most specific wins):
 * <ol>
 *   <li><b>exact</b> - {@code Match} equals the world name (case-insensitive)</li>
 *   <li><b>longest prefix</b> - {@code Match} ends in {@code *}; the longest matching prefix wins (so
 *       {@code instance-dungeon_of_fear_ii*} beats {@code instance-dungeon_of_fear_i*} for
 *       {@code instance-dungeon_of_fear_ii}, and the bare {@code ..._i} world only matches {@code ..._i*}
 *       since the {@code ..._ii}/{@code ..._iii} prefixes are longer than it)</li>
 *   <li><b>bare {@code *}</b> - the catch-all override</li>
 *   <li>{@code null} - no override matched (the caller uses the global settings)</li>
 * </ol>
 *
 * <p>Each {@link Entry} pre-parses its pattern once at config-load time so per-lookup cost is a string
 * compare / {@code startsWith}. Resolution is run once per world and cached on {@code MobScalingConfig}.
 */
public final class WorldOverrideMatcher {

    private WorldOverrideMatcher() {
    }

    /** One authored override: a pre-parsed {@code Match} pattern bound to its {@link WorldOverride} body. */
    public static final class Entry {
        /** Raw pattern as authored, for diagnostics / validation (e.g. {@code "instance-dungeon_of_fear_i*"}). */
        @Nonnull
        public final String pattern;
        private final String normalized;   // lower-cased pattern
        private final boolean wildcardAll; // pattern == "*"
        private final boolean prefix;      // pattern ended in "*" (and was not just "*")
        private final String prefixLower;  // normalized minus the trailing "*"; "" when exact
        @Nonnull
        public final WorldOverride override;

        public Entry(@Nonnull String pattern, @Nonnull WorldOverride override) {
            this.pattern = pattern;
            this.override = override;
            String p = pattern.trim();
            this.normalized = p.toLowerCase(Locale.ROOT);
            if (p.equals("*")) {
                this.wildcardAll = true;
                this.prefix = false;
                this.prefixLower = "";
            } else if (p.endsWith("*")) {
                this.wildcardAll = false;
                this.prefix = true;
                this.prefixLower = normalized.substring(0, normalized.length() - 1);
            } else {
                this.wildcardAll = false;
                this.prefix = false;
                this.prefixLower = "";
            }
        }

        boolean isDefaultRule() {
            return wildcardAll;
        }

        boolean matchesExact(@Nonnull String worldLower) {
            return !wildcardAll && !prefix && normalized.equals(worldLower);
        }

        /** The matched prefix length (for longest-wins), or -1 if no prefix match. */
        int prefixMatchLength(@Nonnull String worldLower) {
            return (prefix && worldLower.startsWith(prefixLower)) ? prefixLower.length() : -1;
        }
    }

    /**
     * Resolve the best-matching override for {@code worldName} against the authored {@code entries}.
     * Returns {@code null} when {@code worldName} is null/blank, {@code entries} is empty, or nothing
     * matches (the caller then uses the GLOBAL settings).
     */
    @Nullable
    public static WorldOverride resolve(@Nonnull List<Entry> entries, @Nullable String worldName) {
        if (worldName == null || worldName.isEmpty() || entries.isEmpty()) {
            return null;
        }
        String worldLower = worldName.toLowerCase(Locale.ROOT);

        Entry exact = null;
        Entry bestPrefix = null;
        int bestPrefixLen = -1;
        Entry defaultRule = null;

        for (Entry e : entries) {
            if (e.matchesExact(worldLower)) {
                exact = e;
                break; // exact is the highest precedence; stop early
            }
            if (e.isDefaultRule()) {
                if (defaultRule == null) {
                    defaultRule = e;
                }
                continue;
            }
            int len = e.prefixMatchLength(worldLower);
            if (len > bestPrefixLen) {
                bestPrefixLen = len;
                bestPrefix = e;
            }
        }

        if (exact != null) {
            return exact.override;
        }
        if (bestPrefix != null) {
            return bestPrefix.override;
        }
        if (defaultRule != null) {
            return defaultRule.override;
        }
        return null;
    }
}
