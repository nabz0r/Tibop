package fr.lettrine.game;

import android.os.Build;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

final class DailyChallengeCard {
    private DailyChallengeCard() {}

    static View heading(ViewKit ui, String heading, String date, String status) {
        LinearLayout copy = ui.column();
        copy.addView(ui.text(date, 12, ui.palette.accent(), ui.medium));
        ui.gap(copy, 3);
        copy.addView(title(ui, heading, 28, ui.palette.ink()));
        LinearLayout card = ui.card();
        ui.inset(card, 0, 0);
        card.addView(ui.illustratedCard(R.drawable.nab_arcade_focus, 77, copy, ui.palette.doubleLetter()));
        if (!status.isEmpty()) {
            TextView objective = ui.body(status);
            objective.setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(11));
            card.addView(objective);
        }
        return card;
    }

    private static TextView title(ViewKit ui, String label, int size, int color) {
        TextView title = ui.text(label, size, color, ui.display);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        return title;
    }

}
