package fr.lettrine.game;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

final class CardStyleStore {
    enum Style {
        CLASSIC(R.string.skins_classic, R.string.card_style_classic_detail),
        NOCTURNE(R.string.card_style_nocturne, R.string.card_style_nocturne_detail);

        final int label;
        final int detail;

        Style(int label, int detail) {
            this.label = label;
            this.detail = detail;
        }
    }

    private final SharedPreferences preferences;

    CardStyleStore(Context context) {
        preferences = context.getSharedPreferences("share_card", Context.MODE_PRIVATE);
    }

    boolean has(Style style) {
        return style == Style.CLASSIC || preferences.getBoolean("nocturne", false);
    }

    Style selected() {
        return has(Style.NOCTURNE) && preferences.getBoolean("use_nocturne", false)
                ? Style.NOCTURNE : Style.CLASSIC;
    }

    boolean equip(Style style) {
        return has(style) && preferences.edit().putBoolean("use_nocturne", style == Style.NOCTURNE).commit();
    }

    boolean recordTournament(JSONObject tournament) {
        if (has(Style.NOCTURNE) || !confirmed(tournament)) return false;
        return preferences.edit().putBoolean("nocturne", true).putBoolean("use_nocturne", true).commit();
    }

    private static boolean confirmed(JSONObject tournament) {
        if (tournament == null) return false;
        if (tournament.has("card_background_unlocked")) return tournament.optBoolean("card_background_unlocked");
        if (tournament.optInt("completed") != 3) return false;
        JSONArray slots = tournament.optJSONArray("slots");
        if (slots == null || slots.length() != 3) return false;
        for (int round = 1; round <= 3; round++) {
            JSONObject slot = TournamentPanel.slot(slots, round);
            if (slot == null || slot.isNull("score") || slot.optInt("score", -1) < 0) return false;
        }
        return true;
    }
}
