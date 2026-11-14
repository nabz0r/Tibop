package fr.lettrine.game;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Context;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
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
    final GameLanguage language;
    final Typeface display;
    final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    final boolean animate;
    final boolean celebrations;
    final boolean haptics;

    ViewKit(Context context, Palette palette, SkinStore.Selection skins, GameAudio audio, GameLanguage language) {
        this.context = context;
        this.display = context.getResources().getFont(R.font.nunito_extrabold);
        this.palette = palette;
        this.skins = skins;
        this.audio = audio;
        this.language = language;
        var preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
        animate = preferences.getBoolean("ui_motion", true) && ValueAnimator.areAnimatorsEnabled();
        celebrations = preferences.getBoolean("ui_celebrations", true);
        haptics = preferences.getBoolean("ui_haptics", true);
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
        int rim = active ? (tile == null ? palette.accent() : tile.ink())
                : tile == null ? palette.tileEdge() : tile.edge();
        int light = mix(color, Color.WHITE, Color.luminance(tileInk()) > 0.5f ? 0.10f : 0.25f);
        GradientDrawable face = shape(color, 9, rim);
        if (active) face.setStroke(dp(2), rim);
        face.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        face.setColors(new int[]{light, color});
        return new RippleDrawable(ColorStateList.valueOf(0x26346149), face, null);
    }

    Drawable rackBackground(SkinStore.RackSkin rack, int radius) {
        if (rack == null || rack == SkinStore.RackSkin.CLASSIC) return shape(palette.ordinary(), radius);
        GradientDrawable surface = shape(rack.face(), radius, rack.edge());
        surface.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        surface.setColors(new int[]{mix(rack.face(), Color.WHITE, 0.16f), rack.face(), rack.edge()});
        return surface;
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

    TextView title(String content, int size) { return text(content, size, palette.ink(), display); }

    NabView mascot(int sprite) {
        return new NabView(context, animate, sprite);
    }

    LinearLayout illustratedCard(int sprite, int size, LinearLayout copy, int color) {
        LinearLayout card = row();
        card.setBackground(shape(color, 23));
        inset(card, 14, 11);
        weight(card, copy);
        var configuration = context.getResources().getConfiguration();
        if (configuration.screenWidthDp >= 340 && configuration.fontScale <= 1.3f) {
            space(card, 10);
            card.addView(mascot(sprite), new LinearLayout.LayoutParams(dp(size), dp(size)));
        }
        return card;
    }

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
        return new RippleDrawable(ColorStateList.valueOf((palette.accent() & 0x00ffffff) | 0x26000000),
                shape(color, radius, outline), null);
    }

    Drawable arcadeKey(int fill, int highlight, int edge) {
        GradientDrawable face = shape(fill, 13);
        face.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        face.setColors(new int[]{highlight, fill});
        LayerDrawable layers = new LayerDrawable(new Drawable[]{shape(edge, 13), face});
        layers.setLayerInset(1, dp(1), dp(1), dp(1), dp(4));
        return new RippleDrawable(ColorStateList.valueOf(0x33ffffff), layers, null);
    }

    void styleKey(TextView button, boolean primary) {
        int fill = primary ? palette.deep() : palette.paper();
        button.setTextColor(primary ? palette.heroInk() : palette.ink());
        button.setBackground(arcadeKey(fill, mix(fill, primary ? palette.accent() : palette.ordinary(), .28f),
                primary ? mix(fill, palette.ink(), .56f) : palette.line()));
    }

    void acknowledge(View view) {
        if (haptics) {
            view.performHapticFeedback(android.os.Build.VERSION.SDK_INT >= 30
                    ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    void tick(View view) {
        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    private static int mix(int from, int to, float amount) {
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount));
    }

    TextView button(String content, boolean primary, Runnable run) {
        TextView button = text(content, 14, primary ? palette.heroInk() : palette.ink(), display);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), dp(8), dp(12), dp(12));
        styleKey(button, primary);
        button.setOnClickListener(view -> run.run());
        return button;
    }

    TextView textButton(String content, Runnable run) {
        TextView button = text(content, 13, palette.accent(), display);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setMinHeight(dp(48));
        button.setMinWidth(dp(48));
        inset(button, 5, 7);
        button.setBackground(touch(palette.ordinary(), 12, 0));
        button.setOnClickListener(view -> run.run());
        return button;
    }

    void enabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1 : 0.43f);
    }

    LinearLayout card() {
        LinearLayout card = column();
        card.setBackground(shape(palette.paper(), 22, palette.line()));
        inset(card, 16, 15);
        return card;
    }

    LinearLayout option(String mark, String title, String detail, Runnable action) {
        LinearLayout option = row();
        option.setBackground(action == null ? shape(palette.page(), 18, palette.line())
                : touch(palette.paper(), 18, palette.line()));
        inset(option, 13, 13);
        TextView icon = text(mark, 20, palette.ink(), display);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(shape(palette.ordinary(), 12));
        option.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        space(option, 12);
        LinearLayout content = column();
        content.addView(text(title, 16, palette.ink(), display));
        if (!detail.isEmpty()) {
            gap(content, 5);
            content.addView(text(detail, 12, palette.muted(), regular));
        }
        weight(option, content);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (action != null) {
            TextView arrow = text("›", 23, palette.accent(), display);
            arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            option.addView(arrow);
            option.setOnClickListener(view -> action.run());
            option.setFocusable(true);
        }
        return option;
    }

}
