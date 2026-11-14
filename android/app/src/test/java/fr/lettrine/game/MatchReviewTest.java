package fr.lettrine.game;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MatchReviewTest {
    private static final int CENTER = FrenchGame.CENTER;

    @Test public void ranksThreeLargestGainsForTheSoloPlayer() throws Exception {
        var moves = List.of(pass(0, "QAS"), pass(1, "ZZ"), pass(0, "AZ"),
                pass(0, "ET"), pass(0, "ZA"));
        var result = MatchReview.analyze(moves, lexicon(), 0, true);

        assertEquals(List.of(2, 4, 0), result.stream().map(MatchReview.Turn::index).collect(Collectors.toList()));
        assertEquals(List.of(22, 22, 4), result.stream()
                .map(turn -> turn.alternative().preview().score()).collect(Collectors.toList()));
        assertEquals(List.of("ZA", "ZA", "AS"), result.stream()
                .map(turn -> turn.alternative().preview().word()).collect(Collectors.toList()));
        for (var turn : result) {
            assertEquals(0, turn.played().player());
            assertEquals("pass", turn.played().type());
            assertNull(turn.played().rack());
            assertEquals(-1, turn.played().bagBefore());
            assertFalse(turn.facts().contains("QAS"));
        }
    }

    @Test public void comparesAlternativeWithRecordedRackAndCorrectPreTurnBoard() throws Exception {
        var lexicon = lexicon();
        var first = new FrenchGame.Move(1, "play", "AS", 4, List.of(tile(CENTER, 0, 'A', false),
                tile(CENTER + 1, 1, 'S', false)), 0, "QAS", 90);
        var second = new FrenchGame.Move(0, "play", "AET", 2, List.of(tile(CENTER + 15, 0, 'E', false),
                tile(CENTER + 30, 1, 'T', true)), 0, "ZE?T", 89);

        var turn = MatchReview.analyze(List.of(first, second), lexicon, 0, true).get(0);
        assertEquals(1, turn.index());
        assertEquals('A', turn.board()[CENTER]);
        assertEquals('S', turn.board()[CENTER + 1]);
        assertEquals(0, turn.board()[CENTER + 15]);
        assertNotNull(turn.alternative());
        var alternative = turn.alternative();
        assertNotEquals(turn.played().tiles(), alternative.tiles());
        assertTrue(alternative.preview().score() >= turn.played().score());
        var validated = FrenchGame.preview(turn.board(), turn.blanks(), List.of('Z', 'E', '?', 'T'),
                lexicon, alternative.tiles());
        assertEquals(validated, alternative.preview());
        assertTrue(turn.text(0).contains(validated.word()));
        assertFalse(turn.facts().contains("ZE?T"));
        assertArrayEquals(new boolean[FrenchGame.CELLS], turn.blanks());
    }

    @Test public void incompleteAndIncompatibleArchivesHavePublicOnlyExplanations() throws Exception {
        var opening = new FrenchGame.Move(0, "play", "AS", 4, List.of(tile(CENTER, 0, 'A', false),
                tile(CENTER + 1, 1, 'S', false)), 0, null, -1);
        var publicTurn = MatchReview.analyze(List.of(opening), lexicon(), -1, true).get(0);
        assertNull(publicTurn.alternative());
        assertFalse(publicTurn.rackAvailable());
        assertTrue(publicTurn.text(0).contains("H8 (mot double)"));

        var wrongLexicon = MatchReview.analyze(List.of(pass(0, "AZ")), lexicon(), 0, false).get(0);
        assertTrue(wrongLexicon.rackAvailable());
        assertNull(wrongLexicon.alternative());
        assertTrue(wrongLexicon.text(0).contains("0 point"));
        assertTrue(MatchReview.analyze(List.of(), lexicon(), 0, true).isEmpty());
    }

    @Test public void respectsCancellationBeforeAnalyzingTheNextTurn() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> MatchReview.analyze(List.of(pass(0, null)), null, 0, false));
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void englishReviewExplainsAnAlternativeUsingTheEnglishScore() throws Exception {
        var english = Lexicon.read(new BufferedReader(new StringReader("AX\n")), "Test", "", "", 1,
                GameLanguage.ENGLISH);
        var review = MatchReview.review(List.of(pass(0, "AX")), english, 0, true);
        var turn = review.turns().get(0);
        assertEquals(18, turn.alternative().preview().score());
        assertTrue(turn.text(0).contains("AX scores 18 points. Your move scored 0 points."));
        assertTrue(turn.text(0).contains("The alternative scores 18 points more."));
        assertNotNull(turn.practice());
        assertEquals("AX scores 18 points more on this board.", review.highlight().explanation());
    }

    @Test public void coachingUsesTheDictionaryOfTheMatch() throws Exception {
        var original = Lexicon.read(new BufferedReader(new StringReader("AS\nCH\n")),
                "Original", "", "original", 2);
        var filtered = original.filtered("Classique", "filtered", List.of("CH"));
        var personal = Lexicon.read(new BufferedReader(new StringReader("AS\n")),
                "Personal", "", "personal", 1);
        var moves = List.of(pass(0, "CH"));

        var originalReview = MatchReview.review(moves, original, 0, true);
        var originalTurn = originalReview.turns().get(0);
        assertEquals("CH", originalTurn.alternative().preview().word());
        assertTrue(originalTurn.facts().contains("Legal placement CH"));
        assertNotNull(originalReview.highlight());

        for (var dictionary : List.of(filtered, personal)) {
            var review = MatchReview.review(moves, dictionary, 0, true);
            var turn = review.turns().get(0);
            assertNull(turn.alternative());
            assertNull(turn.practice());
            assertNull(review.highlight());
            assertFalse(turn.facts().contains("CH"));
            assertTrue(turn.details().stream().noneMatch(detail -> detail.contains("CH")));
        }
    }

    @Test public void highlightsAnEasyCrossingWithoutChangingDetailedReviewOrder() throws Exception {
        var lexicon = Lexicon.read(new BufferedReader(new StringReader("AS\nAA\nSS\nZA\n")), "Test", "", "", 4);
        var opening = new FrenchGame.Move(1, "play", "AS", 4, List.of(tile(CENTER, 0, 'A', false),
                tile(CENTER + 1, 1, 'S', false)), 0, "AS", 90);
        var below = new FrenchGame.Move(1, "play", "AA", 2, List.of(tile(CENTER + FrenchGame.SIZE, 0, 'A', false)),
                0, "A", 89);
        var moves = List.of(pass(0, "ZA"), opening, below, pass(0, "S"));

        var review = MatchReview.review(moves, lexicon, 0, true);
        assertEquals(List.of(0, 3), review.turns().stream().map(MatchReview.Turn::index).collect(Collectors.toList()));
        var highlight = review.highlight();
        assertEquals(3, highlight.turn().index());
        assertEquals(List.of(CENTER + FrenchGame.SIZE + 1), highlight.cells());
        assertTrue(highlight.explanation().contains("vous formez AS et SS"));
        assertTrue(highlight.explanation().contains("6 points de plus"));
        assertNotNull(highlight.turn().practice());
        assertNull(MatchReview.review(moves, lexicon, 0, false).highlight());
    }

    private static Lexicon lexicon() throws Exception {
        return Lexicon.read(new BufferedReader(new StringReader("AS\nAET\nZA\nET\nSE\nZZ\n")),
                "Test", "", "test", 6);
    }

    private static FrenchGame.Tile tile(int position, int index, char letter, boolean blank) {
        return new FrenchGame.Tile(position, index, letter, blank);
    }

    private static FrenchGame.Move pass(int seat, String rack) {
        return new FrenchGame.Move(seat, "pass", "", 0, List.of(), 0, rack, 90);
    }
}
