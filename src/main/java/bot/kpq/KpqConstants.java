package bot.kpq;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * Everything about Kerning Party Quest that {@link KpqPlanner} needs and that isn't discoverable
 * from packets: map/NPC/item ids, and the stage 1 question bank and stage 2-4 rectangle/combo data.
 * The latter is copied verbatim from {@code scripts/npc/9020001.js} (the stage NPC's own script,
 * which is the authoritative source the live server runs) rather than re-derived, since a transcription
 * error here would silently make the brute-force solver in {@link KpqPlanner} search the wrong space.
 *
 * <p>Portal coordinates ({@link #STAGE_PARK_SPOT}) come from the map WZ data
 * ({@code wz/Map.wz/Map/Map1/10300080*.img.xml}), read directly since this bot never loads WZ client
 * data itself (see {@code BotSession}) - they're that map's {@code st00} (entry) portal position, used
 * only as a "definitely outside every puzzle rectangle" parking spot for a surplus party member (see
 * {@link KpqPlanner} on why one exists: {@code rectangleStages()} needs exactly 3 players positioned
 * regardless of party size, so a 4-bot party always has one bot left over).
 */
final class KpqConstants {
    private KpqConstants() {
    }

    static final int NPC_RECRUIT = 9020000;
    static final int NPC_STAGE = 9020001;

    static final int MAP_RECRUIT = 103000000;
    /** Stage maps in order: index 0 = stage 1 (103000800) .. index 4 = stage 5 (103000804). */
    static final int[] STAGE_MAPS = {103000800, 103000801, 103000802, 103000803, 103000804};

    static final int ITEM_COUPON = 4001007;
    static final int ITEM_PASS = 4001008;
    static final int MOB_STAGE1 = 9300001;

    /** The portal all stage 1-4 maps use to advance, once {@code NstageClear} is set. */
    static final String NEXT_STAGE_PORTAL = "next00";

    /** Each stage map's {@code st00} spawn position - always outside the puzzle rectangles. */
    static final Point[] STAGE_PARK_SPOT = {
            new Point(-1075, 106),   // stage 1 (not used as a park spot - stage 1 has no rectangles)
            new Point(-1295, 102),   // stage 2
            new Point(-235, 523),    // stage 3
            new Point(759, 458),     // stage 4
            new Point(-210, -2721),  // stage 5
    };

    // stage1Questions/Answers, verbatim from 9020001.js. Matched by a distinguishing substring rather
    // than full equality since the actual talk text also carries the "Here's the question." preamble
    // and quest text formatting this bot doesn't otherwise care to reproduce.
    record Stage1Question(String matchSubstring, int coupons) {}

    static final Stage1Question[] STAGE1_QUESTIONS = {
            new Stage1Question("STR needed", 35),
            new Stage1Question("INT needed", 20),
            new Stage1Question("DEX needed to make the first job advancement as a bowman", 25),
            new Stage1Question("DEX needed to make the first job advancement as a thief", 25),
            new Stage1Question("advance to 2nd job", 30),
            new Stage1Question("as a magician", 8),
            new Stage1Question("as warrior", 10),   // must be checked last: shortest/loosest match
    };

    // stage{2,3,4}Rects/Combos, verbatim from 9020001.js.
    static final Rectangle[] STAGE2_RECTS = {
            new Rectangle(-755, -132, 4, 218), new Rectangle(-721, -340, 4, 166),
            new Rectangle(-586, -326, 4, 150), new Rectangle(-483, -181, 4, 222),
    };
    static final int[][] STAGE2_COMBOS = {
            {0, 1, 1, 1}, {1, 0, 1, 1}, {1, 1, 0, 1}, {1, 1, 1, 0},
    };

    static final Rectangle[] STAGE3_RECTS = {
            new Rectangle(608, -180, 140, 50), new Rectangle(791, -117, 140, 45),
            new Rectangle(958, -180, 140, 50), new Rectangle(876, -238, 140, 45),
            new Rectangle(702, -238, 140, 45),
    };
    static final int[][] STAGE3_COMBOS = {
            {0, 0, 1, 1, 1}, {0, 1, 0, 1, 1}, {0, 1, 1, 0, 1},
            {0, 1, 1, 1, 0}, {1, 0, 0, 1, 1}, {1, 0, 1, 0, 1},
            {1, 0, 1, 1, 0}, {1, 1, 0, 0, 1}, {1, 1, 0, 1, 0},
            {1, 1, 1, 0, 0},
    };

    static final Rectangle[] STAGE4_RECTS = {
            new Rectangle(910, -236, 35, 5), new Rectangle(877, -184, 35, 5),
            new Rectangle(946, -184, 35, 5), new Rectangle(845, -132, 35, 5),
            new Rectangle(910, -132, 35, 5), new Rectangle(981, -132, 35, 5),
    };
    static final int[][] STAGE4_COMBOS = {
            {0, 0, 0, 1, 1, 1}, {0, 0, 1, 0, 1, 1}, {0, 0, 1, 1, 0, 1},
            {0, 0, 1, 1, 1, 0}, {0, 1, 0, 0, 1, 1}, {0, 1, 0, 1, 0, 1},
            {0, 1, 0, 1, 1, 0}, {0, 1, 1, 0, 0, 1}, {0, 1, 1, 0, 1, 0},
            {0, 1, 1, 1, 0, 0}, {1, 0, 0, 0, 1, 1}, {1, 0, 0, 1, 0, 1},
            {1, 0, 0, 1, 1, 0}, {1, 0, 1, 0, 0, 1}, {1, 0, 1, 0, 1, 0},
            {1, 0, 1, 1, 0, 0}, {1, 1, 0, 0, 0, 1}, {1, 1, 0, 0, 1, 0},
            {1, 1, 0, 1, 0, 0}, {1, 1, 1, 0, 0, 0},
    };

    /** Rectangles for stage index 1/2/3 (stage 2/3/4 in human numbering); null for 0 and 4. */
    static Rectangle[] rectsForStage(int stageIndex) {
        return switch (stageIndex) {
            case 1 -> STAGE2_RECTS;
            case 2 -> STAGE3_RECTS;
            case 3 -> STAGE4_RECTS;
            default -> null;
        };
    }

    static int[][] combosForStage(int stageIndex) {
        return switch (stageIndex) {
            case 1 -> STAGE2_COMBOS;
            case 2 -> STAGE3_COMBOS;
            case 3 -> STAGE4_COMBOS;
            default -> null;
        };
    }
}
