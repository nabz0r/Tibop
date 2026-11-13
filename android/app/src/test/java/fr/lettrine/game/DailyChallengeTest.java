package fr.lettrine.game;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DailyChallengeTest {
    private static final LocalDate LAUNCH = LocalDate.of(2026, 11, 13);
    private static Lexicon french;

    @BeforeClass public static void loadBundledDictionary() throws Exception {
        try (BufferedReader reader = Files.newBufferedReader(Path.of("../../data/fr/words.txt"))) {
            french = Lexicon.read(reader, "Grammalecte 7.7", "", "", 406312);
        }
    }

    @Test public void challengeIsStableForDateAndChangesNextDay() {
        DailyChallenge today = DailyChallenge.forDate(LAUNCH, french);
        DailyChallenge reopened = DailyChallenge.forDate(LAUNCH, french);
        DailyChallenge tomorrow = DailyChallenge.forDate(LAUNCH.plusDays(1), french);

        assertEquals(LAUNCH, today.date());
        assertEquals("CLOCHE", today.openingWord());
        assertEquals("CLOCHETTE", today.position().reference().preview().word());
        assertEquals(today.openingStart(), reopened.openingStart());
        assertEquals(today.openingEnd(), reopened.openingEnd());
        assertArrayEquals(today.position().board(), reopened.position().board());
        assertEquals(today.position().rack(), reopened.position().rack());
        assertEquals(today.position().reference(), reopened.position().reference());
        assertNotEquals(today.openingWord(), tomorrow.openingWord());
        assertNotEquals(today.position().rack(), tomorrow.position().rack());
    }

    @Test public void calendarCoversFourYearsOfDistinctPlayablePositions() {
        Set<String> seen = new HashSet<>();
        Set<String> openings = new HashSet<>();
        String previous = "";
        for (LocalDate date = LAUNCH; date.isBefore(LAUNCH.plusYears(4)); date = date.plusDays(1)) {
            DailyChallenge challenge = DailyChallenge.forDate(date, french);
            assertNotNull(date.toString(), challenge);
            PracticePosition position = challenge.position();
            char[] board = position.board();
            String name = challenge.openingWord();
            assertTrue(name, french.contains(name));
            assertEquals(name, readOpening(challenge, board));
            assertTrue(board[FrenchGame.CENTER] != 0);
            assertEquals(FrenchGame.RACK_SIZE, position.rack().size());
            int[] used = new int[26];
            for (char letter : name.toCharArray()) used[letter - 'A']++;
            for (char letter : position.rack()) used[letter - 'A']++;
            for (char letter = 'A'; letter <= 'Z'; letter++) {
                assertTrue(date.toString(), used[letter - 'A'] <= FrenchGame.tileCount(letter));
            }
            assertNotEquals(date.toString(), previous, name);
            previous = name;
            openings.add(name);
            assertTrue(date.toString(), seen.add(Arrays.toString(board) + position.rack()));

            var reference = position.preview(position.reference().tiles(), french);
            assertTrue(date.toString(), reference.valid());
            assertEquals(position.reference().preview(), reference);
            assertTrue(date.toString(), hasOneTileMove(position, board));
        }
        assertEquals(164, openings.size());
    }

    @Test public void customDictionaryCanOfferCompatibleChallengeOrNone() throws Exception {
        Lexicon supported = dictionary("CLOCHE", "CLOCHES", "CLOCHETTE");
        Lexicon unsupported = dictionary("CHAT", "CHATS", "CHIEN");
        assertEquals("CLOCHE", DailyChallenge.forDate(LAUNCH.plusDays(1), supported).openingWord());
        assertNull(DailyChallenge.forDate(LAUNCH, unsupported));
    }

    private static String readOpening(DailyChallenge challenge, char[] board) {
        int start = challenge.openingStart();
        int end = challenge.openingEnd();
        int direction = start / FrenchGame.SIZE == end / FrenchGame.SIZE ? 1 : FrenchGame.SIZE;
        StringBuilder word = new StringBuilder();
        for (int square = start; square <= end; square += direction) word.append(board[square]);
        return word.toString();
    }

    private static boolean hasOneTileMove(PracticePosition position, char[] board) {
        for (int square = 0; square < board.length; square++) {
            if (board[square] != 0 || !FrenchGame.touches(board, square)) continue;
            for (int index = 0; index < position.rack().size(); index++) {
                var tile = new FrenchGame.Tile(square, index, position.rack().get(index), false);
                if (position.preview(List.of(tile), french).valid()) return true;
            }
        }
        return false;
    }

    private static Lexicon dictionary(String... words) throws Exception {
        return Lexicon.read(new BufferedReader(new StringReader(String.join("\n", words))), "Test", "", "", words.length);
    }
}
