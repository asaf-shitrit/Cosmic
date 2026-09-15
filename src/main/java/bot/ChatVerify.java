package bot;

import net.opcodes.RecvOpcode;
import net.opcodes.SendOpcode;
import net.packet.InPacket;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Verifies that a bot can actually hear the room against the live server: it logs in, stands still, and
 * prints every {@code CHATTEXT} it receives along with the speaker's name.
 *
 * <p><b>Why this exists.</b> {@link WorldState}'s chat perception is easy to get subtly wrong and
 * impossible to prove from the database or from a bot's own log - movement and chat both have no
 * acknowledgement, so a bot saying "I heard you" proves nothing. This harness is the independent
 * witness: a human types in the client, and this prints what arrived on the wire, with the name the
 * server's own {@code SPAWN_PLAYER} gave for that character id. Reading a name here proves the spawn
 * decode and the chat decode both landed, which are exactly the two things tests can only imitate.
 *
 * <p>It never replies, never moves and never picks a planner: it is an observer, so nothing it reports
 * can be confused with its own action.
 *
 * <p><b>Setup.</b> The bot must be standing on the map the human is standing on - chat is broadcast to
 * one map only. Characters enter the world at the map saved in {@code characters.map}, so either put
 * the character there first, or ask the human to walk to it:
 * <pre>
 * docker compose exec -T db mysql -uroot cosmic -e "UPDATE characters SET map=103000000 WHERE name='&lt;char&gt;';"
 * </pre>
 *
 * <p>Run it from the repo (the server is host {@code maplestory} inside the compose network), then type
 * in the client:
 * <pre>
 * java -cp "target/classes:$(cat /tmp/cp.txt)" bot.ChatVerify maplestory 8484 &lt;acct&gt; &lt;pass&gt; [seconds]
 * </pre>
 *
 * <p>Exit code is 0 when at least one line was heard, 2 when nothing arrived inside the budget - so a
 * script can tell "the human didn't type" from "the decode is broken", which look identical in a log.
 */
public final class ChatVerify {
    private static final long DEFAULT_BUDGET_SECONDS = 120;
    /** Matches the other verification harnesses: short reads keep the loop responsive to Ctrl-C. */
    private static final int READ_TIMEOUT_MS = 250;

    private ChatVerify() {
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8484;
        String user = args.length > 2 ? args[2] : "bot";
        String pass = args.length > 3 ? args[3] : "bot";
        long budgetSeconds = args.length > 4 ? Long.parseLong(args[4]) : DEFAULT_BUDGET_SECONDS;

        System.out.printf("=== ChatVerify: %s:%d as '%s', watching for %ds ===%n", host, port, user, budgetSeconds);
        System.out.println("Type in the client while this runs. Nothing here moves or replies.");

        ChannelSession session = BotSession.loginAndEnterChannel(host, port, user, pass);
        try (MapleConnection conn = session.connection()) {
            conn.setReadTimeoutMs(READ_TIMEOUT_MS);
            WorldState world = new WorldState(session.charId());
            watch(conn, world, session, budgetSeconds * 1000);
        }
    }

    private static void watch(MapleConnection conn, WorldState world, ChannelSession session, long budgetMs)
            throws IOException {
        long deadline = System.currentTimeMillis() + budgetMs;
        int consumedSeq = 0;
        int heard = 0;
        int addressedToUs = 0;
        Set<String> speakers = new LinkedHashSet<>();
        boolean enteredWorld = false;
        String myName = session.charName();

        while (System.currentTimeMillis() < deadline) {
            InPacket p;
            try {
                p = conn.receive();
            } catch (SocketTimeoutException e) {
                continue;                       // quiet map; check the clock and read again
            }
            int opcode = p.readShort() & 0xFFFF;
            if (opcode == SendOpcode.SET_FIELD.getValue()) {
                // Every SET_FIELD needs PLAYER_MAP_TRANSFER or the server latches the bot as still
                // changing maps and silently drops everything it does afterwards (README §5).
                world.onMapChanged();
                conn.send(MapleConnection.packet(RecvOpcode.PLAYER_MAP_TRANSFER.getValue()));
                if (!enteredWorld) {
                    enteredWorld = true;
                    System.out.printf("[ok]    entered the world as '%s' on map %d%n", myName, world.getSelfMapId());
                    System.out.printf("        type its name in chat to test addressing, e.g. \"%s, come here\"%n", myName);
                }
                continue;
            }

            String note = world.accept(opcode, p);
            if (note != null && note.contains("entered the map")) {
                System.out.println("[world] " + note);
            }

            WorldState.ChatSince since = world.chatSince(consumedSeq);
            consumedSeq = since.latestSeq();
            for (WorldState.ChatLine line : since.lines()) {
                heard++;
                speakers.add(line.speaker());
                boolean addressed = mentions(line.text(), myName);
                if (addressed) {
                    addressedToUs++;
                }
                System.out.printf("[heard] %s (id=%d%s): %s%n",
                        line.speaker(), line.speakerId(),
                        addressed ? ", names this bot" : "", line.text());
            }
        }

        System.out.println();
        System.out.println("=== evidence summary ===");
        System.out.printf("bot name: %s (charId %d, map %d)%n", myName, session.charId(), world.getSelfMapId());
        System.out.printf("chat lines heard: %d from %d speaker(s) %s%n", heard, speakers.size(), speakers);
        System.out.printf("lines naming this bot: %d%n", addressedToUs);
        System.out.println(heard > 0
                ? "CHAT VERIFY PASSED: a line typed in the client arrived here with its speaker resolved."
                : "CHAT VERIFY INCONCLUSIVE: nothing was typed on this map inside the budget.");
        if (heard == 0) {
            System.exit(2);
        }
    }

    /** The same word-boundary rule the residents use, so "look at Nella" doesn't match a bot named Nell. */
    private static boolean mentions(String message, String name) {
        return name != null && !name.isEmpty()
                && message.toLowerCase().matches(".*\\b" + java.util.regex.Pattern.quote(name.toLowerCase()) + "\\b.*");
    }
}
