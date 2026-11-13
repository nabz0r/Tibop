package fr.lettrine.game;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.AtomicFile;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;

final class PersonalLexicon {
    static final int MAX_SOURCE_BYTES = 12 * 1024 * 1024;
    private static final int MAX_FORMS = 500_000;
    private static final int MAX_NODES = 1_500_000;
    private static final int MAX_LINE_LENGTH = 64;
    private static final int MAX_WORD_LENGTH = FrenchGame.SIZE;
    private static final Pattern WORD = Pattern.compile("[A-Z]{2," + MAX_WORD_LENGTH + "}");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final int FORMAT = 0x57524c31;
    private static final Object LOCK = new Object();

    record Entry(String hash, int count) {
        Entry {
            if (!hash.matches("[0-9a-f]{64}") || count < 1 || count > MAX_FORMS) {
                throw new IllegalArgumentException("Invalid lexicon identity");
            }
        }
    }

    record Prepared(Entry entry, byte[] words) {}

    private final Context context;
    private final GameLanguage language;
    private final AtomicFile active;

    PersonalLexicon(Context context, GameLanguage language) {
        this.context = context.getApplicationContext();
        this.language = language;
        active = new AtomicFile(new File(context.getFilesDir(), "personal-lexicon-" + language.code + ".bin"));
    }

    static Intent picker() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    Entry current() throws IOException {
        synchronized (LOCK) {
            try (DataInputStream input = new DataInputStream(active.openRead())) {
                return readHeader(input);
            } catch (FileNotFoundException missing) {
                if (active.getBaseFile().exists()) throw missing;
                return null;
            }
        }
    }

    Entry importFrom(Uri source) throws IOException {
        if (source == null || !ContentResolver.SCHEME_CONTENT.equals(source.getScheme())) {
            throw new IOException("Not a document");
        }
        Prepared prepared;
        try (InputStream input = context.getContentResolver().openInputStream(source)) {
            if (input == null) throw new IOException("Document unavailable");
            prepared = prepare(input, language);
        } catch (SecurityException unavailable) {
            throw new IOException("Document unavailable", unavailable);
        }
        synchronized (LOCK) {
            FileOutputStream file = active.startWrite();
            try {
                DataOutputStream output = new DataOutputStream(file);
                output.writeInt(FORMAT);
                output.writeUTF(prepared.entry().hash());
                output.writeInt(prepared.entry().count());
                output.writeInt(prepared.words().length);
                output.write(prepared.words());
                output.flush();
                file.getFD().sync();
                active.finishWrite(file);
            } catch (IOException failure) {
                active.failWrite(file);
                throw failure;
            }
            if (!prepared.entry().equals(current())) throw new IOException("Lexicon was not saved");
        }
        return prepared.entry();
    }

    Lexicon load(Entry entry) throws IOException {
        byte[] words;
        synchronized (LOCK) {
            try (DataInputStream input = new DataInputStream(active.openRead())) {
                Entry stored = readHeader(input);
                int size = input.readInt();
                if (!entry.equals(stored) || size < 3 || size > MAX_FORMS * (MAX_WORD_LENGTH + 1)) {
                    throw new IOException("Lexicon changed or is unreadable");
                }
                words = new byte[size];
                input.readFully(words);
                if (input.read() != -1 || !entry.hash().equals(hash(words))) {
                    throw new IOException("Lexicon checksum mismatch");
                }
            }
        }
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(words), StandardCharsets.US_ASCII))) {
            return Lexicon.read(input, context.getString(R.string.personal_lexicon_title), "user-provided",
                    entry.hash(), entry.count(), language);
        } catch (IllegalStateException invalid) {
            throw new IOException("Lexicon word count mismatch", invalid);
        }
    }

    File matchDirectory(Entry entry) {
        return context.getDir("personal-matches-" + language.code + "-" + entry.hash(), Context.MODE_PRIVATE);
    }

    void remove() throws IOException {
        synchronized (LOCK) {
            active.delete();
            if (active.getBaseFile().exists()) throw new IOException("Lexicon could not be removed");
        }
    }

    private static Entry readHeader(DataInputStream input) throws IOException {
        if (input.readInt() != FORMAT) throw new IOException("Unknown lexicon format");
        try {
            return new Entry(input.readUTF(), input.readInt());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid lexicon identity", invalid);
        }
    }

    static Prepared prepare(InputStream source, GameLanguage language) throws IOException {
        TreeSet<String> forms = new TreeSet<>();
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        var reader = new BufferedReader(new InputStreamReader(new BoundedInput(source), decoder));
        StringBuilder line = new StringBuilder(MAX_LINE_LENGTH);
        boolean firstCharacter = true;
        for (int value; (value = reader.read()) != -1; ) {
            if (firstCharacter) {
                firstCharacter = false;
                if (value == '\ufeff') continue;
            }
            if (value == '\n' || value == '\r') {
                addForm(forms, line, language);
                line.setLength(0);
            } else {
                if (line.length() == MAX_LINE_LENGTH) throw new IOException("Word line is too long");
                line.append((char) value);
            }
        }
        addForm(forms, line, language);
        if (forms.isEmpty()) throw new IOException("No playable words");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        String previous = "";
        int nodes = 1;
        for (String form : forms) {
            int common = 0;
            while (common < previous.length() && common < form.length()
                    && previous.charAt(common) == form.charAt(common)) common++;
            nodes += form.length() - common;
            if (nodes > MAX_NODES) throw new IOException("Lexicon has too many letter sequences");
            bytes.write(form.getBytes(StandardCharsets.US_ASCII));
            bytes.write('\n');
            previous = form;
        }
        byte[] words = bytes.toByteArray();
        return new Prepared(new Entry(hash(words), forms.size()), words);
    }

    private static void addForm(TreeSet<String> forms, StringBuilder line, GameLanguage language) throws IOException {
        String value = line.toString().strip();
        if (value.isEmpty()) return;
        if (language == GameLanguage.FRENCH) {
            value = Normalizer.normalize(value.replace("Œ", "OE").replace("œ", "oe")
                    .replace("Æ", "AE").replace("æ", "ae"), Normalizer.Form.NFD);
            value = MARKS.matcher(value).replaceAll("");
        }
        if (!value.chars().allMatch(letter -> letter >= 'A' && letter <= 'Z' || letter >= 'a' && letter <= 'z')) {
            throw new IOException("Expected a word with letters A-Z");
        }
        String form = value.toUpperCase(Locale.ROOT);
        if (!WORD.matcher(form).matches()) throw new IOException("Expected a word of 2–15 letters");
        if (forms.add(form) && forms.size() > MAX_FORMS) throw new IOException("Lexicon has too many words");
    }

    private static String hash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder text = new StringBuilder(64);
            char[] hex = "0123456789abcdef".toCharArray();
            for (byte value : digest) text.append(hex[(value & 0xff) >>> 4]).append(hex[value & 15]);
            return text.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static final class BoundedInput extends FilterInputStream {
        private int remaining = MAX_SOURCE_BYTES;

        BoundedInput(InputStream input) { super(input); }

        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0 && --remaining < 0) throw new EOFException("Lexicon file is too large");
            return value;
        }

        @Override public int read(byte[] buffer, int start, int length) throws IOException {
            int count = in.read(buffer, start, Math.min(length, remaining + 1));
            if (count >= 0 && (remaining -= count) < 0) throw new EOFException("Lexicon file is too large");
            return count;
        }
    }
}
