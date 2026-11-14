package fr.lettrine.game;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

final class OfflineDefinitions {
    private static final int MAGIC = 0x57444631; // WDF1
    private static final int HEADER_BYTES = 4 + (26 * 26 + 1) * 4;
    private static final int MAX_COMPRESSED = 1024 * 1024;
    private static final int MAX_EXPANDED = 5 * 1024 * 1024;
    private static final int MAX_SENSE_LENGTH = 2048;
    private static final int MAX_WORD_LENGTH = 15;
    private static final int MAX_SENSES = 8;
    private static final Pattern WORD = Pattern.compile("[A-Za-z]{2,15}");

    private OfflineDefinitions() {}

    // The pack is read only on demand, off the UI thread.
    static List<String> find(Context context, GameLanguage language, String word) {
        if (language == null || word == null || !WORD.matcher(word).matches()) return List.of();
        String asset = "definitions-" + language.code + ".dpack";
        try (AssetFileDescriptor descriptor = context.getAssets().openFd(asset);
             InputStream input = descriptor.createInputStream()) {
            return find(input, descriptor.getLength(), word);
        } catch (IOException unavailable) {
            return List.of();
        }
    }

    static List<String> find(InputStream source, long length, String word) {
        if (length < HEADER_BYTES || word == null || !WORD.matcher(word).matches()) return List.of();
        String key = word.toUpperCase(Locale.ROOT);
        int bucket = (key.charAt(0) - 'A') * 26 + key.charAt(1) - 'A';

        // Offsets are relative to the start of the asset, including its magic and index.
        try {
            DataInputStream input = new DataInputStream(source);
            if (input.readInt() != MAGIC) return List.of();
            skip(input, (long) bucket * 4);
            long start = Integer.toUnsignedLong(input.readInt());
            long end = Integer.toUnsignedLong(input.readInt());
            if (start < HEADER_BYTES || start >= end || end > length || end - start > MAX_COMPRESSED) {
                return List.of();
            }
            skip(input, start - (4L + 4L * (bucket + 2)));
            byte[] compressed = new byte[(int) (end - start)];
            input.readFully(compressed);
            return matchingSenses(compressed, key);
        } catch (IOException malformed) {
            return List.of();
        }
    }

    private static void skip(InputStream input, long remaining) throws IOException {
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) remaining -= skipped;
            else if (input.read() < 0) throw new IOException("Truncated definition index");
            else remaining--;
        }
    }

    private static List<String> matchingSenses(byte[] compressed, String key) throws IOException {
        byte[] word = key.getBytes(StandardCharsets.US_ASCII);
        byte[] line = new byte[MAX_WORD_LENGTH + 1 + MAX_SENSE_LENGTH];
        byte[] buffer = new byte[8192];
        List<String> senses = new ArrayList<>();
        int length = 0;
        int expanded = 0;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed), buffer.length)) {
            for (int count; (count = gzip.read(buffer)) != -1; ) {
                expanded += count;
                if (expanded > MAX_EXPANDED) return List.of();
                for (int index = 0; index < count; index++) {
                    if (buffer[index] == '\n') {
                        addSense(line, length, word, senses);
                        length = 0;
                    } else if (length <= line.length) {
                        if (length < line.length) line[length] = buffer[index];
                        length++;
                    }
                }
            }
        }
        addSense(line, length, word, senses);
        return List.copyOf(senses);
    }

    private static void addSense(byte[] line, int length, byte[] word, List<String> senses) throws IOException {
        int start = word.length + 1;
        if (length <= start || length - start > MAX_SENSE_LENGTH || senses.size() == MAX_SENSES
                || line[start - 1] != '\t') return;
        for (int index = 0; index < word.length; index++) {
            if (line[index] != word[index]) return;
        }
        String sense = StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(line, start, length - start)).toString().trim();
        if (!sense.isEmpty() && !senses.contains(sense)) senses.add(sense);
    }
}
