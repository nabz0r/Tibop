package fr.lettrine.game;

import android.app.AlertDialog;
import android.graphics.Rect;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;

final class TournamentRoundPanel {
    private final ViewKit ui;
    private final SocialClient client;
    private final Runnable back;
    private final long eventId;
    private final int number;
    private final LinearLayout root;
    private final TextView notice;
    private PracticePanel practice;

    TournamentRoundPanel(ViewKit ui, SocialClient client, JSONObject round, Lexicon lexicon, Runnable back) {
        this.ui = ui;
        this.client = client;
        this.back = back;
        eventId = round.optLong("event", -1);
        number = round.optInt("number");
        root = ui.column();
        notice = ui.body("");
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        notice.setVisibility(View.GONE);
        root.addView(notice);

        DailyChallenge challenge = SocialDuelPanel.compatibleChallenge(round.optJSONObject("puzzle"), "", lexicon);
        if (eventId < 0 || number < 1 || number > 3 || challenge == null) {
            showNotice(label(R.string.social_duel_incompatible));
            addBackButton();
            return;
        }
        root.addView(DailyChallengeCard.heading(ui, label(R.string.tournament_round, number),
                label(R.string.tournament_title), label(R.string.tournament_round_intro)));
        ui.gap(root, 12);
        practice = new PracticePanel(ui, challenge.position(), lexicon, this::confirm);
        root.addView(practice.view());
    }

    View view() { return root; }

    private void confirm(List<FrenchGame.Tile> tiles) {
        new AlertDialog.Builder(ui.context).setTitle(R.string.tournament_confirm)
                .setMessage(R.string.tournament_confirm_copy)
                .setNegativeButton(R.string.social_cancel, null)
                .setPositiveButton(R.string.social_submit, (dialog, which) -> submit(tiles)).show();
    }

    private void submit(List<FrenchGame.Tile> tiles) {
        notice.setVisibility(View.GONE);
        practice.setSubmitting(true);
        client.playTournament(eventId, number, tiles, (result, error) -> {
            if (!root.isAttachedToWindow()) return;
            JSONObject slot = result == null ? null : TournamentPanel.slot(result.optJSONArray("slots"), number);
            if (slot == null || result.optLong("id", -1) != eventId || slot.isNull("score")) {
                practice.setSubmitting(false);
                showNotice(error == null ? label(R.string.tournament_response_error) : error);
            } else {
                showResult(slot.optInt("score"), result.optInt("total"), result.optInt("completed"));
            }
        });
    }

    private void showNotice(String message) {
        notice.setText(message);
        notice.setVisibility(View.VISIBLE);
        notice.post(() -> notice.requestRectangleOnScreen(new Rect(0, 0, notice.getWidth(), notice.getHeight())));
    }

    private void showResult(int score, int total, int completed) {
        root.removeAllViews();
        LinearLayout card = ui.card();
        card.addView(ui.body(label(R.string.tournament_round, number)));
        ui.gap(card, 7);
        card.addView(ui.title(label(R.string.tournament_result), 30));
        ui.gap(card, 8);
        card.addView(ui.title(TournamentPanel.points(ui, score), 39));
        ui.gap(card, 12);
        card.addView(ui.body(label(R.string.tournament_total, TournamentPanel.points(ui, total))));
        ui.gap(card, 4);
        card.addView(ui.body(TournamentPanel.progress(ui, completed)));
        root.addView(card);
        addBackButton();
        root.post(() -> root.requestRectangleOnScreen(new Rect(0, 0, root.getWidth(), ui.dp(120))));
    }

    private void addBackButton() {
        ui.gap(root, 12);
        root.addView(ui.button(label(R.string.tournament_continue), true, back));
    }

    private String label(int resource, Object... values) { return ui.context.getString(resource, values); }
}
