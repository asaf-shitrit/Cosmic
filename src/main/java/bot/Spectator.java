package bot;

import net.opcodes.SendOpcode;
import net.packet.InPacket;

import java.awt.Point;
import java.io.IOException;
import java.net.SocketTimeoutException;

/**
 * A second bot whose only job is to sit in the same map and log what it sees another character do -
 * used to verify {@link BotSession}'s movement actually lands server-side, from an angle
 * {@link BotSession} itself can't observe (a client never gets its own MOVE_PLAYER echoed back to
 * it: {@code MovePlayerHandler} broadcasts to the map with the mover excluded).
 *
 * <p>Not part of the bot/perception/action deliverable - this is a test harness. Two fresh
 * Adventurer characters both spawn on the same starter map, so running this alongside
 * {@link BotSession} against a second fresh account is enough to have it witness the first bot's
 * {@code SPAWN_PLAYER} entrance and every subsequent {@code MOVE_PLAYER} it broadcasts.
 *
 * <p>Run the same way as {@link BotSession}, with a different fresh account:
 * <pre>java -cp Server.jar bot.Spectator maplestory 8484 watcher watcherpass</pre>
 */
public class Spectator {
    private static final int TIMEOUT_MS = 10_000;
    private static final int READ_TICK_MS = 1_000;
    private static final long RUN_BUDGET_MS = 45_000;

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int loginPort = args.length > 1 ? Integer.parseInt(args[1]) : 8484;
        String user = args.length > 2 ? args[2] : "spectator";
        String pass = args.length > 3 ? args[3] : "spectator";

        System.out.println("=== Spectator ===");
        System.out.printf("connecting to %s:%d as '%s'%n", host, loginPort, user);

        ChannelSession session = BotSession.loginAndEnterChannel(host, loginPort, user, pass);
        try (MapleConnection conn = session.connection()) {
            conn.setReadTimeoutMs(READ_TICK_MS);
            System.out.println("[..]   watching for other players entering/moving in this map");

            long deadline = System.currentTimeMillis() + RUN_BUDGET_MS;
            while (System.currentTimeMillis() < deadline) {
                try {
                    InPacket p = conn.receive();
                    int opcode = p.readShort() & 0xFFFF;

                    // No PING branch needed - MapleConnection#receive() answers it internally now and
                    // never surfaces it here; see that method's javadoc.
                    if (opcode == SendOpcode.SPAWN_PLAYER.getValue()) {
                        int charId = p.readInt();   // leading field of spawnPlayerMapObject; rest unparsed
                        if (charId != session.charId()) {
                            System.out.println("[see]   player " + charId + " is in this map");
                        }
                    } else if (opcode == SendOpcode.MOVE_PLAYER.getValue()) {
                        logMove(p, session.charId());
                    }
                } catch (SocketTimeoutException e) {
                    // just a quiet tick, keep waiting
                }
            }
            System.out.println("[ok]   spectator run finished");
        }
    }

    /**
     * {@code PacketCreator.movePlayer(chrId, movementPacket, movementDataLength)} writes the mover's
     * charId, a spare int, then copies the exact fragment bytes {@code MovePlayerHandler} parsed out
     * of the original {@code MOVE_PLAYER} request - i.e. the same "absolute move" fragment format
     * {@link ActionExecutor#execute} sends. Reusing that layout here is what lets a spectator recover
     * the exact coordinates another bot commanded, not just the fact that it moved.
     */
    private static void logMove(InPacket p, int selfCharId) {
        int chrId = p.readInt();
        p.readInt();               // spare int, always 0
        int numCommands = p.readByte();
        if (numCommands < 1) {
            return;
        }
        int command = p.readByte();
        if (command != 0) {
            // Only the absolute-move fragment (command 0) is one ActionExecutor ever sends; other
            // commands (jump, knockback, ...) would need their own field layouts to decode.
            System.out.println("[see]   player " + chrId + " sent a movement fragment (command " + command
                    + ") - not decoded, only command 0 (absolute move) is");
            return;
        }
        Point pos = p.readPos();
        String who = chrId == selfCharId ? "self (unexpected - moves aren't echoed to the mover)" : String.valueOf(chrId);
        System.out.println("[see]   player " + who + " moved to " + pos.x + "," + pos.y);
    }
}
