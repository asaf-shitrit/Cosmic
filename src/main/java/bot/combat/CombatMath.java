package bot.combat;

import bot.WorldState;
import client.BuffStat;
import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.skills.Fighter;
import constants.skills.Page;
import constants.skills.Spearman;
import server.StatEffect;

import java.util.random.RandomGenerator;

/**
 * Feeds {@link DamageModel} with a character's own numbers - level, total stats, equipped weapon, gear
 * accuracy, mastery, buffs - so anything that changes those changes the damage it rolls. Two sources:
 * a summoned companion's live server-side {@link Character}, and a standalone client's own view of
 * itself decoded off the wire ({@link WorldState.SelfStats}).
 */
public final class CombatMath {
    private static final short WEAPON_SLOT = -11;

    private CombatMath() {}

    /**
     * One damage line for {@code skillDamagePercent} (100 for a basic attack) against {@code monsterId}:
     * 0 for a miss. Never above the ceiling {@code AbstractDealDamageHandler#parseDamage} computes for the
     * same attack ({@code calculateMaxBaseDamage(getTotalWatk())} times the skill's damage %), so it
     * never trips the server's damage-hack alert.
     */
    public static int lineDamage(Character self, int skillDamagePercent, int monsterId, RandomGenerator rng) {
        int damage = DamageModel.roll(attacker(self), skillDamagePercent, MobDefense.of(monsterId), rng);
        long serverCeiling = (long) self.calculateMaxBaseDamage(self.getTotalWatk()) * skillDamagePercent / 100;
        return (int) Math.min(damage, serverCeiling);
    }

    /**
     * A basic attack by a standalone client that knows itself only from the wire. Mastery is the base
     * 10%: such a client doesn't decode its skill list, and the KPQ test characters have no skills.
     */
    public static int basicLineDamage(WorldState.SelfStats self, int monsterId, RandomGenerator rng) {
        return basicLineDamage(self, MobDefense.of(monsterId), rng);
    }

    static int basicLineDamage(WorldState.SelfStats self, DamageModel.Target target, RandomGenerator rng) {
        DamageModel.Attacker attacker = attacker(self.level(), Job.getById(self.job()),
                self.str() + self.gearStr(), self.dex() + self.gearDex(), self.int_() + self.gearInt(),
                self.luk() + self.gearLuk(), self.weaponId(), self.gearWatk(), DamageModel.BASE_MASTERY_PERCENT,
                self.gearAcc());
        return DamageModel.roll(attacker, 100, target, rng);
    }

    static DamageModel.Attacker attacker(Character self) {
        Item weapon = self.getInventory(InventoryType.EQUIPPED).getItem(WEAPON_SLOT);
        int gearAccuracy = 0;
        for (Item item : self.getInventory(InventoryType.EQUIPPED).list()) {
            if (item instanceof Equip equip) {
                gearAccuracy += equip.getAcc();
            }
        }
        Integer buffAccuracy = self.getBuffedValue(BuffStat.ACC);
        MasterySkill mastery = masterySkill(self, weapon);
        return attacker(self.getLevel(), self.getJob(), self.getTotalStr(), self.getTotalDex(), self.getTotalInt(),
                self.getTotalLuk(), weapon == null ? 0 : weapon.getItemId(), self.getTotalWatk(),
                masteryPercentForLevel(mastery.level()),
                gearAccuracy + (buffAccuracy == null ? 0 : buffAccuracy) + mastery.accuracy());
    }

    /** The stat pairs and weapon multipliers of {@code Character#calculateMaxBaseDamage}, which the model's max must agree with. */
    static DamageModel.Attacker attacker(int level, Job job, int str, int dex, int int_, int luk, int weaponId,
                                         int watk, int masteryPercent, int bonusAccuracy) {
        WeaponType type = weaponType(weaponId);
        if (job != null && job.isA(Job.THIEF) && type == WeaponType.DAGGER_OTHER) {
            type = WeaponType.DAGGER_THIEVES;
        }
        int primary;
        int secondary;
        if (type == WeaponType.BOW || type == WeaponType.CROSSBOW || type == WeaponType.GUN) {
            primary = dex;
            secondary = str;
        } else if (type == WeaponType.CLAW || type == WeaponType.DAGGER_THIEVES) {
            primary = luk;
            secondary = dex + str;
        } else {
            primary = str;
            secondary = dex;
        }
        return new DamageModel.Attacker(level, primary, secondary, type.getMaxDamageMultiplier(), watk,
                masteryPercent, DamageModel.accuracy(dex, luk, bonusAccuracy));
    }

    private static final WeaponType[] WEAPON_CATEGORIES = {
            WeaponType.SWORD1H, WeaponType.GENERAL1H_SWING, WeaponType.GENERAL1H_SWING, WeaponType.DAGGER_OTHER,
            WeaponType.NOT_A_WEAPON, WeaponType.NOT_A_WEAPON, WeaponType.NOT_A_WEAPON, WeaponType.WAND,
            WeaponType.STAFF, WeaponType.NOT_A_WEAPON, WeaponType.SWORD2H, WeaponType.GENERAL2H_SWING,
            WeaponType.GENERAL2H_SWING, WeaponType.SPEAR_STAB, WeaponType.POLE_ARM_SWING, WeaponType.BOW,
            WeaponType.CROSSBOW, WeaponType.CLAW, WeaponType.KNUCKLE, WeaponType.GUN};

    /**
     * The table of {@code ItemInformationProvider#getWeaponType}, copied rather than called: that class's
     * static initialiser reads the database, and a standalone bot has no connection pool (calling it
     * killed a pure-bot KPQ run with ExceptionInInitializerError). The weapon category is just the
     * item id's 3rd-4th digits.
     */
    static WeaponType weaponType(int itemId) {
        int category = (itemId / 10000) % 100;
        return category < 30 || category > 49 ? WeaponType.NOT_A_WEAPON : WEAPON_CATEGORIES[category - 30];
    }

    /** @param accuracy the mastery skill's {@code x}, the accuracy it adds (130.img 1300001: level 1 -> 1, 20 -> 20) */
    record MasterySkill(int level, int accuracy) {}

    /**
     * The warrior mastery skill for the equipped weapon's category. Skill.wz stores each level's
     * {@code mastery} as 1..10 over levels 1..20 (e.g. 130.img 1300001: level 1 -> 1, 10 -> 5, 20 -> 10),
     * worth 5% each on top of the base 10%, so a maxed mastery skill is the familiar 60%.
     */
    static MasterySkill masterySkill(Character self, Item weapon) {
        if (weapon == null) {
            return new MasterySkill(0, 0);
        }
        int[] skills = switch ((weapon.getItemId() / 10000) % 100) {
            case 30, 40 -> new int[]{Fighter.SWORD_MASTERY, Page.SWORD_MASTERY};
            case 31, 41 -> new int[]{Fighter.AXE_MASTERY};
            case 32, 42 -> new int[]{Page.BW_MASTERY};
            case 43 -> new int[]{Spearman.SPEAR_MASTERY};
            case 44 -> new int[]{Spearman.POLEARM_MASTERY};
            default -> new int[0];
        };
        int bestId = 0;
        int best = 0;
        for (int id : skills) {
            if (self.getSkillLevel(id) > best) {
                best = self.getSkillLevel(id);
                bestId = id;
            }
        }
        if (best == 0) {
            return new MasterySkill(0, 0);
        }
        Skill skill = SkillFactory.getSkill(bestId);
        StatEffect effect = skill == null ? null : skill.getEffect(best);
        return new MasterySkill(best, effect == null ? 0 : effect.getX());
    }

    static int masteryPercentForLevel(int skillLevel) {
        return DamageModel.BASE_MASTERY_PERCENT + 5 * ((skillLevel + 1) / 2);
    }
}
