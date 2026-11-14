package fr.lettrine.game;

import android.graphics.PorterDuff;
import android.os.Build;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class DailyChallengeCard {
    private DailyChallengeCard() {}

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
        image.setColorFilter(0x44252e61, PorterDuff.Mode.SRC_ATOP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }
}
