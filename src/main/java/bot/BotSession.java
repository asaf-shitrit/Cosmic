package bot;

import net.opcodes.RecvOpcode;
import net.opcodes.SendOpcode;
import net.packet.InPacket;
import net.packet.OutPacket;

import java.awt.Point;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Drives a hand-rolled v83 client all the way from an unauthenticated TCP connection to standing
 * in the game world as a real player, then hands control to a {@link Planner} that decides what the
 * character actually does there. Login, character selection, the login-to-channel handoff and
 * {@code PLAYER_LOGGEDIN} are the same flow proven in the earlier login/world-entry work (see
 * {@link #loginAndEnterChannel}); what's new here is the in-game loop: {@link WorldState} perceives
 * the map from the packets the channel volunteers, a {@link Planner} decides on an {@link Action},
 * and {@link ActionExecutor} carries it out - all while still answering the server's keepalive
 * {@code PING}.
 *
 * <p>Today's {@link Planner} is {@link ScriptedPlanner}, a fixed "walk to the nearest NPC, talk to
 * it, say something" sequence. It exists to prove the perception/action wiring against the live
 * server before a real decision-maker (an LLM, via OpenRouter) is dropped in behind the same
 * {@link Planner} interface.
 *
 * <p>Run against a live server, e.g. inside the server container:
 * <pre>java -cp Server.jar bot.BotSession maplestory 8484 botuser botpass</pre>
 */
public class BotSession {
    private static final int TIMEOUT_MS = 10_000;

    /** World/channel are zero-indexed on the wire; {@code CharlistRequestHandler} adds 1 to the channel. */
    private static final int WORLD = 0;
    private static final int CHANNEL = 0;

    // Known-good v83 starter appearance/equip ids (confirmed against BeginnerCreator + ItemInformationProvider
    // on this server - an unknown item id here throws inside CharacterFactory and CREATE_CHAR silently fails).
    private static final int JOB_ADVENTURER = 1;
    private static final int FACE = 20000;
    private static final int HAIR = 30030;
    private static final int HAIR_COLOR = 0;
    private static final int SKIN_COLOR = 0;
    private static final int TOP = 1040002;
    private static final int BOTTOM = 1060002;
    private static final int SHOES = 1072001;
    private static final int WEAPON = 1302000;
    private static final int GENDER = 0;

    // Client#checkIfIdle fires 30s (IDLE_TIME_SECONDS) after the last read/write, then gives 15s to
    // reply before disconnecting. Wait comfortably past that so a missing PING is a real failure, not
    // just impatience.
    private static final long GAME_LOOP_BUDGET_MS = 65_000;

    /**
     * How often the game loop re-checks whether it should read/plan even with nothing incoming -
     * the periodic floor half of the "event-driven with a periodic fallback" cadence (see
     * {@link Planner}). This is deliberately small relative to {@link #REPLAN_FLOOR_MS}; it only
     * bounds how long {@link MapleConnection#receive()} blocks, not how often we actually replan.
     *
     * <p>Kept well under {@link #REPLAN_FLOOR_MS} on purpose: the floor can only fire as often as this
     * loop actually checks it, so if this were larger than the floor, the floor would be silently
     * capped at this value during quiet stretches - see {@link #REPLAN_FLOOR_MS}'s javadoc for why
     * that distinction mattered live.
     */
    private static final int READ_TICK_MS = 250;

    /**
     * Periodic replan floor: an idle bot (nothing notable happening in {@link WorldState}, and its
     * own last action wasn't a real one) still gets a planning tick this often. Deliberately short -
     * a KPQ farming loop lives almost entirely in "Idle, waiting out my own action cooldown" between
     * real actions (see {@code KpqPlanner}'s {@code ACTION_RETRY_COOLDOWN_MS}), and this floor, not
     * that cooldown, was the actual bottleneck the first time this ran live: at the old 7s, a bot's
     * cooldown could clear half a second in and then sit doing nothing for the rest of the 7s window
     * unless some other bot's broadcast happened to bump {@link WorldState#getChangeVersion()} first.
     * Measured live at roughly 1 kill/75s/bot - "the map's plenty stocked, mobs are being one-shot,
     * yet almost nothing is happening" is exactly the signature of this floor gating everything
     * instead of each planner's own cooldown. This value is now short enough to stay out of the way;
     * a planner's own action-specific cooldown (KPQ's 800ms, this class's own {@link #READ_TICK_MS})
     * is what actually paces things, which is where that job belongs - not in this shared loop.
     */
    private static final long REPLAN_FLOOR_MS = 300;

    private static final String HEX = "0123456789ABCDEF";

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int loginPort = args.length > 1 ? Integer.parseInt(args[1]) : 8484;
        String user = args.length > 2 ? args[2] : "bot";
        String pass = args.length > 3 ? args[3] : "bot";

        BotLog.line("=== BotSession ===");
        BotLog.linef("connecting to %s:%d as '%s'%n", host, loginPort, user);

        ChannelSession session = loginAndEnterChannel(host, loginPort, user, pass);
        try (MapleConnection channel = session.connection()) {
            runGameLoop(channel, session.charId());
        }
    }

    /**
     * Runs the full login flow - handshake, LOGIN_PASSWORD, ToS acceptance, char list, char
     * creation if needed, CHAR_SELECT, the login-to-channel reconnect - and sends PLAYER_LOGGEDIN on
     * the resulting channel connection. The login connection is closed before returning, exactly
     * like a real client disconnecting from the login server once it has the channel's address.
     */
    public static ChannelSession loginAndEnterChannel(String host, int loginPort, String user, String pass)
            throws IOException {
        return loginAndEnterChannel(host, loginPort, user, pass, CHANNEL, conn -> { });
    }

    /**
     * As {@link #loginAndEnterChannel(String, int, String, String)}, but onto a chosen channel
     * ({@code channel} is zero-indexed, as on the wire) and reporting every connection it opens to
     * {@code onConnect} before blocking on it. The callback exists so a supervisor on another thread
     * can close a connection that is stuck mid-login - see {@link MapleConnection#close()}.
     *
     * @throws LoginRejectedException if the login server answers LOGIN_PASSWORD with a failure code.
     */
    public static ChannelSession loginAndEnterChannel(String host, int loginPort, String user, String pass,
                                                      int channel, Consumer<MapleConnection> onConnect)
            throws IOException {
        return loginAndEnterChannel(host, loginPort, user, pass, channel, onConnect, null);
    }

    /**
     * As above, but a character this account has yet to create is named {@code preferredCharName} instead
     * of a name derived from the account.
     *
     * <p>A companion's account is named for the machine and its character is named for players to read,
     * so the caller - not {@link #deriveCharName} - decides what that character is called. The name must
     * already be legal: {@code Character.canCreateChar} refuses a blocked, taken or malformed name
     * <em>silently</em>, and the bot would then wait forever with nothing to read.
     */
    public static ChannelSession loginAndEnterChannel(String host, int loginPort, String user, String pass,
                                                      int channel, Consumer<MapleConnection> onConnect,
                                                      String preferredCharName) throws IOException {
        String hostString = randomHostString();
        String macs = "00-00-00-00-00-00";

        int charId;
        String charName;
        int channelPort;

        try (MapleConnection login = MapleConnection.connect(host, loginPort, TIMEOUT_MS)) {
            onConnect.accept(login);
            BotLog.line("[ok]   login handshake complete, server version v" + login.getServerVersion());

            sendLogin(login, user, pass);
            BotLog.line("[sent] LOGIN_PASSWORD");

            int accountId = readLoginStatus(login);
            BotLog.line("[ok]   authenticated, account id " + accountId);

            login.send(MapleConnection.packet(RecvOpcode.SERVERLIST_REQUEST.getValue()));
            drainServerList(login);
            BotLog.line("[ok]   server list received");

            OutPacket charListReq = MapleConnection.packet(RecvOpcode.CHARLIST_REQUEST.getValue());
            charListReq.writeByte(0);       // leading byte the handler reads and discards
            charListReq.writeByte(WORLD);
            charListReq.writeByte(channel);
            login.send(charListReq);
            BotLog.line("[sent] CHARLIST_REQUEST world=" + WORLD + " channel=" + channel);

            InPacket charList = receiveUntil(login, SendOpcode.CHARLIST.getValue());
            charList.readByte();            // status
            int count = charList.readByte() & 0xFF;
            BotLog.line("[ok]   character list received, " + count + " character(s) on this account");

            if (count == 0) {
                charName = preferredCharName != null ? preferredCharName : deriveCharName(user);
                charId = createCharacter(login, charName);
                BotLog.line("[ok]   created character '" + charName + "', id " + charId);
            } else {
                charId = charList.readInt();    // addCharEntry -> addCharStats starts with the charId int
                // addCharStats writes the name as a fixed 13-byte NUL-padded field, not a length-prefixed
                // string, so it must be read by width (same idiom as the party roster decode).
                String raw = new String(charList.readBytes(13), StandardCharsets.US_ASCII);
                int nul = raw.indexOf('\0');
                charName = nul < 0 ? raw : raw.substring(0, nul);
                BotLog.line("[ok]   reusing existing character '" + charName + "', id " + charId);
            }

            OutPacket select = MapleConnection.packet(RecvOpcode.CHAR_SELECT.getValue());
            select.writeInt(charId);
            select.writeString(macs);
            select.writeString(hostString);
            login.send(select);
            BotLog.line("[sent] CHAR_SELECT charId=" + charId);

            channelPort = readServerIpPort(login);
            BotLog.line("[ok]   SERVER_IP received, channel port " + channelPort
                    + " (advertised address ignored - config.yaml HOST is a LAN IP we reconnect around)");
        }
        BotLog.line("[ok]   closed login connection");

        MapleConnection channelConn = MapleConnection.connect(host, channelPort, TIMEOUT_MS);
        onConnect.accept(channelConn);
        BotLog.line("[ok]   channel handshake complete, server version v" + channelConn.getServerVersion());

        OutPacket loggedIn = MapleConnection.packet(RecvOpcode.PLAYER_LOGGEDIN.getValue());
        loggedIn.writeInt(charId);
        channelConn.send(loggedIn);
        BotLog.line("[sent] PLAYER_LOGGEDIN charId=" + charId);

        return new ChannelSession(channelConn, charId, charName);
    }

    /** LOGIN_STATUS came back with a non-zero reason ({@code PacketCreator.getLoginFailed} codes). */
    public static class LoginRejectedException extends IOException {
        private final int reason;

        public LoginRejectedException(int reason) {
            super("login rejected, LOGIN_STATUS reason " + reason);
            this.reason = reason;
        }

        /** 7 = account already logged in - transient right after that account disconnects. */
        public int reason() {
            return reason;
        }
    }

    /**
     * Reads channel packets, feeds spawn/despawn ones to a {@link WorldState}, and drives a
     * {@link ScriptedPlanner} against it via {@link ActionExecutor} - all while {@code conn} keeps
     * answering the server's keepalive PING with a PONG on its own (see
     * {@link MapleConnection#receive()}) so the server's idle disconnect (Client#checkIfIdle, 30s idle
     * + 15s grace) never fires. Runs for a fixed wall-clock budget, then exits, printing evidence of
     * what it observed.
     */
    private static void runGameLoop(MapleConnection conn, int charId) throws IOException {
        Planner planner = new ScriptedPlanner("Hello, world! (bot " + charId + " reporting in)");
        GameLoopResult result = runGameLoop(conn, charId, planner, GAME_LOOP_BUDGET_MS);

        BotLog.line("");
        BotLog.line(result.sawSetField()
                ? "[ok]   confirmed in-world (SET_FIELD observed)"
                : "[WARN] never saw SET_FIELD - character may not have fully entered the world");
        BotLog.line(result.sawPingPong()
                ? "[ok]   confirmed keepalive works (PING answered with PONG, no disconnect)"
                : "[WARN] no PING observed within the wait window - keepalive not verified this run");
        BotLog.line("[ok]   final position (as last commanded): " + result.world().getSelfPosition());

        if (result.sawSetField() && result.sawPingPong()) {
            BotLog.line("BOT SESSION PASSED - character is in the world and survives the server's keepalive.");
        }
    }

    /** What a {@link #runGameLoop(MapleConnection, int, Planner, long)} run observed, for a caller to report on. */
    public record GameLoopResult(WorldState world, boolean sawSetField, boolean sawPingPong) {}

    /**
     * The reusable half of the game loop above: perceive, replan, act, keepalive, for {@code budgetMs}
     * of wall-clock time against {@code planner}. Factored out so other entry points (see
     * {@code bot.kpq.KpqBot}) can drive a longer-running, purpose-built {@link Planner} through the
     * exact same perception/action wiring proven here, rather than duplicating the PING/PONG and
     * replan-cadence bookkeeping.
     *
     * <p>Every {@code SET_FIELD} - not just the first - triggers {@link WorldState#onMapChanged()}.
     * The very first one is genuinely a "just entered the world" signal; every later one is a map
     * change (see that method's javadoc for why decoding the new map id isn't worth it here) after
     * which any NPC/monster/drop object id from the previous map would be actively wrong to keep.
     */
    public static GameLoopResult runGameLoop(MapleConnection conn, int charId, Planner planner, long budgetMs)
            throws IOException {
        return runGameLoop(conn, charId, planner, budgetMs, () -> false, new WorldState(charId));
    }

    /**
     * As {@link #runGameLoop(MapleConnection, int, Planner, long)}, but also returns early once
     * {@code stopRequested} says so (checked every {@link #READ_TICK_MS}, so a stop lands within about
     * a quarter second) and runs against a caller-supplied {@link WorldState}, so a caller can still
     * act on what the bot perceived after the loop exits - e.g. leave its party before disconnecting.
     */
    public static GameLoopResult runGameLoop(MapleConnection conn, int charId, Planner planner, long budgetMs,
                                             BooleanSupplier stopRequested, WorldState world)
            throws IOException {
        conn.setReadTimeoutMs(READ_TICK_MS);

        ActionExecutor executor = new ActionExecutor(conn, world);

        BotLog.line("[..]   entering game loop, watching for world entry (keepalive is MapleConnection's job now)");
        long deadline = System.currentTimeMillis() + budgetMs;
        boolean sawSetField = false;
        int lastPlannedVersion = -1;
        long lastPlanAt = 0;
        boolean forceReplan = true;   // always take the first opportunity to plan
        long planTicks = 0;
        long lastHeartbeatAt = 0;
        // Every 5s, not every tick - REPLAN_FLOOR_MS alone would make this ~3/sec and drown the log.
        // Added after a live run went completely silent for its whole budget with no server-side
        // evidence either way: the ambiguity this resolves is "is plan() even being called" (a driver
        // bug - the loop itself isn't ticking) versus "plan() runs and keeps returning Idle" (a planner
        // bug - see KpqPlanner#plan's mapChanges==0 fix for a real example of the latter). Without this,
        // telling those two apart meant re-instrumenting and re-running instead of just reading the log.
        final long HEARTBEAT_MS = 5000;

        while (System.currentTimeMillis() < deadline && !stopRequested.getAsBoolean()) {
            try {
                InPacket p = conn.receive();
                int opcode = p.readShort() & 0xFFFF;

                // No PING branch here - MapleConnection#receive() answers it for us before returning
                // it (see that method's javadoc), so a PING just falls through to world.accept() below
                // like any other opcode WorldState doesn't track, and is silently ignored there.
                if (opcode == SendOpcode.SET_FIELD.getValue()) {
                    // PlayerLoggedinHandler sends this once the character is in the channel/world
                    // player storage and map; the same opcode is reused for every later map change
                    // (Character#changeMap -> PacketCreator.getWarpToMap) - see onMapChanged javadoc.
                    sawSetField = true;
                    world.onSetField(p);
                    BotLog.line("[ok]   received SET_FIELD - now on map " + world.getSelfMapId());

                    // Character#changeMap sets mapTransitioning=true on every single map change (not
                    // just login), and ChangeMapHandler refuses to process ANY further CHANGE_MAP
                    // request while it's true (chr.isChangingMaps() is the very first check) - the
                    // real client clears it by sending PLAYER_MAP_TRANSFER once it's done loading the
                    // new map. Found live: without this, a bot's first portal-triggered map change
                    // (e.g. KPQ's entryMap warp) leaves it permanently unable to use any *subsequent*
                    // portal - every UsePortal attempt fails the isChangingMaps() check silently
                    // (enableActions, no error text) forever after, indistinguishable from the 632px
                    // proximity rejection without reading this handler specifically. This has no
                    // per-action decision to make, so it belongs in the driver loop next to PING/PONG,
                    // not exposed to a Planner.
                    conn.send(MapleConnection.packet(RecvOpcode.PLAYER_MAP_TRANSFER.getValue()));
                } else {
                    String note = world.accept(opcode, p);
                    if (note != null) {
                        BotLog.line("[world] " + note);
                    }
                }
            } catch (SocketTimeoutException e) {
                // No packet this tick - expected during quiet stretches; falls through to the
                // cadence check below so an idle map still gets replanned on the periodic floor.
            }

            // Queued chat only leaves on a driver tick (see Speech) - this is the tick.
            executor.tick();

            long now = System.currentTimeMillis();
            boolean notableChange = world.getChangeVersion() != lastPlannedVersion;
            boolean floorElapsed = now - lastPlanAt >= REPLAN_FLOOR_MS;
            if (forceReplan || notableChange || floorElapsed) {
                Action action = planner.plan(world, world.getSelfPosition());
                planTicks++;
                lastPlannedVersion = world.getChangeVersion();
                lastPlanAt = now;
                forceReplan = false;

                if (!(action instanceof Action.Idle)) {
                    BotLog.line("[plan]  " + action);
                    executor.execute(action);
                    forceReplan = true;   // objective step just completed - replan promptly, not on the floor
                } else if (now - lastHeartbeatAt >= HEARTBEAT_MS) {
                    // Idle itself is silent by design (see the branch above), so without this an
                    // all-Idle run - whether genuinely waiting or a stuck planner - prints nothing at
                    // all after world entry, which is exactly the ambiguity this exists to remove.
                    lastHeartbeatAt = now;
                    BotLog.line("[tick]  plan #" + planTicks + ": Idle (loop is ticking; planner has nothing to do)");
                }
            }
        }

        return new GameLoopResult(world, sawSetField, conn.getPingsAnswered() > 0);
    }

    /**
     * Sends CREATE_CHAR with a fixed set of known-good starter ids and waits for the server's
     * ADD_NEW_CHAR_ENTRY confirmation, returning the new character's id.
     */
    private static int createCharacter(MapleConnection login, String name) throws IOException {
        OutPacket p = MapleConnection.packet(RecvOpcode.CREATE_CHAR.getValue());
        p.writeString(name);
        p.writeInt(JOB_ADVENTURER);
        p.writeInt(FACE);
        p.writeInt(HAIR);
        p.writeInt(HAIR_COLOR);
        p.writeInt(SKIN_COLOR);
        p.writeInt(TOP);
        p.writeInt(BOTTOM);
        p.writeInt(SHOES);
        p.writeInt(WEAPON);
        p.writeByte(GENDER);
        login.send(p);
        BotLog.line("[sent] CREATE_CHAR name=" + name);

        InPacket reply = receiveUntil(login, SendOpcode.ADD_NEW_CHAR_ENTRY.getValue());
        reply.readByte();               // addNewCharEntry's leading byte (always 0)
        return reply.readInt();         // addCharStats starts with the charId int
    }

    /**
     * Waits for the login server's reply to CHAR_SELECT, which is either SERVER_IP (success) or
     * SELECT_CHARACTER_BY_VAC (getAfterLoginError - anti-multiclient rejection, bad hwid string,
     * world full, etc). Returns the channel port on success.
     */
    private static int readServerIpPort(MapleConnection conn) throws IOException {
        while (true) {
            InPacket p = conn.receive();
            int opcode = p.readShort() & 0xFFFF;

            if (opcode == SendOpcode.SERVER_IP.getValue()) {
                p.readShort();          // always 0
                p.skip(4);              // advertised address - deliberately ignored, see caller
                int port = p.readShort() & 0xFFFF;
                return port;
            }
            if (opcode == SendOpcode.SELECT_CHARACTER_BY_VAC.getValue()) {
                int reason = p.readShort();
                throw new IOException("CHAR_SELECT rejected, error reason " + reason);
            }
            BotLog.linef("[..]   skipping opcode 0x%02X while waiting for SERVER_IP%n", opcode);
        }
    }

    /** Same shape as {@code LoginSpike#sendLogin}. */
    private static void sendLogin(MapleConnection conn, String user, String pass) throws IOException {
        OutPacket p = MapleConnection.packet(RecvOpcode.LOGIN_PASSWORD.getValue());
        p.writeString(user);
        p.writeString(pass);
        p.writeBytes(new byte[6]);
        p.writeBytes(new byte[]{0x00, 0x00, 0x00, 0x00});
        conn.send(p);
    }

    /**
     * Same shape as {@code LoginSpike#readLoginStatus}: accepts ToS on a fresh auto-registered account.
     * Returns the account id, or throws {@link LoginRejectedException} carrying the failure code.
     */
    private static int readLoginStatus(MapleConnection conn) throws IOException {
        boolean acceptedToS = false;
        while (true) {
            InPacket p = conn.receive();
            int opcode = p.readShort();
            if (opcode != SendOpcode.LOGIN_STATUS.getValue()) {
                BotLog.linef("[..]   ignoring opcode 0x%02X while waiting for LOGIN_STATUS%n", opcode);
                continue;
            }

            int reason = p.readInt();
            p.readShort();

            if (reason == 23 && !acceptedToS) {
                acceptedToS = true;
                OutPacket tos = MapleConnection.packet(RecvOpcode.ACCEPT_TOS.getValue());
                tos.writeByte(1);
                conn.send(tos);
                BotLog.line("[sent] ACCEPT_TOS (new account needs to accept the terms)");
                continue;
            }

            if (reason != 0) {
                BotLog.line("[FAIL] LOGIN_STATUS reason code " + reason);
                throw new LoginRejectedException(reason);
            }
            return p.readInt();
        }
    }

    /** Same shape as {@code LoginSpike#drainServerList}, but discards the payload - we only need the count. */
    private static void drainServerList(MapleConnection conn) throws IOException {
        while (true) {
            InPacket p = conn.receive();
            int opcode = p.readShort();
            if (opcode != SendOpcode.SERVERLIST.getValue()) {
                BotLog.linef("[..]   ignoring opcode 0x%02X while reading the server list%n", opcode);
                continue;
            }
            if ((p.readByte() & 0xFF) == 0xFF) {   // list terminator
                return;
            }
        }
    }

    /**
     * Reads packets until one carries {@code wantedOpcode}, discarding anything else. Both the login
     * and channel servers volunteer packets (LAST_CONNECTED_WORLD, RECOMMENDED_WORLD_MESSAGE, etc)
     * that the real client simply ignores.
     */
    private static InPacket receiveUntil(MapleConnection conn, int wantedOpcode) throws IOException {
        while (true) {
            InPacket p = conn.receive();
            int opcode = p.readShort();
            if (opcode == wantedOpcode) {
                return p;
            }
            BotLog.linef("[..]   skipping opcode 0x%02X%n", opcode);
        }
    }

    /** Character.canCreateChar requires 3-12 alphanumeric characters. */
    static String deriveCharName(String account) {
        String cleaned = account.replaceAll("[^a-zA-Z0-9]", "");
        if (cleaned.length() < 3) {
            cleaned = (cleaned + "Bot123").substring(0, 6);
        }
        if (cleaned.length() > 12) {
            cleaned = cleaned.substring(0, 12);
        }
        return cleaned;
    }

    /** Builds a fresh "<12 hex>_<8 hex>" identity accepted by {@code Hwid.fromHostString}. */
    private static String randomHostString() {
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(21);
        for (int i = 0; i < 12; i++) {
            sb.append(HEX.charAt(rnd.nextInt(16)));
        }
        sb.append('_');
        for (int i = 0; i < 8; i++) {
            sb.append(HEX.charAt(rnd.nextInt(16)));
        }
        return sb.toString();
    }
}
