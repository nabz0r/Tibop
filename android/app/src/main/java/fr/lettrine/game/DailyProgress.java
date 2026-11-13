package fr.lettrine.game;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

final class DailyProgress {
    record Snapshot(int streak, Integer bestScore) {}

    private final SharedPreferences preferences;

    DailyProgress(Context context) {
        preferences = context.getSharedPreferences("daily_progress", Context.MODE_PRIVATE);
    }

    Snapshot snapshot(LocalDate today) {
        synchronized (preferences) {
            return summarize(preferences.getAll(), today);
        }
    }

    Snapshot complete(LocalDate challengeDate, int score) throws IOException {
        if (score < 0) throw new IllegalArgumentException("Le score du défi doit être positif ou nul.");
        String key = key(challengeDate);
        synchronized (preferences) {
            int previous = preferences.getInt(key, -1);
            if (score > previous && !preferences.edit().putInt(key, score).commit()) {
                // SharedPreferences updates memory before committing to disk.
                SharedPreferences.Editor restore = preferences.edit();
                if (previous < 0) restore.remove(key);
                else restore.putInt(key, previous);
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

    private static Integer score(Map<String, ?> saved, long day) {
        return saved.get(Long.toString(day)) instanceof Integer score && score >= 0 ? score : null;
    }
}
