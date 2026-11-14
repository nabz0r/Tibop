package fr.lettrine.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

final class ArcadeBackdrop extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path ribbon = new Path();
    private final Palette palette;
    private LinearGradient background;

    ArcadeBackdrop(Context context, Palette palette) {
        super(context);
        this.palette = palette;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        background = new LinearGradient(0, 0, width, height, palette.deep(), 0xff202354, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();
        paint.setShader(background);
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        paint.setColor(0x5958f7f0);
        canvas.drawCircle(width * .81f, height * .54f, height * .43f, paint);
        paint.setColor(0x51ffc1e3);
        canvas.drawCircle(width * .96f, height * .32f, height * .31f, paint);
        paint.setColor(0x5535dff2);
        ribbon.reset();
        ribbon.moveTo(width * .48f, height);
        ribbon.lineTo(width * .92f, 0);
        ribbon.lineTo(width, 0);
        ribbon.lineTo(width * .64f, height);
        ribbon.close();
        canvas.drawPath(ribbon, paint);
        paint.setStrokeWidth(Math.max(1, height * .009f));
        for (int i = 0; i < 7; i++) {
            float x = width * (.60f + (i % 4) * .11f);
            float y = height * (.18f + (i / 4) * .55f + (i % 3) * .07f);
            float radius = height * (i % 2 == 0 ? .032f : .018f);
            paint.setColor(i % 2 == 0 ? 0xffffe47a : 0xb8ffffff);
            canvas.drawLine(x - radius, y, x + radius, y, paint);
            canvas.drawLine(x, y - radius, x, y + radius, paint);
        }
    }
}
