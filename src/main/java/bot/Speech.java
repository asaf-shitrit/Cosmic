package bot;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Decides <em>when</em> a bot speaks and what the wire text ends up being, so that its chat stops
 * looking machine-typed. Nothing here knows a packet: {@link ActionExecutor} hands it {@link
 * Action.Say}, {@link Action.Reply} and {@link Action.Emote} instead of writing them straight to the
 * connection, and its driver loop drains the result one utterance per tick with {@link #poll()}.
 *
 * <p>Four effects, each chosen for a reason rather than as a blanket delay:
 * <ul>
 *   <li><b>Typing time</b> grows with the message ({@link #typingMs}): a base lag plus a per-character
 *       rate plus jitter. A 90-character sentence takes several seconds; a two-character one almost
 *       none. Nobody types the long one in a single tick.</li>
 *   <li><b>Reply latency</b> ({@link #replyTo}) is bimodal - usually a read-then-think pause
 *       proportional to the question, occasionally an immediate answer. The spread is the point: a
 *       tight distribution around one mean is what a 4ms answer already looked like.</li>
 *   <li><b>Imperfection</b> ({@link #imperfections}): rarely a line goes out with a transposed letter,
 *       or as a terse first clause followed by its correction.</li>
 *   <li><b>Emotes</b>: occasionally a reply is punctuated with a face expression.</li>
 * </ul>
 *
 * <p>Two server limits are enforced here rather than trusted to callers:
 * <ul>
 *   <li>{@code GeneralChatHandler} answers any chat sent within 200ms of the previous one with a bare
 *       {@code enableActions()} and drops it, so consecutive chat sends are floored at
 *       {@link #CHAT_GAP_MS} - see {@link #poll()}.</li>
 *   <li>{@code GeneralChatHandler} disconnects a non-GM whose text is longer than {@code Byte.MAX_VALUE}
 *       characters, so every line is cut down in {@link #truncate(String)}.</li>
 * </ul>
 *
 * <p>Deliberately single-threaded, like the rest of the bot client: {@link MapleConnection#receive()}
 * and {@link MapleConnection#send} share a monitor, so a second thread emitting speech would only
 * block behind an in-progress read. Cadence belongs to the driver loop, which is why {@link #poll()}
 * is a non-blocking question and not a {@code Thread.sleep}.
 */
public final class Speech {
    /** GeneralChatHandler's anti-spam window on spam type 7 - see its class javadoc. */
    static final long CHAT_THROTTLE_MS = 200;

    /**
     * The floor between two chat sends, strictly above {@link #CHAT_THROTTLE_MS}: the server's check is
     * {@code lastSpam + 200 > now}, and a send landing exactly on the boundary is the kind of thing a
     * millisecond of scheduling jitter turns into a dropped line. The extra margin also covers a driver
     * loop that only reaches {@link #poll()} once per {@code READ_TICK_MS}.
     */
    static final long CHAT_GAP_MS = 260;

    /** {@code Character#changeFaceExpression} silently ignores an expression within 1500ms of the last. */
    static final long EMOTE_GATE_MS = 1500;
    static final long EMOTE_GAP_MS = EMOTE_GATE_MS + 200;

    /**
     * {@code FaceExpressionHandler} accepts these without the bot owning anything: {@code emote > 7}
     * needs cash face item {@code 5159992 + emote} in the FACE inventory, which no bot has. Which
     * expression each id draws is the client's own art and is not modelled server-side, so
     * {@link #REPLY_EMOTES} deliberately sticks to the first four.
     */
    static final int MIN_EMOTE = 1;
    static final int MAX_BUILT_IN_EMOTE = 7;

    /** Lag before the first keystroke, then ~42-87ms per character, then jitter. */
    private static final long TYPING_BASE_MS = 180;
    private static final long PER_CHAR_MIN_MS = 42;
    private static final long PER_CHAR_RANGE_MS = 46;
    private static final long TYPING_JITTER_MS = 380;
    /** A 127-character line would otherwise run past 9s; the cap keeps one utterance bounded. */
    private static final long MAX_TYPING_MS = 12_000;

    /** Reading time before a reply, scaled by how much there was to read. */
    private static final long READ_BASE_MS = 320;
    private static final long READ_PER_CHAR_MS = 24;
    private static final double QUICK_REPLY_SHARE = 0.25;
    private static final long QUICK_REPLY_MIN_MS = 180;
    private static final long QUICK_REPLY_JITTER_MS = 500;
    private static final long SLOW_REPLY_JITTER_MS = 3500;

    /** Share of utterances that go out with some slip. */
    private static final double IMPERFECTION_SHARE = 0.05;
    /** Below this length there is no room to misspell something plausibly. */
    private static final int MIN_IMPERFECTION_CHARS = 10;
    /** Of those slips, how many are a typo rather than a terse-then-corrected pair. */
    private static final double TYPO_SHARE = 0.6;
    /** A "terse" fragment has to be at least this long to read as deliberate. */
    private static final int MIN_TERSE_CHARS = 3;

    /** How often a reply gets a face expression to go with it. */
    private static final double EMOTE_ON_REPLY_SHARE = 0.12;
    private static final int[] REPLY_EMOTES = {1, 2, 3, 4};

    /** Past this the bot is talking to itself - drop the newest rather than the oldest. */
    static final int MAX_QUEUED = 8;

    /**
     * Something the bot is about to put on the wire. {@link ActionExecutor#tick()} turns each of these
     * into exactly one packet - {@code GENERAL_CHAT} or {@code FACE_EXPRESSION}.
     */
    public sealed interface Utterance {
        record Chat(String text) implements Utterance {}

        record Emote(int emotion) implements Utterance {}
    }

    private record Pending(Utterance utterance, long readyAtMs) {}

    private final Clock clock;
    private final RandomGenerator rng;
    private final Deque<Pending> queue = new ArrayDeque<>();
    private long lastChatAt;
    private long lastEmoteAt;
    private int dropped;

    public Speech() {
        this(Clock.systemUTC(), new Random());
    }

    /**
     * @param clock where "now" comes from; a test moves a {@code MutableClock} by hand
     * @param rng   the only source of jitter, so a test can fix a seed or script the answers
     */
    public Speech(Clock clock, RandomGenerator rng) {
        this.clock = clock;
        this.rng = rng;
    }

    /** Queues a line the bot decided to say on its own, with no assumption anything prompted it. */
    public void say(String text) {
        enqueue(text, 0, false);
    }

    /**
     * Queues a reply to what {@code incoming} just said. The reading/thinking pause comes first (see
     * {@link #replyDelayMs}), so a reply never lands on the tick the question arrived.
     */
    public void replyTo(String incoming, String text) {
        enqueue(text, replyDelayMs(incoming == null ? "" : incoming), true);
    }

    /** Queues a built-in face expression to fire on its own, paced against the server's own gate. */
    public void emote(int emotion) {
        if (emotion < MIN_EMOTE || emotion > MAX_BUILT_IN_EMOTE) {
            throw new IllegalArgumentException("emote " + emotion + " needs cash face item "
                    + (5159992 + emotion) + " in the FACE inventory; the built-in expressions are "
                    + MIN_EMOTE + ".." + MAX_BUILT_IN_EMOTE);
        }
        if (queue.size() >= MAX_QUEUED) {
            dropped++;
            return;
        }
        queue.addLast(new Pending(new Utterance.Emote(emotion), clock.millis()));
    }

    /**
     * The next utterance that is both finished "typing" and clear of its server-side rate limit, or
     * {@code null} when there is nothing to send yet. Never blocks and never waits: the caller's loop
     * is the clock, and one call yields at most one packet so a quiet driver cannot burst.
     */
    public Utterance poll() {
        Pending head = queue.peekFirst();
        if (head == null) {
            return null;
        }
        long now = clock.millis();
        long notBefore = head.readyAtMs();
        if (head.utterance() instanceof Utterance.Chat) {
            notBefore = Math.max(notBefore, lastChatAt + CHAT_GAP_MS);
        } else {
            notBefore = Math.max(notBefore, lastEmoteAt + EMOTE_GAP_MS);
        }
        if (now < notBefore) {
            return null;
        }
        queue.removeFirst();
        if (head.utterance() instanceof Utterance.Chat) {
            lastChatAt = now;
        } else {
            lastEmoteAt = now;
        }
        return head.utterance();
    }

    /** How many utterances are still waiting to go out. */
    public int queued() {
        return queue.size();
    }

    /** How many utterances were refused because {@link #MAX_QUEUED} was already full. */
    public int dropped() {
        return dropped;
    }

    /**
     * Cuts {@code text} down to 127 UTF-8 bytes, never splitting a code point. Measured in bytes
     * rather than characters because a non-ASCII character can cost several bytes while counting as
     * one: byte length is the stricter measure, so a string that passes here also passes
     * {@code GeneralChatHandler}'s {@code s.length() > Byte.MAX_VALUE} check.
     */
    public static String truncate(String text) {
        if (utf8Length(text) <= Byte.MAX_VALUE) {
            return text;
        }
        StringBuilder cut = new StringBuilder(Byte.MAX_VALUE);
        int bytes = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int width = utf8Width(codePoint);
            if (bytes + width > Byte.MAX_VALUE) {
                break;
            }
            cut.appendCodePoint(codePoint);
            bytes += width;
            i += Character.charCount(codePoint);
        }
        return cut.toString().stripTrailing();
    }

    // ---- pacing ----

    private void enqueue(String text, long leadMs, boolean reply) {
        requireSendable(text);
        String clean = truncate(text);
        List<String> lines = imperfections(clean, rng);
        boolean withEmote = reply && rng.nextDouble() < EMOTE_ON_REPLY_SHARE;
        // All or nothing: a self-correction is one utterance in two lines, and admitting the terse half
        // while dropping its correction would leave the bot hanging on "brb".
        if (queue.size() + lines.size() + (withEmote ? 1 : 0) > MAX_QUEUED) {
            dropped++;
            return;
        }
        long readyAt = clock.millis() + leadMs;
        for (String line : lines) {
            readyAt += typingMs(line, rng);
            queue.addLast(new Pending(new Utterance.Chat(line), readyAt));
        }
        if (withEmote) {
            queue.addLast(new Pending(new Utterance.Emote(REPLY_EMOTES[rng.nextInt(REPLY_EMOTES.length)]), readyAt));
        }
    }

    /** {@code GeneralChatHandler#charAt(0)} on an empty line would throw; a leading '/' is a command. */
    private static void requireSendable(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("chat message must not be empty");
        }
        if (text.charAt(0) == '/') {
            throw new IllegalArgumentException("chat message must not start with '/' - "
                    + "GeneralChatHandler treats that as a command and drops anything unrecognised");
        }
    }

    /** Lag before the first keystroke, a per-character rate, and jitter - all rolled per line. */
    private static long typingMs(String line, RandomGenerator rng) {
        long perChar = PER_CHAR_MIN_MS + rng.nextLong(PER_CHAR_RANGE_MS);
        long ms = TYPING_BASE_MS + perChar * line.length() + rng.nextLong(TYPING_JITTER_MS);
        return Math.min(ms, MAX_TYPING_MS);
    }

    /**
     * How long the bot waits before it starts typing a reply. Bimodal on purpose: usually it reads
     * (a base proportional to the question) and thinks (up to 3.5s of drift), occasionally it fires
     * straight back. The variance, not the mean, is what a human reply has and a fixed tick does not.
     */
    private long replyDelayMs(String incoming) {
        long reading = READ_BASE_MS + (long) incoming.length() * READ_PER_CHAR_MS;
        if (rng.nextDouble() < QUICK_REPLY_SHARE) {
            return QUICK_REPLY_MIN_MS + rng.nextLong(QUICK_REPLY_JITTER_MS);
        }
        return reading + rng.nextLong(SLOW_REPLY_JITTER_MS);
    }

    // ---- imperfection ----

    /**
     * The lines one utterance actually goes out as. Almost always a single line identical to the text.
     * Rarely:
     * <ul>
     *   <li><b>typo</b> - two neighbours swapped inside one word ("pots" to "post");</li>
     *   <li><b>self-correction</b> - a terse first clause, then the full line, the way someone writes
     *       "brb" and follows it with "brb, need pots".</li>
     * </ul>
     * Kept rare ({@link #IMPERFECTION_SHARE}) on purpose: a bot that slips on every other line is more
     * obviously a bot than one that never does.
     */
    static List<String> imperfections(String text, RandomGenerator rng) {
        if (text.length() < MIN_IMPERFECTION_CHARS || rng.nextDouble() >= IMPERFECTION_SHARE) {
            return List.of(text);
        }
        if (rng.nextDouble() < TYPO_SHARE) {
            String typo = transposeALetter(text, rng);
            if (!typo.equals(text)) {
                return List.of(typo);
            }
        }
        String terse = firstClause(text);
        if (terse != null && !terse.equals(text)) {
            return List.of(terse, text);
        }
        return List.of(text);
    }

    /**
     * Swaps two adjacent letters that are both inside a word, never its first letter - moving the
     * first letter turns one word into another ("pots" to "opts"), which reads as a mistake no typist
     * makes. Returns {@code text} unchanged when no pair qualifies.
     */
    private static String transposeALetter(String text, RandomGenerator rng) {
        List<Integer> spots = new ArrayList<>();
        for (int i = 1; i + 1 < text.length(); i++) {
            if (Character.isLetter(text.charAt(i - 1)) && Character.isLetter(text.charAt(i))
                    && Character.isLetter(text.charAt(i + 1))) {
                spots.add(i);
            }
        }
        if (spots.isEmpty()) {
            return text;
        }
        int at = spots.get(rng.nextInt(spots.size()));
        char[] chars = text.toCharArray();
        char swapped = chars[at];
        chars[at] = chars[at + 1];
        chars[at + 1] = swapped;
        return new String(chars);
    }

    /**
     * The terse version a self-correction starts with: everything before the first comma, or the first
     * word, whichever is long enough to read as deliberate. {@code null} when the text has no such
     * natural break.
     */
    private static String firstClause(String text) {
        int comma = text.indexOf(',');
        if (comma >= MIN_TERSE_CHARS) {
            return text.substring(0, comma).stripTrailing();
        }
        int space = text.indexOf(' ');
        if (space >= MIN_TERSE_CHARS) {
            return text.substring(0, space);
        }
        return null;
    }

    // ---- utf-8 width ----

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Bytes a single code point costs in UTF-8 (1-4) - no allocation per character. */
    private static int utf8Width(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }
}
