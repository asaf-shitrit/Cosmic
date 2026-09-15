package bot.kpq;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KpqPlannerTest {
    @Test
    void recognizesOnlyTheFiveActualInstanceMaps() {
        assertTrue(KpqPlanner.isStageMap(103000800));
        assertTrue(KpqPlanner.isStageMap(103000804));
        assertFalse(KpqPlanner.isStageMap(103000000));
        assertFalse(KpqPlanner.isStageMap(103000805));
        assertFalse(KpqPlanner.isStageMap(-1));
    }
}
