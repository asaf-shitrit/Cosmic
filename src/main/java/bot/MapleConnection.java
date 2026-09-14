package bot;

import constants.net.ServerConstants;
import io.netty.buffer.Unpooled;
import net.encryption.InitializationVector;
import net.encryption.MapleAESOFB;
import net.encryption.MapleCustomEncryption;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.packet.Packet;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * One TCP connection to a Cosmic server, speaking the v83 protocol as a client rather than as the
 * server. This is the mirror image of the server's netty pipeline: the cyphers are the same
 * {@link MapleAESOFB} the server uses, just swapped round, so whatever the server accepts from the
 * real client it accepts from here.
 *
 * <p>The IV roles are the part that's easy to get backwards. The server generates two IVs and
 * hands both to the client in the clear, labelled from its own point of view: the one it calls
 * "receive" is the one this connection has to <em>send</em> with, and vice versa.
 */
public class MapleConnection implements Closeable {
    /** Length of the unencrypted hello payload, excluding its own 2-byte length prefix. */
    private static final int HELLO_PAYLOAD_LENGTH = 14;
    private static final int HEADER_LENGTH = 4;

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final MapleAESOFB sendCypher;
    private final MapleAESOFB receiveCypher;
    private final short serverVersion;

    private MapleConnection(Socket socket, DataInputStream in, OutputStream out, MapleAESOFB sendCypher,
                            MapleAESOFB receiveCypher, short serverVersion) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.sendCypher = sendCypher;
        this.receiveCypher = receiveCypher;
        this.serverVersion = serverVersion;
    }

    /**
     * Connects and completes the unencrypted handshake, leaving the connection ready to send
     * encrypted packets.
     */
    public static MapleConnection connect(String host, int port, int timeoutMs) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setTcpNoDelay(true);

        DataInputStream in = new DataInputStream(socket.getInputStream());
        OutputStream out = socket.getOutputStream();

        // The hello is sent in the clear, little-endian length prefix first.
        int helloLength = readShortLE(in);
        if (helloLength != HELLO_PAYLOAD_LENGTH) {
            socket.close();
            throw new IOException("Unexpected hello length " + helloLength + ", expected " + HELLO_PAYLOAD_LENGTH
                    + " - is this actually a v83 server?");
        }

        byte[] hello = new byte[helloLength];
        in.readFully(hello);

        short version = (short) ((hello[0] & 0xFF) | ((hello[1] & 0xFF) << 8));
        // hello[2..3] = patch string length, hello[4] = the patch string itself ("1")
        byte[] serverRecvIv = Arrays.copyOfRange(hello, 5, 9);
        byte[] serverSendIv = Arrays.copyOfRange(hello, 9, 13);

        // Swap the roles: our send cypher must be byte-identical to the server's receive cypher.
        MapleAESOFB sendCypher = new MapleAESOFB(InitializationVector.of(serverRecvIv), version);
        MapleAESOFB receiveCypher = new MapleAESOFB(InitializationVector.of(serverSendIv),
                (short) (0xFFFF - version));

        return new MapleConnection(socket, in, out, sendCypher, receiveCypher, version);
    }

    public short getServerVersion() {
        return serverVersion;
    }

    /**
     * Makes {@link #receive()} throw {@link SocketTimeoutException} after {@code millis} of no data
     * instead of blocking forever, so a caller can interleave its own periodic work (e.g. a planner
     * tick) with packet handling on a single thread - useful because {@link #send} and
     * {@link #receive()} share this object's monitor, so a second "sender" thread would simply block
     * behind an in-progress {@link #receive()} call anyway.
     *
     * <p>This assumes a timeout only ever lands between frames, never mid-frame - true in practice on
     * a local/LAN connection where a whole packet arrives well within one OS read. A mid-frame
     * timeout would desync the cypher just like any other corrupted read; the next {@link #receive()}
     * would surface it as an "invalid packet header" IOException rather than silently misbehaving.
     */
    public void setReadTimeoutMs(int millis) throws SocketException {
        socket.setSoTimeout(millis);
    }

    /** Starts a packet addressed to the server, with the given opcode from RecvOpcode. */
    public static OutPacket packet(int opcode) {
        OutPacket p = new ByteBufOutPacket();
        p.writeShort(opcode);
        return p;
    }

    /**
     * Outbound cap, enforced in {@link #send} itself rather than trusting every caller to throttle
     * its own retries. Learned live: a {@code Planner} with an unguarded retry loop (a failed pickup
     * with no cooldown, replanning promptly after every action - see {@code KpqPlanner}'s
     * {@code ACTION_RETRY_COOLDOWN_MS} javadoc for the full story) drove one bot to roughly a
     * thousand sends a second for several minutes, which wedged Docker Desktop's own management API
     * hard enough to need a forced restart - nowhere near "the server slowed down", the *host*
     * stopped responding. A planner-level cooldown fixes one loop; this fixes the whole class of bug,
     * for every {@link Planner} this codebase will ever grow, at the one point every outbound packet
     * already passes through regardless of which code path produced it. 20/sec is far above anything
     * a legitimate action sequence needs (the fastest deliberate retry cadence anywhere in this
     * codebase is one NPC talk per ~700ms) and orders of magnitude below what actually caused the
     * incident.
     */
    private static final int MAX_PACKETS_PER_SECOND = 20;
    private static final long RATE_WINDOW_MS = 1000;

    /** Timestamps (ms) of sends within the current rate window, oldest first. */
    private final Deque<Long> recentSendTimes = new ArrayDeque<>();

    public synchronized void send(Packet packet) throws IOException {
        enforceOutboundRateLimit();

        byte[] body = packet.getBytes();
        byte[] header = sendCypher.getPacketHeader(body.length);

        // Order matters and mirrors PacketEncoder exactly: custom shuffle, then AES.
        MapleCustomEncryption.encryptData(body);
        sendCypher.crypt(body);

        byte[] frame = new byte[header.length + body.length];
        System.arraycopy(header, 0, frame, 0, header.length);
        System.arraycopy(body, 0, frame, header.length, body.length);
        out.write(frame);
        out.flush();
    }

    /**
     * Blocks the caller until sending one more packet would keep this connection at or under
     * {@link #MAX_PACKETS_PER_SECOND} within the trailing {@link #RATE_WINDOW_MS}, logging loudly the
     * first time in a burst that throttling actually kicks in (repeating that on every single send
     * while throttled would just be more flood, on stderr instead of the wire). Called from
     * {@link #send}, which is already {@code synchronized}, so this never races with itself.
     */
    private void enforceOutboundRateLimit() {
        long now = System.currentTimeMillis();
        purgeExpired(now);

        if (recentSendTimes.size() >= MAX_PACKETS_PER_SECOND) {
            System.err.println("[WARN] MapleConnection: outbound rate limit hit (" + MAX_PACKETS_PER_SECOND
                    + "/sec) - throttling. A caller is retrying far faster than any legitimate action "
                    + "needs; this is almost certainly a missing cooldown upstream, not a real workload.");
            while (recentSendTimes.size() >= MAX_PACKETS_PER_SECOND) {
                long waitMs = RATE_WINDOW_MS - (now - recentSendTimes.peekFirst());
                try {
                    Thread.sleep(Math.max(1, waitMs));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                now = System.currentTimeMillis();
                purgeExpired(now);
            }
        }

        recentSendTimes.addLast(now);
    }

    private void purgeExpired(long now) {
        while (!recentSendTimes.isEmpty() && now - recentSendTimes.peekFirst() >= RATE_WINDOW_MS) {
            recentSendTimes.pollFirst();
        }
    }

    /**
     * Reads one packet, blocking until it arrives.
     *
     * @return the decrypted packet, positioned at its opcode.
     */
    public synchronized InPacket receive() throws IOException {
        byte[] headerBytes = new byte[HEADER_LENGTH];
        in.readFully(headerBytes);

        int header = ((headerBytes[0] & 0xFF) << 24) | ((headerBytes[1] & 0xFF) << 16)
                | ((headerBytes[2] & 0xFF) << 8) | (headerBytes[3] & 0xFF);
        if (!receiveCypher.isValidHeader(header)) {
            throw new IOException("Invalid packet header " + Integer.toHexString(header)
                    + " - cypher state has desynchronised from the server");
        }

        byte[] body = new byte[decodePacketLength(header)];
        in.readFully(body);
        receiveCypher.crypt(body);
        MapleCustomEncryption.decryptData(body);

        return new ByteBufInPacket(Unpooled.wrappedBuffer(body));
    }

    /** Mirrors PacketDecoder#decodePacketLength. */
    private static int decodePacketLength(int header) {
        int length = ((header >>> 16) ^ (header & 0xFFFF));
        return ((length << 8) & 0xFF00) | ((length >>> 8) & 0xFF);
    }

    private static int readShortLE(DataInputStream in) throws IOException {
        int low = in.read();
        int high = in.read();
        if (low < 0 || high < 0) {
            throw new IOException("Connection closed before the hello packet arrived");
        }
        return (low & 0xFF) | ((high & 0xFF) << 8);
    }

    public static short version() {
        return ServerConstants.VERSION;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
