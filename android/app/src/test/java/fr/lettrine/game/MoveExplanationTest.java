package fr.lettrine.game;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MoveExplanationTest {
    @Test public void crossWordScoresAndBlankComeFromTheRecordedBoard() {
        int center = FrenchGame.CENTER;
        var opening = play("AS", 4, new FrenchGame.Tile(center, 0, 'A', false),
                new FrenchGame.Tile(center + 1, 1, 'S', false));
        var crossed = play("ET", 4, new FrenchGame.Tile(center + 15, 0, 'E', false),
                new FrenchGame.Tile(center + 16, 1, 'T', true));

        MoveExplanation openingExplanation = MoveExplanation.create(List.of(opening), 0);
        assertTrue(openingExplanation.text(0).contains("H8 (mot double)"));

        MoveExplanation explanation = MoveExplanation.create(List.of(opening, crossed), 1);
        assertTrue(explanation.text(0).contains("ET (1 point), AE (2 points), ST (1 point)"));
        int blank = explanation.options().indexOf("1 blank tiles, worth zero points");
        assertTrue(explanation.text(blank).contains("T en I9. Un joker vaut zéro point."));
        assertTrue(explanation.facts().contains("ET for 4 points"));
        assertFalse(explanation.facts().contains("PRIVATE"));
    }

    @Test public void bingoAddsFiftyToTheWordScore() {
        String word = "ABDIQUE";
        FrenchGame.Tile[] tiles = new FrenchGame.Tile[word.length()];
        for (int i = 0; i < tiles.length; i++) {
            tiles[i] = new FrenchGame.Tile(FrenchGame.CENTER - 2 + i, i, word.charAt(i), false);
        }
        MoveExplanation explanation = MoveExplanation.create(List.of(play(word, 86, tiles)), 0);
        assertTrue(explanation.text(0).contains("ajoute 50 points aux 36 points"));
    }

    @Test public void inconsistentHistoryOnlyReportsTheSavedTotal() {
        var move = play("AT", 99, new FrenchGame.Tile(FrenchGame.CENTER, 0, 'A', false),
                new FrenchGame.Tile(FrenchGame.CENTER + 1, 1, 'T', false));
        MoveExplanation explanation = MoveExplanation.create(List.of(move), 0);
        assertEquals(List.of(), explanation.options());
        assertEquals("« AT » rapporte 99 points.", explanation.text(0));
        assertNull(MoveExplanation.create(List.of(move), 1));
    }

    @Test public void passStreakAndExchangesUsePublicDetails() {
        var pass = new FrenchGame.Move(0, "pass", "", 0, List.of(), 0, "PRIVATE", 87);
        var exchange = new FrenchGame.Move(1, "exchange", "", 0, List.of(), 2, "PRIVATE", 87);
        var history = List.of(pass, pass, exchange, pass);
        assertTrue(MoveExplanation.create(history, 1).text(0).contains("2ᵉ passe consécutive"));
        assertTrue(MoveExplanation.create(history, 2).text(0).contains("2 lettres ont été échangées"));
        assertTrue(MoveExplanation.create(history, 3).facts().contains("1 consecutive passes"));
    }

    @Test public void englishExplanationUsesEnglishTileValuesAndPassRule() {
        int center = FrenchGame.CENTER;
        var opening = play("AX", 18, new FrenchGame.Tile(center, 0, 'A', false),
                new FrenchGame.Tile(center + 1, 1, 'X', false));
        var explanation = MoveExplanation.create(List.of(opening), 0, GameLanguage.ENGLISH);
        assertTrue(explanation.text(0).contains("AX scores 18 points."));
        assertTrue(explanation.text(0).contains("H8 (double word)"));
        assertTrue(explanation.facts().contains("AX for 18 points"));

        var pass = new FrenchGame.Move(0, "pass", "", 0, List.of(), 0, "PRIVATE", 87);
        assertTrue(MoveExplanation.create(List.of(pass), 0, GameLanguage.ENGLISH)
                .text(0).contains("six consecutive scoreless turns"));
    }

    private static FrenchGame.Move play(String word, int score, FrenchGame.Tile... tiles) {
        return new FrenchGame.Move(0, "play", word, score, List.of(tiles), 0, "PRIVATE", 87);
    }
}
