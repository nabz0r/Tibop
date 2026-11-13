package fr.lettrine.game;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class DailyResult {
    private static final Locale FRENCH = Locale.FRANCE;
    private static final DateTimeFormatter CARD_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", FRENCH);
    private static final DateTimeFormatter SHARE_DATE = DateTimeFormatter.ofPattern("d MMMM uuuu", FRENCH);

    private final ViewKit ui;
    private final DailyProgress progress;
    private final LocalDate date;
    private final String opening;
    private final LinearLayout summary;
    private final TextView score;
    private final TextView word;
    private final TextView best;
    private final TextView streak;
    private final TextView share;
    private DailyProgress.Snapshot saved;

    DailyResult(ViewKit ui, DailyProgress progress, DailyChallenge challenge) {
        this.ui = ui;
        this.progress = progress;
        date = challenge.date();
        opening = label(R.string.daily_board, challenge.openingWord(),
                square(challenge.openingStart()), square(challenge.openingEnd()));

        summary = ui.column();
        summary.setBackground(ui.shape(ui.palette.ordinary(), 15));
        ui.inset(summary, 13, 10);
        score = ui.title("", 38);
        word = ui.text("", 18, ui.palette.ink(), ui.medium);
        best = ui.text("", 13, ui.palette.ink(), ui.medium);
        streak = ui.text("", 13, ui.palette.accent(), ui.medium);
        LinearLayout stats = ui.row();
        LinearLayout points = ui.column();
        points.addView(score);
        TextView pointsLabel = ui.micro(label(R.string.daily_points_label), ui.palette.accent());
        pointsLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        points.addView(pointsLabel);
        stats.addView(points);
        ui.space(stats, 16);
        LinearLayout details = ui.column();
        details.addView(word);
        ui.gap(details, 4);
        details.addView(best);
        ui.gap(details, 4);
        details.addView(streak);
        ui.weight(stats, details);
        summary.addView(stats);
        share = ui.button(label(R.string.daily_share), true, this::share);
        share.setMinHeight(ui.dp(48));
    }

    View summary() { return summary; }

    View shareButton() { return share; }

    String boardDescription() { return opening; }

    void show(FrenchGame.Preview move) {
        boolean played = move != null;
        saved = null;
        summary.setVisibility(played ? View.VISIBLE : View.GONE);
        share.setVisibility(View.GONE);
        if (!played) return;

        score.setText(String.valueOf(move.score()));
        score.setContentDescription(points(ui, move.score()));
        word.setText(move.word());
        DailyProgress.Snapshot snapshot = progress.snapshot(date);
        try {
            saved = progress.complete(date, move.score());
            snapshot = saved;
            share.setVisibility(View.VISIBLE);
        } catch (IOException exception) {
            toast(R.string.daily_save_error);
        }
        best.setVisibility(snapshot.bestScore() == null ? View.GONE : View.VISIBLE);
        if (snapshot.bestScore() != null) best.setText(bestLabel(ui, snapshot.bestScore()));
        streak.setText(streakLabel(ui, snapshot.streak()));
    }

    private void share() {
        if (saved == null) return;
        String message = label(R.string.daily_share_text, SHARE_DATE.format(date),
                points(ui, saved.bestScore()), streakLabel(ui, saved.streak()));
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message);
        try {
            ui.context.startActivity(Intent.createChooser(send, label(R.string.daily_share)));
        } catch (ActivityNotFoundException exception) {
            toast(R.string.daily_share_error);
        }
    }

    static String dateLabel(LocalDate date) {
        String label = CARD_DATE.format(date);
        return label.substring(0, 1).toUpperCase(FRENCH) + label.substring(1);
    }

    static String bestLabel(ViewKit ui, int score) {
        return ui.context.getString(R.string.daily_best, points(ui, score));
    }

    static String streakLabel(ViewKit ui, int days) {
        String count = ui.context.getResources().getQuantityString(R.plurals.daily_days, days, days);
        return ui.context.getString(R.string.daily_streak, count);
    }

    private static String points(ViewKit ui, int value) {
        return ui.context.getResources().getQuantityString(R.plurals.practice_points, value, value);
    }

    private static String square(int position) {
        return Character.toString((char) ('A' + position / FrenchGame.SIZE)) + (position % FrenchGame.SIZE + 1);
    }

    private void toast(int resource) { Toast.makeText(ui.context, resource, Toast.LENGTH_SHORT).show(); }

    private String label(int resource, Object... values) { return ui.context.getString(resource, values); }
}
