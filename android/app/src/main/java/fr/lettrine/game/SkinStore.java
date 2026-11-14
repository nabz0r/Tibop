package fr.lettrine.game;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.time.LocalDate;
import java.util.List;

final class SkinStore {
    enum Reward {
        INCLUDED(0, 0, false), FIRST_DAY(1, 1, true), THREE_DAYS(2, 3, true),
        THREE_GAMES(4, 3, false), FIVE_GAMES(8, 5, false);

        final int bit;
        final int target;
        final boolean daily;

        Reward(int bit, int target, boolean daily) {
            this.bit = bit;
            this.target = target;
            this.daily = daily;
        }

        int count(Progress progress) { return daily ? progress.dailyDays() : progress.soloGames(); }
    }

    enum BoardSkin {
        CLASSIC(Reward.INCLUDED, R.string.skins_classic, R.string.skins_board_classic,
                0, 0, 0, 0, 0, 0, 0, 0, 0),
        TIDE(Reward.THREE_DAYS, R.string.skins_tide, R.string.skins_board_tide,
                0xff193645, 0xffe8f4f3, 0xff142f3e, 0xffdbe8e4, 0xffb1d4dc, 0xff85b6cb,
                0xffe8cebe, 0xffd69b82, 0xff326679),
        SLATE(Reward.FIVE_GAMES, R.string.skins_slate, R.string.skins_board_slate,
                0xff2d2636, 0xfff6ebf3, 0xff352c40, 0xffdcdbe4, 0xffbcd3dc, 0xff9daacb,
                0xffe2bdd0, 0xffdba1aa, 0xff624873),
        SAKURA(Reward.THREE_DAYS, R.string.skins_sakura, R.string.skins_board_sakura,
                0xff943768, 0xfffff5fb, 0xff52183f, 0xffffedf5, 0xffd7eedf, 0xffacdbee,
                0xffffc5df, 0xfff597be, 0xff97306c),
        SUNRISE(Reward.INCLUDED, R.string.skins_sunrise, R.string.skins_board_sunrise,
                0xff4f305d, 0xfffff1e2, 0xff3f253e, 0xffffeddd, 0xffc9eedc, 0xffc8ddff,
                0xffffc99e, 0xfff49eb2, 0xff8d365a),
        LAGOON(Reward.THREE_GAMES, R.string.skins_lagoon, R.string.skins_board_lagoon,
                0xff14575e, 0xffeafff8, 0xff133742, 0xffe2f6ee, 0xffa0e6cb, 0xff98d4f7,
                0xfff5d8a8, 0xffefb6b1, 0xff22696c);

        final Reward reward;
        final int name;
        final int detail;
        private final int frame;
        private final int coordinates;
        private final int cellInk;
        private final int ordinary;
        private final int doubleLetter;
        private final int tripleLetter;
        private final int doubleWord;
        private final int tripleWord;
        private final int focus;

        BoardSkin(Reward reward, int name, int detail, int frame, int coordinates, int cellInk, int ordinary,
                  int doubleLetter, int tripleLetter, int doubleWord, int tripleWord, int focus) {
            this.reward = reward;
            this.name = name;
            this.detail = detail;
            this.frame = frame;
            this.coordinates = coordinates;
            this.cellInk = cellInk;
            this.ordinary = ordinary;
            this.doubleLetter = doubleLetter;
            this.tripleLetter = tripleLetter;
            this.doubleWord = doubleWord;
            this.tripleWord = tripleWord;
            this.focus = focus;
        }

        int frame() { return frame; }
        int coordinates() { return coordinates; }
        int cellInk() { return cellInk; }
        int ordinary() { return ordinary; }
        int doubleLetter() { return doubleLetter; }
        int tripleLetter() { return tripleLetter; }
        int doubleWord() { return doubleWord; }
        int tripleWord() { return tripleWord; }
        int focus() { return focus; }
    }

    enum TileSkin {
        CLASSIC(Reward.INCLUDED, R.string.skins_classic, R.string.skins_tile_classic, 0, 0, 0, 0),
        PORCELAIN(Reward.FIRST_DAY, R.string.skins_porcelain, R.string.skins_tile_porcelain,
                0xffebf5f3, 0xff78969e, 0xffb3dadd, 0xff1c3d4a),
        AMBER(Reward.THREE_GAMES, R.string.skins_amber, R.string.skins_tile_amber,
                0xfff3c56d, 0xff8a5c27, 0xffe2e8a4, 0xff452b1f),
        RAMUNE(Reward.FIRST_DAY, R.string.skins_ramune, R.string.skins_tile_ramune,
                0xffd9fff6, 0xff428f83, 0xffa9e6ff, 0xff184254),
        SUNRISE(Reward.INCLUDED, R.string.skins_sunrise, R.string.skins_tile_sunrise,
                0xffffdabe, 0xffa95c78, 0xffffb9ca, 0xff481d37),
        MIDNIGHT(Reward.FIVE_GAMES, R.string.skins_midnight, R.string.skins_tile_midnight,
                0xff382957, 0xffb7a7f5, 0xff51396f, 0xfffff1d2);

        final Reward reward;
        final int name;
        final int detail;
        private final int face;
        private final int edge;
        private final int fresh;
        private final int ink;

        TileSkin(Reward reward, int name, int detail, int face, int edge, int fresh, int ink) {
            this.reward = reward;
            this.name = name;
            this.detail = detail;
            this.face = face;
            this.edge = edge;
            this.fresh = fresh;
            this.ink = ink;
        }

        int face() { return face; }
        int edge() { return edge; }
        int fresh() { return fresh; }
        int ink() { return ink; }
    }

    enum RackSkin {
        CLASSIC(Reward.INCLUDED, R.string.skins_classic, R.string.skins_rack_classic, 0, 0),
        MAPLE(Reward.INCLUDED, R.string.skins_maple, R.string.skins_rack_maple, 0xffd9a16f, 0xff805236),
        LAGOON(Reward.FIRST_DAY, R.string.skins_lagoon, R.string.skins_rack_lagoon, 0xff70bdb4, 0xff256560),
        MIDNIGHT(Reward.FIVE_GAMES, R.string.skins_midnight, R.string.skins_rack_midnight, 0xff32224d, 0xff8069a3);

        final Reward reward;
        final int name;
        final int detail;
        private final int face;
        private final int edge;

        RackSkin(Reward reward, int name, int detail, int face, int edge) {
            this.reward = reward;
            this.name = name;
            this.detail = detail;
            this.face = face;
            this.edge = edge;
        }

        int face() { return face; }
        int edge() { return edge; }
    }

    record Progress(int dailyDays, int soloGames) {}
    record Selection(BoardSkin board, TileSkin tiles, RackSkin rack) {}
    record Inventory(Progress progress, int unlocked, Selection selection) {
        boolean has(Reward reward) { return isUnlocked(reward, unlocked); }
    }

    private static final String BOARD = "board";
    private static final String TILES = "tiles";
    private static final String RACK = "rack";
    private static final String UNLOCKED = "unlocked";
    private final SharedPreferences preferences;

    SkinStore(Context context) { preferences = context.getSharedPreferences("skin_collection", Context.MODE_PRIVATE); }

    Selection selection() {
        return selected(preferences.getString(BOARD, ""), preferences.getString(TILES, ""),
                preferences.getString(RACK, ""), preferences.getInt(UNLOCKED, 0));
    }

    Inventory refresh(GameStore games, DailyProgress daily) {
        int days = daily.achievements(LocalDate.now()).days();
        Progress progress = new Progress(days, countSolo(games == null ? List.of() : games.history()));
        int before = preferences.getInt(UNLOCKED, 0);
        int unlocked = earn(before, progress);
        if (unlocked != before) preferences.edit().putInt(UNLOCKED, unlocked).apply();
        return new Inventory(progress, unlocked, selection());
    }

    boolean equip(BoardSkin skin) { return equip(BOARD, skin.name(), skin.reward); }

    boolean equip(TileSkin skin) { return equip(TILES, skin.name(), skin.reward); }

    boolean equip(RackSkin skin) {
        return skin == RackSkin.CLASSIC ? preferences.edit().remove(RACK).commit()
                : equip(RACK, skin.name(), skin.reward);
    }

    private boolean equip(String category, String skin, Reward reward) {
        if (!isUnlocked(reward, preferences.getInt(UNLOCKED, 0))) return false;
        return preferences.edit().putString(category, skin).commit();
    }

    private static int countSolo(List<JSONObject> history) {
        int count = 0;
        for (JSONObject game : history) {
            if ("solo".equals(game.optString("mode"))) count++;
        }
        return count;
    }

    static int earn(int unlocked, Progress progress) {
        for (Reward reward : Reward.values()) {
            if (reward.count(progress) >= reward.target) unlocked |= reward.bit;
        }
        return unlocked;
    }

    static Selection selected(String boardName, String tileName, String rackName, int unlocked) {
        BoardSkin board = null;
        TileSkin tiles = null;
        RackSkin rack = null;
        for (BoardSkin skin : BoardSkin.values()) {
            if (skin != BoardSkin.CLASSIC && skin.name().equals(boardName) && isUnlocked(skin.reward, unlocked)) board = skin;
        }
        for (TileSkin skin : TileSkin.values()) {
            if (skin != TileSkin.CLASSIC && skin.name().equals(tileName) && isUnlocked(skin.reward, unlocked)) tiles = skin;
        }
        for (RackSkin skin : RackSkin.values()) {
            if (skin != RackSkin.CLASSIC && skin.name().equals(rackName) && isUnlocked(skin.reward, unlocked)) rack = skin;
        }
        return new Selection(board, tiles, rack);
    }

    private static boolean isUnlocked(Reward reward, int unlocked) {
        return reward.bit == 0 || (unlocked & reward.bit) != 0;
    }
}
