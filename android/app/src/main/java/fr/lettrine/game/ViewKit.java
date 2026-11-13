package fr.lettrine.game;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.BooleanSupplier;

final class ViewKit {
    final Context context;
    final Palette palette;
    final SkinStore.Selection skins;
    final GameAudio audio;
    final Typeface serif = Typeface.create("serif", Typeface.NORMAL);
    final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);

    ViewKit(Context context, Palette palette, SkinStore.Selection skins, GameAudio audio) {
        this.context = context;
        this.palette = palette;
        this.skins = skins;
        this.audio = audio;
    }

    @SuppressLint("ClickableViewAccessibility") // Taps keep the accessible click action.
    void dragTile(View tile, int index, char letter, BooleanSupplier enabled) {
        int threshold = ViewConfiguration.get(context).getScaledTouchSlop();
        tile.setOnTouchListener(new View.OnTouchListener() {
            private float x;
            private float y;
            private boolean started;

            @Override public boolean onTouch(View view, MotionEvent event) {
                if (!enabled.getAsBoolean()) return false;
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    x = event.getX();
                    y = event.getY();
                    started = false;
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                } else if (action == MotionEvent.ACTION_MOVE && !started
                        && Math.hypot(event.getX() - x, event.getY() - y) > threshold) {
                    started = view.startDragAndDrop(ClipData.newPlainText("Lettrine", String.valueOf(letter)),
                            new View.DragShadowBuilder(view), index, 0);
                    if (started) view.setPressed(false);
                }
                return started;
            }
        });
    }

    int tileInk() { return skins.tiles() == null ? palette.ink() : skins.tiles().ink(); }

    Drawable rackTile(boolean active) {
        SkinStore.TileSkin tile = skins.tiles();
        int color = tile == null ? (active ? palette.fresh() : palette.tile()) : (active ? tile.fresh() : tile.face());
        int rim = active ? (skins.board() == null ? palette.accent() : skins.board().focus())
                : tile == null ? palette.tileEdge() : tile.edge();
        int light = Color.rgb((3 * Color.red(color) + 255) / 4,
                (3 * Color.green(color) + 255) / 4, (3 * Color.blue(color) + 255) / 4);
        GradientDrawable face = shape(color, 9, rim);
        face.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        face.setColors(new int[]{light, color});
        return new RippleDrawable(ColorStateList.valueOf(0x26346149), face, null);
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

}
