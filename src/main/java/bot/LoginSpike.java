package bot;

import net.opcodes.RecvOpcode;
import net.opcodes.SendOpcode;
import net.packet.InPacket;
import net.packet.OutPacket;

/**
 * Proves a hand-rolled client can complete the v83 handshake, authenticate, and read back the
 * world and character lists. This is the risky half of running real bot clients: if the crypto,
 * framing and string encoding are right, everything after this is just more packet shapes.
 *
 * <p>Run against a live server, e.g. inside the server container:
 * <pre>java -cp Server.jar bot.LoginSpike 127.0.0.1 8484 botuser botpass</pre>
 */
public class LoginSpike {
    private static final int TIMEOUT_MS = 10_000;

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8484;
        String user = args.length > 2 ? args[2] : "spikebot";
        String pass = args.length > 3 ? args[3] : "spikebot";

        System.out.println("=== v83 login spike ===");
        System.out.printf("connecting to %s:%d as '%s'%n", host, port, user);

        try (MapleConnection conn = MapleConnection.connect(host, port, TIMEOUT_MS)) {
            System.out.println("[ok]   handshake complete, server version v" + conn.getServerVersion());

            sendLogin(conn, user, pass);
            System.out.println("[sent] LOGIN_PASSWORD");

            int accountId = readLoginStatus(conn);
            if (accountId < 0) {
                System.out.println("[FAIL] login rejected - see status code above");
                return;
            }
            System.out.println("[ok]   authenticated, account id " + accountId);

            conn.send(MapleConnection.packet(RecvOpcode.SERVERLIST_REQUEST.getValue()));
            System.out.println("[sent] SERVERLIST_REQUEST");
            int worlds = drainServerList(conn);
            System.out.println("[ok]   server list received, " + worlds + " world(s)");

            OutPacket charList = MapleConnection.packet(RecvOpcode.CHARLIST_REQUEST.getValue());
            charList.writeByte(0);      // leading byte the handler reads and discards
            charList.writeByte(0);      // world 0 (Scania)
            charList.writeByte(0);      // channel, zero indexed - the handler adds 1
            conn.send(charList);
            System.out.println("[sent] CHARLIST_REQUEST world=0 channel=0");

            // ServerlistRequestHandler tails the list with selectWorld and sendRecommended, so
            // there are still unread packets ahead of the char list.
            InPacket reply = receiveUntil(conn, SendOpcode.CHARLIST.getValue());
            reply.readByte();           // status
            int characters = reply.readByte();
            System.out.println("[ok]   character list received, " + characters + " character(s) on this account");

            System.out.println();
            System.out.println("SPIKE PASSED - crypto, framing and login flow all verified end to end.");
            if (characters == 0) {
                System.out.println("Account has no characters yet; CREATE_CHAR is the next packet to implement.");
            }
        }
    }

    /**
     * Reads packets until one carries {@code wantedOpcode}, discarding anything else. The login
     * server volunteers a fair amount the real client simply ignores.
     *
     * @return the wanted packet, positioned just after its opcode.
     */
    private static InPacket receiveUntil(MapleConnection conn, int wantedOpcode) throws Exception {
        while (true) {
            InPacket p = conn.receive();
            int opcode = p.readShort();
            if (opcode == wantedOpcode) {
                return p;
            }
            System.out.printf("[..]   skipping opcode 0x%02X%n", opcode);
        }
    }

    private static void sendLogin(MapleConnection conn, String user, String pass) throws Exception {
        OutPacket p = MapleConnection.packet(RecvOpcode.LOGIN_PASSWORD.getValue());
        p.writeString(user);
        p.writeString(pass);
        p.writeBytes(new byte[6]);      // the real client masks this with zeroes
        p.writeBytes(new byte[]{0x00, 0x00, 0x00, 0x00});   // hwid nibbles
        conn.send(p);
    }

    /**
     * Reads the login result, accepting the Terms of Service if the server asks for them. A
     * freshly auto-registered account always asks: Client#login returns 23 while its tos column
     * is still 0, and the server only sends auth success once ACCEPT_TOS comes back.
     *
     * @return the account id on success, or -1 if the server rejected the login.
     */
    private static int readLoginStatus(MapleConnection conn) throws Exception {
        boolean acceptedToS = false;
        while (true) {
            InPacket p = conn.receive();
            int opcode = p.readShort();
            if (opcode != SendOpcode.LOGIN_STATUS.getValue()) {
                System.out.printf("[..]   ignoring opcode 0x%02X while waiting for LOGIN_STATUS%n", opcode);
                continue;
            }

            int reason = p.readInt();
            p.readShort();

            if (reason == 23 && !acceptedToS) {
                acceptedToS = true;
                OutPacket tos = MapleConnection.packet(RecvOpcode.ACCEPT_TOS.getValue());
                tos.writeByte(1);
                conn.send(tos);
                System.out.println("[sent] ACCEPT_TOS (new account needs to accept the terms)");
                continue;
            }

            if (reason != 0) {
                System.out.println("[FAIL] LOGIN_STATUS reason code " + reason);
                return -1;
            }
            return p.readInt();
        }
    }

    private static int drainServerList(MapleConnection conn) throws Exception {
        int worlds = 0;
        while (true) {
            InPacket p = conn.receive();
            int opcode = p.readShort();
            if (opcode != SendOpcode.SERVERLIST.getValue()) {
                System.out.printf("[..]   ignoring opcode 0x%02X while reading the server list%n", opcode);
                continue;
            }
            // The list is terminated by an entry with world id 0xFF.
            if ((p.readByte() & 0xFF) == 0xFF) {
                return worlds;
            }
            worlds++;
        }
    }
}
