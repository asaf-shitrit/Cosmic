package bot.budget;

import bot.MutableClock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageLedgerTest {
    private static final long HOUR = 3_600_000;

    @Test
    void dailyCapStopsFurtherConsumptionUntilTheNextDay() {
        MutableClock clock = MutableClock.at("2026-09-15T10:00:00Z");
        UsageLedger ledger = new UsageLedger(new UsageLedger.InMemoryStore(), clock);

        assertTrue(ledger.tryConsume("mesos", UsageLedger.Window.DAY, 60_000, 100_000));
        assertTrue(ledger.tryConsume("mesos", UsageLedger.Window.DAY, 40_000, 100_000));
        assertFalse(ledger.tryConsume("mesos", UsageLedger.Window.DAY, 1, 100_000), "cap reached exactly");
        assertEquals(100_000, ledger.used("mesos", UsageLedger.Window.DAY));

        clock.advanceMs(14 * HOUR);    // 2026-09-16T00:00Z
        assertEquals(0, ledger.used("mesos", UsageLedger.Window.DAY));
        assertTrue(ledger.tryConsume("mesos", UsageLedger.Window.DAY, 100_000, 100_000));
    }

    @Test
    void aPurchaseThatWouldOvershootIsRefusedWhole() {
        UsageLedger ledger = new UsageLedger(new UsageLedger.InMemoryStore(), MutableClock.at("2026-09-15T10:00:00Z"));
        assertTrue(ledger.tryConsume("mesos", UsageLedger.Window.DAY, 90_000, 100_000));
        assertFalse(ledger.tryConsume("mesos", UsageLedger.Window.DAY, 20_000, 100_000));
        assertEquals(90_000, ledger.used("mesos", UsageLedger.Window.DAY), "a refused consume adds nothing");
    }

    @Test
    void hourlyWindowRollsOverOnTheHour() {
        MutableClock clock = MutableClock.at("2026-09-15T10:59:00Z");
        UsageLedger ledger = new UsageLedger(new UsageLedger.InMemoryStore(), clock);
        assertTrue(ledger.tryConsume("chat", UsageLedger.Window.HOUR, 1, 1));
        assertFalse(ledger.tryConsume("chat", UsageLedger.Window.HOUR, 1, 1));
        clock.advanceMs(60_000);
        assertTrue(ledger.tryConsume("chat", UsageLedger.Window.HOUR, 1, 1));
    }

    @Test
    void zeroCapAlwaysRefusesAndRefundGivesBack() {
        UsageLedger ledger = new UsageLedger(new UsageLedger.InMemoryStore(), MutableClock.at("2026-09-15T10:00:00Z"));
        assertFalse(ledger.tryConsume("llm", UsageLedger.Window.DAY, 1, 0));
        assertTrue(ledger.tryConsume("mesos", UsageLedger.Window.DAY, 5_000, 10_000));
        ledger.refund("mesos", UsageLedger.Window.DAY, 5_000);
        assertEquals(0, ledger.used("mesos", UsageLedger.Window.DAY));
        ledger.refund("mesos", UsageLedger.Window.DAY, 5_000);
        assertEquals(0, ledger.used("mesos", UsageLedger.Window.DAY), "refund never goes negative");
    }
}
