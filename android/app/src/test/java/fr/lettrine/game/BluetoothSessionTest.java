package fr.lettrine.game;

import org.junit.Test;

import java.util.List;

import static fr.lettrine.game.BluetoothSession.Decision.DIFFERENT_GAME;
import static fr.lettrine.game.BluetoothSession.Decision.INCOMPATIBLE;
import static fr.lettrine.game.BluetoothSession.Decision.RESUME;
import static fr.lettrine.game.BluetoothSession.Decision.START;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BluetoothSessionTest {
    @Test public void freshPairingSupportsVersionOneWithoutResumeFields() {
        assertEquals(START, negotiate(null, null, null));
        assertEquals(START, negotiate(false, null, null));
        assertEquals(INCOMPATIBLE, negotiate(true, null, null));
        assertEquals(INCOMPATIBLE, BluetoothSession.negotiate(MatchCodec.VERSION, "other", false,
                null, "dictionary", true, null));
    }

    @Test public void reconnectRequiresTheSameDuelAndComplementaryRoles() {
        assertEquals(RESUME, negotiate(false, "game", "game"));
        assertEquals(DIFFERENT_GAME, negotiate(false, "different", "game"));
        assertEquals(DIFFERENT_GAME, negotiate(false, null, "game"));
        assertEquals(DIFFERENT_GAME, negotiate(false, "game", null));
        assertEquals(DIFFERENT_GAME, negotiate(null, "game", "game"));
        assertEquals(INCOMPATIBLE, negotiate(true, "game", "game"));
    }

    @Test public void guestKeepsAnUnsentDraftOnlyOnTheUnchangedTurn() {
        FrenchGame game = new FrenchGame(new String[]{"Host", "Guest"}, new int[2], 20261114L);
        game.active = 1;
        game.board[FrenchGame.CENTER] = 'I';
        MatchCodec.State before = snapshot(game);
        assertTrue(BluetoothSession.keepGuestDraft(before, snapshot(game)));
        game.blanks[FrenchGame.CENTER] = true;
        assertFalse(BluetoothSession.keepGuestDraft(before, snapshot(game)));
        game.blanks[FrenchGame.CENTER] = false;
        game.pass();
        assertFalse(BluetoothSession.keepGuestDraft(before, snapshot(game)));
    }

    private static BluetoothSession.Decision negotiate(Boolean remoteHost, String remoteId, String localId) {
        return BluetoothSession.negotiate(MatchCodec.VERSION, "dictionary", remoteHost, remoteId,
                "dictionary", true, localId);
    }

    private static MatchCodec.State snapshot(FrenchGame game) {
        return new MatchCodec.State(game.id, game.names.clone(), game.levels.clone(), game.scores.clone(),
                game.active, game.bag.size(), 1, game.board.clone(), game.blanks.clone(), List.copyOf(game.racks.get(1)),
                List.copyOf(game.history), game.end, game.language);
    }
}
