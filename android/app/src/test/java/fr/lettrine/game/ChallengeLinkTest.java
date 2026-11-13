package fr.lettrine.game;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChallengeLinkTest {
    private static final LocalDate DATE = LocalDate.of(2026, 11, 13);
    private static Lexicon lexicon;

    @BeforeClass public static void loadLexicon() throws Exception {
        lexicon = dictionary("grammar-fr");
    }

    @Test public void bothUrlFormsReconstructSamePositionAndScoreWithoutSolution() {
        DailyChallenge original = DailyChallenge.forDate(DATE, lexicon);
        ChallengeLink link = ChallengeLink.create(original, lexicon, 15);
        DailyChallenge recovered = DailyChallenge.forDate(link.date(), lexicon);

        assertEquals(link, ChallengeLink.parse(link.uri()));
        assertEquals(link, ChallengeLink.parse(link.appUri()));
        assertEquals(15, link.score());
        assertTrue(link.matches(recovered, lexicon));
        assertTrue(link.uri().startsWith("https://nabz0r.github.io/scrabnble/defi/#v1/2026-11-13/15/"));
        assertFalse(link.uri().contains("CLOCHETTE"));
        assertFalse(link.appUri().contains(original.openingWord()));
        assertEquals(link.identity(), ChallengeLink.create(recovered, lexicon, 17).identity());
    }

    @Test public void identityRejectsOtherDatesLexiconsAndChangedRack() throws Exception {
        DailyChallenge original = DailyChallenge.forDate(DATE, lexicon);
        ChallengeLink link = ChallengeLink.create(original, lexicon, 15);
        PracticePosition originalPosition = original.position();
        ArrayList<Character> changedRack = new ArrayList<>(originalPosition.rack());
        changedRack.set(0, 'Z');
        PracticePosition wrongPosition = new PracticePosition(originalPosition.board(), originalPosition.blanks(),
                changedRack, originalPosition.reference(), null);
        DailyChallenge changed = new DailyChallenge(DATE, wrongPosition, original.openingWord(),
                original.openingStart(), original.openingEnd());

        assertFalse(link.matches(changed, lexicon));
        assertFalse(link.matches(original, dictionary("other-lexicon")));
        assertFalse(link.matches(DailyChallenge.forDate(DATE.plusDays(1), lexicon), lexicon));
    }

    @Test public void malformedOrAmbiguousLinksAreRejected() {
        ChallengeLink link = ChallengeLink.create(DailyChallenge.forDate(DATE, lexicon), lexicon, 0);
        String uri = link.uri();
        String app = link.appUri();

        assertEquals(link, ChallengeLink.parse(uri));
        assertNull(ChallengeLink.parse(null));
        assertNull(ChallengeLink.parse(uri + "/"));
        assertNull(ChallengeLink.parse(uri + "\n"));
        assertNull(ChallengeLink.parse(uri + "?source=unknown"));
        assertNull(ChallengeLink.parse(uri.replace("https://", "http://")));
        assertNull(ChallengeLink.parse(uri.replace("nabz0r.github.io/", "nabz0r.github.io.invalid/")));
        assertNull(ChallengeLink.parse(uri.replace("/defi/#", "/defi/?source=unknown#")));
        assertNull(ChallengeLink.parse(uri.replace("2026-11-13", "2026-02-30")));
        assertNull(ChallengeLink.parse(uri.replace("2026-11-13", "0000-11-13")));
        assertNull(ChallengeLink.parse(uri.replace("/0/", "/-1/")));
        assertNull(ChallengeLink.parse(uri.replace("/0/", "/10000/")));
        assertNull(ChallengeLink.parse(uri.replace("/0/", "/00/")));
        assertNull(ChallengeLink.parse(app.replace("defi/", "defi:80/")));
        assertNull(ChallengeLink.parse(app.replace("defi/", "other@defi/")));
        assertNull(ChallengeLink.parse(app.replace("/v1/", "/v2/")));
        assertNull(ChallengeLink.parse(app.replace("/v1/", "/%761/")));
    }

    private static Lexicon dictionary(String identity) throws Exception {
        return Lexicon.read(new BufferedReader(new StringReader("CLOCHE\nCLOCHES\nCLOCHETTE\n")),
                "Français", "", identity, 3);
    }
}
