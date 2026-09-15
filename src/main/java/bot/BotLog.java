package bot;

import java.util.function.Consumer;

/**
 * Where a bot's diagnostic lines go. A standalone bot process (the {@code main} entry points) prints
 * them to stdout exactly as before; a bot running as a thread inside the server JVM (see
 * {@code bot.party.BotPartySupervisor}) must not, because its stdout <em>is</em> the server log and a
 * following bot plans several moves a second - three bots would drown every real server line.
 *
 * <p>The sink is per-thread rather than per-object because the lines come from static helpers
 * ({@link BotSession}'s login flow) and from {@link MapleConnection}, neither of which has anywhere
 * sensible to carry a logger through. One bot is always exactly one thread, so a thread-local maps
 * onto a bot one-to-one.
 */
public final class BotLog {
    private static final ThreadLocal<Consumer<String>> SINK = ThreadLocal.withInitial(() -> System.out::println);

    private BotLog() {
    }

    public static void line(String message) {
        SINK.get().accept(message);
    }

    public static void linef(String format, Object... args) {
        line(String.format(format, args).stripTrailing());
    }

    /** Redirects every later {@link #line} on the calling thread. */
    public static void setSink(Consumer<String> sink) {
        SINK.set(sink);
    }
}
