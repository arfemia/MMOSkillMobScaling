package com.ziggfreed.mmomobscaling.reward;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.instance.reward.InstanceRewardGranter;
import com.ziggfreed.common.util.CommandExecutor;

/**
 * mob-scaling's executor for the non-item {@link InstanceRewardGranter} reward kinds, used by the P4
 * additive {@code BonusRewards} layer a {@code Rarity}/{@code Variant} may author (see
 * {@code event.MobScalingLootDropSystem}): granted to the KILLER alongside the tier's native
 * {@code BonusDropList} item loot on a scaled-mob death.
 *
 * <p>Mirrors {@code KweebecRewardSink} (the same soft-integration seam): standalone mob-scaling has no
 * currency system of its own, and the frozen {@code MMOSkillTreeAPI} surface it reads (power level,
 * combat level, {@code registerMobKillXpMultiplier}, {@code castNpcAbility}) exposes no currency-grant
 * method either, so a plain {@code currency <id> <amt>} spec no-ops here. A {@code COMMAND} reward DOES
 * fire - as CONSOLE via the common {@link CommandExecutor} - which is how the practical case works: the
 * MMO jar registers an {@code xp} token with {@code RewardSpecRegistry} at its own {@code setup()} (an
 * {@code xp <SKILL> <amt>} spec rewrites to a {@code /mmoawardxp} command template), so authoring
 * {@code "xp MINING 500"} in a {@code BonusRewards} list runs that command here with no mob-scaling ->
 * MMO compile dependency. The granter has already substituted {@code {amount}}; this substitutes
 * {@code {player}} from the killer's username.
 */
public final class MobScalingRewardSink implements InstanceRewardGranter.Sink {

    public static final MobScalingRewardSink INSTANCE = new MobScalingRewardSink();

    private MobScalingRewardSink() {
    }

    @Override
    public boolean grantCurrency(@Nonnull String currencyId, int amount, @Nonnull PlayerRef player,
                                 @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        return false; // no standalone currency system, and MMOSkillTreeAPI exposes no currency-grant method
    }

    @Override
    public boolean runCommand(@Nonnull String command, @Nonnull PlayerRef player,
                              @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        String resolved = command.replace("{player}", player.getUsername());
        return CommandExecutor.executeAsConsole(resolved, player.getUsername());
    }
}
