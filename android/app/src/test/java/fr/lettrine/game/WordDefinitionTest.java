package fr.lettrine.game;

import org.junit.Test;

import java.net.URI;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class WordDefinitionTest {
    @Test public void frenchSearchLeavesAccentsToTheWiktionaryResults() {
        URI url = URI.create(WordDefinition.url(GameLanguage.FRENCH, "ECOLE"));
        assertEquals("https", url.getScheme());
        assertEquals("fr.wiktionary.org", url.getHost());
        assertEquals("title=Spécial:Recherche&fulltext=1&search=ecole", url.getQuery());
    }

    @Test public void englishSearchTargetsTheEnglishDefinition() {
        URI url = URI.create(WordDefinition.url(GameLanguage.ENGLISH, "CHILDREN"));
        assertEquals("https", url.getScheme());
        assertEquals("en.wiktionary.org", url.getHost());
        assertEquals("search=children", url.getQuery());
        assertEquals("English", url.getFragment());
    }

    @Test public void refusesValuesThatAreNotSingleBoardWords() {
        assertNull(WordDefinition.url(GameLanguage.FRENCH, null));
        assertNull(WordDefinition.url(null, "CHAT"));
        for (String word : List.of("A", "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "ÉCOLE", "CHAT NOIR", "C?AT",
                "CHAT&search=chien", "CHAT#French", "CHAT\n")) {
            assertNull(word, WordDefinition.url(GameLanguage.FRENCH, word));
        }
    }
}
