package fr.lettrine.game;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OfflineDefinitionsTest {
    private static final int BUCKETS = 26 * 26;
    private static final int HEADER_BYTES = 4 + (BUCKETS + 1) * 4;

    private static int bucket(String key) { return (key.charAt(0) - 'A') * 26 + key.charAt(1) - 'A'; }

    @Test public void findsExactBoardWordAndMultipleUtf8Senses() throws IOException {
        byte[] pack = pack(Map.of(
                "BA", "BAT\tA flying mammal.\n",
                "ZA", "ZA\tA slice of pizza.\n",
                "CO", "COTE\tCôte. Partie du squelette.\nCOTE\tNote attribuée.\n"
                        + "COTE\tNote attribuée.\nCOTES\tPluriel de côte.\n"));
        assertEquals(List.of("Côte. Partie du squelette.", "Note attribuée."), find(pack, "cote"));
        assertEquals(List.of("Pluriel de côte."), find(pack, "COTES"));
        assertEquals(List.of("A slice of pizza."), find(pack, "ZA"));
        assertTrue(find(pack, "COT").isEmpty());
        assertTrue(find(pack, "CÔTE").isEmpty());
        assertTrue(find(pack, "XF").isEmpty());
    }

    @Test public void skipsToTheOnlyBucketNeededAndRejectsBadRanges() throws IOException {
        byte[] pack = pack(Map.of("AA", "AA\tLava.\n", "RE", "READ\tInterpret text.\n"));
        ByteBuffer bytes = ByteBuffer.wrap(pack);
        bytes.put(HEADER_BYTES, (byte) 0); // A damaged unrelated bucket should not break RE.
        assertEquals(List.of("Interpret text."), find(pack, "READ"));
        assertTrue(find(pack, "AA").isEmpty());

        int startIndex = 4 + bucket("RE") * 4;
        int start = bytes.getInt(startIndex);
        int originalEnd = bytes.getInt(startIndex + 4);
        for (int invalidOffset : List.of(0, 4, start - 1, start, pack.length + 1, -1)) {
            bytes.putInt(startIndex + 4, invalidOffset);
            assertTrue(find(pack, "READ").isEmpty());
        }
        bytes.putInt(startIndex + 4, originalEnd);
        assertTrue(find(Arrays.copyOf(pack, pack.length - 1), "READ").isEmpty());
        assertTrue(find(Arrays.copyOf(pack, HEADER_BYTES - 1), "READ").isEmpty());
        bytes.putInt(0, 0);
        assertTrue(find(pack, "READ").isEmpty());
    }

    private static List<String> find(byte[] pack, String word) {
        return OfflineDefinitions.find(new ByteArrayInputStream(pack), pack.length, word);
    }

    private static byte[] pack(Map<String, String> entries) throws IOException {
        byte[] header = new byte[HEADER_BYTES];
        ByteBuffer index = ByteBuffer.wrap(header).put("WDF1".getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream blocks = new ByteArrayOutputStream();
        for (int i = 0; i < BUCKETS; i++) {
            index.putInt(HEADER_BYTES + blocks.size());
            String key = "" + (char) ('A' + i / 26) + (char) ('A' + i % 26);
            if (entries.containsKey(key)) {
                try (GZIPOutputStream gzip = new GZIPOutputStream(blocks)) {
                    gzip.write(entries.get(key).getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        index.putInt(HEADER_BYTES + blocks.size());
        ByteArrayOutputStream pack = new ByteArrayOutputStream();
        pack.write(header);
        blocks.writeTo(pack);
        return pack.toByteArray();
    }
}
