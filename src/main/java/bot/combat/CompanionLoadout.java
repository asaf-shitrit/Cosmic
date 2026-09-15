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
import constants.skills.Cleric;
import constants.skills.Spearman;
import constants.skills.Warrior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fits a summoned companion to its owner once per summon: the owner's level (capped), a job for
 * its slot, stats a character of that job and level would plausibly have, the skills its policy in
 * {@link CombatController} uses, a weapon it can actually wear, and a stock of Blue Potions.
 *
 * <p>Everything is <b>set to a target</b>, never added on top: a companion is summoned again and
 * again, so any "grant N" here would compound across summons (an earlier version added the main
 * stat every time and a level-25 warrior drifted towards four-digit STR - and damage with it).
 *
 * <p>Changes are ordinary {@link Character} mutations and persist the way any stat change does,
 * when the character is saved at logout.
 */
public final class CompanionLoadout {
    private static final Logger log = LoggerFactory.getLogger(CompanionLoadout.class);

    private static final int MAX_LEVEL = 70;
    private static final int BASE_STAT = 4;
    private static final int POTION_STOCK = 50;

    private static final int SWORD = 1302000;       // Sword: no requirements
    private static final int POLE_ARM = 1442000;    // Pole Arm: level 10, warriors
    /** Wooden Wand, level 8 and no stat requirement; the first wand a level-30 cleric can always wear. */
    private static final int WAND = 1372005;
    /**
     * Brown Bandana: level 10, +5 accuracy, +8 weapon defence, sold for 1,500 mesos. A warrior's accuracy
     * is only DEX x 0.8 + LUK x 0.5 plus gear, so a companion wearing nothing but a weapon misses
     * Kerning PQ's Jr. Neckis (avoid 15) about 99 times in 100 - seen live, stage 5 never finished.
     * Gear with accuracy is what a real warrior of this level wears for exactly that reason.
     */
    private static final int ACCURACY_CAP = 1002392;
    private static final short WEAPON_SLOT = -11;
    private static final short CAP_SLOT = -1;

    enum Role {
        WARRIOR(Job.WARRIOR, SWORD),
        SPEARMAN(Job.SPEARMAN, POLE_ARM),
        CLERIC(Job.CLERIC, WAND);

        final Job job;
        final int weapon;

        Role(Job job, int weapon) {
            this.job = job;
            this.weapon = weapon;
        }
    }

    /** STR/DEX/INT/LUK a companion of this role and level is set to. */
    record Stats(int str, int dex, int int_, int luk) {
        int sum() {
            return str + dex + int_ + luk;
        }
    }

    private CompanionLoadout() {}

    /** Second job needs level 30, so below that every slot is a first-job warrior. The third slot heals. */
    static Role roleFor(int slot, int level) {
        if (slot < 0 || slot > 2) {
            throw new IllegalArgumentException("companion slot must be 0..2");
        }
        if (level < 30) {
            return Role.WARRIOR;
        }
        return slot == 2 ? Role.CLERIC : Role.SPEARMAN;
    }

    static int levelFor(int ownerLevel) {
        return Math.max(1, Math.min(MAX_LEVEL, ownerLevel));
    }

    /** Four AP per level into the main stat and one into the secondary, as a player of that job would. */
    static Stats statsFor(Role role, int level) {
        int main = BASE_STAT + 4 * (level - 1);
        int secondary = BASE_STAT + (level - 1);
        return role == Role.CLERIC
                ? new Stats(BASE_STAT, BASE_STAT, main, secondary)
                : new Stats(main, secondary, BASE_STAT, BASE_STAT);
    }

    /** Skill levels, capped by each skill's own master level when applied. */
    static int powerStrikeLevel(int level) {
        return Math.min(1 + level / 5, 20);
    }

    static int secondJobSkillLevel(int level) {
        return 1 + Math.max(0, level - 30) / 5;
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
            bot.updateMaxHpMaxMp(role == Role.CLERIC ? 50 + level * 12 : 50 + level * 32,
                    role == Role.CLERIC ? 5 + level * 28 : 5 + level * 10);
            bot.updateHpMp(bot.getMaxHp(), bot.getMaxMp());

            if (role != Role.CLERIC) {
                learn(bot, Warrior.POWER_STRIKE, powerStrikeLevel(level));
            }
            if (role == Role.SPEARMAN) {
                learn(bot, Spearman.POLEARM_MASTERY, secondJobSkillLevel(level));    // also accuracy: its x
                learn(bot, Spearman.HYPER_BODY, secondJobSkillLevel(level));
            }
            if (role == Role.CLERIC) {
                learn(bot, Cleric.HEAL, secondJobSkillLevel(level));
                learn(bot, Cleric.BLESS, secondJobSkillLevel(level));
            }
            equip(bot, role.weapon, WEAPON_SLOT);
            if (role != Role.CLERIC && level >= 10) {
                equip(bot, ACCURACY_CAP, CAP_SLOT);
            }
            stockPotions(bot);
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

    private static void learn(Character bot, int id, int level) {
        Skill skill = SkillFactory.getSkill(id);
        if (skill == null) {
            log.warn("Companion skill {} doesn't exist in the skill data", id);
            return;
        }
        int capped = Math.min(level, skill.getMaxLevel());
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

    /** Tops Blue Potions up to {@link #POTION_STOCK}; {@link CombatController#recover} drinks them. */
    private static void stockPotions(Character bot) {
        int have = bot.getInventory(InventoryType.USE).countById(ItemId.BLUE_POTION);
        if (have < POTION_STOCK) {
            InventoryManipulator.addById(bot.getClient(), ItemId.BLUE_POTION, (short) (POTION_STOCK - have));
        }
    }
}
