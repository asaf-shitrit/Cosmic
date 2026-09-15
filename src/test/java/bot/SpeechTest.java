package bot;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The speech layer's job is a distribution, not a value, so these tests pin the shapes that matter:
 * typing time scales with length, replies are never instant and never identical, slips stay rare, and
 * no two chat packets can ever land inside the server's 200ms throttle window. {@link FixedRandom}
 * exists so the exact-millisecond assertions are arithmetic rather than luck.
 */
class SpeechTest {

    private static final long START = 1_000_000;

    @Test
    void typingTimeGrowsWithMessageLength() {
        double shortMean = meanEmitDelay("hi there", 200);
        double longMean = meanEmitDelay("hi there, " + "x".repeat(80), 200);

        assertTrue(shortMean >= 200, "even a short line is typed, not blurted: " + shortMean);
        assertTrue(longMean > shortMean * 3,
                "a 90-character line must take far longer than an 8-character one: "
                        + shortMean + "ms vs " + longMean + "ms");
    }

    @Test
    void repliesPauseFirstAndVaryWidely() {
        List<Long> delays = new ArrayList<>();
        for (int seed = 0; seed < 200; seed++) {
            MutableClock clock = new MutableClock(START);
            Speech speech = new Speech(clock, new Random(seed));
            speech.replyTo("hey, do you have any potions left in there?", "yeah, a few");
            delays.add(awaitAt(speech, clock) - START);
        }
        long min = delays.stream().mapToLong(Long::longValue).min().orElseThrow();
        long max = delays.stream().mapToLong(Long::longValue).max().orElseThrow();

        assertTrue(min > 0, "a reply must never land on the tick the question arrived");
        assertTrue(max - min > 1000, "variance is the point; got a spread of only " + (max - min) + "ms");
        assertTrue(delays.stream().distinct().count() > 100,
                "the delay must not collapse onto a handful of values");
    }

    @Test
    void aSecondLineWaitsOutTheServersChatThrottle() {
        MutableClock clock = new MutableClock(START);
        // nextLong -> 0 is the fastest possible typing (42ms/char), nextDouble -> 0.9 no slip, so the
        // 1-character line's own typing time (222ms) sits below the floor and the floor is what shows.
        Speech speech = new Speech(clock, new FixedRandom(0, 0.9));

        speech.say("a");
        long first = awaitAt(speech, clock);
        speech.say("a");
        long second = awaitAt(speech, clock);

        assertEquals(Speech.CHAT_GAP_MS, second - first,
                "typing 'a' takes 222ms, below the floor, so the gap must be exactly the floor");
        assertTrue(second - first > Speech.CHAT_THROTTLE_MS,
                "GeneralChatHandler drops anything within " + Speech.CHAT_THROTTLE_MS + "ms");
    }

    @Test
    void typingTimeNotTheFloorCarriesALongerLine() {
        MutableClock clock = new MutableClock(START);
        Speech speech = new Speech(clock, new FixedRandom(0, 0.9));

        // 180ms base + 42ms/char * 18 chars = 936ms, comfortably above the throttle floor.
        speech.say("hello there friend");
        long first = awaitAt(speech, clock);
        speech.say("hello there friend");
        long second = awaitAt(speech, clock);

        assertEquals(936, second - first);
    }

    @Test
    void anOverlongLineIsCutToTheServersLimitBeforeItIsSent() {
        MutableClock clock = new MutableClock(START);
        Speech speech = new Speech(clock, new FixedRandom(0, 0.9));
        String overlong = "x".repeat(400);

        speech.say(overlong);
        Speech.Utterance utterance = await(speech, clock);

        String sent = ((Speech.Utterance.Chat) utterance).text();
        assertEquals(Byte.MAX_VALUE, sent.length());
        assertTrue(overlong.startsWith(sent), "truncation must not rewrite what it keeps");
        assertFalse(sent.isEmpty());
    }

    @Test
    void truncateCutsOnCodePointBoundaries() {
        // 126 bytes of ASCII plus a 2-byte character is 128: the character that does not fit is
        // dropped whole, never split into a lone replacement byte.
        String twoByte = "a".repeat(126) + "é";
        assertEquals("a".repeat(126), Speech.truncate(twoByte));

        // 124 bytes plus a 4-byte emoji is 128 for the same reason.
        String fourByte = "z".repeat(124) + "😀";
        assertEquals("z".repeat(124), Speech.truncate(fourByte));

        String fits = "b".repeat(127);
        assertEquals(fits, Speech.truncate(fits));
        assertEquals(127, Speech.truncate(fits).getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void aTypoKeepsTheWordsButSwapsTwoLetters() {
        String original = "brb, need pots";
        List<String> lines = Speech.imperfections(original, new FixedRandom(0, 0.0, 0.0));

        assertEquals(1, lines.size(), "a plain typo is one line, not a correction pair");
        String typo = lines.get(0);
        assertEquals(original.length(), typo.length());
        assertNotEquals(original, typo);
        assertEquals(sortedLetters(original), sortedLetters(typo), "only the order may change");
    }

    @Test
    void aSelfCorrectionSendsATerseVersionThenTheWholeLine() {
        List<String> lines = Speech.imperfections("brb, need pots", new FixedRandom(0, 0.0, 0.9));
        assertEquals(List.of("brb", "brb, need pots"), lines);
    }

    @Test
    void imperfectionsStayRareEnoughToLookLikeSlips() {
        MutableClock clock = new MutableClock(START);
        Speech speech = new Speech(clock, new Random(20240915));
        String line = "selling red potions for 40 mesos each, come look";
        int samples = 2000;
        int slips = 0;

        for (int i = 0; i < samples; i++) {
            speech.say(line);
            List<String> out = drain(speech, clock);
            if (out.size() != 1 || !out.get(0).equals(line)) {
                slips++;
            }
        }

        double rate = (double) slips / samples;
        assertTrue(rate > 0.01, "the layer must actually slip sometimes, got " + slips + " of " + samples);
        assertTrue(rate < 0.12, "a bot that constantly typoes is worse than one that never does: " + rate);
    }

    @Test
    void emotesRespectTheServersFaceExpressionGate() {
        MutableClock clock = new MutableClock(START);
        Speech speech = new Speech(clock, new FixedRandom(0, 0.9));
        for (int i = 0; i < 4; i++) {
            speech.emote(1 + i);
        }

        List<Long> sent = new ArrayList<>();
        int guard = 0;
        while (sent.size() < 4 && guard++ < 200_000) {
            Speech.Utterance utterance = speech.poll();
            if (utterance == null) {
                clock.advanceMs(1);
                continue;
            }
            assertInstanceOf(Speech.Utterance.Emote.class, utterance);
            sent.add(clock.millis());
        }

        assertEquals(4, sent.size(), "every queued emote should still go out, just slowly");
        for (int i = 1; i < sent.size(); i++) {
            assertTrue(sent.get(i) - sent.get(i - 1) >= Speech.EMOTE_GATE_MS,
                    "Character#changeFaceExpression drops anything within " + Speech.EMOTE_GATE_MS
                            + "ms, silently");
        }
    }

    @Test
    void onlyBuiltInExpressionsAreAllowed() {
        Speech speech = new Speech(new MutableClock(START), new Random(0));
        assertThrows(IllegalArgumentException.class, () -> speech.emote(0));
        assertThrows(IllegalArgumentException.class, () -> speech.emote(8));
        assertThrows(IllegalArgumentException.class, () -> speech.emote(23));
        speech.emote(Speech.MIN_EMOTE);
        speech.emote(Speech.MAX_BUILT_IN_EMOTE);
        assertEquals(2, speech.queued());
    }

    @Test
    void emptyAndCommandLinesAreRefusedRatherThanSent() {
        Speech speech = new Speech(new MutableClock(START), new Random(0));
        assertThrows(IllegalArgumentException.class, () -> speech.say(""));
        assertThrows(IllegalArgumentException.class, () -> speech.replyTo("hey", ""));
        assertThrows(IllegalArgumentException.class, () -> speech.say("/help"));
        assertThrows(IllegalArgumentException.class, () -> speech.replyTo("hey", "/help"));
        assertEquals(0, speech.queued());
    }

    @Test
    void aFloodOfChatterIsDroppedRatherThanQueued() {
        Speech speech = new Speech(new MutableClock(START), new FixedRandom(0, 0.9));
        for (int i = 0; i < 50; i++) {
            speech.say("line number " + i);
        }
        assertEquals(Speech.MAX_QUEUED, speech.queued());
        assertEquals(50 - Speech.MAX_QUEUED, speech.dropped());
    }

    // ---- helpers ----

    private static double meanEmitDelay(String text, int samples) {
        long total = 0;
        for (int seed = 0; seed < samples; seed++) {
            MutableClock clock = new MutableClock(START);
            Speech speech = new Speech(clock, new Random(seed));
            speech.say(text);
            total += awaitAt(speech, clock) - START;
        }
        return (double) total / samples;
    }

    /** Moves the clock a millisecond at a time until {@link Speech} has something to hand over. */
    private static Speech.Utterance await(Speech speech, MutableClock clock) {
        for (int i = 0; i < 200_000; i++) {
            Speech.Utterance utterance = speech.poll();
            if (utterance != null) {
                return utterance;
            }
            clock.advanceMs(1);
        }
        throw new AssertionError("nothing was emitted within 200s of simulated time");
    }

    private static long awaitAt(Speech speech, MutableClock clock) {
        await(speech, clock);
        return clock.millis();
    }

    /** Empties the queue, so a caller can see exactly which lines one utterance turned into. */
    private static List<String> drain(Speech speech, MutableClock clock) {
        List<String> out = new ArrayList<>();
        int guard = 0;
        while (speech.queued() > 0) {
            Speech.Utterance utterance = speech.poll();
            if (utterance == null) {
                clock.advanceMs(1);
                if (++guard > 200_000) {
                    throw new AssertionError("the queue never drained");
                }
            } else if (utterance instanceof Speech.Utterance.Chat chat) {
                out.add(chat.text());
            }
        }
        return out;
    }

    private static List<Character> sortedLetters(String text) {
        return text.chars().mapToObj(c -> (char) c).sorted().toList();
    }

    /**
     * A generator whose every answer the test picks, so a timing assertion can name the exact
     * millisecond it expects. {@code nextLong} answers every bounded request with the same value - 0 in
     * the tests that want the fastest possible typing - and {@code nextDouble} walks {@code chances},
     * repeating the last one, so each successive slip roll is the test's to make.
     */
    private static final class FixedRandom implements RandomGenerator {
        private final long value;
        private final double[] chances;
        private int chanceIndex;

        FixedRandom(long value, double... chances) {
            this.value = value;
            this.chances = chances;
        }

        @Override
        public long nextLong() {
            return value;
        }

        @Override
        public long nextLong(long bound) {
            return Math.floorMod(value, bound);
        }

        @Override
        public int nextInt(int bound) {
            return (int) Math.floorMod(value, bound);
        }

        @Override
        public double nextDouble() {
            return chances[Math.min(chanceIndex++, chances.length - 1)];
        }
    }
}
