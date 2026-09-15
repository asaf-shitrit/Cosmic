package bot.residents;

import bot.BotLog;
import bot.BotSession;
import bot.ChannelSession;
import bot.MapleConnection;
import bot.budget.BotBudget;
import bot.budget.LoopbackLoginGate;
import bot.planning.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The thread for one resident's wake: waits its turn at the loopback login gate, logs in over
 * 127.0.0.1 like any client (a new resident's character is created by that login), runs a
 * {@link ResidentSession}, and always releases its gate and its {@link BotBudget} slot.
 *
 * <p>Diagnostic lines from the shared login code go to a small ring buffer, not the server log (see
 * {@link BotLog}); a failed login prints that buffer once as a WARN.
 */
final class ResidentBot implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ResidentBot.class);

    private static final String LOGIN_HOST = "127.0.0.1";
    private static final int LOGIN_PORT = 8484;
    /** Reason 7 ("already logged in") is transient while the account's previous session tears down. */
    private static final int LOGIN_ATTEMPTS = 3;
    private static final long LOGIN_RETRY_MS = 3000;
    private static final int RECENT_LOG_LINES = 30;

    final ResidentProfile profile;
    private final ShopAssessment assessment;
    private final Decision decision;
    private final ResidentServices services;
    private final BotBudget.Lease lease;
    private final long sessionMs;
    private final Consumer<ResidentBot> onExit;
    private final Deque<String> recentLog = new ArrayDeque<>();

    final long startedAt = System.currentTimeMillis();
    volatile boolean stopRequested;
    volatile String outcome = "not started";
    /** When this bot got the login gate; 0 while queued. */
    volatile long loginStartedAt;
    volatile boolean inWorld;
    private volatile MapleConnection connection;

    ResidentBot(ResidentProfile profile, ShopAssessment assessment, Decision decision, ResidentServices services, BotBudget.Lease lease,
                long sessionMs, Consumer<ResidentBot> onExit) {
        this.profile = profile;
        this.assessment = assessment;
        this.decision = decision;
        this.services = services;
        this.lease = lease;
        this.sessionMs = sessionMs;
        this.onExit = onExit;
    }

    void requestStop() {
        stopRequested = true;
    }

    /** Unblocks a thread stuck in a login read; the read throws and the thread ends. */
    void forceClose() {
        MapleConnection c = connection;
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // closing is all that matters
            }
        }
    }

    @Override
    public void run() {
        BotLog.setSink(this::record);
        AtomicBoolean gateHeld = new AtomicBoolean();
        try {
            if (!LoopbackLoginGate.acquire(() -> stopRequested)) {
                outcome = "stopped before login";
                return;
            }
            gateHeld.set(true);
            loginStartedAt = System.currentTimeMillis();
            ChannelSession session = login();
            try (MapleConnection conn = session.connection()) {
                ResidentSession wake = new ResidentSession(profile, assessment, decision, services, conn, session.charId(), sessionMs,
                        () -> stopRequested, new SplittableRandom(), () -> {
                    inWorld = true;
                    if (gateHeld.compareAndSet(true, false)) {
                        LoopbackLoginGate.release();
                    }
                });
                outcome = wake.run();
            }
        } catch (Exception e) {
            outcome = "crashed: " + e;
            if (!stopRequested) {
                log.warn("Resident {} crashed: {}. Recent bot log:\n{}", profile.name(), e, recentLogText());
            }
        } finally {
            if (gateHeld.compareAndSet(true, false)) {
                LoopbackLoginGate.release();
            }
            lease.close();
            onExit.accept(this);
        }
    }

    private ChannelSession login() throws Exception {
        String password = ResidentAccounts.issueSessionPassword(profile.name());
        for (int attempt = 1; ; attempt++) {
            try {
                return BotSession.loginAndEnterChannel(LOGIN_HOST, LOGIN_PORT, profile.name(), password,
                        services.config().channel() - 1, conn -> connection = conn);
            } catch (BotSession.LoginRejectedException e) {
                if (e.reason() != 7 || attempt >= LOGIN_ATTEMPTS || stopRequested) {
                    throw e;
                }
                Thread.sleep(LOGIN_RETRY_MS);
            }
        }
    }

    private void record(String line) {
        synchronized (recentLog) {
            if (recentLog.size() == RECENT_LOG_LINES) {
                recentLog.removeFirst();
            }
            recentLog.addLast(line);
        }
    }

    private String recentLogText() {
        synchronized (recentLog) {
            return String.join("\n", recentLog);
        }
    }
}
