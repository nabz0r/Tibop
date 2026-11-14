package fr.lettrine.game;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class PlayHome {
    private PlayHome() {}

    static View classic(ViewKit ui, String actionLabel, Runnable play) {
        FrameLayout hero = new FrameLayout(ui.context);
        hero.setBackground(ui.shape(ui.palette.deep(), 24));
        hero.setClipToOutline(true);
        var config = ui.context.getResources().getConfiguration();
        boolean showArt = config.screenWidthDp >= 340 && config.fontScale <= 1.25f;
        if (showArt) {
            View art = new TileArtwork(ui);
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(ui.dp(126), ui.dp(154),
                    Gravity.END | Gravity.CENTER_VERTICAL);
            hero.addView(art, position);
        }

        LinearLayout copy = ui.column();
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.setMinimumHeight(ui.dp(154));
        copy.setPadding(ui.dp(18), ui.dp(15), ui.dp(showArt ? 116 : 18), ui.dp(15));
        hero.addView(copy, new FrameLayout.LayoutParams(-1, -2));
        TextView title = ui.text(ui.context.getString(R.string.home_classic), 27, ui.palette.heroInk(), ui.serif);
        markHeading(title);
        copy.addView(title);
        ui.gap(copy, 4);
        TextView description = ui.text(ui.context.getString(R.string.home_classic_detail), 13,
                ui.palette.heroInk(), ui.regular);
        description.setLineSpacing(ui.dp(2), 1);
        copy.addView(description);
        ui.gap(copy, 12);
        TextView button = ui.button(actionLabel, true, play);
        button.setMinHeight(ui.dp(48));
        button.setTextColor(ui.palette.deep());
        button.setBackground(ui.touch(ui.palette.heroInk(), 15, 0));
        copy.addView(button, new LinearLayout.LayoutParams(-2, -2));
        return hero;
    }

    static View local(ViewKit ui, Runnable play) {
        LinearLayout card = ui.option("2–4", ui.context.getString(R.string.app_home_local),
                ui.context.getString(R.string.app_local_detail), play);
        TextView mark = (TextView) card.getChildAt(0);
        mark.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        return card;
    }

    static View daily(ViewKit ui, String date, String status, String streak, Runnable play) {
        LinearLayout card = ui.row();
        ui.inset(card, 11, 10);
        card.setBackground(ui.touch(ui.palette.paper(), 20, ui.palette.line()));
        card.setOnClickListener(view -> play.run());
        card.setFocusable(true);
        if (Build.VERSION.SDK_INT >= 28) card.setScreenReaderFocusable(true);

        ImageView art = new ImageView(ui.context);
        art.setImageResource(R.drawable.daily_letter_board);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(ui.shape(ui.palette.deep(), 13));
        art.setClipToOutline(true);
        art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(art, new LinearLayout.LayoutParams(ui.dp(59), ui.dp(59)));
        ui.space(card, 12);

        LinearLayout copy = ui.column();
        ui.weight(card, copy);
        copy.addView(ui.text(date, 11, ui.palette.accent(), ui.medium));
        ui.gap(copy, 3);
        copy.addView(ui.text(ui.context.getString(R.string.daily_title), 19, ui.palette.ink(), ui.serif));
        ui.gap(copy, 4);
        copy.addView(ui.text(status, 12, ui.palette.muted(), ui.regular));
        if (!streak.isEmpty()) {
            ui.gap(copy, 3);
            copy.addView(ui.text(streak, 12, ui.palette.accent(), ui.medium));
        }
        ui.space(card, 8);
        TextView arrow = ui.text("›", 24, ui.palette.accent(), ui.serif);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(arrow);
        return card;
    }

    static TextView moreModes(ViewKit ui) {
        TextView title = ui.title(ui.context.getString(R.string.home_more_modes), 22);
        markHeading(title);
        return title;
    }

    private static void markHeading(TextView title) {
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
    }

    private static final class TileArtwork extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Palette palette;
        private final Typeface letterFont;
        private final Typeface pointsFont;

        TileArtwork(ViewKit ui) {
            super(ui.context);
            palette = ui.palette;
            letterFont = ui.serif;
            pointsFont = ui.medium;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float scale = Math.min(getWidth() / 126f, getHeight() / 150f);
            canvas.save();
            canvas.translate(getWidth() - 126f * scale, (getHeight() - 150f * scale) / 2f);
            canvas.scale(scale, scale);
            canvas.rotate(-15, 77, 71);
            paint.setColor(0x1affffff);
            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 4; col++) {
                    float x = 24 + 24 * col;
                    float y = 16 + 24 * row;
                    canvas.drawRoundRect(x, y, x + 21, y + 21, 5, 5, paint);
                }
            }
            canvas.restore();

            canvas.save();
            canvas.translate(getWidth() - 126f * scale, (getHeight() - 150f * scale) / 2f);
            canvas.scale(scale, scale);
            tile(canvas, 52, 45, -15, "A", 1, palette.heroInk());
            tile(canvas, 87, 68, 12, "B", 3, palette.fresh());
            tile(canvas, 53, 98, -9, "C", 3, palette.ordinary());
            canvas.restore();
        }

        private void tile(Canvas canvas, float x, float y, float rotation, String letter, int value, int face) {
            canvas.save();
            canvas.translate(x, y);
            canvas.rotate(rotation);
            paint.setColor(0x40000000);
            canvas.drawRoundRect(-18, -13, 20, 23, 8, 8, paint);
            paint.setColor(face);
            canvas.drawRoundRect(-19, -18, 19, 18, 7, 7, paint);
            paint.setColor(palette.deep());
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(letterFont);
            paint.setTextSize(25);
            canvas.drawText(letter, -1, 8, paint);
            paint.setTypeface(pointsFont);
            paint.setTextSize(7);
            canvas.drawText(Integer.toString(value), 12, 11, paint);
            canvas.restore();
        }
    }
}
