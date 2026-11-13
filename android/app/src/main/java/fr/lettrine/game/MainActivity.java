package fr.lettrine.game;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

public final class MainActivity extends Activity {
    private static final int BLUETOOTH_PERMISSION = 41;
    private static final int ENABLE_BLUETOOTH = 42;
    private static final int EXPORT_FILE = 43;
    private static final int APP_SETTINGS = 44;
    private static final int PROFILE_PHOTO = 45;
    private static final int TURN_PERMISSION = 46;
    private static final int DEFAULT_RATING = GameStore.BASE_ELO;

    private enum Screen { HOME, GAMES, SOLO, LOCAL, BLUETOOTH, SETTINGS, HISTORY, DETAIL, GAME, REVIEW, PRACTICE,
        SOCIAL, SOCIAL_DUEL, PROFILE, COLLECTION, TOURNAMENT, TOURNAMENT_ROUND, HELP }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService explanationWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService reviewWorker = Executors.newSingleThreadExecutor();
    private final List<FrenchGame.Tile> staged = new ArrayList<>();
    private final List<Integer> rackOrder = new ArrayList<>();
    private SharedPreferences preferences;
    private ViewKit ui;
    private GameStore store;
    private DailyProgress dailyProgress;
    private SkinStore skinStore;
    private ProfileStore profileStore;
    private ProfilePanel profilePanel;
    private SocialClient socialClient;
    private CancellationSignal googleSignIn;
    private String pendingSocialInvite;
    private Object pendingTurnNavigation;
    private Lexicon lexicon;
    private FrenchGame game;
    private MatchCodec.State guestState;
    private Screen screen = Screen.HOME;
    private OnBackInvokedCallback backCallback;
    private String mode = "";
    private String lexiconError;
    private int skin;
    private int selected = -1;
    private int lastRackTurn = -1;
    private int startRating;
    private int botLevelSum;
    private int botLevelCount;
    private int historyLimit = 20;
    private int soloSelection;
    private boolean privacy;
    private boolean botThinking;
    private boolean analysing;
    private boolean assisted;
    private boolean waiting;
    private boolean bluetoothHost;
    private boolean bluetoothReady;
    private String bluetoothStatus = "";
    private String bluetoothName = "";
    private BluetoothLink link;
    private Arena arena;
    private FrameLayout frame;
    private LinearLayout content;
    private GameAudio audio;
    private RewardedPractice rewarded;
    private String export;
    private String notice;
    private JSONObject detail;
    private int replayIndex;
    private AlertDialog explanationDialog;
    private Future<?> reviewTask;
    private Runnable reviewBack;
    private Runnable practiceBack;
    private Runnable pendingChallenge;
    private Screen gameBack = Screen.HOME;
    private Screen setupBack = Screen.HOME;
    private LocalDate homeDate;
    private final BroadcastReceiver calendarChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshDailyHome(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("preferences", MODE_PRIVATE);
        audio = new GameAudio(this, preferences);
        audio.prepareEffects();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        soloSelection = Math.max(1, Math.min(Bot.ADAPTIVE, preferences.getInt("solo_level", 1)));
        rewarded = new RewardedPractice(this);
        dailyProgress = new DailyProgress(this);
        skinStore = new SkinStore(this);
        profileStore = new ProfileStore(this);
        socialClient = new SocialClient(this, this::socialBaseUrl, profileStore::nickname);
        skin = Math.max(0, Math.min(Palette.THEMES.length - 1, preferences.getInt("skin", 0)));
        updateAppearance();
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        try {
            store = new GameStore(this);
            List<JSONObject> saved = store.savedGames();
            if (!saved.isEmpty()) restoreMatch(saved.get(0));
        } catch (Exception exception) {
            toast("Une sauvegarde locale n’a pas pu être lue.");
        }
        showHome();
        receiveNavigation(getIntent());
        rewarded.refreshConsent(this::refreshRewardSettings);
        worker.submit(() -> {
            try {
                Lexicon loaded = Lexicon.load(this);
                String source;
                try (InputStream stream = getAssets().open("lexicon-notice.md")) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    for (int length; (length = stream.read(buffer)) >= 0; ) bytes.write(buffer, 0, length);
                    source = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    source = "";
                }
                String sourceNotice = source;
                main.post(() -> {
                    if (isDestroyed()) return;
                    lexicon = loaded;
                    notice = sourceNotice;
                    if (game != null && game.end != null) archiveIfFinished();
                    if (screen == Screen.GAME) {
                        updateArena(-1);
                        startBotIfNeeded();
                    }
                    if (screen == Screen.HOME || screen == Screen.HELP) {
                        Runnable requested = pendingChallenge;
                        pendingChallenge = null;
                        if (requested != null) requested.run();
                        else if (screen == Screen.HOME) showHome();
                    }
                });
            } catch (Exception exception) {
                main.post(() -> {
                    if (isDestroyed()) return;
                    lexiconError = "Le lexique intégré n’a pas pu être chargé.";
                    pendingChallenge = null;
                    if (screen == Screen.HOME) showHome();
                });
            }
        });
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        receiveNavigation(intent);
    }

    private void receiveNavigation(Intent intent) {
        pendingTurnNavigation = null;
        String notified = TurnNotifications.takeDuel(this, intent, socialBaseUrl());
        if (notified != null) {
            if (screen == Screen.GAME && !persist()) return;
            Object navigation = new Object();
            pendingTurnNavigation = navigation;
            socialClient.notifiedDuel(notified, (result, error) -> {
                if (isDestroyed() || pendingTurnNavigation != navigation) return;
                pendingTurnNavigation = null;
                JSONObject duel = result == null ? null : result.optJSONObject("duel");
                if (duel == null || !notified.equals(duel.optString("id"))) {
                    toast(error == null ? getString(R.string.turn_notification_unavailable) : error);
                    return;
                }
                if (lexicon == null && lexiconError == null) {
                    showHome();
                    pendingChallenge = () -> showSocialDuel(duel);
                } else showSocialDuel(duel);
            });
            return;
        }
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())
                && intent.getData() != null && "beta".equals(intent.getData().getHost())
                && "lettrine".equals(intent.getData().getScheme())) {
            confirmBeta(BetaLink.parse(intent.getDataString()));
            return;
        }
        receiveChallenge(intent);
    }

    private void confirmBeta(BetaLink link) {
        if (link == null) {
            toast(getString(R.string.social_beta_invalid));
            return;
        }
        dialog(getString(R.string.social_beta_title), getString(R.string.social_beta_confirm, link.origin()))
                .setNegativeButton(R.string.social_cancel, null)
                .setPositiveButton(R.string.social_beta_connect, (ignored, action) -> {
                    if (screen == Screen.GAME && !persist()) return;
                    preferences.edit().putString("social_endpoint", link.origin())
                            .putString("social_beta_origin", link.origin()).apply();
                    pendingSocialInvite = link.invitation();
                    refreshTurnNotifications();
                    showSocial();
                }).show();
    }

    private void receiveChallenge(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri uri = intent.getData();
        if (uri != null && "lettrine".equals(uri.getScheme()) && "amis".equals(uri.getAuthority())) {
            String token = SocialPanel.invitationToken(intent.getDataString());
            if (token != null) {
                pendingSocialInvite = token;
                showSocial();
            } else toast(getString(R.string.social_navigation_invite_invalid));
            return;
        }
        ChallengeLink invitation = ChallengeLink.parse(intent.getDataString());
        if (invitation == null) {
            toast(getString(R.string.daily_invitation_invalid));
            return;
        }
        if (lexicon == null && lexiconError == null) {
            showHome();
            pendingChallenge = () -> openDailyChallenge(invitation);
        } else if (ready()) {
            openDailyChallenge(invitation);
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        frame.requestLayout();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter changes = new IntentFilter(Intent.ACTION_DATE_CHANGED);
        changes.addAction(Intent.ACTION_TIME_CHANGED);
        changes.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(calendarChanges, changes, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(calendarChanges, changes);
    }

    @Override protected void onResume() {
        super.onResume();
        audio.foreground(true);
        refreshDailyHome();
        refreshTurnNotifications();
    }

    @Override protected void onPause() {
        if (screen == Screen.GAME) persist();
        audio.foreground(false);
        super.onPause();
    }

    @Override protected void onStop() {
        unregisterReceiver(calendarChanges);
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (googleSignIn != null) googleSignIn.cancel();
        socialClient.close();
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        closeLink();
        audio.close();
        if (explanationDialog != null) explanationDialog.dismiss();
        explanationWorker.shutdownNow();
        cancelReview();
        reviewWorker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    @SuppressLint("GestureBackNavigation") // Android 13+ uses the callback registered by page().
    @Override public void onBackPressed() {
        if (screen == Screen.HOME) super.onBackPressed();
        else goBack();
    }

    private void goBack() {
        switch (screen) {
            case HOME -> { }
            case DETAIL -> showHistory();
            case REVIEW -> reviewBack.run();
            case PRACTICE -> leavePractice();
            case SOCIAL_DUEL -> showSocial();
            case TOURNAMENT -> showSocial();
            case TOURNAMENT_ROUND -> showTournament();
            case COLLECTION -> showProfile();
            case SETTINGS -> showProfile();
            case GAME -> leaveGame();
            case SOLO, LOCAL -> leaveGameSetup();
            case BLUETOOTH -> {
                if (link != null && state() == null) closeLink();
                showHome();
            }
            default -> showHome();
        }
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    private void cancelReview() {
        if (reviewTask != null) {
            reviewTask.cancel(true);
            reviewTask = null;
        }
    }

    private void page(Screen next, boolean navigation) {
        if (explanationDialog != null) explanationDialog.dismiss();
        cancelReview();
        if (next != Screen.HOME || screen != Screen.HOME) pendingTurnNavigation = null;
        if (next != Screen.HOME) pendingChallenge = null;
        if (next != Screen.PROFILE) profilePanel = null;
        if (next != Screen.SOCIAL && googleSignIn != null) {
            googleSignIn.cancel();
            googleSignIn = null;
        }
        screen = next;
        if (Build.VERSION.SDK_INT >= 33) {
            if (next == Screen.HOME && backCallback != null) {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
                backCallback = null;
            } else if (next != Screen.HOME && backCallback == null) {
                backCallback = this::goBack;
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
            }
        }
        arena = null;
        frame = new FrameLayout(this);
        frame.setBackgroundColor(ui.palette.page());
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        LinearLayout shell = ui.column();
        frame.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout centered = new FrameLayout(this);
        scroll.addView(centered, new ScrollView.LayoutParams(-1, -2));
        content = ui.column();
        int margin = next == Screen.PRACTICE || next == Screen.TOURNAMENT_ROUND || next == Screen.SOCIAL_DUEL ? 10 : 17;
        content.setPadding(ui.dp(margin), ui.dp(7), ui.dp(margin), ui.dp(16));
        centered.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL));
        LinearLayout capturedContent = content;
        centered.addOnLayoutChangeListener((view, left, top, right, bottom, a, b, c, d) -> {
            int width = Math.min(right - left, ui.dp(530));
            if (width > 0 && capturedContent.getLayoutParams().width != width) {
                capturedContent.getLayoutParams().width = width;
                capturedContent.requestLayout();
            }
        });
        if (navigation) {
            LinearLayout nav = ui.row();
            nav.setBackgroundColor(ui.palette.paper());
            nav.setPadding(ui.dp(10), ui.dp(2), ui.dp(10), ui.dp(2));
            ui.weight(nav, navItem("Jouer", next == Screen.HOME, this::showHome));
            ui.weight(nav, navItem(getString(R.string.social_navigation_friends), next == Screen.SOCIAL, this::showSocial));
            ui.weight(nav, navItem("Historique", next == Screen.HISTORY, this::showHistory));
            ui.weight(nav, navItem(getString(R.string.social_navigation_profile), next == Screen.PROFILE, this::showProfile));
            shell.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        }
        setContentView(frame);
        frame.requestApplyInsets();
    }

    private TextView navItem(String name, boolean active, Runnable action) {
        TextView tab = ui.textButton(name, action);
        tab.setMinHeight(ui.dp(49));
        ui.inset(tab, 1, 7);
        tab.setTextColor(active ? ui.palette.ink() : ui.palette.muted());
        tab.setBackground(ui.touch(active ? ui.palette.ordinary() : ui.palette.paper(), 10, 0));
        return tab;
    }

    private void top(String label, Runnable back, TextView extra) {
        LinearLayout row = ui.row();
        if (back != null) {
            TextView arrow = ui.textButton("‹", back);
            arrow.setTextSize(27);
            arrow.setContentDescription("Retour");
            row.addView(arrow);
            ui.space(row, 5);
        } else {
            TextView badge = ui.text("L", 18, ui.palette.heroInk(), ui.serif);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(ui.shape(ui.palette.deep(), 8));
            row.addView(badge, new LinearLayout.LayoutParams(ui.dp(29), ui.dp(29)));
            ui.space(row, 9);
        }
        ui.weight(row, ui.text(label, 16, ui.palette.ink(), ui.medium));
        if (extra != null) row.addView(extra);
        row.setMinimumHeight(ui.dp(43));
        content.addView(row);
    }

    private void heading(String title, String description) {
        ui.gap(content, 13);
        content.addView(ui.title(title, 33));
        if (!description.isEmpty()) {
            ui.gap(content, 7);
            content.addView(ui.body(description));
        }
        ui.gap(content, 19);
    }

    private void showHome() {
        skinStore.refresh(store, dailyProgress);
        page(Screen.HOME, true);
        homeDate = LocalDate.now();
        TextView rating = ui.text("Elo " + currentRating(), 12, ui.palette.accent(), ui.medium);
        ui.inset(rating, 11, 7);
        rating.setBackground(ui.shape(ui.palette.ordinary(), 14));
        top("Lettrine", null, rating);
        ui.gap(content, 8);
        DailyProgress.Snapshot today = dailyProgress.snapshot(homeDate);
        boolean completed = today.bestScore() != null;
        String status = completed ? DailyResult.bestLabel(ui, today.bestScore()) : getString(R.string.daily_home_open);
        content.addView(DailyChallengeCard.create(ui, DailyResult.dateLabel(homeDate), status,
                DailyResult.streakLabel(ui, today.streak()), getString(completed ? R.string.daily_replay : R.string.daily_play),
                () -> openChallenge(() -> openDailyChallenge(null))));
        MatchCodec.State ongoing = state();
        if (ongoing != null && ongoing.end() == null && (!mode.equals("bluetooth") || bluetoothReady)) {
            ui.gap(content, 13);
            content.addView(ui.button("Reprendre la partie", true, this::showGame));
        }
        ui.gap(content, 10);
        content.addView(ui.option("✦", getString(R.string.practice_first), getString(R.string.practice_first_home),
                () -> openChallenge(() -> openFirstChallenge(this::showHome, false))));
        ui.gap(content, 9);
        content.addView(ui.option("▤", getString(R.string.games_title), getString(R.string.games_home_detail),
                this::showGames));
        ui.gap(content, 19);
        content.addView(ui.micro("NOUVELLE PARTIE", ui.palette.accent()));
        ui.gap(content, 8);
        content.addView(ui.option("✦", "Contre un bot", "Quatre niveaux et un bot adaptatif", () -> {
            if (ready()) openSolo(Screen.HOME);
        }));
        ui.gap(content, 8);
        content.addView(ui.option("2–4", "Entre amis", "À tour de rôle sur cet appareil", () -> {
            if (ready()) openLocal(Screen.HOME);
        }));
        ui.gap(content, 8);
        content.addView(ui.option("↔", "Bluetooth", "Deux appareils déjà associés", () -> {
            if (ready()) showBluetooth();
        }));
        ui.gap(content, 8);
        content.addView(ui.option("?", getString(R.string.help_title), getString(R.string.help_entry), this::showHelp));
        if (lexicon == null) {
            ui.gap(content, 11);
            content.addView(ui.body(lexiconError == null ? "Chargement du lexique…" : lexiconError));
        }
    }

    private int currentRating() { return store == null ? DEFAULT_RATING : store.elo(); }

    private void refreshDailyHome() {
        if (screen == Screen.HOME && !LocalDate.now().equals(homeDate)) showHome();
    }

    private boolean ready() {
        if (lexicon != null) return true;
        toast(lexiconError == null ? "Le lexique se charge…" : lexiconError);
        return false;
    }

    private void openChallenge(Runnable action) {
        if (lexicon == null && lexiconError == null) pendingChallenge = action;
        if (ready()) action.run();
    }

    private void openFirstChallenge(Runnable back, boolean tutorial) {
        PracticePosition position = PracticePosition.first(lexicon);
        if (position == null) {
            toast(getString(R.string.practice_first_unavailable));
            return;
        }
        showPractice(getString(tutorial ? R.string.help_tutorial_intro : R.string.practice_first),
                getString(tutorial ? R.string.help_tutorial_copy : R.string.practice_first_goal),
                position, true, tutorial, back);
    }

    private void showHelp() {
        page(Screen.HELP, false);
        top("Lettrine", this::showHome, null);
        heading(getString(R.string.help_title), "");
        content.addView(new HelpPanel(ui, () -> openChallenge(() -> openFirstChallenge(this::showHelp, true))).view());
    }

    private void openDailyChallenge(ChallengeLink invitation) {
        LocalDate today = LocalDate.now();
        LocalDate date = invitation == null ? today : invitation.date();
        DailyChallenge challenge = DailyChallenge.forDate(date, lexicon);
        if (invitation != null && !invitation.matches(challenge, lexicon)) {
            toast(getString(R.string.daily_invitation_incompatible));
            return;
        }
        if (challenge == null) {
            toast(getString(R.string.daily_unavailable));
            return;
        }
        page(Screen.PRACTICE, false);
        practiceBack = this::showHome;
        top("Lettrine", this::leavePractice, null);
        ui.gap(content, 5);
        String title = getString(invitation == null ? R.string.daily_title : R.string.daily_invitation_title);
        String goal = getString(R.string.daily_goal, challenge.openingWord());
        if (invitation != null) goal = getString(R.string.daily_invitation_goal,
                DailyResult.points(ui, invitation.score())) + "\n" + goal;
        content.addView(DailyChallengeCard.heading(ui, title, DailyResult.dateLabel(date), goal));
        ui.gap(content, 11);
        DailyResult dailyResult = new DailyResult(ui, date.equals(today) ? dailyProgress : null,
                challenge, lexicon, invitation);
        content.addView(new PracticePanel(ui, challenge.position(), lexicon, false, dailyResult).view());
    }

    private void practiceReviewedTurn(MatchReview.Turn turn) {
        PracticePosition position = turn.practice();
        if (position == null || !ready()) return;
        FrameLayout reviewFrame = frame;
        LinearLayout reviewContent = content;
        showPractice(getString(R.string.practice_retry_title, turn.index() + 1),
                getString(R.string.practice_retry_goal), position, false, false, () -> {
                    screen = Screen.REVIEW;
                    frame = reviewFrame;
                    content = reviewContent;
                    setContentView(frame);
                    frame.requestApplyInsets();
                });
    }

    private void showPractice(String title, String goal, PracticePosition position, boolean first,
                              boolean tutorial, Runnable back) {
        page(Screen.PRACTICE, false);
        practiceBack = back;
        top(first ? "Lettrine" : getString(R.string.match_review), this::leavePractice, null);
        heading(title, goal);
        content.addView(new PracticePanel(ui, position, lexicon, first, tutorial).view());
    }

    private void leavePractice() {
        Runnable back = practiceBack;
        practiceBack = null;
        back.run();
    }

    private void showGames() {
        page(Screen.GAMES, false);
        top("Lettrine", this::showHome, null);
        heading(getString(R.string.games_title), "");
        List<JSONObject> games;
        try {
            games = store == null ? List.of() : store.savedGames();
        } catch (Exception exception) {
            games = List.of();
            toast(getString(R.string.games_read_error));
        }
        boolean shown = false;
        for (JSONObject saved : games) {
            try {
                MatchCodec.State match = MatchCodec.readState(saved);
                String title = String.join(" · ", match.names());
                boolean solo = "solo".equals(saved.getString("mode"));
                String summary = getString(solo ? R.string.games_solo_score : R.string.games_local_score,
                        Arrays.stream(match.scores()).mapToObj(Integer::toString)
                                .collect(java.util.stream.Collectors.joining("–")),
                        match.history().size() + 1);
                if (match.end() != null) summary += " · " + getString(R.string.games_pending_archive);
                else if (solo && match.active() == 0) summary += " · " + getString(R.string.games_your_turn);
                LinearLayout card = ui.card();
                card.addView(ui.option(solo ? "✦" : "2–4", title, summary, () -> resumeMatch(match.id())));
                if (match.end() == null) {
                    card.addView(ui.textButton(getString(R.string.games_abandon), () -> abandonMatch(match.id(), title)));
                }
                content.addView(card);
                ui.gap(content, 9);
                shown = true;
            } catch (JSONException exception) {
                toast(getString(R.string.games_read_error));
            }
        }
        if (link != null && bluetoothReady && state() != null && state().end() == null) {
            content.addView(ui.option("↔", "Bluetooth", getString(R.string.games_bluetooth_active), () -> {
                gameBack = Screen.GAMES;
                showGame();
            }));
            ui.gap(content, 9);
            shown = true;
        }
        if (!shown) {
            content.addView(ui.body(getString(R.string.games_empty)));
            ui.gap(content, 14);
        }
        content.addView(ui.button(getString(R.string.games_new_solo), true, () -> {
            if (ready()) openSolo(Screen.GAMES);
        }));
        ui.gap(content, 7);
        content.addView(ui.button(getString(R.string.games_new_local), false, () -> {
            if (ready()) openLocal(Screen.GAMES);
        }));
    }

    private void openSolo(Screen origin) {
        setupBack = origin;
        showSolo(soloSelection);
    }

    private void openLocal(Screen origin) {
        setupBack = origin;
        showLocal(2, null);
    }

    private void leaveGameSetup() {
        if (setupBack == Screen.GAMES) showGames();
        else showHome();
    }

    private void leaveGame() {
        if (!persist()) return;
        if (gameBack == Screen.GAMES) showGames();
        else showHome();
    }

    private void resumeMatch(String id) {
        if (!ready()) return;
        if (game != null && id.equals(game.id)) {
            archiveIfFinished();
            gameBack = Screen.GAMES;
            showGame();
            return;
        }
        switchMatch(() -> {
            try {
                JSONObject saved = store.loadGame(id);
                if (saved == null) {
                    showGames();
                    return;
                }
                restoreMatch(saved);
                archiveIfFinished();
                gameBack = Screen.GAMES;
                showGame();
            } catch (Exception exception) {
                toast(getString(R.string.games_read_error));
            }
        });
    }

    private void abandonMatch(String id, String title) {
        dialog(getString(R.string.games_abandon_title), title)
                .setNegativeButton("Annuler", null)
                .setPositiveButton(R.string.games_abandon, (ignored, which) -> {
                    try {
                        store.removeGame(id);
                        if (game != null && id.equals(game.id)) resetMatch();
                        showGames();
                    } catch (Exception exception) {
                        toast(getString(R.string.games_write_error));
                    }
                }).show();
    }

    private void showSolo(int selection) {
        if (soloSelection != selection) {
            soloSelection = selection;
            preferences.edit().putInt("solo_level", selection).apply();
        }
        page(Screen.SOLO, false);
        top("Partie solo", this::leaveGameSetup, null);
        heading("Choisir un bot", "Elo local " + currentRating() + "  ·  Niveaux indicatifs");
        for (int level = 1; level <= Bot.ADAPTIVE; level++) {
            int id = level;
            String description = level == 4 && lexicon.trained
                    ? "Évaluation du chevalet entraînée par duels entre bots." : Bot.DESCRIPTIONS[level];
            String caption = level == Bot.ADAPTIVE ? description : Bot.RATINGS[level] + " Elo  ·  " + description;
            LinearLayout card = ui.option(level == selection ? "✓" : "·", Bot.NAMES[level], caption,
                    () -> showSolo(id));
            if (level == selection) card.setBackground(ui.touch(ui.palette.ordinary(), 16, ui.palette.accent()));
            content.addView(card);
            ui.gap(content, 8);
        }
        ui.gap(content, 7);
        content.addView(ui.button("Commencer", true, () -> switchMatch(() ->
                newMatch("solo", new String[]{"Vous", Bot.NAMES[selection]}, new int[]{0, selection}))));
    }

    private void showLocal(int count, String[] previous) {
        page(Screen.LOCAL, false);
        top("Partie locale", this::leaveGameSetup, null);
        heading("Entre amis", "Le chevalet est masqué à chaque changement de joueur.");
        LinearLayout counts = ui.row();
        List<EditText> names = new ArrayList<>();
        for (int number = 2; number <= 4; number++) {
            int next = number;
            if (number > 2) ui.space(counts, 8);
            ui.weight(counts, ui.button(number + " joueurs", count == number, () -> showLocal(next, getNames(names))));
        }
        content.addView(counts);
        ui.gap(content, 16);
        for (int index = 0; index < count; index++) {
            String fallback = "Joueur " + (index + 1);
            EditText name = input(fallback, previous != null && previous.length > index ? previous[index] : "");
            names.add(name);
            content.addView(name, new LinearLayout.LayoutParams(-1, ui.dp(48)));
            ui.gap(content, 8);
        }
        ui.gap(content, 9);
        content.addView(ui.button("Commencer", true, () -> switchMatch(() -> {
            String[] entered = getNames(names);
            for (int i = 0; i < entered.length; i++) if (entered[i].isEmpty()) entered[i] = "Joueur " + (i + 1);
            newMatch("local", entered, new int[entered.length]);
        })));
        content.setFocusableInTouchMode(true);
        content.requestFocus();
    }

    private EditText input(String hint, String value) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(18)});
        field.setTextSize(15);
        field.setTextColor(ui.palette.ink());
        field.setHintTextColor(ui.palette.muted());
        field.setHint(hint);
        field.setText(value);
        field.setBackground(ui.shape(ui.palette.paper(), 12, ui.palette.line()));
        ui.inset(field, 12, 7);
        return field;
    }

    private static String[] getNames(List<EditText> names) {
        return names.stream().map(field -> field.getText().toString().trim()).toArray(String[]::new);
    }

    private void switchMatch(Runnable next) {
        if (!persist()) return;
        if (link == null) {
            next.run();
            return;
        }
        dialog(getString(R.string.games_bluetooth_title), getString(R.string.games_bluetooth_body))
                .setNegativeButton("Annuler", null)
                .setPositiveButton(R.string.games_bluetooth_continue, (view, which) -> {
                    resetMatch();
                    next.run();
                }).show();
    }

    private void newMatch(String newMode, String[] names, int[] levels) {
        if (!ready() || store == null) return;
        FrenchGame next = new FrenchGame(names, levels);
        int rating = currentRating();
        try {
            store.saveGame(MatchCodec.saved(next, newMode, rating, 0, 0, false));
        } catch (Exception exception) {
            toast(getString(R.string.games_write_error));
            return;
        }
        resetMatch();
        game = next;
        mode = newMode;
        startRating = rating;
        privacy = mode.equals("local");
        gameBack = setupBack;
        showGame();
    }

    private void restoreMatch(JSONObject saved) throws JSONException {
        FrenchGame restored = MatchCodec.restore(saved);
        String savedMode = saved.getString("mode");
        JSONArray draft = saved.optJSONArray("draft");
        List<FrenchGame.Tile> tiles = draft == null ? List.of() : MatchCodec.readTiles(draft, true);
        resetMatch();
        game = restored;
        mode = savedMode;
        startRating = saved.optInt("start_rating", DEFAULT_RATING);
        botLevelSum = saved.optInt("level_sum");
        botLevelCount = saved.optInt("level_count");
        assisted = saved.optBoolean("assisted");
        privacy = mode.equals("local") && game.end == null;
        MatchCodec.State current = state();
        if (current.end() != null) return;
        for (FrenchGame.Tile tile : tiles) {
            if (tile.rackIndex() < 0 || tile.rackIndex() >= current.rack().size()
                    || current.board()[tile.position()] != 0 || stagedAt(tile.position()) >= 0
                    || staged.stream().anyMatch(previous -> previous.rackIndex() == tile.rackIndex())) continue;
            char letter = current.rack().get(tile.rackIndex());
            if (tile.blank() ? letter == FrenchGame.JOKER : letter == tile.letter()) staged.add(tile);
        }
    }

    private MatchCodec.State state() {
        if (game == null) return guestState;
        int seat = mode.equals("solo") || mode.equals("bluetooth") ? 0 : game.active;
        return MatchCodec.forLocal(game, seat);
    }

    private boolean myTurn(MatchCodec.State state) {
        return state != null && state.end() == null && state.active() == state.seat()
                && !privacy && !waiting && (mode.equals("local") || !mode.equals("bluetooth") || link != null);
    }

    private void clearStaged() {
        staged.clear();
        selected = -1;
    }

    private void resetMatch() {
        closeLink();
        mode = "";
        game = null;
        guestState = null;
        privacy = botThinking = analysing = assisted = waiting = false;
        lastRackTurn = -1;
        clearStaged();
    }

    private boolean persist() {
        if (store == null || game == null || mode.equals("bluetooth") || game.end != null) return true;
        try {
            store.saveGame(savedMatch());
            return true;
        } catch (Exception exception) {
            toast(getString(R.string.games_write_error));
            return false;
        }
    }

    private JSONObject savedMatch() {
        JSONObject saved = MatchCodec.saved(game, mode, startRating, botLevelSum, botLevelCount, assisted);
        if (!staged.isEmpty()) MatchCodec.put(saved, "draft", MatchCodec.tiles(staged, true));
        return saved;
    }

    private void archiveIfFinished() {
        MatchCodec.State finished = state();
        if (finished == null || finished.end() == null || store == null || lexicon == null) return;
        int before = 0;
        int after = 0;
        int opponent = 0;
        if (mode.equals("solo") && !assisted) {
            before = currentRating();
            int level = finished.levels()[1];
            opponent = level == Bot.ADAPTIVE
                    ? (botLevelCount == 0 ? Bot.RATINGS[2] : Math.round(botLevelSum / (float) botLevelCount))
                    : Bot.RATINGS[level];
            after = GameStore.adjustedRating(before, opponent,
                    Integer.compare(finished.scores()[0], finished.scores()[1]));
        }
        try {
            JSONObject archived = MatchCodec.finished(finished, mode, lexicon.label, game == null ? null : game.seed,
                    before, after, opponent);
            MatchCodec.put(archived, "lexicon_sha256", lexicon.hash);
            if (assisted) MatchCodec.put(archived, "assisted", true);
            store.archive(archived);
            skinStore.refresh(store, dailyProgress);
            if (!mode.equals("bluetooth")) store.removeGame(finished.id());
        } catch (Exception exception) {
            toast("L’historique n’a pas pu être enregistré.");
        }
    }

    private AlertDialog.Builder dialog(String title, String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(title);
        if (message != null) builder.setMessage(message);
        return builder;
    }

    private void explainMove(List<FrenchGame.Move> history, int moveIndex) {
        MoveExplanation explanation = MoveExplanation.create(history, moveIndex);
        if (explanation == null) return;
        showExplanation(getString(R.string.move_explanation_title, moveIndex + 1),
                explanation.facts(), explanation.options(), explanation::text);
    }

    private void explainReview(MatchReview.Turn turn) {
        showExplanation(getString(R.string.move_explanation_title, turn.index() + 1),
                turn.facts(), turn.options(), turn::text);
    }

    private void showExplanation(String title, String facts, List<String> options, IntFunction<String> text) {
        if (explanationDialog != null && explanationDialog.isShowing()) return;

        LinearLayout panel = ui.column();
        ui.inset(panel, 24, 12);
        TextView body = ui.body(text.apply(0));
        body.setTextColor(ui.palette.ink());
        body.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(body);
        ui.gap(panel, 9);
        TextView status = ui.body(getString(options.isEmpty()
                ? R.string.move_explanation_partial : R.string.move_explanation_loading));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel, new ScrollView.LayoutParams(-1, -2));
        AlertDialog active = dialog(title, null)
                .setView(scroll)
                .setPositiveButton(R.string.move_explanation_close, null)
                .show();
        explanationDialog = active;
        Context context = getApplicationContext();
        Future<?> request = options.isEmpty() ? null : explanationWorker.submit(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            int index = -1;
            try {
                index = new OfflineMoveModel(context).choose(facts, options);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception | LinkageError ignored) {
                // The verified explanation stays available if local inference fails.
            }
            if (Thread.currentThread().isInterrupted()) return;
            boolean modelUsed = index >= 0 && index < options.size();
            int selected = modelUsed ? index : 0;
            main.post(() -> {
                if (explanationDialog != active || !active.isShowing() || isDestroyed()) return;
                body.setText(text.apply(selected));
                status.setText(modelUsed ? R.string.move_explanation_model : R.string.move_explanation_engine);
            });
        });
        active.setOnDismissListener(ignored -> {
            if (request != null) request.cancel(true);
            if (explanationDialog == active) explanationDialog = null;
        });
    }

    private final class Arena {
        final LinearLayout scores = ui.row();
        final LinearLayout rack = ui.row();
        final TextView status = ui.body("");
        final TextView preview = ui.text("", 14, ui.palette.ink(), ui.medium);
        final TextView confirm = ui.button(getString(R.string.practice_ready), true, MainActivity.this::commit);
        final TextView recall = ui.textButton("Retirer", () -> {
            clearStaged();
            updateArena(-1);
        });
        final TextView tools = ui.textButton("Options", MainActivity.this::turnOptions);
        final TextView review = ui.button(getString(R.string.match_review), true, MainActivity.this::reviewFinishedGame);
        final TextView explain = ui.button(getString(R.string.move_explanation_last), false, () -> {
            MatchCodec.State current = state();
            if (current != null) explainMove(current.history(), current.history().size() - 1);
        });
        final BoardView board = new BoardView(ui, new BoardView.Interaction() {
            @Override public void tap(int position) { boardTap(position); }
            @Override public void drop(int position, int rackIndex) { place(position, rackIndex); }
            @Override public boolean dragTile(int position) {
                return myTurn(state()) && !analysing && stagedAt(position) >= 0;
            }
            @Override public void moveTile(int from, int to) { moveStaged(from, to); }
        });
    }

    private void showGame() {
        if (state() == null) { showHome(); return; }
        page(Screen.GAME, false);
        top(mode.equals("local") ? "Entre amis" : mode.equals("bluetooth") ? "Bluetooth" : "Partie solo",
                this::leaveGame, mode.equals("bluetooth") ? null : ui.textButton(getString(R.string.games_title), () -> {
                    if (persist()) showGames();
                }));
        arena = new Arena();
        content.addView(arena.scores);
        ui.gap(content, 8);
        LinearLayout.LayoutParams reviewPosition = new LinearLayout.LayoutParams(-1, -2);
        reviewPosition.bottomMargin = ui.dp(8);
        content.addView(arena.review, reviewPosition);
        content.addView(arena.board, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout controls = ui.row();
        TextView minus = ui.textButton("−", () -> arena.board.zoomBy(0.8f));
        minus.setContentDescription("Réduire le zoom");
        TextView plus = ui.textButton("+", () -> arena.board.zoomBy(1.25f));
        plus.setContentDescription("Agrandir le plateau");
        TextView reset = ui.textButton("", () -> arena.board.resetZoom());
        arena.board.onZoom(zoom -> reset.setText(getString(R.string.practice_zoom_percent, Math.round(zoom * 100))));
        reset.setContentDescription("Réinitialiser le zoom");
        ui.weight(controls, arena.status);
        controls.addView(minus);
        controls.addView(reset);
        controls.addView(plus);
        content.addView(controls);
        content.addView(arena.rack);
        ui.gap(content, 11);
        ui.inset(arena.preview, 12, 10);
        arena.preview.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(arena.preview, new LinearLayout.LayoutParams(-1, -2));
        ui.gap(content, 10);
        LinearLayout actions = ui.row();
        ui.weight(actions, arena.confirm);
        ui.space(actions, 7);
        actions.addView(arena.recall);
        actions.addView(arena.tools);
        content.addView(actions);
        LinearLayout.LayoutParams explanationPosition = new LinearLayout.LayoutParams(-1, -2);
        explanationPosition.topMargin = ui.dp(7);
        content.addView(arena.explain, explanationPosition);
        updateArena(-1);
        startBotIfNeeded();
    }

    private void updateArena(int animatedPosition) {
        if (arena == null || screen != Screen.GAME) return;
        MatchCodec.State current = state();
        if (current == null) return;
        arena.scores.removeAllViews();
        for (int i = 0; i < current.names().length; i++) {
            if (i != 0) ui.space(arena.scores, 5);
            LinearLayout seat = ui.column();
            ui.inset(seat, 8, 7);
            boolean active = current.end() == null && i == current.active();
            seat.setBackground(ui.shape(active ? ui.palette.ordinary() : ui.palette.paper(),
                    11, active ? ui.palette.accent() : ui.palette.line()));
            TextView name = ui.text(current.names()[i], 11, ui.palette.muted(), ui.medium);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            seat.addView(name);
            ui.gap(seat, 4);
            seat.addView(ui.title(Integer.toString(current.scores()[i]), 21));
            ui.weight(arena.scores, seat);
        }

        List<FrenchGame.Tile> recent = current.history().isEmpty() ? List.of()
                : current.history().get(current.history().size() - 1).tiles();
        arena.board.update(current.board(), current.blanks(), staged, recent, animatedPosition);
        arena.status.setText(current.end() != null ? current.history().size() + " coups"
                : "Sac " + current.bagSize() + "  ·  Tour " + (current.history().size() + 1));
        arena.rack.removeAllViews();

        boolean canPlay = myTurn(current) && !analysing;
        FrenchGame.Preview result = evaluate(current);
        boolean draft = canPlay && !staged.isEmpty();
        arena.preview.setBackground(ui.shape(draft && result.valid() ? ui.palette.ordinary() : ui.palette.paper(),
                12, draft && result.valid() ? ui.palette.accent() : ui.palette.line()));
        if (current.end() != null) {
            ui.weight(arena.rack, ui.button("Voir l’historique", true, this::showHistory));
            arena.preview.setText(resultTitle(current));
        } else if (privacy) {
            ui.weight(arena.rack, ui.button("Afficher mon chevalet", true, () -> {
                privacy = false;
                updateArena(-1);
            }));
            arena.preview.setText(getString(R.string.local_pass_device, current.names()[current.active()]));
        } else if (mode.equals("bluetooth") && !bluetoothReady) {
            arena.preview.setText(R.string.bluetooth_disconnected);
        } else if (!myTurn(current)) {
            arena.preview.setText(waiting ? "Validation par l’hôte…"
                    : current.names()[current.active()] + (botThinking ? " réfléchit…" : " joue."));
        } else {
            renderRack(current);
            arena.preview.setText(analysing ? "Analyse en cours…" : staged.isEmpty()
                    ? getString(R.string.practice_guide)
                    : result.valid() ? getString(R.string.practice_status_valid) + "  ·  " + result.word()
                    + "  ·  " + getResources().getQuantityString(R.plurals.practice_points, result.score(), result.score())
                    : getString(R.string.practice_status_invalid) + "\n" + result.error());
        }
        ui.enabled(arena.confirm, canPlay && result.valid());
        ui.enabled(arena.recall, canPlay && !staged.isEmpty());
        ui.enabled(arena.tools, canPlay);
        arena.explain.setVisibility(current.history().isEmpty() ? View.GONE : View.VISIBLE);
        arena.review.setVisibility(current.end() == null ? View.GONE : View.VISIBLE);
    }

    private void renderRack(MatchCodec.State current) {
        int turn = current.history().size();
        if (lastRackTurn != turn || rackOrder.size() != current.rack().size()) {
            rackOrder.clear();
            for (int i = 0; i < current.rack().size(); i++) rackOrder.add(i);
            lastRackTurn = turn;
        }
        boolean[] occupied = new boolean[FrenchGame.RACK_SIZE];
        for (FrenchGame.Tile tile : staged) occupied[tile.rackIndex()] = true;
        for (int column = 0; column < FrenchGame.RACK_SIZE; column++) {
            if (column != 0) ui.space(arena.rack, 4);
            if (column >= rackOrder.size()) {
                ui.weight(arena.rack, new View(this));
                continue;
            }
            int index = rackOrder.get(column);
            char letter = current.rack().get(index);
            LinearLayout token = ui.column();
            token.setGravity(Gravity.CENTER);
            token.setMinimumHeight(ui.dp(49));
            token.setBackground(ui.rackTile(index == selected));
            TextView glyph = ui.text(occupied[index] ? " " : String.valueOf(letter),
                    22, ui.tileInk(), ui.medium);
            glyph.setGravity(Gravity.CENTER);
            token.addView(glyph);
            if (!occupied[index]) {
                TextView value = ui.text(Integer.toString(FrenchGame.value(letter)), 10, ui.tileInk(), ui.medium);
                value.setGravity(Gravity.CENTER);
                token.addView(value);
                token.setContentDescription(letter == '?' ? "Joker" : "Lettre " + letter);
                token.setOnClickListener(view -> {
                    if (analysing) return;
                    selected = selected == index ? -1 : index;
                    updateArena(-1);
                });
                ui.dragTile(token, index, letter, () -> !analysing && myTurn(state()));
            } else token.setAlpha(0.42f);
            ui.weight(arena.rack, token);
        }
    }

    private FrenchGame.Preview evaluate(MatchCodec.State current) {
        if (lexicon == null || staged.isEmpty()) return FrenchGame.Preview.invalid("Posez des lettres.");
        return FrenchGame.preview(current.board(), current.blanks(), current.rack(), lexicon, staged);
    }

    private void boardTap(int position) {
        MatchCodec.State current = state();
        if (!myTurn(current) || analysing) return;
        int index = stagedAt(position);
        if (index >= 0) {
            selected = staged.remove(index).rackIndex();
            updateArena(-1);
            return;
        }
        if (selected >= 0) place(position, selected);
    }

    private int stagedAt(int position) {
        for (int i = 0; i < staged.size(); i++) if (staged.get(i).position() == position) return i;
        return -1;
    }

    private void moveStaged(int from, int to) {
        MatchCodec.State current = state();
        if (!myTurn(current) || analysing || to < 0 || current.board()[to] != 0 || stagedAt(to) >= 0) return;
        int index = stagedAt(from);
        if (index < 0) return;
        FrenchGame.Tile tile = staged.get(index);
        staged.set(index, new FrenchGame.Tile(to, tile.rackIndex(), tile.letter(), tile.blank()));
        audio.tile();
        updateArena(to);
    }

    private void place(int position, int rackIndex) {
        MatchCodec.State current = state();
        if (!myTurn(current) || analysing || current.board()[position] != 0
                || rackIndex < 0 || rackIndex >= current.rack().size()) return;
        for (FrenchGame.Tile tile : staged) {
            if (tile.position() == position || tile.rackIndex() == rackIndex) return;
        }
        char letter = current.rack().get(rackIndex);
        if (letter == FrenchGame.JOKER) {
            String[] letters = new String[26];
            for (int i = 0; i < letters.length; i++) letters[i] = Character.toString((char) ('A' + i));
            dialog("Quelle lettre pour le joker ?", null)
                    .setItems(letters, (ignored, index) -> stage(position, rackIndex, (char) ('A' + index), true))
                    .setNegativeButton("Annuler", null).show();
        } else stage(position, rackIndex, letter, false);
    }

    private void stage(int position, int rackIndex, char letter, boolean blank) {
        if (!myTurn(state())) return;
        staged.add(new FrenchGame.Tile(position, rackIndex, letter, blank));
        selected = -1;
        if (arena != null) arena.board.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        audio.tile();
        updateArena(position);
    }

    private void commit() {
        MatchCodec.State current = state();
        if (!myTurn(current)) return;
        FrenchGame.Preview preview = evaluate(current);
        if (!preview.valid()) { toast(preview.error()); return; }
        action("play", List.copyOf(staged), List.of());
    }

    private void action(String type, List<FrenchGame.Tile> tiles, List<Integer> exchange) {
        MatchCodec.State current = state();
        if (!myTurn(current)) return;
        if (game == null && mode.equals("bluetooth")) {
            waiting = true;
            link.send(MatchCodec.object("type", "move", "action", type, "turn", current.history().size(),
                    "tiles", MatchCodec.tiles(tiles, true), "exchange", new JSONArray(exchange)));
            updateArena(-1);
            return;
        }
        String error = apply(type, tiles, exchange);
        if (error != null) toast(error);
        else {
            if (type.equals("play")) audio.played();
            afterMove();
        }
    }

    private String apply(String type, List<FrenchGame.Tile> tiles, List<Integer> exchange) {
        try {
            switch (type) {
                case "play" -> {
                    FrenchGame.Preview preview = game.play(tiles, lexicon);
                    if (!preview.valid()) return preview.error();
                }
                case "pass" -> game.pass();
                case "exchange" -> game.exchange(exchange);
                default -> { return "Action inconnue."; }
            }
            return null;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return exception.getMessage();
        }
    }

    private void afterMove() {
        clearStaged();
        lastRackTurn = -1;
        privacy = mode.equals("local") && game.end == null;
        if (game.end == null) persist();
        else if (store != null && !mode.equals("bluetooth")) {
            try {
                store.saveGame(savedMatch());
            } catch (Exception exception) {
                toast(getString(R.string.games_write_error));
            }
        }
        archiveIfFinished();
        if (mode.equals("bluetooth") && bluetoothHost && link != null) {
            link.send(MatchCodec.visible(game, 1));
        }
        List<FrenchGame.Tile> last = game.history.get(game.history.size() - 1).tiles();
        updateArena(last.isEmpty() ? -1 : last.get(0).position());
        if (screen == Screen.GAMES) showGames();
        startBotIfNeeded();
    }

    private void startBotIfNeeded() {
        if (game == null || !mode.equals("solo") || game.end != null || game.active != 1
                || botThinking || lexicon == null) return;
        botThinking = true;
        updateArena(-1);
        FrenchGame snapshot = game;
        int turn = snapshot.history.size();
        int rating = startRating;
        worker.submit(() -> {
            Bot.Choice result;
            try {
                result = Bot.choose(snapshot, lexicon, snapshot.levels[1], rating);
            } catch (RuntimeException exception) {
                result = new Bot.Choice(List.of(), List.of(), 2, 0);
            }
            Bot.Choice move = result;
            main.postDelayed(() -> {
                if (game != snapshot || game.history.size() != turn || isDestroyed()) return;
                botThinking = false;
                botLevelSum += Bot.RATINGS[move.level()];
                botLevelCount++;
                String type = !move.tiles().isEmpty() ? "play" : !move.exchange().isEmpty() ? "exchange" : "pass";
                if (apply(type, move.tiles(), move.exchange()) != null) game.pass();
                afterMove();
            }, 210);
        });
    }

    private void turnOptions() {
        MatchCodec.State current = state();
        if (!myTurn(current)) return;
        String[] choices = {"Mélanger le chevalet", "Échanger des lettres", "Passer", "Chercher un coup"};
        dialog("Options du tour", null).setItems(choices, (ignored, choice) -> {
            switch (choice) {
                case 0 -> { Collections.shuffle(rackOrder); updateArena(-1); }
                case 1 -> exchangeDialog(current);
                case 2 -> dialog("Passer ce tour ?", "Les lettres du chevalet sont conservées.")
                        .setNegativeButton("Annuler", null)
                        .setPositiveButton("Passer", (view, button) -> action("pass", List.of(), List.of())).show();
                case 3 -> analyzeCurrent(current);
                default -> { }
            }
        }).show();
    }

    private void exchangeDialog(MatchCodec.State current) {
        if (current.bagSize() < FrenchGame.RACK_SIZE) { toast("Il faut sept lettres dans le sac."); return; }
        String[] labels = new String[current.rack().size()];
        for (int i = 0; i < labels.length; i++) labels[i] = Character.toString(current.rack().get(i));
        boolean[] checked = new boolean[labels.length];
        dialog("Échanger les lettres cochées", null)
                .setMultiChoiceItems(labels, checked, (view, index, value) -> checked[index] = value)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Échanger", (view, button) -> {
                    List<Integer> exchange = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) exchange.add(i);
                    if (exchange.isEmpty()) toast("Sélectionnez au moins une lettre.");
                    else action("exchange", List.of(), exchange);
                }).show();
    }

    private void analyzeCurrent(MatchCodec.State current) {
        if (mode.equals("bluetooth") || mode.equals("local")) {
            toast("L’aide au placement est disponible en solo.");
            return;
        }
        analysing = true;
        FrenchGame gameAtRequest = game;
        updateArena(-1);
        worker.submit(() -> {
            Bot.Choice candidate;
            try {
                candidate = Bot.choose(current.board().clone(), current.blanks().clone(), List.copyOf(current.rack()),
                        current.bagSize(), lexicon, 4, 0);
            } catch (RuntimeException exception) {
                candidate = null;
            }
            Bot.Choice result = candidate;
            main.post(() -> {
                MatchCodec.State latest = state();
                if (game != gameAtRequest || latest == null || !latest.id().equals(current.id())
                        || latest.history().size() != current.history().size()) return;
                analysing = false;
                if (screen != Screen.GAME) return;
                if (result == null || result.tiles().isEmpty()) {
                    updateArena(-1);
                    toast("Aucun placement trouvé.");
                    return;
                }
                clearStaged();
                staged.addAll(result.tiles());
                assisted = true;
                persist();
                updateArena(result.tiles().get(0).position());
                toast("Placement proposé : " + result.score() + " points. Elo inchangé pour cette partie.");
            });
        });
    }

    private String resultTitle(MatchCodec.State current) {
        int maximum = Arrays.stream(current.scores()).max().orElse(0);
        List<String> winners = new ArrayList<>();
        for (int i = 0; i < current.scores().length; i++) {
            if (current.scores()[i] == maximum) winners.add(current.names()[i]);
        }
        return winners.size() == 1 ? winners.get(0) + " remporte la partie."
                : "Égalité : " + String.join(", ", winners) + ".";
    }

    private BluetoothAdapter adapter() {
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }

    private boolean bluetoothPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void showBluetooth() {
        page(Screen.BLUETOOTH, false);
        top("Bluetooth", () -> {
            if (state() == null) closeLink();
            showHome();
        }, null);
        heading("Jouer à deux", "Associez d’abord les téléphones dans les réglages Bluetooth d’Android.");
        if (link != null && state() != null && mode.equals("bluetooth")) {
            content.addView(ui.button("Reprendre la partie", true, this::showGame));
            return;
        }
        if (link != null) {
            content.addView(ui.body(bluetoothStatus));
            ui.gap(content, 11);
            content.addView(ui.button("Annuler la connexion", false, () -> {
                closeLink();
                showBluetooth();
            }));
            return;
        }
        BluetoothAdapter adapter = adapter();
        if (adapter == null) {
            content.addView(ui.body("Cet appareil ne propose pas le Bluetooth classique."));
            return;
        }
        if (!bluetoothPermission()) {
            String permission = Manifest.permission.BLUETOOTH_CONNECT;
            if (preferences.getBoolean("bluetooth_permission_requested", false)
                    && !shouldShowRequestPermissionRationale(permission)) {
                content.addView(ui.body("Autorisez les appareils à proximité dans les réglages de Lettrine."));
                ui.gap(content, 8);
                content.addView(ui.button("Ouvrir les réglages de Lettrine", true, () -> startActivityForResult(
                        new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)),
                        APP_SETTINGS)));
            } else {
                content.addView(ui.button("Autoriser les appareils à proximité", true, () -> {
                    preferences.edit().putBoolean("bluetooth_permission_requested", true).apply();
                    requestPermissions(new String[]{permission}, BLUETOOTH_PERMISSION);
                }));
            }
            return;
        }
        if (!adapter.isEnabled()) {
            content.addView(ui.button("Activer le Bluetooth", true, this::enableBluetooth));
            return;
        }
        List<BluetoothDevice> devices;
        try {
            devices = new ArrayList<>(adapter.getBondedDevices());
        } catch (SecurityException exception) {
            content.addView(ui.body("L’autorisation Bluetooth n’est plus disponible."));
            return;
        }
        EditText name = input("Votre prénom", bluetoothName);
        content.addView(name, new LinearLayout.LayoutParams(-1, ui.dp(48)));
        ui.gap(content, 11);
        content.addView(ui.button("Héberger", true, () -> {
            String entered = name.getText().toString().trim();
            switchMatch(() -> beginLink(null, entered));
        }));
        ui.gap(content, 15);
        content.addView(ui.micro("APPAREILS ASSOCIÉS", ui.palette.accent()));
        ui.gap(content, 8);
        devices.sort((left, right) -> deviceName(left).compareToIgnoreCase(deviceName(right)));
        if (devices.isEmpty()) content.addView(ui.body("Aucun appareil associé."));
        for (BluetoothDevice device : devices) {
            content.addView(ui.option("↔", deviceName(device), "Rejoindre une partie hébergée", () -> {
                String entered = name.getText().toString().trim();
                switchMatch(() -> beginLink(device, entered));
            }));
            ui.gap(content, 8);
        }
        if (!bluetoothStatus.isEmpty()) {
            ui.gap(content, 8);
            content.addView(ui.body(bluetoothStatus));
        }
        content.setFocusableInTouchMode(true);
        content.requestFocus();
    }

    private String deviceName(BluetoothDevice device) {
        try {
            String name = device.getName();
            if (name != null && !name.isEmpty()) return name;
        } catch (SecurityException ignored) { }
        return "Appareil Bluetooth";
    }

    private void enableBluetooth() {
        try {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BLUETOOTH);
        } catch (SecurityException exception) {
            toast("L’autorisation Bluetooth n’est plus disponible.");
            showBluetooth();
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == TURN_PERMISSION) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            TurnNotifications.setEnabled(this, granted);
            refreshTurnNotifications();
            if (screen == Screen.SETTINGS) showSettings();
            return;
        }
        if (request != BLUETOOTH_PERMISSION) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            preferences.edit().remove("bluetooth_permission_requested").apply();
        }
        if (screen == Screen.BLUETOOTH) showBluetooth();
    }

    private void beginLink(BluetoothDevice peer, String name) {
        if (!ready() || !bluetoothPermission()) return;
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled()) { toast("Activez d’abord le Bluetooth."); return; }
        resetMatch();
        mode = "bluetooth";
        gameBack = Screen.HOME;
        bluetoothHost = peer == null;
        bluetoothName = name.isEmpty() ? (bluetoothHost ? "Hôte" : "Invité") : name;
        bluetoothStatus = bluetoothHost ? "En attente d’un joueur…" : "Connexion à " + deviceName(peer) + "…";
        BluetoothLink next = new BluetoothLink(new BluetoothLink.Listener() {
            @Override public void connected(BluetoothLink connection) {
                main.post(() -> {
                    if (link != connection) return;
                    connection.send(MatchCodec.object("type", "hello", "version", MatchCodec.VERSION,
                            "name", bluetoothName, "lexicon", lexicon.hash));
                });
            }

            @Override public void received(BluetoothLink connection, JSONObject row) {
                main.post(() -> {
                    if (link == connection) receiveBluetooth(row);
                });
            }

            @Override public void lost(BluetoothLink connection, String reason) {
                main.post(() -> {
                    if (link != connection) return;
                    closeLink();
                    bluetoothStatus = reason;
                    if (state() == null) showBluetooth();
                    else updateArena(-1);
                    toast(reason);
                });
            }
        });
        link = next;
        showBluetooth();
        next.start(adapter, peer);
    }

    private void closeLink() {
        BluetoothLink current = link;
        link = null;
        if (current != null) current.close();
        bluetoothReady = false;
        bluetoothStatus = "";
        waiting = false;
    }

    private void receiveBluetooth(JSONObject row) {
        try {
            switch (row.getString("type")) {
                case "hello" -> {
                    if (row.getInt("version") != MatchCodec.VERSION
                            || !lexicon.hash.equals(row.getString("lexicon"))) {
                        closeLink();
                        bluetoothStatus = "Les deux appareils doivent utiliser la même version et le même lexique.";
                        showBluetooth();
                        return;
                    }
                    if (!bluetoothHost || game != null) return;
                    String other = row.optString("name", "Invité").trim();
                    if (other.isEmpty()) other = "Invité";
                    if (other.length() > 18) other = other.substring(0, 18);
                    game = new FrenchGame(new String[]{bluetoothName, other}, new int[2]);
                    bluetoothReady = true;
                    link.send(MatchCodec.visible(game, 1));
                    showGame();
                }
                case "state" -> {
                    if (bluetoothHost) return;
                    MatchCodec.State next = MatchCodec.readState(row);
                    if (next.seat() != 1 || next.names().length != 2 || row.getInt("version") != MatchCodec.VERSION
                            || (guestState != null && (!guestState.id().equals(next.id())
                            || next.history().size() < guestState.history().size()))) {
                        throw new JSONException("État inattendu");
                    }
                    guestState = next;
                    bluetoothReady = true;
                    waiting = false;
                    clearStaged();
                    lastRackTurn = -1;
                    archiveIfFinished();
                    if (screen == Screen.BLUETOOTH) showGame();
                    else updateArena(-1);
                }
                case "move" -> {
                    if (!bluetoothHost || game == null) return;
                    if (game.end != null || game.active != 1 || row.getInt("turn") != game.history.size()) {
                        link.send(MatchCodec.visible(game, 1));
                        return;
                    }
                    List<Integer> exchange = new ArrayList<>();
                    JSONArray indices = row.getJSONArray("exchange");
                    for (int i = 0; i < Math.min(indices.length(), 8); i++) exchange.add(indices.getInt(i));
                    String error = apply(row.getString("action"), MatchCodec.readTiles(row.getJSONArray("tiles"), true), exchange);
                    if (error == null) afterMove();
                    else link.send(MatchCodec.object("type", "invalid", "message", error));
                }
                case "invalid" -> {
                    if (bluetoothHost) return;
                    waiting = false;
                    updateArena(-1);
                    toast(row.optString("message", "L’hôte a refusé ce coup."));
                }
                default -> { }
            }
        } catch (JSONException | IllegalArgumentException exception) {
            if (link != null && bluetoothHost) link.send(MatchCodec.object("type", "invalid", "message", "Coup invalide."));
            else {
                closeLink();
                toast("Le message Bluetooth n’a pas pu être lu.");
                if (state() == null) showBluetooth();
                else updateArena(-1);
            }
        }
    }

    private void showSettings() {
        page(Screen.SETTINGS, false);
        top("Lettrine", this::showProfile, null);
        heading("Réglages", "");
        LinearLayout settings = ui.card();
        Switch musicSwitch = soundSwitch(R.string.ambient_sound, "music");
        settings.addView(musicSwitch);
        String[] tracks = getResources().getStringArray(R.array.music_tracks);
        LinearLayout track = ui.option("♫", tracks[preferences.getInt("music_track", 0)], "", () ->
                dialog(getString(R.string.music_pick), null)
                        .setSingleChoiceItems(tracks, preferences.getInt("music_track", 0), (choice, index) -> {
                            preferences.edit().putInt("music_track", index).apply();
                            audio.refresh();
                            choice.dismiss();
                            showSettings();
                        }).show());
        settings.addView(track);
        TextView volumeLabel = ui.body(getString(R.string.music_volume));
        settings.addView(volumeLabel);
        SeekBar volume = new SeekBar(this);
        volume.setContentDescription(getString(R.string.music_volume));
        volume.setMax(100);
        volume.setProgress(preferences.getInt("music_volume", 50));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                preferences.edit().putInt("music_volume", progress).apply();
                audio.adjustVolume();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        settings.addView(volume);
        settings.addView(soundSwitch(R.string.tile_sounds, "effects"));
        content.addView(settings);
        ui.gap(content, 12);
        if (rewarded.available()) {
            LocalDate date = LocalDate.now();
            if (date.toString().equals(preferences.getString("rewarded_date", ""))) {
                content.addView(ui.option("✦", getString(R.string.rewarded_play), "", () -> openBonus(date)));
            } else {
                content.addView(ui.option("✦", getString(R.string.rewarded_title),
                        getString(R.string.rewarded_details), () -> confirmBonus(date)));
            }
            ui.gap(content, 8);
            if (rewarded.hasPrivacyOptions()) {
                content.addView(ui.option("○", getString(R.string.rewarded_privacy), "",
                        () -> rewarded.privacyOptions(this::refreshRewardSettings)));
                ui.gap(content, 8);
            }
        }
        content.addView(ui.option("↗", getString(R.string.social_navigation_endpoint),
                socialBaseUrl().isEmpty() ? "" : Uri.parse(socialBaseUrl()).getHost(), this::editSocialEndpoint));
        ui.gap(content, 8);
        content.addView(ui.option("◌", getString(R.string.turn_notifications_title),
                getString(TurnNotifications.enabled(this) && TurnNotifications.available(this)
                        ? R.string.turn_notifications_on : R.string.turn_notifications_off), this::editTurnNotifications));
        ui.gap(content, 18);
        content.addView(ui.micro("APPARENCE", ui.palette.accent()));
        ui.gap(content, 8);
        for (int i = 0; i < Palette.THEMES.length; i++) {
            int theme = i;
            content.addView(ui.option(skin == i ? "✓" : "✦", Palette.THEMES[i].name(), "", () -> {
                skin = theme;
                preferences.edit().putInt("skin", skin).apply();
                updateAppearance();
                showSettings();
            }));
            ui.gap(content, 8);
        }
        if (lexicon != null) {
            ui.gap(content, 8);
            String label = lexicon.label;
            content.addView(ui.option("Aa", label, "Lexique français et licence", () -> dialog(label,
                    (notice == null || notice.trim().isEmpty() ? "Lexique intégré au moment de la compilation." : notice)
                            + "\n\nEmpreinte SHA-256 : " + lexicon.hash).setPositiveButton("Fermer", null).show()));
        }
    }

    private void editTurnNotifications() {
        boolean enabled = TurnNotifications.enabled(this);
        String detail = getString(R.string.turn_notifications_detail);
        if (enabled && !TurnNotifications.available(this)) {
            new AlertDialog.Builder(this).setTitle(R.string.turn_notifications_title)
                    .setMessage(R.string.turn_notifications_system)
                    .setNegativeButton(R.string.turn_notifications_disable, (dialog, which) -> setTurnNotifications(false))
                    .setPositiveButton(R.string.turn_notifications_open_settings, (dialog, which) -> openNotificationSettings())
                    .show();
            return;
        }
        dialog(getString(R.string.turn_notifications_title), detail)
                .setNegativeButton(R.string.social_cancel, null)
                .setPositiveButton(enabled ? R.string.turn_notifications_disable : R.string.turn_notifications_enable,
                        (dialog, which) -> {
                            if (enabled) setTurnNotifications(false);
                            else if (Build.VERSION.SDK_INT >= 33
                                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                                    != PackageManager.PERMISSION_GRANTED) {
                                if (preferences.getBoolean("turn_permission_requested", false)
                                        && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                                    openNotificationSettings();
                                    return;
                                }
                                preferences.edit().putBoolean("turn_permission_requested", true).apply();
                                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, TURN_PERMISSION);
                            } else if (!TurnNotifications.available(this)) {
                                setTurnNotifications(true);
                                openNotificationSettings();
                            } else setTurnNotifications(true);
                        }).show();
    }

    private void openNotificationSettings() {
        startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
    }

    private void setTurnNotifications(boolean enabled) {
        TurnNotifications.setEnabled(this, enabled);
        refreshTurnNotifications();
        if (screen == Screen.SETTINGS) showSettings();
    }

    private void refreshTurnNotifications() {
        TurnNotifications.refresh(this, socialBaseUrl());
    }

    private Switch soundSwitch(int label, String key) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextColor(ui.palette.ink());
        toggle.setTextSize(15);
        toggle.setMinimumHeight(ui.dp(48));
        toggle.setChecked(preferences.getBoolean(key, false));
        toggle.setOnCheckedChangeListener((view, checked) -> {
            preferences.edit().putBoolean(key, checked).apply();
            if (key.equals("music")) audio.refresh();
            else audio.prepareEffects();
        });
        return toggle;
    }

    private void confirmBonus(LocalDate date) {
        if (!ready()) return;
        FrameLayout origin = frame;
        dialog(getString(R.string.rewarded_title), getString(R.string.rewarded_confirm))
                .setNegativeButton("Annuler", null)
                .setPositiveButton(R.string.rewarded_watch, (ignored, which) -> rewarded.request(
                        () -> screen == Screen.SETTINGS && frame == origin,
                        () -> preferences.edit().putString("rewarded_date", date.toString()).apply(),
                        this::refreshRewardSettings))
                .show();
    }

    private void refreshRewardSettings() {
        if (screen == Screen.SETTINGS) showSettings();
    }

    private void openBonus(LocalDate date) {
        if (!ready()) return;
        DailyChallenge challenge = DailyChallenge.forDate(date.minusWeeks(5), lexicon);
        if (challenge == null) {
            toast(getString(R.string.daily_unavailable));
            return;
        }
        page(Screen.PRACTICE, false);
        practiceBack = this::showSettings;
        top("Lettrine", this::leavePractice, null);
        heading(getString(R.string.rewarded_title), getString(R.string.daily_goal, challenge.openingWord()));
        content.addView(new PracticePanel(ui, challenge.position(), lexicon, false, false).view());
    }

    private void showProfile() {
        page(Screen.PROFILE, true);
        top("Lettrine", null, ui.textButton("Réglages", this::showSettings));
        heading(getString(R.string.profile_title), "");
        content.addView(ui.option("◇", getString(R.string.skins_title), getString(R.string.collection_profile_detail), this::showCollection));
        ui.gap(content, 10);
        profilePanel = new ProfilePanel(ui, profileStore, store, dailyProgress, this::chooseProfilePhoto);
        content.addView(profilePanel.view());
    }

    private void updateAppearance() {
        ui = new ViewKit(this, Palette.THEMES[skin], skinStore.selection(), audio);
    }

    private void showCollection() {
        page(Screen.COLLECTION, false);
        top("Lettrine", this::showProfile, null);
        content.addView(new SkinPanel(ui, skinStore, store, dailyProgress, this::updateAppearance).view());
    }

    private void chooseProfilePhoto() {
        try {
            startActivityForResult(ProfilePanel.photoPicker(), PROFILE_PHOTO);
        } catch (android.content.ActivityNotFoundException exception) {
            toast("Le sélecteur de photos est indisponible.");
        }
    }

    private String socialBaseUrl() { return preferences.getString("social_endpoint", BuildConfig.SOCIAL_BASE_URL); }

    private void editSocialEndpoint() {
        EditText field = new EditText(this);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        field.setSingleLine(true);
        field.setHint(R.string.social_navigation_endpoint_hint);
        field.setText(socialBaseUrl());
        AlertDialog change = dialog(getString(R.string.social_navigation_endpoint),
                getString(R.string.social_navigation_endpoint_help)).setView(field)
                .setNegativeButton("Annuler", null)
                .setNeutralButton(R.string.social_navigation_endpoint_default, (d, action) -> {
                    preferences.edit().remove("social_endpoint").apply();
                    refreshSocialEndpoint();
                })
                .setPositiveButton("Enregistrer", null).create();
        change.setOnShowListener(d -> change.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String address = field.getText().toString().strip();
            if (!address.isEmpty() && !SocialClient.validBase(address)) {
                field.setError(getString(R.string.social_navigation_endpoint_invalid));
                return;
            }
            preferences.edit().putString("social_endpoint", address).apply();
            change.dismiss();
            refreshSocialEndpoint();
        }));
        change.show();
    }

    private void refreshSocialEndpoint() {
        CancellationSignal request = googleSignIn;
        googleSignIn = null;
        if (request != null) request.cancel();
        refreshTurnNotifications();
        if (screen == Screen.SOCIAL || pendingSocialInvite != null) showSocial();
        else showSettings();
    }

    private void showSocial() {
        page(Screen.SOCIAL, true);
        top("Lettrine", null, ui.textButton(getString(R.string.social_navigation_endpoint), this::editSocialEndpoint));
        heading(getString(R.string.social_navigation_friends), "");
        String invite = pendingSocialInvite;
        if (socialClient.configured()) pendingSocialInvite = null;
        content.addView(new SocialPanel(ui, socialClient, this::showSocialDuel, invite, this::connectGoogle,
                this::showTournament, this::editTurnNotifications, this::confirmBeta, this::betaInviteBase).view());
    }

    private void showTournament() {
        page(Screen.TOURNAMENT, false);
        top("Lettrine", this::showSocial, null);
        content.addView(new TournamentPanel(ui, socialClient, this::showTournamentRound).view());
    }

    private void showTournamentRound(JSONObject round) {
        if (!ready()) return;
        page(Screen.TOURNAMENT_ROUND, false);
        top(getString(R.string.tournament_title), this::showTournament, null);
        content.addView(new TournamentRoundPanel(ui, socialClient, round, lexicon, this::showTournament).view());
    }

    private void showSocialDuel(JSONObject duel) {
        if (!ready()) return;
        TurnNotifications.dismiss(this, duel.optString("id"));
        page(Screen.SOCIAL_DUEL, false);
        top("Lettrine", this::showSocial, null);
        heading(getString(SocialDuelPanel.isMirror(duel) ? R.string.social_mirror_title
                : R.string.social_navigation_duel), "");
        content.addView(new SocialDuelPanel(ui, socialClient, duel, lexicon, this::showSocial).view());
    }

    private String betaInviteBase() {
        String origin = SocialClient.origin(socialBaseUrl());
        if (origin == null) return null;
        return origin.equals(preferences.getString("social_beta_origin", ""))
                || origin.equals(SocialClient.origin(BuildConfig.SOCIAL_BASE_URL)) ? origin + "/beta#" : null;
    }

    private void connectGoogle(String clientId) {
        if (googleSignIn != null) return;
        String endpoint = socialBaseUrl();
        CancellationSignal request = new CancellationSignal();
        googleSignIn = request;
        socialClient.googleChallenge((reply, error) -> {
            if (googleSignIn != request || isDestroyed()) return;
            String nonce = reply == null ? "" : reply.optString("nonce");
            if (error != null || nonce.isEmpty() || !endpoint.equals(socialBaseUrl())
                    || !clientId.equals(reply.optString("google_client_id"))) {
                finishGoogle(request, error);
                return;
            }
            SocialGoogle.request(this, clientId, nonce, request,
                    token -> {
                        if (googleSignIn != request) return;
                        if (!endpoint.equals(socialBaseUrl())) {
                            finishGoogle(request, getString(R.string.social_endpoint_changed));
                            return;
                        }
                        socialClient.linkGoogle(token, nonce, (account, failure) -> {
                            if (googleSignIn != request) return;
                            googleSignIn = null;
                            if (failure != null) toast(failure);
                            else {
                                toast(getString(R.string.social_google_connected));
                                showSocial();
                            }
                        });
                    }, failure -> {
                        if (googleSignIn != request) return;
                        googleSignIn = null;
                        if (failure != 0) toast(getString(failure));
                    });
        });
    }

    private void finishGoogle(CancellationSignal request, String error) {
        if (googleSignIn != request) return;
        googleSignIn = null;
        toast(error == null ? getString(R.string.social_google_error) : error);
    }

    private void showHistory() {
        page(Screen.HISTORY, true);
        top("Lettrine", null, null);
        heading("Historique", "");
        List<JSONObject> games = store == null ? List.of() : store.history();
        LinearLayout summary = ui.card();
        int wins = 0, solo = 0, best = Integer.MIN_VALUE, bingos = 0;
        long points = 0;
        for (JSONObject row : games) {
            JSONArray scores = row.optJSONArray("scores");
            if (scores != null) for (int i = 0; i < scores.length(); i++) best = Math.max(best, scores.optInt(i));
            if (!row.optString("mode").equals("solo")) continue;
            solo++;
            if (row.optInt("winner", -1) == 0) wins++;
            points += scores == null ? 0 : scores.optInt(0);
            JSONArray moves = row.optJSONArray("moves");
            if (moves == null) continue;
            for (int i = 0; i < moves.length(); i++) {
                JSONObject move = moves.optJSONObject(i);
                if (move != null && move.optInt("player", -1) == 0 && move.optJSONArray("tiles") != null
                        && move.optJSONArray("tiles").length() == 7) bingos++;
            }
        }
        summary.addView(ui.title(games.size() + (games.size() == 1 ? " partie" : " parties"), 25));
        ui.gap(summary, 8);
        summary.addView(ui.body("Elo local " + currentRating() + (best == Integer.MIN_VALUE ? "" : "  ·  Record " + best + " pts")));
        if (solo > 0) {
            ui.gap(summary, 4);
            summary.addView(ui.body(wins + "/" + solo + " victoires en solo  ·  Moyenne " + Math.round(points / (double) solo)
                    + " pts  ·  " + bingos + " scrabbles"));
        }
        content.addView(summary);
        ui.gap(content, 12);
        content.addView(EloChart.card(ui, games));
        ui.gap(content, 12);
        if (games.isEmpty()) {
            content.addView(ui.body("Terminez une partie pour la revoir ici."));
            return;
        }
        content.addView(ui.button("Exporter l’historique en JSON", false,
                () -> export("lettrine-historique.json", new JSONArray(games).toString())));
        ui.gap(content, 15);
        for (int i = games.size() - 1; i >= Math.max(0, games.size() - historyLimit); i--) {
            JSONObject row = games.get(i);
            String title = scoreText(row);
            String subtitle = formatMode(row.optString("mode")) + (row.optBoolean("assisted") ? " assisté" : "")
                    + "  ·  " + row.optInt("turns") + " coups  ·  "
                    + DateFormat.getDateInstance(DateFormat.SHORT, Locale.FRANCE).format(new Date(row.optLong("finished_at_ms")));
            content.addView(ui.option("↗", title, subtitle, () -> {
                detail = row;
                replayIndex = row.optInt("turns");
                showDetail();
            }));
            ui.gap(content, 7);
        }
        if (historyLimit < games.size()) content.addView(ui.button("Afficher plus", false, () -> {
            historyLimit += 20;
            showHistory();
        }));
    }

    private String formatMode(String value) {
        return switch (value) {
            case "local" -> "Entre amis";
            case "bluetooth" -> "Bluetooth";
            default -> "Solo";
        };
    }

    private String scoreText(JSONObject row) {
        JSONArray names = row.optJSONArray("names"), scores = row.optJSONArray("scores");
        if (names == null || scores == null) return "Partie terminée";
        StringBuilder title = new StringBuilder();
        for (int i = 0; i < Math.min(names.length(), scores.length()); i++) {
            if (i > 0) title.append("  ·  ");
            title.append(names.optString(i)).append(' ').append(scores.optInt(i));
        }
        return title.toString();
    }

    private void reviewFinishedGame() {
        MatchCodec.State finished = state();
        if (finished == null || finished.end() == null || !ready()) return;
        String summary = resultTitle(finished);
        showReview(List.copyOf(finished.history()), mode, summary, true, this::showGame);
    }

    private void showReview(List<FrenchGame.Move> moves, String gameMode, String summary,
                            boolean compatible, Runnable back) {
        reviewBack = back;
        page(Screen.REVIEW, false);
        top(getString(R.string.match_review), back, null);
        heading(getString(R.string.match_review_heading), summary);

        TextView status = ui.body(getString(R.string.match_review_loading));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status);
        FrameLayout reviewFrame = frame;
        boolean bluetooth = gameMode.equals("bluetooth");
        int seat = gameMode.equals("solo") ? 0 : -1;
        Lexicon dictionary = lexicon;
        reviewTask = reviewWorker.submit(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                List<MatchReview.Turn> turns = MatchReview.analyze(moves, dictionary, seat, compatible && !bluetooth);
                main.post(() -> {
                    if (frame != reviewFrame || isDestroyed()) return;
                    reviewTask = null;
                    if (turns.isEmpty()) {
                        status.setText(R.string.match_review_empty);
                        return;
                    }
                    if (bluetooth || !compatible) {
                        status.setText(bluetooth ? R.string.match_review_bluetooth : R.string.match_review_lexicon);
                        ui.gap(content, 10);
                    } else {
                        content.removeView(status);
                    }
                    content.addView(new MatchReviewPanel(ui, turns, compatible && !bluetooth, this::explainReview,
                            this::practiceReviewedTurn).view());
                });
            } catch (RuntimeException exception) {
                if (Thread.currentThread().isInterrupted()) return;
                main.post(() -> {
                    if (frame != reviewFrame || isDestroyed()) return;
                    reviewTask = null;
                    status.setText(R.string.match_review_error);
                });
            }
        });
    }

    private void showDetail() {
        if (detail == null) { showHistory(); return; }
        page(Screen.DETAIL, false);
        top("Rejouer la partie", this::showHistory, null);
        heading(scoreText(detail), formatMode(detail.optString("mode")) + "  ·  " + detail.optString("lexicon"));
        List<FrenchGame.Move> moves;
        try {
            moves = MatchCodec.readMoves(detail.getJSONArray("moves"));
        } catch (JSONException | IllegalArgumentException exception) {
            content.addView(ui.body("Cette partie ne peut pas être rejouée."));
            return;
        }
        JSONObject replayed = detail;
        if (!moves.isEmpty()) {
            content.addView(ui.button(getString(R.string.match_review), true, () -> {
                if (!ready()) return;
                String digest = replayed.optString("lexicon_sha256");
                boolean compatible = digest.isEmpty()
                        ? lexicon.label.equals(replayed.optString("lexicon")) : lexicon.hash.equals(digest);
                showReview(moves, replayed.optString("mode"), scoreText(replayed), compatible, this::showDetail);
            }));
            ui.gap(content, 9);
        }
        BoardView board = new BoardView(ui, null);
        content.addView(board, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout controls = ui.row();
        TextView label = ui.body("");
        TextView previous = ui.textButton("‹", () -> {});
        previous.setContentDescription("Coup précédent");
        TextView next = ui.textButton("›", () -> {});
        next.setContentDescription("Coup suivant");
        controls.addView(previous);
        ui.weight(controls, label);
        controls.addView(next);
        TextView zoom = ui.textButton("+", () -> board.zoomBy(1.25f));
        zoom.setContentDescription("Agrandir le plateau");
        controls.addView(zoom);
        controls.addView(ui.textButton("1×", board::resetZoom));
        content.addView(controls);
        TextView explain = ui.button(getString(R.string.move_explanation_current), false,
                () -> explainMove(moves, replayIndex - 1));
        Runnable replay = () -> {
            char[] letters = new char[FrenchGame.CELLS];
            boolean[] blanks = new boolean[FrenchGame.CELLS];
            for (int i = 0; i < replayIndex; i++) {
                for (FrenchGame.Tile tile : moves.get(i).tiles()) {
                    letters[tile.position()] = tile.letter();
                    blanks[tile.position()] = tile.blank();
                }
            }
            FrenchGame.Move shown = replayIndex == 0 ? null : moves.get(replayIndex - 1);
            board.update(letters, blanks, List.of(), shown == null ? List.of() : shown.tiles(), -1);
            String word = shown == null ? "Départ" : shown.type().equals("play")
                    ? shown.word() + " +" + shown.score() : shown.type().equals("pass") ? "Passe" : "Échange";
            label.setText(getString(R.string.replay_position, replayIndex, moves.size(), word));
            ui.enabled(previous, replayIndex > 0);
            ui.enabled(next, replayIndex < moves.size());
            ui.enabled(explain, shown != null);
        };
        previous.setOnClickListener(view -> { if (replayIndex > 0) { replayIndex--; replay.run(); } });
        next.setOnClickListener(view -> { if (replayIndex < moves.size()) { replayIndex++; replay.run(); } });
        replayIndex = Math.max(0, Math.min(moves.size(), replayIndex));
        replay.run();
        ui.gap(content, 9);
        content.addView(explain);
        ui.gap(content, 7);
        content.addView(ui.button("Exporter cette partie en JSON", false,
                () -> export("lettrine-partie.json", detail.toString())));
    }

    private void export(String name, String body) {
        export = body;
        Intent document = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        document.addCategory(Intent.CATEGORY_OPENABLE);
        document.setType("application/json");
        document.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(document, EXPORT_FILE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PROFILE_PHOTO) {
            if (result == RESULT_OK && data != null && profilePanel != null) profilePanel.photoSelected(data.getData());
            return;
        }
        if ((request == ENABLE_BLUETOOTH || request == APP_SETTINGS) && screen == Screen.BLUETOOTH) showBluetooth();
        if (request != EXPORT_FILE) return;
        String body = export;
        export = null;
        if (result != RESULT_OK || data == null || data.getData() == null || body == null) return;
        try (OutputStream destination = getContentResolver().openOutputStream(data.getData())) {
            if (destination == null) throw new java.io.IOException("Fichier indisponible");
            destination.write(body.getBytes(StandardCharsets.UTF_8));
            toast("Export enregistré.");
        } catch (java.io.IOException exception) {
            toast("Impossible d’écrire le fichier.");
        }
    }
}
