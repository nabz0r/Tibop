package fr.lettrine.game;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

final class EloChart {
    record Point(int before, int after, long finishedAt, int opponentLevel, int opponentElo) {
        static Point from(String mode, boolean assisted, int before, int after,
                          long finishedAt, int opponentLevel, int opponentElo) {
            if (!"solo".equals(mode) || assisted || before <= 0 || after <= 0) return null;
            return new Point(before, after, finishedAt, opponentLevel, opponentElo);
        }
    }

    private EloChart() {}

    static LinearLayout card(ViewKit ui, List<JSONObject> games) {
        LinearLayout card = ui.card();
        card.addView(ui.title("Évolution de l’Elo", 23));
        ui.gap(card, 8);
        List<Point> points = ratedGames(games);
        if (points.isEmpty()) {
            card.addView(ui.body("Terminez une partie cotée contre NAB pour voir votre courbe."));
            return card;
        }
        card.addView(ui.body("Parties cotées contre NAB"));
        ui.gap(card, 6);

        Curve curve = new Curve(ui, points);
        card.addView(curve, new LinearLayout.LayoutParams(-1, ui.dp(142)));
        ui.gap(card, 8);

        LinearLayout controls = ui.row();
        TextView details = ui.body("");
        details.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        TextView previous = ui.textButton("‹", () -> curve.select(curve.selected - 1));
        TextView next = ui.textButton("›", () -> curve.select(curve.selected + 1));
        previous.setContentDescription("Point Elo précédent");
        next.setContentDescription("Point Elo suivant");
        for (TextView button : List.of(previous, next)) {
            button.setMinHeight(ui.dp(48));
            button.setMinWidth(ui.dp(48));
        }
        controls.addView(previous);
        ui.space(controls, 8);
        ui.weight(controls, details);
        ui.space(controls, 8);
        controls.addView(next);
        card.addView(controls);

        curve.onSelection = index -> {
            details.setText(pointLabel(points, index));
            ui.enabled(previous, index > 0);
            ui.enabled(next, index < points.size());
        };
        curve.select(points.size());
        return card;
    }

    private static List<Point> ratedGames(List<JSONObject> games) {
        List<Point> points = new ArrayList<>();
        for (JSONObject game : games) {
            JSONArray levels = game.optJSONArray("levels");
            Point point = Point.from(game.optString("mode"), game.optBoolean("assisted"),
                    game.optInt("elo_before"), game.optInt("elo_after"), game.optLong("finished_at_ms"),
                    levels == null ? 0 : levels.optInt(1), game.optInt("opponent_reference_elo"));
            if (point != null) points.add(point);
        }
        return points;
    }

    private static String pointLabel(List<Point> points, int index) {
        if (index == 0) return number(points.get(0).before) + " Elo\nAvant la première partie cotée";

        Point point = points.get(index - 1);
        long change = (long) point.after - point.before;
        String delta = change == 0 ? " stable" : "  (" + (change > 0 ? "+" : "") + number(change) + ")";
        StringBuilder label = new StringBuilder(number(point.after)).append(" Elo").append(delta)
                .append("\nPartie ").append(number(index)).append(" sur ").append(number(points.size()));
        if (point.finishedAt > 0) {
            label.append(" · ").append(DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.FRANCE)
                    .format(new Date(point.finishedAt)));
        }
        boolean knownLevel = point.opponentLevel > 0 && point.opponentLevel < Bot.NAMES.length;
        if (knownLevel || point.opponentElo > 0) {
            label.append("\nNAB");
            if (knownLevel) label.append(" · ").append(Bot.NAMES[point.opponentLevel]);
            if (point.opponentElo > 0) label.append(" · ").append(number(point.opponentElo)).append(" Elo");
        }
        return label.toString();
    }

    private static String number(long value) {
        return NumberFormat.getIntegerInstance(Locale.FRANCE).format(value);
    }

    private static final class Curve extends View {
        private final ViewKit ui;
        private final int[] values;
        private final long low;
        private final long high;
        private final String lowLabel;
        private final String midLabel;
        private final String highLabel;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float left;
        private float right;
        private float top;
        private float bottom;
        private float touchX;
        private int selected;
        private IntConsumer onSelection;

        Curve(ViewKit ui, List<Point> points) {
            super(ui.context);
            this.ui = ui;
            values = new int[points.size() + 1];
            values[0] = points.get(0).before;
            long min = values[0];
            long max = values[0];
            for (int i = 0; i < points.size(); i++) {
                int elo = points.get(i).after;
                values[i + 1] = elo;
                min = Math.min(min, elo);
                max = Math.max(max, elo);
            }
            long padding = Math.max(20, (max - min) / 10);
            low = Math.max(0, (min - padding) / 10 * 10);
            high = (max + padding + 9) / 10 * 10;
            lowLabel = number(low);
            midLabel = number((low + high) / 2);
            highLabel = number(high);
            paint.setTypeface(ui.medium);
            paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10,
                    getResources().getDisplayMetrics()));
            setClickable(true);
            // The controls below offer the same selection and report each exact value to TalkBack.
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void select(int index) {
            selected = Math.max(0, Math.min(values.length - 1, index));
            onSelection.accept(selected);
            invalidate();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            float labels = Math.max(paint.measureText(highLabel), paint.measureText(lowLabel));
            left = labels + ui.dp(9);
            right = width - ui.dp(7);
            top = paint.getTextSize() + ui.dp(3);
            bottom = height - paint.getTextSize() - ui.dp(8);
            path.rewind();
            path.moveTo(left, y(values[0]));
            for (int i = 1; i < values.length; i++) path.lineTo(x(i), y(values[i]));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (right <= left || bottom <= top) return;
            gridLine(canvas, top, highLabel);
            gridLine(canvas, (top + bottom) / 2, midLabel);
            gridLine(canvas, bottom, lowLabel);

            paint.setColor(ui.palette.accent());
            paint.setStrokeWidth(ui.dp(2.5f));
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);

            float markerX = x(selected);
            float markerY = y(values[selected]);
            paint.setColor(ui.palette.line());
            paint.setStrokeWidth(ui.dp(1));
            canvas.drawLine(markerX, markerY, markerX, bottom, paint);
            paint.setColor(ui.palette.paper());
            canvas.drawCircle(markerX, markerY, ui.dp(6), paint);
            paint.setColor(ui.palette.accent());
            canvas.drawCircle(markerX, markerY, ui.dp(4), paint);

            paint.setColor(ui.palette.muted());
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("Départ", left, getHeight() - ui.dp(2), paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("Dernière partie", right, getHeight() - ui.dp(2), paint);
        }

        private void gridLine(Canvas canvas, float y, String label) {
            paint.setColor(ui.palette.line());
            paint.setStrokeWidth(ui.dp(1));
            canvas.drawLine(left, y, right, y, paint);
            paint.setColor(ui.palette.muted());
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(label, left - ui.dp(6), y - (paint.ascent() + paint.descent()) / 2, paint);
        }

        private float x(int index) { return left + (right - left) * index / (values.length - 1); }
        private float y(int elo) { return bottom - (bottom - top) * (elo - low) / (high - low); }

        @SuppressLint("ClickableViewAccessibility") // View.onTouchEvent dispatches taps to performClick().
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) touchX = event.getX();
            return super.onTouchEvent(event);
        }

        @Override public boolean performClick() {
            super.performClick();
            if (right > left) select(Math.round((touchX - left) * (values.length - 1) / (right - left)));
            return true;
        }
    }
}
