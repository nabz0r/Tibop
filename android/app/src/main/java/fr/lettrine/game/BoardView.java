package fr.lettrine.game;

import android.annotation.SuppressLint;
import android.animation.ValueAnimator;
import android.content.ClipDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.List;
import java.util.function.Consumer;

@SuppressLint("ViewConstructor") // Boards are created in code with a palette and optional interaction.
final class BoardView extends View {
    interface Interaction {
        void tap(int position);
        void drop(int position, int rackIndex);
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
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rectangle = new RectF();
    private final Path frameClip = new Path();
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final Interaction interaction;
    private char[] board = new char[FrenchGame.CELLS];
    private boolean[] blanks = new boolean[FrenchGame.CELLS];
    private final FrenchGame.Tile[] pending = new FrenchGame.Tile[FrenchGame.CELLS];
    private final boolean[] highlights = new boolean[FrenchGame.CELLS];
    private final int[] cellColors = new int[FrenchGame.CELLS];
    private final int tileRim;
    private final int freshRim;
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
        setFocusable(interaction != null);
        setClickable(interaction != null);
        setContentDescription(interaction == null ? "Plateau de la partie" : "Plateau 15 sur 15. Pincez pour zoomer.");
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
                offsetX -= dx;
                offsetY -= dy;
                clamp();
                invalidate();
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
    }

    void zoomBy(float multiplier) {
        if (zoom == 1 && multiplier > 1 && guided >= 0) {
            float x = gridStart() + (guided % FrenchGame.SIZE + 0.5f) * unit();
            float y = gridStart() + (guided / FrenchGame.SIZE + 0.5f) * unit();
            zoomAt(zoom * multiplier, x, y);
        } else zoomAt(zoom * multiplier, side * 0.5f, side * 0.5f);
    }

    void guide(int square) {
        guided = square;
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
        if (zoomListener != null) zoomListener.accept(zoom);
    }

    private void clamp() {
        float min = side * (1 - zoom);
        offsetX = Math.max(min, Math.min(0, offsetX));
        offsetY = Math.max(min, Math.min(0, offsetY));
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
                label(canvas, String.valueOf(blank ? 0 : FrenchGame.value(letter)), x + unit * 0.76f,
                        y + unit * 0.70f, unit * 0.25f, tileInk, bold);
                if (blank) {
                    paint.setColor(tileInk);
                    canvas.drawCircle(x + unit * 0.22f, y + unit * 0.72f, unit * 0.048f, paint);
                }
            } else {
                paint.setColor(cellColors[i]);
                canvas.drawRoundRect(rectangle, rounding, rounding, paint);
                String mark = i == FrenchGame.CENTER ? "✦"
                        : FrenchGame.WORD_MULTIPLIER[i] > 1 ? "M" + FrenchGame.WORD_MULTIPLIER[i]
                        : FrenchGame.LETTER_MULTIPLIER[i] > 1 ? "L" + FrenchGame.LETTER_MULTIPLIER[i] : "";
                if (!mark.isEmpty()) {
                    label(canvas, mark, x + unit * 0.5f, y + unit * 0.5f,
                            unit * (i == FrenchGame.CENTER ? 0.57f : 0.33f), cellInk, bold);
                }
            }
            if ((isFocused() && focused == i) || (guided == i && tile == null) || (target == i && board[i] == 0)) {
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
        float start = gridStart();
        int col = (int) Math.floor(((x - offsetX) / zoom - start) / unit());
        int row = (int) Math.floor(((y - offsetY) / zoom - start) / unit());
        return row >= 0 && row < FrenchGame.SIZE && col >= 0 && col < FrenchGame.SIZE
                ? row * FrenchGame.SIZE + col : -1;
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
                return performClick();
            }
            default -> { return super.onKeyDown(keyCode, event); }
        }
        int next = FrenchGame.step(focused, direction, amount);
        if (next >= 0) {
            focused = next;
            announceForAccessibility("Case " + (char) ('A' + next / FrenchGame.SIZE) + (next % FrenchGame.SIZE + 1));
            invalidate();
        }
        return true;
    }
}
