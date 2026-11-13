package fr.lettrine.game;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class BetaLinkTest {
    private static final String INVITE = "0123456789abcdef0123456789abcdef";

    @Test public void linksCarryTheSameHttpsOriginAndOptionalInvitation() {
        assertEquals(new BetaLink("https://amis.example.org", null),
                BetaLink.parse("lettrine://beta/v1/?origin=https%3A%2F%2Famis.example.org"));
        BetaLink invitation = new BetaLink("https://amis.example.org:8443", INVITE);
        assertEquals(invitation, BetaLink.parse("https://AMIS.example.org:8443/beta#" + INVITE));
        assertEquals(invitation, BetaLink.parse(
                "lettrine://beta/v1/?origin=https%3A%2F%2Famis.example.org%3A8443&invite=" + INVITE));
    }

    @Test public void rejectsAmbiguousOriginsAndUnexpectedParameters() {
        assertNull(BetaLink.parse("http://amis.example.org/beta#" + INVITE));
        assertNull(BetaLink.parse("https:/beta"));
        assertNull(BetaLink.parse("https://amis.example.org@another.example/beta#" + INVITE));
        assertNull(BetaLink.parse("https://amis.example.org/beta?invite=" + INVITE));
        assertNull(BetaLink.parse("https://amis.example.org/beta#%30" + INVITE.substring(1)));
        assertNull(BetaLink.parse("lettrine://beta/v1/?origin=https%3A%2F%2Fexample.org%2Fapi"));
        assertNull(BetaLink.parse("lettrine://beta/v1/?origin=https%3A%2F%2Fexample.org&origin=elsewhere"));
        assertNull(BetaLink.parse("lettrine://beta/v1/?origin=%Q0"));
    }

    @Test public void englishInvitationsKeepTheirLanguageOnBothLinkSurfaces() {
        BetaLink english = new BetaLink("https://amis.example.org", INVITE, GameLanguage.ENGLISH);
        assertEquals(english, BetaLink.parse("https://amis.example.org/beta#en/" + INVITE));
        assertEquals(english, BetaLink.parse(
                "lettrine://beta/v1/?origin=https%3A%2F%2Famis.example.org&invite=" + INVITE + "&lang=en"));
        assertNull(BetaLink.parse("https://amis.example.org/beta#en/"));
    }
}
