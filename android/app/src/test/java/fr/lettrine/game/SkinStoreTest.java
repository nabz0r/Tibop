package fr.lettrine.game;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SkinStoreTest {
    @Test public void eachSkinUnlocksAtItsOwnThreshold() {
        int fresh = SkinStore.earn(0, new SkinStore.Progress(0, 0));
        assertEquals(0, fresh);
        assertEquals(SkinStore.Reward.FIRST_DAY.bit,
                SkinStore.earn(fresh, new SkinStore.Progress(1, 2)));
        assertEquals(SkinStore.Reward.FIRST_DAY.bit | SkinStore.Reward.THREE_GAMES.bit,
                SkinStore.earn(fresh, new SkinStore.Progress(2, 4)));
        assertEquals(SkinStore.Reward.FIRST_DAY.bit | SkinStore.Reward.THREE_DAYS.bit
                        | SkinStore.Reward.THREE_GAMES.bit | SkinStore.Reward.FIVE_GAMES.bit,
                SkinStore.earn(fresh, new SkinStore.Progress(3, 5)));
    }

    @Test public void earnedSkinsStayUnlockedAndEquipIndependently() {
        int earned = SkinStore.earn(0, new SkinStore.Progress(3, 3));
        assertEquals(earned, SkinStore.earn(earned, new SkinStore.Progress(0, 0)));
        SkinStore.Selection selected = SkinStore.selected("TIDE", "AMBER", "LAGOON", earned);
        assertEquals(SkinStore.BoardSkin.TIDE, selected.board());
        assertEquals(SkinStore.TileSkin.AMBER, selected.tiles());
        assertEquals(SkinStore.RackSkin.LAGOON, selected.rack());
        assertEquals(new SkinStore.Selection(SkinStore.BoardSkin.TIDE, null, SkinStore.RackSkin.LAGOON),
                SkinStore.selected("TIDE", "CLASSIC", "LAGOON", earned));
        assertEquals(new SkinStore.Selection(null, SkinStore.TileSkin.AMBER, null),
                SkinStore.selected("CLASSIC", "AMBER", "CLASSIC", earned));
        assertEquals(new SkinStore.Selection(SkinStore.BoardSkin.TIDE, SkinStore.TileSkin.AMBER, null),
                SkinStore.selected("TIDE", "AMBER", "", earned));
        assertNull(SkinStore.selected("SLATE", "UNKNOWN", "MIDNIGHT", earned).board());
        assertNull(SkinStore.selected("UNKNOWN", "PORCELAIN", "MIDNIGHT", 0).tiles());
        assertNull(SkinStore.selected("TIDE", "AMBER", "MIDNIGHT", earned).rack());
    }

    @Test public void includedAlternativesCanBeEquippedBeforeAnyRewards() {
        assertEquals(new SkinStore.Selection(SkinStore.BoardSkin.SUNRISE, SkinStore.TileSkin.SUNRISE,
                        SkinStore.RackSkin.MAPLE), SkinStore.selected("SUNRISE", "SUNRISE", "MAPLE", 0));
    }
}
