package fr.lettrine.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

final class SocialClient {
    interface Callback { void onResult(JSONObject result, String error); }

    private static final String API = "/api/social";
    private static final int RESPONSE_LIMIT = 2 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String HEX = "0123456789abcdef";

    private final Context context;
    private final SharedPreferences preferences;
    private final Supplier<String> base;
    private final Supplier<String> nickname;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    SocialClient(Context context, Supplier<String> base, Supplier<String> nickname) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences("social_session", Context.MODE_PRIVATE);
        this.base = base;
        this.nickname = nickname;
    }

    boolean configured() { return origin(base.get()) != null; }

    static boolean validBase(String value) { return origin(value) != null; }

    private static String origin(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim());
            String host = uri.getHost();
            String path = uri.getRawPath();
            if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !(path.isEmpty() || path.equals("/"))) return null;
            host = host.toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if (port == 0 || port > 65535 || uri.getRawAuthority().endsWith(":")) return null;
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            boolean local = host.equals("localhost") || host.equals("127.0.0.1")
                    || host.equals("10.0.2.2") || host.equals("[::1]");
            if (!scheme.equals("https") && !(BuildConfig.DEBUG && scheme.equals("http") && local)) return null;
            if ((port == 443 && scheme.equals("https")) || (port == 80 && scheme.equals("http"))) port = -1;
            return new URI(scheme, null, host, port, null, null, null).toString();
        } catch (IllegalArgumentException | java.net.URISyntaxException exception) {
            return null;
        }
    }

    void dashboard(Callback callback) {
        String localName = nickname.get();
        request("GET", "/me", null, (result, error) -> {
            JSONObject profile = result == null ? null : result.optJSONObject("profile");
            if (profile == null || profile.optBoolean("google_linked") || localName == null || localName.isBlank()
                    || localName.equals(profile.optString("nickname"))) {
                callback.onResult(result, error);
                return;
            }
            updateProfile(localName, (saved, failure) -> {
                if (failure != null) callback.onResult(null, failure);
                else request("GET", "/me", null, callback);
            });
        });
    }

    private void updateProfile(String nickname, Callback callback) {
        request("POST", "/profile", json("nickname", nickname), callback);
    }

    void googleChallenge(Callback callback) { request("POST", "/google/challenge", new JSONObject(), callback); }

    void linkGoogle(String idToken, String nonce, Callback callback) {
        JSONObject payload = json("id_token", idToken);
        add(payload, "nonce", nonce);
        request("POST", "/google", payload, callback);
    }

    void createInvitation(Callback callback) { request("POST", "/invites", new JSONObject(), callback); }

    void acceptInvitation(String token, Callback callback) {
        request("POST", "/invites/accept", json("token", token), callback);
    }

    void startDuel(String friend, Callback callback) {
        request("POST", "/duels", json("friend_id", friend), callback);
    }

    void rematch(String duel, Callback callback) {
        request("POST", "/duels/" + segment(duel) + "/rematch", new JSONObject(), callback);
    }

    void play(String duel, List<FrenchGame.Tile> tiles, Callback callback) {
        JSONArray played = new JSONArray();
        for (FrenchGame.Tile tile : tiles) {
            JSONObject letter = json("position", tile.position());
            add(letter, "rack_index", tile.rackIndex());
            add(letter, "letter", String.valueOf(tile.letter()));
            played.put(letter);
        }
        request("POST", "/duels/" + segment(duel) + "/play", json("tiles", played), callback);
    }

    void removeFriend(String friend, Callback callback) {
        request("DELETE", "/friends/" + segment(friend), null, callback);
    }

    void blockFriend(String friend, Callback callback) {
        request("POST", "/friends/" + segment(friend) + "/block", new JSONObject(), callback);
    }

    void deleteProfile(Callback callback) { request("DELETE", "/profile", null, callback); }

    void close() { worker.shutdownNow(); }

    private void request(String method, String path, JSONObject body, Callback callback) {
        String origin = origin(base.get());
        if (origin == null) {
            callback.onResult(null, context.getString(R.string.social_not_configured));
            return;
        }
        String name = nickname.get();
        worker.execute(() -> {
            try {
                String token = session(origin, name);
                JSONObject result;
                try {
                    result = exchange(origin, method, path, body, token);
                } catch (ApiError exception) {
                    if (!path.equals("/me") || exception.status != HttpURLConnection.HTTP_UNAUTHORIZED) throw exception;
                    // A replaced server database can forget this guest. Reuse the local token on explicit refresh.
                    preferences.edit().remove("ready:" + origin).apply();
                    result = exchange(origin, method, path, body, session(origin, name));
                }
                if (method.equals("DELETE") && path.equals("/profile")) {
                    preferences.edit().remove("token:" + origin).remove("ready:" + origin).apply();
                }
                deliver(origin, callback, result, null);
            } catch (ApiError exception) {
                deliver(origin, callback, null, exception.getMessage());
            } catch (IOException | JSONException exception) {
                deliver(origin, callback, null, context.getString(R.string.social_network_error));
            }
        });
    }

    private void deliver(String origin, Callback callback, JSONObject result, String error) {
        main.post(() -> {
            if (worker.isShutdown()) return;
            if (origin.equals(origin(base.get()))) callback.onResult(result, error);
            else callback.onResult(null, context.getString(R.string.social_endpoint_changed));
        });
    }

    private String session(String origin, String name) throws IOException, JSONException, ApiError {
        String key = "token:" + origin;
        String ready = "ready:" + origin;
        String token = preferences.getString(key, null);
        if (token == null) {
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            StringBuilder encoded = new StringBuilder(64);
            for (byte value : bytes) encoded.append(HEX.charAt((value & 255) >>> 4)).append(HEX.charAt(value & 15));
            token = encoded.toString();
            if (!preferences.edit().putString(key, token).commit()) throw new IOException("Session indisponible");
        }
        if (!preferences.getBoolean(ready, false)) {
            JSONObject session = json("token", token);
            if (name != null && !name.isBlank()) add(session, "nickname", name);
            exchange(origin, "POST", "/session", session, null);
            preferences.edit().putBoolean(ready, true).apply();
        }
        return token;
    }

    private JSONObject exchange(String origin, String method, String path, JSONObject body, String token)
            throws IOException, JSONException, ApiError {
        HttpURLConnection http = (HttpURLConnection) URI.create(origin + API + path).toURL().openConnection();
        try {
            http.setConnectTimeout(6000);
            http.setReadTimeout(9000);
            http.setInstanceFollowRedirects(false);
            http.setUseCaches(false);
            http.setRequestMethod(method);
            http.setRequestProperty("Accept", "application/json");
            if (token != null) http.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                http.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                http.setFixedLengthStreamingMode(payload.length);
                http.setDoOutput(true);
                try (OutputStream stream = http.getOutputStream()) { stream.write(payload); }
            }
            int code = http.getResponseCode();
            if (code == HttpURLConnection.HTTP_NO_CONTENT) return new JSONObject();
            if (code < 200 || code >= 300) {
                JSONObject failure = response(http.getErrorStream());
                throw new ApiError(code, failure.optString("error", context.getString(R.string.social_network_error)));
            }
            return response(http.getInputStream());
        } finally {
            http.disconnect();
        }
    }

    private static JSONObject response(InputStream input) throws IOException, JSONException {
        if (input == null) return new JSONObject();
        try (InputStream stream = input; ByteArrayOutputStream body = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int count; (count = stream.read(buffer)) != -1;) {
                if (body.size() + count > RESPONSE_LIMIT) throw new IOException("Réponse trop volumineuse");
                body.write(buffer, 0, count);
            }
            if (body.size() == 0) return new JSONObject();
            return new JSONObject(body.toString(StandardCharsets.UTF_8.name()));
        }
    }

    private static String segment(String value) { return android.net.Uri.encode(value); }

    private static JSONObject json(String key, Object value) {
        JSONObject object = new JSONObject();
        add(object, key, value);
        return object;
    }

    private static void add(JSONObject object, String key, Object value) {
        try { object.put(key, value); }
        catch (JSONException exception) { throw new IllegalArgumentException(exception); }
    }

    private static final class ApiError extends Exception {
        private final int status;

        ApiError(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
