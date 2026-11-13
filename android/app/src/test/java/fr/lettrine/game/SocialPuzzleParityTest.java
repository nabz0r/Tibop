package fr.lettrine.game;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class SocialPuzzleParityTest {
    @Test public void everyServerDuelReconstructsTheExactAndroidPosition() throws Exception {
        Path dictionary = Path.of("../../data/fr/words.txt");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dictionary)));
        Lexicon lexicon;
        try (var reader = Files.newBufferedReader(dictionary)) {
            lexicon = Lexicon.read(reader, "Grammalecte", "MPL-2.0", hash, 406312);
        }

        for (String row : Files.readAllLines(Path.of("../../src/social_puzzles.tsv"))) {
            String[] fields = row.split("\t");
            LocalDate date = LocalDate.parse(fields[0]);
            DailyChallenge challenge = DailyChallenge.forDate(date, lexicon);
            assertNotNull(row, challenge);
            assertEquals(row, fields[1], challenge.openingWord());
            assertEquals(row, Integer.parseInt(fields[2]), challenge.openingStart());
            assertEquals(row, Integer.parseInt(fields[3]), challenge.openingEnd());
            StringBuilder rack = new StringBuilder();
            challenge.position().rack().forEach(rack::append);
            assertEquals(row, fields[4], rack.toString());
            assertEquals(row, fields[5], ChallengeLink.create(challenge, lexicon, 0).identity());
            assertNotNull(row, SocialDuelPanel.exactRackPosition(challenge, fields[4], fields[5], lexicon));
            String reversed = rack.reverse().toString();
            if (!reversed.equals(fields[4])) {
                assertNull(row, SocialDuelPanel.exactRackPosition(challenge, reversed, fields[5], lexicon));
            }
        }
    }

    @Test public void seatIdentityBindsOrderedRackToCatalogBoard() throws Exception {
        Lexicon lexicon = Lexicon.read(new BufferedReader(new StringReader("CLOCHE\nCLOCHES\nCLOCHETTE\n")),
                "Exercice", "Test", "hash", 3);
        DailyChallenge catalog = DailyChallenge.forDate(LocalDate.of(2026, 11, 13), lexicon);
        assertNotNull(catalog);
        List<Character> swapped = List.of('S', 'E', 'A', 'T', 'E', 'E', 'T');
        PracticePosition seat = new PracticePosition(catalog.position().board(), catalog.position().blanks(),
                swapped, null, null);
        String identity = ChallengeLink.positionIdentity(seat, lexicon);
        PracticePosition accepted = SocialDuelPanel.exactRackPosition(catalog, "SEATEET", identity, lexicon);
        assertNotNull(accepted);
        assertEquals(swapped, accepted.rack());
        assertNull(SocialDuelPanel.exactRackPosition(catalog, "TESAETE", identity, lexicon));
        assertNull(SocialDuelPanel.exactRackPosition(catalog, "QQQAAAZ", identity, lexicon));
    }
}
