package fr.lettrine.game;

import java.util.regex.Pattern;

record FriendInvitation(String token, GameLanguage language) {
    static final String WEB = "https://nabz0r.github.io/scrabnble/amis/#";
    private static final String APP = "lettrine://amis/v1/";
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32}");

    static FriendInvitation parse(String value) {
        if (value == null) return null;
        String code = value.strip();
        GameLanguage language = null;
        if (code.startsWith(WEB)) {
            code = code.substring(WEB.length());
            language = GameLanguage.FRENCH;
        } else if (code.startsWith(APP)) {
            code = code.substring(APP.length());
            language = GameLanguage.FRENCH;
        }
        if (code.startsWith("en/")) {
            code = code.substring(3);
            language = GameLanguage.ENGLISH;
        }
        return TOKEN.matcher(code).matches() ? new FriendInvitation(code, language) : null;
    }

    String appUri(GameLanguage fallback) {
        GameLanguage destination = language == null ? fallback : language;
        return APP + destination.text("", "en/") + token;
    }
}
