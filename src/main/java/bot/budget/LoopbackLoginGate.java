package bot.budget;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * One in-server bot logs in at a time, from its first login packet until its first {@code SET_FIELD}.
 *
 * <p>Every in-server bot connects from 127.0.0.1, and the login-to-channel handoff keeps per-IP,
 * single-slot state ({@code HostHwidCache}): two bots between {@code CHAR_SELECT} and
 * {@code PLAYER_LOGGEDIN} at once share one slot and one of them is silently never loaded. See
 * {@code SummonedBot#LOGIN_GATE}, where this was found. That gate is private to the companion code,
 * so residents serialize through this one; after the merge both should use this single gate, or a
 * resident and a companion logging in at the same moment can still collide.
 */
public final class LoopbackLoginGate {
    private static final Semaphore GATE = new Semaphore(1, true);

    private LoopbackLoginGate() {
    }

    /** Waits for the gate, giving up if {@code cancelled} turns true meanwhile. */
    public static boolean acquire(BooleanSupplier cancelled) throws InterruptedException {
        while (!GATE.tryAcquire(250, TimeUnit.MILLISECONDS)) {
            if (cancelled.getAsBoolean()) {
                return false;
            }
        }
        return true;
    }

    public static void release() {
        GATE.release();
    }
}
