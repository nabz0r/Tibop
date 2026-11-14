package fr.lettrine.game;

import java.util.Locale;
import java.util.regex.Pattern;

final class WordDefinition {
    private static final Pattern WORD = Pattern.compile("[A-Za-z]{2,15}");

    private WordDefinition() {}

    static String url(GameLanguage language, String word) {
        if (language == null || word == null || !WORD.matcher(word).matches()) return null;
        String query = word.toLowerCase(Locale.ROOT);
        if (language == GameLanguage.FRENCH) {
            // The board strips accents, so leave the choice of spelling to the reader.
            return "https://fr.wiktionary.org/w/index.php?title=Sp%C3%A9cial:Recherche&fulltext=1&search=" + query;
        }
        return "https://en.wiktionary.org/w/index.php?search=" + query + "#English";
    }
}
