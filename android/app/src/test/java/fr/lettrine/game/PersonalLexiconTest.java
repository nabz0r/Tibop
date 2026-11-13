package fr.lettrine.game;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

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

    private static PersonalLexicon.Prepared prepare(String input, GameLanguage language) throws IOException {
        return PersonalLexicon.prepare(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), language);
    }
}
