package fr.lettrine.game;

import android.content.Context;
import android.widget.ImageView;

final class NabView extends ImageView {
    private final boolean motion;
    private int sprite;
    private int frame;
    private final Runnable blink = this::advanceBlink;

    private static final int[] BLINK_FRAMES = {R.drawable.nab_arcade_blink_half,
            R.drawable.nab_arcade_blink, R.drawable.nab_arcade_blink_half, R.drawable.nab_arcade_idle};

    NabView(Context context, boolean motion, int initialSprite) {
        super(context);
        this.motion = motion;
        sprite = initialSprite;
        setImageResource(sprite);
        setScaleType(ScaleType.FIT_CENTER);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        bounce();
        scheduleBlink();
    }

    @Override protected void onDetachedFromWindow() {
        animate().cancel();
        removeCallbacks(blink);
        super.onDetachedFromWindow();
    }

    void pose(int nextSprite) {
        if (sprite == nextSprite) return;
        sprite = nextSprite;
        setImageResource(sprite);
        scheduleBlink();
        bounce();
    }

    @Override public void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (blink != null) scheduleBlink();
    }

    private void scheduleBlink() {
        removeCallbacks(blink);
        if (sprite != R.drawable.nab_arcade_idle) return;
        frame = 0;
        setImageResource(sprite);
        if (motion && isAttachedToWindow() && getWindowVisibility() == VISIBLE) postDelayed(blink, 3500);
    }

    private void advanceBlink() {
        setImageResource(BLINK_FRAMES[frame++]);
        if (frame == BLINK_FRAMES.length) scheduleBlink();
        else postDelayed(blink, 50);
    }

    private void bounce() {
        if (!motion || !isAttachedToWindow()) return;
        animate().cancel();
        setScaleX(.88f);
        setScaleY(.88f);
        animate().scaleX(1f).scaleY(1f).setDuration(310)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.55f)).start();
    }
}
