package bot.combat;

import client.Character;
import client.Client;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import constants.skills.Archer;
import constants.skills.Cleric;
import constants.skills.Magician;
import constants.skills.Spearman;
import constants.skills.Warrior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fits a summoned companion to its owner once per summon: the owner's level (capped), a job for
 * its slot, stats a character of that job and level would plausibly have, the skills its policy in
 * {@link CombatController} uses, gear it can actually wear, arrows for a bowman and a stock of Blue
 * Potions.
 *
 * <p>Everything is <b>set to a target</b>, never added on top: a companion is summoned again and
 * again, so any "grant N" here would compound across summons (an earlier version added the main
 * stat every time and a level-25 warrior drifted towards four-digit STR - and damage with it). That
 * covers skills too: a skill this role doesn't use is removed, so a slot that was a bowman last summon
 * doesn't keep Double Shot as a warrior.
 *
 * <p>Changes are ordinary {@link Character} mutations and persist the way any stat change does,
 * when the character is saved at logout.
 */
public final class CompanionLoadout {
    private static final Logger log = LoggerFactory.getLogger(CompanionLoadout.class);

    private static final int MAX_LEVEL = 70;
    private static final int BASE_STAT = 4;

    /**
     * Companions are beginners until 10 and take their first job there, magicians included. The game
     * lets a magician advance at 8 ({@code scripts/npc/1032001.js}), but a party of beginners below 10
     * is what a low-level party looks like, so every job starts at the same level.
     */
    static final int FIRST_JOB_LEVEL = 10;
    static final int SECOND_JOB_LEVEL = 30;

    private static final int SWORD = 1302000;       // Sword: no requirements
    private static final int POLE_ARM = 1442000;    // Pole Arm: level 10, warriors
    /** Wooden Wand, level 8 and no stat requirement; the first wand a level-30 cleric can always wear. */
    private static final int WAND = 1372005;
    /**
     * Brown Bandana: level 10, +5 accuracy, +8 weapon defence, sold for 1,500 mesos. A warrior's accuracy
     * is only DEX x 0.8 + LUK x 0.5 plus gear, so a companion wearing nothing but a weapon misses
     * Kerning PQ's Jr. Neckis (avoid 15) about 99 times in 100 - seen live, stage 5 never finished.
     * Gear with accuracy is what a real warrior of this level wears for exactly that reason. A bowman's
     * DEX x 0.6 + LUK x 0.3 needs it just as much. A magician's spells don't use weapon accuracy at all
     * (see {@link MagicDamageModel#hitChance}), so it goes without.
     */
    private static final int ACCURACY_CAP = 1002392;
    /** Arrow for Bow: no attack bonus, 2,000 to a stack (Item.wz 0206.img 02060000). */
    static final int ARROW = 2060000;
    private static final short WEAPON_SLOT = -11;
    private static final short CAP_SLOT = -1;

    /** A weapon and what Character.wz says it takes to wear it; the order in each list is the upgrade path. */
    record Weapon(int id, int reqLevel, int reqStr, int reqDex, int reqInt, int reqLuk) {
        boolean wearableBy(int level, Stats stats) {
            return level >= reqLevel && stats.str() >= reqStr && stats.dex() >= reqDex && stats.int_() >= reqInt
                    && stats.luk() >= reqLuk;
        }
    }

    /** The shop wands of Ellinia's weapon store, lowest first (Character.wz Weapon/0137*.img). */
    static final List<Weapon> WANDS = List.of(
            new Weapon(1372005, 8, 0, 0, 0, 0),         // Wooden Wand, MATK 23
            new Weapon(1372006, 13, 0, 0, 40, 15),      // Hardwood Wand, MATK 28
            new Weapon(1372002, 18, 0, 0, 55, 20),      // Metal Wand, MATK 33
            new Weapon(1372004, 23, 0, 0, 70, 25),      // Ice Wand, MATK 38
            new Weapon(1372003, 28, 0, 0, 85, 30));     // Mithril Wand, MATK 43

    /** The shop bows of Henesys' weapon store, lowest first (Character.wz Weapon/0145*.img). */
    static final List<Weapon> BOWS = List.of(
            new Weapon(1452002, 10, 0, 0, 0, 0),        // War Bow, WATK 25
            new Weapon(1452003, 15, 20, 40, 0, 0),      // Composite Bow, WATK 30
            new Weapon(1452001, 20, 25, 65, 0, 0),      // Hunter's Bow, WATK 35
            new Weapon(1452000, 25, 30, 80, 0, 0));     // Battle Bow, WATK 40

    enum Role {
        BEGINNER(Job.BEGINNER, 50),
        WARRIOR(Job.WARRIOR, 50),
        MAGICIAN(Job.MAGICIAN, 200),
        BOWMAN(Job.BOWMAN, 100),
        SPEARMAN(Job.SPEARMAN, 50),
        CLERIC(Job.CLERIC, 50);

        final Job job;
        /** Blue Potions carried. A magician spends MP on every attack, so it carries the most. */
        final int potions;

        Role(Job job, int potions) {
            this.job = job;
            this.potions = potions;
        }
    }

    /** STR/DEX/INT/LUK a companion of this role and level is set to. */
    record Stats(int str, int dex, int int_, int luk) {
        int sum() {
            return str + dex + int_ + luk;
        }
    }

    private CompanionLoadout() {}

    /**
     * Below 10 all three are beginners with a plain attack. From 10 to 29 slot 0 is a warrior, slot 1 a
     * magician and slot 2 a bowman. From 30 two slots are spearmen and the third a cleric.
     */
    static Role roleFor(int slot, int level) {
        if (slot < 0 || slot > 2) {
            throw new IllegalArgumentException("companion slot must be 0..2");
        }
        if (level < FIRST_JOB_LEVEL) {
            return Role.BEGINNER;
        }
        if (level >= SECOND_JOB_LEVEL) {
            return slot == 2 ? Role.CLERIC : Role.SPEARMAN;
        }
        return switch (slot) {
            case 1 -> Role.MAGICIAN;
            case 2 -> Role.BOWMAN;
            default -> Role.WARRIOR;
        };
    }

    static int levelFor(int ownerLevel) {
        return Math.max(1, Math.min(MAX_LEVEL, ownerLevel));
    }

    /** Four AP per level into the main stat and one into the secondary, as a player of that job would. */
    static Stats statsFor(Role role, int level) {
        int main = BASE_STAT + 4 * (level - 1);
        int secondary = BASE_STAT + (level - 1);
        return switch (role) {
            case CLERIC, MAGICIAN -> new Stats(BASE_STAT, BASE_STAT, main, secondary);
            case BOWMAN -> new Stats(secondary, main, BASE_STAT, BASE_STAT);
            // A beginner swings a sword with a plain attack, so its AP go where a warrior's would.
            case BEGINNER, WARRIOR, SPEARMAN -> new Stats(main, secondary, BASE_STAT, BASE_STAT);
        };
    }

    /** Skill levels, capped by each skill's own master level when applied. */
    static int powerStrikeLevel(int level) {
        return Math.min(1 + level / 5, 20);
    }

    static int secondJobSkillLevel(int level) {
        return 1 + Math.max(0, level - 30) / 5;
    }

    /** First-job SP: 1 at the advancement, 3 per level after it. */
    static int firstJobSp(int level, int advancementLevel) {
        return level < advancementLevel ? 0 : 1 + 3 * (level - advancementLevel);
    }

    /** Every skill a companion loadout manages; any of these not in a role's target is removed. */
    private static final int[] MANAGED_SKILLS = {Warrior.POWER_STRIKE, Spearman.POLEARM_MASTERY, Spearman.HYPER_BODY,
            Cleric.HEAL, Cleric.BLESS, Magician.ENERGY_BOLT, Magician.MAGIC_CLAW, Archer.ARROW_BLOW,
            Archer.DOUBLE_SHOT, Archer.CRITICAL_SHOT, Archer.BLESSING_OF_AMAZON};

    /**
     * The skill levels a role has at a level, spending first-job SP the way the policy uses them:
     * <ul>
     *   <li>Magician: Energy Bolt 1 (Magic Claw's prerequisite), then Magic Claw. The rest would go to
     *       MP recovery or Magic Guard; companions never take damage (the server only learns of damage
     *       taken from the client, and a bot reports none), so Magic Guard would buy nothing.</li>
     *   <li>Bowman: Arrow Blow 1 (Double Shot's prerequisite), Double Shot to 20, Critical Shot to 20,
     *       then The Blessing of Amazon for accuracy.</li>
     * </ul>
     */
    static Map<Integer, Integer> skillsFor(Role role, int level) {
        Map<Integer, Integer> skills = new LinkedHashMap<>();
        switch (role) {
            case BEGINNER -> { }    // no job skills: CombatController falls back to a plain attack
            case WARRIOR -> skills.put(Warrior.POWER_STRIKE, powerStrikeLevel(level));
            case SPEARMAN -> {
                skills.put(Warrior.POWER_STRIKE, powerStrikeLevel(level));
                skills.put(Spearman.POLEARM_MASTERY, secondJobSkillLevel(level));    // also accuracy: its x
                skills.put(Spearman.HYPER_BODY, secondJobSkillLevel(level));
            }
            case CLERIC -> {
                skills.put(Cleric.HEAL, secondJobSkillLevel(level));
                skills.put(Cleric.BLESS, secondJobSkillLevel(level));
            }
            case MAGICIAN -> {
                int sp = firstJobSp(level, FIRST_JOB_LEVEL);
                skills.put(Magician.ENERGY_BOLT, 1);
                put(skills, Magician.MAGIC_CLAW, Math.min(20, sp - 1));
            }
            case BOWMAN -> {
                int sp = firstJobSp(level, FIRST_JOB_LEVEL) - 1;
                skills.put(Archer.ARROW_BLOW, 1);
                int doubleShot = Math.min(20, sp);
                int critical = Math.min(20, sp - doubleShot);
                put(skills, Archer.DOUBLE_SHOT, doubleShot);
                put(skills, Archer.CRITICAL_SHOT, critical);
                put(skills, Archer.BLESSING_OF_AMAZON, Math.min(16, sp - doubleShot - critical));
            }
        }
        return skills;
    }

    private static void put(Map<Integer, Integer> skills, int id, int level) {
        if (level > 0) {
            skills.put(id, level);
        }
    }

    /** The weapon a role wears at a level: the best of its upgrade path its level and stats allow. */
    static int weaponFor(Role role, int level) {
        Stats stats = statsFor(role, level);
        List<Weapon> path = switch (role) {
            case MAGICIAN -> WANDS;
            case BOWMAN -> BOWS;
            case BEGINNER, WARRIOR -> List.of(new Weapon(SWORD, 0, 0, 0, 0, 0));   // reqJob 0: any job
            case SPEARMAN -> List.of(new Weapon(POLE_ARM, 10, 0, 0, 0, 0));
            case CLERIC -> List.of(new Weapon(WAND, 8, 0, 0, 0, 0));
        };
        int best = path.get(0).id();
        for (Weapon weapon : path) {
            if (weapon.wearableBy(level, stats)) {
                best = weapon.id();
            }
        }
        return best;
    }

    static boolean wearsAccuracyCap(Role role, int level) {
        return level >= 10 && (role == Role.WARRIOR || role == Role.SPEARMAN || role == Role.BOWMAN);
    }

    /** Arrows a bowman carries: two stacks. Double Shot uses two a shot, and a Kerning PQ run fires ~1,000. */
    static int arrowsFor(Role role) {
        return role == Role.BOWMAN ? 4000 : 0;
    }

    /** MaxHP and MaxMP a role is set to: the mage line trades HP for MP, a bowman sits between. */
    static int[] maxHpMpFor(Role role, int level) {
        return switch (role) {
            case CLERIC, MAGICIAN -> new int[]{50 + level * 12, 5 + level * 28};
            case BOWMAN -> new int[]{50 + level * 20, 5 + level * 14};
            case WARRIOR, SPEARMAN -> new int[]{50 + level * 32, 5 + level * 10};
            case BEGINNER -> new int[]{50 + level * 12, 5 + level * 10};
        };
    }

    /**
     * Prepares {@code bot} for {@code owner}. The caller (the supervisor's watchdog) has already
     * established that {@code bot} is the character this summon's own connection logged in; the name
     * check is a second, independent guard against ever applying this to anyone else.
     *
     * <p>Runs on the watchdog thread, while the bot's channel connection is live. The stat, skill and
     * inventory methods used take the character's own locks; holding the client's action lock as well
     * keeps this from interleaving with the bot's own lock-taking packet handlers.
     *
     * @return false if the client was busy - try again on the next tick
     */
    public static boolean prepare(Character bot, Character owner, int slot, String expectedName) {
        if (bot == null || owner == null || expectedName == null || !expectedName.equalsIgnoreCase(bot.getName())
                || bot.getId() == owner.getId()) {
            throw new IllegalArgumentException("character is not this owner's summoned companion");
        }
        Client client = bot.getClient();
        if (client == null || !client.tryacquireClient()) {
            return false;
        }
        try {
            int level = levelFor(owner.getLevel());
            Role role = roleFor(slot, level);
            if (bot.getLevel() != level) {
                bot.setLevel(level);
                // Leftover exp from a different level would level it straight back up on its next kill.
                bot.setExp(0);
            }
            bot.setJob(role.job);
            applyStats(bot, statsFor(role, level));
            int[] hpMp = maxHpMpFor(role, level);
            bot.updateMaxHpMaxMp(hpMp[0], hpMp[1]);
            bot.updateHpMp(bot.getMaxHp(), bot.getMaxMp());

            Map<Integer, Integer> skills = skillsFor(role, level);
            for (int id : MANAGED_SKILLS) {
                learn(bot, id, skills.getOrDefault(id, 0));
            }
            equip(bot, weaponFor(role, level), WEAPON_SLOT);
            if (wearsAccuracyCap(role, level)) {
                equip(bot, ACCURACY_CAP, CAP_SLOT);
            }
            stockTo(bot, ItemId.BLUE_POTION, role.potions);
            stockTo(bot, ARROW, arrowsFor(role));
            return true;
        } finally {
            client.releaseClient();
        }
    }

    /** Moves stats to the target through the normal AP path, refunding or granting AP as needed. */
    private static void applyStats(Character bot, Stats target) {
        int dStr = target.str() - bot.getStr();
        int dDex = target.dex() - bot.getDex();
        int dInt = target.int_() - bot.getInt();
        int dLuk = target.luk() - bot.getLuk();
        int apNeeded = dStr + dDex + dInt + dLuk;
        if (apNeeded > bot.getRemainingAp()) {
            bot.gainAp(apNeeded - bot.getRemainingAp(), true);
        }
        if (!bot.assignStrDexIntLuk(dStr, dDex, dInt, dLuk)) {
            log.warn("Couldn't set companion {} stats to {}", bot.getName(), target);
        }
    }

    /** Sets a skill to {@code level}; 0 removes it if the companion has it. */
    private static void learn(Character bot, int id, int level) {
        Skill skill = SkillFactory.getSkill(id);
        if (skill == null) {
            log.warn("Companion skill {} doesn't exist in the skill data", id);
            return;
        }
        int capped = Math.min(level, skill.getMaxLevel());
        if (capped <= 0) {
            if (bot.getSkillLevel(skill) > 0) {
                bot.changeSkillLevel(skill, (byte) -1, 0, -1);
            }
            return;
        }
        if (bot.getSkillLevel(skill) != capped) {
            bot.changeSkillLevel(skill, (byte) capped, skill.getMaxLevel(), -1);
        }
    }

    /** Equips the role's weapon, reusing a copy already in the bag rather than adding one each summon. */
    private static void equip(Character bot, int itemId, short slot) {
        Item equipped = bot.getInventory(InventoryType.EQUIPPED).getItem(slot);
        if (equipped != null && equipped.getItemId() == itemId) {
            return;
        }
        Item inBag = bot.getInventory(InventoryType.EQUIP).findById(itemId);
        if (inBag == null) {
            if (!InventoryManipulator.addById(bot.getClient(), itemId, (short) 1, null, -1, ItemConstants.UNTRADEABLE, -1)) {
                log.warn("Couldn't give companion {} item {}", bot.getName(), itemId);
                return;
            }
            inBag = bot.getInventory(InventoryType.EQUIP).findById(itemId);
        }
        if (inBag != null) {
            InventoryManipulator.equip(bot.getClient(), inBag.getPosition(), slot);
        }
        Item now = bot.getInventory(InventoryType.EQUIPPED).getItem(slot);
        if (now == null || now.getItemId() != itemId) {
            log.warn("Companion {} couldn't wear item {}", bot.getName(), itemId);
        }
    }

    /**
     * Brings a USE item to exactly {@code target}: tops up what was used, and takes away any surplus
     * (arrows a former bowman still holds, say). {@link CombatController} drinks the potions and
     * {@code RangedAttackHandler} consumes the arrows.
     */
    private static void stockTo(Character bot, int itemId, int target) {
        int have = bot.getInventory(InventoryType.USE).countById(itemId);
        if (have < target) {
            if (!InventoryManipulator.addById(bot.getClient(), itemId, (short) (target - have))) {
                log.warn("Couldn't stock companion {} with {} of item {}", bot.getName(), target - have, itemId);
            }
        } else if (have > target) {
            InventoryManipulator.removeById(bot.getClient(), InventoryType.USE, itemId, have - target, false, false);
        }
    }
}
