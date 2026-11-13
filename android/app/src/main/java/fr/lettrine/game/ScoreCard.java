package fr.lettrine.game;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;

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
    private static final Typeface SERIF = Typeface.create("serif", Typeface.NORMAL);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private ScoreCard() {}

    static void share(ViewKit ui, String date, String points, String streak, String shareText, String title)
            throws IOException {
        File folder = ScoreCardProvider.folder(ui.context);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cache de partage indisponible.");
        removeExpired(folder);
        File file = new File(folder, UUID.randomUUID() + ".png");
        Bitmap card = render(ui, date, points, streak, title);
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

    private static Bitmap render(ViewKit ui, String date, String points, String streak, String title) {
        Bitmap image = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawColor(FOREST);

        Bitmap photo = BitmapFactory.decodeResource(ui.context.getResources(), R.drawable.daily_letter_board);
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

        paint.setColor(0x75e3c680);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        canvas.drawRoundRect(27, 27, SIZE - 27, SIZE - 27, 38, 38, paint);
        paint.setStyle(Paint.Style.FILL);

        paint.setColor(IVORY);
        canvas.drawRoundRect(74, 74, 137, 137, 13, 13, paint);
        text(canvas, paint, "L", 91, 122, 45, FOREST, SERIF, 42);
        text(canvas, paint, "Lettrine", 155, 120, 42, IVORY, SERIF, 350);
        text(canvas, paint, title, 73, 300, 90, IVORY, SERIF, 600);
        text(canvas, paint, date, 78, 373, 38, SAGE, MEDIUM, 535);

        paint.setColor(GOLD);
        canvas.drawRoundRect(79, 442, 178, 448, 3, 3, paint);
        text(canvas, paint, points, 68, 664, 138, IVORY, SERIF, 590);
        text(canvas, paint, streak, 80, 744, 43, SAGE, MEDIUM, 530);
        return image;
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
