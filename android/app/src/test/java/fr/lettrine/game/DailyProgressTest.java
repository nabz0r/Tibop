package fr.lettrine.game;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class DailyProgressTest {
    @Test public void yesterdayKeepsTheStreakUntilADayIsMissed() {
        LocalDate day = LocalDate.of(2026, 11, 13);
        Map<String, Integer> scores = Map.of(key(day.minusDays(1)), 18, key(day), 0);

        assertEquals(new DailyProgress.Snapshot(2, 0), DailyProgress.summarize(scores, day));
        assertEquals(new DailyProgress.Snapshot(2, null), DailyProgress.summarize(scores, day.plusDays(1)));
        assertEquals(new DailyProgress.Snapshot(0, null), DailyProgress.summarize(scores, day.plusDays(2)));
    }

    @Test public void calendarDaysStayAdjacentAcrossLeapDayAndDaylightSavingChanges() {
        LocalDate february28 = LocalDate.of(2028, 2, 28);
        LocalDate march25 = LocalDate.of(2028, 3, 25);
        Map<String, Integer> scores = Map.of(
                key(february28), 15, key(february28.plusDays(1)), 16, key(february28.plusDays(2)), 17,
                key(march25), 20, key(march25.plusDays(1)), 21, key(march25.plusDays(2)), 22);

        assertEquals(new DailyProgress.Snapshot(3, 17), DailyProgress.summarize(scores, LocalDate.of(2028, 3, 1)));
        assertEquals(new DailyProgress.Snapshot(3, 22), DailyProgress.summarize(scores, march25.plusDays(2)));
    }

    @Test public void earlierDateIgnoresFutureEntriesAndUsesItsOwnScore() {
        LocalDate day = LocalDate.of(2026, 11, 13);
        Map<String, Object> scores = Map.of(key(day.minusDays(4)), 12, key(day.minusDays(3)), 13,
                key(day), 25, key(day.plusDays(1)), 28, key(day.plusDays(2)), 31,
                "metadata", 12, key(day.minusDays(2)), "invalid");

        assertEquals(new DailyProgress.Snapshot(1, 25), DailyProgress.summarize(scores, day));
        assertEquals(new DailyProgress.Snapshot(0, null), DailyProgress.summarize(scores, day.minusDays(1)));
        assertEquals(new DailyProgress.Snapshot(3, 31), DailyProgress.summarize(scores, day.plusDays(2)));
        assertEquals(new DailyProgress.Snapshot(0, null), DailyProgress.summarize(scores, day.minusDays(7)));
    }

    private static String key(LocalDate date) { return Long.toString(date.toEpochDay()); }
}
