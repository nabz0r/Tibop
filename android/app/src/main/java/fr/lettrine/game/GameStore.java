package fr.lettrine.game;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class GameStore {
    static final int BASE_ELO = 1100;
    static final Object STORE_LOCK = new Object();
    private static long historyRevision;
    private final AtomicFile currentFile;
    private final AtomicFile savedGamesFile;
    private final AtomicFile historyFile;
    private final ArrayList<JSONObject> history = new ArrayList<>();
    private long loadedRevision = -1;

    static void invalidateHistory() {
        synchronized (STORE_LOCK) {
            historyRevision++;
        }
    }

    GameStore(Context context, GameLanguage language) throws IOException, JSONException {
        this(language == GameLanguage.FRENCH ? context.getFilesDir()
                : context.getDir("matches-en", Context.MODE_PRIVATE));
    }

    GameStore(File filesDir) throws IOException, JSONException {
        currentFile = new AtomicFile(new File(filesDir, "current-match.json"));
        savedGamesFile = new AtomicFile(new File(filesDir, "saved-matches.json"));
        historyFile = new AtomicFile(new File(filesDir, "history.json"));
        synchronized (STORE_LOCK) {
            refreshHistory();
            migrateCurrent();
        }
    }

    private void migrateCurrent() throws IOException, JSONException {
        if (read(savedGamesFile) != null) return;
        JSONArray rows = new JSONArray();
        String legacy = read(currentFile);
        if (legacy != null) {
            JSONObject row = new JSONObject(legacy);
            if (!archived(id(row))) rows.put(row);
        }
        // An empty list also marks migration complete, even after removing the last slot.
        write(savedGamesFile, rows.toString());
    }

    List<JSONObject> savedGames() throws IOException, JSONException {
        synchronized (STORE_LOCK) {
            refreshHistory();
            JSONArray rows = readSavedGames();
            List<JSONObject> games = new ArrayList<>(rows.length());
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                if (!archived(id(row))) games.add(row);
            }
            return Collections.unmodifiableList(games);
        }
    }

    JSONObject loadGame(String gameId) throws IOException, JSONException {
        for (JSONObject row : savedGames()) {
            if (gameId.equals(id(row))) return row;
        }
        return null;
    }

    void saveGame(JSONObject row) throws IOException, JSONException {
        String gameId = id(row);
        synchronized (STORE_LOCK) {
            refreshHistory();
            if (archived(gameId)) return;
            JSONArray remaining = withoutGame(readSavedGames(), gameId);
            JSONArray rows = new JSONArray();
            rows.put(row);
            for (int i = 0; i < remaining.length(); i++) rows.put(remaining.getJSONObject(i));
            write(savedGamesFile, rows.toString());
        }
    }

    void removeGame(String gameId) throws IOException, JSONException {
        synchronized (STORE_LOCK) {
            JSONArray existing = readSavedGames();
            JSONArray remaining = withoutGame(existing, gameId);
            if (existing.length() != remaining.length()) write(savedGamesFile, remaining.toString());
        }
    }

    private JSONArray readSavedGames() throws IOException, JSONException {
        return new JSONArray(new String(savedGamesFile.readFully(), StandardCharsets.UTF_8));
    }

    private static JSONArray withoutGame(JSONArray rows, String gameId) throws JSONException {
        JSONArray remaining = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (!gameId.equals(id(row))) remaining.put(row);
        }
        return remaining;
    }

    private static String id(JSONObject row) throws JSONException {
        if (row.opt("id") instanceof String id && !id.trim().isEmpty()) return id;
        throw new JSONException("Identifiant de partie manquant.");
    }

    private boolean archived(String gameId) {
        for (JSONObject row : history) {
            if (gameId.equals(row.optString("id"))) return true;
        }
        return false;
    }

    List<JSONObject> history() {
        synchronized (STORE_LOCK) {
            refreshHistoryForDisplay();
            return Collections.unmodifiableList(new ArrayList<>(history));
        }
    }

    int elo() {
        synchronized (STORE_LOCK) {
            refreshHistoryForDisplay();
            return latestRating();
        }
    }

    private int latestRating() {
        for (int i = history.size() - 1; i >= 0; i--) {
            int value = history.get(i).optInt("elo_after");
            if (value > 0) return value;
        }
        return BASE_ELO;
    }

    static int adjustedRating(int before, int opponent, int comparison) {
        double expected = 1.0 / (1.0 + Math.pow(10, (opponent - before) / 400.0));
        double actual = comparison > 0 ? 1 : comparison == 0 ? 0.5 : 0;
        return Math.max(100, before + (int) Math.round(24 * (actual - expected)));
    }

    void archive(JSONObject row, int opponent, int comparison) throws IOException, JSONException {
        synchronized (STORE_LOCK) {
            refreshHistory();
            if (archived(id(row))) return;
            if (opponent > 0) {
                int before = latestRating();
                row.put("elo_before", before);
                row.put("elo_after", adjustedRating(before, opponent, comparison));
                row.put("opponent_reference_elo", opponent);
            }
            JSONArray serialized = new JSONArray(history);
            serialized.put(row);
            write(historyFile, serialized.toString());
            history.add(row);
            loadedRevision = ++historyRevision;
        }
    }

    private void refreshHistory() throws IOException, JSONException {
        if (loadedRevision == historyRevision) return;
        String serialized = read(historyFile);
        ArrayList<JSONObject> rows = new ArrayList<>();
        if (serialized != null) {
            JSONArray saved = new JSONArray(serialized);
            for (int i = 0; i < saved.length(); i++) rows.add(saved.getJSONObject(i));
        }
        history.clear();
        history.addAll(rows);
        loadedRevision = historyRevision;
    }

    private void refreshHistoryForDisplay() {
        try {
            refreshHistory();
        } catch (IOException | JSONException ignored) {
            // The last readable snapshot can still be displayed. Mutations require a successful refresh.
        }
    }

    private static String read(AtomicFile file) throws IOException {
        try {
            return new String(file.readFully(), StandardCharsets.UTF_8);
        } catch (FileNotFoundException exception) {
            if (file.getBaseFile().exists()) throw exception;
            return null;
        }
    }

    private static void write(AtomicFile file, String text) throws IOException {
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(text.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch (IOException | RuntimeException exception) {
            file.failWrite(stream);
            throw exception;
        }
    }
}
