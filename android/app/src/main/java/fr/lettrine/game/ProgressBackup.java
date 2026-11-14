package fr.lettrine.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class ProgressBackup {
    private static final String MANIFEST = "progress.json";
    private static final String FORMAT = "wordrivet.progress.v1";
    private static final String ECONOMY = "bonus-economy-v1.bin";
    private static final String PHOTO = "profile-photo.jpg";
    private static final String[] MATCH_FILES = {"current-match.json", "saved-matches.json", "history.json"};
    private static final int MAX_BYTES = 48 * 1024 * 1024;
    private static final Map<String, Class<?>> SETTINGS = Map.of(
            "app_language", String.class, "solo_level", Integer.class, "skin", Integer.class,
            "music", Boolean.class, "music_track", Integer.class, "music_volume", Integer.class,
            "effects", Boolean.class);
    private static final Map<String, Map<String, Class<?>>> PREFS = Map.ofEntries(
            Map.entry("preferences", SETTINGS),
            Map.entry("player_profile", Map.of("nickname", String.class, "country", String.class,
                    "age", Integer.class, "sex", String.class)),
            Map.entry("skin_collection", Map.of("board", String.class, "tiles", String.class, "unlocked", Integer.class)),
            Map.entry("share_card", Map.of("nocturne", Boolean.class, "use_nocturne", Boolean.class)),
            Map.entry("daily_progress", Map.of()), Map.entry("daily_progress-en", Map.of()),
            Map.entry("daily_draft", Map.of()), Map.entry("daily_draft-en", Map.of()),
            Map.entry("two_move_draft", Map.of()), Map.entry("two_move_draft-en", Map.of()),
            Map.entry("two_move_challenges", Map.of()), Map.entry("two_move_challenges-en", Map.of()));

    record Snapshot(Map<String, byte[]> entries) {}

    private ProgressBackup() {}

    static Snapshot capture(Context context) throws IOException, JSONException {
        synchronized (GameStore.STORE_LOCK) {
            synchronized (BonusEconomy.LOCK) {
                return captureLocked(context);
            }
        }
    }

    private static Snapshot captureLocked(Context context) throws IOException, JSONException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        JSONObject manifest = new JSONObject();
        JSONObject preferences = new JSONObject();
        for (var group : PREFS.entrySet()) {
            JSONObject values = new JSONObject();
            for (var item : prefs(context, group.getKey()).getAll().entrySet()) {
                if (type(group.getKey(), item.getKey()) != null) values.put(item.getKey(), item.getValue());
            }
            preferences.put(group.getKey(), values);
        }
        JSONObject inventory = new JSONObject();
        int bytes = 0;
        for (var entry : paths(context).entrySet()) {
            try (InputStream input = entry.getValue().openRead()) {
                byte[] data = boundedRead(input, MAX_BYTES - bytes);
                bytes += data.length;
                entries.put(entry.getKey(), data);
                inventory.put(entry.getKey(), checksum(data));
            } catch (FileNotFoundException missing) {
                if (entry.getValue().getBaseFile().exists()) throw missing;
            }
        }
        manifest.put("format", FORMAT).put("preferences", preferences).put("files", inventory);
        byte[] metadata = manifest.toString().getBytes(StandardCharsets.UTF_8);
        if (metadata.length > MAX_BYTES - bytes) throw new IOException("Sauvegarde trop volumineuse");
        entries.put(MANIFEST, metadata);
        return new Snapshot(entries);
    }

    static void write(Snapshot snapshot, OutputStream output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (var entry : snapshot.entries().entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    static Snapshot read(InputStream input) throws IOException, JSONException {
        Snapshot snapshot = readEntries(input);
        validate(snapshot);
        return snapshot;
    }

    private static Snapshot readEntries(InputStream input) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        int remaining = MAX_BYTES;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !allowedEntry(name) || entries.containsKey(name)) {
                    throw new IOException("Entrée de sauvegarde inconnue ou répétée");
                }
                byte[] data = boundedRead(zip, remaining);
                remaining -= data.length;
                entries.put(name, data);
                zip.closeEntry();
            }
        }
        if (!entries.containsKey(MANIFEST)) throw new IOException("Sauvegarde incomplète");
        return new Snapshot(entries);
    }

    private static byte[] boundedRead(InputStream stream, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (count > limit - bytes.size()) throw new IOException("Sauvegarde trop volumineuse");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }

    private static boolean allowedEntry(String name) {
        if (name.equals(MANIFEST) || name.equals(ECONOMY) || name.equals(PHOTO)) return true;
        for (String match : MATCH_FILES) {
            if (name.equals("fr/" + match) || name.equals("en/" + match)) return true;
        }
        return false;
    }

    private static Map<String, AtomicFile> paths(Context context) {
        Map<String, AtomicFile> paths = new LinkedHashMap<>();
        File files = context.getFilesDir();
        File english = context.getDir("matches-en", Context.MODE_PRIVATE);
        for (String name : MATCH_FILES) {
            paths.put("fr/" + name, new AtomicFile(new File(files, name)));
            paths.put("en/" + name, new AtomicFile(new File(english, name)));
        }
        paths.put(ECONOMY, new AtomicFile(new File(files, ECONOMY)));
        paths.put(PHOTO, new AtomicFile(new File(files, PHOTO)));
        return paths;
    }

    private static SharedPreferences prefs(Context context, String group) {
        return context.getSharedPreferences(group, Context.MODE_PRIVATE);
    }

    static Class<?> type(String group, String key) {
        var known = PREFS.get(group);
        if (!known.isEmpty()) return known.get(key);
        if (key.startsWith("lexicon/") && (group.startsWith("daily_progress")
                || group.startsWith("daily_draft") || group.startsWith("two_move_draft"))) {
            String valueKey = key.substring("lexicon/".length());
            return valueKey.indexOf('/') >= 0 || type(group, valueKey) == null
                    || group.startsWith("daily_draft") && !validDraftDate(valueKey)
                    ? null : String.class;
        }
        if (group.startsWith("two_move_challenges")) {
            return key.matches("[a-z0-9-]{1,64}") ? Integer.class : null;
        }
        if (group.startsWith("two_move_draft")) return key.matches("[a-z0-9-]{1,64}") ? String.class : null;
        if (group.startsWith("daily_draft")) return key.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") ? String.class : null;
        try {
            long day = Long.parseLong(key);
            return Long.toString(day).equals(key) && day >= LocalDate.MIN.toEpochDay() && day <= LocalDate.MAX.toEpochDay()
                    ? Integer.class : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static boolean validDraftDate(String value) {
        try {
            return LocalDate.parse(value).getYear() > 0;
        } catch (DateTimeParseException invalid) {
            return false;
        }
    }

    private static String json(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }

    private static String checksum(byte[] data) {
        try {
            return java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static JSONObject preferences(Snapshot snapshot) throws JSONException, IOException {
        JSONObject manifest = new JSONObject(json(snapshot.entries().get(MANIFEST)));
        if (!FORMAT.equals(manifest.optString("format"))) throw new IOException("Format de sauvegarde inconnu");
        JSONObject inventory = manifest.getJSONObject("files");
        if (inventory.length() != snapshot.entries().size() - 1) throw new IOException("Sauvegarde incomplète");
        for (var entry : snapshot.entries().entrySet()) {
            if (!entry.getKey().equals(MANIFEST) && !checksum(entry.getValue()).equals(inventory.optString(entry.getKey()))) {
                throw new IOException("Fichier de sauvegarde invalide");
            }
        }
        return manifest.getJSONObject("preferences");
    }

    private static void validate(Snapshot snapshot) throws IOException, JSONException {
        JSONObject preferences = preferences(snapshot);
        for (String group : PREFS.keySet()) {
            JSONObject values = group.startsWith("two_move_draft") && !preferences.has(group)
                    ? new JSONObject() : preferences.getJSONObject(group);
            var keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = values.get(key);
                Class<?> kind = type(group, key);
                if (kind == null || !kind.isInstance(value)
                        || value instanceof String string && string.length() > (group.contains("_draft") ? 1024 : 256)
                        || value instanceof Integer number && number < 0) {
                    throw new IOException("Réglage de sauvegarde invalide");
                }
                if (key.startsWith("lexicon/")) {
                    if (!(value instanceof String hash) || !hash.matches("[0-9a-f]{64}")
                            || group.startsWith("daily_progress") && !values.has(key.substring("lexicon/".length()))) {
                        throw new IOException("Lexique de sauvegarde invalide");
                    }
                    continue;
                }
                if (group.startsWith("daily_draft")) MatchCodec.readTiles(new JSONArray((String) value), true);
                if (group.startsWith("two_move_draft")) {
                    JSONArray moves = new JSONArray((String) value);
                    if (moves.length() != 2) throw new IOException("Brouillon incomplet");
                    MatchCodec.readTiles(moves.getJSONArray(0), true);
                    MatchCodec.readTiles(moves.getJSONArray(1), true);
                }
            }
        }
        try {
            JSONObject settings = preferences.getJSONObject("preferences");
            if (settings.optInt("music_track") > 1 || settings.optInt("music_volume") > 100
                    || !settings.optString("app_language", "fr").matches("fr|en")) {
                throw new IOException("Réglage de sauvegarde invalide");
            }
            JSONObject profile = preferences.getJSONObject("player_profile");
            new ProfileStore.Profile(profile.optString("nickname"), profile.optString("country"), profile.optInt("age"),
                    ProfileStore.Sex.valueOf(profile.optString("sex", "UNSPECIFIED")));
            for (GameLanguage language : GameLanguage.values()) {
                String prefix = language.code + "/";
                byte[] legacy = snapshot.entries().get(prefix + MATCH_FILES[0]);
                if (legacy != null) validateMatch(new JSONObject(json(legacy)), language);
                for (int i = 1; i < MATCH_FILES.length; i++) {
                    byte[] bytes = snapshot.entries().get(prefix + MATCH_FILES[i]);
                    if (bytes == null) continue;
                    JSONArray rows = new JSONArray(json(bytes));
                    for (int j = 0; j < rows.length(); j++) {
                        JSONObject row = rows.getJSONObject(j);
                        if (i == 1) validateMatch(row, language);
                        else validateHistory(row, language);
                    }
                }
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Progression de sauvegarde invalide", invalid);
        }
        byte[] economy = snapshot.entries().get(ECONOMY);
        if (economy != null) BonusEconomy.validateBackup(economy);
        byte[] photo = snapshot.entries().get(PHOTO);
        if (photo != null) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(photo, 0, photo.length, options);
            if (!"image/jpeg".equals(options.outMimeType) || options.outWidth < 1 || options.outWidth > 768
                    || options.outHeight < 1 || options.outHeight > 768) throw new IOException("Photo invalide");
        }
    }

    private static void validateMatch(JSONObject row, GameLanguage language) throws JSONException, IOException {
        FrenchGame game = MatchCodec.restore(row);
        boolean solo = "solo".equals(row.getString("mode"));
        if (game.language != language || game.id.isBlank() || game.bag.size() > 102
                || game.racks.stream().anyMatch(rack -> rack.size() > FrenchGame.RACK_SIZE)
                || (solo ? game.levels.length != 2 || game.levels[0] != 0 || game.levels[1] < 1 || game.levels[1] > Bot.ADAPTIVE
                        : !"local".equals(row.getString("mode")) || Arrays.stream(game.levels).anyMatch(level -> level != 0))) {
            throw new IOException("Partie invalide");
        }
        JSONArray draft = row.optJSONArray("draft");
        if (draft != null) MatchCodec.readTiles(draft, true);
    }

    private static void validateHistory(JSONObject row, GameLanguage language) throws JSONException, IOException {
        JSONArray names = row.getJSONArray("names");
        if (names.length() < 2 || names.length() > 4 || row.getJSONArray("scores").length() != names.length()
                || row.getString("id").isBlank() || !"lettrine.game.v1".equals(row.getString("schema"))
                || !language.code.equals(row.optString("language", "fr"))) throw new IOException("Historique invalide");
        for (var move : MatchCodec.readMoves(row.getJSONArray("moves"))) {
            if (move.player() < 0 || move.player() >= names.length()) throw new IOException("Tour invalide");
        }
    }

    static void restore(Context context, Snapshot incoming) throws IOException, JSONException {
        synchronized (GameStore.STORE_LOCK) {
            synchronized (BonusEconomy.LOCK) {
                restoreLocked(context, incoming);
            }
        }
    }

    private static void restoreLocked(Context context, Snapshot incoming) throws IOException, JSONException {
        recover(context);
        Snapshot previous = captureLocked(context);
        AtomicFile journal = journal(context);
        FileOutputStream stream = journal.startWrite();
        try {
            // Closing the ZIP must leave the AtomicFile stream open for its final sync.
            write(previous, new OutputStream() {
                @Override public void write(int value) throws IOException { stream.write(value); }
                @Override public void write(byte[] data, int offset, int length) throws IOException {
                    stream.write(data, offset, length);
                }
            });
            journal.finishWrite(stream);
        } catch (IOException | RuntimeException failure) {
            journal.failWrite(stream);
            throw failure;
        }
        if (!journal.getBaseFile().isFile()) throw new IOException("Journal de restauration indisponible");
        preferences(readEntries(journal.openRead()));
        try {
            apply(context, incoming);
            clear(journal);
        } catch (IOException | JSONException | RuntimeException failure) {
            try {
                recover(context);
            } catch (IOException | JSONException | RuntimeException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    static void recover(Context context) throws IOException, JSONException {
        synchronized (GameStore.STORE_LOCK) {
            synchronized (BonusEconomy.LOCK) {
                AtomicFile journal = journal(context);
                Snapshot previous;
                try {
                    previous = readEntries(journal.openRead());
                } catch (FileNotFoundException missing) {
                    if (journal.getBaseFile().exists()) throw missing;
                    return;
                }
                apply(context, previous);
                clear(journal);
            }
        }
    }

    private static AtomicFile journal(Context context) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), "progress-restore.zip"));
    }

    static boolean recoveryPending(Context context) { return journal(context).getBaseFile().isFile(); }

    private static void clear(AtomicFile file) throws IOException {
        file.delete();
        if (file.getBaseFile().exists()) throw new IOException("Effacement de la sauvegarde temporaire impossible");
    }

    private static void apply(Context context, Snapshot snapshot) throws IOException, JSONException {
        JSONObject preferences = preferences(snapshot);
        for (var entry : paths(context).entrySet()) {
            byte[] bytes = snapshot.entries().get(entry.getKey());
            AtomicFile file = entry.getValue();
            if (bytes == null) {
                clear(file);
                continue;
            }
            FileOutputStream stream = file.startWrite();
            try {
                stream.write(bytes);
                file.finishWrite(stream);
                if (!Arrays.equals(bytes, file.readFully())) throw new IOException("Écriture de sauvegarde impossible");
            } catch (IOException | RuntimeException failure) {
                file.failWrite(stream);
                throw failure;
            }
        }
        for (String group : PREFS.keySet()) {
            SharedPreferences store = prefs(context, group);
            SharedPreferences.Editor editor = store.edit();
            for (String key : store.getAll().keySet()) if (type(group, key) != null) editor.remove(key);
            JSONObject values = group.startsWith("two_move_draft") && !preferences.has(group)
                    ? new JSONObject() : preferences.getJSONObject(group);
            var keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = values.get(key);
                if (value instanceof Integer number) editor.putInt(key, number);
                else if (value instanceof Boolean flag) editor.putBoolean(key, flag);
                else if (value instanceof String string) editor.putString(key, string);
            }
            if (!editor.commit()) throw new IOException("Réglages non enregistrés");
        }
        GameStore.invalidateHistory();
    }
}
