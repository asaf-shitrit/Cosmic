package bot;

import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The portals of a map, read from the same WZ map data the server loads. There is no packet that
 * describes a map's portals - a real client knows them because it ships its own copy of the WZ files -
 * so reading them here is this bot's equivalent of that local copy, not a peek at live server state.
 * Nothing here consults a loaded {@code MapleMap}.
 *
 * <p>This is the same source {@code MapFactory#loadMapFromWz} builds portals from: a map's
 * {@code portal} children, each with {@code pn} (name, what {@code CHANGE_MAP} sends), {@code tm}
 * (target map, {@code 999999999} for none), {@code x}/{@code y}, and a node name that is the portal
 * id {@code SET_FIELD} reports as the arrival spawn point. Maps with {@code info/link} borrow another
 * map's data, handled the same way as {@code MapFactory}.
 */
public final class MapPortals {
    public record PortalInfo(int id, String name, int targetMapId, Point position, boolean scripted) {}

    private static final int NO_MAP = 999_999_999;

    /** Own provider instance: {@code XMLWZFile#getData} is synchronized, so sharing the server's would contend with map loads. */
    private static final DataProvider MAP_SOURCE = DataProviderFactory.getDataProvider(WZFiles.MAP);

    /** Portal lists never change at runtime; a follower only ever touches a handful of maps. */
    private static final Map<Integer, List<PortalInfo>> CACHE = new ConcurrentHashMap<>();

    private MapPortals() {
    }

    public static List<PortalInfo> of(int mapId) {
        if (mapId < 0) {
            return List.of();      // WorldState's "not decoded yet"
        }
        return CACHE.computeIfAbsent(mapId, MapPortals::load);
    }

    /**
     * A portal on {@code fromMapId} that leads straight to {@code toMapId}. Plain portals are
     * preferred over scripted ones, because a portal script may send the player somewhere other than
     * its {@code tm} (or nowhere) - {@code GenericPortal#enterPortal} runs the script instead of the
     * target when one is set.
     */
    public static Optional<PortalInfo> leadingTo(int fromMapId, int toMapId) {
        PortalInfo scripted = null;
        for (PortalInfo portal : of(fromMapId)) {
            if (portal.targetMapId() != toMapId || toMapId == fromMapId) {
                continue;
            }
            if (!portal.scripted()) {
                return Optional.of(portal);
            }
            if (scripted == null) {
                scripted = portal;
            }
        }
        return Optional.ofNullable(scripted);
    }

    public static Optional<PortalInfo> byId(int mapId, int portalId) {
        return of(mapId).stream().filter(p -> p.id() == portalId).findFirst();
    }

    private static List<PortalInfo> load(int mapId) {
        Data mapData = MAP_SOURCE.getData(imgPath(mapId));
        if (mapData == null) {
            return List.of();
        }
        String link = DataTool.getString(mapData.getChildByPath("info/link"), "");
        if (!link.isEmpty()) {
            mapData = MAP_SOURCE.getData(imgPath(Integer.parseInt(link)));
            if (mapData == null) {
                return List.of();
            }
        }
        Data portals = mapData.getChildByPath("portal");
        if (portals == null) {
            return List.of();
        }
        List<PortalInfo> result = new java.util.ArrayList<>();
        for (Data portal : portals) {
            int id;
            try {
                id = Integer.parseInt(portal.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            String script = DataTool.getString("script", portal, null);
            result.add(new PortalInfo(
                    id,
                    DataTool.getString(portal.getChildByPath("pn")),
                    DataTool.getInt(portal.getChildByPath("tm"), NO_MAP),
                    new Point(DataTool.getInt(portal.getChildByPath("x")), DataTool.getInt(portal.getChildByPath("y"))),
                    script != null && !script.isEmpty()));
        }
        return List.copyOf(result);
    }

    /** Mirrors {@code MapFactory#getMapName}. */
    private static String imgPath(int mapId) {
        return String.format("Map/Map%d/%09d.img", mapId / 100_000_000, mapId);
    }
}
