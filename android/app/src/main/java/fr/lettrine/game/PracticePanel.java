package fr.lettrine.game;

import android.app.AlertDialog;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class PracticePanel {
    private static final int TUTORIAL_SQUARE = FrenchGame.CENTER + 4;
    private final ViewKit ui;
    private final PracticePosition position;
    private final Lexicon lexicon;
    private final boolean first;
    private final boolean tutorial;
    private final DailyResult dailyResult;
    private final Consumer<List<FrenchGame.Tile>> duelSubmit;
    private final Consumer<List<FrenchGame.Tile>> saveDraft;
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
    private final LinearLayout placementStatus;
    private final TextView validation;
    private final TextView preview;
    private final TextView play;
    private final TextView clear;
    private final TextView retry;
    private final TextView showAttempt;
    private final TextView showReference;
    private final BoardView board;
    private int selected = -1;
    private boolean finished;
    private boolean submitting;
    private FrenchGame.Preview attempt;

    PracticePanel(ViewKit ui, PracticePosition position, Lexicon lexicon, boolean first, boolean tutorial) {
        this(ui, position, lexicon, first, tutorial, null, null, "", null);
    }

    PracticePanel(ViewKit ui, PracticePosition position, Lexicon lexicon, DailyResult dailyResult,
                  String draft, Consumer<List<FrenchGame.Tile>> saveDraft) {
        this(ui, position, lexicon, false, false, dailyResult, null, draft, saveDraft);
    }

    PracticePanel(ViewKit ui, PracticePosition position, Lexicon lexicon, Consumer<List<FrenchGame.Tile>> duelSubmit) {
        this(ui, position, lexicon, false, false, null, duelSubmit, "", null);
    }

    private PracticePanel(ViewKit ui, PracticePosition position, Lexicon lexicon, boolean first, boolean tutorial,
                          DailyResult dailyResult, Consumer<List<FrenchGame.Tile>> duelSubmit,
                          String draft, Consumer<List<FrenchGame.Tile>> saveDraft) {
        this.ui = ui;
        this.position = position;
        this.lexicon = lexicon;
        this.first = first;
        this.tutorial = tutorial;
        this.dailyResult = dailyResult;
        this.duelSubmit = duelSubmit;
        this.saveDraft = saveDraft;
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
            saveDraft();
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

        board = new BoardView(ui, new BoardView.Interaction() {
            @Override public void tap(int square) { tapSquare(square); }
            @Override public boolean canTap(int square) {
                return !finished && !submitting && (selected >= 0 || stagedAt(square) >= 0);
            }
            @Override public void drop(int square, int rackIndex) { place(square, rackIndex); }
            @Override public boolean dragTile(int square) { return !finished && !submitting && stagedAt(square) >= 0; }
            @Override public void moveTile(int from, int to) { moveStaged(from, to); }
        });
        if (tutorial) board.guide(TUTORIAL_SQUARE);
        panel.addView(board, new LinearLayout.LayoutParams(-1, -2));
        ui.gap(panel, 7);
        LinearLayout tools = ui.row();
        boolean stackTools = ui.context.getResources().getConfiguration().screenWidthDp < 360
                || ui.context.getResources().getConfiguration().fontScale > 1.3f;
        if (stackTools) tools.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        placementStatus = ui.column();
        ui.inset(placementStatus, 9, 7);
        validation = ui.text("", 11, ui.palette.accent(), ui.medium);
        validation.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        placementStatus.addView(validation);
        ui.gap(placementStatus, 4);
        preview = ui.text("", 15, ui.palette.ink(), ui.medium);
        preview.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        placementStatus.addView(preview);
        if (!stackTools) {
            ui.weight(tools, placementStatus);
            ui.space(tools, 6);
        }
        LinearLayout zoom = ui.row();
        zoom.setBackground(ui.shape(ui.palette.paper(), 13, ui.palette.line()));
        TextView reduce = zoomButton("−", R.string.practice_zoom_out, () -> board.zoomBy(0.74f));
        TextView zoomReset = zoomButton("100 %", R.string.practice_zoom_reset, board::resetZoom);
        zoomReset.setMinWidth(ui.dp(52));
        TextView enlarge = zoomButton("+", R.string.practice_zoom, () -> board.zoomBy(1.35f));
        zoom.addView(reduce);
        zoom.addView(zoomReset);
        zoom.addView(enlarge);
        tools.addView(zoom);
        board.onZoom(value -> {
            zoomReset.setText(label(R.string.practice_zoom_percent, Math.round(value * 100)));
            ui.enabled(reduce, value > 1.001f);
            ui.enabled(enlarge, value < 3.499f);
        });
        panel.addView(tools);
        if (stackTools) {
            ui.gap(panel, 5);
            panel.addView(placementStatus);
        }

        playControls = ui.column();
        LinearLayout hand = ui.row();
        ui.gap(playControls, 8);
        hand.setBackground(ui.shape(ui.palette.ordinary(), 16));
        ui.inset(hand, 6, 6);
        for (int i = 0; i < rack.size(); i++) {
            int index = i;
            char letter = rack.get(i);
            TextView tile = ui.text(String.valueOf(letter), 21, ui.tileInk(), ui.medium);
            tile.setGravity(android.view.Gravity.CENTER);
            tile.setMinHeight(ui.dp(48));
            tile.setElevation(ui.dp(1));
            tile.setOnClickListener(view -> {
                if (finished || submitting || used(index)) return;
                selected = selected == index ? -1 : index;
                renderAttempt(-1);
            });
            ui.dragTile(tile, index, letter, () -> !finished && !submitting && !used(index));
            rackButtons.add(tile);
            if (i > 0) ui.space(hand, 4);
            ui.weight(hand, tile);
        }
        playControls.addView(hand);
        ui.gap(playControls, 8);
        LinearLayout actions = ui.row();
        play = ui.button(label(duelSubmit == null ? R.string.practice_ready : R.string.social_submit), true, this::submit);
        clear = ui.textButton(label(R.string.practice_clear), () -> {
            clear();
            saveDraft();
        });
        ui.weight(actions, play);
        ui.space(actions, 7);
        actions.addView(clear);
        playControls.addView(actions);
        if (duelSubmit == null && !tutorial) {
            playControls.addView(ui.textButton(label(R.string.practice_reveal), () -> finish(null)));
        }
        panel.addView(playControls);
        if (dailyResult == null) panel.addView(retry);
        reset();
        restoreDraft(draft);
    }

    View view() { return panel; }

    private void restoreDraft(String draft) {
        if (draft.isEmpty()) return;
        try {
            for (FrenchGame.Tile tile : MatchCodec.readTiles(new JSONArray(draft), true)) {
                if (tile.rackIndex() >= rack.size() || letters[tile.position()] != 0
                        || used(tile.rackIndex()) || stagedAt(tile.position()) >= 0) continue;
                if (rack.get(tile.rackIndex()) == (tile.blank() ? FrenchGame.JOKER : tile.letter())) staged.add(tile);
            }
            renderAttempt(-1);
        } catch (JSONException | IllegalArgumentException invalid) {
            clear();
        }
    }

    private void saveDraft() {
        if (saveDraft != null) saveDraft.accept(List.copyOf(staged));
    }

    private TextView zoomButton(String text, int description, Runnable action) {
        TextView button = ui.text(text, 15, ui.palette.ink(), ui.medium);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(ui.dp(48));
        button.setMinWidth(ui.dp(44));
        button.setContentDescription(label(description));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    void setSubmitting(boolean pending) {
        submitting = pending;
        play.setText(pending ? R.string.social_sending : R.string.social_submit);
        renderAttempt(-1);
    }

    private void reset() {
        finished = false;
        attempt = null;
        result.setVisibility(View.GONE);
        if (dailyResult != null) dailyResult.show(null);
        playControls.setVisibility(View.VISIBLE);
        retry.setVisibility(View.GONE);
        placementStatus.setVisibility(View.VISIBLE);
        if (tutorial) board.guide(TUTORIAL_SQUARE);
        board.resetZoom();
        clear();
    }

    private void clear() {
        staged.clear();
        selected = -1;
        renderAttempt(-1);
    }

    private void tapSquare(int square) {
        if (finished || submitting) return;
        for (int i = 0; i < staged.size(); i++) {
            if (staged.get(i).position() == square) {
                selected = staged.remove(i).rackIndex();
                saveDraft();
                renderAttempt(-1);
                return;
            }
        }
        if (selected >= 0) place(square, selected);
    }

    private int stagedAt(int square) {
        for (int i = 0; i < staged.size(); i++) if (staged.get(i).position() == square) return i;
        return -1;
    }

    private void moveStaged(int from, int to) {
        int index = stagedAt(from);
        if (index < 0 || to < 0 || letters[to] != 0 || stagedAt(to) >= 0) return;
        FrenchGame.Tile tile = staged.get(index);
        staged.set(index, new FrenchGame.Tile(to, tile.rackIndex(), tile.letter(), tile.blank()));
        saveDraft();
        ui.audio.tile();
        renderAttempt(to);
    }

    private void place(int square, int index) {
        if (finished || submitting || index < 0 || index >= rack.size() || letters[square] != 0 || used(index)) return;
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
        saveDraft();
        selected = -1;
        board.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        ui.audio.tile();
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
            tile.setBackground(ui.rackTile(i == selected || tutorial && staged.isEmpty() && selected < 0 && letter == 'S'));
            ui.enabled(tile, !submitting && !occupied);
        }
        FrenchGame.Preview move = staged.isEmpty() ? null : position.preview(staged, lexicon);
        boolean canSubmit = move != null && move.valid() && (!tutorial || tutorialMove());
        updateValidation(canSubmit, move != null);
        if (tutorial && !canSubmit) {
            preview.setText(!staged.isEmpty() ? R.string.help_tutorial_clear
                    : selected >= 0 && rack.get(selected) == 'S' ? R.string.help_tutorial_place : R.string.help_tutorial_select);
        } else {
            preview.setText(move == null ? label(R.string.practice_guide) : move.valid()
                    ? label(R.string.practice_preview_score, move.word(), points(move.score())) : move.error());
        }
        ui.enabled(play, !submitting && canSubmit);
        ui.enabled(clear, !submitting && (!staged.isEmpty() || selected >= 0));
    }

    private void submit() {
        FrenchGame.Preview move = position.preview(staged, lexicon);
        if (!move.valid() || tutorial && !tutorialMove()) return;
        ui.audio.played();
        if (duelSubmit == null) finish(move);
        else duelSubmit.accept(List.copyOf(staged));
    }

    private boolean tutorialMove() {
        return staged.size() == 1 && staged.get(0).position() == TUTORIAL_SQUARE && staged.get(0).letter() == 'S';
    }

    private void finish(FrenchGame.Preview move) {
        finished = true;
        if (saveDraft != null) saveDraft.accept(List.of());
        if (tutorial) board.guide(-1);
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
        if (first) message += (message.isEmpty() ? "" : "\n") + label(tutorial ? R.string.help_tutorial_done : R.string.practice_first_tip);
        feedback.setText(message);
        feedback.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        result.setVisibility(View.VISIBLE);
        playControls.setVisibility(View.GONE);
        placementStatus.setVisibility(View.GONE);
        retry.setVisibility(View.VISIBLE);
        showAttempt.setVisibility(move == null ? View.GONE : View.VISIBLE);
        showResult(move == null);
        showStart();
    }

    private void updateValidation(boolean valid, boolean attempted) {
        int bg = valid ? ui.palette.ordinary() : attempted ? ui.palette.doubleWord() : ui.palette.paper();
        validation.setText(valid ? R.string.practice_status_valid : attempted
                ? R.string.practice_status_invalid : R.string.practice_status_empty);
        validation.setTextColor(valid ? ui.palette.accent() : ui.palette.ink());
        placementStatus.setBackground(ui.shape(bg, 12));
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
