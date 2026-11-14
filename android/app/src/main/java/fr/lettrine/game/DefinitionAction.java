package fr.lettrine.game;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class DefinitionAction {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private DefinitionAction() {}

    static TextView button(ViewKit ui) {
        TextView action = ui.textButton("", () -> {});
        action.setVisibility(View.GONE);
        return action;
    }

    static void add(ViewKit ui, LinearLayout panel, List<String> words) {
        TextView action = button(ui);
        update(ui, action, words);
        if (action.getVisibility() == View.VISIBLE) panel.addView(action);
    }

    static void update(ViewKit ui, TextView action, List<String> words) {
        String[] entries = words.stream().filter(word -> WordDefinition.url(ui.language, word) != null)
                .distinct().toArray(String[]::new);
        action.setVisibility(entries.length == 0 ? View.GONE : View.VISIBLE);
        if (entries.length == 0) {
            action.setOnClickListener(null);
            return;
        }
        if (entries.length == 1) {
            action.setText(ui.context.getString(R.string.definition_word, entries[0]));
            action.setOnClickListener(view -> show(ui, entries[0]));
        } else {
            action.setText(R.string.definition_words);
            action.setOnClickListener(view -> new AlertDialog.Builder(ui.context)
                    .setTitle(R.string.definition_words)
                    .setItems(entries, (dialog, index) -> show(ui, entries[index]))
                    .setNegativeButton(R.string.social_cancel, null)
                    .show());
        }
    }

    static List<String> recordedWords(char[] board, boolean[] blanks, List<FrenchGame.Tile> tiles,
                                      GameLanguage language, int recordedScore) {
        List<FrenchGame.FormedWord> words = FrenchGame.recordedWords(board, blanks, tiles, language);
        int bingo = tiles.size() == FrenchGame.RACK_SIZE ? 50 : 0;
        int score = words.stream().mapToInt(FrenchGame.FormedWord::score).sum() + bingo;
        return score == recordedScore ? words.stream().map(FrenchGame.FormedWord::word).toList() : List.of();
    }

    private static void show(ViewKit ui, String word) {
        TextView body = ui.body(ui.context.getString(R.string.definition_loading));
        ui.inset(body, 24, 12);
        ScrollView scroll = new ScrollView(ui.context);
        scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(ui.context).setTitle(word)
                .setView(scroll)
                .setNeutralButton(R.string.definition_online, (ignored, button) -> open(ui, word))
                .setPositiveButton(R.string.move_explanation_close, null)
                .show();
        body.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        var context = ui.context.getApplicationContext();
        var lookup = WORKER.submit(() -> {
            List<String> meanings = OfflineDefinitions.find(context, ui.language, word);
            body.post(() -> {
                if (!dialog.isShowing()) return;
                int source = ui.language == GameLanguage.FRENCH ? R.string.definition_source_fr
                        : R.string.definition_source_en;
                body.setText(meanings.isEmpty() ? ui.context.getString(R.string.definition_missing)
                        : String.join("\n\n", meanings) + "\n\n" + ui.context.getString(source));
            });
        });
        dialog.setOnDismissListener(ignored -> lookup.cancel(false));
    }

    private static void open(ViewKit ui, String word) {
        String url = WordDefinition.url(ui.language, word);
        if (url == null) return;
        Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            ui.context.startActivity(browser);
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(ui.context, R.string.definition_unavailable, Toast.LENGTH_SHORT).show();
        }
    }
}
