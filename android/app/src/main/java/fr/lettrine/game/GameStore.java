package fr.lettrine.game;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class GameStore {
    static final int BASE_ELO = 1100;
    private final AtomicFile currentFile;
    private final AtomicFile historyFile;
    private final ArrayList<JSONObject> history = new ArrayList<>();

    GameStore(Context context) throws IOException, JSONException {
        currentFile = new AtomicFile(new File(context.getFilesDir(), "current-match.json"));
        historyFile = new AtomicFile(new File(context.getFilesDir(), "history.json"));
        if (historyFile.getBaseFile().isFile()) {
            JSONArray rows = new JSONArray(new String(historyFile.readFully(), StandardCharsets.UTF_8));
            for (int i = 0; i < rows.length(); i++) history.add(rows.getJSONObject(i));
        }
    }

    JSONObject current() throws IOException, JSONException {
        if (!currentFile.getBaseFile().isFile()) return null;
        return new JSONObject(new String(currentFile.readFully(), StandardCharsets.UTF_8));
    }

    void current(JSONObject row) throws IOException {
        write(currentFile, row.toString());
    }

    void clearCurrent() { currentFile.delete(); }

    List<JSONObject> history() { return Collections.unmodifiableList(history); }

    int elo() {
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

    void archive(JSONObject row) throws IOException {
        String id = row.optString("id");
        for (JSONObject previous : history) {
            if (id.equals(previous.optString("id"))) return;
        }
        JSONArray serialized = new JSONArray(history);
        serialized.put(row);
        write(historyFile, serialized.toString());
        history.add(row);
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
