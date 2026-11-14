package fr.lettrine.game;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.regex.Pattern;

public final class ScoreCardProvider extends ContentProvider {
    static final String MIME_TYPE = "image/png";
    private static final String[] COLUMNS = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
    private static final Pattern CARD_PATH = Pattern.compile("/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.png");

    static File folder(Context context) { return new File(context.getCacheDir(), "scorecards"); }

    static Uri uri(Context context, String name) {
        return new Uri.Builder().scheme("content").authority(authority(context)).appendPath(name).build();
    }

    private static String authority(Context context) { return context.getPackageName() + ".scorecards"; }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { return file(uri) != null ? MIME_TYPE : null; }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                                  String sortOrder) {
        String[] columns = projection == null ? COLUMNS : projection;
        MatrixCursor result = new MatrixCursor(columns, 1);
        File file = file(uri);
        if (file != null && file.isFile()) {
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                row[i] = switch (columns[i]) {
                    case OpenableColumns.DISPLAY_NAME -> "tibop-bats-mon-score.png";
                    case OpenableColumns.SIZE -> file.length();
                    default -> null;
                };
            }
            result.addRow(row);
        }
        return result;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = file(uri);
        if (!"r".equals(mode) || file == null || !file.isFile()) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private File file(Uri uri) {
        Context context = getContext();
        String path = uri.getEncodedPath();
        if (context == null || !"content".equals(uri.getScheme()) || !authority(context).equals(uri.getAuthority())
                || uri.getQuery() != null || uri.getFragment() != null || path == null
                || !CARD_PATH.matcher(path).matches()) return null;
        return new File(folder(context), path.substring(1));
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
