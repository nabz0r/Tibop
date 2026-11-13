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
        SkinStore.Selection selected = SkinStore.selected("TIDE", "AMBER", earned);
        assertEquals(SkinStore.BoardSkin.TIDE, selected.board());
        assertEquals(SkinStore.TileSkin.AMBER, selected.tiles());
        assertEquals(new SkinStore.Selection(SkinStore.BoardSkin.TIDE, null),
                SkinStore.selected("TIDE", "CLASSIC", earned));
        assertEquals(new SkinStore.Selection(null, SkinStore.TileSkin.AMBER),
                SkinStore.selected("CLASSIC", "AMBER", earned));
        assertNull(SkinStore.selected("SLATE", "UNKNOWN", earned).board());
        assertNull(SkinStore.selected("UNKNOWN", "PORCELAIN", 0).tiles());
    }
}
