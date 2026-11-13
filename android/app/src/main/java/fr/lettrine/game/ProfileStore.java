package fr.lettrine.game;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;

final class ProfileStore {
    static final int MAX_NICKNAME = 24;
    static final int MAX_AGE = 130;
    private static final int PHOTO_SIZE = 384;
    private static final String[] COUNTRIES = Locale.getISOCountries();

    enum Sex { UNSPECIFIED, FEMALE, MALE, NON_BINARY, OTHER }

    record Profile(String nickname, String country, int age, Sex sex) {
        Profile {
            nickname = nickname.trim();
            if (nickname.codePointCount(0, nickname.length()) > MAX_NICKNAME
                    || nickname.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Nickname");
            }
            country = country.trim().toUpperCase(Locale.ROOT);
            if (!country.isEmpty() && !Arrays.asList(COUNTRIES).contains(country)) {
                throw new IllegalArgumentException("Country");
            }
            if (age < 0 || age > MAX_AGE) throw new IllegalArgumentException("Age");
        }
    }

    private final SharedPreferences preferences;
    private final ContentResolver resolver;
    private final AtomicFile photo;

    ProfileStore(Context context) {
        preferences = context.getSharedPreferences("player_profile", Context.MODE_PRIVATE);
        resolver = context.getContentResolver();
        photo = new AtomicFile(new File(context.getFilesDir(), "profile-photo.jpg"));
    }

    String nickname() { return preferences.getString("nickname", ""); }

    Profile read() {
        return new Profile(nickname(), preferences.getString("country", ""), preferences.getInt("age", 0),
                Sex.valueOf(preferences.getString("sex", Sex.UNSPECIFIED.name())));
    }

    boolean save(Profile profile) {
        return preferences.edit().putString("nickname", profile.nickname()).putString("country", profile.country())
                .putInt("age", profile.age()).putString("sex", profile.sex().name()).commit();
    }

    static String[] countryCodes() { return COUNTRIES.clone(); }

    Bitmap photo() {
        try (InputStream stream = photo.openRead()) {
            return BitmapFactory.decodeStream(stream);
        } catch (IOException unreadable) {
            return null;
        }
    }

    void savePhoto(Uri source) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream stream = open(source)) {
            BitmapFactory.decodeStream(stream, null, options);
        }
        if (options.outWidth < 1 || options.outHeight < 1) throw new IOException("Photo illisible.");
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 2 * PHOTO_SIZE) {
            options.inSampleSize *= 2;
        }
        Bitmap decoded;
        try (InputStream stream = open(source)) {
            decoded = BitmapFactory.decodeStream(stream, null, options);
        }
        if (decoded == null) throw new IOException("Photo illisible.");
        Bitmap resized = decoded;
        try {
            Matrix transform = orientation(source);
            int side = Math.min(decoded.getWidth(), decoded.getHeight());
            if (side > PHOTO_SIZE) transform.postScale(PHOTO_SIZE / (float) side, PHOTO_SIZE / (float) side);
            resized = Bitmap.createBitmap(decoded, (decoded.getWidth() - side) / 2,
                    (decoded.getHeight() - side) / 2, side, side, transform, true);
            writePhoto(resized);
        } finally {
            if (resized != decoded) resized.recycle();
            decoded.recycle();
        }
    }

    void clearPhoto() { photo.delete(); }

    private InputStream open(Uri uri) throws IOException {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            throw new IOException("Source de photo invalide.");
        }
        InputStream stream = resolver.openInputStream(uri);
        if (stream == null) throw new IOException("Photo introuvable.");
        return stream;
    }

    private Matrix orientation(Uri source) {
        Matrix matrix = new Matrix();
        int direction;
        try (InputStream stream = open(source)) {
            direction = new ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException malformed) {
            return matrix;
        }
        switch (direction) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1, 1);
            case ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180);
            case ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1, -1);
            case ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90);
                matrix.postScale(-1, 1);
            }
            case ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90);
            case ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90);
                matrix.postScale(-1, 1);
            }
            case ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90);
            default -> { }
        }
        return matrix;
    }

    private void writePhoto(Bitmap bitmap) throws IOException {
        FileOutputStream stream = photo.startWrite();
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)) throw new IOException("Photo illisible.");
            photo.finishWrite(stream);
        } catch (IOException | RuntimeException exception) {
            photo.failWrite(stream);
            throw exception;
        }
    }
}
