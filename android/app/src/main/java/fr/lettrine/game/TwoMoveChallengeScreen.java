package fr.lettrine.game;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

final class TwoMoveChallengeScreen {
    private static final List<String> STARS = List.of("☆☆☆", "★☆☆", "★★☆", "★★★");
    private final ViewKit ui;
    private final Lexicon lexicon;
    private final Lexicon legacyLexicon;
    private final LinearLayout panel;
    private final List<TwoMoveChallenge.Puzzle> catalog;
    private final SharedPreferences progress;
    private final SharedPreferences drafts;
    private final Consumer<String> baseCompletion;
    private final CircuitPlay circuit;
    private final CampaignPlay campaign;
    private PuzzlePanel active;

    record CircuitPlay(String savedDraft, IntPredicate complete, Consumer<String> saveDraft,
                       Runnable next, boolean lastRound) {}

    record CampaignPlay(NabCampaign.Stage stage, NabCampaignProgress progress, Runnable next,
                        Runnable map, Runnable workshop) {}

    TwoMoveChallengeScreen(ViewKit ui, Lexicon lexicon, Lexicon legacyLexicon, Consumer<String> baseCompletion) {
        this(ui, lexicon, legacyLexicon, null, null, baseCompletion);
        showCatalog();
    }

    TwoMoveChallengeScreen(ViewKit ui, Lexicon lexicon, TwoMoveChallenge.Puzzle puzzle, CircuitPlay circuit) {
        this(ui, lexicon, lexicon, circuit, null, null);
        open(puzzle);
    }

    TwoMoveChallengeScreen(ViewKit ui, Lexicon lexicon, CampaignPlay campaign) {
        this(ui, lexicon, lexicon, null, campaign, null);
        open(campaign.stage().puzzle());
    }

    private TwoMoveChallengeScreen(ViewKit ui, Lexicon lexicon, Lexicon legacyLexicon,
                                   CircuitPlay circuit, CampaignPlay campaign, Consumer<String> baseCompletion) {
        this.ui = ui;
        this.lexicon = lexicon;
        this.legacyLexicon = legacyLexicon;
        this.baseCompletion = baseCompletion;
        this.circuit = circuit;
        this.campaign = campaign;
        panel = ui.column();
        boolean freePractice = circuit == null && campaign == null;
        catalog = freePractice ? TwoMoveChallenge.catalog(lexicon) : List.of();
        progress = ui.context.getSharedPreferences(lexicon.language.storageName("two_move_challenges"), Context.MODE_PRIVATE);
        drafts = freePractice ? ui.context.getSharedPreferences(lexicon.language.storageName("two_move_draft"),
                Context.MODE_PRIVATE) : null;
    }

    View view() { return panel; }

    boolean goBack() {
        if (active == null) return false;
        if (active.replay >= 0) active.leaveReplay();
        else if (circuit != null || campaign != null) return false;
        else showCatalog();
        return true;
    }

    private void showCatalog() {
        active = null;
        panel.removeAllViews();
        LinearLayout intro = ui.column();
        intro.addView(ui.title(label(R.string.two_move_title), 27));
        ui.gap(intro, 8);
        TextView rules = ui.body(label(catalog.isEmpty() ? R.string.two_move_unavailable : R.string.two_move_intro));
        intro.addView(rules);
        if (!catalog.isEmpty()) {
            int completed = 0;
            for (TwoMoveChallenge.Puzzle puzzle : catalog) {
                if (progress.contains(puzzle.id())) completed++;
            }
            ui.gap(intro, 9);
            intro.addView(ui.text(label(R.string.two_move_catalog_progress, completed, catalog.size()),
                    13, ui.palette.accent(), ui.medium));
        }
        panel.addView(ui.illustratedCard(R.drawable.nab_arcade_think, 85, intro, ui.palette.tripleLetter()));
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
        Lexicon dictionary = lexicon;
        if (drafts != null) {
            String dictionaryKey = "lexicon/" + puzzle.id();
            if ((drafts.contains(puzzle.id()) || drafts.contains(dictionaryKey))
                    && !lexicon.hash.equals(drafts.getString(dictionaryKey, ""))) dictionary = legacyLexicon;
        }
        active = new PuzzlePanel(puzzle, dictionary);
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
        private final Lexicon lexicon;
        private final LinearLayout layout = ui.column();
        private final List<FrenchGame.Tile> staged = new ArrayList<>();
        private final List<TextView> rackButtons = new ArrayList<>();
        private final TextView phase;
        private final TextView objective;
        private final NabView portrait;
        private final BoardView board;
        private final boolean compact;
        private final TextView feedback;
        private final TextView definition;
        private final TextView submit;
        private final TextView clear;
        private final TextView edit;
        private final TextView hint;
        private final TextView replayToggle;
        private final TextView replayStep;
        private final TextView replayRival;
        private final TextView retry;
        private final TextView next;
        private final LinearLayout campaignResult;
        private final TextView campaignStatus;
        private final TextView scoreBreakdown;
        private final TextView workshopButton;
        private final LinearLayout nabCard;
        private final TextView nabText;
        private final LinearLayout rackRow;
        private final RackDrop rackDrop;
        private final LinearLayout playRow;
        private TwoMoveChallenge.Position position;
        private TwoMoveChallenge.Step first;
        private TwoMoveChallenge.Step second;
        private TwoMoveChallenge.Step finish;
        private ValueAnimator scoreAnimation;
        private int earnedStars;
        private int earnedWorkshop;
        private int selected = -1;
        private int replay = -1;
        private boolean rivalReplay;
        private boolean hintVisible;
        private boolean blocked;

        PuzzlePanel(TwoMoveChallenge.Puzzle puzzle, Lexicon lexicon) {
            this.puzzle = puzzle;
            this.lexicon = lexicon;
            position = puzzle.initial();
            var config = ui.context.getResources().getConfiguration();
            compact = config.screenWidthDp < 340 || config.fontScale > 1.3f;
            LinearLayout heading = ui.row();
            heading.setBackground(ui.shape(ui.palette.tripleLetter(), 20, ui.palette.line()));
            ui.inset(heading, 11, 9);
            LinearLayout copy = ui.column();
            copy.addView(ui.title(puzzle.title(), 23));
            ui.gap(copy, 4);
            phase = ui.text("", 15, ui.palette.accent(), ui.medium);
            phase.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            copy.addView(phase);
            ui.weight(heading, copy);
            portrait = ui.mascot(R.drawable.nab_arcade_focus);
            if (!compact) {
                ui.space(heading, 5);
                heading.addView(portrait, new LinearLayout.LayoutParams(ui.dp(50), ui.dp(50)));
            }
            layout.addView(heading);
            objective = ui.body("");
            objective.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            ui.gap(layout, 6);
            layout.addView(objective);
            ui.gap(layout, 8);

            campaignResult = campaign == null ? null : ui.column();
            campaignStatus = campaign == null ? null : ui.text("", 18, ui.palette.ink(), ui.display);
            scoreBreakdown = campaign == null ? null : ui.body("");
            workshopButton = campaign == null ? null
                    : ui.button(label(R.string.atelier_play_visit), false, campaign.workshop());
            if (campaign != null) {
                campaignStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                campaignResult.setBackground(ui.shape(ui.palette.tripleLetter(), 18, ui.palette.line()));
                ui.inset(campaignResult, 12, 10);
                campaignResult.addView(campaignStatus);
                ui.gap(campaignResult, 4);
                campaignResult.addView(scoreBreakdown);
                ui.gap(campaignResult, 7);
                campaignResult.addView(workshopButton);
                layout.addView(campaignResult);
                ui.gap(layout, 6);
            }
            board = new BoardView(ui, this);
            layout.addView(board, new LinearLayout.LayoutParams(-1, -2));
            ui.gap(layout, 6);

            LinearLayout tools = ui.row();
            rackRow = ui.row();
            rackDrop = new RackDrop(ui, rackRow, rackButtons, this::canMoveRackTile, this::reorderRack);
            rackRow.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            rackRow.setBackground(ui.rackBackground(ui.skins.rack(), 13));
            ui.inset(rackRow, 6, 6);
            layout.addView(rackRow);
            ui.gap(layout, 6);
            feedback = ui.text("", 14, ui.palette.ink(), ui.medium);
            ui.inset(feedback, 9, 8);
            feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            if (compact) tools.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            else {
                ui.weight(tools, feedback);
                ui.space(tools, 5);
            }
            tools.addView(zoomControls());
            layout.addView(tools);
            if (compact) {
                ui.gap(layout, 5);
                layout.addView(feedback);
            }
            definition = DefinitionAction.button(ui);
            layout.addView(definition);
            ui.gap(layout, 6);

            playRow = compact ? ui.column() : ui.row();
            submit = ui.button("", true, this::submit);
            clear = ui.textButton(label(R.string.practice_clear), this::clear);
            if (compact) {
                playRow.addView(submit);
                ui.gap(playRow, 5);
            } else {
                ui.weight(playRow, submit);
                ui.space(playRow, 7);
            }
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
            LinearLayout advice = ui.column();
            nabText = ui.body("");
            nabText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            advice.addView(nabText);
            nabCard = ui.illustratedCard(R.drawable.nab_arcade_think, 44, advice, ui.palette.ordinary());
            layout.addView(nabCard);
            replayStep = ui.button("", true, () -> showReplay(1 - replay));
            layout.addView(replayStep);
            replayToggle = ui.textButton("", this::toggleReplay);
            layout.addView(replayToggle);
            replayRival = campaign == null || !campaign.stage().rival() ? null
                    : ui.textButton(label(R.string.atelier_play_replay_rival), () -> {
                        rivalReplay = true;
                        showReplay(0);
                    });
            if (replayRival != null) layout.addView(replayRival);
            retry = ui.textButton(label(R.string.two_move_retry), this::restart);
            if (campaign == null) layout.addView(retry);
            if (campaign != null) {
                boolean last = campaign.stage().number() == NabCampaign.STAGE_COUNT;
                next = ui.button(last ? label(R.string.atelier_play_map)
                        : label(R.string.atelier_home_continue, campaign.stage().number() + 1), true,
                        last ? campaign.map() : campaign.next());
                campaignResult.addView(next);
                campaignResult.addView(retry);
            } else if (circuit == null) {
                next = null;
                layout.addView(ui.textButton(label(R.string.two_move_catalog), TwoMoveChallengeScreen.this::showCatalog));
            } else {
                next = ui.button(label(circuit.lastRound() ? R.string.bonus_see_results : R.string.bonus_next_round),
                        true, circuit.next());
                layout.addView(next);
            }
            restoreDraft();
            blocked = first != null && !TwoMoveChallenge.hasSecondMove(position, lexicon);

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
            TextView button = ui.textButton(text, action);
            button.setMinWidth(ui.dp(40));
            button.setContentDescription(label(description));
            ui.styleKey(button, false);
            return button;
        }

        private void fitBoard() {
            // A zoomed board can consume vertical scroll gestures over a small viewport.
            // Keep the controls reachable and leave zoom changes to the player here.
            if (compact) return;
            List<TwoMoveChallenge.Step> moves = replayMoves();
            TwoMoveChallenge.Position framed = replay >= 0 ? moves.get(moves.size() - 1).after()
                    : campaign != null && finish != null ? position : puzzle.solution().get(puzzle.solution().size() - 1).after();
            char[] cells = framed.board();
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

        private boolean playing() { return replay < 0 && finish == null; }

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
                TwoMoveChallenge.Position original = position;
                new TurnMenu(ui, R.string.practice_joker_choice).chooseJoker(chosen -> {
                    if (position == original && canStage(square, rackIndex)) stage(square, rackIndex, chosen, true);
                });
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
            ui.tick(board);
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
            TwoMoveChallenge.Step step = playMove(staged);
            if (!step.preview().valid()) return;
            boolean finalMove = first != null || campaign != null && campaign.stage().turns() == 1;
            if (finalMove && circuit != null && !circuit.complete().test(step.after().totalScore())) {
                Toast.makeText(ui.context, R.string.bonus_save_error, Toast.LENGTH_SHORT).show();
                return;
            }
            if (finalMove && campaign != null && !recordCampaign(step)) return;
            position = step.after();
            if (first == null) {
                first = step;
                blocked = !finalMove && !TwoMoveChallenge.hasSecondMove(position, lexicon);
            } else {
                second = step;
                if (drafts != null) {
                    if (position.totalScore() > progress.getInt(puzzle.id(), -1)) {
                        progress.edit().putInt(puzzle.id(), position.totalScore()).apply();
                    }
                    baseCompletion.accept(puzzle.id());
                    removeDraft();
                }
            }
            if (finalMove) finish = step;
            if (finalMove && (campaign == null || earnedStars > 0) && ui.celebrations) ui.audio.celebrate();
            else ui.audio.played();
            hintVisible = false;
            staged.clear();
            selected = -1;
            if (!finalMove) saveDraft();
            buildRack();
            render(-1);
            if (finalMove && campaign != null) {
                fitBoard();
                animateCampaignScore();
            }
            if (finalMove || blocked) scrollToStart();
        }

        private TwoMoveChallenge.Step playMove(List<FrenchGame.Tile> tiles) {
            return campaign == null ? TwoMoveChallenge.play(position, tiles, lexicon)
                    : NabCampaign.play(campaign.stage(), position, tiles, lexicon);
        }

        private boolean recordCampaign(TwoMoveChallenge.Step step) {
            NabCampaign.Stage stage = campaign.stage();
            earnedStars = NabCampaign.qualifies(stage, step) ? NabCampaign.stars(stage, step.after()) : 0;
            if (earnedStars == 0) {
                campaign.progress().saveDraft(stage.number(), lexicon.hash, null);
                return true;
            }
            int previousRoom = campaign.progress().snapshot().workshopLevel();
            try {
                NabCampaignProgress.Snapshot saved = campaign.progress().complete(stage, earnedStars,
                        step.after().totalScore());
                earnedWorkshop = saved.workshopLevel() > previousRoom ? saved.workshopLevel() : 0;
                return true;
            } catch (IOException exception) {
                Toast.makeText(ui.context, R.string.atelier_play_save_error, Toast.LENGTH_SHORT).show();
                return false;
            }
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
            if (scoreAnimation != null) scoreAnimation.cancel();
            if (drafts != null && finish != null) {
                open(puzzle);
                return;
            }
            position = puzzle.initial();
            first = null;
            second = null;
            finish = null;
            earnedStars = 0;
            earnedWorkshop = 0;
            replay = -1;
            rivalReplay = false;
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
                drafts.edit().remove(puzzle.id()).putString("lexicon/" + puzzle.id(), lexicon.hash).apply();
                return;
            }
            JSONArray moves = new JSONArray();
            moves.put(MatchCodec.tiles(first == null ? List.of() : first.tiles(), true));
            moves.put(MatchCodec.tiles(staged, true));
            String saved = moves.toString();
            if (campaign != null) campaign.progress().saveDraft(campaign.stage().number(), lexicon.hash,
                    first == null && staged.isEmpty() ? null : saved);
            else if (drafts == null) circuit.saveDraft().accept(saved);
            else drafts.edit().putString(puzzle.id(), saved)
                    .putString("lexicon/" + puzzle.id(), lexicon.hash).apply();
        }

        private void removeDraft() {
            drafts.edit().remove(puzzle.id()).remove("lexicon/" + puzzle.id()).apply();
        }

        private void restoreDraft() {
            String saved = campaign != null ? campaign.progress().draft(campaign.stage().number(), lexicon.hash)
                    : drafts == null ? circuit.savedDraft() : drafts.getString(puzzle.id(), "");
            if (saved == null || saved.isEmpty()) return;
            try {
                JSONArray moves = new JSONArray(saved);
                if (moves.length() != 2) return;
                List<FrenchGame.Tile> opening = MatchCodec.readTiles(moves.getJSONArray(0), true);
                List<FrenchGame.Tile> pending = MatchCodec.readTiles(moves.getJSONArray(1), true);
                if (!opening.isEmpty() && (campaign == null || campaign.stage().turns() == 2)) {
                    TwoMoveChallenge.Step played = playMove(opening);
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
            if (replay < 0) {
                rivalReplay = false;
                showReplay(0);
            }
            else leaveReplay();
        }

        private List<TwoMoveChallenge.Step> replayMoves() {
            return rivalReplay ? campaign.stage().rivalMoves() : puzzle.solution();
        }

        private void showReplay(int index) {
            replay = index;
            List<FrenchGame.Tile> tiles = replayMoves().get(replay).tiles();
            render(tiles.get(0).position());
            fitBoard();
            scrollToStart();
        }

        private void leaveReplay() {
            replay = -1;
            rivalReplay = false;
            render(-1);
            if (campaign != null) fitBoard();
            scrollToStart();
        }

        private void render(int animate) {
            boolean reviewing = replay >= 0;
            boolean playing = playing();
            boolean showHint = playing && first == null;
            if (campaignResult != null) show(campaignResult, !playing && !reviewing);
            show(rackRow, playing);
            show(feedback, !playing || !blocked);
            show(playRow, playing && !blocked);
            show(edit, playing && first != null);
            show(hint, showHint);
            show(nabCard, reviewing || showHint && hintVisible);
            show(replayStep, reviewing && replayMoves().size() > 1);
            show(replayToggle, reviewing || !playing || showHint && hintVisible);
            if (replayRival != null) show(replayRival, !playing && !reviewing);
            show(retry, circuit == null && !playing && !reviewing);
            if (next != null) show(next, !playing && !reviewing && (campaign == null || earnedStars > 0));
            hint.setText(hintVisible ? R.string.two_move_hint_hide : R.string.two_move_hint);
            replayToggle.setText(reviewing ? R.string.two_move_yours : campaign == null ? R.string.two_move_replay
                    : R.string.atelier_play_replay_example);
            board.guide(showHint && hintVisible ? puzzle.hintPosition() : -1);
            portrait.pose(reviewing || showHint && hintVisible ? R.drawable.nab_arcade_think
                    : blocked || campaign != null && !playing && earnedStars == 0 ? R.drawable.nab_arcade_sad
                    : playing ? R.drawable.nab_arcade_focus : R.drawable.nab_arcade_cheer);

            if (reviewing) {
                renderReplay(animate);
                return;
            }
            if (!playing) {
                List<String> words = new ArrayList<>(first.preview().words());
                if (second != null) words.addAll(second.preview().words());
                DefinitionAction.update(ui, definition, words);
                phase.setText(label(R.string.two_move_result, points(position.totalScore())));
                objective.setText(campaign != null ? puzzle.objective() : label(R.string.two_move_breakdown,
                        points(first.preview().score()), points(second.preview().score())));
                feedback.setText(score(finish.preview()));
                feedback.setBackground(ui.shape(ui.palette.ordinary(), 12));
                renderBoard(position, List.of(), finish.tiles(), -1, "");
                if (campaign != null) renderCampaignResult();
                return;
            }

            DefinitionAction.update(ui, definition, List.of());
            boolean firstMove = first == null;
            String turnLabel = firstMove ? label(R.string.two_move_phase, 1)
                    : label(R.string.two_move_phase, 2) + "  ·  "
                            + label(R.string.two_move_running_total, points(position.totalScore()));
            if (campaign != null && firstMove) {
                NabCampaign.Stage stage = campaign.stage();
                turnLabel = label(R.string.atelier_play_level, stage.number(), NabCampaign.STAGE_COUNT);
                if (stage.rival()) turnLabel += "  ·  " + label(R.string.atelier_play_nab, points(stage.rivalScore()));
            }
            phase.setText(turnLabel);
            objective.setText(firstMove ? puzzle.objective()
                    : label(blocked ? R.string.bonus_no_second_move : R.string.two_move_keep));
            if (campaign != null && !firstMove && !blocked) objective.setText(puzzle.objective());
            submit.setText(campaign != null && campaign.stage().turns() == 1 ? R.string.practice_ready
                    : firstMove ? R.string.two_move_first : R.string.two_move_second);
            TwoMoveChallenge.Step candidate = staged.isEmpty() ? null : playMove(staged);
            FrenchGame.Preview preview = candidate == null ? null : candidate.preview();
            boolean valid = preview != null && preview.valid();
            ui.enabled(submit, valid);
            ui.enabled(clear, !staged.isEmpty() || selected >= 0);
            int status = R.string.practice_status_valid;
            if (valid && campaign != null && candidate.after().moves() == campaign.stage().turns()) {
                status = NabCampaign.qualifies(campaign.stage(), candidate) ? R.string.atelier_play_goal_ready
                        : R.string.atelier_play_goal_short;
            }
            String message = blocked ? "" : preview == null ? label(R.string.two_move_pick) : valid
                    ? label(status) + "\n" + score(preview) : preview.error();
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
            List<TwoMoveChallenge.Step> moves = replayMoves();
            TwoMoveChallenge.Step step = moves.get(replay);
            DefinitionAction.update(ui, definition, step.preview().words());
            phase.setText(label(rivalReplay ? R.string.atelier_play_replay_rival_turn : R.string.two_move_replay_phase,
                    replay + 1));
            objective.setText(label(R.string.two_move_running_total, points(step.after().totalScore())));
            feedback.setText(score(step.preview()));
            feedback.setBackground(ui.shape(ui.palette.ordinary(), 12));
            replayStep.setText(replay == 0 ? R.string.two_move_replay_next : R.string.two_move_replay_previous);
            nabText.setText(rivalReplay ? label(R.string.atelier_play_nab, points(campaign.stage().rivalScore()))
                    : replay == 0 && moves.size() > 1
                    ? label(R.string.two_move_replay_note) + "\n" + rackText(step.after().rack())
                    : label(R.string.two_move_nab_hint, puzzle.explanation()));
            renderBoard(step.after(), List.of(), step.tiles(), animate, "");
        }

        private void renderCampaignResult() {
            String result = earnedStars == 0 ? label(R.string.atelier_play_try_again)
                    : label(R.string.atelier_play_success, STARS.get(earnedStars));
            if (earnedWorkshop > 0) result += "\n" + label(R.string.atelier_play_reward,
                    label(AtelierScreen.rewardName(earnedWorkshop)));
            campaignStatus.setText(result);
            if (earnedStars > 0) campaignStatus.setContentDescription(label(R.string.atelier_play_success,
                    ui.context.getResources().getQuantityString(R.plurals.atelier_play_stars, earnedStars, earnedStars))
                    + (earnedWorkshop == 0 ? "" : ". " + label(R.string.atelier_play_reward,
                            label(AtelierScreen.rewardName(earnedWorkshop)))));
            else campaignStatus.setContentDescription(null);
            show(workshopButton, earnedWorkshop > 0);
            NabCampaign.Stage stage = campaign.stage();
            String breakdown = String.join("  ·  ", campaignWords(finish).stream()
                    .map(word -> label(R.string.atelier_play_word_score, word.word(), word.score())).toList());
            if (stage.rival()) breakdown = label(R.string.atelier_play_versus_score,
                    points(position.totalScore()), points(stage.rivalScore())) + "\n" + breakdown;
            if (finish.tiles().size() == FrenchGame.RACK_SIZE) breakdown += "  ·  " + label(R.string.atelier_play_bingo);
            scoreBreakdown.setText(breakdown);
        }

        private List<FrenchGame.FormedWord> campaignWords(TwoMoveChallenge.Step move) {
            return FrenchGame.recordedWords(move.before().board(), move.before().blanks(), move.tiles(), lexicon.language);
        }

        private void animateCampaignScore() {
            if (!ui.animate || !ui.celebrations || earnedStars == 0) return;
            TwoMoveChallenge.Step played = finish;
            List<FrenchGame.FormedWord> words = campaignWords(played);
            if (words.size() < 2) return;
            int base = played.before().totalScore();
            int[] totals = new int[words.size() + 1];
            totals[0] = base;
            for (int i = 0; i < words.size(); i++) totals[i + 1] = totals[i] + words.get(i).score();
            List<List<Integer>> lines = campaignWordLines(played);
            scoreAnimation = ValueAnimator.ofInt(1, words.size() + 1);
            scoreAnimation.setDuration(190L * words.size());
            scoreAnimation.setInterpolator(new android.view.animation.LinearInterpolator());
            scoreAnimation.addUpdateListener(animation -> {
                if (finish != played || replay >= 0 || !board.isAttachedToWindow()) {
                    animation.cancel();
                    return;
                }
                int count = (Integer) animation.getAnimatedValue();
                boolean finalFrame = count > words.size();
                phase.setText(label(R.string.two_move_result, points(finalFrame ? position.totalScore() : totals[count])));
                board.highlightSquares(!finalFrame && count <= lines.size() ? lines.get(count - 1) : List.of());
            });
            scoreAnimation.start();
        }

        private List<List<Integer>> campaignWordLines(TwoMoveChallenge.Step played) {
            char[] letters = played.after().board();
            List<FrenchGame.Tile> tiles = played.tiles();
            int start = tiles.get(0).position();
            boolean horizontal = tiles.size() == 1 ? wordLine(letters, start, 1).size() > 1
                    : tiles.get(1).position() / FrenchGame.SIZE == start / FrenchGame.SIZE;
            List<List<Integer>> lines = new ArrayList<>();
            lines.add(wordLine(letters, start, horizontal ? 1 : FrenchGame.SIZE));
            for (FrenchGame.Tile tile : tiles) {
                List<Integer> cross = wordLine(letters, tile.position(), horizontal ? FrenchGame.SIZE : 1);
                if (cross.size() > 1) lines.add(cross);
            }
            return lines;
        }

        private List<Integer> wordLine(char[] letters, int square, int direction) {
            int start = square;
            int previous = FrenchGame.step(start, direction, -1);
            while (previous >= 0 && letters[previous] != 0) {
                start = previous;
                previous = FrenchGame.step(start, direction, -1);
            }
            List<Integer> line = new ArrayList<>();
            for (int cell = start; cell >= 0 && letters[cell] != 0; cell = FrenchGame.step(cell, direction, 1)) {
                line.add(cell);
            }
            return line;
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
            if (campaign != null) board.highlightSquares(List.of());
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
