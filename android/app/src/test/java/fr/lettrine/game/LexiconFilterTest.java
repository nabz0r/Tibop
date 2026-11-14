package fr.lettrine.game;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LexiconFilterTest {
    @Test public void excludesAnEndingWithoutRemovingItsDescendantsOrChangingTheBase() throws Exception {
        Lexicon original = dictionary();
        Lexicon filtered = original.filtered("Classique", "classic", List.of("CH", "KM"));
        assertTrue(original.contains("CH"));
        assertTrue(original.contains("KM"));
        assertFalse(filtered.contains("CH"));
        assertFalse(filtered.contains("KM"));
        assertFalse(filtered.contains("?"));
        assertTrue(filtered.contains("CHAT"));
        assertTrue(filtered.contains("CHATS"));
        assertEquals("classic", filtered.hash);
        assertEquals(2, filtered.size);
        assertEquals(rank(original, "CHAT"), rank(filtered, "CHAT"));
    }

    @Test public void botAndPlacementValidationShareTheFilteredWordRules() throws Exception {
        Lexicon original = dictionary();
        Lexicon filtered = original.filtered("Classique", "classic", List.of("CH", "KM"));
        char[] board = new char[FrenchGame.CELLS];
        boolean[] blanks = new boolean[FrenchGame.CELLS];
        List<Character> shortRack = List.of('C', 'H');
        List<FrenchGame.Tile> prefix = List.of(new FrenchGame.Tile(FrenchGame.CENTER, 0, 'C', false),
                new FrenchGame.Tile(FrenchGame.CENTER + 1, 1, 'H', false));

        assertTrue(FrenchGame.preview(board, blanks, shortRack, original, prefix).valid());
        assertFalse(FrenchGame.preview(board, blanks, shortRack, filtered, prefix).valid());
        assertFalse(Bot.scoreCandidates(board, blanks, shortRack, original).isEmpty());
        assertTrue(Bot.scoreCandidates(board, blanks, shortRack, filtered).isEmpty());

        List<Character> rack = List.of('C', 'H', 'A', 'T');
        var chosen = Bot.choose(board, blanks, rack, 0, filtered, 4, 0L);
        var preview = FrenchGame.preview(board, blanks, rack, filtered, chosen.tiles());
        assertTrue(preview.valid());
        assertEquals("CHAT", preview.word());
    }

    @Test public void rejectsUnknownOrRepeatedExclusions() throws Exception {
        Lexicon original = dictionary();
        assertThrows(IOException.class, () -> original.filtered("Classique", "test", List.of("CA")));
        assertThrows(IOException.class, () -> original.filtered("Classique", "test", List.of("CH", "CH")));
    }

    private static Lexicon dictionary() throws Exception {
        return Lexicon.read(new BufferedReader(new StringReader("CH\nCHAT\nKM\nCHATS\n")), "Grammalecte",
                "MPL-2.0", "base", 4);
    }

    private static int rank(Lexicon lexicon, String word) {
        int node = lexicon.root();
        for (char c : word.toCharArray()) node = lexicon.child(node, c - 'A');
        return lexicon.rank(node);
    }
}
