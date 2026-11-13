package fr.lettrine.game;

import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class TwoMoveHomeCard {
    private static final int NAVY = 0xff222d5f;
    private static final int CREAM = 0xfffff7e9;
    private static final int SOFT_CREAM = 0xffe9e4f4;

    private TwoMoveHomeCard() {}

    static View create(ViewKit ui, Runnable play) {
        LinearLayout card = ui.row();
        card.setGravity(Gravity.TOP);
        card.setMinimumHeight(ui.dp(180));
        card.setBackground(ui.shape(NAVY, 22));
        card.setClipToOutline(true);

        LinearLayout text = ui.column();
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setMinimumHeight(ui.dp(180));
        text.setPadding(ui.dp(17), ui.dp(15), ui.dp(4), ui.dp(15));
        TextView title = ui.text(ui.context.getString(R.string.two_move_title), 28, CREAM, ui.serif);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        text.addView(title);
        ui.gap(text, 8);
        TextView detail = ui.text(ui.context.getString(R.string.two_move_home), 13, SOFT_CREAM, ui.regular);
        detail.setLineSpacing(ui.dp(2), 1);
        text.addView(detail);
        ui.gap(text, 12);
        TextView button = ui.button(ui.context.getString(R.string.two_move_home_action), true, play);
        button.setMinHeight(ui.dp(48));
        button.setTextColor(NAVY);
        button.setBackground(ui.touch(CREAM, 12, 0));
        text.addView(button, new LinearLayout.LayoutParams(-2, -2));

        boolean narrow = ui.context.getResources().getConfiguration().screenWidthDp < 360
                || ui.context.getResources().getConfiguration().fontScale > 1.3f;
        card.addView(text, new LinearLayout.LayoutParams(0, -2, narrow ? 76 : 61));
        ImageView art = new ImageView(ui.context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                setMeasuredDimension(View.getDefaultSize(0, widthSpec), View.resolveSize(0, heightSpec));
            }
        };
        art.setImageResource(R.drawable.two_move_rivet_art);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setForeground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{NAVY, 0x32222d5f, 0x00222d5f}));
        art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(art, new LinearLayout.LayoutParams(0, -1, narrow ? 24 : 39));
        return card;
    }
}
