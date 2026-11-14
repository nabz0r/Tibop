package fr.lettrine.game;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class NabCampaignProgress {
    static final String PREFS = "nab_campaign_v1";
    static final Object LOCK = new Object();
    static final int MAX_SCORE = 9_999;
    static final int MAX_DRAFT_LENGTH = 1_024;

    private record Result(int stars, int score) {}

    static final class Snapshot {
        private final List<Result> results;

        private Snapshot(List<Result> results) {
            this.results = List.copyOf(results);
        }

        int stars(int number) { return unlocked(number) && number <= completed() ? results.get(number - 1).stars() : 0; }
        int bestScore(int number) { return unlocked(number) && number <= completed() ? results.get(number - 1).score() : 0; }
        int completed() { return results.size(); }
        boolean unlocked(int number) { return validStage(number) && number <= completed() + 1; }
        int nextStage() { return completed() == NabCampaign.STAGE_COUNT ? 0 : completed() + 1; }
        int workshopLevel() { return completed() / NabCampaign.CHAPTER_SIZE; }
        int totalStars() { return results.stream().mapToInt(Result::stars).sum(); }
    }

    private final SharedPreferences preferences;

    NabCampaignProgress(Context context, GameLanguage language) {
        preferences = context.getSharedPreferences(language.storageName(PREFS), Context.MODE_PRIVATE);
    }

    Snapshot snapshot() {
        synchronized (LOCK) {
            return summarize(preferences.getAll());
        }
    }

    Snapshot complete(NabCampaign.Stage stage, int stars, int score) throws IOException {
        int number = stage.number();
        synchronized (LOCK) {
            Map<String, ?> before = preferences.getAll();
            Snapshot current = summarize(before);
            if (!current.unlocked(number) || stars < 1 || stars > 3 || score < stage.passScore()
                    || score > MAX_SCORE || stars != (score >= stage.goldScore() ? 3
                            : score >= stage.silverScore() ? 2 : 1)) {
                throw new IllegalArgumentException("Résultat de campagne invalide.");
            }
            int storedStars = Math.max(stars, current.stars(number));
            int storedScore = Math.max(score, current.bestScore(number));
            String starsKey = key("stars", number);
            String scoreKey = key("score", number);
            String draftKey = key("draft", number);
            String lexiconKey = key("lexicon", number);
            if (!preferences.edit().putInt(starsKey, storedStars).putInt(scoreKey, storedScore)
                    .remove(draftKey).remove(lexiconKey).commit()) {
                // A failed commit updates memory before the disk error is reported.
                SharedPreferences.Editor rollback = preferences.edit();
                for (String changed : List.of(starsKey, scoreKey, draftKey, lexiconKey)) {
                    Object previous = before.get(changed);
                    if (previous instanceof Integer value) rollback.putInt(changed, value);
                    else if (previous instanceof String value) rollback.putString(changed, value);
                    else rollback.remove(changed);
                }
                rollback.apply();
                throw new IOException("La progression de l’atelier n’a pas pu être enregistrée.");
            }
            return summarize(preferences.getAll());
        }
    }

    String draft(int number, String lexiconHash) {
        synchronized (LOCK) {
            if (!validStage(number) || !lexiconHash.equals(preferences.getString(key("lexicon", number), ""))) return null;
            return preferences.getString(key("draft", number), null);
        }
    }

    void saveDraft(int number, String lexiconHash, String payload) {
        synchronized (LOCK) {
            if (!summarize(preferences.getAll()).unlocked(number)) return;
            String draftKey = key("draft", number);
            String lexiconKey = key("lexicon", number);
            if (payload == null || payload.isEmpty()) {
                preferences.edit().remove(draftKey).remove(lexiconKey).apply();
                return;
            }
            if (payload.length() > MAX_DRAFT_LENGTH || !lexiconHash.matches("[0-9a-f]{64}")) return;
            try {
                var moves = new JSONArray(payload);
                if (moves.length() != 2) return;
                var first = MatchCodec.readTiles(moves.getJSONArray(0), true);
                var pending = MatchCodec.readTiles(moves.getJSONArray(1), true);
                if (first.isEmpty() && pending.isEmpty()) {
                    preferences.edit().remove(draftKey).remove(lexiconKey).apply();
                    return;
                }
            } catch (JSONException | IllegalArgumentException invalid) {
                return;
            }
            preferences.edit().putString(draftKey, payload).putString(lexiconKey, lexiconHash).apply();
        }
    }

    static boolean validStage(int number) { return number >= 1 && number <= NabCampaign.STAGE_COUNT; }

    static String key(String prefix, int number) { return prefix + "/" + number; }

    static Snapshot summarize(Map<String, ?> values) {
        List<Result> results = new ArrayList<>(NabCampaign.STAGE_COUNT);
        for (int number = 1; number <= NabCampaign.STAGE_COUNT; number++) {
            if (!(values.get(key("stars", number)) instanceof Integer stars) || stars < 1 || stars > 3
                    || !(values.get(key("score", number)) instanceof Integer score) || score < 1
                    || score > MAX_SCORE) break;
            results.add(new Result(stars, score));
        }
        return new Snapshot(results);
    }
}
