package bot.combat;

import java.awt.Point;

/**
 * Where an attacker has to stand to attack a monster, for melee, spells and bows. No server classes,
 * so the standalone KPQ bot can load it.
 *
 * <p>None of this is enforced by the server: {@code AbstractDealDamageHandler#applyAttack} only raises
 * a (logged, never blocking) {@code DISTANCE_HACK} alert past {@code distanceSq} 200,000 for melee,
 * 400,000 for magic and 600,000 for ranged attacks (about 447, 632 and 775 px). The reaches here are
 * what looks like real play and sit well inside those.
 */
public final class AttackReach {
    public static final int MELEE = 110;
    /** Energy Bolt and Magic Claw are short spells; about two character widths in the client. */
    public static final int MAGIC = 220;
    /** A first-job bow shot, before The Eye of Amazon. */
    public static final int BOW = 360;
    /** A spell or arrow flies level: the target has to be on about the same line. */
    static final int RANGED_VERTICAL = 50;

    private AttackReach() {}

    public static boolean inReach(Point self, Point target, int reach) {
        if (reach <= MELEE) {
            return self.distanceSq(target) <= (long) reach * reach;
        }
        return Math.abs(self.x - target.x) <= reach && Math.abs(self.y - target.y) <= RANGED_VERTICAL;
    }

    /**
     * Where to step to attack {@code target}: onto it for melee, otherwise level with it and two thirds
     * of the reach away, on the side the attacker already is - so a moving monster stays in reach
     * for the attack that follows the step.
     */
    public static Point approach(Point self, Point target, int reach) {
        if (reach <= MELEE || self == null) {
            return new Point(target);
        }
        int side = self.x < target.x ? -1 : 1;
        return new Point(target.x + side * reach * 2 / 3, target.y);
    }
}
