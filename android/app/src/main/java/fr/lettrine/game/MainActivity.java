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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final int BLUETOOTH_PERMISSION = 41;
    private static final int ENABLE_BLUETOOTH = 42;
    private static final int EXPORT_FILE = 43;
    private static final int APP_SETTINGS = 44;
    private static final int PROFILE_PHOTO = 45;
    private static final int TURN_PERMISSION = 46;
    private static final int BACKUP_EXPORT = 47;
    private static final int BACKUP_IMPORT = 48;
    private static final int PERSONAL_LEXICON_IMPORT = 49;
    private static final int BLUETOOTH_SETTINGS = 50;
    private static final int DEFAULT_RATING = GameStore.BASE_ELO;
    private static final long BLUETOOTH_HANDSHAKE_TIMEOUT_MS = 15_000;

    private enum Screen { HOME, GAMES, SOLO, LOCAL, BLUETOOTH, SETTINGS, HISTORY, DETAIL, GAME, RESULT, REVIEW, PRACTICE,
        SOCIAL, SOCIAL_DUEL, PROFILE, COLLECTION, TOURNAMENT, TOURNAMENT_ROUND, HELP, TWO_MOVES, BONUS_CIRCUIT, BACKUP,
        PERSONAL_LEXICON }

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
    private TwoMoveChallengeScreen twoMoveScreen;
    private BonusCircuitScreen bonusCircuit;
    private BonusEconomy economy;
    private SocialClient socialClient;
    private SocialPanel socialPanel;
    private CancellationSignal googleSignIn;
    private String pendingSocialInvite;
    private String pendingSocialInviteOrigin;
    private Object pendingTurnNavigation;
    private Lexicon lexicon;
    private Lexicon bundledLexicon;
    private Lexicon frenchClassicLexicon;
    private String matchLexiconHash = "";
    private GameStore bundledStore;
    private PersonalLexicon personalLexicon;
    private PersonalLexicon.Entry personalEntry;
    private boolean personalBusy;
    private boolean personalSession;
    private GameLanguage gameLanguage;
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
    private boolean bluetoothHelloReceived;
    private String bluetoothStatus = "";
    private String bluetoothName = "";
    private String bluetoothPeerAddress;
    private BluetoothDevice bluetoothDevice;
    private BluetoothLink link;
    private Arena arena;
    private FrameLayout frame;
    private LinearLayout content;
    private LinearLayout screenShell;
    private ScrollView screenScroll;
    private GameAudio audio;
    private RewardedPractice rewarded;
    private String export;
    private boolean backupInProgress;
    private boolean backupRecoveryFailed;
    private String notice;
    private JSONObject detail;
    private int replayIndex;
    private AlertDialog explanationDialog;
    private Future<?> explanationTask;
    private Future<?> reviewTask;
    private MatchReview.Review finishedReview;
    private Runnable reviewBack;
    private Runnable practiceBack;
    private Runnable pendingChallenge;
    private Screen gameBack = Screen.HOME;
    private Screen setupBack = Screen.HOME;
    private LocalDate homeDate;
    private final BroadcastReceiver calendarChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refreshDailyHome();
            if (bonusCircuit != null) bonusCircuit.refresh();
        }
    };

    @Override protected void attachBaseContext(Context base) {
        try {
            ProgressBackup.recover(base);
        } catch (java.io.IOException | JSONException exception) {
            android.util.Log.e("WordRivetBackup", "Restauration interrompue", exception);
            backupRecoveryFailed = true;
        }
        super.attachBaseContext(AppLocale.wrap(base));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        gameLanguage = AppLocale.language(this);
        personalLexicon = new PersonalLexicon(this, gameLanguage);
        preferences = getSharedPreferences("preferences", MODE_PRIVATE);
        pendingSocialInvite = preferences.getString(gameLanguage.storageName("pending_social_invite"), pendingSocialInvite);
        pendingSocialInviteOrigin = preferences.getString(gameLanguage.storageName("pending_social_invite_origin"), pendingSocialInviteOrigin);
        audio = new GameAudio(this, preferences);
        audio.prepareEffects();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        soloSelection = Math.max(1, Math.min(Bot.ADAPTIVE, preferences.getInt("solo_level", 1)));
        rewarded = new RewardedPractice(this);
        economy = new BonusEconomy(this);
        dailyProgress = new DailyProgress(this, gameLanguage);
        skinStore = new SkinStore(this);
        profileStore = new ProfileStore(this);
        socialClient = new SocialClient(this, gameLanguage, this::socialBaseUrl, profileStore::nickname,
                () -> frenchClassicLexicon == null ? null : frenchClassicLexicon.hash,
                this::receiveReferralBalance);
        skin = Math.max(0, Math.min(Palette.THEMES.length - 1, preferences.getInt("skin", 0)));
        updateAppearance();
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Build.VERSION.SDK_INT < 29 ? ui.palette.page() : Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        if (backupRecoveryFailed) {
            showBackupRecovery();
            return;
        }
        try {
            store = bundledStore = new GameStore(this, gameLanguage);
            List<JSONObject> saved = store.savedGames();
            if (!saved.isEmpty()) restoreMatch(saved.get(0));
        } catch (Exception exception) {
            toast(getString(R.string.app_saved_read_error));
        }
        showHome();
        receiveNavigation(getIntent());
        rewarded.refreshConsent(this::refreshRewardViews);
        worker.submit(() -> {
            try {
                Lexicon loaded = Lexicon.load(this, gameLanguage);
                Lexicon classic = gameLanguage == GameLanguage.FRENCH ? Lexicon.loadFrenchClassic(this, loaded) : null;
                String source;
                try (InputStream stream = getAssets().open(gameLanguage.storageName("lexicon-notice") + ".md")) {
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
                    bundledLexicon = loaded;
                    frenchClassicLexicon = classic;
                    if (personalSession) return;
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
                    lexiconError = getString(R.string.app_dictionary_load_error);
                    pendingChallenge = null;
                    if (screen == Screen.HOME) showHome();
                });
            }
        });
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        receiveNavigation(intent);
    }

    private void receiveNavigation(Intent intent) {
        if (backupInProgress || backupRecoveryFailed) return;
        pendingTurnNavigation = null;
        TurnNotifications.Target target = TurnNotifications.peekDuel(this, intent, socialBaseUrl());
        if (personalSession && (target != null || intent != null && Intent.ACTION_VIEW.equals(intent.getAction()))) {
            if (!persist()) return;
            leavePersonalSession();
            showHome();
        }
        if (target != null) {
            if (target.language() != gameLanguage) {
                switchLanguage(target.language(), intent);
                return;
            }
            if (screen == Screen.GAME && !persist()) return;
            TurnNotifications.consumeDuel(intent);
            String notified = target.id();
            Object navigation = new Object();
            pendingTurnNavigation = navigation;
            socialClient.notifiedDuel(notified, (result, error) -> {
                if (isDestroyed() || pendingTurnNavigation != navigation) return;
                pendingTurnNavigation = null;
                JSONObject duel = result == null ? null : result.optJSONObject("duel");
                if (duel == null || !notified.equals(duel.optString("id"))) {
                    toast(error == null ? getString(R.string.turn_notification_unavailable) : error);
                    refreshTurnNotifications();
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
                    refreshTurnNotifications();
                    if (link.invitation() != null) {
                        openFriendInvitation(new FriendInvitation(link.invitation(), link.language()));
                    } else {
                        setIntent(new Intent(this, MainActivity.class));
                        showSocial();
                    }
                }).show();
    }

    private void receiveChallenge(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri uri = intent.getData();
        if (uri != null && "lettrine".equals(uri.getScheme()) && "amis".equals(uri.getAuthority())) {
            FriendInvitation invitation = FriendInvitation.parse(intent.getDataString());
            if (invitation == null) toast(getString(R.string.social_navigation_invite_invalid));
            else openFriendInvitation(invitation);
            return;
        }
        ChallengeLink invitation = ChallengeLink.parse(intent.getDataString());
        if (invitation == null) {
            toast(getString(R.string.daily_invitation_invalid));
            return;
        }
        if (invitation.language() != gameLanguage) {
            switchLanguage(invitation.language(), intent);
            return;
        }
        if (screen == Screen.GAME && !persist()) return;
        setIntent(new Intent(this, MainActivity.class));
        if (lexicon == null && lexiconError == null) {
            showHome();
            pendingChallenge = () -> openDailyChallenge(invitation);
        } else if (ready()) {
            openDailyChallenge(invitation);
        }
    }

    private void openFriendInvitation(FriendInvitation invitation) {
        if (invitation.language() != null && invitation.language() != gameLanguage) {
            Intent destination = new Intent(this, MainActivity.class).setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse(invitation.appUri(gameLanguage)));
            switchLanguage(invitation.language(), destination);
            return;
        }
        if (screen == Screen.GAME && !persist()) return;
        setIntent(new Intent(this, MainActivity.class));
        pendingSocialInvite = invitation.token();
        pendingSocialInviteOrigin = SocialClient.origin(socialBaseUrl());
        rememberSocialInvitation();
        showSocial();
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
        if (backupRecoveryFailed) return;
        audio.foreground(true);
        refreshDailyHome();
        refreshTurnNotifications();
        if (bonusCircuit != null) bonusCircuit.refresh();
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
            case TWO_MOVES -> leaveTwoMoves();
            case BONUS_CIRCUIT -> leaveBonusCircuit();
            case SOCIAL_DUEL -> showSocial();
            case TOURNAMENT -> showSocial();
            case TOURNAMENT_ROUND -> showTournament();
            case COLLECTION -> showProfile();
            case PERSONAL_LEXICON -> { if (!personalBusy) showSettings(); }
            case GAMES -> { if (personalSession) leavePersonalGames(); else showHome(); }
            case HISTORY -> { if (personalSession) showGames(); else showHome(); }
            case SETTINGS -> showProfile();
            case BACKUP -> {
                if (backupRecoveryFailed) finish();
                else if (!backupInProgress) showSettings();
            }
            case GAME, RESULT -> leaveGame();
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
        if (next != Screen.TWO_MOVES) twoMoveScreen = null;
        if (next != Screen.BONUS_CIRCUIT) bonusCircuit = null;
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
        screenShell = shell;
        frame.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        ScrollView scroll = new ScrollView(this);
        screenScroll = scroll;
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout centered = new FrameLayout(this);
        scroll.addView(centered, new ScrollView.LayoutParams(-1, -2));
        content = ui.column();
        int margin = next == Screen.GAME || next == Screen.PRACTICE || next == Screen.TOURNAMENT_ROUND
                || next == Screen.SOCIAL_DUEL || next == Screen.TWO_MOVES || next == Screen.BONUS_CIRCUIT ? 10 : 17;
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
            View divider = new View(this);
            divider.setBackgroundColor(ui.palette.line());
            divider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            shell.addView(divider, new LinearLayout.LayoutParams(-1, ui.dp(1)));
            LinearLayout nav = ui.row();
            nav.setGravity(Gravity.CENTER);
            nav.setBackgroundColor(ui.palette.paper());
            nav.setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2));
            ui.weight(nav, GameNavigation.item(ui, GameNavigation.Tab.PLAY, getString(R.string.app_play),
                    next == Screen.HOME, this::showHome));
            ui.weight(nav, GameNavigation.item(ui, GameNavigation.Tab.FRIENDS,
                    getString(R.string.social_navigation_friends), next == Screen.SOCIAL, this::showSocial));
            ui.weight(nav, GameNavigation.item(ui, GameNavigation.Tab.HISTORY, getString(R.string.app_history),
                    next == Screen.HISTORY, this::showHistory));
            ui.weight(nav, GameNavigation.item(ui, GameNavigation.Tab.PROFILE,
                    getString(R.string.social_navigation_profile), next == Screen.PROFILE, this::showProfile));
            FrameLayout navFrame = new FrameLayout(this);
            navFrame.setBackgroundColor(ui.palette.paper());
            navFrame.addView(nav, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL));
            navFrame.addOnLayoutChangeListener((view, left, top, right, bottom, a, b, c, d) -> {
                int width = Math.min(right - left, ui.dp(530));
                if (width > 0 && nav.getLayoutParams().width != width) {
                    nav.getLayoutParams().width = width;
                    nav.requestLayout();
                }
            });
            shell.addView(navFrame, new LinearLayout.LayoutParams(-1, -2));
        }
        setContentView(frame);
        frame.requestApplyInsets();
    }

    private void top(String label, Runnable back, TextView extra) {
        top(label, back, extra, null);
    }

    private void top(String label, Runnable back, TextView extra, TextView secondExtra) {
        LinearLayout row = ui.row();
        if (back != null) {
            TextView arrow = ui.textButton("‹", back);
            arrow.setTextSize(27);
            arrow.setContentDescription(getString(R.string.app_back));
            row.addView(arrow);
            ui.space(row, 5);
        } else {
            TextView badge = ui.text(getString(R.string.app_name).substring(0, 1), 18,
                    ui.palette.heroInk(), ui.serif);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(ui.shape(ui.palette.deep(), 10));
            badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(badge, new LinearLayout.LayoutParams(ui.dp(34), ui.dp(32)));
            ui.space(row, 10);
        }
        ui.weight(row, ui.text(label, 16, ui.palette.ink(), ui.medium));
        if (extra != null) row.addView(extra);
        if (secondExtra != null) {
            ui.space(row, 5);
            row.addView(secondExtra);
        }
        row.setMinimumHeight(ui.dp(44));
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
        TextView rating = ui.text(getString(R.string.app_elo_badge, currentRating()), 12, ui.palette.accent(), ui.medium);
        ui.inset(rating, 11, 7);
        rating.setBackground(ui.shape(ui.palette.ordinary(), 14));
        boolean english = AppLocale.language(this) == GameLanguage.ENGLISH;
        TextView language = ui.textButton(english ? "FR" : "EN",
                () -> selectLanguage(english ? GameLanguage.FRENCH : GameLanguage.ENGLISH));
        language.setContentDescription(getString(english ? R.string.locale_use_french : R.string.locale_use_english));
        top(getString(R.string.app_name), null, rating, language);
        ui.gap(content, 8);
        MatchCodec.State ongoing = state();
        boolean canResume = ongoing != null && ongoing.end() == null;
        String savedId = canResume ? "" : savedGameToResume();
        Runnable playSolo = () -> {
            if (ready()) openSolo(Screen.HOME);
        };
        boolean hasGameToResume = canResume || !savedId.isEmpty();
        content.addView(PlayHome.classic(ui, getString(hasGameToResume ? R.string.app_resume : R.string.app_vs_bot), () -> {
            if (canResume) {
                if (ready()) showGame();
            } else if (!savedId.isEmpty()) resumeMatch(savedId, Screen.HOME);
            else playSolo.run();
        }));
        ui.gap(content, 8);
        content.addView(PlayHome.local(ui, () -> {
            if (ready()) openLocal(Screen.HOME);
        }));
        ui.gap(content, 13);
        DailyProgress.Snapshot today = dailyProgress.snapshot(homeDate);
        boolean completed = today.bestScore() != null;
        String status = completed ? DailyResult.bestLabel(ui, today.bestScore()) : getString(R.string.daily_home_open);
        content.addView(PlayHome.daily(ui, DailyResult.dateLabel(ui, homeDate), status,
                today.streak() == 0 ? "" : DailyResult.streakLabel(ui, today.streak()),
                () -> openChallenge(() -> openDailyChallenge(null))));
        if (hasGameToResume) {
            ui.gap(content, 4);
            content.addView(ui.textButton(getString(R.string.games_new_solo), playSolo));
        }
        ui.gap(content, 9);
        content.addView(ui.option("▤", getString(R.string.games_title), getString(R.string.games_home_detail),
                this::showGames));
        ui.gap(content, 16);
        content.addView(PlayHome.moreModes(ui));
        ui.gap(content, 8);
        content.addView(ui.option("↔", "Bluetooth", getString(R.string.app_bluetooth_detail), () -> {
            if (ready()) showBluetooth();
        }));
        ui.gap(content, 9);
        content.addView(ui.option("↝", getString(R.string.two_move_title), getString(R.string.two_move_home),
                () -> openChallenge(this::openTwoMoves)));
        ui.gap(content, 9);
        content.addView(ui.option("◇", getString(R.string.bonus_home_title), getString(R.string.bonus_home_detail),
                () -> openChallenge(this::openBonusCircuit)));
        ui.gap(content, 9);
        content.addView(ui.option("✦", getString(R.string.practice_first), getString(R.string.practice_first_home),
                () -> openChallenge(() -> openFirstChallenge(this::showHome, false))));
        ui.gap(content, 8);
        content.addView(ui.option("?", getString(R.string.help_title), getString(R.string.help_entry), this::showHelp));
        if (lexicon == null) {
            ui.gap(content, 11);
            content.addView(ui.body(lexiconError == null ? getString(R.string.app_dictionary_loading) : lexiconError));
        }
    }

    private String savedGameToResume() {
        if (store == null) return "";
        try {
            for (JSONObject saved : store.savedGames()) {
                if (saved.isNull("end") && !saved.optString("id").isEmpty()) return saved.optString("id");
            }
        } catch (java.io.IOException | JSONException exception) {
            android.util.Log.w("WordRivetGames", "Lecture des parties en cours", exception);
        }
        return "";
    }

    private String moveCount(int count) {
        return getResources().getQuantityString(R.plurals.app_move_count, count, count);
    }

    private String points(int score) {
        return getResources().getQuantityString(R.plurals.practice_points, score, score);
    }

    private int currentRating() { return store == null ? DEFAULT_RATING : store.elo(); }

    private void refreshDailyHome() {
        if (screen == Screen.HOME && !LocalDate.now().equals(homeDate)) showHome();
    }

    private boolean ready() {
        if (lexicon != null) return true;
        toast(lexiconError == null ? getString(R.string.app_dictionary_loading_notice) : lexiconError);
        return false;
    }

    private Lexicon newClassicLexicon() {
        return personalSession || frenchClassicLexicon == null ? lexicon : frenchClassicLexicon;
    }

    private Lexicon matchLexicon() {
        return frenchClassicLexicon != null && frenchClassicLexicon.hash.equals(matchLexiconHash)
                ? frenchClassicLexicon : lexicon;
    }

    private Lexicon archivedLexicon(JSONObject match) {
        String hash = match.optString("lexicon_sha256");
        if (!personalSession && frenchClassicLexicon != null && frenchClassicLexicon.hash.equals(hash)) {
            return frenchClassicLexicon;
        }
        boolean matches = hash.isEmpty() ? lexicon.label.equals(match.optString("lexicon")) : lexicon.hash.equals(hash);
        return matches ? lexicon : null;
    }

    private void openChallenge(Runnable action) {
        if (lexicon == null && lexiconError == null) pendingChallenge = action;
        if (ready()) action.run();
    }

    private void openTwoMoves() {
        page(Screen.TWO_MOVES, false);
        top(getString(R.string.app_name), this::leaveTwoMoves, null);
        twoMoveScreen = new TwoMoveChallengeScreen(ui, newClassicLexicon(), lexicon,
                id -> economy.puzzle(gameLanguage, id));
        content.addView(twoMoveScreen.view());
    }

    private void leaveTwoMoves() {
        if (twoMoveScreen == null || !twoMoveScreen.goBack()) showHome();
    }

    private void openBonusCircuit() {
        page(Screen.BONUS_CIRCUIT, false);
        top(getString(R.string.app_name), this::leaveBonusCircuit, null);
        bonusCircuit = new BonusCircuitScreen(ui, newClassicLexicon(), lexicon, economy, rewarded::available, this::requestBonusVideo,
                this::showSocial);
        content.addView(bonusCircuit.view());
    }

    private void leaveBonusCircuit() {
        if (bonusCircuit == null || !bonusCircuit.goBack()) showHome();
    }

    private void openFirstChallenge(Runnable back, boolean tutorial) {
        Lexicon dictionary = newClassicLexicon();
        PracticePosition position = PracticePosition.first(dictionary);
        if (position == null) {
            toast(getString(R.string.practice_first_unavailable));
            return;
        }
        showPractice(getString(tutorial ? R.string.help_tutorial_intro : R.string.practice_first),
                getString(tutorial ? R.string.help_tutorial_copy : R.string.practice_first_goal),
                position, dictionary, true, tutorial, back);
    }

    private void showHelp() {
        page(Screen.HELP, false);
        top(getString(R.string.app_name), this::showHome, null);
        heading(getString(R.string.help_title), "");
        content.addView(new HelpPanel(ui, () -> openChallenge(() -> openFirstChallenge(this::showHelp, true))).view());
    }

    private void openDailyChallenge(ChallengeLink invitation) {
        LocalDate today = LocalDate.now();
        LocalDate date = invitation == null ? today : invitation.date();
        SharedPreferences drafts = getSharedPreferences(gameLanguage.storageName("daily_draft"), MODE_PRIVATE);
        String key = date.toString();
        String dictionaryKey = "lexicon/" + key;
        String recorded = dailyProgress.recordedLexicon(date);
        if (recorded == null && (drafts.contains(key) || drafts.contains(dictionaryKey))) {
            recorded = drafts.getString(dictionaryKey, "");
        }
        Lexicon preferred = newClassicLexicon();
        Lexicon personalDaily = recorded == null || preferred.hash.equals(recorded) ? preferred : lexicon;
        Lexicon dictionary = personalDaily;
        DailyChallenge challenge;
        if (invitation == null) {
            challenge = DailyChallenge.forDate(date, dictionary);
        } else {
            ChallengeLink.Resolved resolved = invitation.resolve(preferred, lexicon);
            if (resolved == null) {
                toast(getString(R.string.daily_invitation_incompatible));
                return;
            }
            challenge = resolved.challenge();
            dictionary = resolved.dictionary();
        }
        if (challenge == null) {
            toast(getString(R.string.daily_unavailable));
            return;
        }
        page(Screen.PRACTICE, false);
        practiceBack = this::showHome;
        top(getString(R.string.app_name), this::leavePractice, null);
        ui.gap(content, 5);
        String title = getString(invitation == null ? R.string.daily_title : R.string.daily_invitation_title);
        String goal = getString(R.string.daily_goal, challenge.openingWord());
        if (invitation != null) goal = getString(R.string.daily_invitation_goal,
                DailyResult.points(ui, invitation.score())) + "\n" + goal;
        content.addView(DailyChallengeCard.heading(ui, title, DailyResult.dateLabel(ui, date), goal));
        ui.gap(content, 11);
        boolean countProgress = date.equals(today) && (recorded == null || dictionary == personalDaily);
        DailyResult dailyResult = new DailyResult(ui, countProgress ? dailyProgress : null,
                challenge, dictionary, invitation, () -> {
                    if (economy.daily(gameLanguage, date)) toast(getString(R.string.daily_coins_earned));
                });
        boolean ownDaily = invitation == null;
        String dictionaryHash = dictionary.hash;
        content.addView(new PracticePanel(ui, challenge.position(), dictionary, dailyResult,
                ownDaily ? drafts.getString(key, "") : "", ownDaily ? tiles -> {
                    SharedPreferences.Editor editor = drafts.edit().clear().putString(dictionaryKey, dictionaryHash);
                    if (!tiles.isEmpty()) editor.putString(key, MatchCodec.tiles(tiles, true).toString());
                    editor.apply();
                } : null).view());
    }

    private void practiceReviewedTurn(MatchReview.Turn turn, Lexicon dictionary) {
        PracticePosition position = turn.practice();
        if (position == null || !ready()) return;
        Screen previousScreen = screen;
        FrameLayout reviewFrame = frame;
        LinearLayout reviewContent = content;
        showPractice(getString(R.string.practice_retry_title, turn.index() + 1),
                getString(R.string.practice_retry_goal), position, dictionary, false, false, () -> {
                    screen = previousScreen;
                    frame = reviewFrame;
                    content = reviewContent;
                    setContentView(frame);
                    frame.requestApplyInsets();
                });
    }

    private void showPractice(String title, String goal, PracticePosition position, Lexicon dictionary, boolean first,
                              boolean tutorial, Runnable back) {
        page(Screen.PRACTICE, false);
        practiceBack = back;
        top(first ? getString(R.string.app_name) : getString(R.string.match_review), this::leavePractice, null);
        heading(title, goal);
        content.addView(new PracticePanel(ui, position, dictionary, first, tutorial).view());
    }

    private void leavePractice() {
        Runnable back = practiceBack;
        practiceBack = null;
        back.run();
    }

    private void showGames() {
        page(Screen.GAMES, false);
        top(getString(R.string.app_name), personalSession ? this::leavePersonalGames : this::showHome,
                personalSession ? ui.textButton(getString(R.string.app_history), this::showHistory) : null);
        heading(getString(R.string.games_title), personalSession ? getString(R.string.personal_lexicon_game_context) : "");
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
                card.addView(ui.option(solo ? "✦" : "2–4", title, summary, () -> resumeMatch(match.id(), Screen.GAMES)));
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
        if (mode.equals("bluetooth") && state() != null && state().end() == null) {
            content.addView(ui.option("↔", "Bluetooth", getString(bluetoothReady ? R.string.games_bluetooth_active
                    : R.string.app_bluetooth_resume_status), () -> {
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
        showLocal();
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

    private void resumeMatch(String id, Screen origin) {
        if (!ready()) return;
        if (game != null && id.equals(game.id)) {
            archiveIfFinished();
            gameBack = origin;
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
                gameBack = origin;
                showGame();
            } catch (Exception exception) {
                toast(getString(R.string.games_read_error));
            }
        });
    }

    private void abandonMatch(String id, String title) {
        dialog(getString(R.string.games_abandon_title), title)
                .setNegativeButton(getString(R.string.social_cancel), null)
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
        page(Screen.SOLO, false);
        top(getString(R.string.app_solo), this::leaveGameSetup,
                personalSession ? null : setupBadge(getString(R.string.app_elo_badge, currentRating())));
        heading(getString(R.string.setup_solo_title), personalSession ? getString(R.string.personal_lexicon_game_context) : "");
        List<LinearLayout> levels = new ArrayList<>(Bot.ADAPTIVE);
        String[] marks = {"", "◔", "◑", "◕", "●", "✦"};
        for (int level = 1; level <= Bot.ADAPTIVE; level++) {
            int id = level;
            String description = level == 4 && newClassicLexicon().trained
                    ? getString(R.string.app_bot_trained) : getResources().getStringArray(R.array.bot_difficulties_detail)[level];
            String caption = personalSession || level == Bot.ADAPTIVE ? description
                    : getString(R.string.app_level_elo, Bot.RATINGS[level], description);
            LinearLayout card = ui.option(marks[level], botName(level), caption, () -> {
                soloSelection = id;
                preferences.edit().putInt("solo_level", id).apply();
                updateLevelCards(levels, id);
            });
            levels.add(card);
            content.addView(card);
            ui.gap(content, 8);
        }
        updateLevelCards(levels, selection);
        ui.gap(content, 7);
        content.addView(ui.button(getString(R.string.app_start_game), true, () -> {
            int level = soloSelection;
            switchMatch(() -> newMatch("solo", new String[]{getString(R.string.app_you), botName(level)},
                    new int[]{0, level}));
        }));
    }

    private TextView setupBadge(String label) {
        TextView badge = ui.text(label, 12, ui.palette.deep(), ui.medium);
        ui.inset(badge, 10, 6);
        badge.setBackground(ui.shape(ui.palette.ordinary(), 13));
        return badge;
    }

    private void updateLevelCards(List<LinearLayout> levels, int level) {
        for (int i = 0; i < levels.size(); i++) {
            LinearLayout card = levels.get(i);
            boolean selected = i + 1 == level;
            card.setSelected(selected);
            card.setBackground(ui.touch(selected ? ui.palette.ordinary() : ui.palette.paper(), 18,
                    selected ? ui.palette.deep() : ui.palette.line()));
            TextView mark = (TextView) card.getChildAt(0);
            mark.setTextColor(selected ? ui.palette.heroInk() : ui.palette.ink());
            mark.setBackground(ui.shape(selected ? ui.palette.deep() : ui.palette.ordinary(), 12));
        }
    }

    private String botName(int level) { return getResources().getStringArray(R.array.bot_difficulties)[level]; }

    private void showLocal() {
        page(Screen.LOCAL, false);
        top(getString(R.string.app_local_game), this::leaveGameSetup, null);
        heading(getString(R.string.app_home_local), getString(R.string.app_local_privacy));
        LinearLayout counts = ui.row();
        LinearLayout players = ui.card();
        List<LinearLayout> slots = new ArrayList<>(4);
        List<EditText> names = new ArrayList<>(4);
        for (int index = 0; index < 4; index++) {
            String fallback = getString(R.string.app_player, index + 1);
            EditText name = input(fallback, "");
            names.add(name);
            LinearLayout slot = ui.row();
            TextView tile = ui.text(Integer.toString(index + 1), 14, ui.palette.deep(), ui.medium);
            tile.setGravity(Gravity.CENTER);
            tile.setBackground(ui.shape(ui.palette.ordinary(), 10));
            tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            slot.addView(tile, new LinearLayout.LayoutParams(ui.dp(33), ui.dp(33)));
            ui.space(slot, 10);
            name.setMinHeight(ui.dp(46));
            ui.weight(slot, name);
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
            if (index > 0) layout.topMargin = ui.dp(8);
            players.addView(slot, layout);
            slots.add(slot);
        }
        List<TextView> choices = new ArrayList<>(3);
        for (int number = 2; number <= 4; number++) {
            int size = number;
            if (number > 2) ui.space(counts, 8);
            TextView choice = ui.button(Integer.toString(number), false, () -> showPlayerSlots(choices, slots, size));
            choice.setContentDescription(getString(R.string.app_players, number));
            choices.add(choice);
            ui.weight(counts, choice);
        }
        showPlayerSlots(choices, slots, 2);
        content.addView(counts);
        ui.gap(content, 16);
        content.addView(players);
        ui.gap(content, 12);
        content.addView(ui.button(getString(R.string.app_start_game), true, () -> switchMatch(() -> {
            int count = (int) slots.stream().filter(slot -> slot.getVisibility() == View.VISIBLE).count();
            String[] entered = new String[count];
            for (int i = 0; i < count; i++) {
                String name = names.get(i).getText().toString().trim();
                entered[i] = name.isEmpty() ? getString(R.string.app_player, i + 1) : name;
            }
            newMatch("local", entered, new int[entered.length]);
        })));
        content.setFocusableInTouchMode(true);
        content.requestFocus();
    }

    private void showPlayerSlots(List<TextView> choices, List<LinearLayout> slots, int count) {
        for (int i = 0; i < choices.size(); i++) {
            TextView choice = choices.get(i);
            boolean active = i + 2 == count;
            choice.setSelected(active);
            choice.setTextColor(active ? ui.palette.heroInk() : ui.palette.ink());
            choice.setBackground(ui.touch(active ? ui.palette.deep() : ui.palette.paper(), 15,
                    active ? 0 : ui.palette.line()));
        }
        for (int i = 0; i < slots.size(); i++) slots.get(i).setVisibility(i < count ? View.VISIBLE : View.GONE);
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

    private void switchMatch(Runnable next) {
        if (!persist()) return;
        if (link == null && !(mode.equals("bluetooth") && state() != null && state().end() == null)) {
            next.run();
            return;
        }
        dialog(getString(R.string.games_bluetooth_title), getString(R.string.games_bluetooth_body))
                .setNegativeButton(getString(R.string.social_cancel), null)
                .setPositiveButton(R.string.games_bluetooth_continue, (view, which) -> {
                    resetMatch();
                    next.run();
                }).show();
    }

    private void newMatch(String newMode, String[] names, int[] levels) {
        if (!ready() || store == null) return;
        FrenchGame next = new FrenchGame(names, levels, gameLanguage);
        int rating = currentRating();
        String dictionaryHash = newClassicLexicon().hash;
        try {
            JSONObject saved = MatchCodec.saved(next, newMode, rating, 0, 0, false);
            MatchCodec.put(saved, "lexicon_sha256", dictionaryHash);
            store.saveGame(saved);
        } catch (Exception exception) {
            toast(getString(R.string.games_write_error));
            return;
        }
        resetMatch();
        game = next;
        mode = newMode;
        matchLexiconHash = dictionaryHash;
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
        matchLexiconHash = saved.optString("lexicon_sha256");
        startRating = saved.optInt("start_rating", DEFAULT_RATING);
        botLevelSum = saved.optInt("level_sum");
        botLevelCount = saved.optInt("level_count");
        assisted = saved.optBoolean("assisted");
        privacy = mode.equals("local") && game.end == null;
        MatchCodec.State current = state();
        if (current.end() != null) return;
        restoreRackOrder(saved.optJSONArray("rack_order"), current);
        for (FrenchGame.Tile tile : tiles) {
            if (tile.rackIndex() < 0 || tile.rackIndex() >= current.rack().size()
                    || current.board()[tile.position()] != 0 || stagedAt(tile.position()) >= 0
                    || staged.stream().anyMatch(previous -> previous.rackIndex() == tile.rackIndex())) continue;
            char letter = current.rack().get(tile.rackIndex());
            if (tile.blank() ? letter == FrenchGame.JOKER : letter == tile.letter()) staged.add(tile);
        }
    }

    private void restoreRackOrder(JSONArray savedOrder, MatchCodec.State current) {
        int count = current.rack().size();
        if (savedOrder == null || savedOrder.length() != count) return;
        List<Integer> order = new ArrayList<>(count);
        for (int slot = 0; slot < count; slot++) {
            int index = savedOrder.optInt(slot, -1);
            if (index < 0 || index >= count || order.contains(index)) return;
            order.add(index);
        }
        rackOrder.addAll(order);
        lastRackTurn = current.history().size();
    }

    private MatchCodec.State state() {
        if (game == null) return guestState;
        int seat = mode.equals("solo") || mode.equals("bluetooth") ? 0 : game.active;
        return MatchCodec.forLocal(game, seat);
    }

    private boolean myTurn(MatchCodec.State state) {
        return state != null && state.end() == null && state.active() == state.seat()
                && !privacy && !waiting && (!mode.equals("bluetooth") || bluetoothReady);
    }

    private void clearStaged() {
        staged.clear();
        selected = -1;
    }

    private void resetMatch() {
        closeLink();
        bluetoothPeerAddress = null;
        bluetoothDevice = null;
        mode = "";
        game = null;
        guestState = null;
        finishedReview = null;
        matchLexiconHash = "";
        privacy = botThinking = analysing = assisted = waiting = false;
        lastRackTurn = -1;
        rackOrder.clear();
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
        if (!matchLexiconHash.isEmpty()) MatchCodec.put(saved, "lexicon_sha256", matchLexiconHash);
        if (!staged.isEmpty()) MatchCodec.put(saved, "draft", MatchCodec.tiles(staged, true));
        if (lastRackTurn == game.history.size()) {
            for (int slot = 0; slot < rackOrder.size(); slot++) {
                if (rackOrder.get(slot) != slot) {
                    MatchCodec.put(saved, "rack_order", new JSONArray(rackOrder));
                    break;
                }
            }
        }
        return saved;
    }

    private void archiveIfFinished() {
        MatchCodec.State finished = state();
        Lexicon dictionary = matchLexicon();
        if (finished == null || finished.end() == null || store == null || dictionary == null) return;
        int opponent = 0;
        if (!personalSession && mode.equals("solo") && !assisted) {
            int level = finished.levels()[1];
            opponent = level == Bot.ADAPTIVE
                    ? (botLevelCount == 0 ? Bot.RATINGS[2] : Math.round(botLevelSum / (float) botLevelCount))
                    : Bot.RATINGS[level];
        }
        try {
            JSONObject archived = MatchCodec.finished(finished, mode, dictionary.label, game == null ? null : game.seed);
            MatchCodec.put(archived, "lexicon_sha256", dictionary.hash);
            if (assisted) MatchCodec.put(archived, "assisted", true);
            store.archive(archived, opponent, Integer.compare(finished.scores()[0], finished.scores()[1]));
            if (!personalSession) skinStore.refresh(store, dailyProgress);
            if (!mode.equals("bluetooth")) store.removeGame(finished.id());
        } catch (Exception exception) {
            toast(getString(R.string.app_history_save_error));
        }
    }

    private AlertDialog.Builder dialog(String title, String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(title);
        if (message != null) builder.setMessage(message);
        return builder;
    }

    private void explainMove(List<FrenchGame.Move> history, int moveIndex) {
        MoveExplanation explanation = MoveExplanation.create(history, moveIndex, gameLanguage);
        if (explanation == null) return;
        showExplanation(getString(R.string.move_explanation_title, moveIndex + 1),
                explanation.summary(), explanation.facts(), explanation.options(), explanation.details(), explanation.words());
    }

    private void explainReview(MatchReview.Turn turn) {
        showExplanation(getString(R.string.move_explanation_title, turn.index() + 1),
                turn.summary(), turn.facts(), turn.options(), turn.details(), List.of());
    }

    private void showExplanation(String title, String summary, String facts, List<String> options, List<String> details,
                                 List<String> words) {
        if (explanationDialog != null) return;

        LinearLayout panel = ui.column();
        ui.inset(panel, 24, 12);
        TextView body = ui.body(details.isEmpty() ? summary : summary + "\n\n" + details.get(0));
        body.setTextColor(ui.palette.ink());
        panel.addView(body);
        DefinitionAction.add(ui, panel, words);
        if (details.isEmpty()) {
            ui.gap(panel, 9);
            panel.addView(ui.body(getString(R.string.move_explanation_partial)));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel, new ScrollView.LayoutParams(-1, -2));
        AlertDialog active = dialog(title, null)
                .setView(scroll)
                .setPositiveButton(R.string.move_explanation_close, null)
                .show();
        explanationDialog = active;
        if (details.size() > 1) addExplanationChoices(panel, active, facts, options, details);
        active.setOnDismissListener(ignored -> {
            if (explanationDialog != active) return;
            if (explanationTask != null) explanationTask.cancel(true);
            explanationTask = null;
            explanationDialog = null;
        });
    }

    private void addExplanationChoices(LinearLayout panel, AlertDialog active, String facts,
                                       List<String> options, List<String> details) {
        ui.gap(panel, 9);
        TextView extra = ui.body(String.join("\n\n", details.subList(1, details.size())));
        extra.setTextColor(ui.palette.ink());
        extra.setVisibility(View.GONE);
        TextView toggle = ui.textButton(getString(R.string.move_explanation_more), () -> {});
        toggle.setOnClickListener(view -> {
            boolean expanded = extra.getVisibility() != View.VISIBLE;
            extra.setVisibility(expanded ? View.VISIBLE : View.GONE);
            toggle.setText(expanded ? R.string.move_explanation_less : R.string.move_explanation_more);
        });
        panel.addView(toggle);
        panel.addView(extra);
        ui.gap(panel, 9);
        TextView status = ui.body("");
        status.setVisibility(View.GONE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        TextView ask = ui.textButton(getString(R.string.move_explanation_ask_model), () -> {});
        ask.setOnClickListener(view -> {
            ui.enabled(ask, false);
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.move_explanation_loading);
            chooseExplanationDetail(active, facts, options, details, ask, status);
        });
        panel.addView(ask);
        panel.addView(status);
    }

    private void chooseExplanationDetail(AlertDialog active, String facts, List<String> options, List<String> details,
                                         TextView ask, TextView status) {
        Context context = getApplicationContext();
        explanationTask = explanationWorker.submit(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            int index = -1;
            try {
                index = new OfflineMoveModel(context).choose(facts, options);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception | LinkageError ignored) {
                // A failed suggestion can be retried from the dialog.
            }
            if (Thread.currentThread().isInterrupted()) return;
            int selected = index;
            main.post(() -> {
                if (explanationDialog != active || !active.isShowing() || isDestroyed()) return;
                explanationTask = null;
                if (selected >= 0 && selected < details.size()) {
                    status.setText(getString(R.string.move_explanation_model, details.get(selected)));
                } else {
                    status.setText(R.string.move_explanation_engine);
                    ui.enabled(ask, true);
                }
            });
        });
    }

    private final class Arena {
        final LinearLayout scores = ui.column();
        final LinearLayout rack = ui.row();
        final View[] rackTokens = new View[FrenchGame.RACK_SIZE];
        final RackDrop rackDrop = new RackDrop(ui, rack, Arrays.asList(rackTokens),
                MainActivity.this::rackTileCanMove, MainActivity.this::reorderRack);
        final TextView status = ui.text("", 12, ui.palette.muted(), ui.medium);
        final TextView turn = ui.text("", 12, ui.palette.ink(), ui.medium);
        final TextView preview = ui.text("", 14, ui.palette.ink(), ui.medium);
        final TextView confirm = ui.button(getString(R.string.practice_ready), true, MainActivity.this::commit);
        final TextView recall = ui.textButton(getString(R.string.app_recall_tiles), () -> {
            if (!staged.isEmpty()) arena.recall.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            clearStaged();
            updateDraft(-1);
        });
        final TextView shuffle = ui.textButton("⤨", () -> {
            arena.shuffle.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            shuffleRack();
        });
        final TextView tools = ui.textButton(getString(R.string.app_turn_tools), MainActivity.this::turnOptions);
        final TextView explain = ui.textButton(getString(R.string.move_explanation_last), () -> {
            MatchCodec.State current = state();
            if (current != null) explainMove(current.history(), current.history().size() - 1);
        });
        int renderedTurn = -1;
        int[] renderedScores;
        int previewScore = -1;
        final BoardView board = new BoardView(ui, new BoardView.Interaction() {
            @Override public void tap(int position) { boardTap(position); }
            @Override public boolean canTap(int position) {
                return myTurn(state()) && !analysing && (selected >= 0 || stagedAt(position) >= 0);
            }
            @Override public void drop(int position, int rackIndex) { place(position, rackIndex); }
            @Override public boolean dragTile(int position) {
                return myTurn(state()) && !analysing && stagedAt(position) >= 0;
            }
            @Override public void moveTile(int from, int to) { moveStaged(from, to); }
        });
    }

    private void showGame() {
        MatchCodec.State current = state();
        if (current == null) {
            if (personalSession) showGames();
            else showHome();
            return;
        }
        if (current.end() != null) {
            showMatchResult(current);
            return;
        }
        if (mode.equals("local")) privacy = true;
        page(Screen.GAME, false);
        top(mode.equals("local") ? getString(R.string.app_local) : mode.equals("bluetooth") ? "Bluetooth" : getString(R.string.app_solo),
                this::leaveGame, mode.equals("bluetooth") ? null : ui.textButton(getString(R.string.games_title), () -> {
                    if (persist()) showGames();
                }));
        if (personalSession) content.addView(ui.body(getString(R.string.personal_lexicon_game_context)));
        arena = new Arena();
        content.addView(arena.scores);
        ui.gap(content, 3);
        LinearLayout.LayoutParams boardBox = new LinearLayout.LayoutParams(-1, -2);
        boardBox.gravity = Gravity.CENTER_HORIZONTAL;
        Arena opened = arena;
        LinearLayout scrollContent = content;
        ScrollView scroll = screenScroll;
        Runnable fitBoard = () -> {
            if (arena != opened || opened.board.getHeight() == 0) return;
            Configuration config = getResources().getConfiguration();
            int width = scrollContent.getWidth() - scrollContent.getPaddingLeft() - scrollContent.getPaddingRight();
            int available = dockArenaTray(config) ? scroll.getHeight() - opened.board.getTop() - ui.dp(4)
                    : config.orientation == Configuration.ORIENTATION_LANDSCAPE ? ui.dp(config.screenHeightDp - 84) : width;
            int side = Math.min(width, Math.max(ui.dp(208), available));
            if (side > 0 && boardBox.width != side) {
                boardBox.width = side;
                opened.board.requestLayout();
            }
        };
        scrollContent.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> scroll.post(fitBoard));
        scroll.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> scroll.post(fitBoard));
        LinearLayout boardTools = ui.row();
        TextView minus = ui.textButton("−", () -> arena.board.zoomBy(0.8f));
        minus.setContentDescription(getString(R.string.practice_zoom_out));
        TextView plus = ui.textButton("+", () -> arena.board.zoomBy(1.25f));
        plus.setContentDescription(getString(R.string.practice_zoom));
        TextView reset = ui.textButton("", () -> arena.board.resetZoom());
        reset.setContentDescription(getString(R.string.practice_zoom_reset));
        for (TextView control : List.of(minus, reset, plus)) {
            control.setMinimumHeight(ui.dp(39));
            control.setMinHeight(ui.dp(39));
        }
        arena.board.onZoom(zoom -> {
            reset.setText(getString(R.string.practice_zoom_percent, Math.round(zoom * 100)));
            ui.enabled(minus, zoom > 1.001f);
            ui.enabled(plus, zoom < 3.499f);
        });
        ui.weight(boardTools, arena.status);
        boardTools.addView(minus);
        boardTools.addView(reset);
        boardTools.addView(plus);
        content.addView(boardTools);
        ui.gap(content, 3);
        content.addView(arena.board, boardBox);
        content.addView(arena.explain);
        addArenaTray(opened);
        updateArena(-1);
        startBotIfNeeded();
    }

    private boolean dockArenaTray(Configuration config) {
        return config.orientation == Configuration.ORIENTATION_PORTRAIT && config.screenHeightDp >= 580
                && config.fontScale <= 1.3f;
    }

    private void addArenaTray(Arena opened) {
        LinearLayout tray = ui.column();
        ui.inset(tray, 10, 7);
        tray.setBackground(ui.shape(ui.palette.paper(), 16, ui.palette.line()));
        tray.setElevation(ui.dp(3));
        opened.rack.setClipChildren(false);
        LinearLayout rackTools = ui.row();
        opened.shuffle.setContentDescription(getString(R.string.app_shuffle_rack));
        ui.weight(rackTools, opened.turn);
        rackTools.addView(opened.shuffle);
        tray.addView(rackTools);
        tray.addView(opened.rack);
        ui.gap(tray, 6);
        ui.inset(opened.preview, 9, 6);
        opened.preview.setMinHeight(ui.dp(47));
        opened.preview.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        tray.addView(opened.preview, new LinearLayout.LayoutParams(-1, -2));
        ui.gap(tray, 6);
        LinearLayout actions = ui.row();
        ui.weight(actions, opened.confirm);
        ui.space(actions, 5);
        actions.addView(opened.recall);
        actions.addView(opened.tools);
        tray.addView(actions);
        LinearLayout scrollContent = content;
        LinearLayout shell = screenShell;
        LinearLayout footer = ui.row();
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(ui.dp(10), ui.dp(2), ui.dp(10), ui.dp(5));
        Runnable fit = () -> {
            if (arena != opened) return;
            Configuration config = getResources().getConfiguration();
            if (dockArenaTray(config)) {
                int width = ui.dp(Math.min(510, config.screenWidthDp - 20));
                if (tray.getParent() != footer) {
                    scrollContent.removeView(tray);
                    footer.addView(tray, new LinearLayout.LayoutParams(width, -2));
                    shell.addView(footer, new LinearLayout.LayoutParams(-1, -2));
                } else if (tray.getLayoutParams().width != width) {
                    tray.getLayoutParams().width = width;
                    tray.requestLayout();
                }
            } else if (tray.getParent() != scrollContent) {
                footer.removeView(tray);
                shell.removeView(footer);
                scrollContent.addView(tray, new LinearLayout.LayoutParams(-1, -2));
            }
        };
        fit.run();
        shell.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) shell.post(fit);
        });
    }

    private void updateArena(int animatedPosition) {
        if (arena == null || screen != Screen.GAME) return;
        MatchCodec.State current = state();
        if (current == null) return;
        if (current.end() != null) {
            showMatchResult(current);
            return;
        }
        renderScores(current);
        boolean canPlay = myTurn(current) && !analysing;
        List<FrenchGame.Tile> visibleDraft = privacy ? List.of() : staged;
        List<FrenchGame.Tile> recent = current.history().isEmpty() ? List.of()
                : current.history().get(current.history().size() - 1).tiles();
        arena.board.update(current.board(), current.blanks(), visibleDraft, recent, animatedPosition);
        arena.status.setText(getString(R.string.app_bag_turn, current.bagSize(), current.history().size() + 1));
        arena.rack.removeAllViews();
        Arrays.fill(arena.rackTokens, null);

        boolean draft = canPlay && !staged.isEmpty();
        FrenchGame.Preview result = draft ? evaluate(current) : null;
        boolean legal = draft && result.valid();
        arena.preview.setBackground(ui.shape(legal ? ui.palette.ordinary() : ui.palette.paper(),
                10, legal ? ui.palette.accent() : ui.palette.line()));
        arena.turn.setText(canPlay ? getString(R.string.arena_your_turn)
                : getString(R.string.arena_active_player, current.names()[current.active()]));
        ui.enabled(arena.shuffle, canPlay && current.rack().size() > 1);
        if (privacy) {
            ui.weight(arena.rack, ui.button(getString(R.string.app_show_rack), true, () -> {
                privacy = false;
                updateArena(-1);
            }));
            arena.preview.setText(getString(R.string.local_pass_device, current.names()[current.active()]));
        } else if (mode.equals("bluetooth") && !bluetoothReady) {
            arena.preview.setText(bluetoothStatus.isEmpty() ? getString(R.string.bluetooth_disconnected) : bluetoothStatus);
            ui.weight(arena.rack, ui.button(getString(link == null ? R.string.app_bluetooth_reconnect
                    : R.string.app_bluetooth_cancel), true, link == null ? this::showBluetooth : () -> {
                closeLink();
                updateArena(-1);
            }));
        } else if (!myTurn(current)) {
            if (mode.equals("solo")) renderRack(current, false);
            arena.preview.setText(waiting ? getString(R.string.app_host_validating)
                    : getString(botThinking ? R.string.app_thinking : R.string.app_playing, current.names()[current.active()]));
        } else {
            renderRack(current, canPlay);
            if (analysing) {
                arena.preview.setText(R.string.app_analyzing);
            } else if (staged.isEmpty()) {
                arena.preview.setText(selected < 0 ? R.string.arena_place_hint : R.string.arena_place_selected);
            } else if (legal) {
                String value = points(result.score());
                String label = getString(R.string.arena_valid_preview, getString(R.string.practice_status_valid),
                        result.word(), value);
                android.text.SpannableString scored = new android.text.SpannableString(label);
                scored.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        label.length() - value.length(), label.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                arena.preview.setText(scored);
            } else {
                arena.preview.setText(getString(R.string.arena_invalid_preview,
                        getString(R.string.practice_status_invalid), result.error()));
            }
        }
        if (legal && result.score() != arena.previewScore && animatedPosition >= 0) pulseMoveFeedback(arena.preview);
        arena.previewScore = legal ? result.score() : -1;
        ui.enabled(arena.confirm, legal);
        ui.enabled(arena.recall, canPlay && !staged.isEmpty());
        ui.enabled(arena.tools, canPlay);
        arena.explain.setVisibility(current.history().isEmpty() ? View.GONE : View.VISIBLE);
        if (!current.history().isEmpty()) {
            FrenchGame.Move last = current.history().get(current.history().size() - 1);
            arena.explain.setText(last.type().equals("play")
                    ? getString(R.string.arena_last_score, last.word(), points(last.score()))
                    : getString(R.string.move_explanation_last));
            arena.explain.setContentDescription(last.type().equals("play") ? getString(R.string.arena_last_explain,
                    last.word(), points(last.score())) : null);
        }
    }

    private void renderScores(MatchCodec.State current) {
        int turn = current.history().size();
        if (arena.renderedTurn == turn && Arrays.equals(arena.renderedScores, current.scores())) return;
        int[] previousScores = arena.renderedScores;
        arena.renderedTurn = turn;
        arena.renderedScores = current.scores().clone();
        arena.scores.removeAllViews();
        boolean stacked = current.names().length > 2;
        int columns = stacked && getResources().getConfiguration().fontScale > 1.3f
                ? 2 : current.names().length;
        LinearLayout row = ui.row();
        arena.scores.addView(row);
        for (int i = 0; i < current.names().length; i++) {
            if (i == columns) {
                ui.gap(arena.scores, 4);
                row = ui.row();
                arena.scores.addView(row);
            } else if (i != 0) ui.space(row, 5);
            LinearLayout seat = stacked ? ui.column() : ui.row();
            ui.inset(seat, stacked ? 6 : 8, stacked ? 3 : 7);
            boolean active = i == current.active();
            seat.setBackground(ui.shape(active ? ui.palette.deep() : ui.palette.paper(),
                    13, active ? 0 : ui.palette.line()));
            TextView name = ui.text(current.names()[i], 11, active ? ui.palette.heroInk() : ui.palette.muted(), ui.medium);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (stacked) seat.addView(name, new LinearLayout.LayoutParams(-1, -2));
            else {
                ui.weight(seat, name);
                ui.space(seat, 4);
            }
            TextView score = ui.text(Integer.toString(current.scores()[i]), 18,
                    active ? ui.palette.heroInk() : ui.palette.ink(), ui.medium);
            seat.addView(score);
            if (previousScores != null && previousScores[i] != current.scores()[i]) pulseMoveFeedback(score);
            ui.weight(row, seat);
        }
        if (current.names().length % columns != 0) {
            ui.space(row, 5);
            ui.weight(row, new View(this));
        }
    }

    private void pulseMoveFeedback(View view) {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        view.animate().cancel();
        view.setAlpha(0.72f);
        view.setScaleX(0.98f);
        view.setScaleY(0.98f);
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(175).start();
    }

    private void renderRack(MatchCodec.State current, boolean canPlay) {
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
            token.setMinimumHeight(ui.dp(47));
            updateRackSelection(token, index);
            TextView glyph = ui.text(occupied[index] ? " " : String.valueOf(letter),
                    22, ui.tileInk(), ui.medium);
            glyph.setGravity(Gravity.CENTER);
            glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            token.addView(glyph);
            if (!occupied[index]) {
                int tilePoints = gameLanguage.value(letter);
                TextView value = ui.text(Integer.toString(tilePoints), 10, ui.tileInk(), ui.medium);
                value.setGravity(Gravity.CENTER);
                value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                token.addView(value);
                token.setContentDescription(letter == FrenchGame.JOKER ? getString(R.string.practice_joker)
                        : getString(R.string.board_accessibility_tile, letter, points(tilePoints)));
                if (canPlay) {
                    token.setOnClickListener(view -> selectRackTile(index));
                    ui.dragTile(token, index, letter, () -> !analysing && myTurn(state()));
                } else token.setAlpha(0.74f);
            } else {
                token.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                token.setAlpha(0.42f);
            }
            arena.rackTokens[index] = token;
            ui.weight(arena.rack, token);
        }
        arena.rackDrop.refreshActions();
    }

    private boolean rackTileCanMove(int index) {
        return !analysing && arena != null && myTurn(state()) && rackOrder.contains(index)
                && staged.stream().noneMatch(tile -> tile.rackIndex() == index);
    }

    private void reorderRack(int source, int target) {
        if (!rackTileCanMove(source)) return;
        int from = rackOrder.indexOf(source);
        int to = rackOrder.indexOf(target);
        if (to < 0 || from == to) return;
        rackOrder.add(to, rackOrder.remove(from));
        arena.rack.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        updateDraft(-1);
    }

    private void shuffleRack() {
        selected = -1;
        Collections.shuffle(rackOrder);
        updateDraft(-1);
        pulseMoveFeedback(arena.rack);
    }

    private void selectRackTile(int index) {
        if (analysing || !myTurn(state())) return;
        int previous = selected;
        selected = selected == index ? -1 : index;
        if (previous >= 0) updateRackSelection(arena.rackTokens[previous], previous);
        if (selected >= 0) updateRackSelection(arena.rackTokens[selected], selected);
        if (selected >= 0) arena.rackTokens[selected].performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (staged.isEmpty()) arena.preview.setText(selected < 0 ? R.string.arena_place_hint : R.string.arena_place_selected);
        arena.board.refreshCellActions();
    }

    private void updateRackSelection(View token, int index) {
        boolean active = index == selected;
        token.setSelected(active);
        token.setBackground(ui.rackTile(active));
        token.setTranslationY(active ? -ui.dp(3) : 0);
        androidx.core.view.ViewCompat.setStateDescription(token,
                active ? getString(R.string.practice_rack_selected) : null);
    }

    private FrenchGame.Preview evaluate(MatchCodec.State current) {
        Lexicon dictionary = matchLexicon();
        if (dictionary == null || staged.isEmpty()) return FrenchGame.Preview.invalid(getString(R.string.app_place_tiles));
        return FrenchGame.preview(current.board(), current.blanks(), current.rack(), dictionary, staged);
    }

    private void boardTap(int position) {
        MatchCodec.State current = state();
        if (!myTurn(current) || analysing) return;
        int index = stagedAt(position);
        if (index >= 0) {
            selected = staged.remove(index).rackIndex();
            arena.board.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            updateDraft(-1);
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
        arena.board.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        audio.tile();
        updateDraft(to);
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
            new TurnMenu(ui, R.string.practice_joker_choice).chooseJoker(chosen -> {
                if (stillPlayingTurn(current)) stage(position, rackIndex, chosen, true);
            });
        } else stage(position, rackIndex, letter, false);
    }

    private void stage(int position, int rackIndex, char letter, boolean blank) {
        if (!myTurn(state())) return;
        staged.add(new FrenchGame.Tile(position, rackIndex, letter, blank));
        selected = -1;
        if (arena != null) arena.board.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        audio.tile();
        updateDraft(position);
    }

    private void updateDraft(int position) {
        persist();
        updateArena(position);
    }

    private void commit() {
        MatchCodec.State current = state();
        if (!myTurn(current)) return;
        FrenchGame.Preview preview = evaluate(current);
        if (!preview.valid()) { toast(preview.error()); return; }
        if (arena != null) {
            arena.confirm.performHapticFeedback(Build.VERSION.SDK_INT >= 30
                    ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.CLOCK_TICK);
        }
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
                    FrenchGame.Preview preview = game.play(tiles, matchLexicon());
                    if (!preview.valid()) return preview.error();
                }
                case "pass" -> game.pass();
                case "exchange" -> game.exchange(exchange);
                default -> { return getString(R.string.app_action_unknown); }
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
        Lexicon dictionary = matchLexicon();
        if (game == null || !mode.equals("solo") || game.end != null || game.active != 1
                || botThinking || dictionary == null) return;
        botThinking = true;
        updateArena(-1);
        FrenchGame snapshot = game;
        int turn = snapshot.history.size();
        int rating = startRating;
        worker.submit(() -> {
            Bot.Choice result;
            try {
                result = Bot.choose(snapshot, dictionary, snapshot.levels[1], rating);
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
        if (!myTurn(current) || privacy || analysing) return;
        TurnMenu menu = new TurnMenu(ui, R.string.app_turn_options);
        boolean exchangeAvailable = current.bagSize() >= FrenchGame.RACK_SIZE;
        boolean solo = mode.equals("solo");
        menu.option("↻", R.string.app_shuffle_rack, R.string.turn_shuffle_help, () -> {
            if (!stillPlayingTurn(current)) return;
            shuffleRack();
        });
        menu.option("⇄", R.string.app_exchange_tiles,
                exchangeAvailable ? R.string.turn_exchange_help : R.string.app_exchange_minimum,
                exchangeAvailable ? () -> exchangeDialog(current) : null);
        menu.option("→", R.string.app_pass_turn, R.string.turn_pass_help, () -> {
            if (!stillPlayingTurn(current)) return;
            new TurnMenu(ui, R.string.app_pass_confirm).confirmPass(() -> {
                if (stillPlayingTurn(current)) action("pass", List.of(), List.of());
            });
        });
        menu.option("✦", R.string.app_find_move,
                solo ? R.string.turn_hint_help : R.string.app_hint_solo, solo ? () -> {
                    if (stillPlayingTurn(current)) analyzeCurrent(current);
                } : null);
        menu.show();
    }

    private void exchangeDialog(MatchCodec.State current) {
        if (!stillPlayingTurn(current)) return;
        new TurnMenu(ui, R.string.app_exchange_tiles).selectTiles(current.rack(), gameLanguage, exchange -> {
            if (stillPlayingTurn(current)) action("exchange", List.of(), exchange);
        });
    }

    private boolean stillPlayingTurn(MatchCodec.State openedFor) {
        MatchCodec.State current = state();
        return screen == Screen.GAME && myTurn(current) && !privacy && !analysing
                && current.id().equals(openedFor.id()) && current.history().size() == openedFor.history().size();
    }

    private void analyzeCurrent(MatchCodec.State current) {
        if (mode.equals("bluetooth") || mode.equals("local")) {
            toast(getString(R.string.app_hint_solo));
            return;
        }
        analysing = true;
        FrenchGame gameAtRequest = game;
        Lexicon dictionary = matchLexicon();
        updateArena(-1);
        worker.submit(() -> {
            Bot.Choice candidate;
            try {
                candidate = Bot.choose(current.board().clone(), current.blanks().clone(), List.copyOf(current.rack()),
                        current.bagSize(), dictionary, 4, 0);
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
                    toast(getString(R.string.app_hint_no_move));
                    return;
                }
                clearStaged();
                staged.addAll(result.tiles());
                assisted = true;
                updateDraft(result.tiles().get(0).position());
                toast(getString(R.string.app_hint_score, points(result.score())));
            });
        });
    }

    private String resultTitle(MatchCodec.State current) {
        int maximum = Arrays.stream(current.scores()).max().orElse(0);
        List<String> winners = new ArrayList<>();
        for (int i = 0; i < current.scores().length; i++) {
            if (current.scores()[i] == maximum) winners.add(current.names()[i]);
        }
        if (mode.equals("solo") && current.scores()[0] > current.scores()[1]) {
            return getString(R.string.match_result_solo_win);
        }
        return winners.size() == 1 ? getString(R.string.app_winner, winners.get(0))
                : getString(R.string.app_draw, String.join(", ", winners));
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
        heading(getString(R.string.app_bluetooth_two), getString(R.string.app_bluetooth_pair));
        if (bluetoothReady && state() != null && mode.equals("bluetooth")) {
            content.addView(ui.button(getString(R.string.app_resume), true, this::showGame));
            return;
        }
        if (link != null) {
            content.addView(ui.body(bluetoothStatus));
            ui.gap(content, 11);
            content.addView(ui.button(getString(R.string.app_bluetooth_cancel), false, () -> {
                closeLink();
                showBluetooth();
            }));
            return;
        }
        BluetoothAdapter adapter = adapter();
        if (adapter == null) {
            content.addView(ui.body(getString(R.string.app_bluetooth_missing)));
            return;
        }
        if (!bluetoothPermission()) {
            String permission = Manifest.permission.BLUETOOTH_CONNECT;
            if (preferences.getBoolean("bluetooth_permission_requested", false)
                    && !shouldShowRequestPermissionRationale(permission)) {
                content.addView(ui.body(getString(R.string.app_bluetooth_nearby_hint)));
                ui.gap(content, 8);
                content.addView(ui.button(getString(R.string.app_bluetooth_settings), true, () -> startActivityForResult(
                        new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)),
                        APP_SETTINGS)));
            } else {
                content.addView(ui.button(getString(R.string.app_bluetooth_nearby), true, () -> {
                    preferences.edit().putBoolean("bluetooth_permission_requested", true).apply();
                    requestPermissions(new String[]{permission}, BLUETOOTH_PERMISSION);
                }));
            }
            return;
        }
        if (!adapter.isEnabled()) {
            content.addView(ui.button(getString(R.string.app_bluetooth_enable), true, this::enableBluetooth));
            return;
        }
        MatchCodec.State current = state();
        if (mode.equals("bluetooth") && current != null && (current.end() == null || bluetoothHost && !bluetoothReady)) {
            content.addView(ui.body(bluetoothStatus.isEmpty()
                    ? getString(R.string.app_bluetooth_resume_status) : bluetoothStatus));
            ui.gap(content, 8);
            content.addView(ui.button(getString(bluetoothHost ? R.string.app_bluetooth_resume_host
                    : R.string.app_bluetooth_reconnect), true, () -> startBluetoothLink(bluetoothDevice, current.id())));
            if (!bluetoothHost) content.addView(ui.body(getString(R.string.app_bluetooth_resume_guest_hint)));
            ui.gap(content, 8);
            content.addView(ui.textButton(getString(R.string.games_bluetooth_continue),
                    () -> switchMatch(() -> { resetMatch(); showBluetooth(); })));
            return;
        }
        List<BluetoothDevice> devices;
        try {
            devices = new ArrayList<>(adapter.getBondedDevices());
        } catch (SecurityException exception) {
            content.addView(ui.body(getString(R.string.app_bluetooth_permission)));
            return;
        }
        EditText name = input(getString(R.string.app_bluetooth_your_name), bluetoothName);
        content.addView(name, new LinearLayout.LayoutParams(-1, ui.dp(48)));
        ui.gap(content, 11);
        content.addView(ui.button(getString(R.string.app_bluetooth_host_game), true, () -> {
            String entered = name.getText().toString().trim();
            switchMatch(() -> beginLink(null, entered));
        }));
        ui.gap(content, 15);
        content.addView(ui.micro(getString(R.string.app_bluetooth_devices), ui.palette.accent()));
        ui.gap(content, 8);
        devices.sort((left, right) -> deviceName(left).compareToIgnoreCase(deviceName(right)));
        if (devices.isEmpty()) content.addView(ui.body(getString(R.string.app_bluetooth_no_devices)));
        for (BluetoothDevice device : devices) {
            content.addView(ui.option("↔", deviceName(device), getString(R.string.app_bluetooth_join), () -> {
                String entered = name.getText().toString().trim();
                switchMatch(() -> beginLink(device, entered));
            }));
            ui.gap(content, 8);
        }
        content.addView(ui.textButton(getString(R.string.app_bluetooth_pair_settings), () -> {
            try {
                startActivityForResult(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), BLUETOOTH_SETTINGS);
            } catch (android.content.ActivityNotFoundException unavailable) {
                toast(getString(R.string.app_bluetooth_missing));
            }
        }));
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
        return getString(R.string.app_bluetooth_device);
    }

    private void enableBluetooth() {
        try {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BLUETOOTH);
        } catch (SecurityException exception) {
            toast(getString(R.string.app_bluetooth_permission));
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
        resetMatch();
        mode = "bluetooth";
        gameBack = Screen.HOME;
        bluetoothHost = peer == null;
        bluetoothDevice = peer;
        bluetoothName = name.isEmpty() ? (bluetoothHost ? getString(R.string.app_bluetooth_host) : getString(R.string.profile_guest)) : name;
        startBluetoothLink(peer, null);
    }

    private void startBluetoothLink(BluetoothDevice peer, String resumeId) {
        if (!ready() || !bluetoothPermission()) return;
        if (!bluetoothHost && peer == null) return;
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled()) { toast(getString(R.string.app_bluetooth_enable_first)); return; }
        closeLink();
        bluetoothStatus = bluetoothHost ? getString(R.string.app_bluetooth_waiting) : getString(R.string.app_bluetooth_connecting, deviceName(peer));
        BluetoothLink next = new BluetoothLink(new BluetoothLink.Listener() {
            @Override public void connected(BluetoothLink connection) {
                main.post(() -> {
                    if (link != connection) return;
                    connection.send(BluetoothSession.hello(bluetoothName, lexicon.hash, bluetoothHost, resumeId));
                    main.postDelayed(() -> {
                        if (link == connection && !bluetoothReady) rejectBluetooth(R.string.app_bluetooth_handshake_timeout);
                    }, BLUETOOTH_HANDSHAKE_TIMEOUT_MS);
                });
            }

            @Override public void received(BluetoothLink connection, JSONObject row) {
                main.post(() -> {
                    if (link == connection) receiveBluetooth(row, resumeId);
                });
            }

            @Override public void lost(BluetoothLink connection, int reason) {
                main.post(() -> {
                    if (link != connection) return;
                    closeLink();
                    bluetoothStatus = getString(reason);
                    if (screen == Screen.BLUETOOTH) showBluetooth();
                    else if (screen == Screen.HOME) showHome();
                    else if (screen == Screen.GAMES) showGames();
                    else if (screen == Screen.RESULT && state() != null) showMatchResult(state());
                    else updateArena(-1);
                    toast(bluetoothStatus);
                });
            }
        });
        link = next;
        showBluetooth();
        next.start(adapter, peer, resumeId == null ? null : bluetoothPeerAddress);
    }

    private void closeLink() {
        BluetoothLink current = link;
        link = null;
        if (current != null) current.close();
        bluetoothReady = false;
        bluetoothHelloReceived = false;
        bluetoothStatus = "";
        waiting = false;
    }

    private void receiveBluetooth(JSONObject row, String resumeId) {
        try {
            switch (row.getString("type")) {
                case "hello" -> {
                    if (bluetoothHelloReceived) return;
                    BluetoothSession.Decision decision = BluetoothSession.negotiate(row, lexicon.hash, bluetoothHost, resumeId);
                    String address = link.peerAddress();
                    if (decision == BluetoothSession.Decision.INCOMPATIBLE) {
                        rejectBluetooth(R.string.app_bluetooth_incompatible);
                        return;
                    }
                    if (decision == BluetoothSession.Decision.DIFFERENT_GAME
                            || bluetoothPeerAddress != null && !bluetoothPeerAddress.equals(address)) {
                        rejectBluetooth(R.string.app_bluetooth_wrong_match);
                        return;
                    }
                    bluetoothHelloReceived = true;
                    bluetoothPeerAddress = address;
                    if (!bluetoothHost) return;
                    if (game == null) {
                        String other = row.optString("name", getString(R.string.profile_guest)).trim();
                        if (other.isEmpty()) other = getString(R.string.profile_guest);
                        if (other.length() > 18) other = other.substring(0, 18);
                        game = new FrenchGame(new String[]{bluetoothName, other}, new int[2], gameLanguage);
                    }
                    bluetoothReady = true;
                    link.send(MatchCodec.visible(game, 1));
                    if (screen == Screen.BLUETOOTH) showGame();
                    else updateArena(-1);
                }
                case "state" -> {
                    if (bluetoothHost) return;
                    if (!bluetoothHelloReceived) throw new JSONException(getString(R.string.app_bluetooth_unexpected));
                    MatchCodec.State next = MatchCodec.readState(row);
                    if (next.language() != gameLanguage || next.seat() != 1 || next.names().length != 2
                            || row.getInt("version") != MatchCodec.VERSION
                            || (guestState != null && (!guestState.id().equals(next.id())
                            || next.history().size() < guestState.history().size()))) {
                        throw new JSONException(getString(R.string.app_bluetooth_unexpected));
                    }
                    boolean keepDraft = BluetoothSession.keepGuestDraft(guestState, next);
                    guestState = next;
                    bluetoothReady = true;
                    waiting = false;
                    if (!keepDraft) {
                        clearStaged();
                        lastRackTurn = -1;
                    }
                    archiveIfFinished();
                    if (screen == Screen.BLUETOOTH) showGame();
                    else updateArena(-1);
                }
                case "move" -> {
                    if (!bluetoothHost || game == null || !bluetoothReady) return;
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
                    if (bluetoothHost || !bluetoothReady) return;
                    waiting = false;
                    updateArena(-1);
                    toast(row.optString("message", getString(R.string.app_bluetooth_refused)));
                }
                default -> { }
            }
        } catch (JSONException | IllegalArgumentException exception) {
            if (bluetoothReady && bluetoothHost) link.send(MatchCodec.object("type", "invalid", "message", getString(R.string.app_invalid_move)));
            else rejectBluetooth(R.string.app_bluetooth_read_error);
        }
    }

    private void rejectBluetooth(int reason) {
        closeLink();
        bluetoothStatus = getString(reason);
        if (screen == Screen.BLUETOOTH) showBluetooth();
        else updateArena(-1);
        toast(bluetoothStatus);
    }

    private void showSettings() {
        page(Screen.SETTINGS, false);
        top(getString(R.string.app_name), this::showProfile, null);
        heading(getString(R.string.app_settings), "");
        boolean english = AppLocale.language(this) == GameLanguage.ENGLISH;
        content.addView(ui.option("Aa", getString(R.string.locale_settings), english ? "English" : "Français",
                () -> dialog(getString(R.string.locale_settings), null)
                        .setSingleChoiceItems(new String[]{"Français", "English"}, english ? 1 : 0, (choice, index) -> {
                            choice.dismiss();
                            selectLanguage(index == 0 ? GameLanguage.FRENCH : GameLanguage.ENGLISH);
                        }).show()));
        ui.gap(content, 12);
        LinearLayout settings = ui.card();
        Switch musicSwitch = soundSwitch(R.string.ambient_sound, "music");
        settings.addView(musicSwitch);
        String[] tracks = getResources().getStringArray(R.array.music_tracks);
        int trackIndex = Math.max(0, Math.min(tracks.length - 1, preferences.getInt("music_track", 0)));
        LinearLayout track = ui.option("♫", tracks[trackIndex], "", () ->
                dialog(getString(R.string.music_pick), null)
                        .setSingleChoiceItems(tracks, trackIndex, (choice, index) -> {
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
        if (rewarded.hasPrivacyOptions()) {
            content.addView(ui.option("○", getString(R.string.rewarded_privacy), "",
                    () -> rewarded.privacyOptions(this::refreshRewardViews)));
            ui.gap(content, 8);
        }
        content.addView(ui.option("↗", getString(R.string.social_navigation_endpoint),
                socialBaseUrl().isEmpty() ? "" : Uri.parse(socialBaseUrl()).getHost(), this::editSocialEndpoint));
        ui.gap(content, 8);
        content.addView(ui.option("◌", getString(R.string.turn_notifications_title),
                getString(TurnNotifications.enabled(this) && TurnNotifications.available(this)
                        ? R.string.turn_notifications_on : R.string.turn_notifications_off), this::editTurnNotifications));
        ui.gap(content, 8);
        content.addView(ui.option("↕", getString(R.string.backup_title), getString(R.string.backup_summary), this::showBackup));
        ui.gap(content, 8);
        content.addView(ui.option("Aa", getString(R.string.personal_lexicon_title),
                getString(R.string.personal_lexicon_entry), this::openPersonalLexicon));
        ui.gap(content, 8);
        content.addView(ui.option("○", getString(R.string.privacy_title), "", () ->
                dialog(getString(R.string.privacy_title), getString(R.string.privacy_details))
                        .setPositiveButton(R.string.move_explanation_close, null).show()));
        ui.gap(content, 18);
        content.addView(ui.micro(getString(R.string.app_appearance), ui.palette.accent()));
        ui.gap(content, 8);
        for (int i = 0; i < Palette.THEMES.length; i++) {
            int theme = i;
            String unlock = Palette.unlockId(i);
            boolean locked = !unlock.isEmpty() && !economy.themeUnlocked(unlock);
            String name = getString(Palette.THEMES[i].name());
            String cost = locked ? getString(R.string.bonus_theme_cost, BonusEconomy.themeCost(unlock)) : "";
            content.addView(ui.option(skin == i ? "✓" : "✦", name, cost,
                    () -> { if (locked) confirmTheme(theme, unlock, name, cost); else equipTheme(theme); }));
            ui.gap(content, 8);
        }
        if (lexicon != null) {
            ui.gap(content, 8);
            String label = lexicon.label;
            if (!personalSession && frenchClassicLexicon != null) {
                content.addView(ui.option("Aa", frenchClassicLexicon.label, getString(R.string.classic_dictionary_summary),
                        () -> dialog(frenchClassicLexicon.label, getString(R.string.classic_dictionary_summary)
                                + "\n\n" + getString(R.string.app_dictionary_hash, frenchClassicLexicon.hash))
                                .setPositiveButton(R.string.move_explanation_close, null).show()));
                ui.gap(content, 8);
            }
            content.addView(ui.option("Aa", label, getString(R.string.app_dictionary_license), () -> dialog(label,
                    (notice == null || notice.trim().isEmpty() ? getString(R.string.app_dictionary_bundled) : notice)
                            + "\n\n" + getString(R.string.app_dictionary_hash, lexicon.hash)).setPositiveButton(getString(R.string.move_explanation_close), null).show()));
        }
    }

    private void openPersonalLexicon() {
        updatePersonalLexicon(() -> personalLexicon.current(), false);
    }

    private void showPersonalLexicon() {
        page(Screen.PERSONAL_LEXICON, false);
        top(getString(R.string.app_name), () -> { if (!personalBusy) showSettings(); }, null);
        heading(getString(R.string.personal_lexicon_title), getString(R.string.personal_lexicon_intro));
        if (personalBusy) {
            content.addView(ui.body(getString(R.string.personal_lexicon_loading)));
            return;
        }
        if (personalEntry != null) {
            content.addView(ui.body(getResources().getQuantityString(R.plurals.personal_lexicon_loaded,
                    personalEntry.count(), personalEntry.count())));
            content.addView(ui.textButton(getString(R.string.personal_lexicon_details), this::showPersonalLexiconDetails));
            ui.gap(content, 8);
            content.addView(ui.button(getString(R.string.personal_lexicon_play), true, this::startPersonalSession));
            ui.gap(content, 8);
        }
        content.addView(ui.button(getString(personalEntry == null ? R.string.personal_lexicon_import
                : R.string.personal_lexicon_replace), personalEntry == null, () -> {
            try {
                startActivityForResult(PersonalLexicon.picker(), PERSONAL_LEXICON_IMPORT);
            } catch (android.content.ActivityNotFoundException unavailable) {
                toast(getString(R.string.backup_picker_error));
            }
        }));
        if (personalEntry != null) {
            ui.gap(content, 8);
            content.addView(ui.textButton(getString(R.string.personal_lexicon_remove), () ->
                    dialog(getString(R.string.personal_lexicon_remove), getString(R.string.personal_lexicon_remove_confirm))
                            .setNegativeButton(R.string.social_cancel, null)
                            .setPositiveButton(R.string.personal_lexicon_remove, (ignored, action) ->
                                    updatePersonalLexicon(() -> { personalLexicon.remove(); return null; }, false)).show()));
        }
    }

    private void showPersonalLexiconDetails() {
        PersonalLexicon.Entry entry = personalEntry;
        if (entry == null) return;
        dialog(getString(R.string.personal_lexicon_details), getString(R.string.app_dictionary_hash, entry.hash()))
                .setNegativeButton(R.string.move_explanation_close, null)
                .setPositiveButton(R.string.personal_lexicon_copy, (ignored, action) -> {
                    android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
                    if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SHA-256", entry.hash()));
                }).show();
    }

    private void updatePersonalLexicon(java.util.concurrent.Callable<PersonalLexicon.Entry> operation, boolean open) {
        if (personalBusy) return;
        personalBusy = true;
        showPersonalLexicon();
        worker.submit(() -> {
            try {
                PersonalLexicon.Entry entry = operation.call();
                Lexicon loaded = open ? personalLexicon.load(entry) : null;
                GameStore matches = open ? new GameStore(personalLexicon.matchDirectory(entry)) : null;
                main.post(() -> {
                    if (isDestroyed()) return;
                    personalBusy = false;
                    personalEntry = entry;
                    if (screen != Screen.PERSONAL_LEXICON) return;
                    if (open) {
                        resetMatch();
                        personalSession = true;
                        store = matches;
                        lexicon = loaded;
                        showGames();
                    } else showPersonalLexicon();
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (isDestroyed()) return;
                    personalBusy = false;
                    toast(getString(R.string.personal_lexicon_error));
                    if (screen == Screen.PERSONAL_LEXICON) showPersonalLexicon();
                });
            }
        });
    }

    private void startPersonalSession() {
        if (personalEntry == null || !persist()) return;
        if (link != null) {
            toast(getString(R.string.personal_lexicon_bluetooth));
            return;
        }
        updatePersonalLexicon(() -> personalLexicon.current(), true);
    }

    private void leavePersonalSession() {
        resetMatch();
        personalSession = false;
        store = bundledStore;
        lexicon = bundledLexicon;
        if (store != null) {
            try {
                List<JSONObject> saved = store.savedGames();
                if (!saved.isEmpty()) restoreMatch(saved.get(0));
            } catch (java.io.IOException | JSONException invalid) {
                toast(getString(R.string.games_read_error));
            }
        }
    }

    private void leavePersonalGames() {
        if (!persist()) return;
        leavePersonalSession();
        showPersonalLexicon();
    }

    private void showBackup() {
        page(Screen.BACKUP, false);
        top(getString(R.string.app_name), () -> { if (!backupInProgress) showSettings(); }, null);
        heading(getString(R.string.backup_title), getString(R.string.backup_details));
        TextView save = ui.button(getString(R.string.backup_export), true, () -> openBackupDocument(true));
        ui.enabled(save, !backupInProgress);
        content.addView(save);
        ui.gap(content, 8);
        TextView load = ui.button(getString(R.string.backup_import), false, () -> openBackupDocument(false));
        ui.enabled(load, !backupInProgress);
        content.addView(load);
        if (backupInProgress) content.addView(ui.body(getString(R.string.backup_working)));
    }

    private void showBackupRecovery() {
        page(Screen.BACKUP, false);
        top(getString(R.string.app_name), this::finish, null);
        heading(getString(R.string.backup_title), getString(R.string.backup_recovery_error));
        content.addView(ui.button(getString(R.string.backup_recovery_retry), true, this::recreate));
    }

    private void openBackupDocument(boolean create) {
        if (backupInProgress || !persist()) return;
        Intent intent = new Intent(create ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(create ? "application/zip" : "*/*");
        if (create) intent.putExtra(Intent.EXTRA_TITLE, "WordRivet-" + LocalDate.now() + ".zip");
        try {
            startActivityForResult(intent, create ? BACKUP_EXPORT : BACKUP_IMPORT);
        } catch (android.content.ActivityNotFoundException unavailable) {
            toast(getString(R.string.backup_picker_error));
        }
    }

    private void transferBackup(Uri uri, boolean create) {
        if (backupInProgress || !persist()) return;
        if (!create) resetMatch();
        backupInProgress = true;
        showBackup();
        Context storageContext = getApplicationContext();
        worker.submit(() -> {
            try {
                if (create) {
                    ProgressBackup.Snapshot snapshot = ProgressBackup.capture(storageContext);
                    try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                        if (output == null) throw new java.io.IOException();
                        ProgressBackup.write(snapshot, output);
                    }
                    main.post(() -> finishBackup(R.string.backup_saved));
                } else {
                    try (InputStream input = getContentResolver().openInputStream(uri)) {
                        if (input == null) throw new java.io.IOException();
                        ProgressBackup.Snapshot snapshot = ProgressBackup.read(input);
                        ProgressBackup.restore(storageContext, snapshot);
                    }
                    main.post(() -> finishBackup(R.string.backup_restored));
                }
            } catch (java.io.IOException | JSONException | RuntimeException failure) {
                android.util.Log.w("WordRivetBackup", "Échec du transfert", failure);
                main.post(() -> {
                    if (isDestroyed()) return;
                    if (!create && ProgressBackup.recoveryPending(storageContext)) {
                        backupInProgress = false;
                        backupRecoveryFailed = true;
                        showBackupRecovery();
                    } else finishBackup(create ? R.string.backup_write_error : R.string.backup_read_error);
                });
            }
        });
    }

    private void finishBackup(int result) {
        if (isDestroyed()) return;
        backupInProgress = false;
        toast(getString(result));
        if (result == R.string.backup_restored) {
            resetMatch();
            setIntent(new Intent(this, MainActivity.class));
            recreate();
        } else {
            showBackup();
        }
    }

    private void confirmTheme(int theme, String unlock, String name, String cost) {
        dialog(name, cost).setNegativeButton(R.string.social_cancel, null)
                .setPositiveButton(R.string.bonus_theme_unlock, (ignored, action) -> {
                    if (economy.unlockTheme(unlock)) equipTheme(theme);
                    else toast(getString(R.string.bonus_theme_insufficient));
                }).show();
    }

    private void equipTheme(int theme) {
        skin = theme;
        preferences.edit().putInt("skin", skin).apply();
        updateAppearance();
        showSettings();
    }

    private void selectLanguage(GameLanguage language) {
        switchLanguage(language, new Intent(this, MainActivity.class));
    }

    private void switchLanguage(GameLanguage language, Intent destination) {
        if (language == gameLanguage || screen == Screen.GAME && !persist()) return;
        AppLocale.select(this, language);
        setIntent(destination);
        recreate();
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
                                    setTurnNotifications(true);
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

    private void requestBonusVideo(LocalDate date) {
        BonusCircuitScreen origin = bonusCircuit;
        if (origin == null || !rewarded.available() || !economy.videoAvailable(date)) return;
        dialog(getString(R.string.rewarded_title), getString(R.string.rewarded_confirm))
                .setNegativeButton(getString(R.string.social_cancel), null)
                .setPositiveButton(R.string.rewarded_watch, (ignored, which) -> rewarded.request(
                        () -> bonusCircuit == origin && LocalDate.now().equals(date) && economy.videoAvailable(date),
                        () -> {
                            if (economy.video(date)) toast(getString(R.string.rewarded_ticket_earned));
                            refreshRewardViews();
                        }, this::refreshRewardViews))
                .show();
    }

    private void refreshRewardViews() {
        if (screen == Screen.SETTINGS) showSettings();
        if (bonusCircuit != null) bonusCircuit.refresh();
    }

    private void receiveReferralBalance(JSONObject referral) {
        String id = referral.optString("wallet_id");
        int count = referral.optInt("tickets_earned", -1);
        if (count <= 0 || !id.matches("[0-9a-f]{32}")) return;
        if (economy.referrals(gameLanguage, "wordrivet-social-v1", id, count)) {
            toast(getString(R.string.referral_ticket_earned));
            if (bonusCircuit != null) bonusCircuit.refresh();
        }
    }

    private void showProfile() {
        page(Screen.PROFILE, true);
        top(getString(R.string.app_name), null, ui.textButton(getString(R.string.app_settings), this::showSettings));
        heading(getString(R.string.profile_title), "");
        content.addView(ui.option("◇", getString(R.string.skins_title), getString(R.string.collection_profile_detail), this::showCollection));
        ui.gap(content, 10);
        profilePanel = new ProfilePanel(ui, profileStore, store, dailyProgress, this::chooseProfilePhoto);
        content.addView(profilePanel.view());
    }

    private void updateAppearance() {
        ui = new ViewKit(this, Palette.THEMES[skin], skinStore.selection(), audio, gameLanguage);
    }

    private void showCollection() {
        page(Screen.COLLECTION, false);
        top(getString(R.string.app_name), this::showProfile, null);
        content.addView(new SkinPanel(ui, skinStore, store, dailyProgress, this::updateAppearance).view());
    }

    private void chooseProfilePhoto() {
        try {
            startActivityForResult(ProfilePanel.photoPicker(), PROFILE_PHOTO);
        } catch (android.content.ActivityNotFoundException exception) {
            toast(getString(R.string.app_photo_picker));
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
                .setNegativeButton(getString(R.string.social_cancel), null)
                .setNeutralButton(R.string.social_navigation_endpoint_default, (d, action) -> {
                    preferences.edit().remove("social_endpoint").apply();
                    refreshSocialEndpoint();
                })
                .setPositiveButton(R.string.app_save, null).create();
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
        top(getString(R.string.app_name), null, ui.textButton(getString(R.string.social_navigation_endpoint), this::editSocialEndpoint));
        heading(getString(R.string.social_navigation_friends), "");
        if (pendingSocialInvite != null) {
            String origin = SocialClient.origin(socialBaseUrl());
            if (pendingSocialInviteOrigin == null && origin != null) {
                pendingSocialInviteOrigin = origin;
                rememberSocialInvitation();
            }
        }
        String visibleInvite = pendingSocialInviteOrigin == null || pendingSocialInviteOrigin.equals(SocialClient.origin(socialBaseUrl()))
                ? pendingSocialInvite : null;
        socialPanel = new SocialPanel(ui, socialClient, this::showSocialDuel, visibleInvite, this::connectGoogle,
                this::showTournament, this::editTurnNotifications, this::confirmBeta, this::betaInviteBase,
                this::editSocialEndpoint, this::openFriendInvitation, this::clearPendingSocialInvite);
        content.addView(socialPanel.view());
    }

    private void clearPendingSocialInvite(String token) {
        if (!token.equals(pendingSocialInvite)) return;
        pendingSocialInvite = null;
        pendingSocialInviteOrigin = null;
        rememberSocialInvitation();
        if (screen == Screen.SOCIAL && socialPanel != null) socialPanel.clearInvitation(token);
    }

    private void rememberSocialInvitation() {
        preferences.edit().putString(gameLanguage.storageName("pending_social_invite"), pendingSocialInvite)
                .putString(gameLanguage.storageName("pending_social_invite_origin"), pendingSocialInviteOrigin).apply();
    }

    private void showTournament() {
        page(Screen.TOURNAMENT, false);
        top(getString(R.string.app_name), this::showSocial, null);
        content.addView(new TournamentPanel(ui, socialClient, this::showTournamentRound, this::awardTournamentCoins).view());
    }

    private void showTournamentRound(JSONObject round) {
        if (!ready()) return;
        page(Screen.TOURNAMENT_ROUND, false);
        top(getString(R.string.tournament_title), this::showTournament, null);
        content.addView(new TournamentRoundPanel(ui, socialClient, round, bundledLexicon, frenchClassicLexicon, this::showTournament,
                this::awardTournamentCoins).view());
    }

    private void awardTournamentCoins(JSONObject event) {
        long id = event.optLong("id", -1);
        if (id >= 0 && event.optInt("completed") == 3 && economy.tournament(gameLanguage, Long.toString(id))) {
            toast(getString(R.string.tournament_coins_earned));
        }
    }

    private void showSocialDuel(JSONObject duel) {
        if (!ready()) return;
        TurnNotifications.dismiss(this, gameLanguage, duel.optString("id"));
        page(Screen.SOCIAL_DUEL, false);
        top(getString(R.string.app_name), this::showSocial, null);
        heading(getString(SocialDuelPanel.isMirror(duel) ? R.string.social_mirror_title
                : R.string.social_navigation_duel), "");
        content.addView(new SocialDuelPanel(ui, socialClient, duel, bundledLexicon, frenchClassicLexicon,
                this::showSocial).view());
    }

    private String betaInviteBase() {
        String origin = SocialClient.origin(socialBaseUrl());
        if (origin == null) return null;
        return origin.equals(preferences.getString("social_beta_origin", ""))
                || origin.equals(SocialClient.origin(BuildConfig.SOCIAL_BASE_URL))
                ? origin + "/beta#" + gameLanguage.text("", "en/") : null;
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
        page(Screen.HISTORY, !personalSession);
        top(getString(R.string.app_name), personalSession ? this::showGames : null, null);
        heading(getString(R.string.app_history), personalSession ? getString(R.string.personal_lexicon_game_context) : "");
        List<JSONObject> games = store == null ? List.of() : store.history();
        LinearLayout summary = ui.card();
        int wins = 0, solo = 0, best = Integer.MIN_VALUE, bingos = 0;
        long points = 0;
        for (JSONObject row : games) {
            JSONArray scores = row.optJSONArray("scores");
            if (!row.optString("mode").equals("solo")) continue;
            if (scores != null && scores.length() > 0) best = Math.max(best, scores.optInt(0));
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
        summary.addView(ui.title(getResources().getQuantityString(R.plurals.app_history_game_count, games.size(), games.size()), 25));
        ui.gap(summary, 8);
        String rating = personalSession ? "" : getString(R.string.app_history_elo, currentRating());
        if (best != Integer.MIN_VALUE) rating += (rating.isEmpty() ? "" : "  ·  ")
                + getString(R.string.app_history_best, points(best));
        if (!rating.isEmpty()) summary.addView(ui.body(rating));
        if (solo > 0) {
            ui.gap(summary, 4);
            summary.addView(ui.body(getString(R.string.app_history_solo, wins, solo, points((int) Math.round(points / (double) solo)), bingos)));
        }
        content.addView(summary);
        ui.gap(content, 12);
        if (!personalSession) {
            content.addView(EloChart.card(ui, games));
            ui.gap(content, 12);
        }
        if (games.isEmpty()) {
            content.addView(ui.body(getString(R.string.app_history_empty)));
            return;
        }
        content.addView(ui.button(getString(R.string.app_history_export), false,
                () -> export("lettrine-historique.json", new JSONArray(games).toString())));
        ui.gap(content, 15);
        for (int i = games.size() - 1; i >= Math.max(0, games.size() - historyLimit); i--) {
            JSONObject row = games.get(i);
            String title = scoreText(row);
            String subtitle = formatMode(row.optString("mode")) + (row.optBoolean("assisted") ? getString(R.string.app_history_assisted) : "")
                    + "  ·  " + moveCount(row.optInt("turns")) + "  ·  "
                    + DateFormat.getDateInstance(DateFormat.SHORT, AppLocale.locale(this)).format(new Date(row.optLong("finished_at_ms")));
            content.addView(ui.option("↗", title, subtitle, () -> {
                detail = row;
                replayIndex = row.optInt("turns");
                showDetail();
            }));
            ui.gap(content, 7);
        }
        if (historyLimit < games.size()) content.addView(ui.button(getString(R.string.app_show_more), false, () -> {
            historyLimit += 20;
            showHistory();
        }));
    }

    private String formatMode(String value) {
        return switch (value) {
            case "local" -> getString(R.string.app_local);
            case "bluetooth" -> "Bluetooth";
            default -> getString(R.string.app_solo_short);
        };
    }

    private String scoreText(JSONObject row) {
        JSONArray names = row.optJSONArray("names"), scores = row.optJSONArray("scores");
        if (names == null || scores == null) return getString(R.string.app_game_finished);
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
        showReview(finished.history(), mode, resultTitle(finished), matchLexicon(), this::showGame, finishedReview);
    }

    private void showMatchResult(MatchCodec.State finished) {
        page(Screen.RESULT, false);
        top(getString(R.string.app_game_finished), this::leaveGame, null);
        List<String> scores = new ArrayList<>();
        for (int i = 0; i < finished.names().length; i++) {
            scores.add(finished.names()[i] + " " + finished.scores()[i]);
        }
        ui.gap(content, 6);
        content.addView(ui.title(resultTitle(finished), 28));
        ui.gap(content, 5);
        content.addView(ui.body(String.join("  ·  ", scores)));
        ui.gap(content, 12);

        if (mode.equals("bluetooth") && bluetoothHost && !bluetoothReady) {
            content.addView(ui.button(getString(R.string.app_bluetooth_resume_host), false, this::showBluetooth));
            ui.gap(content, 8);
        }

        content.addView(ui.button(getString(R.string.match_result_new_game), true, () -> {
            if (mode.equals("bluetooth")) {
                resetMatch();
                showBluetooth();
            } else {
                setupBack = gameBack;
                newMatch(mode, finished.names(), finished.levels());
            }
        }));
        ui.gap(content, 10);
        LinearLayout featured = ui.column();
        content.addView(featured);
        content.addView(ui.button(getString(R.string.match_review), false, this::reviewFinishedGame));
        content.addView(ui.textButton(getString(R.string.app_view_history), this::showHistory));

        Lexicon dictionary = matchLexicon();
        if (mode.equals("bluetooth") || dictionary == null) {
            showResultBoard(featured, finished);
        } else if (finishedReview != null) {
            showFeaturedMove(featured, finished, finishedReview.highlight(), dictionary);
        } else {
            TextView status = ui.body(getString(R.string.match_review_loading));
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            featured.addView(status);
            ui.gap(featured, 12);
            analyzeReview(finished.history(), mode.equals("solo") ? 0 : -1, dictionary, review -> {
                finishedReview = review;
                showFeaturedMove(featured, finished, review.highlight(), dictionary);
            }, () -> showFeaturedMove(featured, finished, null, dictionary));
        }
    }

    private void showFeaturedMove(LinearLayout panel, MatchCodec.State finished, MatchReview.Highlight highlight,
                                  Lexicon dictionary) {
        panel.removeAllViews();
        if (highlight == null) {
            showResultBoard(panel, finished);
            return;
        }
        MatchReview.Turn turn = highlight.turn();
        LinearLayout lesson = ui.card();
        TextView title = ui.title(getString(R.string.match_result_highlight, turn.index() + 1), 20);
        lesson.addView(title);
        ui.gap(lesson, 4);
        lesson.addView(ui.body(highlight.explanation()));
        ui.gap(lesson, 8);
        lesson.addView(ui.button(getString(R.string.match_result_replay), false,
                () -> practiceReviewedTurn(turn, dictionary)));
        ui.gap(lesson, 8);
        BoardView board = new BoardView(ui, null);
        char[] letters = turn.board().clone();
        boolean[] blanks = turn.blanks().clone();
        MatchReview.Alternative alternative = turn.alternative();
        for (FrenchGame.Tile tile : alternative.tiles()) {
            letters[tile.position()] = tile.letter();
            blanks[tile.position()] = tile.blank();
        }
        board.update(letters, blanks, List.of(), alternative.tiles(), -1);
        board.highlightSquares(highlight.cells());
        var preview = alternative.preview();
        board.setContentDescription(getResources().getQuantityString(R.plurals.match_review_board,
                preview.score(), getString(R.string.match_review_alternative), preview.word(), preview.score()));
        lesson.addView(board, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(lesson);
        ui.gap(panel, 10);
        title.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    private void showResultBoard(LinearLayout panel, MatchCodec.State finished) {
        BoardView board = new BoardView(ui, null);
        board.update(finished.board(), finished.blanks(), List.of(), List.of(), -1);
        panel.addView(board, new LinearLayout.LayoutParams(-1, -2));
        ui.gap(panel, 10);
    }

    private void showReview(List<FrenchGame.Move> moves, String gameMode, String summary,
                            Lexicon dictionary, Runnable back, MatchReview.Review cached) {
        reviewBack = back;
        page(Screen.REVIEW, false);
        top(getString(R.string.match_review), back, null);
        heading(getString(R.string.match_review_heading), summary);

        TextView status = ui.body(getString(R.string.match_review_loading));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status);
        boolean bluetooth = gameMode.equals("bluetooth");
        boolean compatible = dictionary != null;
        int seat = gameMode.equals("solo") ? 0 : -1;
        Consumer<MatchReview.Review> display = review -> {
            if (review.turns().isEmpty()) {
                status.setText(R.string.match_review_empty);
                return;
            }
            if (bluetooth || !compatible) {
                status.setText(bluetooth ? R.string.match_review_bluetooth : R.string.match_review_lexicon);
                ui.gap(content, 10);
            } else {
                content.removeView(status);
            }
            content.addView(new MatchReviewPanel(ui, review.turns(), compatible && !bluetooth,
                    this::explainReview, turn -> practiceReviewedTurn(turn, dictionary)).view());
        };
        if (cached != null) display.accept(cached);
        else analyzeReview(moves, seat, bluetooth ? null : dictionary, display,
                () -> status.setText(R.string.match_review_error));
    }

    private void analyzeReview(List<FrenchGame.Move> moves, int seat, Lexicon dictionary,
                               Consumer<MatchReview.Review> display, Runnable showError) {
        FrameLayout reviewFrame = frame;
        List<FrenchGame.Move> history = List.copyOf(moves);
        Lexicon reviewDictionary = dictionary == null ? lexicon : dictionary;
        reviewTask = reviewWorker.submit(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                MatchReview.Review review = MatchReview.review(history, reviewDictionary, seat, dictionary != null);
                main.post(() -> {
                    if (frame != reviewFrame || isDestroyed()) return;
                    reviewTask = null;
                    display.accept(review);
                });
            } catch (RuntimeException exception) {
                if (Thread.currentThread().isInterrupted()) return;
                main.post(() -> {
                    if (frame != reviewFrame || isDestroyed()) return;
                    reviewTask = null;
                    showError.run();
                });
            }
        });
    }

    private void showDetail() {
        if (detail == null) { showHistory(); return; }
        page(Screen.DETAIL, false);
        top(getString(R.string.app_replay_game), this::showHistory, null);
        heading(scoreText(detail), formatMode(detail.optString("mode")) + "  ·  " + detail.optString("lexicon"));
        List<FrenchGame.Move> moves;
        try {
            moves = MatchCodec.readMoves(detail.getJSONArray("moves"));
        } catch (JSONException | IllegalArgumentException exception) {
            content.addView(ui.body(getString(R.string.app_replay_unavailable)));
            return;
        }
        JSONObject replayed = detail;
        if (!moves.isEmpty()) {
            content.addView(ui.button(getString(R.string.match_review), true, () -> {
                if (!ready()) return;
                showReview(moves, replayed.optString("mode"), scoreText(replayed),
                        archivedLexicon(replayed), this::showDetail, null);
            }));
            ui.gap(content, 9);
        }
        BoardView board = new BoardView(ui, null);
        content.addView(board, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout controls = ui.row();
        TextView label = ui.body("");
        TextView previous = ui.textButton("‹", () -> {});
        previous.setContentDescription(getString(R.string.app_replay_previous));
        TextView next = ui.textButton("›", () -> {});
        next.setContentDescription(getString(R.string.app_replay_next));
        controls.addView(previous);
        ui.weight(controls, label);
        controls.addView(next);
        TextView zoom = ui.textButton("+", () -> board.zoomBy(1.25f));
        zoom.setContentDescription(getString(R.string.practice_zoom));
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
            String word = shown == null ? getString(R.string.app_replay_start) : shown.type().equals("play")
                    ? shown.word() + " +" + shown.score() : shown.type().equals("pass") ? getString(R.string.match_review_pass) : getString(R.string.match_review_exchange);
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
        content.addView(ui.button(getString(R.string.app_game_export), false,
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
        if (request == PERSONAL_LEXICON_IMPORT) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                Uri source = data.getData();
                updatePersonalLexicon(() -> personalLexicon.importFrom(source), false);
            }
            return;
        }
        if (request == BACKUP_IMPORT || request == BACKUP_EXPORT) {
            if (result != RESULT_OK || data == null || data.getData() == null) return;
            Uri file = data.getData();
            if (request == BACKUP_EXPORT) transferBackup(file, true);
            else dialog(getString(R.string.backup_import), getString(R.string.backup_replace))
                    .setNegativeButton(R.string.social_cancel, null)
                    .setPositiveButton(R.string.backup_import, (ignored, action) -> transferBackup(file, false)).show();
            return;
        }
        if (request == PROFILE_PHOTO) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                if (profilePanel == null) showProfile();
                profilePanel.photoSelected(data.getData());
            }
            return;
        }
        if ((request == ENABLE_BLUETOOTH || request == APP_SETTINGS || request == BLUETOOTH_SETTINGS)
                && screen == Screen.BLUETOOTH) showBluetooth();
        if (request != EXPORT_FILE) return;
        String body = export;
        export = null;
        if (result != RESULT_OK || data == null || data.getData() == null || body == null) return;
        try (OutputStream destination = getContentResolver().openOutputStream(data.getData())) {
            if (destination == null) throw new java.io.IOException(getString(R.string.app_export_unavailable));
            destination.write(body.getBytes(StandardCharsets.UTF_8));
            toast(getString(R.string.app_export_saved));
        } catch (java.io.IOException exception) {
            toast(getString(R.string.app_export_error));
        }
    }
}
