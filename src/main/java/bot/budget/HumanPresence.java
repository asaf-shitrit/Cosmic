package bot.budget;

import client.Character;
import net.server.Server;
import net.server.world.World;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Answers "is a human playing right now?" - the switch for scale-to-zero (residents don't wake and
 * no LLM calls are made while nobody is around to see them).
 *
 * <p>A character counts as a bot if an in-server bot registered its name here, or if it has a
 * summoned companion's name ({@code Shu<ownerId>b<slot>}, see {@code BotAccounts#botName}). The name
 * pattern bridges the companion code until it registers its bots itself.
 */
public final class HumanPresence {
    private static final Pattern SUMMONED_BOT_NAME = Pattern.compile("Shu\\d+b\\d");
    private static final Set<String> BOT_NAMES = ConcurrentHashMap.newKeySet();

    private HumanPresence() {
    }

    public static void registerBot(String name) {
        BOT_NAMES.add(name.toLowerCase());
    }

    public static boolean isBotName(String name) {
        return BOT_NAMES.contains(name.toLowerCase()) || SUMMONED_BOT_NAME.matcher(name).matches();
    }

    /** Humans logged into any world, excluding every known bot. */
    public static int humansOnline() {
        Server server = Server.getInstance();
        if (server.getWorlds() == null) {
            return 0;
        }
        int humans = 0;
        for (World world : server.getWorlds()) {
            for (Character chr : world.getPlayerStorage().getAllCharacters()) {
                if (chr.isLoggedinWorld() && !isBotName(chr.getName())) {
                    humans++;
                }
            }
        }
        return humans;
    }

    /** Summoned companions currently connected, for {@link BotBudget#setExternalUsage}. */
    public static int summonedCompanionsOnline() {
        Server server = Server.getInstance();
        if (server.getWorlds() == null) {
            return 0;
        }
        int count = 0;
        for (World world : server.getWorlds()) {
            for (Character chr : world.getPlayerStorage().getAllCharacters()) {
                if (SUMMONED_BOT_NAME.matcher(chr.getName()).matches()) {
                    count++;
                }
            }
        }
        return count;
    }
}
