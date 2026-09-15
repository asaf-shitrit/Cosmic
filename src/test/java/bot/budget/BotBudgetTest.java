package bot.budget;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotBudgetTest {

    @Test
    void backgroundKindsStopAtCapMinusCompanionReserve() {
        BotBudget budget = new BotBudget(10, 3);
        List<BotBudget.Lease> leases = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            leases.add(budget.tryAcquire(BotBudget.Kind.RESIDENT, "r" + i).orElseThrow());
        }
        assertTrue(budget.tryAcquire(BotBudget.Kind.AMBIENT, "a").isEmpty());
        assertEquals(7, budget.inUse());
    }

    @Test
    void companionsMayUseTheReserveButNeverExceedTheCap() {
        BotBudget budget = new BotBudget(10, 3);
        for (int i = 0; i < 7; i++) {
            budget.tryAcquire(BotBudget.Kind.RESIDENT, "r" + i).orElseThrow();
        }
        for (int i = 0; i < 3; i++) {
            assertTrue(budget.tryAcquire(BotBudget.Kind.COMPANION, "c" + i).isPresent());
        }
        assertTrue(budget.tryAcquire(BotBudget.Kind.COMPANION, "c3").isEmpty());
        assertEquals(10, budget.inUse());
    }

    @Test
    void releasingFreesTheSlotAndDoubleCloseIsHarmless() {
        BotBudget budget = new BotBudget(1, 0);
        BotBudget.Lease lease = budget.tryAcquire(BotBudget.Kind.RESIDENT, "r").orElseThrow();
        assertTrue(budget.tryAcquire(BotBudget.Kind.RESIDENT, "r2").isEmpty());
        lease.close();
        lease.close();
        assertEquals(0, budget.inUse());
        assertTrue(budget.tryAcquire(BotBudget.Kind.RESIDENT, "r2").isPresent());
    }

    @Test
    void externalBotsCountAgainstTheCap() {
        BotBudget budget = new BotBudget(10, 3);
        AtomicInteger summoned = new AtomicInteger(6);
        budget.setExternalUsage(summoned::get);
        Optional<BotBudget.Lease> first = budget.tryAcquire(BotBudget.Kind.RESIDENT, "r0");
        assertTrue(first.isPresent());
        assertTrue(budget.tryAcquire(BotBudget.Kind.RESIDENT, "r1").isEmpty(), "6 external + 1 resident = 7 = cap - reserve");
        summoned.set(2);
        assertTrue(budget.tryAcquire(BotBudget.Kind.RESIDENT, "r1").isPresent());
        assertEquals(4, budget.inUse());
        assertEquals(2, budget.inUse(BotBudget.Kind.RESIDENT));
        assertFalse(budget.holders().isEmpty());
    }
}
