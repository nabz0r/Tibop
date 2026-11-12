package fr.lettrine.game;

import android.animation.ValueAnimator;
import android.content.ClipDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.List;

final class BoardView extends View {
    interface Interaction {
        void tap(int position);
        void drop(int position, int rackIndex);
    }

    private final Palette palette;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rectangle = new RectF();
    private final Typeface bold = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final Interaction interaction;
    private char[] board = new char[FrenchGame.CELLS];
    private boolean[] blanks = new boolean[FrenchGame.CELLS];
    private final FrenchGame.Tile[] pending = new FrenchGame.Tile[FrenchGame.CELLS];
    private final boolean[] highlights = new boolean[FrenchGame.CELLS];
    private float side;
    private float zoom = 1;
    private float offsetX;
    private float offsetY;
    private int animated = -1;
    private int focused = FrenchGame.CENTER;
    private float animation = 1;
    private ValueAnimator animator;
    private boolean endedScale;

    BoardView(Context context, Palette palette, Interaction interaction) {
        super(context);
        this.palette = palette;
        this.interaction = interaction;
        setFocusable(interaction != null);
        setContentDescription(interaction == null ? "Plateau de la partie" : "Plateau 15 sur 15. Pincez pour zoomer.");
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
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
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }

            @Override public boolean onSingleTapUp(MotionEvent event) {
                int position = position(event.getX(), event.getY());
                if (!endedScale && interaction != null && position >= 0) {
                    performClick();
                    interaction.tap(position);
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

    void zoomBy(float multiplier) { zoomAt(zoom * multiplier, side * 0.5f, side * 0.5f); }

    void resetZoom() { zoomAt(1, side * 0.5f, side * 0.5f); }

    private void zoomAt(float target, float x, float y) {
        float next = Math.max(1f, Math.min(3.5f, target));
        float ratio = next / zoom;
        offsetX = x - (x - offsetX) * ratio;
        offsetY = y - (y - offsetY) * ratio;
        zoom = next;
        clamp();
        invalidate();
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
        clamp();
    }

    private float unit() { return side / 16.2f; }

    private float gridStart() { return unit() * 0.82f; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float unit = unit();
        float start = gridStart();
        float inset = unit * 0.062f;
        rectangle.set(0, 0, side, side);
        paint.setColor(palette.deep());
        canvas.drawRoundRect(rectangle, 10, 10, paint);
        canvas.save();
        canvas.clipRect(0, 0, side, side);
        canvas.translate(offsetX, offsetY);
        canvas.scale(zoom, zoom);
        for (int i = 0; i < FrenchGame.SIZE; i++) {
            label(canvas, String.valueOf(i + 1), start + (i + 0.5f) * unit, start * 0.53f,
                    unit * 0.36f, palette.heroInk(), regular);
            label(canvas, String.valueOf((char) ('A' + i)), start * 0.45f, start + (i + 0.5f) * unit,
                    unit * 0.36f, palette.heroInk(), regular);
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
                paint.setColor(tile != null || highlights[i] ? palette.fresh() : palette.tile());
                canvas.drawRoundRect(rectangle, unit * 0.11f, unit * 0.11f, paint);
                char letter = tile != null ? tile.letter() : board[i];
                boolean blank = tile != null ? tile.blank() : blanks[i];
                label(canvas, String.valueOf(letter), x + unit * 0.45f, y + unit * 0.48f,
                        unit * 0.55f, palette.ink(), bold);
                label(canvas, String.valueOf(blank ? 0 : FrenchGame.value(letter)), x + unit * 0.76f,
                        y + unit * 0.73f, unit * 0.23f, palette.ink(), regular);
                if (blank) {
                    paint.setColor(palette.accent());
                    canvas.drawCircle(x + unit * 0.22f, y + unit * 0.77f, unit * 0.048f, paint);
                }
            } else {
                paint.setColor(palette.bonus(i));
                canvas.drawRoundRect(rectangle, unit * 0.11f, unit * 0.11f, paint);
                String mark = i == FrenchGame.CENTER ? "✦"
                        : FrenchGame.WORD_MULTIPLIER[i] > 1 ? "M" + FrenchGame.WORD_MULTIPLIER[i]
                        : FrenchGame.LETTER_MULTIPLIER[i] > 1 ? "L" + FrenchGame.LETTER_MULTIPLIER[i] : "";
                if (!mark.isEmpty()) {
                    label(canvas, mark, x + unit * 0.5f, y + unit * 0.5f,
                            unit * (i == FrenchGame.CENTER ? 0.60f : 0.30f), palette.ink(), bold);
                }
            }
            if (isFocused() && focused == i) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(unit * 0.085f);
                paint.setColor(palette.accent());
                canvas.drawRoundRect(rectangle, unit * 0.11f, unit * 0.11f, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            if (i == animated && animation < 1) canvas.restore();
        }
        canvas.restore();
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

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) endedScale = false;
        if (event.getPointerCount() > 1) endedScale = true;
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && (zoom > 1 || endedScale)) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }

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
                if (interaction != null) interaction.tap(focused);
                return true;
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
