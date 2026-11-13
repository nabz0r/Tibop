package fr.lettrine.game;

import android.annotation.SuppressLint;
import android.animation.ValueAnimator;
import android.content.ClipDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;

import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import androidx.customview.widget.ExploreByTouchHelper;

import java.util.List;
import java.util.function.Consumer;

@SuppressLint("ViewConstructor") // Boards are created in code with a palette and optional interaction.
final class BoardView extends View {
    interface Interaction {
        void tap(int position);
        void drop(int position, int rackIndex);
        default boolean canTap(int position) { return true; }
        default boolean dragTile(int position) { return false; }
        default void moveTile(int from, int to) { }
    }

    private final int frame;
    private final int coordinates;
    private final int cellInk;
    private final int focus;
    private final int tileFace;
    private final int freshFace;
    private final int tileInk;
    private final GameLanguage language;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rectangle = new RectF();
    private final Path frameClip = new Path();
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final Interaction interaction;
    private final BoardAccessibility accessibility;
    private char[] board = new char[FrenchGame.CELLS];
    private boolean[] blanks = new boolean[FrenchGame.CELLS];
    private final FrenchGame.Tile[] pending = new FrenchGame.Tile[FrenchGame.CELLS];
    private final boolean[] highlights = new boolean[FrenchGame.CELLS];
    private final int[] cellColors = new int[FrenchGame.CELLS];
    private final int tileRim;
    private final int freshRim;
    private final String[] wordBonuses = new String[4];
    private final String[] letterBonuses = new String[4];
    private LinearGradient frameShading;
    private float side;
    private float zoom = 1;
    private Consumer<Float> zoomListener;
    private float offsetX;
    private float offsetY;
    private int animated = -1;
    private int focused = FrenchGame.CENTER;
    private int guided = -1;
    private float animation = 1;
    private ValueAnimator animator;
    private boolean endedScale;
    private int movingTile = -1;
    private boolean tileDragged;
    private float touchX;
    private float touchY;
    private final int touchSlop;

    BoardView(ViewKit ui, Interaction interaction) {
        super(ui.context);
        touchSlop = android.view.ViewConfiguration.get(ui.context).getScaledTouchSlop();
        Palette palette = ui.palette;
        language = ui.language;
        SkinStore.BoardSkin boardSkin = ui.skins.board();
        SkinStore.TileSkin tileSkin = ui.skins.tiles();
        this.interaction = interaction;
        frame = boardSkin == null ? palette.deep() : boardSkin.frame();
        coordinates = boardSkin == null ? palette.heroInk() : boardSkin.coordinates();
        cellInk = boardSkin == null ? palette.ink() : boardSkin.cellInk();
        focus = boardSkin == null ? palette.accent() : boardSkin.focus();
        tileFace = tileSkin == null ? palette.tile() : tileSkin.face();
        freshFace = tileSkin == null ? palette.fresh() : tileSkin.fresh();
        tileInk = ui.tileInk();
        int edge = tileSkin == null ? palette.tileEdge() : tileSkin.edge();
        tileRim = blend(tileFace, edge, 0.56f);
        freshRim = blend(freshFace, tileSkin == null ? palette.accent() : edge, 0.64f);
        for (int i = 0; i < cellColors.length; i++) cellColors[i] = boardSkin == null ? palette.bonus(i) : bonus(boardSkin, i);
        setClickable(interaction != null);
        setContentDescription(ui.context.getString(interaction == null
                ? R.string.board_read_only : R.string.board_interactive));
        accessibility = new BoardAccessibility();
        ViewCompat.setAccessibilityDelegate(this, accessibility);
        for (int multiplier = 2; multiplier <= 3; multiplier++) {
            wordBonuses[multiplier] = ui.context.getString(R.string.board_word_bonus, multiplier);
            letterBonuses[multiplier] = ui.context.getString(R.string.board_letter_bonus, multiplier);
        }
        scaleDetector = new ScaleGestureDetector(ui.context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                endedScale = true;
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                zoomAt(zoom * detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
        scaleDetector.setQuickScaleEnabled(false);
        gestureDetector = new GestureDetector(ui.context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }

            @Override public boolean onSingleTapUp(MotionEvent event) {
                int position = position(event.getX(), event.getY());
                if (!endedScale && interaction != null && position >= 0) {
                    focused = position;
                    performClick();
                }
                return true;
            }

            @Override public boolean onScroll(MotionEvent first, MotionEvent last, float dx, float dy) {
                if (zoom <= 1 || scaleDetector.isInProgress()) return false;
                panBy(-dx, -dy);
                return true;
            }
        });
    }

    private static int bonus(SkinStore.BoardSkin skin, int position) {
        if (FrenchGame.WORD_MULTIPLIER[position] == 3) return skin.tripleWord();
        if (FrenchGame.WORD_MULTIPLIER[position] == 2) return skin.doubleWord();
        if (FrenchGame.LETTER_MULTIPLIER[position] == 3) return skin.tripleLetter();
        if (FrenchGame.LETTER_MULTIPLIER[position] == 2) return skin.doubleLetter();
        return skin.ordinary();
    }

    void update(char[] letters, boolean[] usedBlanks, List<FrenchGame.Tile> staged,
                List<FrenchGame.Tile> recent, int animatePosition) {
        board = letters;
        blanks = usedBlanks;
        java.util.Arrays.fill(pending, null);
        java.util.Arrays.fill(highlights, false);
        for (FrenchGame.Tile tile : staged) pending[tile.position()] = tile;
        for (FrenchGame.Tile tile : recent) highlights[tile.position()] = true;
        if (animatePosition >= 0) {
            if (animator != null) animator.cancel();
            animated = animatePosition;
            animator = ValueAnimator.ofFloat(0.6f, 1f);
            animator.setDuration(220);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(value -> {
                animation = (Float) value.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }
        invalidate();
        accessibility.invalidateRoot();
    }

    void zoomBy(float multiplier) {
        if (zoom == 1 && multiplier > 1 && guided >= 0) {
            float x = gridStart() + (guided % FrenchGame.SIZE + 0.5f) * unit();
            float y = gridStart() + (guided / FrenchGame.SIZE + 0.5f) * unit();
            zoomAt(zoom * multiplier, x, y);
        } else zoomAt(zoom * multiplier, side * 0.5f, side * 0.5f);
    }

    void guide(int square) {
        int previous = guided;
        guided = square;
        if (previous != square) {
            if (previous >= 0) accessibility.invalidateVirtualView(previous);
            if (square >= 0) accessibility.invalidateVirtualView(square);
        }
        invalidate();
    }

    void onZoom(Consumer<Float> listener) {
        zoomListener = listener;
        listener.accept(zoom);
    }

    void resetZoom() { zoomAt(1, side * 0.5f, side * 0.5f); }

    void focusArea(int minRow, int minCol, int maxRow, int maxCol) {
        if (side <= 0) return;
        int extent = Math.max(maxRow - minRow, maxCol - minCol) + 1;
        zoom = Math.max(1f, Math.min(2.15f, side / (unit() * (extent + 2.5f))));
        float centerX = gridStart() + (0.5f + (minCol + maxCol) / 2f) * unit();
        float centerY = gridStart() + (0.5f + (minRow + maxRow) / 2f) * unit();
        offsetX = side * 0.5f - centerX * zoom;
        offsetY = side * 0.5f - centerY * zoom;
        clamp();
        invalidate();
        accessibility.invalidateRoot();
        if (zoomListener != null) zoomListener.accept(zoom);
    }

    private void zoomAt(float target, float x, float y) {
        float next = Math.max(1f, Math.min(3.5f, target));
        float ratio = next / zoom;
        offsetX = x - (x - offsetX) * ratio;
        offsetY = y - (y - offsetY) * ratio;
        zoom = next;
        clamp();
        invalidate();
        accessibility.invalidateRoot();
        if (zoomListener != null) zoomListener.accept(zoom);
    }

    private void clamp() {
        float min = side * (1 - zoom);
        offsetX = Math.max(min, Math.min(0, offsetX));
        offsetY = Math.max(min, Math.min(0, offsetY));
    }

    private boolean panBy(float dx, float dy) {
        float previousX = offsetX;
        float previousY = offsetY;
        offsetX += dx;
        offsetY += dy;
        clamp();
        if (offsetX == previousX && offsetY == previousY) return false;
        invalidate();
        accessibility.invalidateRoot();
        return true;
    }

    @Override protected void onMeasure(int width, int height) {
        int length = MeasureSpec.getSize(width);
        setMeasuredDimension(length, length);
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        if (oldW > 0) {
            offsetX *= w / (float) oldW;
            offsetY *= w / (float) oldW;
        }
        side = Math.min(w, h);
        if (side > 0) {
            float corner = unit() * 0.48f;
            rectangle.set(0, 0, side, side);
            frameClip.rewind();
            frameClip.addRoundRect(rectangle, corner, corner, Path.Direction.CW);
            frameShading = new LinearGradient(0, 0, side, side,
                    blend(frame, coordinates, 0.13f),
                    blend(frame, 0xff000000, 0.28f), Shader.TileMode.CLAMP);
        }
        clamp();
        accessibility.invalidateRoot();
    }

    private float unit() { return side / 16.2f; }

    private float gridStart() { return unit() * 0.82f; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float unit = unit();
        float start = gridStart();
        float inset = unit * 0.051f;
        float rounding = unit * 0.17f;
        canvas.save();
        canvas.clipPath(frameClip);
        paint.setShader(frameShading);
        canvas.drawPaint(paint);
        paint.setShader(null);
        canvas.translate(offsetX, offsetY);
        canvas.scale(zoom, zoom);
        int target = tileDragged ? position(touchX, touchY) : -1;
        for (int i = 0; i < FrenchGame.SIZE; i++) {
            label(canvas, String.valueOf(i + 1), start + (i + 0.5f) * unit, start * 0.53f,
                    unit * 0.36f, coordinates, regular);
            label(canvas, String.valueOf((char) ('A' + i)), start * 0.45f, start + (i + 0.5f) * unit,
                    unit * 0.36f, coordinates, regular);
        }
        for (int i = 0; i < FrenchGame.CELLS; i++) {
            float x = start + i % FrenchGame.SIZE * unit;
            float y = start + i / FrenchGame.SIZE * unit;
            if (i == animated && animation < 1) {
                canvas.save();
                canvas.scale(animation, animation, x + unit * 0.5f, y + unit * 0.5f);
            }
            rectangle.set(x + inset, y + inset, x + unit - inset, y + unit - inset);
            FrenchGame.Tile tile = pending[i];
            if (tile != null || board[i] != 0) {
                boolean fresh = tile != null || highlights[i];
                paint.setColor(fresh ? freshRim : tileRim);
                canvas.drawRoundRect(rectangle, rounding, rounding, paint);
                rectangle.bottom -= unit * 0.11f;
                paint.setColor(fresh ? freshFace : tileFace);
                canvas.drawRoundRect(rectangle, rounding, rounding, paint);
                paint.setColor(0x65ffffff);
                canvas.drawRoundRect(rectangle.left + unit * 0.11f, rectangle.top + unit * 0.037f,
                        rectangle.right - unit * 0.11f, rectangle.top + unit * 0.083f,
                        rounding, rounding, paint);
                rectangle.bottom += unit * 0.11f;
                char letter = tile != null ? tile.letter() : board[i];
                boolean blank = tile != null ? tile.blank() : blanks[i];
                label(canvas, String.valueOf(letter), x + unit * 0.46f, y + unit * 0.43f,
                        unit * 0.55f, tileInk, bold);
                label(canvas, String.valueOf(blank ? 0 : language.value(letter)), x + unit * 0.76f,
                        y + unit * 0.70f, unit * 0.25f, tileInk, bold);
                if (blank) {
                    paint.setColor(tileInk);
                    canvas.drawCircle(x + unit * 0.22f, y + unit * 0.72f, unit * 0.048f, paint);
                }
            } else {
                paint.setColor(cellColors[i]);
                canvas.drawRoundRect(rectangle, rounding, rounding, paint);
                String mark = i == FrenchGame.CENTER ? "✦"
                        : FrenchGame.WORD_MULTIPLIER[i] > 1 ? wordBonuses[FrenchGame.WORD_MULTIPLIER[i]]
                        : FrenchGame.LETTER_MULTIPLIER[i] > 1 ? letterBonuses[FrenchGame.LETTER_MULTIPLIER[i]] : "";
                if (!mark.isEmpty()) {
                    label(canvas, mark, x + unit * 0.5f, y + unit * 0.5f,
                            unit * (i == FrenchGame.CENTER ? 0.57f : 0.33f), cellInk, bold);
                }
            }
            if ((isFocused() && focused == i) || accessibility.getAccessibilityFocusedVirtualViewId() == i
                    || (guided == i && tile == null) || (target == i && board[i] == 0)) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(unit * 0.10f);
                paint.setColor(coordinates);
                canvas.drawRoundRect(rectangle, rounding, rounding, paint);
                rectangle.inset(unit * 0.09f, unit * 0.09f);
                paint.setStrokeWidth(unit * 0.066f);
                paint.setColor(focus);
                canvas.drawRoundRect(rectangle, rounding, rounding, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            if (i == animated && animation < 1) canvas.restore();
        }
        canvas.restore();
        if (tileDragged && pending[movingTile] != null) {
            float half = unit * 0.65f;
            paint.setColor(freshRim);
            canvas.drawRoundRect(touchX - half, touchY - half + half * 0.16f,
                    touchX + half, touchY + half, half * 0.25f, half * 0.25f, paint);
            paint.setColor(freshFace);
            canvas.drawRoundRect(touchX - half, touchY - half, touchX + half, touchY + half * 0.86f,
                    half * 0.25f, half * 0.25f, paint);
            label(canvas, String.valueOf(pending[movingTile].letter()), touchX, touchY - half * 0.06f,
                    half * 1.13f, tileInk, bold);
        }
    }

    private static int blend(int first, int second, float weight) {
        int red = Math.round((first >> 16 & 0xff) * (1 - weight) + (second >> 16 & 0xff) * weight);
        int green = Math.round((first >> 8 & 0xff) * (1 - weight) + (second >> 8 & 0xff) * weight);
        int blue = Math.round((first & 0xff) * (1 - weight) + (second & 0xff) * weight);
        return 0xff000000 | red << 16 | green << 8 | blue;
    }

    private void label(Canvas canvas, String value, float x, float middle, float size, int color, Typeface face) {
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(size);
        paint.setTypeface(face);
        paint.setColor(color);
        canvas.drawText(value, x, middle - (paint.ascent() + paint.descent()) / 2, paint);
    }

    private int position(float x, float y) {
        if (side <= 0) return -1;
        float start = gridStart();
        int col = (int) Math.floor(((x - offsetX) / zoom - start) / unit());
        int row = (int) Math.floor(((y - offsetY) / zoom - start) / unit());
        return row >= 0 && row < FrenchGame.SIZE && col >= 0 && col < FrenchGame.SIZE
                ? row * FrenchGame.SIZE + col : -1;
    }

    @Override public boolean dispatchHoverEvent(MotionEvent event) {
        return accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        AccessibilityNodeInfoCompat node = AccessibilityNodeInfoCompat.wrap(info);
        node.setCollectionInfo(AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(
                FrenchGame.SIZE, FrenchGame.SIZE, false));
        node.setClickable(false);
        node.removeAction(AccessibilityActionCompat.ACTION_CLICK);
        accessibility.addPanActions(node);
    }

    @SuppressLint("ClickableViewAccessibility") // The single-tap detector calls performClick().
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            endedScale = false;
            touchX = event.getX();
            touchY = event.getY();
            int square = position(touchX, touchY);
            movingTile = square >= 0 && interaction != null && interaction.dragTile(square) ? square : -1;
            if (movingTile >= 0) getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (event.getPointerCount() > 1) {
            movingTile = -1;
            tileDragged = false;
            endedScale = true;
        }
        if (movingTile >= 0) {
            if (action == MotionEvent.ACTION_MOVE && (tileDragged
                    || Math.hypot(event.getX() - touchX, event.getY() - touchY) > touchSlop)) {
                tileDragged = true;
                touchX = event.getX();
                touchY = event.getY();
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                int from = movingTile;
                movingTile = -1;
                if (tileDragged) {
                    tileDragged = false;
                    if (action == MotionEvent.ACTION_UP) interaction.moveTile(from, position(event.getX(), event.getY()));
                    invalidate();
                    return true;
                }
            }
        }
        if (action == MotionEvent.ACTION_MOVE && (zoom > 1 || endedScale)) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        return true;
    }

    @Override public boolean performClick() {
        boolean handled = super.performClick();
        if (interaction == null) return handled;
        interaction.tap(focused);
        return true;
    }

    @Override public boolean onDragEvent(DragEvent event) {
        if (interaction == null) return false;
        if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) {
            return event.getClipDescription() != null && event.getClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                    && event.getLocalState() instanceof Integer;
        }
        if (event.getAction() == DragEvent.ACTION_DROP) {
            int position = position(event.getX(), event.getY());
            if (position < 0) return false;
            interaction.drop(position, (Integer) event.getLocalState());
        }
        return true;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        int direction = 0;
        int amount = 0;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP -> { direction = FrenchGame.SIZE; amount = -1; }
            case KeyEvent.KEYCODE_DPAD_DOWN -> { direction = FrenchGame.SIZE; amount = 1; }
            case KeyEvent.KEYCODE_DPAD_LEFT -> { direction = 1; amount = -1; }
            case KeyEvent.KEYCODE_DPAD_RIGHT -> { direction = 1; amount = 1; }
            case KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                return activateCell(focused) || super.onKeyDown(keyCode, event);
            }
            default -> { return super.onKeyDown(keyCode, event); }
        }
        int next = FrenchGame.step(focused, direction, amount);
        if (next < 0) return super.onKeyDown(keyCode, event);
        accessibility.requestKeyboardFocusForVirtualView(next);
        return true;
    }

    @Override protected void onFocusChanged(boolean gain, int direction, Rect previous) {
        super.onFocusChanged(gain, direction, previous);
        if (accessibility == null) return;
        if (gain && !isInTouchMode()) accessibility.requestKeyboardFocusForVirtualView(focused);
        if (!gain) accessibility.clearKeyboardFocusForVirtualView(accessibility.getKeyboardFocusedVirtualViewId());
        invalidate();
    }

    private boolean canActivateCell(int square) {
        return interaction != null && board[square] == 0 && interaction.canTap(square);
    }

    private boolean activateCell(int square) {
        if (!canActivateCell(square)) return false;
        focused = square;
        interaction.tap(square);
        accessibility.sendEventForVirtualView(square, AccessibilityEvent.TYPE_VIEW_CLICKED);
        return true;
    }

    private void cellBounds(int square, Rect bounds) {
        float cellSize = unit() * zoom;
        float left = offsetX + gridStart() * zoom + (square % FrenchGame.SIZE) * cellSize;
        float top = offsetY + gridStart() * zoom + (square / FrenchGame.SIZE) * cellSize;
        bounds.set(Math.round(left), Math.round(top), Math.round(left + cellSize), Math.round(top + cellSize));
    }

    private boolean revealCell(int square) {
        Rect bounds = new Rect();
        cellBounds(square, bounds);
        int edge = Math.round(side);
        float dx = bounds.left < 0 ? -bounds.left : Math.min(0, edge - bounds.right);
        float dy = bounds.top < 0 ? -bounds.top : Math.min(0, edge - bounds.bottom);
        boolean panned = panBy(dx, dy);
        cellBounds(square, bounds);
        return requestRectangleOnScreen(bounds) || panned;
    }

    private String cellDescription(int square) {
        Context context = getContext();
        String coordinate = context.getString(R.string.board_square,
                (char) ('A' + square / FrenchGame.SIZE), square % FrenchGame.SIZE + 1);
        FrenchGame.Tile staged = pending[square];
        char letter = staged == null ? board[square] : staged.letter();
        String detail;
        if (letter == 0) {
            detail = context.getString(R.string.board_accessibility_empty);
        } else {
            boolean blank = staged == null ? blanks[square] : staged.blank();
            int value = blank ? 0 : language.value(letter);
            String points = context.getResources().getQuantityString(R.plurals.practice_points, value, value);
            detail = context.getString(blank ? R.string.board_accessibility_blank : R.string.board_accessibility_tile,
                    letter, points);
            if (staged != null) detail = context.getString(R.string.board_accessibility_staged, detail);
        }
        if (board[square] == 0) {
            String bonus = cellBonusDescription(square);
            if (bonus != null) detail = detail + ". " + bonus;
        }
        if (guided == square && staged == null) {
            detail = detail + ". " + context.getString(R.string.board_accessibility_guided);
        }
        return context.getString(R.string.board_accessibility_cell, coordinate, detail);
    }

    private String cellBonusDescription(int square) {
        Context context = getContext();
        if (square == FrenchGame.CENTER) return context.getString(R.string.board_accessibility_center);
        int word = FrenchGame.WORD_MULTIPLIER[square];
        if (word > 1) return context.getString(R.string.board_accessibility_word, word);
        int letter = FrenchGame.LETTER_MULTIPLIER[square];
        return letter > 1 ? context.getString(R.string.board_accessibility_letter, letter) : null;
    }

    private final class BoardAccessibility extends ExploreByTouchHelper {
        private final Rect bounds = new Rect();
        private final Rect viewport = new Rect();

        BoardAccessibility() { super(BoardView.this); }

        @Override protected int getVirtualViewAt(float x, float y) {
            int square = position(x, y);
            return square >= 0 ? square : INVALID_ID;
        }

        @Override protected void getVisibleVirtualViews(List<Integer> ids) {
            viewport.set(0, 0, Math.round(side), Math.round(side));
            for (int square = 0; square < FrenchGame.CELLS; square++) {
                cellBounds(square, bounds);
                if (Rect.intersects(bounds, viewport)) ids.add(square);
            }
        }

        @SuppressWarnings("deprecation") // ExploreByTouchHelper 1.0 needs bounds in parent coordinates.
        @Override protected void onPopulateNodeForVirtualView(int square, AccessibilityNodeInfoCompat node) {
            node.setContentDescription(cellDescription(square));
            node.setScreenReaderFocusable(true);
            cellBounds(square, bounds);
            viewport.set(0, 0, Math.round(side), Math.round(side));
            if (!bounds.intersect(viewport)) bounds.setEmpty();
            node.setBoundsInParent(bounds);
            if (canActivateCell(square)) {
                node.setClickable(true);
                node.addAction(new AccessibilityActionCompat(AccessibilityNodeInfoCompat.ACTION_CLICK,
                        getContext().getString(pending[square] == null
                                ? R.string.board_accessibility_place : R.string.board_accessibility_recall)));
            }
            node.addAction(AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN);
            addPanActions(node);
        }

        @Override protected void onVirtualViewKeyboardFocusChanged(int square, boolean hasFocus) {
            if (hasFocus) {
                focused = square;
                revealCell(square);
            }
            invalidate();
        }

        @Override public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
            return pan(action) || super.performAccessibilityAction(host, action, arguments);
        }

        @Override protected boolean onPerformActionForVirtualView(int square, int action, Bundle arguments) {
            if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) return activateCell(square);
            if (action == AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN.getId()) return revealCell(square);
            return pan(action);
        }

        private void addPanActions(AccessibilityNodeInfoCompat node) {
            if (zoom <= 1) return;
            float limit = side * (1 - zoom);
            node.setScrollable(true);
            if (offsetX < 0) node.addAction(AccessibilityActionCompat.ACTION_SCROLL_LEFT);
            if (offsetX > limit) node.addAction(AccessibilityActionCompat.ACTION_SCROLL_RIGHT);
            if (offsetY < 0) node.addAction(AccessibilityActionCompat.ACTION_SCROLL_UP);
            if (offsetY > limit) node.addAction(AccessibilityActionCompat.ACTION_SCROLL_DOWN);
        }

        private boolean pan(int action) {
            float distance = side * 0.75f;
            float dx = 0;
            float dy = 0;
            if (action == AccessibilityActionCompat.ACTION_SCROLL_LEFT.getId()) dx = distance;
            else if (action == AccessibilityActionCompat.ACTION_SCROLL_RIGHT.getId()) dx = -distance;
            else if (action == AccessibilityActionCompat.ACTION_SCROLL_UP.getId()) dy = distance;
            else if (action == AccessibilityActionCompat.ACTION_SCROLL_DOWN.getId()) dy = -distance;
            else return false;
            if (!panBy(dx, dy)) return false;
            BoardView.this.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED);
            int focusedSquare = getAccessibilityFocusedVirtualViewId();
            if (focusedSquare != INVALID_ID) {
                cellBounds(focusedSquare, bounds);
                viewport.set(0, 0, Math.round(side), Math.round(side));
                if (!Rect.intersects(bounds, viewport)) {
                    int center = position(side * 0.5f, side * 0.5f);
                    if (center >= 0) getAccessibilityNodeProvider(BoardView.this).performAction(
                            center, AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS, null);
                }
            }
            return true;
        }
    }
}
