package fr.lettrine.game;

import org.junit.Test;

import static fr.lettrine.game.GameLanguage.ENGLISH;
import static fr.lettrine.game.GameLanguage.FRENCH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

public class TurnNotificationsTest {
    private static final String ID = "0123456789abcdef0123456789abcdef";
    private static final String ORIGIN = "https://example.com";
    private static final String TOKEN = "a".repeat(64);

    @Test public void notificationPathsCarryTheExactLanguageAndDuel() {
        for (GameLanguage language : GameLanguage.values()) {
            var target = new TurnNotifications.Target(language, ID);
            assertEquals(target, TurnNotifications.Target.parse(target.path()));
        }
        assertNull(TurnNotifications.Target.parse("/" + ID));
        assertNull(TurnNotifications.Target.parse("/de/" + ID));
        assertNull(TurnNotifications.Target.parse("/EN/" + ID));
        assertNull(TurnNotifications.Target.parse("/en/" + ID + "/"));
    }

    @Test public void signedTargetsBindLanguageOriginAndAccountEpochEvenWithTheSameToken() {
        var french = new TurnNotifications.Session(FRENCH, ORIGIN, TOKEN, 0);
        var english = new TurnNotifications.Session(ENGLISH, ORIGIN, TOKEN, 0);
        var anotherAccount = new TurnNotifications.Session(FRENCH, ORIGIN, TOKEN, 1);
        var anotherOrigin = new TurnNotifications.Session(FRENCH, "https://another.example.com", TOKEN, 0);
        assertNotEquals(french.scope(), english.scope());
        assertNotEquals(french.proofFor(ID), english.proofFor(ID));
        assertNotEquals(french.proofFor(ID), anotherAccount.proofFor(ID));
        assertNotEquals(french.proofFor(ID), anotherOrigin.proofFor(ID));
        assertNotEquals(french.proofFor(ID), french.legacyProofFor(ID));
    }
}
