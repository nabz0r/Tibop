package fr.lettrine.game;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record ChallengeLink(LocalDate date, int score, String identity) {
    private static final String WEBSITE = "https://nabz0r.github.io/scrabnble/defi/#";
    private static final String APP = "lettrine://defi/";
    private static final String VERSION = "v1/";
    private static final Pattern IDENTITY = Pattern.compile("[0-9a-f]{24}");
    private static final Pattern PAYLOAD = Pattern.compile(VERSION
            + "([0-9]{4}-[0-9]{2}-[0-9]{2})/(0|[1-9][0-9]{0,3})/(" + IDENTITY + ")");
    private static final String HEX = "0123456789abcdef";

    ChallengeLink {
        Objects.requireNonNull(date);
        if (date.getYear() < 1 || date.getYear() > 9999 || score < 0 || score > 9999
                || identity == null || !IDENTITY.matcher(identity).matches()) {
            throw new IllegalArgumentException("Invitation invalide.");
        }
    }

    static ChallengeLink create(DailyChallenge challenge, Lexicon lexicon, int score) {
        return new ChallengeLink(challenge.date(), score, positionIdentity(challenge, lexicon));
    }

    static ChallengeLink parse(String value) {
        if (value == null || value.length() > 100) return null;
        String base;
        if (value.startsWith(WEBSITE)) base = WEBSITE;
        else if (value.startsWith(APP)) base = APP;
        else return null;
        Matcher fields = PAYLOAD.matcher(value.substring(base.length()));
        if (!fields.matches()) return null;
        try {
            return new ChallengeLink(LocalDate.parse(fields.group(1)), Integer.parseInt(fields.group(2)), fields.group(3));
        } catch (DateTimeParseException | IllegalArgumentException invalid) {
            return null;
        }
    }

    boolean matches(DailyChallenge challenge, Lexicon lexicon) {
        return challenge != null && date.equals(challenge.date()) && identity.equals(positionIdentity(challenge, lexicon));
    }

    String uri() { return WEBSITE + payload(); }

    String appUri() { return APP + payload(); }

    private String payload() { return VERSION + date + "/" + score + "/" + identity; }

    private static String positionIdentity(DailyChallenge challenge, Lexicon lexicon) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        digest.update(VERSION.getBytes(StandardCharsets.US_ASCII));
        digest.update(lexicon.hash.getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        PracticePosition position = challenge.position();
        char[] board = position.board();
        boolean[] blanks = position.blanks();
        for (int i = 0; i < board.length; i++) {
            digest.update((byte) board[i]);
            digest.update(blanks[i] ? (byte) 1 : (byte) 0);
        }
        for (char letter : position.rack()) digest.update((byte) letter);
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(24);
        for (int i = 0; i < 12; i++) hex.append(HEX.charAt((hash[i] & 255) >>> 4)).append(HEX.charAt(hash[i] & 15));
        return hex.toString();
    }
}
