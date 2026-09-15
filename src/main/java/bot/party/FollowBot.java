package bot.party;

import bot.Action;
import bot.ActionExecutor;
import bot.BotSession;
import bot.ChannelSession;
import bot.MapleConnection;
import bot.WorldState;

/**
 * Runs {@link FollowPlanner} as a standalone process, so following can be tested against the live
 * server without the in-server supervisor. The owner must invite this bot itself (e.g. with
 * {@link OwnerHarness}'s {@code invite:NAME} step); there is no catch-up warp standalone.
 *
 * <pre>java -cp ... bot.party.FollowBot maplestory 8484 acct pass ownerCharId ownerName slot budgetSeconds</pre>
 */
public class FollowBot {
    public static void main(String[] args) throws Exception {
        if (args.length < 8) {
            System.err.println("usage: FollowBot <host> <port> <user> <pass> <ownerCharId> <ownerName> <slot> <budgetSeconds>");
            System.exit(1);
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int ownerId = Integer.parseInt(args[4]);
        long budgetMs = Long.parseLong(args[7]) * 1000;

        ChannelSession session = BotSession.loginAndEnterChannel(host, port, args[2], args[3]);
        WorldState world = new WorldState(session.charId());
        try (MapleConnection conn = session.connection()) {
            FollowPlanner planner = new FollowPlanner(ownerId, args[5], Integer.parseInt(args[6]));
            BotSession.runGameLoop(conn, session.charId(), planner, budgetMs, () -> false, world);
            if (world.getPartyId() != -1) {
                new ActionExecutor(conn, world).execute(new Action.LeaveParty());
                Thread.sleep(300);
            }
        }
        System.out.println("[ok]   follow bot finished, last map " + world.getSelfMapId());
    }
}
