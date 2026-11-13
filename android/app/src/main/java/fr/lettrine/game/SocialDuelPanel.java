package fr.lettrine.game;

import android.app.AlertDialog;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

final class SocialDuelPanel {
    private final ViewKit ui;
    private final SocialClient client;
    private final Runnable back;
    private final String duelId;
    private final LinearLayout root;
    private final TextView notice;
    private PracticePanel practice;

    SocialDuelPanel(ViewKit ui, SocialClient client, JSONObject duel, Lexicon lexicon, Runnable back) {
        this.ui = ui;
        this.client = client;
        this.back = back;
        duelId = duel.optString("id");
        root = ui.column();
        notice = ui.body("");
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        notice.setVisibility(View.GONE);
        root.addView(notice);

        if (!"your_turn".equals(duel.optString("status"))) {
            showResult(duel);
            return;
        }
        JSONObject puzzle = duel.optJSONObject("puzzle");
        PracticePosition position = compatiblePosition(puzzle, duel.optString("date"), lexicon);
        if (position == null) {
            notice.setText(R.string.social_duel_incompatible);
            notice.setVisibility(View.VISIBLE);
            backButton();
            return;
        }
        JSONObject opponent = duel.optJSONObject("opponent");
        if (opponent != null) {
            root.addView(ui.title(opponent.optString("nickname"), 26));
            ui.gap(root, 6);
        }
        root.addView(ui.body(label(isMirror(duel) ? R.string.social_mirror_goal : R.string.social_duel_goal)));
        ui.gap(root, 10);
        practice = new PracticePanel(ui, position, lexicon, this::confirm);
        root.addView(practice.view());
    }

    View view() { return root; }

    static boolean isMirror(JSONObject duel) { return "mirror".equals(duel.optString("kind")); }

    private static PracticePosition compatiblePosition(JSONObject puzzle, String fallbackDate, Lexicon lexicon) {
        DailyChallenge catalog = catalogChallenge(puzzle, fallbackDate, lexicon);
        if (catalog == null) return null;
        if (!catalog.openingWord().equals(puzzle.optString("opening_word"))
                || catalog.openingStart() != puzzle.optInt("opening_start", -1)
                || catalog.openingEnd() != puzzle.optInt("opening_end", -1)) return null;
        return exactRackPosition(catalog, puzzle.optString("rack"), puzzle.optString("identity"), lexicon);
    }

    static PracticePosition exactRackPosition(DailyChallenge catalog, String rack, String identity, Lexicon lexicon) {
        if (rack.length() != FrenchGame.RACK_SIZE) return null;
        PracticePosition base = catalog.position();
        char[] board = base.board();
        int[] used = new int[26];
        for (char letter : board) if (letter != 0) used[letter - 'A']++;
        List<Character> letters = new ArrayList<>(FrenchGame.RACK_SIZE);
        for (char letter : rack.toCharArray()) {
            if (letter < 'A' || letter > 'Z' || ++used[letter - 'A'] > lexicon.language.tileCount(letter)) return null;
            letters.add(letter);
        }
        // The shared duel UI only evaluates the player's move, with the submitted rack indices.
        PracticePosition position = new PracticePosition(board, base.blanks(), letters, null, null);
        return ChallengeLink.positionIdentity(position, lexicon).equals(identity) ? position : null;
    }

    static DailyChallenge compatibleChallenge(JSONObject puzzle, String fallbackDate, Lexicon lexicon) {
        DailyChallenge challenge = catalogChallenge(puzzle, fallbackDate, lexicon);
        if (challenge == null) return null;
        return ChallengeLink.create(challenge, lexicon, 0).identity().equals(puzzle.optString("identity"))
                ? challenge : null;
    }

    private static DailyChallenge catalogChallenge(JSONObject puzzle, String fallbackDate, Lexicon lexicon) {
        if (puzzle == null || lexicon == null) return null;
        try {
            LocalDate date = LocalDate.parse(puzzle.optString("date", fallbackDate));
            DailyChallenge challenge = DailyChallenge.forDate(date, lexicon);
            if (challenge == null) return null;
            String identity = ChallengeLink.create(challenge, lexicon, 0).identity();
            return identity.equals(puzzle.optString("catalog_identity", puzzle.optString("identity")))
                    ? challenge : null;
        } catch (DateTimeParseException | IllegalArgumentException exception) {
            return null;
        }
    }

    private void confirm(List<FrenchGame.Tile> tiles) {
        new AlertDialog.Builder(ui.context).setTitle(R.string.social_duel_confirm)
                .setMessage(R.string.social_duel_confirm_copy).setNegativeButton(R.string.social_cancel, null)
                .setPositiveButton(R.string.social_submit, (dialog, which) -> submit(tiles)).show();
    }

    private void submit(List<FrenchGame.Tile> tiles) {
        notice.setVisibility(View.GONE);
        practice.setSubmitting(true);
        client.play(duelId, tiles, (result, error) -> {
            JSONObject duel = result == null ? null : result.optJSONObject("duel");
            if (duel == null) {
                practice.setSubmitting(false);
                notice.setText(error == null ? label(R.string.social_network_error) : error);
                notice.setVisibility(View.VISIBLE);
                notice.requestRectangleOnScreen(new android.graphics.Rect(0, 0, notice.getWidth(), ui.dp(48)));
            } else {
                showResult(duel);
            }
        });
    }

    private void showResult(JSONObject duel) {
        root.removeAllViews();
        LinearLayout result = ui.card();
        int score = duel.optInt("score");
        if (duel.optString("status").equals("finished")) {
            int opponentScore = duel.optInt("opponent_score");
            int outcome = score == opponentScore ? R.string.social_duel_draw
                    : score > opponentScore ? R.string.social_duel_win : R.string.social_duel_loss;
            result.addView(ui.title(label(R.string.social_duel_result, label(outcome), score, opponentScore), 25));
            if (isMirror(duel) && !duel.isNull("original_score") && !duel.isNull("original_opponent_score")) {
                ui.gap(result, 10);
                result.addView(ui.body(label(R.string.social_mirror_total,
                        score + duel.optInt("original_score"), opponentScore + duel.optInt("original_opponent_score"))));
            }
        } else {
            result.addView(ui.title(label(R.string.social_duel_your_result, score), 25));
            ui.gap(result, 6);
            result.addView(ui.body(label(R.string.social_waiting)));
        }
        root.addView(result);
        backButton();
    }

    private void backButton() {
        ui.gap(root, 10);
        root.addView(ui.button(label(R.string.social_back_to_duels), true, back));
    }

    private String label(int resource, Object... values) { return ui.context.getString(resource, values); }
}
