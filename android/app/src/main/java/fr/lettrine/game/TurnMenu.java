package fr.lettrine.game;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class TurnMenu {
    private final ViewKit ui;
    private final Dialog dialog;
    private final LinearLayout content;

    TurnMenu(ViewKit ui, int heading) {
        this.ui = ui;
        dialog = new Dialog(ui.context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setTitle(label(heading));
        dialog.setCanceledOnTouchOutside(true);

        ScrollView scroll = new ScrollView(ui.context);
        scroll.setFillViewport(false);
        content = ui.column();
        ui.inset(content, 16, 16);
        content.setBackground(ui.shape(ui.palette.page(), 22, ui.palette.line()));
        if (Build.VERSION.SDK_INT >= 28) content.setAccessibilityPaneTitle(label(heading));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        dialog.setContentView(scroll);

        TextView title = ui.title(label(heading), 25);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        content.addView(title);
        ui.gap(content, 12);
    }

    void option(String icon, int title, int detail, Runnable action) {
        LinearLayout row = ui.option(icon, label(title), label(detail), action == null ? null : () -> {
            dialog.dismiss();
            action.run();
        });
        content.addView(row);
        ui.gap(content, 6);
    }

    void confirmPass(Runnable pass) {
        content.addView(ui.body(label(R.string.app_keep_tiles)));
        ui.gap(content, 14);
        addAction(label(R.string.app_pass_turn), pass);
        show();
    }

    void chooseJoker(Consumer<Character> choose) {
        for (int start = 0; start < 26; start += 5) {
            LinearLayout row = ui.row();
            for (int column = 0; column < 5; column++) {
                if (column > 0) ui.space(row, 6);
                int index = start + column;
                if (index >= 26) {
                    ui.weight(row, new View(ui.context));
                    continue;
                }
                char letter = (char) ('A' + index);
                TextView tile = ui.text(Character.toString(letter), 20, ui.tileInk(), ui.medium);
                tile.setGravity(Gravity.CENTER);
                tile.setBackground(ui.rackTile(false));
                tile.setOnClickListener(view -> {
                    dialog.dismiss();
                    choose.accept(letter);
                });
                tile.setFocusable(true);
                row.addView(tile, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
            }
            content.addView(row);
            ui.gap(content, 6);
        }
        show();
    }

    void selectTiles(List<Character> rack, GameLanguage language, Consumer<List<Integer>> exchange) {
        content.addView(ui.body(label(R.string.turn_exchange_help)));
        ui.gap(content, 14);
        boolean[] selected = new boolean[rack.size()];
        TextView count = ui.body(label(R.string.turn_exchange_none));
        count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        TextView confirm = ui.button(label(R.string.app_exchange), true, () -> {
            List<Integer> indices = new ArrayList<>();
            for (int index = 0; index < selected.length; index++) {
                if (selected[index]) indices.add(index);
            }
            if (indices.isEmpty()) return;
            dialog.dismiss();
            exchange.accept(indices);
        });
        confirm.setMinimumHeight(ui.dp(48));
        ui.enabled(confirm, false);
        for (int start = 0; start < rack.size(); start += 4) {
            LinearLayout row = ui.row();
            for (int column = 0; column < 4; column++) {
                if (column > 0) ui.space(row, 8);
                int index = start + column;
                if (index >= rack.size()) {
                    ui.weight(row, new View(ui.context));
                    continue;
                }
                char letter = rack.get(index);
                boolean blank = letter == FrenchGame.JOKER;
                int points = blank ? 0 : language.value(letter);
                View tile = tile(letter, points, blank);
                tile.setOnClickListener(view -> {
                    selected[index] = !selected[index];
                    view.setSelected(selected[index]);
                    view.setBackground(ui.rackTile(selected[index]));
                    ui.tick(view);
                    int total = 0;
                    for (boolean item : selected) if (item) total++;
                    count.setText(total == 0 ? label(R.string.turn_exchange_none) : ui.context.getResources()
                            .getQuantityString(R.plurals.turn_exchange_count, total, total));
                    ui.enabled(confirm, total > 0);
                });
                row.addView(tile, new LinearLayout.LayoutParams(0, ui.dp(54), 1));
            }
            content.addView(row);
            ui.gap(content, 8);
        }
        content.addView(count);
        ui.gap(content, 12);
        content.addView(confirm);
        show();
    }

    private View tile(char letter, int points, boolean blank) {
        LinearLayout tile = ui.row();
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(ui.rackTile(false));
        tile.setFocusable(true);
        tile.setContentDescription(blank ? label(R.string.turn_exchange_blank)
                : label(R.string.board_accessibility_tile, letter, ui.context.getResources()
                .getQuantityString(R.plurals.practice_points, points, points)));
        tile.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.CheckBox.class.getName());
                info.setCheckable(true);
                info.setChecked(host.isSelected());
            }
        });
        TextView face = ui.text(blank ? "?" : Character.toString(letter), 22, ui.tileInk(), ui.medium);
        face.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(face);
        ui.space(tile, 4);
        TextView score = ui.text(Integer.toString(points), 11, ui.tileInk(), ui.medium);
        score.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(score);
        return tile;
    }

    private void addAction(String title, Runnable action) {
        TextView button = ui.button(title, true, () -> {
            dialog.dismiss();
            action.run();
        });
        button.setMinimumHeight(ui.dp(48));
        content.addView(button);
    }

    void show() {
        content.addView(ui.textButton(label(R.string.social_cancel), dialog::dismiss));
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.48f);
            window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        }
        dialog.show();
        if (window != null) {
            int width = Math.min(ui.context.getResources().getDisplayMetrics().widthPixels - ui.dp(16), ui.dp(530));
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private String label(int resource, Object... arguments) {
        return ui.context.getString(resource, arguments);
    }
}
