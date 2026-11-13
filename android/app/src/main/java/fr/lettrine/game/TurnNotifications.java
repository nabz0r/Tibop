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
import java.util.HashSet;
import java.util.List;
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
    private static final Constraints CONNECTED = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED).build();
    private static final int NOTIFICATION_ID = 1;
    private static long latestApplied;
    private static long nextRequest;

    record Session(String origin, String token, long accountEpoch) {
        String scope() { return proof(token, "turns:" + origin + ":" + accountEpoch); }
        String proofFor(String duelId) { return proof(token, origin + "/duel/" + duelId + ":" + accountEpoch); }
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
                latestApplied = ++nextRequest;
                clearVisible(context);
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
            Session session = readSession(context, origin);
            syncScope(context, session);
            WorkManager work = WorkManager.getInstance(context);
            if (session == null || !available(context)) {
                clearVisible(context);
                work.cancelUniqueWork(IMMEDIATE);
                work.cancelUniqueWork(PERIODIC);
                return;
            }
            work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                    new PeriodicWorkRequest.Builder(TurnNotificationWorker.class, 15, TimeUnit.MINUTES)
                            .setInitialDelay(15, TimeUnit.MINUTES).setConstraints(CONNECTED).build());
            work.enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP,
                    new OneTimeWorkRequest.Builder(TurnNotificationWorker.class).setConstraints(CONNECTED).build());
        }
    }

    static void dismiss(Context context, String duelId) {
        if (!isDuelId(duelId)) return;
        synchronized (LOCK) {
            SharedPreferences state = settings(context);
            if (!enabled(context)) return;
            syncScope(context, currentSession(context));
            latestApplied = ++nextRequest;
            manager(context).cancel(tag(duelId), NOTIFICATION_ID);
            Set<String> visible = copySet(state, "visible");
            visible.remove(duelId);
            Set<String> seen = copySet(state, "seen");
            if (!state.getString("scope", "").isEmpty()) seen.add(duelId);
            state.edit().putStringSet("visible", visible).putStringSet("seen", seen).apply();
        }
    }

    static void accountChanged(Context context) {
        synchronized (LOCK) {
            latestApplied = ++nextRequest;
            SharedPreferences state = settings(context);
            state.edit().putLong("account_epoch", state.getLong("account_epoch", 0) + 1).apply();
            syncScope(context, currentSession(context));
        }
    }

    static String takeDuel(Context context, Intent intent, String baseUrl) {
        if (intent == null || !OPEN_TURN.equals(intent.getAction()) || !enabled(context)) return null;
        Uri uri = intent.getData();
        if (uri == null || !"lettrine".equals(uri.getScheme()) || !"tour".equals(uri.getAuthority())) return null;
        String id = uri.getLastPathSegment();
        if (!isDuelId(id) || !("/" + id).equals(uri.getEncodedPath()) || uri.getEncodedQuery() != null
                || uri.getEncodedFragment() != null) return null;
        Session session = readSession(context, SocialClient.origin(baseUrl));
        String supplied = intent.getStringExtra(EXTRA_PROOF);
        if (session == null || supplied == null || supplied.length() != 64
                || !MessageDigest.isEqual(session.proofFor(id).getBytes(StandardCharsets.US_ASCII),
                supplied.getBytes(StandardCharsets.US_ASCII))) return null;
        intent.removeExtra(EXTRA_PROOF);
        return id;
    }

    static boolean isDuelId(String id) { return id != null && DUEL_ID.matcher(id).matches(); }

    static Session currentSession(Context context) {
        String raw = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
                .getString("social_endpoint", BuildConfig.SOCIAL_BASE_URL);
        return readSession(context, SocialClient.origin(raw));
    }

    private static Session readSession(Context context, String origin) {
        if (origin == null) return null;
        GameLanguage language = AppLocale.language(context);
        SharedPreferences sessions = context.getSharedPreferences(language.storageName("social_session"), Context.MODE_PRIVATE);
        String token = sessions.getString("token:" + origin, null);
        return token == null || !sessions.getBoolean("ready:" + origin, false) ? null
                : new Session(origin, token, settings(context).getLong("account_epoch", 0));
    }

    static long begin() {
        synchronized (LOCK) {
            return ++nextRequest;
        }
    }

    static void reconcile(Context context, Session requested, long order, List<Turn> turns) {
        synchronized (LOCK) {
            Session current = currentSession(context);
            syncScope(context, current);
            if (order <= latestApplied || !requested.equals(current) || !enabled(context) || !available(context)) return;
            latestApplied = order;
            SharedPreferences state = settings(context);
            Set<String> seen = copySet(state, "seen");
            Set<String> visible = copySet(state, "visible");
            Set<String> pending = new HashSet<>();
            for (Turn turn : turns) pending.add(turn.id());
            for (String id : visible) {
                if (!pending.contains(id)) manager(context).cancel(tag(id), NOTIFICATION_ID);
            }
            visible.retainAll(pending);
            seen.retainAll(pending);
            for (Turn turn : turns) {
                if (!seen.add(turn.id())) continue;
                if (post(context, current, turn)) visible.add(turn.id());
                else seen.remove(turn.id());
            }
            state.edit().putStringSet("seen", seen).putStringSet("visible", visible).apply();
        }
    }

    private static void syncScope(Context context, Session session) {
        SharedPreferences state = settings(context);
        String scope = session == null ? "" : session.scope();
        if (scope.equals(state.getString("scope", ""))) return;
        clearVisible(context);
        state.edit().putString("scope", scope).remove("seen").apply();
    }

    private static void clearVisible(Context context) {
        SharedPreferences state = settings(context);
        NotificationManager manager = manager(context);
        for (String id : copySet(state, "visible")) manager.cancel(tag(id), NOTIFICATION_ID);
        state.edit().remove("visible").apply();
    }

    @SuppressLint("MissingPermission") // Checked before posting. Android can revoke permission during the request.
    private static boolean post(Context context, Session session, Turn turn) {
        NotificationManager manager = manager(context);
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                context.getString(R.string.turn_notifications_title), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.turn_notification_channel_detail));
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(channel);
        Intent intent = new Intent(context, MainActivity.class)
                .setAction(OPEN_TURN)
                .setData(Uri.parse("lettrine://tour/" + turn.id()))
                .putExtra(EXTRA_PROOF, session.proofFor(turn.id()))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = context.getString(R.string.turn_notification_title);
        Notification publicVersion = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.turn_notification_icon)
                .setContentTitle(title)
                .setContentText(context.getString(R.string.turn_notification_public))
                .build();
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.turn_notification_icon)
                .setContentTitle(title)
                .setContentText(context.getString(turn.mirror() ? R.string.turn_notification_mirror
                        : R.string.turn_notification_duel, turn.opponent()))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .build();
        try {
            manager.notify(tag(turn.id()), NOTIFICATION_ID, notification);
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

    private static String tag(String id) { return "turn:" + id; }
    private static NotificationManager manager(Context context) { return context.getSystemService(NotificationManager.class); }
    private static SharedPreferences settings(Context context) {
        return context.getSharedPreferences("turn_notifications", Context.MODE_PRIVATE);
    }

    private static Set<String> copySet(SharedPreferences preferences, String key) {
        return new HashSet<>(preferences.getStringSet(key, Set.of()));
    }
}
