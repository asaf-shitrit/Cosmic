package bot.pq;

import bot.Action;
import bot.WorldState;

import java.awt.Point;

/** The combat adapter available to a party-quest session. */
@FunctionalInterface
public interface PartyQuestCombat {
    Action attack(WorldState world, Point selfPosition, int monsterObjectId);
}
