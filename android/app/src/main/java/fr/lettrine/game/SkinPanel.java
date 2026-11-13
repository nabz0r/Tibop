package fr.lettrine.game;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.BooleanSupplier;

final class SkinPanel {
    private enum Category { BOARDS, TILES, CARDS }

    private final ViewKit ui;
    private final SkinStore store;
    private final CardStyleStore cardStyles;
    private final GameStore games;
    private final DailyProgress daily;
    private final Runnable applied;
    private final LinearLayout root;
    private final LinearLayout items;
    private final TextView boardsTab;
    private final TextView tilesTab;
    private final TextView cardsTab;
    private final TextView count;
    private Category category = Category.BOARDS;

    SkinPanel(ViewKit ui, SkinStore store, GameStore games, DailyProgress daily, Runnable applied) {
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
        TextView title = ui.text(label(R.string.skins_title), 38, ui.palette.heroInk(), ui.serif);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        hero.addView(title);
        ui.gap(hero, 8);
        TextView intro = ui.body(label(R.string.skins_intro));
        intro.setTextColor(ui.palette.heroInk());
        hero.addView(intro);
        root.addView(hero);

        ui.gap(root, 15);
        LinearLayout tabs = ui.row();
        boardsTab = ui.button(label(R.string.skins_boards), true, () -> select(Category.BOARDS));
        tilesTab = ui.button(label(R.string.skins_tiles), false, () -> select(Category.TILES));
        cardsTab = ui.button(label(R.string.card_styles), false, () -> select(Category.CARDS));
        ui.weight(tabs, boardsTab);
        ui.space(tabs, 6);
        ui.weight(tabs, tilesTab);
        ui.space(tabs, 6);
        ui.weight(tabs, cardsTab);
        root.addView(tabs);
        ui.gap(root, 11);
        count = ui.body("");
        root.addView(count);
        ui.gap(root, 7);
        items = ui.column();
        root.addView(items);
        render();
    }

    View view() { return root; }

    private void select(Category category) {
        if (this.category == category) return;
        this.category = category;
        render();
    }

    private void render() {
        tab(boardsTab, category == Category.BOARDS);
        tab(tilesTab, category == Category.TILES);
        tab(cardsTab, category == Category.CARDS);
        items.removeAllViews();
        int available = 0;
        if (category == Category.CARDS) {
            for (CardStyleStore.Style style : CardStyleStore.Style.values()) {
                if (cardStyles.has(style)) available++;
                addShareStyle(style);
            }
            count.setText(label(R.string.skins_available, available, CardStyleStore.Style.values().length));
            return;
        }
        SkinStore.Inventory inventory = store.refresh(games, daily);
        SkinStore.Selection selection = inventory.selection();
        int total;
        if (category == Category.BOARDS) {
            SkinStore.BoardSkin[] skins = SkinStore.BoardSkin.values();
            total = skins.length;
            for (SkinStore.BoardSkin skin : skins) {
                boolean classic = skin == SkinStore.BoardSkin.CLASSIC;
                boolean active = classic ? selection.board() == null : selection.board() == skin;
                if (inventory.has(skin.reward)) available++;
                Preview preview = new Preview(ui, true, classic ? null : skin, selection.tiles());
                addCard(skin.name, skin.detail, skin.reward, inventory, active, preview, () -> store.equip(skin));
            }
        } else {
            SkinStore.TileSkin[] skins = SkinStore.TileSkin.values();
            total = skins.length;
            for (SkinStore.TileSkin skin : skins) {
                boolean classic = skin == SkinStore.TileSkin.CLASSIC;
                boolean active = classic ? selection.tiles() == null : selection.tiles() == skin;
                if (inventory.has(skin.reward)) available++;
                Preview preview = new Preview(ui, false, selection.board(), classic ? null : skin);
                addCard(skin.name, skin.detail, skin.reward, inventory, active, preview, () -> store.equip(skin));
            }
        }
        count.setText(label(R.string.skins_available, available, total));
    }

    private void tab(TextView tab, boolean selected) {
        tab.setSelected(selected);
        tab.setTextColor(selected ? ui.palette.heroInk() : ui.palette.ink());
        tab.setBackground(ui.touch(selected ? ui.palette.deep() : ui.palette.paper(), 12,
                selected ? 0 : ui.palette.line()));
    }

    private void addCard(int name, int detail, SkinStore.Reward reward, SkinStore.Inventory inventory,
                         boolean active, Preview preview, BooleanSupplier equip) {
        LinearLayout card = ui.card();
        ui.inset(card, 12, 12);
        card.addView(preview, new LinearLayout.LayoutParams(-1, ui.dp(116)));
        ui.gap(card, 10);

        card.addView(styleHeader(name, active, inventory.has(reward), () -> {
            if (!equip.getAsBoolean()) return false;
            applied.run();
            return true;
        }));
        card.addView(ui.body(label(detail)));
        if (!inventory.has(reward)) {
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
        LinearLayout header = ui.row();
        TextView title = ui.title(label(name), 25);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        ui.weight(header, title);
        if (active) {
            TextView badge = ui.text(label(R.string.skins_active), 13, ui.palette.ink(), ui.medium);
            badge.setBackground(ui.shape(ui.palette.ordinary(), 9));
            ui.inset(badge, 8, 6);
            header.addView(badge);
        } else if (unlocked) {
            TextView action = ui.button(label(R.string.skins_equip), false, () -> {
                if (!equip.getAsBoolean()) {
                    Toast.makeText(ui.context, R.string.skins_save_error, Toast.LENGTH_SHORT).show();
                    return;
                }
                render();
                root.announceForAccessibility(label(R.string.skins_equipped_notice, label(name)));
            });
            action.setContentDescription(label(R.string.skins_equip_named, label(name)));
            header.addView(action);
        }
        return header;
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
        private static final String[] MARKS = {"", "L2", "L3", "M2", "M3"};
        private final boolean board;
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

        Preview(ViewKit ui, boolean board, SkinStore.BoardSkin boardSkin, SkinStore.TileSkin tileSkin) {
            super(ui.context);
            this.board = board;
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
            serif = ui.serif;
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
            if (board) drawBoard(canvas, w, h);
            else drawTiles(canvas, w, h);
        }

        private void drawBoard(Canvas canvas, float width, float height) {
            float size = Math.min(width / 7.4f, height / 3.6f);
            float startX = (width - size * 6) / 2;
            float startY = (height - size * 3) / 2;
            for (int i = 0; i < CELL_STYLES.length; i++) {
                float x = startX + i % 6 * size;
                float y = startY + i / 6 * size;
                if (i >= 8 && i <= 10) {
                    drawTile(canvas, x + size * 0.06f, y + size * 0.05f, size * 0.9f, "MOT".charAt(i - 8), i == 10);
                } else {
                    int kind = CELL_STYLES[i];
                    paint.setColor(cells[kind]);
                    canvas.drawRoundRect(x + size * 0.055f, y + size * 0.055f, x + size * 0.945f,
                            y + size * 0.945f, size * 0.11f, size * 0.11f, paint);
                    if (kind > 0) drawText(canvas, MARKS[kind], x + size / 2, y + size / 2, size * 0.25f, cellInk, medium);
                }
            }
        }

        private void drawTiles(Canvas canvas, float width, float height) {
            float size = Math.min(width / 4.7f, height * 0.54f);
            float pitch = size * 1.13f;
            float start = (width - (pitch * 2 + size)) / 2;
            float top = (height - size) / 2;
            for (int i = 0; i < 3; i++) {
                float x = start + i * pitch;
                float y = top + (i == 1 ? -0.06f : 0.04f) * size;
                canvas.save();
                canvas.rotate((i - 1) * 8f, x + size / 2, y + size / 2);
                drawTile(canvas, x, y, size, "MOT".charAt(i), i == 2);
                canvas.restore();
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
            drawText(canvas, String.valueOf(FrenchGame.value(letter)), x + size * 0.79f, y + size * 0.70f,
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
