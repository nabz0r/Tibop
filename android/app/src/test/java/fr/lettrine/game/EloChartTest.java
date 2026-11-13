package fr.lettrine.game;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class EloChartTest {
    @Test public void assistedAndNonSoloGamesDoNotAppear() {
        assertNull(EloChart.Point.from("local", false, 1100, 1112, 1000, 3, 1300));
        assertNull(EloChart.Point.from("bluetooth", false, 1100, 1112, 1000, 3, 1300));
        assertNull(EloChart.Point.from("solo", true, 1100, 1112, 1000, 3, 1300));
        assertNull(EloChart.Point.from("solo", false, 0, 1112, 1000, 3, 1300));
        assertNull(EloChart.Point.from("solo", false, 1100, 0, 1000, 3, 1300));
    }

    @Test public void ratedGamesRetainHistoricalOpponentAndEvenRating() {
        var point = EloChart.Point.from("solo", false, 1300, 1300, 1234, Bot.ADAPTIVE, 1200);
        assertEquals(new EloChart.Point(1300, 1300, 1234, Bot.ADAPTIVE, 1200), point);
        assertEquals(new EloChart.Point(1100, 1080, 0, 0, 0),
                EloChart.Point.from("solo", false, 1100, 1080, 0, 0, 0));
    }
}
