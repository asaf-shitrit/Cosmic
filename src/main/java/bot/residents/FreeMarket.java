package bot.residents;

import client.Character;
import net.server.Server;
import net.server.channel.Channel;
import server.maps.HiredMerchant;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import server.maps.PlayerShop;
import server.maps.PlayerShopItem;
import server.maps.Portal;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Free Market as residents see it: room geometry, where a merchant may stand, and the listings in
 * open shops.
 *
 * <p>Reading listings from the server's shop registry is the in-game equivalent of the Owl of Minerva,
 * which searches every shop on the channel for an item and shows seller and price; it is not a peek at
 * anything a player couldn't learn. Buying still happens over the wire: walk to the shop, visit, buy.
 *
 * <p>All FM rooms (910000001-910000022) share one layout (see {@code wz/Map.wz/Map/Map9/91000000N.img.xml}):
 * three platforms at y 34, -206 and -416, with teleport portals at (-274,30), (-248,-209), (423,-211)
 * and (676,-421). {@code PlayerInteractionHandler#canPlaceStore} rejects a store within 120px of the
 * nearest teleport portal or within ~151px ({@code distanceSq < 23000}) of another store, silently
 * apart from a mini-room error, so the spots below keep 170px apart and clear of every teleport portal.
 */
final class FreeMarket {
    static final int ENTRANCE = 910000000;
    static final int FREDRICK_NPC = 9030000;
    /** Where Fredrick stands in the entrance ({@code 910000000.img.xml} life). */
    static final Point FREDRICK_POSITION = new Point(83, -179);

    private static final Point[] SPOTS = {
            new Point(50, 34), new Point(220, 34), new Point(-120, 34), new Point(390, 34), new Point(560, 34),
            new Point(115, -206), new Point(-60, -206), new Point(290, -206),
            new Point(80, -416), new Point(260, -416), new Point(-100, -416), new Point(440, -416)};
    /** A little more than the server's own 23000, so a spot never sits right on the rejection boundary. */
    private static final double STORE_CLEARANCE_SQ = 28_000;
    private static final double PORTAL_CLEARANCE = 130;

    /** One item for sale in an open shop. {@code index} is its position in the shop's list, what BUY sends. */
    record Listing(String seller, int sellerId, boolean hiredMerchant, int mapId, int objectId, Point position, int index,
                   int itemId, int bundles, int perBundle, int pricePerBundle) {
        int unitPrice() {
            return (int) Math.ceil((double) pricePerBundle / perBundle);
        }
    }

    private FreeMarket() {
    }

    static boolean isFreeMarket(int mapId) {
        return mapId >= ENTRANCE && mapId <= 910000022;
    }

    static MapleMap map(int worldId, int channel, int mapId) {
        Channel ch = Server.getInstance().getChannel(worldId, channel);
        return ch == null ? null : ch.getMapFactory().getMap(mapId);
    }

    static HiredMerchant ownMerchant(int worldId, int charId) {
        return Server.getInstance().getWorld(worldId).getHiredMerchant(charId);
    }

    /**
     * A spot in {@code room} where a merchant may open: the preferred spot for {@code seed} if free,
     * otherwise the next free one. Empty if the room is full.
     */
    static Optional<Point> freeSpot(MapleMap room, int seed) {
        for (int i = 0; i < SPOTS.length; i++) {
            Point spot = SPOTS[Math.floorMod(seed + i, SPOTS.length)];
            if (isFree(room, spot)) {
                return Optional.of(new Point(spot));
            }
        }
        return Optional.empty();
    }

    private static boolean isFree(MapleMap room, Point spot) {
        for (MapObject o : room.getMapObjectsInRange(spot, STORE_CLEARANCE_SQ, List.of(MapObjectType.HIRED_MERCHANT, MapObjectType.PLAYER))) {
            if (o instanceof HiredMerchant) {
                return false;
            }
            if (o instanceof Character c && c.getPlayerShop() != null && c.getPlayerShop().isOwner(c)) {
                return false;
            }
        }
        Portal portal = room.findClosestTeleportPortal(spot);
        return portal == null || portal.getPosition().distance(spot) >= PORTAL_CLEARANCE;
    }

    /** Every listing in open shops in the FM rooms of this channel, excluding {@code exceptOwnerId}'s own. */
    static List<Listing> listings(int worldId, int channel, int exceptOwnerId) {
        List<Listing> out = new ArrayList<>();
        var world = Server.getInstance().getWorld(worldId);
        for (HiredMerchant hm : world.getActiveMerchants()) {
            if (hm.getChannel() != channel || hm.getOwnerId() == exceptOwnerId || hm.getMap() == null || !isFreeMarket(hm.getMapId())) {
                continue;
            }
            addItems(out, hm.getOwner(), hm.getOwnerId(), true, hm.getMapId(), hm.getObjectId(), hm.getPosition(), hm.getItems());
        }
        for (PlayerShop ps : world.getActivePlayerShops()) {
            Character owner = ps.getOwner();
            if (ps.getChannel() != channel || owner.getId() == exceptOwnerId || !ps.isOpen() || !isFreeMarket(ps.getMapId())) {
                continue;
            }
            addItems(out, owner.getName(), owner.getId(), false, ps.getMapId(), ps.getObjectId(), ps.getPosition(), ps.getItems());
        }
        return out;
    }

    private static void addItems(List<Listing> out, String seller, int sellerId, boolean merchant, int mapId, int oid, Point pos,
                                 List<PlayerShopItem> items) {
        // getItems() is an unmodifiable view of a list the shop mutates under its own lock; a concurrent
        // sale can make the copy throw, and a skipped shop this scan is harmless.
        List<PlayerShopItem> copy;
        try {
            copy = new ArrayList<>(items);
        } catch (java.util.ConcurrentModificationException e) {
            return;
        }
        for (int i = 0; i < copy.size(); i++) {
            PlayerShopItem it = copy.get(i);
            if (!it.isExist() || it.getBundles() <= 0) {
                continue;
            }
            out.add(new Listing(seller, sellerId, merchant, mapId, oid, new Point(pos), i, it.getItem().getItemId(), it.getBundles(),
                    Math.max(1, it.getItem().getQuantity()), it.getPrice()));
        }
    }
}
