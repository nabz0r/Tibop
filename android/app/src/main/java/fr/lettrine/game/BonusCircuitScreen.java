package fr.lettrine.game;

import android.graphics.Rect;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class BonusCircuitScreen {
    private static final long MINUTE_MILLIS = 60_000;

    private final ViewKit ui;
    private final Lexicon lexicon;
    private final Lexicon legacyLexicon;
    private final BonusEconomy economy;
    private final BooleanSupplier videoAvailable;
    private final Consumer<LocalDate> requestVideo;
    private final Runnable openFriends;
    private final LinearLayout panel;
    private final List<TwoMoveChallenge.BonusSet> circuits;
    private final List<TwoMoveChallenge.BonusSet> legacyCircuits;
    private TwoMoveChallengeScreen play;
    private TextView balance;
    private TextView refill;
    private TextView start;
    private TextView exchange;
    private TextView video;
    private final Runnable refreshBalance = this::refresh;

    BonusCircuitScreen(ViewKit ui, Lexicon lexicon, Lexicon legacyLexicon, BonusEconomy economy,
                       BooleanSupplier videoAvailable, Consumer<LocalDate> requestVideo, Runnable openFriends) {
        this.ui = ui;
        this.lexicon = lexicon;
        this.legacyLexicon = legacyLexicon;
        this.economy = economy;
        this.videoAvailable = videoAvailable;
        this.requestVideo = requestVideo;
        this.openFriends = openFriends;
        panel = ui.column();
        circuits = TwoMoveChallenge.bonusCircuits(lexicon);
        legacyCircuits = lexicon == legacyLexicon ? circuits : TwoMoveChallenge.bonusCircuits(legacyLexicon);
        panel.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { refresh(); }
            @Override public void onViewDetachedFromWindow(View view) { panel.removeCallbacks(refreshBalance); }
        });
        showLobby();
    }

    View view() { return panel; }

    boolean goBack() {
        if (play == null) return false;
        if (!play.goBack()) showLobby();
        return true;
    }

    void refresh() {
        panel.removeCallbacks(refreshBalance);
        if (play != null || balance == null) return;
        long now = System.currentTimeMillis();
        BonusEconomy.Wallet wallet = economy.wallet(now);
        balance.setText(label(R.string.bonus_balance, wallet.coins(), wallet.freeTickets(),
                BonusEconomy.FREE_CAP, wallet.bonusTickets()));
        long wait = wallet.nextRefillAtMillis() - now;
        if (wait > 0) {
            int minutes = (int) Math.min(Integer.MAX_VALUE, (wait + MINUTE_MILLIS - 1) / MINUTE_MILLIS);
            refill.setText(minutes >= 60 ? label(R.string.bonus_refill_hours, minutes / 60, minutes % 60)
                    : label(R.string.bonus_refill_minutes, minutes));
        } else {
            refill.setText(R.string.bonus_refill_full);
        }
        BonusEconomy.Circuit run = economy.active(ui.language);
        boolean resumable = run != null && !run.complete();
        if (resumable) start.setText(R.string.bonus_resume);
        else start.setText(wallet.freeEntry() ? R.string.bonus_start_free : R.string.bonus_start_ticket);
        ui.enabled(start, resumable || wallet.canStart());
        ui.enabled(exchange, wallet.coins() >= BonusEconomy.EXCHANGE_COST);
        boolean supported = videoAvailable.getAsBoolean();
        boolean availableToday = economy.videoAvailable(LocalDate.now());
        video.setText(!supported ? R.string.bonus_video_unavailable
                : availableToday ? R.string.bonus_watch_video : R.string.bonus_video_claimed);
        ui.enabled(video, supported && availableToday);
        if (panel.isAttachedToWindow()) panel.postDelayed(refreshBalance, MINUTE_MILLIS);
    }

    private void showLobby() {
        play = null;
        balance = null;
        panel.removeAllViews();
        LinearLayout intro = ui.card();
        intro.setBackground(ui.shape(ui.palette.deep(), 20));
        intro.addView(ui.text(label(R.string.bonus_home_title), 29, ui.palette.heroInk(), ui.serif));
        ui.gap(intro, 7);
        TextView rules = ui.body(label(R.string.bonus_rules));
        rules.setTextColor(ui.palette.heroInk());
        intro.addView(rules);
        panel.addView(intro);
        ui.gap(panel, 10);
        BonusEconomy.Circuit run = economy.active(ui.language);
        if (run != null && !run.complete() ? puzzlesFor(run).isEmpty() : circuits.isEmpty()) {
            panel.addView(ui.body(label(R.string.bonus_unavailable)));
            scrollToStart();
            return;
        }

        LinearLayout ticketCard = ui.card();
        ticketCard.addView(ui.title(label(R.string.bonus_wallet), 21));
        ui.gap(ticketCard, 8);
        balance = ui.text("", 15, ui.palette.ink(), ui.medium);
        ticketCard.addView(balance);
        ui.gap(ticketCard, 4);
        refill = ui.body("");
        ticketCard.addView(refill);
        panel.addView(ticketCard);
        ui.gap(panel, 12);

        if (run != null) showProgress(run, puzzlesFor(run));
        start = ui.button("", true, this::startOrResume);
        panel.addView(start);
        ui.gap(panel, 8);
        exchange = ui.button(label(R.string.bonus_exchange, BonusEconomy.EXCHANGE_COST), false, () -> {
            if (!economy.exchange()) Toast.makeText(ui.context, R.string.bonus_save_error, Toast.LENGTH_SHORT).show();
            refresh();
        });
        panel.addView(exchange);
        ui.gap(panel, 7);
        video = ui.button("", false, () -> {
            LocalDate date = LocalDate.now();
            if (videoAvailable.getAsBoolean() && economy.videoAvailable(date)) requestVideo.accept(date);
        });
        panel.addView(video);
        ui.gap(panel, 8);
        panel.addView(ui.body(label(R.string.bonus_referral_copy)));
        panel.addView(ui.textButton(label(R.string.bonus_invite), openFriends));
        refresh();
        scrollToStart();
    }

    private void showProgress(BonusEconomy.Circuit run, List<TwoMoveChallenge.Puzzle> puzzles) {
        LinearLayout progress = ui.card();
        progress.addView(ui.title(label(run.complete() ? R.string.bonus_complete : R.string.bonus_in_progress,
                run.complete() ? run.totalScore() : run.round() + 1), 21));
        Integer best = economy.bestScore(ui.language);
        if (best != null) {
            ui.gap(progress, 5);
            progress.addView(ui.body(label(R.string.bonus_record, best)));
        }
        for (int i = 0; i < run.round() && i < puzzles.size(); i++) {
            ui.gap(progress, 5);
            progress.addView(ui.body(label(R.string.bonus_round_score, i + 1, puzzles.get(i).title(),
                    run.scores().get(i))));
        }
        panel.addView(progress);
        ui.gap(panel, 12);
    }

    private void startOrResume() {
        List<String> ids = circuits.stream().map(TwoMoveChallenge.BonusSet::id).toList();
        BonusEconomy.Circuit run = economy.start(ui.language, ids, System.currentTimeMillis(), lexicon.hash);
        if (run == null || puzzlesFor(run).isEmpty()) {
            Toast.makeText(ui.context, R.string.bonus_start_error, Toast.LENGTH_SHORT).show();
            refresh();
            return;
        }
        showRound(run);
    }

    private List<TwoMoveChallenge.Puzzle> puzzlesFor(BonusEconomy.Circuit run) {
        for (var circuit : dictionaryFor(run) == lexicon ? circuits : legacyCircuits) {
            if (circuit.id().equals(run.id())) return circuit.puzzles();
        }
        return List.of();
    }

    private Lexicon dictionaryFor(BonusEconomy.Circuit run) {
        return lexicon.hash.equals(run.lexiconHash()) ? lexicon : legacyLexicon;
    }

    private void showRound(BonusEconomy.Circuit run) {
        if (run == null || run.complete()) {
            showLobby();
            return;
        }
        List<TwoMoveChallenge.Puzzle> puzzles = puzzlesFor(run);
        if (puzzles.isEmpty()) {
            showLobby();
            return;
        }
        int round = run.round();
        String attempt = run.attemptId();
        String id = run.id();
        String draft = economy.draft(ui.language, id, attempt, round);
        panel.removeCallbacks(refreshBalance);
        panel.removeAllViews();
        panel.addView(ui.micro(label(R.string.bonus_round, round + 1), ui.palette.accent()));
        ui.gap(panel, 6);
        var circuit = new TwoMoveChallengeScreen.CircuitPlay(draft,
                score -> economy.completeRound(ui.language, id, attempt, round, score) != null,
                saved -> economy.saveDraft(ui.language, id, attempt, round, saved),
                () -> showRound(economy.active(ui.language)), round == BonusEconomy.ROUND_COUNT - 1);
        play = new TwoMoveChallengeScreen(ui, dictionaryFor(run), puzzles.get(round), circuit);
        panel.addView(play.view());
        scrollToStart();
    }

    private void scrollToStart() {
        panel.post(() -> panel.requestRectangleOnScreen(new Rect(0, 0, panel.getWidth(), ui.dp(48)), true));
    }

    private String label(int resource, Object... values) { return ui.context.getString(resource, values); }
}
