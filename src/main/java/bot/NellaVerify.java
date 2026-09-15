package bot;

import net.opcodes.RecvOpcode;
import net.opcodes.SendOpcode;
import net.packet.InPacket;

import java.io.IOException;
import java.net.SocketTimeoutException;

/**
 * Verifies NPC Nella (1052103, Kerning City / map 103000000) against the live server: opens her
 * menu, drives "add N" and "remove all" via raw {@code NPC_TALK_MORE} packets (not through a
 * generic {@link Planner} loop - the sequence here is scripted step by step so each expectation is
 * explicit), and counts the {@code SPAWN_PLAYER}/{@code REMOVE_PLAYER_FROM_MAP} broadcasts her
 * {@code FakePlayerService} calls produce independently of what her own NPC text claims.
 *
 * <p>Requires the account's character to already be sitting on map 103000000. {@code NPCTalkHandler}
 * has no proximity check (see its javadoc / README §5), so once there this never needs to move.
 * First run {@code BotSession} once to create the account/character, then patch
 * {@code characters.map} directly (see CLAUDE.md's database command) before running this.
 */
public class NellaVerify {
    private static final int NELLA_NPC_ID = 1052103;
    private static final long STEP_TIMEOUT_MS = 15_000;
    private static final long OVERALL_BUDGET_MS = 90_000;
    /** Matches BLOCK_NPC_RACE_CONDT (config.yaml, 500ms) with headroom before reopening a conversation. */
    private static final long BETWEEN_CONVERSATIONS_MS = 800;

    /** Running tallies of SPAWN_PLAYER / REMOVE_PLAYER_FROM_MAP broadcasts observed on the wire. */
    private static final class Counter {
        int entered;
        int left;
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8484;
        String user = args.length > 2 ? args[2] : "bot";
        String pass = args.length > 3 ? args[3] : "bot";

        System.out.println("=== NellaVerify ===");
        System.out.printf("connecting to %s:%d as '%s'%n", host, port, user);

        ChannelSession session = BotSession.loginAndEnterChannel(host, port, user, pass);
        try (MapleConnection conn = session.connection()) {
            conn.setReadTimeoutMs(250);
            WorldState world = new WorldState(session.charId());
            ActionExecutor exec = new ActionExecutor(conn, world);
            Counter counter = new Counter();

            long deadline = System.currentTimeMillis() + OVERALL_BUDGET_MS;

            waitForSetField(conn, world, counter, deadline);
            System.out.println("[ok]   entered the world/map");

            int nellaOid = waitForNpc(conn, world, counter, NELLA_NPC_ID, deadline);
            System.out.println("[ok]   Nella (npc " + NELLA_NPC_ID + ") spotted, oid=" + nellaOid);

            // --- Conversation 1: open menu, read counts, add 5 ---
            exec.execute(new Action.TalkToNpc(nellaOid));
            WorldState.NpcTalk menu = waitForNpcTalk(conn, world, counter, null, 4, deadline);
            System.out.println("[wire] menu (type " + menu.msgType() + "): " + oneLine(menu.text()));

            exec.execute(new Action.RespondToNpc(4, true, 0)); // #L0# = Add
            WorldState.NpcTalk numPrompt = waitForNpcTalk(conn, world, counter, menu, 3, deadline);
            System.out.println("[wire] number prompt (type " + numPrompt.msgType() + "): " + oneLine(numPrompt.text()));

            int enteredBefore = counter.entered;
            exec.execute(new Action.RespondToNpc(3, true, 5)); // add 5
            WorldState.NpcTalk addResult = waitForNpcTalk(conn, world, counter, numPrompt, 0, deadline);
            int enteredAfter = counter.entered;
            System.out.println("[wire] add result (type " + addResult.msgType() + "): " + oneLine(addResult.text()));
            System.out.println("[independent] SPAWN_PLAYER broadcasts observed during this add: "
                    + (enteredAfter - enteredBefore) + " (requested 5)");

            Thread.sleep(BETWEEN_CONVERSATIONS_MS);

            // --- Conversation 2: request far above any cap, to observe the cap message ---
            exec.execute(new Action.TalkToNpc(nellaOid));
            WorldState.NpcTalk menu2 = waitForNpcTalk(conn, world, counter, addResult, 4, deadline);
            System.out.println("[wire] menu (type " + menu2.msgType() + "): " + oneLine(menu2.text()));

            exec.execute(new Action.RespondToNpc(4, true, 0));
            WorldState.NpcTalk numPrompt2 = waitForNpcTalk(conn, world, counter, menu2, 3, deadline);
            System.out.println("[wire] number prompt (type " + numPrompt2.msgType() + "): " + oneLine(numPrompt2.text()));

            int enteredBefore2 = counter.entered;
            exec.execute(new Action.RespondToNpc(3, true, 999)); // deliberately over every cap
            WorldState.NpcTalk capResult = waitForNpcTalk(conn, world, counter, numPrompt2, 0, deadline);
            int enteredAfter2 = counter.entered;
            System.out.println("[wire] capped-add result (type " + capResult.msgType() + "): " + oneLine(capResult.text()));
            System.out.println("[independent] SPAWN_PLAYER broadcasts observed during this add: "
                    + (enteredAfter2 - enteredBefore2) + " (requested 999)");

            Thread.sleep(BETWEEN_CONVERSATIONS_MS);

            // --- Conversation 3: remove all ---
            exec.execute(new Action.TalkToNpc(nellaOid));
            WorldState.NpcTalk menu3 = waitForNpcTalk(conn, world, counter, capResult, 4, deadline);
            System.out.println("[wire] menu (type " + menu3.msgType() + "): " + oneLine(menu3.text()));

            exec.execute(new Action.RespondToNpc(4, true, 1)); // #L1# = Remove all
            WorldState.NpcTalk confirm = waitForNpcTalk(conn, world, counter, menu3, 1, deadline);
            System.out.println("[wire] confirm (type " + confirm.msgType() + "): " + oneLine(confirm.text()));

            int leftBefore = counter.left;
            exec.execute(new Action.RespondToNpc(1, true, null)); // Yes
            WorldState.NpcTalk removeResult = waitForNpcTalk(conn, world, counter, confirm, 0, deadline);
            int leftAfter = counter.left;
            System.out.println("[wire] remove result (type " + removeResult.msgType() + "): " + oneLine(removeResult.text()));
            System.out.println("[independent] REMOVE_PLAYER_FROM_MAP broadcasts observed: " + (leftAfter - leftBefore));

            Thread.sleep(BETWEEN_CONVERSATIONS_MS);

            // --- Conversation 4: courtesy restock back to roughly the startup baseline (12) ---
            // "Remove all" above emptied the map entirely, including the startup crowd - restore it
            // so Kerning City isn't left bare for other live testing on this map.
            exec.execute(new Action.TalkToNpc(nellaOid));
            WorldState.NpcTalk menu4 = waitForNpcTalk(conn, world, counter, removeResult, 4, deadline);
            System.out.println("[wire] menu (type " + menu4.msgType() + "): " + oneLine(menu4.text()));

            exec.execute(new Action.RespondToNpc(4, true, 0));
            WorldState.NpcTalk numPrompt4 = waitForNpcTalk(conn, world, counter, menu4, 3, deadline);
            System.out.println("[wire] number prompt (type " + numPrompt4.msgType() + "): " + oneLine(numPrompt4.text()));

            exec.execute(new Action.RespondToNpc(3, true, 12));
            WorldState.NpcTalk restockResult = waitForNpcTalk(conn, world, counter, numPrompt4, 0, deadline);
            System.out.println("[wire] restock result (type " + restockResult.msgType() + "): " + oneLine(restockResult.text()));

            System.out.println();
            System.out.println("NELLA VERIFY COMPLETE.");
        }
    }

    /** Waits for the very first SET_FIELD (world/map entry) after PLAYER_LOGGEDIN. */
    private static void waitForSetField(MapleConnection conn, WorldState world, Counter counter, long deadline)
            throws IOException {
        while (System.currentTimeMillis() < deadline) {
            InPacket p;
            try {
                p = conn.receive();
            } catch (SocketTimeoutException e) {
                continue;
            }
            int opcode = p.readShort() & 0xFFFF;
            if (opcode == SendOpcode.SET_FIELD.getValue()) {
                world.onMapChanged();
                conn.send(MapleConnection.packet(RecvOpcode.PLAYER_MAP_TRANSFER.getValue()));
                return;
            }
            observe(world, counter, opcode, p);
        }
        throw new IOException("timed out waiting for SET_FIELD (world entry)");
    }

    /** Waits until {@code npcId} shows up in {@link WorldState#getNpcs()}, returning its map object id. */
    private static int waitForNpc(MapleConnection conn, WorldState world, Counter counter, int npcId, long deadline)
            throws IOException {
        long stepDeadline = Math.min(deadline, System.currentTimeMillis() + STEP_TIMEOUT_MS);
        while (System.currentTimeMillis() < stepDeadline) {
            drainOne(conn, world, counter);
            for (WorldState.NpcSighting sighting : world.getNpcs()) {
                if (sighting.npcId() == npcId) {
                    return sighting.objectId();
                }
            }
        }
        throw new IOException("timed out waiting for NPC " + npcId + " to appear on the map - seen instead: "
                + world.getNpcs());
    }

    /**
     * Waits for a new {@link WorldState.NpcTalk} (by reference, distinct from {@code previous} - the
     * one already consumed by the caller) whose {@code msgType} is {@code expectedMsgType}. Fails
     * loudly (rather than looping forever) if a talk of a different type shows up instead, since that
     * means the script's flow diverged from what this driver expected.
     */
    private static WorldState.NpcTalk waitForNpcTalk(MapleConnection conn, WorldState world, Counter counter,
                                                       WorldState.NpcTalk previous, int expectedMsgType,
                                                       long deadline) throws IOException {
        long stepDeadline = Math.min(deadline, System.currentTimeMillis() + STEP_TIMEOUT_MS);
        while (System.currentTimeMillis() < stepDeadline) {
            drainOne(conn, world, counter);
            WorldState.NpcTalk latest = world.getLastNpcTalk();
            if (latest != null && latest != previous) {
                if (latest.msgType() != expectedMsgType) {
                    throw new IOException("expected NPC_TALK type " + expectedMsgType + " but got type "
                            + latest.msgType() + ": " + latest.text());
                }
                return latest;
            }
        }
        throw new IOException("timed out waiting for NPC_TALK type " + expectedMsgType);
    }

    /** Reads one packet (or times out quietly) and feeds it to {@code world}, updating {@code counter}. */
    private static void drainOne(MapleConnection conn, WorldState world, Counter counter) throws IOException {
        InPacket p;
        try {
            p = conn.receive();
        } catch (SocketTimeoutException e) {
            return;
        }
        int opcode = p.readShort() & 0xFFFF;
        if (opcode == SendOpcode.SET_FIELD.getValue()) {
            // Not expected mid-run (Nella never changes our map), but handle defensively rather than
            // silently mis-decoding the rest of the stream as some other opcode.
            world.onMapChanged();
            conn.send(MapleConnection.packet(RecvOpcode.PLAYER_MAP_TRANSFER.getValue()));
            return;
        }
        observe(world, counter, opcode, p);
    }

    private static void observe(WorldState world, Counter counter, int opcode, InPacket p) {
        String note = world.accept(opcode, p);
        if (note == null) {
            System.out.println("[raw]   unhandled opcode 0x" + Integer.toHexString(opcode));
            return;
        }
        System.out.println("[world] " + note);
        if (note.endsWith("entered the map")) {
            counter.entered++;
        } else if (note.endsWith("left the map")) {
            counter.left++;
        }
    }

    private static String oneLine(String text) {
        return text.replace("\r\n", " | ");
    }
}
