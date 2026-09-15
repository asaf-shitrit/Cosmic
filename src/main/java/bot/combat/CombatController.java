package bot.combat;

import bot.Action;
import bot.WorldState;
import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import constants.skills.Archer;
import constants.skills.Cleric;
import constants.skills.Magician;
import constants.skills.Spearman;
import constants.skills.Warrior;
import server.StatEffect;

import java.awt.Point;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Small, deterministic combat policy for a summoned protocol bot. The server-side Character
 * suppliers are intentional: stats, learned skills and map state remain live server truth while
 * actions still go through the normal bot packets, so the server validates them exactly as it would
 * a player's (a skill the character hasn't learned is dropped by {@code SpecialMoveHandler}).
 */
public final class CombatController {
    public static final int OWNER_LEASH = 700;
    public static final int MELEE_RANGE = AttackReach.MELEE;
    /**
     * Heal, Bless and Hyper Body all reach {@code lt=(-250,-150) rb=(250,150)} around the caster at the
     * levels a companion has (Skill.wz 230.img / 130.img); {@code StatEffect#applyBuff} tests the
     * party member's server position against that box. Kept inside it with a margin.
     */
    private static final int SUPPORT_REACH_X = 200;
    private static final int SUPPORT_REACH_Y = 100;
    public static final long ATTACK_COOLDOWN_MS = 700;
    public static final long SUPPORT_COOLDOWN_MS = 1800;
    private static final long POTION_COOLDOWN_MS = 2000;

    private final Supplier<Character> selfSupplier;
    private final Supplier<Character> ownerSupplier;
    private final LongSupplier clock;
    private final RandomGenerator rng;
    private long lastAttack;
    private long lastSupport;
    private long lastPotion;
    /** The monster assist() is working on, kept until it dies or strays past the leash. */
    private int currentTarget = -1;

    public CombatController(Supplier<Character> self, Supplier<Character> owner) {
        this(self, owner, System::currentTimeMillis, RandomGenerator.getDefault());
    }

    CombatController(Supplier<Character> self, Supplier<Character> owner, LongSupplier clock, RandomGenerator rng) {
        this.selfSupplier = self;
        this.ownerSupplier = owner;
        this.clock = clock;
        this.rng = rng;
    }

    /**
     * Assist the owner; empty means no valid hostile and permits a follow planner to act. Sticks to
     * one target until it is gone: with real damage a monster takes several hits, and switching to
     * whichever is nearest each tick spreads damage over many monsters and kills none.
     */
    public Optional<Action> assist(WorldState world, Point position) {
        Character owner = ownerSupplier.get();
        Character self = selfSupplier.get();
        if (!canAct(self, owner, position) || !fights(self)) {
            return Optional.empty();
        }
        boolean kpqCombat = kpqCombatMap(owner.getMapId());
        WorldState.MonsterSighting target = world.getMonsters().stream()
                .filter(m -> m.objectId() == currentTarget && withinLeash(m, owner, kpqCombat))
                .findFirst()
                .orElseGet(() -> nearest(world, owner, kpqCombat));
        if (target == null) {
            currentTarget = -1;
            return Optional.empty();
        }
        currentTarget = target.objectId();
        return Optional.of(attackTarget(world, position, target.objectId()));
    }

    /**
     * Attack a specific observed monster if it remains inside the owner leash, from this companion's
     * reach: a warrior steps onto it, a magician or bowman stands off level with it (see
     * {@link AttackReach}). A Cleric doesn't attack: it heals and buffs instead.
     */
    public Action attackTarget(WorldState world, Point position, int monsterObjectId) {
        Character self = selfSupplier.get();
        Character owner = ownerSupplier.get();
        if (!canAct(self, owner, position) || !fights(self)) {
            return new Action.Idle();
        }
        boolean kpqCombat = kpqCombatMap(owner.getMapId());
        WorldState.MonsterSighting mob = world.getMonsters().stream()
                .filter(m -> m.objectId() == monsterObjectId)
                .findFirst().orElse(null);
        if (mob == null || !withinLeash(mob, owner, kpqCombat)) {
            return new Action.Idle();
        }
        int reach = reachFor(self);
        if (!AttackReach.inReach(position, mob.position(), reach)) {
            return new Action.MoveTo(AttackReach.approach(position, mob.position(), reach));
        }
        long now = clock.getAsLong();
        if (now - lastAttack < ATTACK_COOLDOWN_MS) {
            return new Action.Idle();
        }
        lastAttack = now;
        if (self.getJob().isA(Job.BOWMAN)) {
            return shoot(self, mob);
        }
        if (self.getJob().isA(Job.MAGICIAN)) {
            return cast(self, mob);
        }
        SkillChoice choice = meleeSkill(self);
        if (choice == null) {
            return new Action.AttackMonster(monsterObjectId, CombatMath.lineDamage(self, 100, mob.monsterId(), rng));
        }
        return new Action.SkillAttackMonster(monsterObjectId, choice.skillId(),
                CombatMath.lineDamage(self, choice.effect().getDamage(), mob.monsterId(), rng));
    }

    /** How close the live character's job attacks from; {@code KpqPlanner} steps to the same reach. */
    public int attackReach() {
        Character self = selfSupplier.get();
        return self == null ? AttackReach.MELEE : reachFor(self);
    }

    private static int reachFor(Character self) {
        if (self.getJob().isA(Job.BOWMAN)) {
            return AttackReach.BOW;
        }
        return self.getJob().isA(Job.MAGICIAN) ? AttackReach.MAGIC : AttackReach.MELEE;
    }

    /**
     * Double Shot when it can pay for it, else Arrow Blow, else a plain shot. With no arrow stack large
     * enough {@code RangedAttackHandler} would drop the attack without a word, so this doesn't send one.
     */
    private Action shoot(Character self, WorldState.MonsterSighting mob) {
        SkillChoice choice = firstCastable(self, Archer.DOUBLE_SHOT, Archer.ARROW_BLOW);
        StatEffect effect = choice == null ? null : choice.effect();
        if (!holdsArrows(self, effect == null ? 1 : effect.getBulletCount())) {
            return new Action.Idle();
        }
        return new Action.RangedAttackMonster(mob.objectId(), choice == null ? 0 : choice.skillId(),
                CombatMath.rangedLines(self, effect, mob.monsterId(), rng));
    }

    /**
     * Magic Claw when learned and affordable, else Energy Bolt. Out of MP a magician waits for
     * {@link #recover} rather than swinging its wand: a spell can't be sent as skill 0.
     */
    private Action cast(Character self, WorldState.MonsterSighting mob) {
        SkillChoice choice = firstCastable(self, Magician.MAGIC_CLAW, Magician.ENERGY_BOLT);
        if (choice == null) {
            return new Action.Idle();
        }
        return new Action.MagicAttackMonster(mob.objectId(), choice.skillId(),
                CombatMath.magicLines(self, choice.effect(), choice.level(), mob.monsterId(), rng));
    }

    /** The stack test {@code RangedAttackHandler} applies: one bow-arrow stack holding at least {@code needed}. */
    private static boolean holdsArrows(Character self, int needed) {
        for (Item item : self.getInventory(InventoryType.USE).list()) {
            if (ItemConstants.isArrowForBow(item.getItemId()) && item.getQuantity() >= needed) {
                return true;
            }
        }
        return false;
    }

    /**
     * Heal the owner when hurt, otherwise (if {@code maintainBuffs}) keep this companion's party buff
     * on the owner. Every skill here is one {@link CompanionLoadout} teaches the matching job: Cleric
     * Heal and Bless (2nd job, 230), Spearman Hyper Body (2nd job, 130).
     *
     * <p>Casting means walking back into range of the owner, and at the skill levels a level-30
     * companion has, Bless and Hyper Body last 10 seconds. In KPQ that pulled every companion off its
     * monster or drop every 10 seconds (seen live), so the KPQ caller passes {@code false}.
     */
    public Action support(WorldState world, Point position, boolean maintainBuffs) {
        Character self = selfSupplier.get();
        Character owner = ownerSupplier.get();
        if (!canAct(self, owner, position)) {
            return new Action.Idle();
        }
        long now = clock.getAsLong();
        if (now - lastSupport < SUPPORT_COOLDOWN_MS) {
            return new Action.Idle();
        }
        int skillId = 0;
        if (self.getJob().isA(Job.CLERIC)) {
            if (owner.getHp() < owner.getCurrentMaxHp() * 3 / 4) {
                skillId = Cleric.HEAL;
            } else if (maintainBuffs && !owner.hasBuffFromSourceid(Cleric.BLESS)) {
                skillId = Cleric.BLESS;
            }
        } else if (maintainBuffs && self.getJob().isA(Job.SPEARMAN) && !owner.hasBuffFromSourceid(Spearman.HYPER_BODY)) {
            skillId = Spearman.HYPER_BODY;
        }
        if (skillId == 0 || !canCast(self, skillId)) {
            return new Action.Idle();
        }
        Point selfAt = self.getPosition();
        Point ownerAt = owner.getPosition();
        if (Math.abs(selfAt.x - ownerAt.x) > SUPPORT_REACH_X || Math.abs(selfAt.y - ownerAt.y) > SUPPORT_REACH_Y) {
            return new Action.MoveTo(new Point(ownerAt));
        }
        lastSupport = now;
        return new Action.CastSkill(skillId, self.getSkillLevel(skillId));
    }

    /** Drinks a Blue Potion when MP runs low, so skills don't simply stop once the pool is spent. */
    public Action recover() {
        Character self = selfSupplier.get();
        if (self == null || !self.isAlive() || self.isChangingMaps()
                || self.getMp() >= self.getCurrentMaxMp() / 3) {
            return new Action.Idle();
        }
        long now = clock.getAsLong();
        if (now - lastPotion < POTION_COOLDOWN_MS) {
            return new Action.Idle();
        }
        Item potion = self.getInventory(InventoryType.USE).findById(ItemId.BLUE_POTION);
        if (potion == null) {
            return new Action.Idle();
        }
        lastPotion = now;
        return new Action.UseItem(potion.getItemId(), potion.getPosition());
    }

    private static boolean canAct(Character self, Character owner, Point position) {
        return self != null && owner != null && position != null && self.getMap() == owner.getMap()
                && self.isAlive() && owner.isAlive() && !self.isChangingMaps() && !owner.isChangingMaps()
                && self.getPartyId() >= 0 && self.getPartyId() == owner.getPartyId() && combatAllowed(owner);
    }

    /**
     * Farming KPQ's stage 1 and 5 needs the whole map, so the leash is lifted there. It only ever
     * spans one map: this never moves a companion off the owner's map.
     */
    private static boolean withinLeash(WorldState.MonsterSighting mob, Character owner, boolean kpqCombat) {
        return kpqCombat || mob.position().distanceSq(owner.getPosition()) <= (long) OWNER_LEASH * OWNER_LEASH;
    }

    private static WorldState.MonsterSighting nearest(WorldState world, Character owner, boolean kpqCombat) {
        Point ownerPosition = owner.getPosition();
        return world.getMonsters().stream().filter(m -> withinLeash(m, owner, kpqCombat))
                .min(Comparator.comparingDouble(m -> m.position().distanceSq(ownerPosition))).orElse(null);
    }

    private static boolean combatAllowed(Character owner) {
        if (owner.getMap() == null || owner.getMap().isTown()) {
            return false;
        }
        int map = owner.getMapId();
        return map < 103000801 || map > 103000803; // KPQ stages 2-4 are positional, not combat areas.
    }

    private static boolean kpqCombatMap(int map) {
        return map == 103000800 || map == 103000804 || map == 103000805;
    }

    private static boolean canCast(Character self, int id) {
        int level = self.getSkillLevel(id);
        if (level <= 0) {
            return false;
        }
        Skill skill = SkillFactory.getSkill(id);
        StatEffect effect = skill == null ? null : skill.getEffect(level);
        return effect != null && self.getMp() >= effect.getMpCon();
    }

    private static SkillChoice meleeSkill(Character self) {
        return self.getJob().isA(Job.WARRIOR) ? firstCastable(self, Warrior.POWER_STRIKE) : null;
    }

    /** The first of {@code ids} this character has learned and has the MP for, or null. */
    private static SkillChoice firstCastable(Character self, int... ids) {
        for (int id : ids) {
            if (canCast(self, id)) {
                int level = self.getSkillLevel(id);
                return new SkillChoice(id, level, SkillFactory.getSkill(id).getEffect(level));
            }
        }
        return null;
    }

    /** Everyone but a Cleric attacks; the Cleric's job in the party is Heal and Bless. */
    private static boolean fights(Character self) {
        return !self.getJob().isA(Job.CLERIC);
    }

    record SkillChoice(int skillId, int level, StatEffect effect) {}
}
