package fr.lettrine.game;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

final class ViewKit {
    final Context context;
    final Palette palette;
    final Typeface serif = Typeface.create("serif", Typeface.NORMAL);
    final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);

    ViewKit(Context context, Palette palette) {
        this.context = context;
        this.palette = palette;
    }

    int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout row() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    void inset(View view, int x, int y) { view.setPadding(dp(x), dp(y), dp(x), dp(y)); }

    void gap(LinearLayout layout, int height) {
        View gap = new View(context);
        layout.addView(gap, new LinearLayout.LayoutParams(1, dp(height)));
    }

    void space(LinearLayout layout, int width) {
        View gap = new View(context);
        layout.addView(gap, new LinearLayout.LayoutParams(dp(width), 1));
    }

    void weight(LinearLayout layout, View view) {
        layout.addView(view, new LinearLayout.LayoutParams(0, -2, 1));
    }

    TextView text(String content, int size, int color, Typeface font) {
        TextView label = new TextView(context);
        label.setText(content);
        label.setTextColor(color);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        label.setTypeface(font);
        label.setIncludeFontPadding(false);
        return label;
    }

    TextView title(String content, int size) { return text(content, size, palette.ink(), serif); }

    TextView body(String content) {
        TextView label = text(content, 14, palette.muted(), regular);
        label.setLineSpacing(dp(3), 1);
        return label;
    }

    TextView micro(String content, int color) {
        TextView label = text(content, 10, color, medium);
        label.setLetterSpacing(0.105f);
        return label;
    }

    GradientDrawable shape(int color, int radius) { return shape(color, radius, 0); }

    GradientDrawable shape(int color, int radius, int outline) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        if (outline != 0) background.setStroke(dp(1), outline);
        return background;
    }

    Drawable touch(int color, int radius, int outline) {
        return new RippleDrawable(ColorStateList.valueOf(0x26346149), shape(color, radius, outline), null);
    }

    TextView button(String content, boolean primary, Runnable run) {
        TextView button = text(content, 14, primary ? palette.heroInk() : palette.ink(), medium);
        button.setGravity(Gravity.CENTER);
        button.setMinimumHeight(dp(46));
        inset(button, 12, 8);
        button.setBackground(touch(primary ? palette.deep() : palette.paper(), 12, primary ? 0 : palette.line()));
        button.setOnClickListener(view -> run.run());
        return button;
    }

    TextView textButton(String content, Runnable run) {
        TextView button = text(content, 13, palette.accent(), medium);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(42));
        button.setMinWidth(dp(42));
        inset(button, 5, 7);
        button.setBackground(touch(palette.page(), 10, 0));
        button.setOnClickListener(view -> run.run());
        return button;
    }

    void enabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1 : 0.43f);
    }

    LinearLayout card() {
        LinearLayout card = column();
        card.setBackground(shape(palette.paper(), 16, palette.line()));
        inset(card, 16, 15);
        return card;
    }

    LinearLayout option(String mark, String title, String detail, Runnable action) {
        LinearLayout option = row();
        option.setBackground(touch(palette.paper(), 16, palette.line()));
        inset(option, 13, 13);
        TextView icon = text(mark, 20, palette.ink(), serif);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(shape(palette.ordinary(), 12));
        option.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        space(option, 12);
        LinearLayout content = column();
        content.addView(text(title, 16, palette.ink(), medium));
        if (!detail.isEmpty()) {
            gap(content, 5);
            content.addView(text(detail, 12, palette.muted(), regular));
        }
        weight(option, content);
        TextView arrow = text("›", 23, palette.accent(), serif);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        option.addView(arrow);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        option.setOnClickListener(view -> action.run());
        return option;
    }

    View hero() { return new Hero(context); }

    private final class Hero extends View {
        private final Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final android.graphics.Path clip = new android.graphics.Path();

        Hero(Context context) {
            super(context);
            setContentDescription("Lettrine. Jeu de lettres.");
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            rect.set(0, 0, width, height);
            brush.setColor(palette.deep());
            canvas.drawRoundRect(rect, dp(22), dp(22), brush);
            canvas.save();
            clip.rewind();
            clip.addRoundRect(rect, dp(22), dp(22), android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            float tileSize = dp(28);
            float x = width - dp(93);
            float y = height * 0.51f;
            canvas.save();
            canvas.rotate(-18, x, y);
            for (int row = -2; row <= 2; row++) {
                for (int col = -2; col <= 3; col++) {
                    if ((row == -2 && col <= -1) || (row == 2 && col == -2)) continue;
                    brush.setColor(((row + col) & 1) == 0 ? 0x226bd0a2 : 0x126bd0a2);
                    square(canvas, x + col * tileSize, y + row * tileSize, tileSize * 0.93f);
                }
            }
            token(canvas, "Z", 10, x - tileSize * 0.96f, y - tileSize, tileSize);
            token(canvas, "E", 1, x + tileSize * 0.04f, y - tileSize, tileSize);
            token(canvas, "N", 1, x + tileSize * 1.04f, y - tileSize, tileSize);
            token(canvas, "J", 8, x + tileSize * 0.04f, y, tileSize);
            canvas.restore();
            brush.setColor(palette.fresh());
            brush.setTypeface(medium);
            brush.setTextSize(dp(10));
            canvas.drawText("JEU DE LETTRES", dp(21), dp(32), brush);
            brush.setColor(palette.heroInk());
            brush.setTypeface(serif);
            brush.setTextSize(dp(width > dp(330) ? 48 : 39));
            canvas.drawText("Lettrine", dp(19), dp(86), brush);
            brush.setColor(palette.heroInk());
            brush.setTypeface(regular);
            brush.setTextSize(dp(13));
            canvas.drawText("Français", dp(22), dp(115), brush);
            canvas.restore();
        }

        private void square(Canvas canvas, float x, float y, float size) {
            rect.set(x, y, x + size, y + size);
            canvas.drawRoundRect(rect, dp(4), dp(4), brush);
        }

        private void token(Canvas canvas, String letter, int value, float x, float y, float size) {
            brush.setColor(0x3310140b);
            square(canvas, x, y + dp(2), size * 0.92f);
            brush.setColor(letter.equals("J") ? palette.fresh() : palette.tile());
            square(canvas, x, y, size * 0.92f);
            brush.setColor(palette.ink());
            brush.setTypeface(medium);
            brush.setTextSize(size * 0.53f);
            canvas.drawText(letter, x + size * 0.23f, y + size * 0.61f, brush);
            brush.setTextSize(size * 0.21f);
            canvas.drawText(String.valueOf(value), x + size * 0.60f, y + size * 0.78f, brush);
        }
    }
}
