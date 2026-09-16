package bot.budget;

import client.Character;
import client.Client;
import net.server.Server;
import net.server.world.World;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Answers "is a human playing right now?" - the switch for scale-to-zero (residents don't wake and
 * no LLM calls are made while nobody is around to see them).
 *
 * <p>A character counts as a bot if an in-server bot registered its name here, or if its account is a
 * bot account ({@code Shu<ownerId>b<slot>}, see {@code BotAccounts#botAccountName}). Companions now
 * look like ordinary adventurers, so the account is what identifies them; the name check alone would
 * have counted a companion as a player and let {@link bot.ambient.AmbientCompanionDirector} send it a
 * companion of its own.
 */
public final class HumanPresence {
    /**
     * Bot <em>accounts</em> are still named for the machine ({@code Shu<ownerId>b<slot>}); the characters
     * standing in the world are not, so recognition cannot rely on a character name pattern any more.
     * Every bot registers the name it logs in with through {@link #registerBot}, and a character whose
     * account is one of ours counts as a bot whether or not it has registered yet.
     */
    private static final Pattern BOT_ACCOUNT_NAME = Pattern.compile("Shu\\d+b\\d");
    private static final Set<String> BOT_NAMES = ConcurrentHashMap.newKeySet();

    private HumanPresence() {
    }

    public static void registerBot(String name) {
        BOT_NAMES.add(name.toLowerCase());
    }

    public static boolean isBotName(String name) {
        return name != null && BOT_NAMES.contains(name.toLowerCase());
    }

    /**
     * Whether a character is one of ours, by either measure: its name was registered when it logged in,
     * or its account is a bot account. The second check closes the window between a companion logging in
     * and registering - without it, one bot could be mistaken for a human player and be sent a companion
     * of its own.
     */
    public static boolean isBot(Character chr) {
        if (chr == null) {
            return false;
        }
        if (isBotName(chr.getName())) {
            return true;
        }
        Client client = chr.getClient();
        String account = client == null ? null : client.getAccountName();
        return account != null && BOT_ACCOUNT_NAME.matcher(account).matches();
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
                if (chr.isLoggedinWorld() && !isBot(chr)) {
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
                Client client = chr.getClient();
                String account = client == null ? null : client.getAccountName();
                if (account != null && BOT_ACCOUNT_NAME.matcher(account).matches()) {
                    count++;
                }
            }
        }
        return count;
    }
}
