package bot.residents;

import bot.BotSession;
import bot.ChannelSession;
import bot.MapPortals;
import bot.MapleConnection;
import net.opcodes.RecvOpcode;
import net.opcodes.SendOpcode;
import net.packet.InPacket;
import net.packet.OutPacket;

import java.awt.Point;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A scripted stand-in for the human player when verifying residents: an independent client in its own
 * process that sees only what the wire shows it. Run inside the server container, e.g.
 * <pre>java -cp Server.jar bot.residents.ResidentCheckClient 127.0.0.1 8484 acct pass watch 120</pre>
 *
 * Commands (every one ends after its wall-clock budget in seconds):
 * <ul>
 *   <li>{@code watch <s>} - log merchants spawning/closing, players, chat, chairs and whispers on its map</li>
 *   <li>{@code buy <seller> <itemId> <bundles> <s>} - visit the seller's Hired Merchant, find the item in
 *       the room's item list and buy it</li>
 *   <li>{@code merchant <x> <y> <title> <type:slot:bundles:perBundle:price>... <s>} - open its own Hired
 *       Merchant at x,y (needs a 503xxxx permit and the items in those slots), then keep watching</li>
 *   <li>{@code whisper <target> <message> <s>} and {@code say <message> <s>} - talk, then watch for replies</li>
 * </ul>
 * The character must already be standing in an FM room (set {@code characters.map} while offline).
 */
public final class ResidentCheckClient {
    private final MapleConnection conn;
    private final Map<Integer, String> names = new HashMap<>();
    /** ownerName -> (objectId, position, title, ownerId) of merchants on this map. */
    private final Map<String, Object[]> merchants = new HashMap<>();
    private int mapId = -1;
    private Point position = new Point(0, 0);
    private InPacket lastRoom;

    private ResidentCheckClient(MapleConnection conn) {
        this.conn = conn;
    }

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        ChannelSession session = BotSession.loginAndEnterChannel(host, port, args[2], args[3]);
        try (MapleConnection conn = session.connection()) {
            conn.setReadTimeoutMs(250);
            ResidentCheckClient c = new ResidentCheckClient(conn);
            String command = args[4];
            long budgetMs = Long.parseLong(args[args.length - 1]) * 1000;
            long end = System.currentTimeMillis() + budgetMs;
            c.pumpUntil(() -> c.mapId > 0, System.currentTimeMillis() + 15_000);
            c.pumpFor(2500);
            switch (command) {
                case "watch" -> { }
                case "buy" -> c.buy(args[5], Integer.parseInt(args[6]), Integer.parseInt(args[7]));
                case "merchant" -> {
                    List<int[]> listings = new ArrayList<>();
                    for (int i = 8; i < args.length - 1; i++) {
                        String[] f = args[i].split(":");
                        listings.add(new int[]{Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]),
                                Integer.parseInt(f[3]), Integer.parseInt(f[4])});
                    }
                    c.openMerchant(new Point(Integer.parseInt(args[5]), Integer.parseInt(args[6])), args[7], listings);
                }
                case "whisper" -> {
                    c.conn.send(ShopPackets.whisper(args[5], args[6]));
                    say("sent whisper to " + args[5] + ": " + args[6]);
                }
                case "say" -> {
                    OutPacket p = MapleConnection.packet(RecvOpcode.GENERAL_CHAT.getValue());
                    p.writeString(args[5]);
                    p.writeByte(0);
                    c.conn.send(p);
                    say("said: " + args[5]);
                }
                default -> throw new IllegalArgumentException("unknown command " + command);
            }
            c.pumpUntil(() -> false, end);
            say("check client finished");
        }
    }

    private void buy(String seller, int itemId, int bundles) throws IOException {
        pumpUntil(() -> merchants.containsKey(seller), System.currentTimeMillis() + 30_000);
        Object[] m = merchants.get(seller);
        if (m == null) {
            say("FAIL no merchant owned by " + seller + " on map " + mapId);
            return;
        }
        Point at = (Point) m[1];
        walk(new Point(at.x - 50, at.y));
        lastRoom = null;
        conn.send(ShopPackets.visit((int) m[0]));
        pumpUntil(() -> lastRoom != null, System.currentTimeMillis() + 5000);
        if (lastRoom == null) {
            say("FAIL merchant room never opened");
            return;
        }
        List<int[]> items = parseMerchantItems(lastRoom, (String) m[2]);
        int index = -1;
        for (int i = 0; i < items.size(); i++) {
            int[] it = items.get(i);
            say("  shop entry " + i + ": item " + it[0] + " bundles " + it[1] + " x" + it[2] + " @ " + it[3] + " mesos/bundle");
            if (it[0] == itemId && index < 0) {
                index = i;
            }
        }
        if (index < 0) {
            say("FAIL item " + itemId + " not in " + seller + "'s shop");
        } else {
            conn.send(ShopPackets.buy(true, index, bundles));
            say("sent MERCHANT_BUY index " + index + " x" + bundles);
            pumpFor(2500);
        }
        conn.send(ShopPackets.exit());
        pumpFor(1000);
    }

    private void openMerchant(Point spot, String title, List<int[]> listings) throws IOException {
        walk(spot);
        pumpFor(500);
        conn.send(ShopPackets.hiredMerchantRequest());
        pumpFor(1500);
        conn.send(ShopPackets.createHiredMerchant(title, ResidentSetup.HIRED_MERCHANT_PERMIT));
        pumpFor(1500);
        for (int[] l : listings) {
            conn.send(ShopPackets.putItem(l[0], l[1], l[2], l[3], l[4]));
            say("sent PUT_ITEM type " + l[0] + " slot " + l[1] + " bundles " + l[2] + " x" + l[3] + " @ " + l[4]);
            pumpFor(800);
        }
        conn.send(ShopPackets.openStore());
        say("sent OPEN_STORE for \"" + title + "\"");
        pumpFor(2000);
    }

    /**
     * The visitor's view of {@code getHiredMerchant}: the item list follows the title string, a slot-count
     * byte and the visitor's mesos. Locating it by the title (already known from the spawn packet) avoids
     * decoding every visitor's character look before it. Non-cash items and equips only.
     */
    static List<int[]> parseMerchantItems(InPacket p, String title) {
        byte[] all = p.readBytes(p.available());
        byte[] needle = new byte[title.length() + 3];
        needle[0] = (byte) title.length();
        needle[1] = (byte) (title.length() >> 8);
        for (int i = 0; i < title.length(); i++) {
            needle[2 + i] = (byte) title.charAt(i);
        }
        needle[needle.length - 1] = 0x10;
        int start = indexOf(all, needle);
        List<int[]> items = new ArrayList<>();
        if (start < 0) {
            return items;
        }
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(all).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.position(start + needle.length);
        b.getInt();                                   // this visitor's mesos
        int count = b.get() & 0xFF;
        for (int i = 0; i < count; i++) {
            int bundles = b.getShort();
            int quantity = b.getShort();
            int price = b.getInt();
            int type = b.get();
            int itemId = b.getInt();
            if (b.get() != 0) {
                break;                                // cash items aren't sold by residents
            }
            b.getLong();                              // expiration
            if (type == 1) {
                b.position(b.position() + 2 + 15 * 2);  // slots, level, 15 stat shorts
                skipString(b);                        // owner
                b.position(b.position() + 2 + 1 + 1 + 4 + 4 + 8 + 8 + 4);
            } else {
                b.getShort();                         // quantity per bundle (again)
                skipString(b);
                b.getShort();                         // flag
            }
            items.add(new int[]{itemId, bundles, quantity, price});
        }
        return items;
    }

    private static void skipString(java.nio.ByteBuffer b) {
        int len = b.getShort();
        b.position(b.position() + len);
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private void walk(Point target) throws IOException {
        Point from = position;
        int steps = Math.max(1, (int) Math.ceil(from.distance(target) / 110));
        for (int i = 1; i <= steps; i++) {
            Point step = new Point(from.x + (target.x - from.x) * i / steps, from.y + (target.y - from.y) * i / steps);
            OutPacket p = MapleConnection.packet(RecvOpcode.MOVE_PLAYER.getValue());
            p.writeBytes(new byte[9]);
            p.writeByte(1);
            p.writeByte(0);
            p.writePos(step);
            p.writePos(new Point(0, 0));
            p.writeShort(0);
            p.writeByte(0);
            p.writeShort(0);
            conn.send(p);
            position = step;
            pumpFor(200);
        }
    }

    private void pumpFor(long ms) throws IOException {
        long end = System.currentTimeMillis() + ms;
        pumpUntil(() -> false, end);
    }

    private void pumpUntil(java.util.function.BooleanSupplier done, long end) throws IOException {
        while (!done.getAsBoolean() && System.currentTimeMillis() < end) {
            try {
                handle(conn.receive());
            } catch (SocketTimeoutException ignored) {
                // quiet tick
            }
        }
    }

    private void handle(InPacket p) throws IOException {
        int op = p.readShort() & 0xFFFF;
        if (op == SendOpcode.SET_FIELD.getValue()) {
            p.readInt();
            if (p.readByte() == 1) {
                p.skip(1 + 2 + 3 * 4 + 8 + 1);
                p.skip(4 + 13 + 1 + 1 + 4 + 4 + 3 * 8 + 1 + 2 + 8 * 2 + 2 + 2 + 4 + 2 + 4);
            } else {
                p.skip(3 + 1);
            }
            mapId = p.readInt();
            int portal = p.readByte() & 0xFF;
            position = MapPortals.byId(mapId, portal).map(MapPortals.PortalInfo::position).orElse(new Point(0, 0));
            merchants.clear();
            conn.send(MapleConnection.packet(RecvOpcode.PLAYER_MAP_TRANSFER.getValue()));
            say("on map " + mapId + " at " + position.x + "," + position.y);
        } else if (op == SendOpcode.SPAWN_HIRED_MERCHANT.getValue()) {
            int owner = p.readInt();
            p.readInt();
            Point pos = new Point(p.readShort(), p.readShort());
            p.readShort();
            String ownerName = p.readString();
            p.readByte();
            int oid = p.readInt();
            String title = p.readString();
            merchants.put(ownerName, new Object[]{oid, pos, title, owner});
            say("[merchant] " + ownerName + " (id " + owner + ") has a Hired Merchant \"" + title + "\" at " + pos.x + "," + pos.y + " oid " + oid);
        } else if (op == SendOpcode.DESTROY_HIRED_MERCHANT.getValue()) {
            int owner = p.readInt();
            merchants.values().removeIf(v -> (int) v[3] == owner);
            say("[merchant] merchant of character " + owner + " closed");
        } else if (op == SendOpcode.SPAWN_PLAYER.getValue()) {
            int id = p.readInt();
            p.readByte();
            String name = p.readString();
            names.put(id, name);
            say("[player] " + name + " (id " + id + ") is on the map");
        } else if (op == SendOpcode.REMOVE_PLAYER_FROM_MAP.getValue()) {
            int id = p.readInt();
            say("[player] " + names.getOrDefault(id, String.valueOf(id)) + " left the map");
        } else if (op == SendOpcode.CHATTEXT.getValue()) {
            int from = p.readInt();
            p.readByte();
            say("[chat] " + names.getOrDefault(from, String.valueOf(from)) + ": " + p.readString());
        } else if (op == SendOpcode.WHISPER.getValue()) {
            int flag = p.readByte() & 0xFF;
            if (flag == 0x12) {
                String from = p.readString();
                p.readByte();
                p.readByte();
                say("[whisper] " + from + ": " + p.readString());
            }
        } else if (op == SendOpcode.SHOW_CHAIR.getValue()) {
            int id = p.readInt();
            int chair = p.readInt();
            say("[chair] " + names.getOrDefault(id, String.valueOf(id)) + (chair == 0 ? " stood up" : " sat on chair " + chair));
        } else if (op == SendOpcode.PLAYER_INTERACTION.getValue()) {
            int mode = p.readByte();
            if (mode == 5) {
                int sub = p.readByte();
                if (sub == 0) {
                    say("[shop] mini-room error " + p.readByte());
                } else {
                    p.seek(2);
                    lastRoom = p;
                    say("[shop] room window opened (type " + sub + ")");
                }
            } else {
                say("[shop] interaction mode 0x" + Integer.toHexString(mode & 0xFF));
            }
        } else if (op == SendOpcode.ENTRUSTED_SHOP_CHECK_RESULT.getValue()) {
            say("[shop] merchant check result 0x" + Integer.toHexString(p.readByte() & 0xFF));
        } else if (op == SendOpcode.STAT_CHANGED.getValue()) {
            say("[stat] stat update");
        } else if (op == SendOpcode.INVENTORY_OPERATION.getValue()) {
            say("[inventory] inventory changed");
        } else if (op == SendOpcode.SERVERMESSAGE.getValue()) {
            int type = p.readByte();
            if (type == 1 || type == 5 || type == 6) {
                say("[notice " + type + "] " + p.readString());
            }
        }
    }

    private static void say(String line) {
        System.out.println(LocalTime.now().withNano(0) + " " + line);
    }
}
