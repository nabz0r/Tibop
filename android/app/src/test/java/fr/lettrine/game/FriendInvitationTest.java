package fr.lettrine.game;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class FriendInvitationTest {
    private static final String CODE = "0123456789abcdef0123456789abcdef";
    private static final String WEB = "https://nabz0r.github.io/scrabnble/amis/#";
    private static final String APP = "lettrine://amis/v1/";

    @Test public void preservesLanguageOfPastedLinks() {
        FriendInvitation english = new FriendInvitation(CODE, GameLanguage.ENGLISH);
        assertEquals(english, FriendInvitation.parse(" " + WEB + "en/" + CODE + " "));
        assertEquals(english, FriendInvitation.parse(APP + "en/" + CODE));
        assertEquals(APP + "en/" + CODE, english.appUri(GameLanguage.FRENCH));

        FriendInvitation french = new FriendInvitation(CODE, GameLanguage.FRENCH);
        assertEquals(french, FriendInvitation.parse(WEB + CODE));
        assertEquals(french, FriendInvitation.parse(APP + CODE));
        assertEquals(APP + CODE, french.appUri(GameLanguage.ENGLISH));
    }

    @Test public void unqualifiedCodesStayInCurrentLanguageAndUnknownUrlsAreRejected() {
        FriendInvitation code = FriendInvitation.parse(CODE);
        assertEquals(new FriendInvitation(CODE, null), code);
        assertEquals(APP + "en/" + CODE, code.appUri(GameLanguage.ENGLISH));
        assertNull(FriendInvitation.parse("https://example.org/amis/#en/" + CODE));
        assertNull(FriendInvitation.parse(WEB + "en/"));
    }
}
