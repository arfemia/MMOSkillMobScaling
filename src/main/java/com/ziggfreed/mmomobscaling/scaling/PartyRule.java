package com.ziggfreed.mmomobscaling.scaling;

import java.util.Collection;
import java.util.List;

import javax.annotation.Nonnull;

import org.joml.Vector3d;

/**
 * How a mob grows with the party in front of it, read the way Update 7's goblin dungeon reads it
 * ({@code Component_Instruction_Dungeon_Goblin_Scale_Health}): the players standing within {@link #radius} blocks of
 * the mob are counted once, and a party of exactly 2, exactly 3, or 4 and more multiplies the mob's health by its
 * rung ({@link #twoPlayers}, {@link #threePlayers}, {@link #fourPlayers}, the game's 1.55, 2.1 and 2.65). A boss or an
 * elite is additive per player instead, as the game's Gobliterator is ({@code Gobliterator_Scale_Health_P2} to
 * {@code _P4}: the same flat amount for each player past the first): each counted player past the first adds
 * {@link #bossPerPlayer} of its health, counting no more than {@link #bossMostPlayers} players. Nothing about a player
 * is read but where he stands.
 *
 * <p>Pure and immutable: the settings fold builds one ({@code MobScalingConfig}, a world file's overlay per leaf), and
 * the engagement system asks it ({@code event/MobScalingEngageSystem}). {@link #NONE} is the switched-off rule.
 *
 * @param enabled         whether the party is counted at all in this world
 * @param radius          how far from the mob, in blocks, a player counts (a player exactly this far counts)
 * @param twoPlayers      the health multiplier for a party of exactly two
 * @param threePlayers    the health multiplier for a party of exactly three
 * @param fourPlayers     the health multiplier for a party of four or more
 * @param bossPerPlayer   for a boss or an elite: the share of its health each counted player past the first adds
 * @param bossMostPlayers for a boss or an elite: the most players it counts
 * @param skipGroups      native NPC group ids whose members already grow with the party by themselves (the game's
 *                        dungeon goblins), left as they are
 */
public record PartyRule(boolean enabled, double radius, double twoPlayers, double threePlayers, double fourPlayers,
        double bossPerPlayer, int bossMostPlayers, @Nonnull List<String> skipGroups) {

    /** The rule that never counts anyone: every multiplier 1.0. */
    public static final PartyRule NONE = new PartyRule(false, 0.0, 1.0, 1.0, 1.0, 0.0, 1, List.of());

    /** The sanity every layer applies: no negative reach, no rung below 1.0, a boss counts at least one player. */
    public PartyRule {
        radius = Double.isFinite(radius) ? Math.max(0.0, radius) : 0.0;
        twoPlayers = atLeastOne(twoPlayers);
        threePlayers = atLeastOne(threePlayers);
        fourPlayers = atLeastOne(fourPlayers);
        bossPerPlayer = Double.isFinite(bossPerPlayer) ? Math.max(0.0, bossPerPlayer) : 0.0;
        bossMostPlayers = Math.max(1, bossMostPlayers);
        skipGroups = skipGroups == null ? List.of() : List.copyOf(skipGroups.stream()
                .filter(g -> g != null && !g.isBlank()).map(String::trim).toList());
    }

    /**
     * The health multiplier a mob takes for {@code players} counted around it: 1.0 when the rule is off or one player
     * (or none) stands there; a boss or an elite {@code 1 + bossPerPlayer x (min(players, bossMostPlayers) - 1)};
     * anything else its party's rung, the fourth rung for four or more.
     */
    public double healthMultiplier(int players, boolean boss) {
        if (!enabled || players < 2) {
            return 1.0;
        }
        if (boss) {
            return 1.0 + bossPerPlayer * (Math.min(players, bossMostPlayers) - 1);
        }
        return switch (players) {
            case 2 -> twoPlayers;
            case 3 -> threePlayers;
            default -> fourPlayers;
        };
    }

    /** Is a player {@code distanceSquared} (squared blocks) from the mob inside the party's reach, its edge included? */
    public boolean within(double distanceSquared) {
        return distanceSquared >= 0.0 && distanceSquared <= radius * radius;
    }

    /** How many of {@code players} (their positions; a null one counts nothing) stand within reach of {@code mob}. */
    public int partySize(@Nonnull Vector3d mob, @Nonnull Collection<Vector3d> players) {
        int count = 0;
        for (Vector3d at : players) {
            if (at != null && within(at.distanceSquared(mob))) {
                count++;
            }
        }
        return count;
    }

    private static double atLeastOne(double v) {
        return Double.isFinite(v) ? Math.max(1.0, v) : 1.0;
    }
}
