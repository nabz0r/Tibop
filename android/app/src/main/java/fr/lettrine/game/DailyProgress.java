package fr.lettrine.game;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

final class DailyProgress {
    record Snapshot(int streak, Integer bestScore) {}
    record Achievements(int days, int longestStreak) {}

    private final SharedPreferences preferences;

    DailyProgress(Context context, GameLanguage language) {
        preferences = context.getSharedPreferences(language.storageName("daily_progress"), Context.MODE_PRIVATE);
    }

    Snapshot snapshot(LocalDate today) {
        synchronized (preferences) {
            return summarize(preferences.getAll(), today);
        }
    }

    String recordedLexicon(LocalDate date) {
        String scoreKey = key(date);
        synchronized (preferences) {
            return preferences.contains(scoreKey) ? preferences.getString("lexicon/" + scoreKey, "") : null;
        }
    }

    Achievements achievements(LocalDate today) {
        synchronized (preferences) {
            return achievements(preferences.getAll(), today);
        }
    }

    Snapshot complete(LocalDate challengeDate, int score, String lexicon) throws IOException {
        if (score < 0) throw new IllegalArgumentException("Le score du défi doit être positif ou nul.");
        String key = key(challengeDate);
        String lexiconKey = "lexicon/" + key;
        synchronized (preferences) {
            int previous = preferences.getInt(key, -1);
            String previousLexicon = preferences.getString(lexiconKey, null);
            SharedPreferences.Editor editor = preferences.edit().putInt(key, score);
            if (lexicon.isEmpty()) editor.remove(lexiconKey);
            else editor.putString(lexiconKey, lexicon);
            if (score > previous && !editor.commit()) {
                // SharedPreferences updates memory before committing to disk.
                SharedPreferences.Editor restore = preferences.edit();
                if (previous < 0) restore.remove(key);
                else restore.putInt(key, previous);
                if (previousLexicon == null) restore.remove(lexiconKey);
                else restore.putString(lexiconKey, previousLexicon);
                restore.apply();
                throw new IOException("Le score du défi n’a pas pu être enregistré.");
            }
            return summarize(preferences.getAll(), challengeDate);
        }
    }

    private static String key(LocalDate date) { return Long.toString(date.toEpochDay()); }

    static Snapshot summarize(Map<String, ?> saved, LocalDate today) {
        long todayKey = today.toEpochDay();
        Integer best = score(saved, todayKey);
        long start = best == null ? todayKey - 1 : todayKey;
        int streak = 0;
        for (long day = start; day >= LocalDate.MIN.toEpochDay() && score(saved, day) != null; day--) {
            streak++;
        }
        return new Snapshot(streak, best);
    }

    static Achievements achievements(Map<String, ?> saved, LocalDate today) {
        long min = LocalDate.MIN.toEpochDay();
        long max = today.toEpochDay();
        var days = new ArrayList<Long>();
        for (Map.Entry<String, ?> entry : saved.entrySet()) {
            if (!(entry.getValue() instanceof Integer points) || points < 0) continue;
            try {
                long day = Long.parseLong(entry.getKey());
                if (day >= min && day <= max && Long.toString(day).equals(entry.getKey())) {
                    days.add(day);
                }
            } catch (NumberFormatException ignored) { }
        }
        Collections.sort(days);
        int current = 0;
        int longest = 0;
        long previous = Long.MIN_VALUE;
        for (long day : days) {
            current = day == previous + 1 ? current + 1 : 1;
            longest = Math.max(longest, current);
            previous = day;
        }
        return new Achievements(days.size(), longest);
    }

    private static Integer score(Map<String, ?> saved, long day) {
        return saved.get(Long.toString(day)) instanceof Integer score && score >= 0 ? score : null;
    }
}
