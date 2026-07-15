package com.ziggfreed.mmomobscaling.caster;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.CombatSupport;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;

/**
 * The engine-coupled half of a {@code NativeChain} caster entry (the pure half is
 * {@link CasterEntry}) - arms a mob's native attack sequence to a specific {@code RootInteraction}
 * chain id via {@link CombatSupport#addAttackOverride}. Mirrors {@code family/MobFamilyMatcher}'s
 * split (pure filter type + a same-package engine-reading evaluator).
 *
 * <p><b>The {@code Attack} tag caveat (recon-verified, {@code CombatSupport.java}/{@code
 * ActionAttack.java} in the shared source):</b> the native {@code ActionAttack} action only honours
 * an override whose {@code RootInteraction} asset carries the {@code Attack} tag
 * ({@code RootInteraction.getAssetMap().getKeysForTag(CombatSupport.ATTACK_TAG_INDEX)}); an untagged
 * override id makes every attack cycle stall (the NPC skips its attack that tick, logs its own
 * warning, and retries) instead of ever landing a hit. {@link #arm} checks membership FIRST and
 * refuses to arm an untagged id, warning ONCE per distinct chain id (a busy spawner with one
 * mis-tagged demo chain must not spam the log).
 *
 * <p><b>Arm-once, not a re-arm (cadence caveat):</b> {@code CombatSupport.addAttackOverride} APPENDS
 * to a round-robin list AND resets its cursor ({@code attackOverrideIndex}) to 0 on every single call -
 * {@code getNextAttackOverride} then cycles the list forever from wherever the cursor sits (see
 * {@code CombatSupport.java:102-119}). {@code MobScalingCasterArmSystem} therefore calls {@link #arm}
 * exactly ONCE, at spawn, for a {@code NATIVE_CHAIN} entry, and deliberately never again: because the
 * round-robin never expires an entry on its own, one arm suffices for the mob's lifetime, and a
 * cadence-driven re-arm would be actively harmful, not merely redundant - it would snap the cursor back
 * to index 0 (the first chain ever armed) on every tick, starving every OTHER {@code NATIVE_CHAIN}
 * entry in a multi-chain roster from ever being reached by {@code getNextAttackOverride}. A chunk
 * reload / respawn discards the list entirely (per-entity {@code CombatSupport} state is not
 * persisted), which is exactly when {@code MobScalingCasterArmSystem} re-fires and re-arms once more.
 */
public final class NativeChainAttacker {

    /** Warn-once-per-distinct-chain-id set (case-insensitive), so a mis-tagged demo chain logs once. */
    private static final Set<String> WARNED_UNTAGGED = ConcurrentHashMap.newKeySet();

    private NativeChainAttacker() {
    }

    /**
     * Arm {@code role}'s next native attack to (also) draw from {@code chainId}. Returns {@code false}
     * (and warns once) when {@code chainId}'s {@code RootInteraction} asset is not {@code Attack}-tagged
     * - {@code ActionAttack} would reject it every cycle, so arming it would only stall the mob's attacks.
     */
    public static boolean arm(@Nonnull Role role, @Nonnull String chainId) {
        if (!isAttackTagged(chainId)) {
            if (WARNED_UNTAGGED.add(chainId.toLowerCase(Locale.ROOT))) {
                safeWarn("NativeChain '" + chainId + "' is not tagged 'Attack' on its RootInteraction asset "
                        + "(NPCs cannot use it as an attack override) - skipping; tag the asset or fix the id.");
            }
            return false;
        }
        role.getCombatSupport().addAttackOverride(chainId);
        return true;
    }

    /** True when {@code chainId} names a {@code RootInteraction} asset carrying the native {@code Attack} tag. */
    private static boolean isAttackTagged(@Nonnull String chainId) {
        try {
            return RootInteraction.getAssetMap().getKeysForTag(CombatSupport.ATTACK_TAG_INDEX).contains(chainId);
        } catch (Throwable t) {
            return false; // asset store unavailable (unit JVM / not yet loaded): treat as not tagged, never crash
        }
    }

    private static void safeWarn(@Nonnull String message) {
        try {
            MobScalingPlugin.LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // log-manager-less JVMs
        }
    }
}
