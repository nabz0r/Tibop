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
                0xffe2bdd0, 0xffdba1aa, 0xff624873);

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
                0xfff3c56d, 0xff8a5c27, 0xffe2e8a4, 0xff452b1f);

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

    record Progress(int dailyDays, int soloGames) {}
    record Selection(BoardSkin board, TileSkin tiles) {}
    record Inventory(Progress progress, int unlocked, Selection selection) {
        boolean has(Reward reward) { return reward.bit == 0 || (unlocked & reward.bit) != 0; }
    }

    private static final String BOARD = "board";
    private static final String TILES = "tiles";
    private static final String UNLOCKED = "unlocked";
    private final SharedPreferences preferences;

    SkinStore(Context context) { preferences = context.getSharedPreferences("skin_collection", Context.MODE_PRIVATE); }

    Selection selection() {
        return selected(preferences.getString(BOARD, ""), preferences.getString(TILES, ""),
                preferences.getInt(UNLOCKED, 0));
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

    private boolean equip(String category, String skin, Reward reward) {
        if (reward.bit != 0 && (preferences.getInt(UNLOCKED, 0) & reward.bit) == 0) return false;
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

    static Selection selected(String boardName, String tileName, int unlocked) {
        BoardSkin board = null;
        TileSkin tiles = null;
        for (BoardSkin skin : BoardSkin.values()) {
            if (skin.reward.bit != 0 && (unlocked & skin.reward.bit) != 0 && skin.name().equals(boardName)) board = skin;
        }
        for (TileSkin skin : TileSkin.values()) {
            if (skin.reward.bit != 0 && (unlocked & skin.reward.bit) != 0 && skin.name().equals(tileName)) tiles = skin;
        }
        return new Selection(board, tiles);
    }
}
