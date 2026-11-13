package fr.lettrine.game;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GameLanguageRulesTest {
    @Test public void exchangeAndZeroPointPlacementCountOnlyTowardTheEnglishDuelLimit() throws Exception {
        for (GameLanguage language : GameLanguage.values()) {
            FrenchGame game = duel(language);
            game.racks.get(0).addAll(List.of('?', '?'));
            game.racks.get(1).add('A');
            game.bag.addAll(List.of('E', 'E', 'E', 'E', 'E', 'E', 'E', 'E', 'E'));
            game.pass();
            game.exchange(List.of(0));
            var move = game.play(List.of(new FrenchGame.Tile(FrenchGame.CENTER, 0, 'A', true),
                    new FrenchGame.Tile(FrenchGame.CENTER + 1, 1, 'S', true)), dictionary("AS", language));
            assertTrue(move.valid());
            assertEquals(0, move.score());
            game.pass();
            game.pass();
            game.pass();
            if (language == GameLanguage.ENGLISH) assertEquals("consecutive_passes", game.end);
            else assertNull(game.end);
        }
    }

    @Test public void rackOutUsesFrenchTransferAndEnglishDoubleResidue() throws Exception {
        for (GameLanguage language : GameLanguage.values()) {
            FrenchGame game = duel(language);
            game.racks.get(0).addAll(List.of('A', 'S'));
            game.racks.get(1).add('B');
            var move = game.play(List.of(new FrenchGame.Tile(FrenchGame.CENTER, 0, 'A', false),
                    new FrenchGame.Tile(FrenchGame.CENTER + 1, 1, 'S', false)), dictionary("AS", language));
            assertTrue(move.valid());
            assertEquals(4, move.score());
            assertEquals("rack_out", game.end);
            assertArrayEquals(language == GameLanguage.ENGLISH ? new int[]{10, 0} : new int[]{7, -3}, game.scores);
        }
    }

    @Test public void englishBagUsesTheProfileRatherThanTheFrenchTileDistribution() {
        var game = new FrenchGame(new String[]{"NAB", "Guest"}, new int[]{1, 0}, 23L, GameLanguage.ENGLISH);
        assertEquals(86, game.bag.size());
        for (char letter : "ABCDEFGHIJKLMNOPQRSTUVWXYZ?".toCharArray()) {
            long total = game.bag.stream().filter(tile -> tile == letter).count();
            for (var rack : game.racks) total += rack.stream().filter(tile -> tile == letter).count();
            assertEquals(Character.toString(letter), GameLanguage.ENGLISH.tileCount(letter), total);
        }
    }

    private static Lexicon dictionary(String word, GameLanguage language) throws Exception {
        return Lexicon.read(new BufferedReader(new StringReader(word)), "Test", "", "", 1, language);
    }

    private static FrenchGame duel(GameLanguage language) {
        return new FrenchGame("test", 23L, new String[]{"NAB", "Guest"}, new int[]{1, 0}, language);
    }
}
