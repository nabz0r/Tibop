package fr.lettrine.game;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
        }
    }
}
