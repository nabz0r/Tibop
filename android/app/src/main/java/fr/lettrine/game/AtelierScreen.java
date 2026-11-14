package fr.lettrine.game;

import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.StringRes;
import androidx.core.view.ViewCompat;

import java.util.List;
import java.util.function.IntConsumer;

final class AtelierScreen {
    private static final int[] CHAPTERS = {
            R.string.atelier_chapter_1, R.string.atelier_chapter_2, R.string.atelier_chapter_3
    };
    private static final int[] ROOM_ART = {
            R.drawable.atelier_room_0, R.drawable.atelier_room_1,
            R.drawable.atelier_room_2, R.drawable.atelier_room_3
    };
    private static final int[] ROOM_DESCRIPTION = {
            R.string.atelier_room_art_starter, R.string.atelier_room_art_table,
            R.string.atelier_room_art_library, R.string.atelier_room_art_gallery
    };
    private static final int[] REWARDS = {
            R.string.atelier_room_starter, R.string.atelier_room_table,
            R.string.atelier_room_library, R.string.atelier_room_gallery
    };

    private final ViewKit ui;
    private final List<NabCampaign.Stage> catalog;
    private final NabCampaignProgress.Snapshot progress;
    private final IntConsumer play;

    AtelierScreen(ViewKit ui, List<NabCampaign.Stage> catalog, NabCampaignProgress.Snapshot progress,
                  IntConsumer play) {
        this.ui = ui;
        this.catalog = catalog;
        this.progress = progress;
        this.play = play;
    }

    static @StringRes int rewardName(int tier) { return REWARDS[tier]; }

    View view() { return view(false); }

    View view(boolean workshop) {
        LinearLayout panel = ui.column();
        ui.gap(panel, 8);
        panel.addView(heading(label(R.string.atelier_title), 31));
        ui.gap(panel, 10);
        var configuration = ui.context.getResources().getConfiguration();
        boolean stackTabs = configuration.screenWidthDp / configuration.fontScale < 205;
        LinearLayout tabs = stackTabs ? ui.column() : ui.row();
        LinearLayout section = ui.column();
        TextView map = ui.button(label(R.string.atelier_tab_map), !workshop, () -> {});
        TextView room = ui.button(label(R.string.atelier_tab_workshop), workshop, () -> {});
        if (stackTabs) {
            tabs.addView(map);
            ui.gap(tabs, 6);
            tabs.addView(room);
        } else {
            ui.weight(tabs, map);
            ui.space(tabs, 8);
            ui.weight(tabs, room);
        }
        panel.addView(tabs);
        ui.gap(panel, 13);
        panel.addView(section);
        map.setOnClickListener(view -> {
            if (!view.isSelected()) selectTab(section, map, room, false);
        });
        room.setOnClickListener(view -> {
            if (!view.isSelected()) selectTab(section, map, room, true);
        });
        selectTab(section, map, room, workshop);
        return panel;
    }

    private void selectTab(LinearLayout section, TextView map, TextView room, boolean workshop) {
        map.setSelected(!workshop);
        room.setSelected(workshop);
        ui.styleKey(map, !workshop);
        ui.styleKey(room, workshop);
        section.removeAllViews();
        if (workshop) buildWorkshop(section);
        else buildMap(section);
    }

    private void buildMap(LinearLayout section) {
        LinearLayout copy = ui.column();
        int next = progress.nextStage();
        if (next > 0) {
            NabCampaign.Stage stage = catalog.get(next - 1);
            copy.addView(heading(stage.title(), 22));
            ui.gap(copy, 4);
            copy.addView(ui.body(stage.objective()));
        } else {
            copy.addView(ui.body(label(R.string.atelier_campaign_done)));
        }
        ui.gap(copy, 8);
        copy.addView(ui.text(label(R.string.atelier_campaign_count, progress.completed(), catalog.size()),
                13, ui.palette.ink(), ui.medium));
        if (next > 0) {
            ui.gap(copy, 10);
            copy.addView(ui.button(label(R.string.atelier_home_continue, next), true, () -> play.accept(next)));
        }
        section.addView(ui.illustratedCard(next == 0 ? R.drawable.nab_arcade_cheer : R.drawable.nab_arcade_welcome,
                78, copy, ui.palette.doubleLetter()));
        int tier = progress.workshopLevel();
        if (tier < CHAPTERS.length) {
            ui.gap(section, 8);
            int upcoming = tier + 1;
            TextView reward = ui.text(label(R.string.atelier_next_reward, chapterFinale(upcoming),
                    label(rewardName(upcoming))), 13, ui.palette.accent(), ui.medium);
            reward.setGravity(Gravity.CENTER);
            ui.inset(reward, 6, 5);
            section.addView(reward);
        }
        for (int chapter = 1; chapter <= CHAPTERS.length; chapter++) {
            ui.gap(section, 14);
            section.addView(chapterCard(chapter));
        }
    }

    private View chapterCard(int chapter) {
        LinearLayout card = ui.column();
        card.setBackground(ui.shape(chapterColor(chapter), 21));
        ui.inset(card, 12, 12);
        TextView number = ui.text(label(R.string.atelier_chapter_number, chapter), 12, ui.palette.muted(), ui.medium);
        card.addView(number);
        ui.gap(card, 3);
        card.addView(heading(label(CHAPTERS[chapter - 1]), 21));
        ui.gap(card, 10);

        var configuration = ui.context.getResources().getConfiguration();
        int columns = configuration.screenWidthDp < 340 || configuration.fontScale >= 1.55f ? 2 : 4;
        int count = 0;
        LinearLayout row = ui.row();
        for (NabCampaign.Stage stage : catalog) {
            if (stage.chapter() != chapter) continue;
            if (count > 0 && count % columns == 0) {
                card.addView(row);
                ui.gap(card, 7);
                row = ui.row();
            }
            if (count % columns != 0) {
                View link = new View(ui.context);
                link.setBackgroundColor(progress.unlocked(stage.number()) ? ui.palette.accent() : ui.palette.line());
                link.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.addView(link, new LinearLayout.LayoutParams(ui.dp(6), ui.dp(2)));
            }
            row.addView(levelTile(stage), new LinearLayout.LayoutParams(0, -2, 1));
            count++;
        }
        card.addView(row);
        return card;
    }

    private View levelTile(NabCampaign.Stage stage) {
        int number = stage.number();
        boolean available = progress.unlocked(number);
        boolean next = number == progress.nextStage();
        int stars = progress.stars(number);
        int fill = next ? ui.palette.deep() : available ? ui.palette.paper() : ui.palette.page();
        int ink = next ? ui.palette.heroInk() : available ? ui.palette.ink() : ui.palette.muted();
        LinearLayout tile = ui.column();
        tile.setGravity(Gravity.CENTER);
        tile.setMinimumHeight(ui.dp(74));
        ui.inset(tile, 4, 8);
        tile.setBackground(available ? ui.touch(fill, 14, ui.palette.line()) : ui.shape(fill, 14, ui.palette.line()));
        tile.addView(ui.text(label(R.string.atelier_level_number, number), 21, ink, ui.display));
        ui.gap(tile, 3);
        String badge = switch (stars) {
            case 1 -> "★";
            case 2 -> "★★";
            case 3 -> "★★★";
            default -> stage.rival() ? label(R.string.atelier_level_finale) : available ? "›" : "—";
        };
        tile.addView(ui.text(badge, 11, ink, ui.display));
        String state = label(available ? R.string.atelier_level_open : R.string.atelier_level_locked);
        if (stars > 0) {
            state = ui.context.getResources().getQuantityString(R.plurals.atelier_level_result, stars,
                    stars, progress.bestScore(number));
        }
        String title = label(R.string.atelier_level_label, number, stage.title());
        if (stage.rival()) title += ". " + label(R.string.atelier_level_finale);
        tile.setContentDescription(title + ". " + stage.objective() + " " + state);
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        for (int index = 0; index < tile.getChildCount(); index++) {
            tile.getChildAt(index).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        if (available) {
            tile.setOnClickListener(view -> play.accept(number));
            tile.setFocusable(true);
        }
        return tile;
    }

    private void buildWorkshop(LinearLayout section) {
        int tier = progress.workshopLevel();
        ImageView room = new ImageView(ui.context);
        room.setImageResource(ROOM_ART[tier]);
        room.setContentDescription(label(ROOM_DESCRIPTION[tier]));
        room.setAdjustViewBounds(true);
        room.setScaleType(ImageView.ScaleType.FIT_CENTER);
        room.setBackground(ui.shape(ui.palette.ordinary(), 20));
        room.setClipToOutline(true);
        section.addView(room, new LinearLayout.LayoutParams(-1, -2));
        ui.gap(section, 10);
        section.addView(heading(label(rewardName(tier)), 23));
        ui.gap(section, 6);
        if (tier < CHAPTERS.length) {
            int upcoming = tier + 1;
            section.addView(ui.body(label(R.string.atelier_next_reward, chapterFinale(upcoming),
                    label(rewardName(upcoming)))));
            int next = progress.nextStage();
            ui.gap(section, 10);
            section.addView(ui.button(label(R.string.atelier_home_continue, next), true, () -> play.accept(next)));
        } else {
            section.addView(ui.body(label(R.string.atelier_campaign_done)));
        }
        ui.gap(section, 18);
        section.addView(heading(label(R.string.atelier_upgrades_heading), 19));
        for (int chapter = 1; chapter <= CHAPTERS.length; chapter++) {
            ui.gap(section, 7);
            String title = label(rewardName(chapter));
            boolean earned = chapter <= tier;
            String status = earned ? label(R.string.atelier_upgrade_earned, label(CHAPTERS[chapter - 1]))
                    : label(R.string.atelier_upgrade_locked, chapterFinale(chapter));
            section.addView(ui.option(earned ? "✓" : "◇", title, status, null));
        }
    }

    private int chapterFinale(int chapter) {
        for (NabCampaign.Stage stage : catalog) {
            if (stage.chapter() == chapter && stage.rival()) return stage.number();
        }
        throw new IllegalStateException("Missing finale for chapter " + chapter);
    }

    private int chapterColor(int chapter) {
        return switch (chapter) {
            case 1 -> ui.palette.doubleLetter();
            case 2 -> ui.palette.tripleLetter();
            default -> ui.palette.doubleWord();
        };
    }

    private TextView heading(String text, int size) {
        TextView heading = ui.title(text, size);
        ViewCompat.setAccessibilityHeading(heading, true);
        return heading;
    }

    private String label(@StringRes int resource, Object... values) {
        return ui.context.getString(resource, values);
    }
}
