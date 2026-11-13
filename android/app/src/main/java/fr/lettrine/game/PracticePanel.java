package fr.lettrine.game;

import android.app.AlertDialog;
import android.content.ClipData;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

final class PracticePanel {
    private final ViewKit ui;
    private final PracticePosition position;
    private final Lexicon lexicon;
    private final boolean first;
    private final DailyResult dailyResult;
    private final char[] letters;
    private final boolean[] blanks;
    private final List<Character> rack;
    private final List<FrenchGame.Tile> staged = new ArrayList<>();
    private final List<TextView> rackButtons = new ArrayList<>();
    private final LinearLayout panel;
    private final LinearLayout result;
    private final LinearLayout playControls;
    private final TextView attemptScore;
    private final TextView referenceScore;
    private final TextView feedback;
    private final TextView preview;
    private final TextView play;
    private final TextView clear;
    private final TextView retry;
    private final TextView showAttempt;
    private final TextView showReference;
    private final BoardView board;
    private int selected = -1;
    private boolean finished;
    private FrenchGame.Preview attempt;

    PracticePanel(ViewKit ui, PracticePosition position, Lexicon lexicon, boolean first) {
        this(ui, position, lexicon, first, null);
    }

    PracticePanel(ViewKit ui, PracticePosition position, Lexicon lexicon, boolean first, DailyResult dailyResult) {
        this.ui = ui;
        this.position = position;
        this.lexicon = lexicon;
        this.first = first;
        this.dailyResult = dailyResult;
        letters = position.board();
        blanks = position.blanks();
        rack = position.rack();
        panel = ui.column();

        result = ui.card();
        if (dailyResult != null) {
            LinearLayout.LayoutParams summary = new LinearLayout.LayoutParams(-1, -2);
            summary.bottomMargin = ui.dp(9);
            result.addView(dailyResult.summary(), summary);
        }
        attemptScore = ui.body("");
        referenceScore = ui.body("");
        feedback = ui.body("");
        if (dailyResult == null) result.addView(attemptScore);
        result.addView(referenceScore);
        if (position.originalScore() != null) {
            result.addView(ui.body(label(R.string.practice_original, points(position.originalScore()))));
        }
        ui.gap(result, 5);
        result.addView(feedback);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        ui.gap(result, 6);
        LinearLayout modes = ui.row();
        showAttempt = ui.button(label(R.string.practice_yours), false, () -> showResult(false));
        showReference = ui.button(label(R.string.practice_reference), false, () -> showResult(true));
        ui.weight(modes, showAttempt);
        ui.space(modes, 6);
        ui.weight(modes, showReference);
        result.addView(modes);
        retry = ui.textButton(label(R.string.practice_reset), () -> {
            reset();
            showStart();
        });
        if (dailyResult != null) {
            ui.gap(result, 9);
            LinearLayout next = ui.row();
            next.addView(dailyResult.shareButton(), new LinearLayout.LayoutParams(0, -2, 1.55f));
            LinearLayout.LayoutParams replay = new LinearLayout.LayoutParams(0, -2, 1);
            replay.leftMargin = ui.dp(7);
            next.addView(retry, replay);
            result.addView(next);
        }
        panel.addView(result);

        board = new BoardView(ui.context, ui.palette, new BoardView.Interaction() {
            @Override public void tap(int square) { tapSquare(square); }
            @Override public void drop(int square, int rackIndex) { place(square, rackIndex); }
        });
        panel.addView(board, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout zoom = ui.row();
        preview = ui.text("", 14, ui.palette.ink(), ui.medium);
        preview.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        ui.weight(zoom, preview);
        TextView zoomReset = ui.textButton("1×", board::resetZoom);
        zoomReset.setContentDescription(label(R.string.practice_zoom_reset));
        zoom.addView(zoomReset);
        TextView enlarge = ui.textButton("+", () -> board.zoomBy(1.25f));
        enlarge.setContentDescription(label(R.string.practice_zoom));
        zoom.addView(enlarge);
        panel.addView(zoom);

        playControls = ui.column();
        LinearLayout hand = ui.row();
        for (int i = 0; i < rack.size(); i++) {
            int index = i;
            char letter = rack.get(i);
            TextView tile = ui.text(String.valueOf(letter), 21, ui.palette.ink(), ui.medium);
            tile.setGravity(android.view.Gravity.CENTER);
            tile.setMinHeight(ui.dp(48));
            tile.setElevation(ui.dp(1));
            tile.setOnClickListener(view -> {
                if (finished || used(index)) return;
                selected = selected == index ? -1 : index;
                renderAttempt(-1);
            });
            tile.setOnLongClickListener(view -> !finished && !used(index) && view.startDragAndDrop(
                    ClipData.newPlainText("Lettrine", String.valueOf(letter)),
                    new View.DragShadowBuilder(view), index, 0));
            rackButtons.add(tile);
            if (i > 0) ui.space(hand, 4);
            ui.weight(hand, tile);
        }
        playControls.addView(hand);
        ui.gap(playControls, 8);
        LinearLayout actions = ui.row();
        play = ui.button(label(R.string.practice_ready), true, this::submit);
        clear = ui.textButton(label(R.string.practice_clear), this::clear);
        ui.weight(actions, play);
        ui.space(actions, 7);
        actions.addView(clear);
        playControls.addView(actions);
        playControls.addView(ui.textButton(label(R.string.practice_reveal), () -> finish(null)));
        panel.addView(playControls);
        if (dailyResult == null) panel.addView(retry);
        reset();
    }

    View view() { return panel; }

    private void reset() {
        finished = false;
        attempt = null;
        result.setVisibility(View.GONE);
        if (dailyResult != null) dailyResult.show(null);
        playControls.setVisibility(View.VISIBLE);
        retry.setVisibility(View.GONE);
        preview.setVisibility(View.VISIBLE);
        board.resetZoom();
        clear();
    }

    private void clear() {
        staged.clear();
        selected = -1;
        renderAttempt(-1);
    }

    private void tapSquare(int square) {
        if (finished) return;
        for (int i = 0; i < staged.size(); i++) {
            if (staged.get(i).position() == square) {
                selected = staged.remove(i).rackIndex();
                renderAttempt(-1);
                return;
            }
        }
        if (selected >= 0) place(square, selected);
    }

    private void place(int square, int index) {
        if (finished || index < 0 || index >= rack.size() || letters[square] != 0 || used(index)) return;
        for (FrenchGame.Tile tile : staged) if (tile.position() == square) return;
        char letter = rack.get(index);
        if (letter == FrenchGame.JOKER) {
            String[] choices = new String[26];
            for (int i = 0; i < choices.length; i++) choices[i] = Character.toString((char) ('A' + i));
            new AlertDialog.Builder(ui.context).setTitle(R.string.practice_joker_choice)
                    .setItems(choices, (dialog, item) -> stage(square, index, (char) ('A' + item), true))
                    .setNegativeButton(R.string.practice_cancel, null).show();
        } else stage(square, index, letter, false);
    }

    private void stage(int square, int index, char letter, boolean blank) {
        staged.add(new FrenchGame.Tile(square, index, letter, blank));
        selected = -1;
        board.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        renderAttempt(square);
    }

    private boolean used(int index) {
        for (FrenchGame.Tile tile : staged) if (tile.rackIndex() == index) return true;
        return false;
    }

    private void renderAttempt(int animated) {
        board.update(letters, blanks, staged, List.of(), animated);
        String description = dailyResult == null ? label(first ? R.string.practice_board_first_description
                : R.string.practice_board_description) : dailyResult.boardDescription();
        board.setContentDescription(description + tileSummary(staged));
        for (int i = 0; i < rackButtons.size(); i++) {
            TextView tile = rackButtons.get(i);
            boolean occupied = used(i);
            char letter = rack.get(i);
            String tileLabel = letter == FrenchGame.JOKER ? label(R.string.practice_joker)
                    : label(R.string.practice_letter, letter);
            tile.setContentDescription(occupied ? label(R.string.practice_placed, tileLabel) : tileLabel);
            tile.setSelected(i == selected);
            tile.setBackground(rackFace(i == selected));
            ui.enabled(tile, !occupied);
        }
        FrenchGame.Preview move = staged.isEmpty() ? null : position.preview(staged, lexicon);
        preview.setText(move == null ? label(R.string.practice_guide) : move.valid()
                ? label(R.string.practice_preview_score, move.word(), points(move.score())) : move.error());
        ui.enabled(play, move != null && move.valid());
        ui.enabled(clear, !staged.isEmpty() || selected >= 0);
    }

    private void submit() {
        FrenchGame.Preview move = position.preview(staged, lexicon);
        if (move.valid()) finish(move);
    }

    private void finish(FrenchGame.Preview move) {
        finished = true;
        attempt = move;
        int referencePoints = position.reference().preview().score();
        attemptScore.setVisibility(move == null ? View.GONE : View.VISIBLE);
        if (move != null) attemptScore.setText(scoreLine(R.string.practice_yours, move));
        if (dailyResult != null) dailyResult.show(move);
        referenceScore.setText(scoreLine(R.string.practice_reference, position.reference().preview()));
        String message = "";
        if (move != null) {
            int difference = move.score() - referencePoints;
            message = difference == 0 ? label(R.string.practice_equal) : label(
                    difference > 0 ? R.string.practice_better : R.string.practice_lower, points(Math.abs(difference)));
        }
        if (first) message += (message.isEmpty() ? "" : "\n") + label(R.string.practice_first_tip);
        feedback.setText(message);
        feedback.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        result.setVisibility(View.VISIBLE);
        playControls.setVisibility(View.GONE);
        preview.setVisibility(View.INVISIBLE);
        retry.setVisibility(View.VISIBLE);
        showAttempt.setVisibility(move == null ? View.GONE : View.VISIBLE);
        showResult(move == null);
        showStart();
    }

    private void showResult(boolean alternative) {
        if (!finished || (!alternative && attempt == null)) return;
        List<FrenchGame.Tile> tiles = alternative ? position.reference().tiles() : staged;
        FrenchGame.Preview move = alternative ? position.reference().preview() : attempt;
        char[] played = letters.clone();
        boolean[] playedBlanks = blanks.clone();
        for (FrenchGame.Tile tile : tiles) {
            played[tile.position()] = tile.letter();
            playedBlanks[tile.position()] = tile.blank();
        }
        board.update(played, playedBlanks, List.of(), tiles, -1);
        board.setContentDescription(label(R.string.practice_board_result,
                label(alternative ? R.string.practice_reference : R.string.practice_yours),
                move.word(), points(move.score())) + tileSummary(tiles));
        highlight(showAttempt, !alternative);
        highlight(showReference, alternative);
    }

    private void showStart() {
        panel.post(() -> panel.requestRectangleOnScreen(new Rect(0, 0, panel.getWidth(), ui.dp(55)), false));
    }

    private GradientDrawable rackFace(boolean active) {
        int base = active ? ui.palette.fresh() : ui.palette.tile();
        int light = Color.rgb((3 * Color.red(base) + 255) / 4,
                (3 * Color.green(base) + 255) / 4, (3 * Color.blue(base) + 255) / 4);
        GradientDrawable face = ui.shape(base, 9, active ? ui.palette.accent() : ui.palette.tileEdge());
        face.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        face.setColors(new int[]{light, base});
        return face;
    }

    private void highlight(TextView button, boolean active) {
        button.setSelected(active);
        button.setTextColor(active ? ui.palette.heroInk() : ui.palette.ink());
        button.setBackground(ui.touch(active ? ui.palette.deep() : ui.palette.paper(), 12,
                active ? 0 : ui.palette.line()));
    }

    private String tileSummary(List<FrenchGame.Tile> tiles) {
        List<String> cells = new ArrayList<>(tiles.size());
        for (FrenchGame.Tile tile : tiles) {
            char row = (char) ('A' + tile.position() / FrenchGame.SIZE);
            int col = tile.position() % FrenchGame.SIZE + 1;
            cells.add(label(tile.blank() ? R.string.practice_board_blank : R.string.practice_board_tile,
                    tile.letter(), row, col));
        }
        return cells.isEmpty() ? "" : " " + String.join(", ", cells) + ".";
    }

    private String scoreLine(int name, FrenchGame.Preview move) {
        return label(R.string.practice_score_line, label(name), move.word(), points(move.score()));
    }

    private String points(int value) {
        return ui.context.getResources().getQuantityString(R.plurals.practice_points, value, value);
    }

    private String label(int resource, Object... values) { return ui.context.getString(resource, values); }
}
