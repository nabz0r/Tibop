package fr.lettrine.game;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class MatchReviewPanel {
    private final ViewKit ui;
    private final List<MatchReview.Turn> turns;
    private final boolean alternativesAllowed;
    private final List<TextView> choices = new ArrayList<>(3);
    private final LinearLayout panel;
    private final BoardView board;
    private final TextView turnTitle;
    private final TextView playedScore;
    private final TextView alternativeScore;
    private final TextView comparison;
    private final TextView playedButton;
    private final TextView alternativeButton;
    private final TextView explainButton;
    private final TextView practiceButton;
    private int selected;
    private boolean alternativeRevealed;

    MatchReviewPanel(ViewKit ui, List<MatchReview.Turn> turns, boolean alternativesAllowed,
                     Consumer<MatchReview.Turn> explain, Consumer<MatchReview.Turn> practice) {
        this.ui = ui;
        this.turns = turns;
        this.alternativesAllowed = alternativesAllowed;
        panel = ui.column();
        LinearLayout positions = ui.row();
        for (int i = 0; i < turns.size(); i++) {
            int position = i;
            MatchReview.Turn turn = turns.get(i);
            TextView choice = ui.button(label(R.string.match_review_turn, turn.index() + 1), false,
                    () -> select(position));
            int score = turn.played().score();
            choice.setContentDescription(quantity(R.plurals.match_review_choose_turn, score, turn.index() + 1,
                    playedWord(turn.played()), score));
            if (i > 0) ui.space(positions, 6);
            ui.weight(positions, choice);
            choices.add(choice);
        }
        panel.addView(positions);
        ui.gap(panel, 10);

        practiceButton = ui.button(label(R.string.match_review_practice), true, () -> {
            MatchReview.Turn turn = turns.get(selected);
            if (turn.practice() != null) practice.accept(turn);
        });
        LinearLayout.LayoutParams practicePosition = new LinearLayout.LayoutParams(-1, -2);
        practicePosition.bottomMargin = ui.dp(10);
        panel.addView(practiceButton, practicePosition);

        LinearLayout scores = ui.card();
        turnTitle = ui.title("", 22);
        scores.addView(turnTitle);
        ui.gap(scores, 9);
        playedScore = ui.body("");
        scores.addView(playedScore);
        ui.gap(scores, 3);
        alternativeScore = ui.body("");
        scores.addView(alternativeScore);
        ui.gap(scores, 6);
        comparison = ui.text("", 13, ui.palette.accent(), ui.medium);
        comparison.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        scores.addView(comparison);
        panel.addView(scores);
        ui.gap(panel, 10);

        LinearLayout modes = ui.row();
        playedButton = ui.button(label(R.string.match_review_played), false, () -> showPosition(false));
        alternativeButton = ui.button(label(R.string.match_review_alternative), false, () -> showPosition(true));
        ui.weight(modes, playedButton);
        ui.space(modes, 6);
        ui.weight(modes, alternativeButton);
        panel.addView(modes);
        ui.gap(panel, 7);

        board = new BoardView(ui, null);
        panel.addView(board, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = ui.row();
        explainButton = ui.button(label(R.string.match_review_explain), false, () -> {
            MatchReview.Turn turn = turns.get(selected);
            revealAlternative(turn);
            explain.accept(turn);
        });
        ui.weight(actions, explainButton);
        ui.space(actions, 5);
        TextView reset = ui.textButton("1×", board::resetZoom);
        reset.setContentDescription(label(R.string.match_review_reset_zoom));
        actions.addView(reset);
        TextView zoom = ui.textButton("+", () -> board.zoomBy(1.25f));
        zoom.setContentDescription(label(R.string.match_review_zoom));
        actions.addView(zoom);
        ui.gap(panel, 7);
        panel.addView(actions);
        select(0);
    }

    View view() { return panel; }

    private void select(int position) {
        selected = position;
        for (int i = 0; i < choices.size(); i++) highlight(choices.get(i), i == selected);
        MatchReview.Turn turn = turns.get(position);
        FrenchGame.Move played = turn.played();
        MatchReview.Alternative alternative = turn.alternative();
        turnTitle.setText(label(R.string.match_review_turn_title, turn.index() + 1, playedWord(played)));
        playedScore.setText(label(R.string.match_review_played_score, playedWord(played), played.score()));
        practiceButton.setVisibility(turn.practice() == null ? View.GONE : View.VISIBLE);
        alternativeRevealed = turn.practice() == null;
        if (alternative == null) {
            alternativeScore.setVisibility(View.GONE);
            comparison.setVisibility(alternativesAllowed ? View.VISIBLE : View.GONE);
            comparison.setText(turn.rackAvailable()
                    ? R.string.match_review_alternative_unavailable : R.string.match_review_rack_unavailable);
        } else {
            showAlternativeScores(turn);
        }
        ui.enabled(alternativeButton, alternative != null);
        explainButton.setText(alternative == null ? R.string.move_explanation_current : R.string.match_review_explain);
        board.resetZoom();
        showPosition(false);
    }

    private void showPosition(boolean showAlternative) {
        MatchReview.Turn turn = turns.get(selected);
        FrenchGame.Move played = turn.played();
        MatchReview.Alternative alternative = turn.alternative();
        if (showAlternative && alternative == null) return;
        if (showAlternative) revealAlternative(turn);
        char[] letters = turn.board().clone();
        boolean[] blanks = turn.blanks().clone();
        List<FrenchGame.Tile> tiles = showAlternative ? alternative.tiles() : played.tiles();
        for (FrenchGame.Tile tile : tiles) {
            letters[tile.position()] = tile.letter();
            blanks[tile.position()] = tile.blank();
        }
        String word = showAlternative ? alternative.preview().word() : playedWord(played);
        int score = showAlternative ? alternative.preview().score() : played.score();
        List<String> placed = new ArrayList<>(tiles.size());
        for (FrenchGame.Tile tile : tiles) {
            char row = (char) ('A' + tile.position() / FrenchGame.SIZE);
            int column = tile.position() % FrenchGame.SIZE + 1;
            placed.add(label(tile.blank() ? R.string.match_review_board_blank : R.string.match_review_board_tile,
                    tile.letter(), row, column));
        }
        board.update(letters, blanks, List.of(), tiles, -1);
        board.setContentDescription(quantity(R.plurals.match_review_board, score, label(showAlternative
                ? R.string.match_review_alternative : R.string.match_review_played), word, score)
                + (placed.isEmpty() ? "" : " " + String.join(", ", placed) + "."));
        highlight(playedButton, !showAlternative);
        highlight(alternativeButton, showAlternative);
    }

    private void revealAlternative(MatchReview.Turn turn) {
        if (alternativeRevealed || turn.alternative() == null) return;
        alternativeRevealed = true;
        showAlternativeScores(turn);
    }

    private void showAlternativeScores(MatchReview.Turn turn) {
        if (!alternativeRevealed) {
            alternativeScore.setVisibility(View.GONE);
            comparison.setVisibility(View.GONE);
            return;
        }
        FrenchGame.Preview preview = turn.alternative().preview();
        alternativeScore.setText(label(R.string.match_review_alternative_score, preview.word(), preview.score()));
        alternativeScore.setVisibility(View.VISIBLE);
        int delta = preview.score() - turn.played().score();
        comparison.setText(delta == 0 ? label(R.string.match_review_equal)
                : label(R.string.match_review_score_delta, delta));
        comparison.setVisibility(View.VISIBLE);
    }

    private void highlight(TextView button, boolean active) {
        button.setSelected(active);
        button.setTextColor(active ? ui.palette.heroInk() : ui.palette.ink());
        button.setBackground(ui.touch(active ? ui.palette.deep() : ui.palette.paper(), 12,
                active ? 0 : ui.palette.line()));
    }

    private String playedWord(FrenchGame.Move move) {
        return switch (move.type()) {
            case "pass" -> label(R.string.match_review_pass);
            case "exchange" -> label(R.string.match_review_exchange);
            default -> move.word();
        };
    }

    private String label(int resource, Object... values) { return ui.context.getString(resource, values); }

    private String quantity(int resource, int count, Object... values) {
        return ui.context.getResources().getQuantityString(resource, count, values);
    }
}
