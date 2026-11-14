package fr.lettrine.game;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

final class GameNavigation {
    enum Tab { PLAY, FRIENDS, HISTORY, PROFILE }

    private GameNavigation() {}

    static View item(ViewKit ui, Tab tab, String name, boolean active, Runnable action) {
        LinearLayout item = ui.column();
        item.setGravity(Gravity.CENTER);
        item.setMinimumHeight(ui.dp(58));
        ui.inset(item, 1, 5);
        item.setBackground(ui.touch(active ? ui.palette.ordinary() : ui.palette.paper(), 16, 0));
        item.setContentDescription(name);
        item.setSelected(active);
        item.setFocusable(true);
        item.setOnClickListener(view -> action.run());

        int color = active ? ui.palette.deep() : ui.palette.muted();
        TabIcon icon = new TabIcon(ui, tab, color);
        if (active) icon.setBackground(ui.arcadeKey(ui.palette.paper(), ui.palette.paper(), ui.palette.line()));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(icon, new LinearLayout.LayoutParams(ui.dp(46), ui.dp(26)));
        ui.gap(item, 3);
        var configuration = ui.context.getResources().getConfiguration();
        int labelSize = configuration.screenWidthDp < 340 && configuration.fontScale > 1.3f ? 10 : 11;
        TextView title = ui.text(name, labelSize, color, active ? ui.display : ui.medium);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(title, new LinearLayout.LayoutParams(-1, -2));
        return item;
    }

    private static final class TabIcon extends View {
        private final Tab tab;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int size;

        TabIcon(ViewKit ui, Tab tab, int color) {
            super(ui.context);
            this.tab = tab;
            size = ui.dp(20);
            paint.setColor(color);
            paint.setStrokeWidth(1.65f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
            canvas.scale(size / 24f, size / 24f);
            switch (tab) {
                case PLAY -> {
                    canvas.drawRoundRect(3, 3, 21, 21, 4, 4, paint);
                    path.reset();
                    path.moveTo(10, 8);
                    path.lineTo(16, 12);
                    path.lineTo(10, 16);
                    path.close();
                    canvas.drawPath(path, paint);
                }
                case FRIENDS -> {
                    canvas.drawCircle(9, 8, 3.3f, paint);
                    canvas.drawArc(2.5f, 13.5f, 15.5f, 23, 180, 180, false, paint);
                    canvas.drawArc(13, 4, 20, 11, -68, 142, false, paint);
                    canvas.drawArc(11, 13.5f, 23, 23, 275, 80, false, paint);
                }
                case HISTORY -> {
                    canvas.drawCircle(12, 12, 8.5f, paint);
                    canvas.drawLine(12, 7, 12, 12, paint);
                    canvas.drawLine(12, 12, 15.5f, 14, paint);
                }
                case PROFILE -> {
                    canvas.drawCircle(12, 8.5f, 3.2f, paint);
                    canvas.drawArc(5, 13.5f, 19, 23, 180, 180, false, paint);
                }
            }
            canvas.restore();
        }
    }
}
