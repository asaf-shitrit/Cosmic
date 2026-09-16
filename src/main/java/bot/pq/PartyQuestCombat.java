package bot.pq;

import bot.Action;
import bot.WorldState;
import bot.combat.AttackReach;

import java.awt.Point;

/** The combat adapter available to a party-quest session. */
@FunctionalInterface
public interface PartyQuestCombat {
    Action attack(WorldState world, Point selfPosition, int monsterObjectId);

    /**
     * How close this participant's job attacks from, so a session steps to the same reach the
     * attack will use: a warrior walks onto the monster, a magician or bowman stands off it.
     */
    default int attackReach() {
        return AttackReach.MELEE;
    }
}
