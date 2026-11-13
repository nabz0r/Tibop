package fr.lettrine.game;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class ScoreCard {
    private static final int SIZE = 1080;
    private static final int FOREST = 0xff182f25;
    private static final int DARK = 0xff0d211a;
    private static final int IVORY = 0xfffbf1da;
    private static final int SAGE = 0xffbad2af;
    private static final int GOLD = 0xffe3c680;
    private static final int NIGHT = 0xff101829;
    private static final int NIGHT_INK = 0xff080e19;
    private static final int AMBER = 0xfff1c47a;
    private static final int MOONLIGHT = 0xffffeacf;
    private static final Typeface SERIF = Typeface.create("serif", Typeface.NORMAL);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private ScoreCard() {}

    static void share(ViewKit ui, String date, String points, String streak, String shareText, String title)
            throws IOException {
        File folder = ScoreCardProvider.folder(ui.context);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cache de partage indisponible.");
        removeExpired(folder);
        File file = new File(folder, UUID.randomUUID() + ".png");
        Bitmap card = render(ui, SIZE, new CardStyleStore(ui.context).selected(), date, points, streak, title);
        boolean encoded = false;
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (!card.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("La carte de score n’a pas pu être créée.");
            }
            encoded = true;
        } finally {
            card.recycle();
            if (!encoded) file.delete();
        }

        Uri attachment = ScoreCardProvider.uri(ui.context, file.getName());
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(ScoreCardProvider.MIME_TYPE)
                .putExtra(Intent.EXTRA_STREAM, attachment)
                .putExtra(Intent.EXTRA_TEXT, shareText)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newUri(ui.context.getContentResolver(), title, attachment));
        ui.context.startActivity(Intent.createChooser(send, title));
    }

    static ImageView preview(ViewKit ui, CardStyleStore.Style style) {
        ImageView sample = new ImageView(ui.context);
        sample.setScaleType(ImageView.ScaleType.FIT_CENTER);
        sample.setImageBitmap(render(ui, 480, style, ui.context.getString(R.string.card_style_preview_date),
                DailyResult.points(ui, 31), "", ui.context.getString(R.string.daily_share)));
        sample.setContentDescription(ui.context.getString(R.string.card_style_preview_label,
                ui.context.getString(style.label)));
        return sample;
    }

    private static Bitmap render(ViewKit ui, int size, CardStyleStore.Style style,
                                 String date, String points, String streak, String title) {
        Bitmap image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.scale(size / (float) SIZE, size / (float) SIZE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        boolean nocturne = style == CardStyleStore.Style.NOCTURNE;
        if (nocturne) drawNocturne(canvas, paint);
        else drawForest(ui, canvas, paint, size);

        int light = nocturne ? MOONLIGHT : IVORY;
        int secondary = nocturne ? AMBER : SAGE;
        int accent = nocturne ? AMBER : GOLD;
        paint.setColor(nocturne ? 0xa8f1c47a : 0x75e3c680);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        canvas.drawRoundRect(27, 27, SIZE - 27, SIZE - 27, 38, 38, paint);
        paint.setStyle(Paint.Style.FILL);

        paint.setColor(light);
        canvas.drawRoundRect(74, 74, 137, 137, 13, 13, paint);
        text(canvas, paint, "L", 91, 122, 45, nocturne ? NIGHT : FOREST, SERIF, 42);
        text(canvas, paint, "Lettrine", 155, 120, 42, light, SERIF, 350);
        text(canvas, paint, title, 73, 300, 90, light, SERIF, 600);
        text(canvas, paint, date, 78, 373, 38, secondary, MEDIUM, 535);

        paint.setColor(accent);
        canvas.drawRoundRect(79, 442, 178, 448, 3, 3, paint);
        text(canvas, paint, points, 68, 664, 138, light, SERIF, 590);
        text(canvas, paint, streak, 80, 744, 43, secondary, MEDIUM, 530);
        return image;
    }

    private static void drawForest(ViewKit ui, Canvas canvas, Paint paint, int size) {
        canvas.drawColor(FOREST);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = size <= 480 ? 2 : 1;
        Bitmap photo = BitmapFactory.decodeResource(ui.context.getResources(), R.drawable.daily_letter_board, options);
        if (photo != null) {
            float width = SIZE * (float) photo.getWidth() / photo.getHeight();
            canvas.drawBitmap(photo, null, new RectF(SIZE - width, 0, SIZE, SIZE), paint);
            photo.recycle();
            paint.setShader(new LinearGradient(SIZE - width, 0, 850, 0, new int[]{FOREST, FOREST, 0x00182f25},
                    new float[]{0, 0.34f, 1}, Shader.TileMode.CLAMP));
            canvas.drawRect(SIZE - width, 0, SIZE, SIZE, paint);
        }
        paint.setShader(new LinearGradient(0, 770, 0, SIZE, 0x000d211a, DARK, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 770, SIZE, SIZE, paint);
        paint.setShader(null);
    }

    private static void drawNocturne(Canvas canvas, Paint paint) {
        paint.setShader(new RadialGradient(878, 564, 860, new int[]{0xff343949, NIGHT, NIGHT_INK},
                new float[]{0, 0.49f, 1}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, SIZE, SIZE, paint);
        paint.setShader(null);

        float cx = 874;
        float cy = 570;
        canvas.save();
        canvas.rotate(-17, cx, cy);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setColor(0x35f1c47a);
        float gridEdge = 311;
        for (int i = 0; i <= 14; i++) {
            float grid = -gridEdge + i * gridEdge / 7;
            canvas.drawLine(cx + grid, cy - gridEdge, cx + grid, cy + gridEdge, paint);
            canvas.drawLine(cx - gridEdge, cy + grid, cx + gridEdge, cy + grid, paint);
        }
        for (int i = 0; i < 4; i++) {
            float edge = gridEdge + 14 + i * 21;
            paint.setColor(i == 0 ? 0x9cf1c47a : 0x42f1c47a);
            canvas.drawRoundRect(cx - edge, cy - edge, cx + edge, cy + edge, 16, 16, paint);
        }
        canvas.restore();

        paint.setColor(0x87f1c47a);
        for (int i = 0; i < 4; i++) {
            float y = 886 + i * 26;
            Path stairs = new Path();
            stairs.moveTo(535 + i * 27, y + 117);
            stairs.lineTo(722 + i * 27, y + 117);
            stairs.lineTo(839 + i * 27, y);
            stairs.lineTo(SIZE, y);
            canvas.drawPath(stairs, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        drawTile(canvas, paint, 815, 491, 131, -17, 'N');
        drawTile(canvas, paint, 955, 454, 131, -17, 'U');
        drawTile(canvas, paint, 850, 633, 131, -17, 'I');
        drawTile(canvas, paint, 990, 594, 131, -17, 'T');

        paint.setShader(new LinearGradient(575, 0, 765, 0, new int[]{NIGHT_INK, 0x95080e19, 0x00080e19},
                new float[]{0, 0.48f, 1}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 765, SIZE, paint);
        paint.setShader(null);
        star(canvas, paint, 867, 258, 34);
        star(canvas, paint, 930, 212, 12);
        star(canvas, paint, 743, 792, 17);
    }

    private static void drawTile(Canvas canvas, Paint paint, float x, float y, float size, float rotation, char letter) {
        float half = size / 2;
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(rotation);
        paint.setColor(0x6c070910);
        canvas.drawRoundRect(-half + 4, -half + 13, half + 9, half + 18, 16, 16, paint);
        paint.setColor(0xff986535);
        canvas.drawRoundRect(-half, -half + 7, half, half + 9, 15, 15, paint);
        paint.setShader(new LinearGradient(-half, -half, half, half, 0xfffae5bc, 0xffc49357, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(-half, -half, half, half, 15, 15, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setColor(0xa8fff0cd);
        canvas.drawRoundRect(-half + 7, -half + 7, half - 7, half - 7, 10, 10, paint);
        paint.setStyle(Paint.Style.FILL);
        text(canvas, paint, String.valueOf(letter), -32, 24, 85, 0xff50351e, SERIF, 69);
        text(canvas, paint, String.valueOf(FrenchGame.value(letter)), 33, 45, 21, 0xff50351e, MEDIUM, 25);
        canvas.restore();
    }

    private static void star(Canvas canvas, Paint paint, float x, float y, float radius) {
        Path rays = new Path();
        rays.moveTo(x, y - radius);
        rays.lineTo(x + radius * 0.15f, y - radius * 0.16f);
        rays.lineTo(x + radius * 0.76f, y);
        rays.lineTo(x + radius * 0.15f, y + radius * 0.16f);
        rays.lineTo(x, y + radius);
        rays.lineTo(x - radius * 0.15f, y + radius * 0.16f);
        rays.lineTo(x - radius * 0.76f, y);
        rays.lineTo(x - radius * 0.15f, y - radius * 0.16f);
        rays.close();
        paint.setColor(AMBER);
        canvas.drawPath(rays, paint);
    }

    private static void text(Canvas canvas, Paint paint, String value, float x, float y, float size, int color,
                             Typeface font, float width) {
        paint.setColor(color);
        paint.setTypeface(font);
        paint.setTextSize(size);
        float measured = paint.measureText(value);
        if (measured > width) paint.setTextSize(size * width / measured);
        canvas.drawText(value, x, y, paint);
    }

    private static void removeExpired(File folder) {
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
        File[] expired = folder.listFiles(file -> file.lastModified() < cutoff);
        if (expired != null) for (File file : expired) file.delete();
    }
}
