package fr.lettrine.game;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class TurnNotifications {
    private static final String CHANNEL = "duel_turns";
    private static final String PERIODIC = "duel-turns-periodic";
    private static final String IMMEDIATE = "duel-turns-refresh";
    private static final String OPEN_TURN = "fr.lettrine.game.OPEN_DUEL_TURN";
    private static final String EXTRA_PROOF = "turn_proof";
    private static final Pattern DUEL_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Object LOCK = new Object();
    private static final int NOTIFICATION_ID = 1;
    private static final Map<GameLanguage, Long> latestApplied = new EnumMap<>(GameLanguage.class);
    private static long nextRequest;

    record Session(GameLanguage language, String origin, String token, long accountEpoch) {
        String scope() { return proof(token, "turns:" + origin + ":" + language.code + ":" + accountEpoch); }
        String legacyScope() { return proof(token, "turns:" + origin + ":" + accountEpoch); }
        String legacyProofFor(String duelId) { return proof(token, origin + "/duel/" + duelId + ":" + accountEpoch); }
        String proofFor(String duelId) {
            return proof(token, origin + "/duel/" + language.code + "/" + duelId + ":" + accountEpoch);
        }
    }

    record Target(GameLanguage language, String id) {
        static Target parse(String path) {
            if (path == null) return null;
            for (GameLanguage language : GameLanguage.values()) {
                String prefix = "/" + language.code + "/";
                if (path.startsWith(prefix)) {
                    String id = path.substring(prefix.length());
                    return isDuelId(id) ? new Target(language, id) : null;
                }
            }
            return null;
        }

        String path() { return "/" + language.code + "/" + id; }
    }

    record Turn(String id, String opponent, boolean mirror) {}

    private TurnNotifications() {}

    static boolean enabled(Context context) {
        return settings(context).getBoolean("enabled", false);
    }

    static boolean available(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager = manager(context);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled() && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    static void setEnabled(Context context, boolean enabled) {
        synchronized (LOCK) {
            settings(context).edit().putBoolean("enabled", enabled).apply();
            if (!enabled) {
                migrateLegacy(context, currentOrigin(context));
                for (GameLanguage language : GameLanguage.values()) {
                    invalidate(language);
                    clearVisible(context, language);
                }
                WorkManager work = WorkManager.getInstance(context);
                work.cancelUniqueWork(IMMEDIATE);
                work.cancelUniqueWork(PERIODIC);
            }
        }
    }

    static void refresh(Context context, String baseUrl) {
        synchronized (LOCK) {
            if (!enabled(context)) return;
            String origin = SocialClient.origin(baseUrl);
            migrateLegacy(context, origin);
            boolean hasSession = false;
            for (GameLanguage language : GameLanguage.values()) {
                Session session = readSession(context, origin, language);
                hasSession |= session != null;
                if (syncScope(context, language, session)) invalidate(language);
            }
            WorkManager work = WorkManager.getInstance(context);
            if (!hasSession || !available(context)) {
                for (GameLanguage language : GameLanguage.values()) clearVisible(context, language);
                work.cancelUniqueWork(IMMEDIATE);
                work.cancelUniqueWork(PERIODIC);
                return;
            }
            Constraints connected = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
            work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                    new PeriodicWorkRequest.Builder(TurnNotificationWorker.class, 15, TimeUnit.MINUTES)
                            .setInitialDelay(15, TimeUnit.MINUTES).setConstraints(connected).build());
            work.enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP,
                    new OneTimeWorkRequest.Builder(TurnNotificationWorker.class).setConstraints(connected).build());
        }
    }

    static void dismiss(Context context, GameLanguage language, String duelId) {
        if (!isDuelId(duelId)) return;
        synchronized (LOCK) {
            SharedPreferences state = settings(context);
            if (!enabled(context)) return;
            migrateLegacy(context, currentOrigin(context));
            syncScope(context, language, currentSession(context, language));
            invalidate(language);
            String visibleKey = key(language, "visible");
            String seenKey = key(language, "seen");
            manager(context).cancel(tag(language, duelId), NOTIFICATION_ID);
            Set<String> visible = copySet(state, visibleKey);
            visible.remove(duelId);
            Set<String> seen = copySet(state, seenKey);
            if (!state.getString(key(language, "scope"), "").isEmpty()) seen.add(duelId);
            state.edit().putStringSet(visibleKey, visible).putStringSet(seenKey, seen).apply();
        }
    }

    static void accountChanged(Context context, GameLanguage language) {
        synchronized (LOCK) {
            migrateLegacy(context, currentOrigin(context));
            invalidate(language);
            SharedPreferences state = settings(context);
            String epoch = key(language, "account_epoch");
            state.edit().putLong(epoch, state.getLong(epoch, 0) + 1).apply();
            syncScope(context, language, currentSession(context, language));
        }
    }

    static Target peekDuel(Context context, Intent intent, String baseUrl) {
        if (intent == null || !OPEN_TURN.equals(intent.getAction()) || !enabled(context)) return null;
        Uri uri = intent.getData();
        if (uri == null || !"lettrine".equals(uri.getScheme()) || !"tour".equals(uri.getAuthority())) return null;
        if (uri.getEncodedQuery() != null || uri.getEncodedFragment() != null) return null;
        String supplied = intent.getStringExtra(EXTRA_PROOF);
        if (supplied == null || supplied.length() != 64) return null;
        String origin = SocialClient.origin(baseUrl);
        String path = uri.getEncodedPath();
        Target target = Target.parse(path);
        if (target != null) {
            Session session = readSession(context, origin, target.language());
            return session != null && matchesProof(session.proofFor(target.id()), supplied) ? target : null;
        }

        // The previous release used language-free notification links, with the token
        // and stored scope still identifying the account that received the alert.
        SharedPreferences state = settings(context);
        String legacyScope = state.getString("scope", "");
        if (legacyScope.isEmpty() || path == null || !path.startsWith("/") || !isDuelId(path.substring(1))) return null;
        String id = path.substring(1);
        for (GameLanguage language : GameLanguage.values()) {
            Session session = readSession(context, origin, language);
            if (session != null && legacyScope.equals(session.legacyScope())
                    && matchesProof(session.legacyProofFor(id), supplied)) return new Target(language, id);
        }
        return null;
    }

    static void consumeDuel(Intent intent) { intent.removeExtra(EXTRA_PROOF); }

    static boolean isDuelId(String id) { return id != null && DUEL_ID.matcher(id).matches(); }

    static Session currentSession(Context context, GameLanguage language) {
        return readSession(context, currentOrigin(context), language);
    }

    private static String currentOrigin(Context context) {
        String raw = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
                .getString("social_endpoint", BuildConfig.SOCIAL_BASE_URL);
        return SocialClient.origin(raw);
    }

    private static Session readSession(Context context, String origin, GameLanguage language) {
        if (origin == null) return null;
        SharedPreferences sessions = context.getSharedPreferences(language.storageName("social_session"), Context.MODE_PRIVATE);
        String token = sessions.getString("token:" + origin, null);
        SharedPreferences state = settings(context);
        return token == null || !sessions.getBoolean("ready:" + origin, false) ? null
                : new Session(language, origin, token, state.getLong(key(language, "account_epoch"),
                        state.getLong("account_epoch", 0)));
    }

    static long begin() {
        synchronized (LOCK) {
            return ++nextRequest;
        }
    }

    static void reconcile(Context context, Session requested, long order, List<Turn> turns) {
        synchronized (LOCK) {
            GameLanguage language = requested.language();
            String origin = currentOrigin(context);
            migrateLegacy(context, origin);
            Session current = readSession(context, origin, language);
            if (order <= latestApplied.getOrDefault(language, 0L) || !requested.equals(current)
                    || !enabled(context) || !available(context)) return;
            syncScope(context, language, current);
            latestApplied.put(language, order);
            SharedPreferences state = settings(context);
            String visibleKey = key(language, "visible");
            String seenKey = key(language, "seen");
            Set<String> seen = copySet(state, seenKey);
            Set<String> visible = copySet(state, visibleKey);
            Set<String> pending = new HashSet<>();
            for (Turn turn : turns) pending.add(turn.id());
            for (String id : visible) {
                if (!pending.contains(id)) manager(context).cancel(tag(language, id), NOTIFICATION_ID);
            }
            visible.retainAll(pending);
            seen.retainAll(pending);
            for (Turn turn : turns) {
                if (!seen.add(turn.id())) continue;
                if (post(context, current, turn)) visible.add(turn.id());
                else seen.remove(turn.id());
            }
            state.edit().putStringSet(seenKey, seen).putStringSet(visibleKey, visible).apply();
        }
    }

    private static void invalidate(GameLanguage language) {
        latestApplied.put(language, ++nextRequest);
    }

    private static boolean syncScope(Context context, GameLanguage language, Session session) {
        SharedPreferences state = settings(context);
        String scopeKey = key(language, "scope");
        String scope = session == null ? "" : session.scope();
        if (scope.equals(state.getString(scopeKey, ""))) return false;
        clearVisible(context, language);
        state.edit().putString(scopeKey, scope).remove(key(language, "seen")).apply();
        return true;
    }

    private static void clearVisible(Context context, GameLanguage language) {
        SharedPreferences state = settings(context);
        String visibleKey = key(language, "visible");
        NotificationManager manager = manager(context);
        for (String id : copySet(state, visibleKey)) manager.cancel(tag(language, id), NOTIFICATION_ID);
        state.edit().remove(visibleKey).apply();
    }

    private static void migrateLegacy(Context context, String origin) {
        SharedPreferences state = settings(context);
        if (!state.contains("scope") && !state.contains("account_epoch")) return;
        var migrated = state.edit();
        long legacyEpoch = state.getLong("account_epoch", 0);
        String legacyScope = state.getString("scope", "");
        Set<String> visible = copySet(state, "visible");
        Set<String> seen = copySet(state, "seen");
        seen.removeAll(visible);
        for (GameLanguage language : GameLanguage.values()) {
            Session session = readSession(context, origin, language);
            migrated.putLong(key(language, "account_epoch"), legacyEpoch);
            if (session != null && legacyScope.equals(session.legacyScope())) {
                migrated.putString(key(language, "scope"), session.scope());
                migrated.putStringSet(key(language, "seen"), seen);
            }
        }
        NotificationManager manager = manager(context);
        for (String id : visible) manager.cancel("turn:" + id, NOTIFICATION_ID);
        migrated.remove("scope").remove("seen").remove("visible").remove("account_epoch").apply();
    }

    @SuppressLint("MissingPermission") // Checked before posting. Android can revoke permission during the request.
    private static boolean post(Context context, Session session, Turn turn) {
        NotificationManager manager = manager(context);
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                context.getString(R.string.turn_notifications_title), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.turn_notification_channel_detail));
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(channel);
        Target target = new Target(session.language(), turn.id());
        Intent intent = new Intent(context, MainActivity.class)
                .setAction(OPEN_TURN)
                .setData(Uri.parse("lettrine://tour" + target.path()))
                .putExtra(EXTRA_PROOF, session.proofFor(turn.id()))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Context localized = AppLocale.wrap(context, session.language());
        String title = localized.getString(R.string.turn_notification_title);
        Notification publicVersion = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.turn_notification_icon)
                .setContentTitle(title)
                .setContentText(localized.getString(R.string.turn_notification_public))
                .build();
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.turn_notification_icon)
                .setContentTitle(title)
                .setContentText(localized.getString(turn.mirror() ? R.string.turn_notification_mirror
                        : R.string.turn_notification_duel, turn.opponent()))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .build();
        try {
            manager.notify(tag(session.language(), turn.id()), NOTIFICATION_ID, notification);
            return true;
        } catch (SecurityException denied) {
            return false;
        }
    }

    private static String proof(String token, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            String hex = "0123456789abcdef";
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append(hex.charAt((b & 255) >>> 4)).append(hex.charAt(b & 15));
            return result.toString();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 indisponible", exception);
        }
    }

    private static boolean matchesProof(String expected, String supplied) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                supplied.getBytes(StandardCharsets.US_ASCII));
    }

    private static String key(GameLanguage language, String name) { return language.code + ":" + name; }
    private static String tag(GameLanguage language, String id) { return "turn:" + key(language, id); }
    private static NotificationManager manager(Context context) { return context.getSystemService(NotificationManager.class); }
    private static SharedPreferences settings(Context context) {
        return context.getSharedPreferences("turn_notifications", Context.MODE_PRIVATE);
    }

    private static Set<String> copySet(SharedPreferences preferences, String key) {
        return new HashSet<>(preferences.getStringSet(key, Set.of()));
    }
}
