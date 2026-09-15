package bot.party;

import bot.Action;
import bot.BotSession;
import bot.ChannelSession;
import bot.MapPortals;
import bot.MapleConnection;
import bot.Planner;
import bot.WorldState;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Optional;

/**
 * Test harness, not part of the feature: a scripted "player" that owns summoned bots. It plays the
 * human's side of every verification step - talking to Shumi, walking, taking portals, logging out -
 * and logs the party roster and its party members' positions as the server reports them, so the
 * bots are observed from another client rather than trusted.
 *
 * <p>Steps run in order, each with a timeout; a step that times out is logged as FAIL and skipped:
 * <ul>
 *   <li>{@code shumi:summon:N}, {@code shumi:list}, {@code shumi:dismiss} - talk to Shumi and pick a menu entry</li>
 *   <li>{@code invite:NAME} - invite a character by name (standalone tests without the supervisor)</li>
 *   <li>{@code waitparty:N} - wait until the party roster has N members</li>
 *   <li>{@code walk:X:Y} - walk there in visible steps</li>
 *   <li>{@code portal:NAME} - walk onto a portal of the current map and take it</li>
 *   <li>{@code cc:CHANNEL} - request a channel change (1-based); the harness doesn't follow it to the new channel</li>
 *   <li>{@code wait:SECONDS}, {@code exit}</li>
 * </ul>
 * <pre>java -cp ... bot.party.OwnerHarness maplestory 8484 acct pass budgetSeconds shumi:summon:2,waitparty:3,walk:0:426,exit</pre>
 */
public class OwnerHarness implements Planner {
    private static final int SHUMI = 1052102;
    private static final long STEP_TIMEOUT_MS = 40_000;
    private static final long WALK_INTERVAL_MS = 300;
    private static final int WALK_STEP_PX = 90;
    private static final long REPORT_INTERVAL_MS = 2000;

    private final Deque<String> steps;
    private String step;
    private long stepStartedAt;
    private int phase;
    private long lastActionAt;
    private WorldState.NpcTalk handledTalk;
    private int mapChangesAtStepStart;
    private long lastReportAt;
    private boolean exitRequested;

    OwnerHarness(String steps) {
        this.steps = new ArrayDeque<>(Arrays.asList(steps.split(",")));
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println("usage: OwnerHarness <host> <port> <user> <pass> <budgetSeconds> <step,step,...>");
            System.exit(1);
        }
        ChannelSession session = BotSession.loginAndEnterChannel(args[0], Integer.parseInt(args[1]), args[2], args[3]);
        OwnerHarness harness = new OwnerHarness(args[5]);
        WorldState world = new WorldState(session.charId());
        try (MapleConnection conn = session.connection()) {
            BotSession.runGameLoop(conn, session.charId(), harness, Long.parseLong(args[4]) * 1000,
                    () -> harness.exitRequested, world);
        }
        System.out.println(java.time.LocalTime.now() + " [harness] disconnected (steps left: " + harness.steps + ")");
    }

    @Override
    public Action plan(WorldState world, Point self) {
        long now = System.currentTimeMillis();
        report(world, self, now);
        if (step == null) {
            if (world.getMapChangeCount() == 0) {
                return new Action.Idle();      // not in the world yet
            }
            step = steps.pollFirst();
            if (step == null) {
                return new Action.Idle();
            }
            System.out.println(java.time.LocalTime.now() + " [harness] step " + step + " (map " + world.getSelfMapId() + ", at " + self + ")");
            stepStartedAt = now;
            phase = 0;
            handledTalk = world.getLastNpcTalk();
            mapChangesAtStepStart = world.getMapChangeCount();
        }
        if (now - stepStartedAt > STEP_TIMEOUT_MS) {
            System.out.println(java.time.LocalTime.now() + " [harness] FAIL step " + step + " timed out in phase " + phase);
            step = null;
            return new Action.Idle();
        }
        String[] parts = step.split(":");
        Action action = switch (parts[0]) {
            case "shumi" -> shumi(world, now, parts[1], parts.length > 2 ? Integer.parseInt(parts[2]) : 0);
            case "invite" -> done(new Action.InviteToParty(parts[1]));
            case "waitparty" -> world.getPartyMemberIds().size() >= Integer.parseInt(parts[1]) ? done(new Action.Idle()) : new Action.Idle();
            case "walk" -> walk(self, now, new Point(Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
            case "portal" -> portal(world, self, now, parts[1]);
            case "cc" -> done(new Action.ChangeChannel(Integer.parseInt(parts[1])));
            case "wait" -> now - stepStartedAt >= Long.parseLong(parts[1]) * 1000 ? done(new Action.Idle()) : new Action.Idle();
            case "exit" -> {
                exitRequested = true;
                yield done(new Action.Idle());
            }
            default -> {
                System.out.println(java.time.LocalTime.now() + " [harness] FAIL unknown step " + step);
                yield done(new Action.Idle());
            }
        };
        return action;
    }

    private Action done(Action last) {
        System.out.println(java.time.LocalTime.now() + " [harness] step " + step + " done");
        step = null;
        return last;
    }

    private Action shumi(WorldState world, long now, String what, int count) {
        WorldState.NpcTalk talk = world.getLastNpcTalk();
        boolean fresh = talk != null && talk != handledTalk && talk.npcId() == SHUMI;
        switch (phase) {
            case 0 -> {
                Optional<WorldState.NpcSighting> npc = world.getNpcs().stream().filter(n -> n.npcId() == SHUMI).findFirst();
                if (npc.isEmpty() || now - lastActionAt < 1000) {
                    return new Action.Idle();
                }
                lastActionAt = now;
                phase = 1;
                return new Action.TalkToNpc(npc.get().objectId());
            }
            case 1 -> {
                if (!fresh) {
                    return new Action.Idle();
                }
                handledTalk = talk;
                if (talk.msgType() != 4) {
                    System.out.println(java.time.LocalTime.now() + " [harness] FAIL expected Shumi's menu, got type " + talk.msgType());
                    return done(new Action.Idle());
                }
                int selection = switch (what) {
                    case "summon" -> 0;
                    case "list" -> 1;
                    default -> 2;
                };
                phase = 2;
                return new Action.RespondToNpc(4, true, selection);
            }
            default -> {
                if (!fresh) {
                    return new Action.Idle();
                }
                handledTalk = talk;
                System.out.println(java.time.LocalTime.now() + " [harness] Shumi (type " + talk.msgType() + "): " + talk.text());
                if (talk.msgType() == 3) {                  // how many?
                    return new Action.RespondToNpc(3, true, count);
                }
                return done(new Action.Idle());             // sendOk + dispose: the conversation is over
            }
        }
    }

    private Action walk(Point self, long now, Point target) {
        if (now - lastActionAt < WALK_INTERVAL_MS) {
            return new Action.Idle();
        }
        if (self != null && self.distance(target) < 5) {
            return done(new Action.Idle());
        }
        lastActionAt = now;
        if (self == null) {
            return new Action.MoveTo(target);
        }
        int dx = target.x - self.x;
        int stepX = Integer.signum(dx) * Math.min(Math.abs(dx), WALK_STEP_PX);
        return new Action.MoveTo(new Point(self.x + stepX, target.y));
    }

    private Action portal(WorldState world, Point self, long now, String name) {
        if (world.getMapChangeCount() != mapChangesAtStepStart) {
            return done(new Action.Idle());
        }
        if (now - lastActionAt < 1000) {
            return new Action.Idle();
        }
        Optional<MapPortals.PortalInfo> portal = MapPortals.of(world.getSelfMapId()).stream()
                .filter(p -> p.name().equals(name)).findFirst();
        if (portal.isEmpty()) {
            System.out.println(java.time.LocalTime.now() + " [harness] FAIL no portal " + name + " on map " + world.getSelfMapId());
            return done(new Action.Idle());
        }
        lastActionAt = now;
        if (self == null || self.distance(portal.get().position()) > 5) {
            return new Action.MoveTo(portal.get().position());
        }
        return new Action.UsePortal(name);
    }

    /** What this client sees of its party: the server's roster, and each member's broadcast position. */
    private void report(WorldState world, Point self, long now) {
        if (now - lastReportAt < REPORT_INTERVAL_MS || world.getPartyId() == -1) {
            return;
        }
        lastReportAt = now;
        StringBuilder sb = new StringBuilder("[party] me at " + self + " map " + world.getSelfMapId() + ";");
        for (int id : world.getPartyMemberIds()) {
            WorldState.PartyMember m = world.getPartyMember(id);
            if (m == null) {
                continue;
            }
            Point pos = world.getPlayerPosition(id);
            sb.append(' ').append(m.name()).append("#").append(id)
                    .append(m.channel() < 0 ? " offline" : " map " + m.mapId())
                    .append(pos == null ? "" : " at " + pos.x + "," + pos.y)
                    .append(pos == null || self == null ? "" : " dist " + (int) pos.distance(self))
                    .append(';');
        }
        System.out.println(java.time.LocalTime.now() + " " + sb);
    }
}
