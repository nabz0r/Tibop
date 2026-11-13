package fr.lettrine.game;

import org.junit.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executors;

import static fr.lettrine.game.GameLanguage.ENGLISH;
import static fr.lettrine.game.GameLanguage.FRENCH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BonusEconomyTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);
    private static final String CIRCUIT = "bonus-1";

    @Test public void entryThenThreeTicketsRefillInTwoHourStepsWithoutOverflow() {
        var economy = new BonusEconomy(new MemoryStore());
        assertEquals(new BonusEconomy.Wallet(0, 3, 0, true, 0), economy.wallet(NOW));
        for (int i = 0; i < 4; i++) {
            var run = economy.start(FRENCH, CIRCUIT, NOW);
            assertEquals(Math.min(3, 4 - i - 1), economy.wallet(NOW).freeTickets());
            complete(economy, FRENCH, run);
        }
        assertNull(economy.start(FRENCH, CIRCUIT, NOW));
        assertEquals(NOW + BonusEconomy.REFILL_MILLIS, economy.wallet(NOW).nextRefillAtMillis());
        assertEquals(0, economy.wallet(NOW + BonusEconomy.REFILL_MILLIS - 1).freeTickets());
        assertEquals(1, economy.wallet(NOW + BonusEconomy.REFILL_MILLIS).freeTickets());
        assertEquals(2, economy.wallet(NOW + BonusEconomy.REFILL_MILLIS * 2).freeTickets());
        assertEquals(3, economy.wallet(NOW + BonusEconomy.REFILL_MILLIS * 25).freeTickets());
        assertEquals(0, economy.wallet(NOW + BonusEconomy.REFILL_MILLIS * 25).nextRefillAtMillis());
    }

    @Test public void languageRunsAndDraftsSurviveRestartWithoutExtraTicketOrStaleWrites() {
        var store = new MemoryStore();
        var economy = new BonusEconomy(store);
        var french = economy.start(FRENCH, CIRCUIT, NOW);
        var english = economy.start(ENGLISH, CIRCUIT, NOW);
        assertNotEquals(french.attemptId(), english.attemptId());
        assertEquals(2, economy.wallet(NOW).freeTickets());
        assertTrue(economy.saveDraft(FRENCH, CIRCUIT, french.attemptId(), 0, "draft"));
        assertNull(economy.completeRound(FRENCH, CIRCUIT, french.attemptId(), 1, 80));

        var restored = new BonusEconomy(store);
        assertEquals(french, restored.start(FRENCH, "new-circuit", NOW));
        assertEquals(english, restored.start(ENGLISH, CIRCUIT, NOW));
        assertEquals("draft", restored.draft(FRENCH, CIRCUIT, french.attemptId(), 0));
        assertEquals("", restored.draft(ENGLISH, CIRCUIT, english.attemptId(), 0));
        assertEquals(2, restored.wallet(NOW).freeTickets());
        var round = restored.completeRound(FRENCH, CIRCUIT, french.attemptId(), 0, 20);
        assertEquals(List.of(20), round.scores());
        assertEquals(round, restored.completeRound(FRENCH, CIRCUIT, french.attemptId(), 0, 81));
        assertFalse(restored.saveDraft(FRENCH, CIRCUIT, french.attemptId(), 0, "stale"));
        assertEquals("", restored.draft(FRENCH, CIRCUIT, french.attemptId(), 1));
        assertEquals(List.of(), restored.active(ENGLISH).scores());
        complete(restored, FRENCH, round);
        assertTrue(restored.active(FRENCH).complete());
        assertEquals(37, restored.active(FRENCH).totalScore());
        var replay = restored.start(FRENCH, CIRCUIT, NOW);
        assertNotEquals(french.attemptId(), replay.attemptId());
        assertNull(restored.completeRound(FRENCH, CIRCUIT, french.attemptId(), 0, 100));
        assertFalse(restored.saveDraft(FRENCH, CIRCUIT, french.attemptId(), 0, "wrong attempt"));
    }

    @Test public void sourcesAreClaimedOncePerLanguageAndSharedCoinsBuyOneThemeOrTicket() {
        var store = new MemoryStore();
        var economy = new BonusEconomy(store);
        for (GameLanguage language : GameLanguage.values()) {
            assertTrue(economy.daily(language, TODAY));
            assertFalse(economy.daily(language, TODAY));
            assertTrue(economy.puzzle(language, "fork"));
            assertFalse(economy.puzzle(language, "fork"));
            assertTrue(economy.tournament(language, "26"));
            assertFalse(economy.tournament(language, "26"));
        }
        assertEquals(300, economy.wallet(NOW).coins());
        assertFalse(economy.unlockTheme("poppy"));
        assertFalse(economy.unlockTheme("unknown"));
        assertTrue(economy.unlockTheme("indigo"));
        var restored = new BonusEconomy(store);
        assertTrue(restored.themeUnlocked("indigo"));
        assertTrue(restored.unlockTheme("indigo"));
        assertEquals(50, restored.wallet(NOW).coins());
        assertFalse(restored.exchange());
        assertTrue(restored.puzzle(ENGLISH, "corner"));
        assertTrue(restored.exchange());
        assertEquals(0, restored.wallet(NOW).coins());
        assertEquals(1, restored.wallet(NOW).bonusTickets());
        assertEquals(3, restored.wallet(NOW).freeTickets());
    }

    @Test public void videoAndReferralSnapshotsCanOnlyIncreaseTheWalletOnce() {
        var store = new MemoryStore();
        var economy = new BonusEconomy(store);
        assertTrue(economy.videoAvailable(TODAY));
        assertTrue(economy.video(TODAY));
        assertFalse(economy.video(TODAY));
        assertFalse(economy.videoAvailable(TODAY.minusDays(1)));
        assertTrue(economy.video(TODAY.plusDays(1)));
        assertFalse(economy.video(TODAY));
        assertTrue(economy.referrals(FRENCH, "v1", "player", 3));
        assertFalse(economy.referrals(FRENCH, "v1", "player", 3));
        assertFalse(economy.referrals(FRENCH, "v1", "player", 2));
        var restored = new BonusEconomy(store);
        assertTrue(restored.referrals(FRENCH, "v1", "player", 5));
        assertTrue(restored.referrals(ENGLISH, "v1", "player", 1));
        assertEquals(8, restored.wallet(NOW).bonusTickets());
        assertEquals(3, restored.wallet(NOW).freeTickets());
    }

    @Test public void reservedBonusTicketsAreSpentOnlyWhenTheFreeReserveIsEmpty() {
        var economy = new BonusEconomy(new MemoryStore());
        assertTrue(economy.video(TODAY));
        for (int i = 0; i < 4; i++) {
            complete(economy, ENGLISH, economy.start(ENGLISH, CIRCUIT, NOW));
            assertEquals(1, economy.wallet(NOW).bonusTickets());
        }
        var extra = economy.start(ENGLISH, CIRCUIT, NOW);
        assertEquals(0, economy.wallet(NOW).bonusTickets());
        assertEquals(extra, economy.start(ENGLISH, CIRCUIT, NOW));
        complete(economy, ENGLISH, extra);
        assertNull(economy.start(ENGLISH, CIRCUIT, NOW));
        assertEquals(1, economy.wallet(NOW + BonusEconomy.REFILL_MILLIS).freeTickets());
    }

    @Test public void failedWritesCannotSpendOrGrantAndCanBeRetried() {
        var store = new MemoryStore();
        var economy = new BonusEconomy(store);
        store.failWrites = true;
        assertNull(economy.start(FRENCH, CIRCUIT, NOW));
        assertFalse(economy.video(TODAY));
        assertFalse(economy.referrals(FRENCH, "v1", "player", 2));
        assertTrue(economy.wallet(NOW).freeEntry());
        store.failWrites = false;
        assertTrue(economy.video(TODAY));
        assertTrue(economy.referrals(FRENCH, "v1", "player", 2));
        assertEquals(3, economy.wallet(NOW).bonusTickets());
        assertEquals(0, economy.wallet(NOW).coins());
    }

    @Test public void simultaneousInstancesCanOnlyChargeOrGrantOnce() throws Exception {
        var store = new MemoryStore();
        var pool = Executors.newFixedThreadPool(4);
        try {
            var calls = List.of(
                    pool.submit(() -> new BonusEconomy(store).start(FRENCH, CIRCUIT, NOW)),
                    pool.submit(() -> new BonusEconomy(store).start(FRENCH, CIRCUIT, NOW)));
            assertEquals(calls.get(0).get(), calls.get(1).get());
            var claims = List.of(
                    pool.submit(() -> new BonusEconomy(store).video(TODAY)),
                    pool.submit(() -> new BonusEconomy(store).video(TODAY)));
            assertNotEquals(claims.get(0).get(), claims.get(1).get());
            assertEquals(new BonusEconomy.Wallet(0, 3, 1, false, 0), new BonusEconomy(store).wallet(NOW));
        } finally {
            pool.shutdownNow();
        }
    }

    private static void complete(BonusEconomy economy, GameLanguage language, BonusEconomy.Circuit run) {
        for (int round = run.round(); round < BonusEconomy.ROUND_COUNT; round++) {
            assertTrue(economy.completeRound(language, run.id(), run.attemptId(), round, 7 + round).round() > round);
        }
    }

    private static final class MemoryStore implements BonusEconomy.Store {
        byte[] data;
        boolean failWrites;

        @Override public byte[] read() { return data; }

        @Override public void write(byte[] data) throws IOException {
            if (failWrites) throw new IOException("No storage");
            this.data = data;
        }
    }
}
