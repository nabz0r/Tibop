package fr.lettrine.game;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BLUETOOTH_PERMISSION = 41;
    private static final int ENABLE_BLUETOOTH = 42;
    private static final int EXPORT_FILE = 43;
    private static final int DEFAULT_RATING = GameStore.BASE_ELO;

    private enum Screen { HOME, SOLO, LOCAL, BLUETOOTH, SETTINGS, HISTORY, DETAIL, GAME }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<FrenchGame.Tile> staged = new ArrayList<>();
    private final List<Integer> rackOrder = new ArrayList<>();
    private SharedPreferences preferences;
    private ViewKit ui;
    private GameStore store;
    private Lexicon lexicon;
    private FrenchGame game;
    private MatchCodec.State guestState;
    private Screen screen = Screen.HOME;
    private String mode = "";
    private String lexiconError;
    private int skin;
    private int selected = -1;
    private int lastRackTurn = -1;
    private int startRating;
    private int botLevelSum;
    private int botLevelCount;
    private int historyLimit = 20;
    private int soloSelection = 2;
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
    private MediaPlayer music;
    private String export;
    private String notice;
    private boolean began;
    private JSONObject detail;
    private int replayIndex;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("preferences", MODE_PRIVATE);
        skin = Math.max(0, Math.min(Palette.THEMES.length - 1, preferences.getInt("skin", 0)));
        ui = new ViewKit(this, Palette.THEMES[skin]);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        try {
            store = new GameStore(this);
            JSONObject saved = store.current();
            if (saved != null) {
                game = MatchCodec.restore(saved);
                mode = saved.getString("mode");
                startRating = saved.optInt("start_rating", DEFAULT_RATING);
                botLevelSum = saved.optInt("level_sum");
                botLevelCount = saved.optInt("level_count");
                assisted = saved.optBoolean("assisted");
                privacy = mode.equals("local") && game.end == null;
            }
        } catch (Exception exception) {
            toast("Une sauvegarde locale n’a pas pu être lue.");
        }
        showHome();
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
                    if (screen == Screen.HOME) showHome();
                });
            } catch (Exception exception) {
                main.post(() -> {
                    if (isDestroyed()) return;
                    lexiconError = "Le lexique intégré n’a pas pu être chargé.";
                    if (screen == Screen.HOME) showHome();
                });
            }
        });
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        frame.requestLayout();
    }

    @Override protected void onStart() {
        super.onStart();
        began = true;
        updateMusic();
    }

    @Override protected void onStop() {
        began = false;
        if (music != null) music.pause();
        super.onStop();
    }

    @Override protected void onDestroy() {
        closeLink();
        if (music != null) music.release();
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        switch (screen) {
            case HOME -> super.onBackPressed();
            case DETAIL -> showHistory();
            case GAME -> showHome();
            case BLUETOOTH -> {
                if (link != null && state() == null) closeLink();
                showHome();
            }
            default -> showHome();
        }
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    private void page(Screen next, boolean navigation) {
        screen = next;
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
        content.setPadding(ui.dp(17), ui.dp(7), ui.dp(17), ui.dp(16));
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
            ui.weight(nav, navItem("Historique", next == Screen.HISTORY, this::showHistory));
            ui.weight(nav, navItem("Réglages", next == Screen.SETTINGS, this::showSettings));
            shell.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        }
        setContentView(frame);
        frame.requestApplyInsets();
    }

    private TextView navItem(String name, boolean active, Runnable action) {
        TextView tab = ui.textButton(name, action);
        tab.setMinHeight(ui.dp(49));
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
        page(Screen.HOME, true);
        TextView rating = ui.text("Elo " + currentRating(), 12, ui.palette.accent(), ui.medium);
        ui.inset(rating, 11, 7);
        rating.setBackground(ui.shape(ui.palette.ordinary(), 14));
        top("Lettrine", null, rating);
        ui.gap(content, 8);
        content.addView(ui.hero(), new LinearLayout.LayoutParams(-1, ui.dp(137)));
        MatchCodec.State ongoing = state();
        if (ongoing != null && ongoing.end() == null) {
            ui.gap(content, 13);
            content.addView(ui.button("Reprendre la partie", true, this::showGame));
        }
        ui.gap(content, 19);
        content.addView(ui.micro("NOUVELLE PARTIE", ui.palette.accent()));
        ui.gap(content, 8);
        content.addView(ui.option("✦", "Contre un bot", "Quatre niveaux et un bot adaptatif", () -> {
            if (ready()) showSolo(soloSelection);
        }));
        ui.gap(content, 8);
        content.addView(ui.option("2–4", "Entre amis", "À tour de rôle sur cet appareil", () -> {
            if (ready()) showLocal(2, null);
        }));
        ui.gap(content, 8);
        content.addView(ui.option("↔", "Bluetooth", "Deux appareils déjà associés", () -> {
            if (ready()) showBluetooth();
        }));
        if (lexicon == null) {
            ui.gap(content, 11);
            content.addView(ui.body(lexiconError == null ? "Chargement du lexique…" : lexiconError));
        }
    }

    private int currentRating() { return store == null ? DEFAULT_RATING : store.elo(); }

    private boolean ready() {
        if (lexicon != null) return true;
        toast(lexiconError == null ? "Le lexique se charge…" : lexiconError);
        return false;
    }

    private void showSolo(int selection) {
        soloSelection = selection;
        page(Screen.SOLO, false);
        top("Partie solo", this::showHome, null);
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
        content.addView(ui.button("Commencer", true, () -> replaceMatch(() ->
                newMatch("solo", new String[]{"Vous", Bot.NAMES[selection]}, new int[]{0, selection}))));
    }

    private void showLocal(int count, String[] previous) {
        page(Screen.LOCAL, false);
        top("Partie locale", this::showHome, null);
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
        content.addView(ui.button("Commencer", true, () -> replaceMatch(() -> {
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

    private void replaceMatch(Runnable next) {
        MatchCodec.State ongoing = state();
        if ((ongoing == null || ongoing.end() != null) && link == null) {
            next.run();
            return;
        }
        dialog("Remplacer la partie ?", "La partie en cours sera abandonnée.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Remplacer", (view, which) -> {
                    clearMatch();
                    next.run();
                }).show();
    }

    private void newMatch(String newMode, String[] names, int[] levels) {
        if (!ready()) return;
        mode = newMode;
        game = new FrenchGame(names, levels);
        guestState = null;
        startRating = currentRating();
        botLevelSum = botLevelCount = 0;
        lastRackTurn = -1;
        privacy = mode.equals("local");
        waiting = false;
        botThinking = analysing = assisted = false;
        clearStaged();
        persist();
        showGame();
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

    private void clearMatch() {
        closeLink();
        game = null;
        guestState = null;
        privacy = botThinking = analysing = assisted = waiting = false;
        lastRackTurn = -1;
        clearStaged();
        if (store != null) store.clearCurrent();
    }

    private void persist() {
        if (store == null || game == null || mode.equals("bluetooth")) return;
        try {
            store.current(MatchCodec.saved(game, mode, startRating, botLevelSum, botLevelCount, assisted));
        } catch (Exception exception) {
            toast("Impossible d’enregistrer la partie en cours.");
        }
    }

    private void archiveIfFinished() {
        MatchCodec.State finished = state();
        if (finished == null || finished.end() == null || store == null || lexicon == null) return;
        int before = 0;
        int after = 0;
        int opponent = 0;
        if (mode.equals("solo") && !assisted) {
            before = startRating;
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
            if (!mode.equals("bluetooth")) store.clearCurrent();
        } catch (Exception exception) {
            toast("L’historique n’a pas pu être enregistré.");
        }
    }

    private AlertDialog.Builder dialog(String title, String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(title);
        if (message != null) builder.setMessage(message);
        return builder;
    }

    private void updateMusic() {
        if (!began || !preferences.getBoolean("music", false)) {
            if (music != null) music.pause();
            return;
        }
        if (music == null) {
            music = MediaPlayer.create(this, R.raw.ambient);
            if (music != null) {
                music.setLooping(true);
                music.setVolume(0.52f, 0.52f);
            }
        }
        if (music != null) music.start();
    }

    private final class Arena {
        final LinearLayout scores = ui.row();
        final LinearLayout rack = ui.row();
        final TextView status = ui.body("");
        final TextView preview = ui.text("", 14, ui.palette.ink(), ui.medium);
        final TextView confirm = ui.button("Jouer", true, MainActivity.this::commit);
        final TextView recall = ui.textButton("Retirer", () -> {
            clearStaged();
            updateArena(-1);
        });
        final TextView tools = ui.textButton("Options", MainActivity.this::turnOptions);
        final BoardView board = new BoardView(MainActivity.this, ui.palette, new BoardView.Interaction() {
            @Override public void tap(int position) { boardTap(position); }
            @Override public void drop(int position, int rackIndex) { place(position, rackIndex); }
        });
    }

    private void showGame() {
        if (state() == null) { showHome(); return; }
        page(Screen.GAME, false);
        top(mode.equals("local") ? "Entre amis" : mode.equals("bluetooth") ? "Bluetooth" : "Partie solo",
                this::showHome, null);
        arena = new Arena();
        content.addView(arena.scores);
        ui.gap(content, 8);
        content.addView(arena.board, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout controls = ui.row();
        TextView minus = ui.textButton("−", () -> arena.board.zoomBy(0.8f));
        minus.setContentDescription("Réduire le zoom");
        TextView plus = ui.textButton("+", () -> arena.board.zoomBy(1.25f));
        plus.setContentDescription("Agrandir le plateau");
        TextView reset = ui.textButton("1×", () -> arena.board.resetZoom());
        reset.setContentDescription("Réinitialiser le zoom");
        ui.weight(controls, arena.status);
        controls.addView(minus);
        controls.addView(reset);
        controls.addView(plus);
        content.addView(controls);
        content.addView(arena.rack);
        ui.gap(content, 11);
        content.addView(arena.preview, new LinearLayout.LayoutParams(-1, -2));
        ui.gap(content, 10);
        LinearLayout actions = ui.row();
        ui.weight(actions, arena.confirm);
        ui.space(actions, 7);
        actions.addView(arena.recall);
        actions.addView(arena.tools);
        content.addView(actions);
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
        if (current.end() != null) {
            ui.weight(arena.rack, ui.button("Voir l’historique", true, this::showHistory));
            arena.preview.setText(resultTitle(current));
        } else if (privacy) {
            ui.weight(arena.rack, ui.button("Afficher mon chevalet", true, () -> {
                privacy = false;
                updateArena(-1);
            }));
            arena.preview.setText("Passez l’appareil à " + current.names()[current.active()] + ".");
        } else if (mode.equals("bluetooth") && !bluetoothReady) {
            arena.preview.setText("La connexion Bluetooth est terminée.");
        } else if (!myTurn(current)) {
            arena.preview.setText(waiting ? "Validation par l’hôte…"
                    : current.names()[current.active()] + (botThinking ? " réfléchit…" : " joue."));
        } else {
            renderRack(current);
            arena.preview.setText(analysing ? "Analyse en cours…" : staged.isEmpty()
                    ? "Touchez une lettre, puis une case."
                    : result.valid() ? result.word() + "  ·  " + result.score() + " points" : result.error());
        }
        ui.enabled(arena.confirm, canPlay && result.valid());
        ui.enabled(arena.recall, canPlay && !staged.isEmpty());
        ui.enabled(arena.tools, canPlay);
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
            token.setBackground(ui.touch(index == selected ? ui.palette.fresh() : ui.palette.tile(),
                    9, index == selected ? ui.palette.accent() : ui.palette.tileEdge()));
            TextView glyph = ui.text(occupied[index] ? " " : String.valueOf(letter),
                    22, ui.palette.ink(), ui.medium);
            glyph.setGravity(Gravity.CENTER);
            token.addView(glyph);
            if (!occupied[index]) {
                TextView value = ui.text(Integer.toString(FrenchGame.value(letter)), 10, ui.palette.muted(), ui.medium);
                value.setGravity(Gravity.CENTER);
                token.addView(value);
                token.setContentDescription(letter == '?' ? "Joker" : "Lettre " + letter);
                token.setOnClickListener(view -> {
                    if (analysing) return;
                    selected = selected == index ? -1 : index;
                    updateArena(-1);
                });
                token.setOnLongClickListener(view -> !analysing && view.startDragAndDrop(
                        ClipData.newPlainText("Lettrine", String.valueOf(letter)),
                        new View.DragShadowBuilder(view), index, 0));
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
        for (int i = 0; i < staged.size(); i++) {
            FrenchGame.Tile tile = staged.get(i);
            if (tile.position() == position) {
                staged.remove(i);
                selected = tile.rackIndex();
                updateArena(-1);
                return;
            }
        }
        if (selected >= 0) place(position, selected);
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
        else afterMove();
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
        persist();
        archiveIfFinished();
        if (mode.equals("bluetooth") && bluetoothHost && link != null) {
            link.send(MatchCodec.visible(game, 1));
        }
        List<FrenchGame.Tile> last = game.history.get(game.history.size() - 1).tiles();
        updateArena(last.isEmpty() ? -1 : last.get(0).position());
        startBotIfNeeded();
    }

    private void startBotIfNeeded() {
        if (game == null || !mode.equals("solo") || game.end != null || game.active != 1
                || botThinking || lexicon == null) return;
        botThinking = true;
        updateArena(-1);
        FrenchGame snapshot = game;
        int turn = snapshot.history.size();
        worker.submit(() -> {
            Bot.Choice result;
            try {
                result = Bot.choose(snapshot, lexicon, snapshot.levels[1], startRating);
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
                if (latest == null || !latest.id().equals(current.id()) || latest.history().size() != current.history().size()) return;
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
            content.addView(ui.button("Autoriser les appareils à proximité", true,
                    () -> requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, BLUETOOTH_PERMISSION)));
            return;
        }
        if (!adapter.isEnabled()) {
            content.addView(ui.button("Activer le Bluetooth", true,
                    () -> startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BLUETOOTH)));
            return;
        }
        EditText name = input("Votre prénom", bluetoothName);
        content.addView(name, new LinearLayout.LayoutParams(-1, ui.dp(48)));
        ui.gap(content, 11);
        content.addView(ui.button("Héberger", true, () -> {
            String entered = name.getText().toString().trim();
            replaceMatch(() -> beginLink(null, entered));
        }));
        ui.gap(content, 15);
        content.addView(ui.micro("APPAREILS ASSOCIÉS", ui.palette.accent()));
        ui.gap(content, 8);
        List<BluetoothDevice> devices = new ArrayList<>(adapter.getBondedDevices());
        devices.sort((left, right) -> deviceName(left).compareToIgnoreCase(deviceName(right)));
        if (devices.isEmpty()) content.addView(ui.body("Aucun appareil associé."));
        for (BluetoothDevice device : devices) {
            content.addView(ui.option("↔", deviceName(device), "Rejoindre une partie hébergée", () -> {
                String entered = name.getText().toString().trim();
                replaceMatch(() -> beginLink(device, entered));
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
        String name = device.getName();
        return name == null || name.isEmpty() ? "Appareil Bluetooth" : name;
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == BLUETOOTH_PERMISSION && screen == Screen.BLUETOOTH) showBluetooth();
    }

    private void beginLink(BluetoothDevice peer, String name) {
        if (!ready() || !bluetoothPermission()) return;
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled()) { toast("Activez d’abord le Bluetooth."); return; }
        clearMatch();
        mode = "bluetooth";
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
        page(Screen.SETTINGS, true);
        top("Lettrine", null, null);
        heading("Réglages", "");
        LinearLayout settings = ui.card();
        Switch audio = new Switch(this);
        audio.setText("Ambiance sonore");
        audio.setTextColor(ui.palette.ink());
        audio.setTextSize(15);
        audio.setMinimumHeight(ui.dp(43));
        audio.setChecked(preferences.getBoolean("music", false));
        audio.setOnCheckedChangeListener((view, checked) -> {
            preferences.edit().putBoolean("music", checked).apply();
            updateMusic();
        });
        settings.addView(audio);
        content.addView(settings);
        ui.gap(content, 18);
        content.addView(ui.micro("APPARENCE", ui.palette.accent()));
        ui.gap(content, 8);
        for (int i = 0; i < Palette.THEMES.length; i++) {
            int theme = i;
            content.addView(ui.option(skin == i ? "✓" : "✦", Palette.THEMES[i].name(), "", () -> {
                skin = theme;
                preferences.edit().putInt("skin", skin).apply();
                ui = new ViewKit(this, Palette.THEMES[skin]);
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
        BoardView board = new BoardView(this, ui.palette, null);
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
            label.setText(replayIndex + "/" + moves.size() + "  ·  " + word);
            ui.enabled(previous, replayIndex > 0);
            ui.enabled(next, replayIndex < moves.size());
        };
        previous.setOnClickListener(view -> { if (replayIndex > 0) { replayIndex--; replay.run(); } });
        next.setOnClickListener(view -> { if (replayIndex < moves.size()) { replayIndex++; replay.run(); } });
        replayIndex = Math.max(0, Math.min(moves.size(), replayIndex));
        replay.run();
        ui.gap(content, 9);
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
        if (request == ENABLE_BLUETOOTH && screen == Screen.BLUETOOTH) showBluetooth();
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
