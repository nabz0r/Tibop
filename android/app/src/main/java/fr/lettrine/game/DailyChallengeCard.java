package fr.lettrine.game;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class DailyChallengeCard {
    private DailyChallengeCard() {}

    static View create(ViewKit ui, String date, String status, String streak, String actionLabel, Runnable action) {
        LinearLayout card = ui.row();
        card.setGravity(Gravity.TOP);
        card.setMinimumHeight(ui.dp(208));
        card.setBackground(ui.shape(ui.palette.deep(), 24));
        card.setClipToOutline(true);

        LinearLayout content = ui.column();
        content.setPadding(ui.dp(20), ui.dp(18), ui.dp(3), ui.dp(18));
        content.addView(ui.text(date, 13, ui.palette.fresh(), ui.medium));
        ui.gap(content, 5);
        content.addView(title(ui, ui.context.getString(R.string.daily_title), 32, ui.palette.heroInk()));
        ui.gap(content, 6);
        content.addView(ui.text(status, 14, ui.palette.heroInk(), ui.regular));
        ui.gap(content, 9);
        content.addView(ui.text(streak, 13, ui.palette.fresh(), ui.medium));
        ui.gap(content, 13);
        Button button = new Button(ui.context, null, 0);
        button.setText(actionLabel);
        button.setTextSize(15);
        button.setTextColor(ui.palette.deep());
        button.setTypeface(ui.medium);
        button.setIncludeFontPadding(false);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(ui.dp(48));
        ui.inset(button, 14, 10);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33356149),
                ui.shape(ui.palette.heroInk(), 14), null));
        button.setOnClickListener(view -> action.run());
        content.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        float fontScale = ui.context.getResources().getConfiguration().fontScale;
        int artWeight = fontScale > 1.3f ? 25 : 36;
        card.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 100 - artWeight));
        ImageView art = artwork(ui);
        int deep = ui.palette.deep();
        art.setForeground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] { deep, (deep & 0x00ffffff) | 0x22000000, (deep & 0x00ffffff) | 0x08000000 }));
        card.addView(art, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, artWeight));
        return card;
    }

    static View heading(ViewKit ui, String heading, String date, String status) {
        LinearLayout card = ui.card();
        LinearLayout row = ui.row();
        LinearLayout copy = ui.column();
        copy.addView(ui.text(date, 12, ui.palette.accent(), ui.medium));
        ui.gap(copy, 3);
        copy.addView(title(ui, heading, 28, ui.palette.ink()));
        ui.weight(row, copy);
        ui.space(row, 12);
        ImageView art = artwork(ui);
        art.setBackground(ui.shape(ui.palette.deep(), 12));
        art.setClipToOutline(true);
        row.addView(art, new LinearLayout.LayoutParams(ui.dp(54), ui.dp(54)));
        card.addView(row);
        if (!status.isEmpty()) {
            ui.gap(card, 8);
            card.addView(ui.body(status));
        }
        return card;
    }

    private static TextView title(ViewKit ui, String label, int size, int color) {
        TextView title = ui.text(label, size, color, ui.serif);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        return title;
    }

    private static ImageView artwork(ViewKit ui) {
        ImageView image = new ImageView(ui.context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                setMeasuredDimension(View.getDefaultSize(0, widthSpec), View.resolveSize(0, heightSpec));
            }
        };
        image.setImageResource(R.drawable.daily_letter_board);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }
}
