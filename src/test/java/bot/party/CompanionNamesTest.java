package bot.party;

import client.Character;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The names a summoned companion can end up with.
 *
 * <p>The rules being protected are the server's, not this class's taste: a name that is too long, not
 * alphanumeric, already used, or containing a blocked substring is refused at character creation
 * <em>silently</em> - the bot then waits forever with no error to read (CLAUDE.md records twenty-three
 * minutes lost to exactly that). Every name in the pool is checked against the server's own
 * {@link Character#isBlockedName} rather than against a copy of the list, so the list can change
 * without this test going stale.
 */
class CompanionNamesTest {
    private static final Pattern ALLOWED = Pattern.compile("[a-zA-Z0-9]{3,12}");

    @Test
    void everyNameInThePoolWouldSurviveCharacterCreation() {
        for (String name : CompanionNames.POOL) {
            assertFalse(Character.isBlockedName(name), name + " contains a substring the server refuses");
            assertTrue(ALLOWED.matcher(name).matches(), name + " is not a legal character name here");
        }
    }

    @Test
    void noTwoNamesInThePoolAreTheSame() {
        Set<String> seen = new HashSet<>();
        for (String name : CompanionNames.POOL) {
            assertTrue(seen.add(name.toLowerCase()), name + " appears twice in the pool");
        }
    }

    /** A companion should be the same person every time it is summoned. */
    @Test
    void theSameOwnerAndSlotAlwaysWantsTheSameName() {
        for (int slot = 0; slot < 3; slot++) {
            assertEquals(CompanionNames.wanted(35, slot), CompanionNames.wanted(35, slot));
        }
        assertTrue(Arrays.asList(CompanionNames.POOL).contains(CompanionNames.wanted(35, 0)));
    }

    /** Two owners wanting the same name is normal; the second one has to get a different one. */
    @Test
    void aNameAlreadyTakenIsSkipped() throws Exception {
        String wanted = CompanionNames.wanted(35, 0);
        Set<String> taken = Set.of(wanted.toLowerCase());

        String picked = CompanionNames.available(35, 0, name -> taken.contains(name.toLowerCase()));

        assertNotEquals(wanted, picked, "a name another character already has cannot be created");
        assertTrue(Arrays.asList(CompanionNames.POOL).contains(picked), picked + " is not from the pool");
    }

    @Test
    void aFullServerGivesUpInsteadOfLoopingForever() throws Exception {
        assertNull(CompanionNames.available(35, 0, name -> true));
    }
}
