package bot.combat;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttackReachTest {
    private static final Point MOB = new Point(1000, 300);

    @Test
    void meleeIsUnchangedStepOntoTheMonster() {
        assertTrue(AttackReach.inReach(new Point(1100, 300), MOB, AttackReach.MELEE));
        assertFalse(AttackReach.inReach(new Point(1080, 380), MOB, AttackReach.MELEE));
        assertEquals(MOB, AttackReach.approach(new Point(0, 0), MOB, AttackReach.MELEE));
    }

    @Test
    void rangedStandsOffLevelWithTheMonsterOnItsOwnSide() {
        Point left = AttackReach.approach(new Point(200, 100), MOB, AttackReach.BOW);
        assertEquals(new Point(1000 - AttackReach.BOW * 2 / 3, 300), left);
        assertTrue(AttackReach.inReach(left, MOB, AttackReach.BOW));
        assertFalse(AttackReach.inReach(left, MOB, AttackReach.MELEE));

        Point right = AttackReach.approach(new Point(1500, 300), MOB, AttackReach.MAGIC);
        assertEquals(new Point(1000 + AttackReach.MAGIC * 2 / 3, 300), right);
        assertTrue(AttackReach.inReach(right, MOB, AttackReach.MAGIC));

        // A monster on another platform is out of reach however close it is sideways.
        assertFalse(AttackReach.inReach(new Point(1000, 400), MOB, AttackReach.BOW));
    }
}
