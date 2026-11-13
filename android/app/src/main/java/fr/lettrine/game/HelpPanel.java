package fr.lettrine.game;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

final class HelpPanel {
    private final ViewKit ui;
    private final Runnable play;
    private final LinearLayout root;
    private final LinearLayout content;
    private final TextView tutorial;
    private final TextView rules;

    HelpPanel(ViewKit ui, Runnable play) {
        this.ui = ui;
        this.play = play;
        root = ui.column();
        LinearLayout tabs = ui.row();
        tutorial = ui.button(label(R.string.help_tutorial), true, () -> render(true));
        rules = ui.button(label(R.string.help_rules_tab), false, () -> render(false));
        ui.weight(tabs, tutorial);
        ui.space(tabs, 7);
        ui.weight(tabs, rules);
        root.addView(tabs);
        ui.gap(root, 12);
        content = ui.column();
        root.addView(content);
        render(true);
    }

    View view() { return root; }

    private void render(boolean showTutorial) {
        select(tutorial, showTutorial);
        select(rules, !showTutorial);
        content.removeAllViews();
        if (showTutorial) {
            section(R.string.help_tutorial_intro, R.string.help_tutorial_copy);
            step("1", R.string.help_tutorial_select, R.string.help_tutorial_select_copy);
            step("2", R.string.help_tutorial_place, R.string.help_tutorial_place_copy);
            step("3", R.string.help_tutorial_validate, R.string.help_tutorial_validate_copy);
            content.addView(ui.button(label(R.string.help_tutorial_action), true, play));
        } else {
            section(R.string.help_rules, R.string.help_rules_copy);
            section(R.string.help_scoring, R.string.help_scoring_copy);
            section(R.string.help_local_bot, R.string.help_local_bot_copy);
            section(R.string.help_competition, R.string.help_competition_copy);
            section(R.string.help_local_model, R.string.help_local_model_copy);
        }
    }

    private void select(TextView tab, boolean active) {
        tab.setSelected(active);
        tab.setTextColor(active ? ui.palette.heroInk() : ui.palette.ink());
        tab.setBackground(ui.touch(active ? ui.palette.deep() : ui.palette.paper(), 12,
                active ? 0 : ui.palette.line()));
    }

    private void section(int title, int copy) {
        LinearLayout card = ui.card();
        addText(card, title, copy);
        content.addView(card);
        ui.gap(content, 8);
    }

    private void step(String number, int title, int copy) {
        LinearLayout row = ui.row();
        ui.inset(row, 10, 8);
        TextView badge = ui.title(number, 23);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(ui.shape(ui.palette.ordinary(), 11));
        row.addView(badge, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        ui.space(row, 12);
        LinearLayout detail = ui.column();
        addText(detail, title, copy);
        ui.weight(row, detail);
        content.addView(row);
        ui.gap(content, 8);
    }

    private void addText(LinearLayout container, int title, int copy) {
        container.addView(ui.title(label(title), 21));
        ui.gap(container, 4);
        container.addView(ui.body(label(copy)));
    }

    private String label(int resource) { return ui.context.getString(resource); }
}
