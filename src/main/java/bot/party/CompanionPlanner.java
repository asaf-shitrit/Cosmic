package bot.party;

import bot.Action;
import bot.Planner;
import bot.WorldState;
import bot.combat.CombatController;
import bot.kpq.KpqPlanner;
import client.Character;
import scripting.event.EventInstanceManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.util.function.Supplier;

/**
 * Chooses between following, field combat and the party quest the owner actually entered.
 * Preparation and membership are checked before combat so a freshly logged-in companion cannot
 * fight on its old map while the supervisor is still equipping and placing it.
 */
final class CompanionPlanner implements Planner {
    private static final Logger log = LoggerFactory.getLogger(CompanionPlanner.class);

    private final SummonedBot bot;
    private final BotPartySupervisor supervisor;
    private final Supplier<Character> selfSupplier;
    private final Supplier<Character> ownerSupplier;
    private final FollowPlanner follow;
    private final CombatController combat;
    private KpqPlanner kpq;
    private EventInstanceManager lastInstance;

    CompanionPlanner(SummonedBot bot, BotPartySupervisor supervisor,
                     Supplier<Character> selfSupplier, Supplier<Character> ownerSupplier) {
        this.bot = bot;
        this.supervisor = supervisor;
        this.selfSupplier = selfSupplier;
        this.ownerSupplier = ownerSupplier;
        follow = new FollowPlanner(bot.ownerId, bot.ownerName, bot.slot);
        combat = new CombatController(selfSupplier, ownerSupplier);
    }

    @Override
    public Action plan(WorldState world, Point position) {
        if (bot.stopRequested || !bot.prepared || !bot.placed || world.getMapChangeCount() == 0) {
            return new Action.Idle();
        }
        // FollowPlanner accepts only the owner's invite. The PQ planner must not consume arbitrary
        // invites while a summon is still joining, or it could attach itself to another player's run.
        if (world.getPendingPartyInvite() != null) {
            return follow.plan(world, position);
        }
        Character self = selfSupplier.get();
        Character owner = ownerSupplier.get();
        if (self == null || owner == null || self.isChangingMaps() || !bot.joinedParty) {
            return new Action.Idle();
        }
        if (!self.isAlive()) {
            bot.activity = "defeated";
            return new Action.Idle();
        }

        if (KpqPlanner.isStageMap(world.getSelfMapId())) {
            String problem = supervisor.kpqTeamProblem(bot, self, owner);
            if (problem != null) {
                String activity = "waiting for the Kerning party: " + problem;
                if (!activity.equals(bot.activity)) {
                    log.info("Summoned bot {} is {}", bot.name, activity);
                }
                bot.activity = activity;
                return new Action.Idle();
            }
            EventInstanceManager instance = self.getEventInstance();
            if (kpq == null || lastInstance != instance) {
                kpq = KpqPlanner.summonedMember(bot.slot, bot.ownerId, bot.ownerName, combat::attackTarget);
                lastInstance = instance;
            }
            bot.activity = "helping with Kerning Party Quest";
            if (kpq.midStep()) {
                // Seen live: a spearman stepped back to recast Hyper Body between its step to a
                // coupon and the pickup, and ItemPickupHandler rejected the pickup as too far away.
                return kpq.plan(world, position);
            }
            Action recover = combat.recover();
            if (!(recover instanceof Action.Idle)) {
                return recover;
            }
            Action support = combat.support(world, position, false);
            return support instanceof Action.Idle ? kpq.plan(world, position) : support;
        }

        kpq = null;
        lastInstance = null;
        if (self.getMap() != owner.getMap()) {
            bot.activity = "catching up";
            return follow.plan(world, position);
        }
        Action recover = combat.recover();
        if (!(recover instanceof Action.Idle)) {
            return recover;
        }
        Action support = combat.support(world, position, true);
        if (!(support instanceof Action.Idle)) {
            bot.activity = "supporting the party";
            return support;
        }
        var assist = combat.assist(world, position);
        if (assist.isPresent()) {
            bot.activity = "fighting nearby monsters";
            return assist.get();
        }
        bot.activity = "following";
        return follow.plan(world, position);
    }
}
