package bot.kpq;

import bot.BotSession;
import bot.ChannelSession;
import bot.MapleConnection;
import bot.WorldState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Entry point for one Kerning Party Quest bot. Every bot in a run is a separate process/character
 * (same pattern as {@code BotSession}/{@code Spectator}) - the leader creates the party and invites
 * the others by name, then everyone drives their own {@link KpqPlanner} independently against the
 * same live server.
 *
 * <p>Levelling is out of scope here - see the task notes: a character must already be level 21-30
 * (and, for this bot, already sitting in the recruit map {@code 103000000}) before this is run,
 * via a direct {@code characters} table update after the account/character is first created (run
 * {@code BotSession} once to create it, then patch the DB, then run this).
 *
 * <p>Run one leader and up to 3 members, e.g. for a 4-bot party:
 * <pre>
 * java -cp ... bot.kpq.KpqBot maplestory 8484 kpqlead pass leader 0 kpqmem1,kpqmem2,kpqmem3
 * java -cp ... bot.kpq.KpqBot maplestory 8484 kpqmem1 pass member 1
 * java -cp ... bot.kpq.KpqBot maplestory 8484 kpqmem2 pass member 2
 * java -cp ... bot.kpq.KpqBot maplestory 8484 kpqmem3 pass member 3
 * </pre>
 */
public class KpqBot {
    private static final long RUN_BUDGET_MS = 15 * 60 * 1000L;   // comfortably inside the 30-min PQ timer

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println("usage: KpqBot <host> <port> <user> <pass> <leader|member> <ordinal> [inviteName,...]");
            System.exit(1);
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String user = args[2];
        String pass = args[3];
        KpqPlanner.Role role = "leader".equalsIgnoreCase(args[4]) ? KpqPlanner.Role.LEADER : KpqPlanner.Role.MEMBER;
        int ordinal = Integer.parseInt(args[5]);
        List<String> inviteNames = new ArrayList<>();
        if (args.length > 6 && !args[6].isBlank()) {
            inviteNames.addAll(Arrays.asList(args[6].split(",")));
        }
        int passesNeeded = inviteNames.size();   // party size minus the leader

        System.out.println("=== KpqBot (" + role + ", ordinal=" + ordinal + ") ===");
        System.out.printf("connecting to %s:%d as '%s'%n", host, port, user);
        if (role == KpqPlanner.Role.LEADER) {
            System.out.println("will invite: " + inviteNames + ", needs " + passesNeeded + " pass(es) at stage 1");
        }

        ChannelSession session = BotSession.loginAndEnterChannel(host, port, user, pass);
        KpqPlanner planner = new KpqPlanner(role, ordinal, inviteNames, passesNeeded);
        try (MapleConnection channel = session.connection()) {
            BotSession.GameLoopResult result = BotSession.runGameLoop(channel, session.charId(), planner, RUN_BUDGET_MS);
            report(role, result.world());
        }
    }

    private static void report(KpqPlanner.Role role, WorldState world) {
        System.out.println();
        System.out.println("[ok]   run finished (budget exhausted or process interrupted)");
        System.out.println("[ok]   party id: " + world.getPartyId() + ", is leader: " + world.isPartyLeader()
                + ", members seen: " + world.getPartyMemberIds());
        System.out.println("[ok]   map changes observed: " + world.getMapChangeCount());
        System.out.println("[ok]   coupons held: " + world.getEtcQuantity(KpqConstants.ITEM_COUPON)
                + ", passes held: " + world.getEtcQuantity(KpqConstants.ITEM_PASS));
        System.out.println("[ok]   final position: " + world.getSelfPosition());
    }
}
