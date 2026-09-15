package bot.residents;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WakeSchedulerTest {
    private static final long MIN = 60_000;
    private static final long HOUR = 60 * MIN;

    private static WakeScheduler.Settings production() {
        return new WakeScheduler.Settings(2 * HOUR, 6 * HOUR, 5 * MIN, 15 * MIN, 3, 90_000, MIN, 30 * MIN);
    }

    private static List<String> cast(int n) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            names.add("Res" + i);
        }
        return names;
    }

    @Test
    void startupSpreadsFirstWakesAcrossTheCatchUpWindow() {
        WakeScheduler s = new WakeScheduler(production(), new Random(7));
        long now = 1_000_000;
        s.startup(cast(12), now);
        List<Long> times = new ArrayList<>();
        for (String r : cast(12)) {
            long at = s.nextWakeAt(r);
            assertTrue(at >= now + MIN && at <= now + 30 * MIN, "first wake inside the window");
            times.add(at);
        }
        times.sort(Long::compare);
        for (int i = 1; i < times.size(); i++) {
            assertTrue(times.get(i) - times.get(i - 1) >= 2 * MIN, "evenly spread, no two at the same moment");
        }
    }

    @Test
    void neverMoreThanMaxAwakeAndAlwaysTheGapBetweenStarts() {
        WakeScheduler s = new WakeScheduler(production(), new Random(1));
        long now = 0;
        s.startup(cast(12), now);
        now += 31 * MIN;                       // everyone is due now
        assertEquals(12, s.dueCount(now));

        String first = s.nextDue(now).orElseThrow();
        s.started(first, now);
        assertTrue(s.nextDue(now + 89_000).isEmpty(), "gap not yet elapsed");
        String second = s.nextDue(now + 90_000).orElseThrow();
        s.started(second, now + 90_000);
        String third = s.nextDue(now + 180_000).orElseThrow();
        s.started(third, now + 180_000);
        assertTrue(s.nextDue(now + 10 * HOUR).isEmpty(), "three awake is the ceiling");
        assertEquals(3, s.awakeCount());

        s.ended(first, now + 400_000);
        assertTrue(s.nextDue(now + 400_000).isPresent());
    }

    @Test
    void sleepAndSessionLengthsStayInTheirRanges() {
        WakeScheduler s = new WakeScheduler(production(), new Random(3));
        s.startup(List.of("Mira"), 0);
        for (int i = 0; i < 200; i++) {
            long session = s.started("Mira", 0);
            assertTrue(session >= 5 * MIN && session <= 15 * MIN);
            long next = s.ended("Mira", 1_000);
            assertTrue(next - 1_000 >= 2 * HOUR && next - 1_000 <= 6 * HOUR);
        }
    }

    @Test
    void aBlockedResidentStaysDueAndTheQueueDrainsOneGapAtATime() {
        WakeScheduler.Settings compressed = new WakeScheduler.Settings(3 * MIN, 5 * MIN, MIN, 2 * MIN, 2, 20_000, 5_000, 60_000);
        WakeScheduler s = new WakeScheduler(compressed, new Random(5));
        s.startup(cast(4), 0);
        long now = 10 * MIN;                   // e.g. nobody was online for ten minutes
        assertEquals(4, s.dueCount(now));
        int started = 0;
        for (long t = now; t < now + 20_000 * 4; t += 1_000) {
            if (s.nextDue(t).isPresent()) {
                s.started(s.nextDue(t).get(), t);
                started++;
            }
        }
        assertEquals(2, started, "maxAwake 2 holds even when four are waiting");
    }

    @Test
    void dueWithinListsSoonestFirstForBatchedPlanning() {
        WakeScheduler s = new WakeScheduler(production(), new Random(11));
        s.startup(cast(6), 0);
        List<String> soon = s.dueWithin(0, 10 * MIN);
        assertTrue(soon.size() >= 2);
        for (int i = 1; i < soon.size(); i++) {
            assertTrue(s.nextWakeAt(soon.get(i - 1)) <= s.nextWakeAt(soon.get(i)));
        }
    }
}
