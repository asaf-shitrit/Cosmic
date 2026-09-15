package bot.party;

import bot.Action;
import bot.MapPortals;
import bot.Planner;
import bot.WorldState;

import java.awt.Point;
import java.util.Optional;

/**
 * Keeps one bot near one owner: joins the owner's party when invited, walks to a loose spot beside
 * the owner on the same map, and follows the owner through portals to adjacent maps.
 *
 * <p>Everything here comes off the wire, the way a real client would learn it:
 * <ul>
 *   <li>the owner's <b>position</b> from {@code SPAWN_PLAYER} and every {@code MOVE_PLAYER} broadcast
 *       ({@link WorldState#getPlayerPosition});</li>
 *   <li>the owner's <b>map</b> from the party roster, which the server rebroadcasts to every member on
 *       each member's map change ({@link WorldState#getPartyMember});</li>
 *   <li>which <b>portal</b> leads there from the WZ map data a client ships with ({@link MapPortals}).</li>
 * </ul>
 * If the owner's new map is not one portal away (a scroll, a taxi, an NPC warp) this planner simply
 * waits; the in-server supervisor notices the bot has been left behind and warps it (see
 * {@link BotPartySupervisor}). Standalone, the bot just stays put.
 *
 * <p>Pacing is this class's job, not the driver loop's: every packet-producing branch sits behind a
 * cooldown, because an owner's movement broadcasts make the driver replan many times a second. The
 * transport rate limit in {@code MapleConnection} remains the backstop.
 */
public class FollowPlanner implements Planner {
    /** Minimum gap between follow moves. With {@link #STEP_PX} this is ~370px/s, faster than a walking owner. */
    private static final long MOVE_INTERVAL_MS = 300;
    /** Largest single follow step, so an observer sees the bot travel toward the owner rather than blink. */
    private static final int STEP_PX = 110;
    /** Beyond this the bot places itself directly - e.g. the owner just arrived at the far side of a map. */
    private static final int SNAP_DISTANCE_PX = 900;
    /** Horizontal slack around the bot's slot before it bothers moving: the "loose" in loose following. */
    private static final int DEAD_ZONE_PX = 45;
    private static final int VERTICAL_SLACK_PX = 60;
    /** Spacing between follow slots, alternating left and right of the owner. */
    private static final int SLOT_SPACING_PX = 70;

    /** Gap after arriving on a map before trying a portal: PLAYER_MAP_TRANSFER has to land first. */
    private static final long ARRIVAL_SETTLE_MS = 500;
    /**
     * Gap between the approach move and the portal use, and between whole attempts. Two steps for the
     * same reason as {@code KpqPlanner#idleOrTryPortal}: {@code ChangeMapHandler} silently ignores a
     * portal more than 632px ({@code distanceSq > 400000}) from the character's server-side position,
     * so the bot stands on the portal first.
     */
    private static final long PORTAL_STEP_MS = 400;
    private static final long PORTAL_RETRY_MS = 1500;
    /**
     * Portal uses toward one destination before giving up on it (a quest-gated or scripted portal can
     * refuse forever). After that the bot stops sending CHANGE_MAP for that destination, which is what
     * lets the supervisor's catch-up warp run without racing one - see {@code BotPartySupervisor#catchUp}.
     */
    private static final int MAX_PORTAL_ATTEMPTS = 4;
    /**
     * No portal travel until this long after first entering the world, unless the bot has already been
     * moved again by then. The supervisor warps a freshly logged-in bot onto its owner within a second
     * or two; a bot that rejoins with party membership still attached would otherwise know its owner's
     * map immediately and could be walking through a portal at the moment that warp lands.
     */
    private static final long LOGIN_TRAVEL_DELAY_MS = 10_000;

    private final int ownerCharId;
    private final String ownerName;
    private final int slotOffsetX;

    private int lastMapChangeCount = -1;
    private long mapArrivedAt;
    /** Where this bot believes it stands when it hasn't moved since arriving: its arrival portal. */
    private Point arrivalPosition;
    private long lastMoveAt;
    private long lastPortalActionAt;
    private boolean portalApproached;
    private long firstEnteredAt;
    /** (bot map, destination map) the attempt count below applies to. */
    private long portalAttemptKey;
    private int portalAttempts;

    /**
     * @param slot 0-based index among the owner's bots, deciding which side of the owner and how far
     *             out this bot settles, so several bots don't stack on one pixel
     */
    public FollowPlanner(int ownerCharId, String ownerName, int slot) {
        this.ownerCharId = ownerCharId;
        this.ownerName = ownerName;
        int side = slot % 2 == 0 ? -1 : 1;
        this.slotOffsetX = side * SLOT_SPACING_PX * (slot / 2 + 1);
    }

    @Override
    public Action plan(WorldState world, Point selfPosition) {
        long now = System.currentTimeMillis();

        if (world.getMapChangeCount() != lastMapChangeCount) {
            lastMapChangeCount = world.getMapChangeCount();
            mapArrivedAt = now;
            if (firstEnteredAt == 0) {
                firstEnteredAt = now;
            }
            portalApproached = false;
            arrivalPosition = MapPortals.byId(world.getSelfMapId(), world.getSelfSpawnPortalId())
                    .map(MapPortals.PortalInfo::position).orElse(null);
        }

        WorldState.PartyInvite invite = world.getPendingPartyInvite();
        if (invite != null) {
            world.clearPendingPartyInvite();
            // Only the owner's party. Anyone on the channel can invite a bot by name.
            if (invite.fromName().equalsIgnoreCase(ownerName)) {
                return new Action.AcceptPartyInvite(invite.partyId());
            }
        }

        Point current = selfPosition != null ? selfPosition : arrivalPosition;
        Point ownerPos = world.getPlayerPosition(ownerCharId);
        if (ownerPos != null) {
            portalAttemptKey = 0;       // reunited: a later trip to the same map gets a fresh attempt budget
            return followOnMap(now, current, ownerPos);
        }

        WorldState.PartyMember owner = world.getPartyMember(ownerCharId);
        int selfMap = world.getSelfMapId();
        if (owner != null && owner.channel() >= 0 && owner.mapId() > 0 && selfMap > 0 && owner.mapId() != selfMap) {
            boolean justLoggedIn = lastMapChangeCount <= 1 && now - firstEnteredAt < LOGIN_TRAVEL_DELAY_MS;
            return justLoggedIn ? new Action.Idle() : travelToward(now, selfMap, owner.mapId());
        }
        return new Action.Idle();
    }

    private Action followOnMap(long now, Point current, Point ownerPos) {
        if (now - lastMoveAt < MOVE_INTERVAL_MS) {
            return new Action.Idle();
        }
        Point slot = new Point(ownerPos.x + slotOffsetX, ownerPos.y);
        if (current == null) {
            lastMoveAt = now;
            return new Action.MoveTo(slot);
        }
        int dx = slot.x - current.x;
        int dy = slot.y - current.y;
        // A real client reports mid-jump positions too, so the owner's y bobs; don't chase the bob.
        if (Math.abs(dx) <= DEAD_ZONE_PX && Math.abs(dy) <= VERTICAL_SLACK_PX) {
            return new Action.Idle();
        }
        lastMoveAt = now;
        if (Math.hypot(dx, dy) > SNAP_DISTANCE_PX) {
            return new Action.MoveTo(slot);
        }
        // Take the owner's height at once and walk the horizontal gap: a bot stepping diagonally
        // through the air between platforms looks far worse to onlookers than one that hops up.
        int stepX = Math.abs(dx) <= DEAD_ZONE_PX ? 0 : Integer.signum(dx) * Math.min(Math.abs(dx), STEP_PX);
        return new Action.MoveTo(new Point(current.x + stepX, slot.y));
    }

    private Action travelToward(long now, int selfMap, int ownerMap) {
        if (now - mapArrivedAt < ARRIVAL_SETTLE_MS) {
            return new Action.Idle();
        }
        Optional<MapPortals.PortalInfo> portal = MapPortals.leadingTo(selfMap, ownerMap);
        if (portal.isEmpty()) {
            return new Action.Idle();
        }
        long key = ((long) selfMap << 32) | (ownerMap & 0xFFFFFFFFL);
        if (key != portalAttemptKey) {
            portalAttemptKey = key;
            portalAttempts = 0;
        }
        if (portalAttempts >= MAX_PORTAL_ATTEMPTS && !portalApproached) {
            return new Action.Idle();       // given up; the supervisor's catch-up warp takes over
        }
        if (!portalApproached) {
            if (now - lastPortalActionAt < PORTAL_RETRY_MS) {
                return new Action.Idle();
            }
            lastPortalActionAt = now;
            portalApproached = true;
            return new Action.MoveTo(portal.get().position());
        }
        if (now - lastPortalActionAt < PORTAL_STEP_MS) {
            return new Action.Idle();
        }
        lastPortalActionAt = now;
        portalApproached = false;   // re-approach on a retry, in case something moved us off the portal
        portalAttempts++;
        return new Action.UsePortal(portal.get().name());
    }
}
