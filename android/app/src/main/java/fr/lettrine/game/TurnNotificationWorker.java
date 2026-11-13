package fr.lettrine.game;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class TurnNotificationWorker extends Worker {
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;

    public TurnNotificationWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        Context context = AppLocale.wrap(getApplicationContext());
        if (!TurnNotifications.enabled(context) || !TurnNotifications.available(context)) return Result.success();
        boolean retry = false;
        for (GameLanguage language : GameLanguage.values()) {
            if (isStopped()) break;
            TurnNotifications.Session session = TurnNotifications.currentSession(context, language);
            if (session != null) retry |= refresh(context, session);
        }
        return retry && getRunAttemptCount() == 0 ? Result.retry() : Result.success();
    }

    private boolean refresh(Context context, TurnNotifications.Session session) {
        long order = TurnNotifications.begin();
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(session.origin()
                    + SocialClient.api(session.language()) + "/turns").toURL().openConnection();
            try {
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(7000);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + session.token());
                int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN) {
                    if (!isStopped()) TurnNotifications.reconcile(context, session, order, List.of());
                    return false;
                }
                if (status == 429 || status >= 500) return true;
                if (status != HttpURLConnection.HTTP_OK) return false;
                List<TurnNotifications.Turn> turns = read(connection);
                if (!isStopped()) TurnNotifications.reconcile(context, session, order, turns);
                return false;
            } finally {
                connection.disconnect();
            }
        } catch (IOException exception) {
            return true;
        } catch (JSONException exception) {
            return false;
        }
    }

    private static List<TurnNotifications.Turn> read(HttpURLConnection connection) throws IOException, JSONException {
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) != -1; ) {
                if (output.size() + count > MAX_RESPONSE_BYTES) throw new JSONException("Liste de tours trop volumineuse");
                output.write(buffer, 0, count);
            }
            JSONArray array = new JSONObject(output.toString(StandardCharsets.UTF_8.name())).getJSONArray("turns");
            List<TurnNotifications.Turn> turns = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.getJSONObject(i);
                String id = row.getString("id");
                String opponent = row.getString("opponent");
                if (!TurnNotifications.isDuelId(id) || opponent.isBlank() || opponent.length() > 100) {
                    throw new JSONException("Tour invalide");
                }
                turns.add(new TurnNotifications.Turn(id, opponent, "mirror".equals(row.getString("kind"))));
            }
            return turns;
        }
    }
}
