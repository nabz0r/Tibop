package fr.lettrine.game;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class DailyResult {
    private final ViewKit ui;
    private final DailyProgress progress;
    private final DailyChallenge challenge;
    private final Lexicon lexicon;
    private final ChallengeLink invitation;
    private final Runnable onComplete;
    private final LocalDate date;
    private final String opening;
    private final LinearLayout summary;
    private final TextView score;
    private final TextView word;
    private final TextView best;
    private final TextView streak;
    private final TextView comparison;
    private final LinearLayout sharing;
    private ChallengeLink outgoing;
    private int shareStreak;

    DailyResult(ViewKit ui, DailyProgress progress, DailyChallenge challenge, Lexicon lexicon,
                ChallengeLink invitation, Runnable onComplete) {
        this.ui = ui;
        this.progress = progress;
        this.challenge = challenge;
        this.lexicon = lexicon;
        this.invitation = invitation;
        this.onComplete = onComplete;
        date = challenge.date();
        opening = label(R.string.daily_board, challenge.openingWord(),
                square(challenge.openingStart()), square(challenge.openingEnd()));

        LinearLayout resultCopy = ui.column();
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
        resultCopy.addView(stats);
        comparison = ui.body("");
        if (invitation != null) {
            ui.gap(resultCopy, 7);
            resultCopy.addView(comparison);
        }
        summary = ui.illustratedCard(R.drawable.nab_arcade_cheer, 66, resultCopy, ui.palette.fresh());
        sharing = ui.column();
        TextView share = ui.button(label(R.string.daily_share), true, this::share);
        share.setMinHeight(ui.dp(48));
        sharing.addView(share);
        TextView copy = ui.textButton(label(R.string.daily_share_link), this::copy);
        copy.setMinHeight(ui.dp(48));
        sharing.addView(copy);
    }

    View summary() { return summary; }

    View shareButton() { return sharing; }

    String boardDescription() { return opening; }

    void show(FrenchGame.Preview move) {
        boolean played = move != null;
        outgoing = null;
        summary.setVisibility(played ? View.VISIBLE : View.GONE);
        sharing.setVisibility(View.GONE);
        if (!played) return;

        score.setText(String.valueOf(move.score()));
        score.setContentDescription(points(ui, move.score()));
        word.setText(move.word());
        int bestScore = move.score();
        shareStreak = 0;
        best.setVisibility(View.GONE);
        streak.setVisibility(View.GONE);
        if (progress != null) {
            try {
                DailyProgress.Snapshot saved = progress.complete(date, move.score(), lexicon.hash);
                onComplete.run();
                bestScore = saved.bestScore();
                shareStreak = saved.streak();
                best.setText(bestLabel(ui, bestScore));
                best.setVisibility(View.VISIBLE);
                streak.setText(streakLabel(ui, shareStreak));
                streak.setVisibility(View.VISIBLE);
            } catch (IOException exception) {
                toast(R.string.daily_save_error);
            }
        }
        if (invitation != null) {
            int difference = move.score() - invitation.score();
            comparison.setText(difference == 0 ? label(R.string.daily_invitation_tie) : label(
                    difference > 0 ? R.string.daily_invitation_beat : R.string.daily_invitation_short,
                    points(ui, Math.abs(difference))));
        }
        outgoing = ChallengeLink.create(challenge, lexicon, bestScore);
        sharing.setVisibility(View.VISIBLE);
    }

    private void share() {
        if (outgoing == null) return;
        String formattedDate = date.format(DateTimeFormatter.ofPattern(label(R.string.daily_share_date),
                AppLocale.locale(ui.context)));
        String points = points(ui, outgoing.score());
        String title = label(R.string.daily_share);
        String message = label(R.string.daily_share_text, formattedDate,
                points, outgoing.uri());
        try {
            ScoreCard.share(ui, formattedDate, points, shareStreak == 0 ? "" : streakLabel(ui, shareStreak), message, title);
        } catch (ActivityNotFoundException | IOException exception) {
            toast(R.string.daily_share_error);
        }
    }

    private void copy() {
        if (outgoing == null) return;
        ClipboardManager clipboard = ui.context.getSystemService(ClipboardManager.class);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label(R.string.daily_share), outgoing.uri()));
            if (android.os.Build.VERSION.SDK_INT < 33) toast(R.string.daily_share_link_copied);
        }
    }

    static String dateLabel(ViewKit ui, LocalDate date) {
        Locale locale = AppLocale.locale(ui.context);
        String pattern = ui.context.getString(R.string.daily_card_date);
        String label = date.format(DateTimeFormatter.ofPattern(pattern, locale));
        return label.substring(0, 1).toUpperCase(locale) + label.substring(1);
    }

    static String bestLabel(ViewKit ui, int score) {
        return ui.context.getString(R.string.daily_best, points(ui, score));
    }

    static String streakLabel(ViewKit ui, int days) {
        String count = ui.context.getResources().getQuantityString(R.plurals.daily_days, days, days);
        return ui.context.getString(R.string.daily_streak, count);
    }

    static String points(ViewKit ui, int value) {
        return ui.context.getResources().getQuantityString(R.plurals.practice_points, value, value);
    }

    private static String square(int position) {
        return Character.toString((char) ('A' + position / FrenchGame.SIZE)) + (position % FrenchGame.SIZE + 1);
    }

    private void toast(int resource) { Toast.makeText(ui.context, resource, Toast.LENGTH_SHORT).show(); }

    private String label(int resource, Object... values) { return ui.context.getString(resource, values); }
}
