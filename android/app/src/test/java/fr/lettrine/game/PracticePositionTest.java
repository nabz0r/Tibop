package fr.lettrine.game;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PracticePositionTest {
    @Test public void firstChallengeAcceptsOneLetterAndShowsALegalReference() throws Exception {
        Lexicon lexicon = dictionary("CHAT", "CHATS", "CHATONS");
        PracticePosition exercise = PracticePosition.first(lexicon);
        assertNotNull(exercise);
        assertNull(exercise.originalScore());
        assertEquals("CHAT", new String(exercise.board(), FrenchGame.CENTER, 4));
        assertEquals(List.of('S', 'O', 'N', 'E', 'R', 'I', 'L'), exercise.rack());

        var move = List.of(tile(FrenchGame.CENTER + 4, 0, 'S', false));
        FrenchGame.Preview result = exercise.preview(move, lexicon);
        assertTrue(result.valid());
        assertEquals("CHATS", result.word());
        assertEquals(11, result.score());
        assertEquals("CHATONS", exercise.reference().preview().word());
        assertEquals(13, exercise.reference().preview().score());
        assertEquals(exercise.reference().preview(), exercise.preview(exercise.reference().tiles(), lexicon));

        exercise.board()[FrenchGame.CENTER] = 'Q';
        exercise.blanks()[FrenchGame.CENTER] = true;
        assertEquals(result, exercise.preview(move, lexicon));
        assertFalse(exercise.preview(List.of(tile(FrenchGame.CENTER, 0, 'S', false)), lexicon).valid());
        assertFalse(exercise.preview(List.of(tile(FrenchGame.CENTER + 4, 1, 'S', false)), lexicon).valid());
        assertNull(PracticePosition.first(dictionary("CHAT", "CHATONS")));
    }

    @Test public void missedTurnRestoresItsOwnBoardAndRackAndKeepsOriginalScore() throws Exception {
        Lexicon lexicon = dictionary("AS", "AET", "ZA", "ET", "SE");
        int center = FrenchGame.CENTER;
        FrenchGame.Move opening = new FrenchGame.Move(1, "play", "AS", 4,
                List.of(tile(center, 0, 'A', false), tile(center + 1, 1, 'S', false)), 0, "QAS", 90);
        FrenchGame.Move played = new FrenchGame.Move(0, "play", "AET", 2,
                List.of(tile(center + 15, 0, 'E', false), tile(center + 30, 1, 'T', true)), 0, "ZE?T", 89);
        MatchReview.Turn turn = MatchReview.analyze(List.of(opening, played), lexicon, 0, true).get(0);
        PracticePosition exercise = turn.practice();
        assertNotNull(exercise);
        assertEquals(Integer.valueOf(2), exercise.originalScore());
        assertEquals('A', exercise.board()[center]);
        assertEquals('S', exercise.board()[center + 1]);
        assertEquals(0, exercise.board()[center + 15]);
        assertEquals(List.of('Z', 'E', '?', 'T'), exercise.rack());
        assertNull(turn.played().rack());
        assertFalse(turn.facts().contains("ZE?T"));
        assertTrue(exercise.reference().preview().score() > exercise.originalScore());
        assertEquals(exercise.reference().preview(), exercise.preview(exercise.reference().tiles(), lexicon));

        var replay = List.of(tile(center + 15, 1, 'E', false), tile(center + 30, 2, 'T', true));
        assertEquals("AET", exercise.preview(replay, lexicon).word());
        assertEquals(2, exercise.preview(replay, lexicon).score());
        assertFalse(exercise.preview(List.of(tile(center + 15, 2, 'E', false)), lexicon).valid());
        assertNull(MatchReview.analyze(List.of(opening, played), lexicon, 0, false).get(0).practice());
    }

    @Test public void unavailableOrNonImprovingTurnsDoNotBecomeExercises() throws Exception {
        Lexicon lexicon = dictionary("AS", "SA");
        int center = FrenchGame.CENTER;
        FrenchGame.Move played = new FrenchGame.Move(0, "play", "AS", 4,
                List.of(tile(center, 0, 'A', false), tile(center + 1, 1, 'S', false)), 0, "AS", 89);
        MatchReview.Turn turn = MatchReview.analyze(List.of(played), lexicon, 0, true).get(0);
        assertNotNull(turn.alternative());
        assertEquals(4, turn.alternative().preview().score());
        assertNull(turn.practice());

        FrenchGame.Move hidden = new FrenchGame.Move(0, "play", "AS", 4, played.tiles(), 0, null, -1);
        assertNull(MatchReview.analyze(List.of(hidden), lexicon, 0, true).get(0).practice());
    }

    private static FrenchGame.Tile tile(int position, int rackIndex, char letter, boolean blank) {
        return new FrenchGame.Tile(position, rackIndex, letter, blank);
    }

    private static Lexicon dictionary(String... words) throws Exception {
        return Lexicon.read(new BufferedReader(new StringReader(String.join("\n", words))), "Test", "", "test", words.length);
    }
}
