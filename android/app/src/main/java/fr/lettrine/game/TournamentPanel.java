package fr.lettrine.game;

import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Consumer;

final class TournamentPanel {
    private static final int FOREST = 0xff17352a;
    private static final int DARK = 0xff0e251c;
    private static final int IVORY = 0xfffff6df;
    private static final int GOLD = 0xffe8c88d;
    private static final int SAGE = 0xffd1e2c7;
    private static final long LAST_SUPPORTED_SECOND = 253_402_300_799L;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("EEEE d MMMM 'à' HH:mm", Locale.FRENCH);

    private final ViewKit ui;
    private final SocialClient client;
    private final CardStyleStore cardStyles;
    private final Consumer<JSONObject> openRound;
    private final LinearLayout root;
    private final TextView notice;
    private final TextView refresh;
    private final LinearLayout body;

    TournamentPanel(ViewKit ui, SocialClient client, Consumer<JSONObject> openRound) {
        this.ui = ui;
        this.client = client;
        cardStyles = new CardStyleStore(ui.context);
        this.openRound = openRound;
        root = ui.column();
        root.addView(hero(ui));
        ui.gap(root, 14);
        notice = ui.body("");
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(notice);
        body = ui.column();
        root.addView(body);
        ui.gap(root, 7);
        refresh = ui.textButton(label(R.string.social_refresh), this::load);
        refresh.setMinHeight(ui.dp(48));
        root.addView(refresh);
        load();
    }

    View view() { return root; }

    static View entry(ViewKit ui, Runnable action) {
        LinearLayout card = ui.row();
        card.setBackground(ui.touch(FOREST, 19, 0));
        ui.inset(card, 12, 11);
        ImageView art = art(ui);
        art.setBackground(ui.shape(DARK, 12));
        art.setClipToOutline(true);
        card.addView(art, new LinearLayout.LayoutParams(ui.dp(57), ui.dp(57)));
        ui.space(card, 11);
        LinearLayout copy = ui.column();
        copy.addView(ui.text(ui.context.getString(R.string.tournament_title), 20, IVORY, ui.serif));
        ui.gap(copy, 4);
        copy.addView(ui.text(ui.context.getString(R.string.tournament_entry_detail), 12, SAGE, ui.medium));
        ui.weight(card, copy);
        ui.space(card, 5);
        TextView arrow = ui.text("›", 25, GOLD, ui.serif);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(arrow);
        card.setOnClickListener(view -> action.run());
        return card;
    }

    private static View hero(ViewKit ui) {
        LinearLayout strip = ui.row();
        strip.addView(new View(ui.context), new LinearLayout.LayoutParams(0, -1, 59));
        ImageView photo = art(ui);
        photo.setForeground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{FOREST, 0x4017352a, 0x0017352a}));
        strip.addView(photo, new LinearLayout.LayoutParams(0, -1, 41));
        FrameLayout card = new FrameLayout(ui.context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, heightSpec);
                strip.measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
            }
        };
        card.setBackground(ui.shape(FOREST, 23));
        card.setClipToOutline(true);
        card.setMinimumHeight(ui.dp(176));
        card.addView(strip, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout row = ui.row();
        LinearLayout copy = ui.column();
        copy.setPadding(ui.dp(19), ui.dp(23), ui.dp(3), ui.dp(24));
        TextView heading = ui.text(ui.context.getString(R.string.tournament_title), 32, IVORY, ui.serif);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        copy.addView(heading);
        ui.gap(copy, 10);
        TextView summary = ui.text(ui.context.getString(R.string.tournament_summary), 14, SAGE, ui.regular);
        summary.setLineSpacing(ui.dp(3), 1);
        copy.addView(summary);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 69));
        row.addView(new View(ui.context), new LinearLayout.LayoutParams(0, 1, 31));
        card.addView(row, new FrameLayout.LayoutParams(-1, -2));
        return card;
    }

    private static ImageView art(ViewKit ui) {
        ImageView view = new ImageView(ui.context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                setMeasuredDimension(MeasureSpec.getSize(widthSpec), resolveSize(0, heightSpec));
            }
        };
        view.setImageResource(R.drawable.tournament_art);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private void load() {
        showNotice(label(R.string.tournament_loading));
        ui.enabled(refresh, false);
        client.tournament((result, error) -> {
            ui.enabled(refresh, true);
            if (error != null) showNotice(error);
            else if (result == null || !render(result)) showNotice(label(R.string.tournament_response_error));
            else showNotice(null);
        });
    }

    private void showNotice(String text) {
        notice.setText(text);
        notice.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    private boolean render(JSONObject event) {
        String status = event.optString("status");
        boolean open = "open".equals(status);
        boolean upcoming = "upcoming".equals(status);
        if (!open && !upcoming && !"closed".equals(status)) return false;
        JSONArray slots = event.optJSONArray("slots");
        long eventId = event.optLong("id", -1);
        long starts = event.optLong("starts_at");
        long ends = event.optLong("ends_at");
        long next = event.optLong("next_starts_at");
        int completed = event.optInt("completed");
        if (eventId < 0 || starts <= 0 || ends <= starts || ends > LAST_SUPPORTED_SECOND
                || next < starts || next > LAST_SUPPORTED_SECOND || completed < 0 || completed > 3
                || (!upcoming && (slots == null || slots.length() != 3))) return false;

        cardStyles.recordTournament(event);
        body.removeAllViews();
        LinearLayout scorecard = ui.card();
        String deadline = open ? label(R.string.tournament_open, time(ends)) : upcoming
                ? label(R.string.tournament_upcoming, time(starts)) : label(R.string.tournament_closed);
        scorecard.addView(ui.text(deadline, 14, ui.palette.accent(), ui.medium));
        if (!open && !upcoming) {
            ui.gap(scorecard, 4);
            scorecard.addView(ui.body(label(R.string.tournament_next, time(next))));
        }
        ui.gap(scorecard, 11);
        LinearLayout progress = ui.row();
        ui.weight(progress, ui.title(progress(ui, completed), 22));
        ui.space(progress, 6);
        String total = points(ui, event.optInt("total"));
        TextView tally = ui.text(total, 15, ui.palette.accent(), ui.medium);
        tally.setContentDescription(label(R.string.tournament_total, total));
        progress.addView(tally);
        scorecard.addView(progress);
        ui.gap(scorecard, 11);
        scorecard.addView(progressRail(completed));
        ui.gap(scorecard, 9);

        for (int number = 1; number <= 3; number++) {
            JSONObject slot = slot(slots, number);
            scorecard.addView(roundRow(eventId, number, slot, open, upcoming));
            if (number < 3) {
                View rule = new View(ui.context);
                rule.setBackgroundColor(ui.palette.line());
                LinearLayout.LayoutParams edge = new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(1)));
                edge.setMarginStart(ui.dp(41));
                scorecard.addView(rule, edge);
            }
        }
        body.addView(scorecard);
        ui.gap(body, 10);
        body.addView(rewardCard(ui, cardStyles));
        ui.gap(body, 17);
        TextView ranking = ui.title(label(R.string.tournament_ranking), 26);
        if (Build.VERSION.SDK_INT >= 28) ranking.setAccessibilityHeading(true);
        body.addView(ranking);
        ui.gap(body, 9);
        showRanking(event.optJSONArray("ranking"));
        return true;
    }

    private View progressRail(int completed) {
        LinearLayout rail = ui.row();
        rail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        for (int i = 0; i < 3; i++) {
            if (i > 0) ui.space(rail, 5);
            View segment = new View(ui.context);
            segment.setBackground(ui.shape(i < completed ? ui.palette.accent() : ui.palette.ordinary(), 8));
            rail.addView(segment, new LinearLayout.LayoutParams(0, ui.dp(6), 1));
        }
        return rail;
    }

    private View roundRow(long eventId, int number, JSONObject slot, boolean open, boolean upcoming) {
        LinearLayout header = ui.row();
        ui.inset(header, 0, 10);
        header.setMinimumHeight(ui.dp(61));
        JSONObject puzzle = slot == null ? null : slot.optJSONObject("puzzle");
        boolean scored = slot != null && !slot.isNull("score");
        boolean playable = open && !scored && puzzle != null;
        TextView numeral = ui.text(scored ? "✓" : Integer.toString(number), 17,
                playable || scored ? ui.palette.ink() : ui.palette.muted(), ui.serif);
        numeral.setGravity(Gravity.CENTER);
        numeral.setBackground(ui.shape(ui.palette.ordinary(), 10));
        numeral.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(numeral, new LinearLayout.LayoutParams(ui.dp(31), ui.dp(31)));
        ui.space(header, 10);
        LinearLayout copy = ui.column();
        copy.addView(ui.text(label(playable ? R.string.tournament_round_action : R.string.tournament_round, number),
                16, ui.palette.ink(), ui.medium));
        String detail = upcoming ? label(R.string.tournament_round_upcoming) : scored
                ? label(R.string.tournament_round_done) : open ? (puzzle == null ? "" : puzzle.optString("opening_word"))
                : label(R.string.tournament_round_missed);
        if (!detail.isEmpty()) {
            ui.gap(copy, 3);
            copy.addView(ui.body(detail));
        }
        ui.weight(header, copy);
        ui.space(header, 6);
        if (scored) header.addView(ui.text(points(ui, slot.optInt("score")), 14, ui.palette.accent(), ui.medium));
        if (playable) {
            TextView arrow = ui.text("›", 23, ui.palette.accent(), ui.serif);
            arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            header.addView(arrow);
            header.setBackground(ui.touch(ui.palette.paper(), 10, 0));
            header.setOnClickListener(view -> openRound.accept(
                    MatchCodec.object("event", eventId, "number", number, "puzzle", puzzle)));
        }
        return header;
    }

    private void showRanking(JSONArray ranking) {
        if (ranking == null || ranking.length() == 0) {
            body.addView(ui.body(label(R.string.tournament_ranking_empty)));
            return;
        }
        LinearLayout card = ui.card();
        for (int i = 0; i < ranking.length(); i++) {
            JSONObject row = ranking.optJSONObject(i);
            if (row == null) continue;
            boolean you = row.optBoolean("is_you");
            String name = row.optString("nickname");
            LinearLayout rank = ui.row();
            ui.inset(rank, 8, 9);
            if (you) rank.setBackground(ui.shape(ui.palette.ordinary(), 10));
            ui.weight(rank, ui.text(label(R.string.tournament_ranking_place, row.optInt("rank", i + 1),
                    you ? label(R.string.tournament_ranking_you, name) : name), 15, ui.palette.ink(), ui.medium));
            ui.space(rank, 8);
            rank.addView(ui.text(points(ui, row.optInt("score")), 14, ui.palette.accent(), ui.medium));
            card.addView(rank);
        }
        body.addView(card);
    }

    static JSONObject slot(JSONArray slots, int number) {
        if (slots == null) return null;
        for (int i = 0; i < slots.length(); i++) {
            JSONObject slot = slots.optJSONObject(i);
            if (slot != null && slot.optInt("number") == number) return slot;
        }
        return null;
    }

    static String points(ViewKit ui, int points) {
        return ui.context.getResources().getQuantityString(R.plurals.practice_points, points, points);
    }

    static String progress(ViewKit ui, int completed) {
        return ui.context.getResources().getQuantityString(R.plurals.tournament_progress,
                completed == 1 ? 1 : 2, completed);
    }

    static View rewardCard(ViewKit ui, CardStyleStore cardStyles) {
        CardStyleStore.Style style = CardStyleStore.Style.NOCTURNE;
        boolean unlocked = cardStyles.has(style);
        LinearLayout banner = ui.row();
        banner.setBackground(ui.shape(ui.palette.ordinary(), 15));
        ui.inset(banner, 10, 10);
        ImageView preview = ScoreCard.preview(ui, style);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        banner.addView(preview, new LinearLayout.LayoutParams(ui.dp(65), ui.dp(65)));
        ui.space(banner, 12);
        LinearLayout copy = ui.column();
        TextView status = ui.text(ui.context.getString(unlocked ? R.string.card_style_reward_unlocked
                : R.string.card_style_tournament_reward), 12, ui.palette.accent(), ui.medium);
        copy.addView(status);
        ui.gap(copy, 4);
        copy.addView(ui.title(ui.context.getString(style.label), 21));
        ui.gap(copy, 5);
        int detail = !unlocked ? R.string.card_style_unlock_requirement
                : cardStyles.selected() == style ? R.string.card_style_reward_in_use : R.string.card_style_reward_collection;
        copy.addView(ui.body(ui.context.getString(detail)));
        ui.weight(banner, copy);
        return banner;
    }

    private static String time(long seconds) {
        return TIME.format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()));
    }

    private String label(int resource, Object... values) { return ui.context.getString(resource, values); }
}
