package fr.lettrine.game;

import android.app.AlertDialog;
import android.graphics.Rect;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;
import java.util.function.Consumer;

final class TournamentRoundPanel {
    private final ViewKit ui;
    private final SocialClient client;
    private final CardStyleStore cardStyles;
    private final Runnable back;
    private final Consumer<JSONObject> completedEvent;
    private final long eventId;
    private final int number;
    private final PracticePosition position;
    private final LinearLayout root;
    private final TextView notice;
    private PracticePanel practice;

    TournamentRoundPanel(ViewKit ui, SocialClient client, JSONObject round, Lexicon catalog, Lexicon curated, Runnable back,
                         Consumer<JSONObject> completedEvent) {
        this.ui = ui;
        this.client = client;
        cardStyles = new CardStyleStore(ui.context);
        this.back = back;
        this.completedEvent = completedEvent;
        eventId = round.optLong("event", -1);
        number = round.optInt("number");
        root = ui.column();
        notice = ui.body("");
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        notice.setVisibility(View.GONE);
        root.addView(notice);

        JSONObject puzzle = round.optJSONObject("puzzle");
        DailyChallenge challenge = SocialDuelPanel.compatibleChallenge(puzzle, "", catalog);
        position = challenge == null ? null : challenge.position();
        Lexicon rules = SocialDuelPanel.validationLexicon(puzzle, catalog, curated);
        if (eventId < 0 || number < 1 || number > 3 || position == null || rules == null) {
            showNotice(label(R.string.social_duel_incompatible));
            addBackButton();
            return;
        }
        root.addView(DailyChallengeCard.heading(ui, label(R.string.tournament_round, number),
                label(R.string.tournament_title), label(R.string.tournament_round_intro)));
        ui.gap(root, 12);
        practice = new PracticePanel(ui, position, rules, this::confirm);
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
            JSONObject slot = result == null || error != null ? null
                    : TournamentPanel.slot(result.optJSONArray("slots"), number);
            boolean accepted = slot != null && result.optLong("id", -1) == eventId && !slot.isNull("score");
            boolean unlocked = accepted && cardStyles.recordTournament(result);
            if (accepted) completedEvent.accept(result);
            if (!root.isAttachedToWindow()) return;
            if (!accepted) {
                practice.setSubmitting(false);
                showNotice(error == null ? label(R.string.tournament_response_error) : error);
            } else {
                showResult(slot.optInt("score"), result.optInt("total"), result.optInt("completed"), unlocked, tiles);
            }
        });
    }

    private void showNotice(String message) {
        notice.setText(message);
        notice.setVisibility(View.VISIBLE);
        notice.post(() -> notice.requestRectangleOnScreen(new Rect(0, 0, notice.getWidth(), notice.getHeight())));
    }

    private void showResult(int score, int total, int completed, boolean unlocked, List<FrenchGame.Tile> tiles) {
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
        DefinitionAction.add(ui, card,
                DefinitionAction.recordedWords(position.board(), position.blanks(), tiles, ui.language, score));
        root.addView(card);
        if (unlocked) {
            ui.gap(root, 10);
            root.addView(TournamentPanel.rewardCard(ui, cardStyles));
        }
        addBackButton();
        root.post(() -> root.requestRectangleOnScreen(new Rect(0, 0, root.getWidth(), ui.dp(120))));
    }

    private void addBackButton() {
        ui.gap(root, 12);
        root.addView(ui.button(label(R.string.tournament_continue), true, back));
    }

    private String label(int resource, Object... values) { return ui.context.getString(resource, values); }
}
