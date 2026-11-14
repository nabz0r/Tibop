package fr.lettrine.game;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

final class TwoMoveChallengeScreen {
    private final ViewKit ui;
    private final Lexicon lexicon;
    private final LinearLayout panel;
    private final List<TwoMoveChallenge.Puzzle> catalog;
    private final SharedPreferences progress;
    private final SharedPreferences drafts;
    private final Consumer<String> baseCompletion;
    private final CircuitPlay circuit;
    private PuzzlePanel active;

    record CircuitPlay(String savedDraft, IntPredicate complete, Consumer<String> saveDraft,
                       Runnable next, boolean lastRound) {}

    TwoMoveChallengeScreen(ViewKit ui, Lexicon lexicon, Consumer<String> baseCompletion) {
        this(ui, lexicon, null, baseCompletion);
        showCatalog();
    }

    TwoMoveChallengeScreen(ViewKit ui, Lexicon lexicon, TwoMoveChallenge.Puzzle puzzle, CircuitPlay circuit) {
        this(ui, lexicon, circuit, null);
        open(puzzle);
    }

    private TwoMoveChallengeScreen(ViewKit ui, Lexicon lexicon, CircuitPlay circuit, Consumer<String> baseCompletion) {
        this.ui = ui;
        this.lexicon = lexicon;
        this.baseCompletion = baseCompletion;
        this.circuit = circuit;
        panel = ui.column();
        catalog = circuit == null ? TwoMoveChallenge.catalog(lexicon) : List.of();
        progress = ui.context.getSharedPreferences(lexicon.language.storageName("two_move_challenges"), Context.MODE_PRIVATE);
        drafts = circuit == null ? ui.context.getSharedPreferences(lexicon.language.storageName("two_move_draft"),
                Context.MODE_PRIVATE) : null;
    }

    View view() { return panel; }

    boolean goBack() {
        if (active == null) return false;
        if (active.replay >= 0) active.leaveReplay();
        else if (circuit != null) return false;
        else showCatalog();
        return true;
    }

    private void showCatalog() {
        active = null;
        panel.removeAllViews();
        LinearLayout intro = ui.card();
        intro.setBackground(ui.shape(ui.palette.deep(), 18));
        intro.addView(ui.text(label(R.string.two_move_title), 28, ui.palette.heroInk(), ui.serif));
        ui.gap(intro, 8);
        TextView rules = ui.body(label(catalog.isEmpty() ? R.string.two_move_unavailable : R.string.two_move_intro));
        rules.setTextColor(ui.palette.heroInk());
        intro.addView(rules);
        if (!catalog.isEmpty()) {
            int completed = 0;
            for (TwoMoveChallenge.Puzzle puzzle : catalog) {
                if (progress.contains(puzzle.id())) completed++;
            }
            ui.gap(intro, 9);
            intro.addView(ui.text(label(R.string.two_move_catalog_progress, completed, catalog.size()),
                    13, ui.palette.heroInk(), ui.medium));
        }
        panel.addView(intro);
        for (int i = 0; i < catalog.size(); i++) {
            TwoMoveChallenge.Puzzle puzzle = catalog.get(i);
            int record = progress.getInt(puzzle.id(), -1);
            String detail = record < 0 ? puzzle.objective()
                    : label(R.string.two_move_catalog_record, points(record));
            ui.gap(panel, 7);
            panel.addView(ui.option(record < 0 ? Integer.toString(i + 1) : "✓", puzzle.title(), detail,
                    () -> open(puzzle)));
        }
        scrollToStart();
    }

    private void open(TwoMoveChallenge.Puzzle puzzle) {
        panel.removeAllViews();
        active = new PuzzlePanel(puzzle);
        panel.addView(active.layout);
        active.fitBoard();
        scrollToStart();
    }

    private void scrollToStart() {
        panel.post(() -> panel.requestRectangleOnScreen(new Rect(0, 0, panel.getWidth(), ui.dp(48)), true));
    }

    private String label(int resource, Object... values) {
        return ui.context.getString(resource, values);
    }

    private String points(int value) {
        return ui.context.getResources().getQuantityString(R.plurals.practice_points, value, value);
    }

    private final class PuzzlePanel implements BoardView.Interaction {
        private final TwoMoveChallenge.Puzzle puzzle;
        private final LinearLayout layout = ui.column();
        private final List<FrenchGame.Tile> staged = new ArrayList<>();
        private final List<TextView> rackButtons = new ArrayList<>();
        private final TextView phase;
        private final TextView objective;
        private final BoardView board;
        private final TextView feedback;
        private final TextView submit;
        private final TextView clear;
        private final TextView edit;
        private final TextView hint;
        private final TextView replayToggle;
        private final TextView replayStep;
        private final TextView retry;
        private final TextView next;
        private final TextView nabText;
        private final LinearLayout rackRow;
        private final RackDrop rackDrop;
        private final LinearLayout playRow;
        private TwoMoveChallenge.Position position;
        private TwoMoveChallenge.Step first;
        private TwoMoveChallenge.Step second;
        private int selected = -1;
        private int replay = -1;
        private boolean hintVisible;
        private boolean blocked;

        PuzzlePanel(TwoMoveChallenge.Puzzle puzzle) {
            this.puzzle = puzzle;
            position = puzzle.initial();
            layout.addView(ui.title(puzzle.title(), 24));
            ui.gap(layout, 4);
            phase = ui.text("", 15, ui.palette.accent(), ui.medium);
            phase.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            layout.addView(phase);
            objective = ui.body("");
            objective.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            ui.gap(layout, 4);
            layout.addView(objective);
            ui.gap(layout, 8);
            board = new BoardView(ui, this);
            layout.addView(board, new LinearLayout.LayoutParams(-1, -2));
            ui.gap(layout, 6);

            LinearLayout tools = ui.row();
            rackRow = ui.row();
            rackDrop = new RackDrop(ui, rackRow, rackButtons, this::canMoveRackTile, this::reorderRack);
            rackRow.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            ui.inset(rackRow, 6, 6);
            rackRow.setBackground(ui.shape(ui.palette.ordinary(), 13));
            layout.addView(rackRow);
            ui.gap(layout, 6);
            feedback = ui.text("", 14, ui.palette.ink(), ui.medium);
            ui.inset(feedback, 9, 8);
            feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            ui.weight(tools, feedback);
            ui.space(tools, 5);
            tools.addView(zoomControls());
            layout.addView(tools);
            ui.gap(layout, 6);

            playRow = ui.row();
            submit = ui.button("", true, this::submit);
            clear = ui.textButton(label(R.string.practice_clear), this::clear);
            ui.weight(playRow, submit);
            ui.space(playRow, 7);
            playRow.addView(clear);
            layout.addView(playRow);
            edit = ui.textButton(label(R.string.two_move_edit_first), this::editFirst);
            layout.addView(edit);
            hint = ui.textButton(label(R.string.two_move_hint), () -> {
                hintVisible = !hintVisible;
                if (hintVisible) fitBoard();
                render(-1);
            });
            layout.addView(hint);
            nabText = ui.body("");
            ui.inset(nabText, 10, 9);
            nabText.setBackground(ui.shape(ui.palette.ordinary(), 12));
            nabText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            layout.addView(nabText);
            replayStep = ui.button("", true, () -> showReplay(1 - replay));
            layout.addView(replayStep);
            replayToggle = ui.textButton("", this::toggleReplay);
            layout.addView(replayToggle);
            retry = ui.textButton(label(R.string.two_move_retry), this::restart);
            layout.addView(retry);
            next = circuit == null ? null : ui.button(label(circuit.lastRound()
                    ? R.string.bonus_see_results : R.string.bonus_next_round), true, circuit.next());
            if (next == null) {
                layout.addView(ui.textButton(label(R.string.two_move_catalog), TwoMoveChallengeScreen.this::showCatalog));
            } else {
                layout.addView(next);
            }
            restoreDraft();
            blocked = circuit != null && first != null && !TwoMoveChallenge.hasSecondMove(position, lexicon);

            buildRack();
            render(-1);
        }

        private View zoomControls() {
            LinearLayout zoom = ui.row();
            zoom.setBackground(ui.shape(ui.palette.paper(), 12, ui.palette.line()));
            TextView minus = zoomButton("−", R.string.practice_zoom_out, () -> board.zoomBy(0.74f));
            TextView reset = zoomButton("", R.string.practice_zoom_reset, board::resetZoom);
            reset.setMinWidth(ui.dp(46));
            TextView plus = zoomButton("+", R.string.practice_zoom, () -> board.zoomBy(1.35f));
            zoom.addView(minus);
            zoom.addView(reset);
            zoom.addView(plus);
            board.onZoom(value -> {
                reset.setText(label(R.string.practice_zoom_percent, Math.round(value * 100)));
                ui.enabled(minus, value > 1.001f);
                ui.enabled(plus, value < 3.499f);
            });
            return zoom;
        }

        private TextView zoomButton(String text, int description, Runnable action) {
            TextView button = ui.text(text, 13, ui.palette.ink(), ui.medium);
            button.setGravity(Gravity.CENTER);
            button.setMinimumHeight(ui.dp(48));
            button.setMinWidth(ui.dp(40));
            button.setContentDescription(label(description));
            button.setOnClickListener(view -> action.run());
            return button;
        }

        private void fitBoard() {
            char[] cells = puzzle.solution().get(1).after().board();
            int minRow = FrenchGame.SIZE;
            int minCol = FrenchGame.SIZE;
            int maxRow = 0;
            int maxCol = 0;
            for (int i = 0; i < cells.length; i++) {
                if (cells[i] == 0) continue;
                int row = i / FrenchGame.SIZE;
                int col = i % FrenchGame.SIZE;
                minRow = Math.min(minRow, row);
                minCol = Math.min(minCol, col);
                maxRow = Math.max(maxRow, row);
                maxCol = Math.max(maxCol, col);
            }
            int top = minRow;
            int left = minCol;
            int bottom = maxRow;
            int right = maxCol;
            board.post(() -> board.focusArea(top, left, bottom, right));
        }

        private void buildRack() {
            rackButtons.clear();
            rackRow.removeAllViews();
            List<Character> rack = position.rack();
            for (int i = 0; i < rack.size(); i++) {
                int index = i;
                char letter = rack.get(i);
                TextView tile = ui.text(String.valueOf(letter), 21, ui.tileInk(), ui.medium);
                tile.setGravity(Gravity.CENTER);
                tile.setMinHeight(ui.dp(48));
                tile.setElevation(ui.dp(1));
                tile.setOnClickListener(view -> {
                    if (blocked) return;
                    selected = selected == index ? -1 : index;
                    render(-1);
                });
                ui.dragTile(tile, index, letter, () -> playing() && !blocked && !used(index));
                rackButtons.add(tile);
                if (i > 0) ui.space(rackRow, 4);
                rackRow.addView(tile, new LinearLayout.LayoutParams(0, -2, 1));
            }
            if (rack.size() < FrenchGame.RACK_SIZE) {
                rackRow.addView(new View(ui.context), new LinearLayout.LayoutParams(0, 1,
                        FrenchGame.RACK_SIZE - rack.size()));
            }
        }

        private void updateRack(List<Character> rack) {
            for (int i = 0; i < rackButtons.size(); i++) {
                TextView tile = rackButtons.get(i);
                char letter = rack.get(i);
                boolean occupied = used(i);
                String description = letter == FrenchGame.JOKER ? label(R.string.practice_joker)
                        : label(R.string.practice_letter, letter);
                tile.setContentDescription(occupied ? label(R.string.practice_placed, description) : description);
                tile.setSelected(selected == i);
                tile.setBackground(ui.rackTile(selected == i));
                ui.enabled(tile, !blocked && !occupied);
            }
            rackDrop.refreshActions();
        }

        private boolean canMoveRackTile(int index) {
            return index >= 0 && index < rackButtons.size() && playing() && !blocked && !used(index);
        }

        private void reorderRack(int source, int target) { rackDrop.reorderViews(source, target); }

        private boolean playing() { return replay < 0 && second == null; }

        private boolean used(int rackIndex) {
            for (FrenchGame.Tile tile : staged) if (tile.rackIndex() == rackIndex) return true;
            return false;
        }

        private int stagedAt(int square) {
            for (int i = 0; i < staged.size(); i++) if (staged.get(i).position() == square) return i;
            return -1;
        }

        @Override public boolean canTap(int square) {
            return playing() && !blocked && (selected >= 0 || stagedAt(square) >= 0);
        }

        @Override public void tap(int square) {
            if (!playing() || blocked) return;
            int index = stagedAt(square);
            if (index >= 0) {
                selected = staged.remove(index).rackIndex();
                saveDraft();
                render(-1);
            } else if (selected >= 0) drop(square, selected);
        }

        @Override public void drop(int square, int rackIndex) {
            if (!canStage(square, rackIndex)) return;
            char letter = position.rack().get(rackIndex);
            if (letter == FrenchGame.JOKER) {
                String[] letters = new String[26];
                for (int i = 0; i < letters.length; i++) letters[i] = Character.toString((char) ('A' + i));
                TwoMoveChallenge.Position original = position;
                new AlertDialog.Builder(ui.context).setTitle(R.string.practice_joker_choice)
                        .setItems(letters, (dialog, item) -> {
                            if (position == original && canStage(square, rackIndex)) {
                                stage(square, rackIndex, (char) ('A' + item), true);
                            }
                        })
                        .setNegativeButton(R.string.practice_cancel, null).show();
            } else stage(square, rackIndex, letter, false);
        }

        private boolean canStage(int square, int rackIndex) {
            return playing() && !blocked && square >= 0 && square < FrenchGame.CELLS
                    && rackIndex >= 0 && rackIndex < position.rack().size() && !used(rackIndex)
                    && position.board()[square] == 0 && stagedAt(square) < 0;
        }

        private void stage(int square, int index, char letter, boolean blank) {
            staged.add(new FrenchGame.Tile(square, index, letter, blank));
            selected = -1;
            board.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            ui.audio.tile();
            saveDraft();
            render(square);
        }

        @Override public boolean dragTile(int square) { return playing() && !blocked && stagedAt(square) >= 0; }

        @Override public void moveTile(int from, int to) {
            int index = stagedAt(from);
            if (!playing() || blocked || index < 0 || to < 0 || position.board()[to] != 0 || stagedAt(to) >= 0) return;
            FrenchGame.Tile tile = staged.get(index);
            staged.set(index, new FrenchGame.Tile(to, tile.rackIndex(), tile.letter(), tile.blank()));
            ui.audio.tile();
            saveDraft();
            render(to);
        }

        private void clear() {
            staged.clear();
            selected = -1;
            saveDraft();
            render(-1);
        }

        private void submit() {
            if (!playing() || blocked) return;
            TwoMoveChallenge.Step step = TwoMoveChallenge.play(position, staged, lexicon);
            if (!step.preview().valid()) return;
            if (first != null && circuit != null && !circuit.complete().test(step.after().totalScore())) {
                Toast.makeText(ui.context, R.string.bonus_save_error, Toast.LENGTH_SHORT).show();
                return;
            }
            position = step.after();
            if (first == null) {
                first = step;
                blocked = circuit != null && !TwoMoveChallenge.hasSecondMove(position, lexicon);
            } else {
                second = step;
                if (drafts != null) {
                    if (position.totalScore() > progress.getInt(puzzle.id(), -1)) {
                        progress.edit().putInt(puzzle.id(), position.totalScore()).apply();
                    }
                    baseCompletion.accept(puzzle.id());
                    drafts.edit().remove(puzzle.id()).apply();
                }
            }
            ui.audio.played();
            hintVisible = false;
            staged.clear();
            selected = -1;
            if (second == null) saveDraft();
            buildRack();
            render(-1);
            if (second != null || blocked) scrollToStart();
        }

        private void editFirst() {
            if (first == null || !playing()) return;
            position = first.before();
            staged.clear();
            staged.addAll(first.tiles());
            first = null;
            blocked = false;
            selected = -1;
            saveDraft();
            buildRack();
            render(-1);
        }

        private void restart() {
            position = puzzle.initial();
            first = null;
            second = null;
            replay = -1;
            hintVisible = false;
            blocked = false;
            staged.clear();
            selected = -1;
            saveDraft();
            buildRack();
            render(-1);
            fitBoard();
            scrollToStart();
        }

        private void saveDraft() {
            if (drafts != null && first == null && staged.isEmpty()) {
                drafts.edit().remove(puzzle.id()).apply();
                return;
            }
            JSONArray moves = new JSONArray();
            moves.put(MatchCodec.tiles(first == null ? List.of() : first.tiles(), true));
            moves.put(MatchCodec.tiles(staged, true));
            String saved = moves.toString();
            if (drafts == null) circuit.saveDraft().accept(saved);
            else drafts.edit().putString(puzzle.id(), saved).apply();
        }

        private void restoreDraft() {
            String saved = drafts == null ? circuit.savedDraft() : drafts.getString(puzzle.id(), "");
            if (saved == null || saved.isEmpty()) return;
            try {
                JSONArray moves = new JSONArray(saved);
                if (moves.length() != 2) return;
                List<FrenchGame.Tile> opening = MatchCodec.readTiles(moves.getJSONArray(0), true);
                List<FrenchGame.Tile> pending = MatchCodec.readTiles(moves.getJSONArray(1), true);
                if (!opening.isEmpty()) {
                    TwoMoveChallenge.Step played = TwoMoveChallenge.play(position, opening, lexicon);
                    if (!played.preview().valid()) return;
                    first = played;
                    position = first.after();
                }
                for (FrenchGame.Tile tile : pending) {
                    if (!canStage(tile.position(), tile.rackIndex())) continue;
                    char rackLetter = position.rack().get(tile.rackIndex());
                    if (tile.blank() ? rackLetter == FrenchGame.JOKER : rackLetter == tile.letter()) staged.add(tile);
                }
            } catch (JSONException | IllegalArgumentException ignored) {
                // An invalid draft does not prevent opening the position.
            }
        }

        private void toggleReplay() {
            if (replay < 0) showReplay(0);
            else leaveReplay();
        }

        private void showReplay(int index) {
            replay = index;
            List<FrenchGame.Tile> tiles = puzzle.solution().get(replay).tiles();
            render(tiles.get(0).position());
            fitBoard();
            scrollToStart();
        }

        private void leaveReplay() {
            replay = -1;
            render(-1);
            scrollToStart();
        }

        private void render(int animate) {
            boolean reviewing = replay >= 0;
            boolean playing = playing();
            boolean showHint = playing && first == null;
            show(rackRow, playing);
            show(playRow, playing && !blocked);
            show(edit, playing && first != null);
            show(hint, showHint);
            show(nabText, reviewing || showHint && hintVisible);
            show(replayStep, reviewing);
            show(replayToggle, reviewing || !playing || showHint && hintVisible);
            show(retry, circuit == null && !playing && !reviewing);
            if (next != null) show(next, !playing && !reviewing);
            hint.setText(hintVisible ? R.string.two_move_hint_hide : R.string.two_move_hint);
            replayToggle.setText(reviewing ? R.string.two_move_yours : R.string.two_move_replay);
            board.guide(showHint && hintVisible ? puzzle.hintPosition() : -1);

            if (reviewing) {
                renderReplay(animate);
                return;
            }
            if (!playing) {
                phase.setText(label(R.string.two_move_result, points(position.totalScore())));
                objective.setText(label(R.string.two_move_breakdown,
                        points(first.preview().score()), points(second.preview().score())));
                feedback.setText(score(second.preview()));
                feedback.setBackground(ui.shape(ui.palette.ordinary(), 12));
                renderBoard(position, List.of(), second.tiles(), -1, "");
                return;
            }

            boolean firstMove = first == null;
            phase.setText(firstMove ? label(R.string.two_move_phase, 1)
                    : label(R.string.two_move_phase, 2) + "  ·  "
                            + label(R.string.two_move_running_total, points(position.totalScore())));
            objective.setText(firstMove ? puzzle.objective()
                    : label(blocked ? R.string.bonus_no_second_move : R.string.two_move_keep));
            submit.setText(firstMove ? R.string.two_move_first : R.string.two_move_second);
            FrenchGame.Preview preview = staged.isEmpty() ? null : TwoMoveChallenge.play(position, staged, lexicon).preview();
            boolean valid = preview != null && preview.valid();
            ui.enabled(submit, valid);
            ui.enabled(clear, !staged.isEmpty() || selected >= 0);
            String message = blocked ? "" : preview == null ? label(R.string.two_move_pick) : valid
                    ? label(R.string.practice_status_valid) + "\n" + score(preview) : preview.error();
            feedback.setText(message);
            int feedbackColor = valid ? ui.palette.ordinary() : preview != null ? ui.palette.doubleWord() : ui.palette.paper();
            feedback.setBackground(ui.shape(feedbackColor, 12));
            updateRack(position.rack());
            String clue = "";
            if (showHint && hintVisible) {
                nabText.setText(label(R.string.two_move_nab_hint, puzzle.hint()));
                int square = puzzle.hintPosition();
                clue = label(R.string.two_move_highlight, (char) ('A' + square / FrenchGame.SIZE),
                        square % FrenchGame.SIZE + 1);
            }
            renderBoard(position, staged, firstMove ? List.of() : first.tiles(), animate, clue);
        }

        private void renderReplay(int animate) {
            TwoMoveChallenge.Step step = puzzle.solution().get(replay);
            phase.setText(label(R.string.two_move_replay_phase, replay + 1));
            objective.setText(label(R.string.two_move_running_total, points(step.after().totalScore())));
            feedback.setText(score(step.preview()));
            feedback.setBackground(ui.shape(ui.palette.ordinary(), 12));
            replayStep.setText(replay == 0 ? R.string.two_move_replay_next : R.string.two_move_replay_previous);
            nabText.setText(replay == 0
                    ? label(R.string.two_move_replay_note) + "\n" + rackText(step.after().rack())
                    : label(R.string.two_move_nab_hint, puzzle.explanation()));
            renderBoard(step.after(), List.of(), step.tiles(), animate, "");
        }

        private String rackText(List<Character> rack) {
            List<String> letters = new ArrayList<>(rack.size());
            for (char letter : rack) {
                letters.add(letter == FrenchGame.JOKER ? label(R.string.practice_joker) : Character.toString(letter));
            }
            return String.join(" · ", letters);
        }

        private String score(FrenchGame.Preview preview) {
            return label(R.string.practice_preview_score, String.join(" + ", preview.words()), points(preview.score()));
        }

        private void renderBoard(TwoMoveChallenge.Position state, List<FrenchGame.Tile> pending,
                                 List<FrenchGame.Tile> highlighted, int animate, String suffix) {
            char[] cells = state.board();
            boolean[] blanks = state.blanks();
            board.update(cells, blanks, pending, highlighted, animate);
            List<String> labels = new ArrayList<>();
            for (int square = 0; square < cells.length; square++) {
                if (cells[square] != 0) labels.add(cellLabel(cells[square], blanks[square], square));
            }
            for (FrenchGame.Tile tile : pending) labels.add(cellLabel(tile.letter(), tile.blank(), tile.position()));
            String description = label(R.string.two_move_board, String.join(", ", labels) + ".");
            board.setContentDescription(suffix.isEmpty() ? description : description + " " + suffix);
        }

        private String cellLabel(char letter, boolean blank, int square) {
            return label(blank ? R.string.practice_board_blank : R.string.practice_board_tile, letter,
                    (char) ('A' + square / FrenchGame.SIZE), square % FrenchGame.SIZE + 1);
        }

        private void show(View view, boolean visible) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
}
