package fr.lettrine.game;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.function.BooleanSupplier;

final class SkinPanel {
    private enum Category {
        BOARDS(R.string.skins_boards), RACKS(R.string.skins_racks), TILES(R.string.skins_tiles),
        CARDS(R.string.card_styles);

        final int title;
        Category(int title) { this.title = title; }
    }

    private enum Sample { BOARD, RACK, TILES, SET }

    private record Piece(int name, int detail, SkinStore.Reward reward,
                         boolean equipped, View preview, BooleanSupplier equip) {}

    private final ViewKit ui;
    private final SkinStore store;
    private final CardStyleStore cardStyles;
    private final GameStore games;
    private final DailyProgress daily;
    private final Runnable applied;
    private final LinearLayout root;
    private final FrameLayout previewSet;
    private final TextView combination;
    private final LinearLayout items;
    private final TextView[] categoryTabs = new TextView[Category.values().length];
    private final TextView count;
    private int columns;
    private Category category = Category.BOARDS;

    SkinPanel(ViewKit ui, SkinStore store, GameStore games, DailyProgress daily, Runnable applied,
              Runnable openThemes) {
        this.ui = ui;
        this.store = store;
        cardStyles = new CardStyleStore(ui.context);
        this.games = games;
        this.daily = daily;
        this.applied = applied;

        root = ui.column();
        LinearLayout hero = ui.column();
        GradientDrawable cover = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{ui.palette.accent(), ui.palette.deep()});
        cover.setCornerRadius(ui.dp(24));
        hero.setBackground(cover);
        ui.inset(hero, 20, 19);
        var configuration = ui.context.getResources().getConfiguration();
        boolean compact = configuration.screenWidthDp < 340 || configuration.fontScale > 1.35f;
        int titleSize = compact ? 29 : 38;
        TextView title = ui.text(label(R.string.skins_title), titleSize, ui.palette.heroInk(), ui.display);
        title.setMaxLines(1);
        title.setAutoSizeTextTypeUniformWithConfiguration(16, titleSize, 1, TypedValue.COMPLEX_UNIT_SP);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        hero.addView(title);
        ui.gap(hero, 8);
        TextView intro = ui.body(label(R.string.skins_intro));
        intro.setTextColor(ui.palette.heroInk());
        hero.addView(intro);
        root.addView(hero);

        ui.gap(root, 13);
        LinearLayout tabs = ui.column();
        for (Category entry : Category.values()) {
            categoryTabs[entry.ordinal()] = ui.button(label(entry.title), entry == category, () -> select(entry));
        }
        arrangeTabs(tabs, columns(ui.dp(configuration.screenWidthDp) - ui.dp(34), configuration.fontScale));
        tabs.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int next = columns(right - left, ui.context.getResources().getConfiguration().fontScale);
            if (next != columns) tabs.post(() -> arrangeTabs(tabs, next));
        });
        root.addView(tabs);
        ui.gap(root, 11);
        TextView previewTitle = ui.title(label(R.string.skins_preview), 18);
        if (Build.VERSION.SDK_INT >= 28) previewTitle.setAccessibilityHeading(true);
        root.addView(previewTitle);
        ui.gap(root, 5);
        previewSet = new FrameLayout(ui.context);
        root.addView(previewSet, new LinearLayout.LayoutParams(-1, ui.dp(112)));
        ui.gap(root, 5);
        combination = ui.body("");
        combination.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(combination);
        ui.gap(root, 7);
        TextView themes = ui.textButton(label(R.string.skins_theme_current, label(ui.palette.name())), openThemes);
        themes.setContentDescription(label(R.string.skins_theme_open, label(ui.palette.name())));
        root.addView(themes);
        ui.gap(root, 11);
        count = ui.body("");
        root.addView(count);
        ui.gap(root, 7);
        items = ui.column();
        root.addView(items);
        render();
    }

    View view() { return root; }

    private int columns(int width, float scale) {
        if (width >= ui.dp(430) && scale <= 1.2f) return 4;
        return width >= ui.dp(240) && scale <= 1.75f ? 2 : 1;
    }

    private void arrangeTabs(LinearLayout tabs, int count) {
        if (count == columns) return;
        columns = count;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            if (tabs.getChildAt(i) instanceof LinearLayout row) row.removeAllViews();
        }
        tabs.removeAllViews();
        for (int i = 0; i < categoryTabs.length; i += count) {
            if (i > 0) ui.gap(tabs, 5);
            LinearLayout row = ui.row();
            for (int k = 0; k < count; k++) {
                if (k > 0) ui.space(row, 6);
                ui.weight(row, categoryTabs[i + k]);
            }
            tabs.addView(row);
        }
    }

    private void select(Category category) {
        if (this.category == category) return;
        this.category = category;
        render();
    }

    private void render() {
        for (Category entry : Category.values()) {
            TextView tab = categoryTabs[entry.ordinal()];
            boolean active = entry == category;
            tab.setSelected(active);
            ui.styleKey(tab, active);
        }
        items.removeAllViews();
        SkinStore.Inventory inventory = store.refresh(games, daily);
        SkinStore.Selection selection = inventory.selection();
        updatePreviewSet(selection);
        int available = 0;
        if (category == Category.CARDS) {
            for (CardStyleStore.Style style : CardStyleStore.Style.values()) {
                if (cardStyles.has(style)) available++;
                addShareStyle(style);
            }
            count.setText(label(R.string.skins_available, available, CardStyleStore.Style.values().length));
            return;
        }
        var pieces = new ArrayList<Piece>();
        switch (category) {
            case BOARDS -> {
                for (SkinStore.BoardSkin item : SkinStore.BoardSkin.values()) {
                    SkinStore.BoardSkin shown = item == SkinStore.BoardSkin.CLASSIC ? null : item;
                    View preview = new Preview(ui, Sample.BOARD, shown, selection.tiles(), null);
                    pieces.add(new Piece(item.name, item.detail, item.reward, shown == selection.board(),
                            preview, () -> store.equip(item)));
                }
            }
            case RACKS -> {
                for (SkinStore.RackSkin item : SkinStore.RackSkin.values()) {
                    SkinStore.RackSkin shown = item == SkinStore.RackSkin.CLASSIC ? null : item;
                    View preview = new Preview(ui, Sample.RACK, null, selection.tiles(), shown);
                    pieces.add(new Piece(item.name, item.detail, item.reward, shown == selection.rack(),
                            preview, () -> store.equip(item)));
                }
            }
            case TILES -> {
                for (SkinStore.TileSkin item : SkinStore.TileSkin.values()) {
                    SkinStore.TileSkin shown = item == SkinStore.TileSkin.CLASSIC ? null : item;
                    View preview = new Preview(ui, Sample.TILES, null, shown, selection.rack());
                    pieces.add(new Piece(item.name, item.detail, item.reward, shown == selection.tiles(),
                            preview, () -> store.equip(item)));
                }
            }
            case CARDS -> throw new IllegalStateException();
        }
        for (Piece piece : pieces) {
            if (inventory.has(piece.reward())) {
                addCard(piece, inventory, true);
                available++;
            }
        }
        for (Piece piece : pieces) if (!inventory.has(piece.reward())) addCard(piece, inventory, false);
        count.setText(label(R.string.skins_available, available, pieces.size()));
    }

    private void updatePreviewSet(SkinStore.Selection selection) {
        previewSet.removeAllViews();
        Preview preview = new Preview(ui, Sample.SET, selection.board(), selection.tiles(), selection.rack());
        previewSet.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        combination.setText(label(R.string.skins_preview_equipped,
                label(selection.board() == null ? R.string.skins_classic : selection.board().name),
                label(selection.rack() == null ? R.string.skins_classic : selection.rack().name),
                label(selection.tiles() == null ? R.string.skins_classic : selection.tiles().name)));
    }

    private void addCard(Piece piece, SkinStore.Inventory inventory, boolean unlocked) {
        LinearLayout card = ui.card();
        if (piece.equipped()) card.setBackground(ui.shape(ui.palette.paper(), 22, ui.palette.accent()));
        ui.inset(card, 12, 12);
        card.addView(piece.preview(), new LinearLayout.LayoutParams(-1, ui.dp(94)));
        ui.gap(card, 10);

        card.addView(styleHeader(piece.name(), piece.equipped(), unlocked, () -> {
            if (!piece.equip().getAsBoolean()) return false;
            applied.run();
            return true;
        }));
        card.addView(ui.body(label(piece.detail())));
        if (!unlocked) {
            SkinStore.Reward reward = piece.reward();
            ui.gap(card, 11);
            int value = reward.count(inventory.progress());
            int quantity = reward.daily ? R.plurals.skins_daily_requirement : R.plurals.skins_solo_requirement;
            String requirement = ui.context.getResources().getQuantityString(quantity, reward.target, reward.target);
            LinearLayout milestone = ui.row();
            ui.weight(milestone, ui.body(requirement));
            ui.space(milestone, 7);
            milestone.addView(ui.text(label(R.string.skins_progress, value, reward.target), 14,
                    ui.palette.ink(), ui.medium));
            card.addView(milestone);
            ui.gap(card, 6);
            card.addView(new Meter(ui, value / (float) reward.target), new LinearLayout.LayoutParams(-1, ui.dp(5)));
        }
        items.addView(card);
        ui.gap(items, 10);
    }

    private void addShareStyle(CardStyleStore.Style style) {
        boolean unlocked = cardStyles.has(style);
        LinearLayout card = ui.card();
        ui.inset(card, 12, 12);
        ImageView preview = ScoreCard.preview(ui, style);
        LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(-1, ui.dp(155));
        preview.setBackground(ui.shape(ui.palette.ordinary(), 12));
        card.addView(preview, bounds);
        ui.gap(card, 10);
        card.addView(styleHeader(style.label, cardStyles.selected() == style, unlocked, () -> cardStyles.equip(style)));
        card.addView(ui.body(label(style.detail)));
        if (!unlocked) {
            ui.gap(card, 8);
            card.addView(ui.body(label(R.string.card_style_unlock_requirement)));
        }
        items.addView(card);
        ui.gap(items, 10);
    }

    private View styleHeader(int name, boolean active, boolean unlocked, BooleanSupplier equip) {
        var configuration = ui.context.getResources().getConfiguration();
        boolean stacked = configuration.screenWidthDp < 340 || configuration.fontScale > 1.35f;
        LinearLayout header = stacked ? ui.column() : ui.row();
        TextView title = ui.title(label(name), 25);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        if (stacked) header.addView(title);
        else ui.weight(header, title);
        if (active || !unlocked) {
            TextView badge = ui.text(label(active ? R.string.skins_active : R.string.skins_locked), 13,
                    ui.palette.ink(), ui.medium);
            badge.setBackground(ui.shape(active ? ui.palette.fresh() : ui.palette.ordinary(), 9));
            ui.inset(badge, 8, 6);
            addStyleAction(header, badge, stacked);
        } else {
            TextView action = ui.button(label(R.string.skins_equip), false, () -> {
                if (!equip.getAsBoolean()) {
                    Toast.makeText(ui.context, R.string.skins_save_error, Toast.LENGTH_SHORT).show();
                    return;
                }
                render();
                root.announceForAccessibility(label(R.string.skins_equipped_notice, label(name)));
            });
            action.setContentDescription(label(R.string.skins_equip_named, label(name)));
            addStyleAction(header, action, stacked);
        }
        return header;
    }

    private void addStyleAction(LinearLayout header, View action, boolean stacked) {
        if (stacked) ui.gap(header, 6);
        else ui.space(header, 6);
        header.addView(action, new LinearLayout.LayoutParams(-2, -2));
    }

    private String label(int resource, Object... values) { return ui.context.getString(resource, values); }

    @SuppressLint("ViewConstructor")
    private static final class Meter extends View {
        private final ViewKit ui;
        private final float fraction;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Meter(ViewKit ui, float fraction) {
            super(ui.context);
            this.ui = ui;
            this.fraction = fraction;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            float radius = getHeight() / 2f;
            paint.setColor(ui.palette.ordinary());
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, paint);
            if (fraction > 0) {
                paint.setColor(ui.palette.accent());
                canvas.drawRoundRect(0, 0, getWidth() * fraction, getHeight(), radius, radius, paint);
            }
        }
    }

    @SuppressLint("ViewConstructor")
    private static final class Preview extends View {
        private static final int[] CELL_STYLES = {4, 0, 1, 0, 2, 0, 0, 1, 0, 0, 0, 3, 2, 0, 3, 1, 0, 4};
        private final Sample sample;
        private final Drawable rackBackground;
        private final GameLanguage language;
        private final String word;
        private final String[] marks;
        private final int frame;
        private final int coordinates;
        private final int cellInk;
        private final int[] cells;
        private final int tileFace;
        private final int tileEdge;
        private final int tileFresh;
        private final int tileInk;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Typeface serif;
        private final Typeface medium;
        private Shader frameShader;

        Preview(ViewKit ui, Sample sample, SkinStore.BoardSkin boardSkin, SkinStore.TileSkin tileSkin,
                SkinStore.RackSkin rackSkin) {
            super(ui.context);
            this.sample = sample;
            rackBackground = ui.rackBackground(rackSkin, 9);
            language = ui.language;
            word = language.text("MOT", "CAT");
            marks = new String[]{"", ui.context.getString(R.string.board_letter_bonus, 2),
                    ui.context.getString(R.string.board_letter_bonus, 3),
                    ui.context.getString(R.string.board_word_bonus, 2),
                    ui.context.getString(R.string.board_word_bonus, 3)};
            Palette palette = ui.palette;
            frame = boardSkin == null ? palette.deep() : boardSkin.frame();
            coordinates = boardSkin == null ? palette.heroInk() : boardSkin.coordinates();
            cellInk = boardSkin == null ? palette.ink() : boardSkin.cellInk();
            cells = boardSkin == null
                    ? new int[]{palette.ordinary(), palette.doubleLetter(), palette.tripleLetter(), palette.doubleWord(), palette.tripleWord()}
                    : new int[]{boardSkin.ordinary(), boardSkin.doubleLetter(), boardSkin.tripleLetter(), boardSkin.doubleWord(), boardSkin.tripleWord()};
            tileFace = tileSkin == null ? palette.tile() : tileSkin.face();
            tileEdge = tileSkin == null ? palette.tileEdge() : tileSkin.edge();
            tileFresh = tileSkin == null ? palette.fresh() : tileSkin.fresh();
            tileInk = tileSkin == null ? palette.ink() : tileSkin.ink();
            serif = ui.display;
            medium = ui.medium;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            if (w <= 0 || h <= 0) return;
            frameShader = new LinearGradient(0, 0, w, h, blend(frame, coordinates, 0.18f),
                    blend(frame, Color.BLACK, 0.22f), Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float radius = h * 0.12f;
            paint.setShader(frameShader);
            canvas.drawRoundRect(0, 0, w, h, radius, radius, paint);
            paint.setShader(null);
            switch (sample) {
                case BOARD -> drawBoard(canvas, w, h);
                case RACK, TILES -> drawTiles(canvas, w, h);
                case SET -> {
                    drawBoard(canvas, w * 0.53f, h);
                    canvas.save();
                    canvas.translate(w * 0.53f, 0);
                    drawTiles(canvas, w * 0.47f, h);
                    canvas.restore();
                }
            }
        }

        private void drawBoard(Canvas canvas, float width, float height) {
            float size = Math.min(width / 7.4f, height / 3.6f);
            float startX = (width - size * 6) / 2;
            float startY = (height - size * 3) / 2;
            for (int i = 0; i < CELL_STYLES.length; i++) {
                float x = startX + i % 6 * size;
                float y = startY + i / 6 * size;
                if (i >= 8 && i <= 10) {
                    drawTile(canvas, x + size * 0.06f, y + size * 0.05f, size * 0.9f, word.charAt(i - 8), i == 10);
                } else {
                    int kind = CELL_STYLES[i];
                    paint.setColor(cells[kind]);
                    canvas.drawRoundRect(x + size * 0.055f, y + size * 0.055f, x + size * 0.945f,
                            y + size * 0.945f, size * 0.11f, size * 0.11f, paint);
                    if (kind > 0) drawText(canvas, marks[kind], x + size / 2, y + size / 2, size * 0.25f, cellInk, medium);
                }
            }
        }

        private void drawTiles(Canvas canvas, float width, float height) {
            float size = Math.min(width / 5f, height * 0.5f);
            float pitch = size * 1.13f;
            float start = (width - (pitch * 2 + size)) / 2;
            float top = (height - size) / 2;
            float rim = size * 0.25f;
            rackBackground.setBounds(Math.round(start - rim), Math.round(top - rim),
                    Math.round(start + pitch * 2 + size + rim), Math.round(top + size + rim));
            rackBackground.draw(canvas);
            for (int i = 0; i < 3; i++) {
                float x = start + i * pitch;
                drawTile(canvas, x, top, size, word.charAt(i), i == 2);
            }
        }

        private void drawTile(Canvas canvas, float x, float y, float size, char letter, boolean fresh) {
            float radius = size * 0.14f;
            int face = fresh ? tileFresh : tileFace;
            paint.setColor(fresh ? blend(face, tileInk, 0.37f) : tileEdge);
            canvas.drawRoundRect(x, y, x + size, y + size, radius, radius, paint);
            rect.set(x, y, x + size, y + size * 0.88f);
            paint.setColor(face);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setColor(0x74ffffff);
            canvas.drawRoundRect(x + size * 0.15f, y + size * 0.06f, x + size * 0.85f, y + size * 0.105f,
                    radius, radius, paint);
            drawText(canvas, String.valueOf(letter), x + size * 0.46f, y + size * 0.43f, size * 0.54f, tileInk, serif);
            drawText(canvas, String.valueOf(language.value(letter)), x + size * 0.79f, y + size * 0.70f,
                    size * 0.18f, tileInk, medium);
        }

        private void drawText(Canvas canvas, String text, float x, float middle, float size, int color, Typeface font) {
            paint.setTypeface(font);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(size);
            paint.setColor(color);
            canvas.drawText(text, x, middle - (paint.ascent() + paint.descent()) / 2, paint);
        }

        private static int blend(int first, int second, float weight) {
            return Color.rgb(blendChannel(Color.red(first), Color.red(second), weight),
                    blendChannel(Color.green(first), Color.green(second), weight),
                    blendChannel(Color.blue(first), Color.blue(second), weight));
        }

        private static int blendChannel(int first, int second, float weight) {
            return Math.round(first * (1f - weight) + second * weight);
        }
    }
}
