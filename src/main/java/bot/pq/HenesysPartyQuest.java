package bot.pq;

import bot.Action;
import bot.WorldState;

import java.awt.Point;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/** Moon Bunny PQ definition and its seed-planting/defence session. */
final class HenesysPartyQuest implements PartyQuestDefinition {
    static final int MAIN_MAP = 910010000;
    static final int CLEAR_MAP = 910010100;
    static final int TORY = 1012112;
    static final int MOON_BUNNY = 9300061;
    static final int RICE_CAKE = 4001101;

    private static final int[] PLANT_REACTORS = {
            9102002, 9102003, 9102004, 9102005, 9102006, 9102007
    };
    private static final int[] SEEDS = {
            4001095, 4001096, 4001097, 4001098, 4001099, 4001100
    };
    private static final int[] FLOWER_REACTORS = {
            9108000, 9108001, 9108002, 9108003, 9108004, 9108005
    };
    private static final PartyQuestTeamPolicy TEAM = new PartyQuestTeamPolicy(3, 6, 2, 3);

    @Override
    public String id() {
        return "henesys-moon-bunny";
    }

    @Override
    public String displayName() {
        return "Henesys Moon Bunny Party Quest";
    }

    @Override
    public boolean ownsMap(int mapId) {
        return mapId == MAIN_MAP || mapId == CLEAR_MAP;
    }

    @Override
    public PartyQuestTeamPolicy teamPolicy() {
        return TEAM;
    }

    @Override
    public boolean requiresReadyTeam(int mapId) {
        return mapId != CLEAR_MAP;
    }

    @Override
    public PartyQuestSession createSession(PartyQuestContext context, PartyQuestCombat combat) {
        return new Session(context, combat, System::currentTimeMillis);
    }

    static final class Session implements PartyQuestSession {
        private static final long ACTION_COOLDOWN_MS = 800;
        private static final long FLOWER_ACTIVATION_TIMEOUT_MS = 8_000;
        private static final long NPC_RETRY_MS = 3_000;

        private enum FollowUp { HIT_REACTOR, PICK_UP, DROP_SEED, DROP_CAKES }

        private final PartyQuestContext context;
        private final PartyQuestCombat combat;
        private final LongSupplier clock;
        private final Set<Integer> planted = new HashSet<>();
        private final Set<Integer> attemptedDrops = new HashSet<>();

        private FollowUp followUp;
        private int pendingObjectId;
        private int pendingItemId;
        private int waitingFlower = -1;
        private long waitingFlowerSince;
        private long lastActionAt;
        private long lastNpcTalkAt;
        private int lastMapChangeCount = -1;
        private int currentMonsterOid = -1;
        private WorldState.NpcTalk lastHandledTalk;

        Session(PartyQuestContext context, PartyQuestCombat combat, LongSupplier clock) {
            this.context = context;
            this.combat = combat;
            this.clock = clock;
        }

        @Override
        public Action plan(WorldState world, Point selfPosition) {
            if (lastMapChangeCount != world.getMapChangeCount()) {
                lastMapChangeCount = world.getMapChangeCount();
                followUp = null;
                attemptedDrops.clear();
                currentMonsterOid = -1;
                lastHandledTalk = null;
            }
            if (world.getSelfMapId() == CLEAR_MAP) {
                return claimReward(world);
            }
            if (world.getSelfMapId() != MAIN_MAP) {
                return new Action.Idle();
            }

            long now = clock.getAsLong();
            if (followUp != null) {
                if (now - lastActionAt < ACTION_COOLDOWN_MS) {
                    return new Action.Idle();
                }
                FollowUp action = followUp;
                followUp = null;
                lastActionAt = now;
                return switch (action) {
                    case HIT_REACTOR -> new Action.HitReactor(pendingObjectId);
                    case PICK_UP -> new Action.PickupItem(pendingObjectId);
                    case DROP_SEED -> {
                        waitingFlower = indexOf(SEEDS, pendingItemId);
                        waitingFlowerSince = now;
                        yield new Action.DropItem(pendingItemId, 1);
                    }
                    case DROP_CAKES -> new Action.DropItem(RICE_CAKE, pendingItemId);
                };
            }

            Optional<WorldState.MonsterSighting> bunny = world.getMonsters().stream()
                    .filter(m -> m.monsterId() == MOON_BUNNY).findFirst();
            if (bunny.isPresent()) {
                return defendBunny(world, selfPosition, bunny.get(), now);
            }
            return plantSeeds(world, now);
        }

        private Action plantSeeds(WorldState world, long now) {
            for (int i = context.ordinal(); i < SEEDS.length; i += context.managedCompanionCount()) {
                int color = i;
                Optional<WorldState.ReactorSighting> flower = reactor(world, FLOWER_REACTORS[color]);
                if (flower.isPresent() && flower.get().state() > 0) {
                    planted.add(color);
                    if (waitingFlower == color) {
                        waitingFlower = -1;
                    }
                }
                if (planted.contains(color)) {
                    continue;
                }
                if (waitingFlower == color && now - waitingFlowerSince < FLOWER_ACTIVATION_TIMEOUT_MS) {
                    return new Action.Idle();
                }
                if (waitingFlower == color) {
                    waitingFlower = -1; // activation was missed or failed; retry from observed state
                }
                if (world.getEtcQuantity(SEEDS[color]) > 0 && flower.isPresent()) {
                    pendingItemId = SEEDS[color];
                    followUp = FollowUp.DROP_SEED;
                    lastActionAt = now;
                    return new Action.MoveTo(flower.get().position());
                }
                Optional<WorldState.ItemDrop> seed = world.getItemDrops().stream()
                        .filter(d -> d.itemId() == SEEDS[color] && !d.playerDrop())
                        .filter(d -> !attemptedDrops.contains(d.objectId()))
                        .findFirst();
                if (seed.isPresent()) {
                    attemptedDrops.add(seed.get().objectId());
                    pendingObjectId = seed.get().objectId();
                    followUp = FollowUp.PICK_UP;
                    lastActionAt = now;
                    return new Action.MoveTo(seed.get().position());
                }
                Optional<WorldState.ReactorSighting> plant = reactor(world, PLANT_REACTORS[color]);
                if (plant.isPresent() && now - lastActionAt >= ACTION_COOLDOWN_MS) {
                    pendingObjectId = plant.get().objectId();
                    followUp = FollowUp.HIT_REACTOR;
                    lastActionAt = now;
                    return new Action.MoveTo(plant.get().position());
                }
                return new Action.Idle();
            }
            return new Action.Idle(); // another companion may still be planting its assigned colors
        }

        private Action defendBunny(WorldState world, Point selfPosition,
                                   WorldState.MonsterSighting bunny, long now) {
            int cakes = world.getEtcQuantity(RICE_CAKE);
            if (cakes > 0) {
                Point ownerPosition = world.getPlayerPosition(context.ownerId());
                if (ownerPosition == null || now - lastActionAt < ACTION_COOLDOWN_MS) {
                    return new Action.Idle();
                }
                pendingItemId = cakes; // quantity for DROP_CAKES
                followUp = FollowUp.DROP_CAKES;
                lastActionAt = now;
                return new Action.MoveTo(ownerPosition);
            }

            Optional<WorldState.ItemDrop> cake = world.getItemDrops().stream()
                    .filter(d -> d.itemId() == RICE_CAKE && !d.playerDrop())
                    .filter(d -> d.dropperObjectId() == bunny.objectId())
                    .filter(d -> !attemptedDrops.contains(d.objectId()))
                    .min(Comparator.comparingInt(WorldState.ItemDrop::objectId));
            if (cake.isPresent() && now - lastActionAt >= ACTION_COOLDOWN_MS) {
                attemptedDrops.add(cake.get().objectId());
                pendingObjectId = cake.get().objectId();
                followUp = FollowUp.PICK_UP;
                lastActionAt = now;
                return new Action.MoveTo(cake.get().position());
            }

            List<WorldState.MonsterSighting> hostiles = world.getMonsters().stream()
                    .filter(m -> m.monsterId() != MOON_BUNNY).toList();
            Optional<WorldState.MonsterSighting> current = hostiles.stream()
                    .filter(m -> m.objectId() == currentMonsterOid).findFirst();
            if (current.isEmpty() && !hostiles.isEmpty()) {
                current = Optional.of(hostiles.get(context.ordinal() % hostiles.size()));
            }
            currentMonsterOid = current.map(WorldState.MonsterSighting::objectId).orElse(-1);
            return current.map(m -> combat.attack(world, selfPosition, m.objectId()))
                    .orElseGet(Action.Idle::new);
        }

        private Action claimReward(WorldState world) {
            long now = clock.getAsLong();
            WorldState.NpcTalk talk = world.getLastNpcTalk();
            if (talk != null && talk.npcId() == TORY && !talk.equals(lastHandledTalk)) {
                lastHandledTalk = talk;
                lastNpcTalkAt = now;
                return new Action.RespondToNpc(talk.msgType(), true, null);
            }
            if (now - lastNpcTalkAt < NPC_RETRY_MS) {
                return new Action.Idle();
            }
            Optional<WorldState.NpcSighting> tory = world.getNpcs().stream()
                    .filter(n -> n.npcId() == TORY).findFirst();
            if (tory.isEmpty()) {
                return new Action.Idle();
            }
            lastNpcTalkAt = now;
            return new Action.TalkToNpc(tory.get().objectId());
        }

        @Override
        public boolean midStep() {
            return followUp != null;
        }

        private static Optional<WorldState.ReactorSighting> reactor(WorldState world, int reactorId) {
            return world.getReactors().stream().filter(r -> r.reactorId() == reactorId).findFirst();
        }

        private static int indexOf(int[] values, int value) {
            for (int i = 0; i < values.length; i++) {
                if (values[i] == value) {
                    return i;
                }
            }
            return -1;
        }
    }
}
