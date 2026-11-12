package fr.lettrine.game;

import android.content.Context;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class Lexicon {
    private static final int ROOT = 1;
    private int[] first = new int[16384];
    private int[] sibling = new int[16384];
    private int[] frequency = new int[16384];
    private byte[] character = new byte[16384];
    private int nodes = ROOT;
    private int[] childBase;
    private int[] childMask;
    private int[] wordRank;
    final String label;
    final String license;
    final String hash;
    final int size;
    Bot.Policy expert = Bot.BASE_EXPERT;
    boolean trained;

    private Lexicon(JSONObject meta) throws Exception {
        label = meta.getString("label");
        license = meta.getString("license");
        hash = meta.getString("sha256");
        size = meta.getInt("count");
    }

    static Lexicon load(Context context) throws Exception {
        StringBuilder metadata = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("lexicon.json"), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) metadata.append(line);
        }
        Lexicon dictionary = new Lexicon(new JSONObject(metadata.toString()));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("words-fr.txt"), StandardCharsets.US_ASCII), 65536)) {
            int rank = 0;
            for (String word; (word = reader.readLine()) != null; ) dictionary.add(word, ++rank);
            if (rank != dictionary.size) throw new IllegalStateException("Liste incomplète");
        }
        dictionary.pack();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("bot-policy.json"), StandardCharsets.UTF_8))) {
            StringBuilder source = new StringBuilder();
            for (String line; (line = reader.readLine()) != null; ) source.append(line);
            JSONObject policy = new JSONObject(source.toString());
            if (policy.optBoolean("promoted") && dictionary.hash.equals(policy.optString("lexicon_sha256"))) {
                JSONObject weights = policy.getJSONArray("levels").getJSONObject(3).getJSONObject("weights");
                int width = weights.getInt("width");
                if (width >= 1 && width <= 36) {
                    dictionary.expert = new Bot.Policy(weights.getInt("leave_scale"), weights.getInt("blank_weight"),
                            weights.getInt("s_weight"), weights.getInt("turnover"), weights.getInt("end_penalty"), width);
                    dictionary.trained = true;
                }
            }
        } catch (java.io.IOException | org.json.JSONException ignored) {
            // Custom lexicons use the reference weights.
        }
        return dictionary;
    }

    private void add(String word, int rank) {
        int node = ROOT;
        for (int i = 0; i < word.length(); i++) {
            byte letter = (byte) (word.charAt(i) - 'A');
            int child = first[node];
            int previous = 0;
            while (child != 0 && character[child] < letter) {
                previous = child;
                child = sibling[child];
            }
            if (child == 0 || character[child] != letter) {
                int inserted = ++nodes;
                if (inserted == first.length) {
                    int capacity = first.length * 2;
                    first = Arrays.copyOf(first, capacity);
                    sibling = Arrays.copyOf(sibling, capacity);
                    frequency = Arrays.copyOf(frequency, capacity);
                    character = Arrays.copyOf(character, capacity);
                }
                character[inserted] = letter;
                sibling[inserted] = child;
                if (previous == 0) first[node] = inserted;
                else sibling[previous] = inserted;
                child = inserted;
            }
            node = child;
        }
        frequency[node] = rank;
    }

    private void pack() {
        childBase = new int[nodes + 1];
        childMask = new int[nodes + 1];
        wordRank = new int[nodes + 1];
        int[] order = new int[nodes + 1];
        order[ROOT] = ROOT;
        int end = ROOT;
        for (int node = ROOT; node <= end; node++) {
            int original = order[node];
            wordRank[node] = frequency[original];
            childBase[node] = end + 1;
            for (int child = first[original]; child != 0; child = sibling[child]) {
                order[++end] = child;
                childMask[node] |= 1 << character[child];
            }
        }
        first = sibling = frequency = null;
        character = null;
    }

    int root() { return ROOT; }

    int mask(int node) { return childMask[node]; }

    int child(int node, int letter) {
        int bit = 1 << letter;
        int mask = childMask[node];
        return (mask & bit) == 0 ? 0 : childBase[node] + Integer.bitCount(mask & (bit - 1));
    }

    int rank(int node) { return wordRank[node]; }

    boolean contains(String word) {
        int node = ROOT;
        for (int i = 0; i < word.length(); i++) {
            int letter = word.charAt(i) - 'A';
            if (letter < 0 || letter >= 26) return false;
            node = child(node, letter);
            if (node == 0) return false;
        }
        return rank(node) != 0;
    }
}
