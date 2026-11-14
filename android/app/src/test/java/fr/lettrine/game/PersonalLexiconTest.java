package fr.lettrine.game;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class PersonalLexiconTest {
    @Test public void normalizesFrenchAndIdentifiesEquivalentLists() throws IOException {
        PersonalLexicon.Prepared first = prepare("\ufeff  ÉtÉ\r\nœIL\r  ÆON\náa\nété", GameLanguage.FRENCH);
        PersonalLexicon.Prepared reordered = prepare("AEON\nETE\nAA\nOEIL\n", GameLanguage.FRENCH);

        assertEquals("AA\nAEON\nETE\nOEIL\n", new String(first.words(), StandardCharsets.US_ASCII));
        assertEquals(first.entry(), reordered.entry());
        assertEquals(4, first.entry().count());

        Lexicon dictionary = Lexicon.read(new BufferedReader(new StringReader(
                new String(first.words(), StandardCharsets.US_ASCII))), "test", "test",
                first.entry().hash(), first.entry().count(), GameLanguage.FRENCH);
        assertTrue(dictionary.contains("OEIL"));
        assertFalse(dictionary.contains("CHAT"));
    }

    @Test public void rejectsForeignCharactersPunctuationAndInvalidUtf8() {
        assertThrows(IOException.class, () -> prepare("OK\ncafé", GameLanguage.ENGLISH));
        assertThrows(IOException.class, () -> prepare("AB\nB-C", GameLanguage.FRENCH));
        assertThrows(IOException.class, () -> prepare("AB\nX", GameLanguage.FRENCH));
        assertThrows(IOException.class, () -> prepare("ABCDEFGHIJKLMNOP", GameLanguage.FRENCH));
        assertThrows(IOException.class, () -> prepare("  \r\n", GameLanguage.FRENCH));
        assertThrows(IOException.class, () -> PersonalLexicon.prepare(
                new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28}), GameLanguage.FRENCH));
    }

    @Test public void boundsOversizedInputEvenWhenOnlyBlankLines() {
        InputStream endlessLines = new InputStream() {
            @Override public int read() { return '\n'; }

            @Override public int read(byte[] buffer, int offset, int length) {
                java.util.Arrays.fill(buffer, offset, offset + length, (byte) '\n');
                return length;
            }
        };
        assertThrows(IOException.class, () -> PersonalLexicon.prepare(endlessLines, GameLanguage.FRENCH));
    }

    @Test public void additionsPreserveTheClassicBaseAndIsolatePracticeSaves() throws IOException {
        String source = "CHAT\nKM\nAS\n";
        Lexicon base = Lexicon.read(new BufferedReader(new StringReader(source)), "Base", "test", "base", 3)
                .filtered("Classic", "classic", List.of("KM"));
        Lexicon practice = additions(source, base, List.of("œufs", "ok", "OK", "AS"));
        Lexicon reordered = additions(source, base, List.of("OK", "OEUFS"));
        Lexicon removed = additions(source, base, List.of("OEUFS"));

        assertTrue(practice.contains("CHAT"));
        assertTrue(practice.contains("OK"));
        assertTrue(practice.contains("OEUFS"));
        assertFalse(practice.contains("KM"));
        assertFalse(base.contains("OK"));
        assertFalse(removed.contains("OK"));
        assertEquals(4, practice.size);
        assertEquals(practice.hash, reordered.hash);
        assertFalse(practice.hash.equals(removed.hash));
        char[] board = new char[FrenchGame.CELLS];
        boolean[] blanks = new boolean[FrenchGame.CELLS];
        List<Character> rack = List.of('O', 'K');
        List<FrenchGame.Tile> opening = List.of(new FrenchGame.Tile(FrenchGame.CENTER, 0, 'O', false),
                new FrenchGame.Tile(FrenchGame.CENTER + 1, 1, 'K', false));
        assertFalse(FrenchGame.preview(board, blanks, rack, base, opening).valid());
        assertTrue(FrenchGame.preview(board, blanks, rack, practice, opening).valid());
        var suggested = Bot.choose(board, blanks, rack, 0, practice, 4, 0L);
        assertEquals("OK", FrenchGame.preview(board, blanks, rack, practice, suggested.tiles()).word());
        int c = practice.child(practice.root(), 'C' - 'A');
        int h = practice.child(c, 'H' - 'A');
        int a = practice.child(h, 'A' - 'A');
        assertEquals(1, practice.rank(practice.child(a, 'T' - 'A')));
    }

    private static Lexicon additions(String source, Lexicon base, List<String> words) throws IOException {
        return PersonalLexicon.withAdditions(new BufferedReader(new StringReader(source)), base, words, "Test");
    }

    private static PersonalLexicon.Prepared prepare(String input, GameLanguage language) throws IOException {
        return PersonalLexicon.prepare(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), language);
    }
}
