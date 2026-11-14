package fr.lettrine.game;

import android.content.Context;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

final class Lexicon {
    private static final int ROOT = 1;
    private int[] first;
    private int[] sibling;
    private int[] frequency;
    private byte[] character;
    private int nodes = ROOT;
    private int[] childBase;
    private int[] childMask;
    private int[] wordRank;
    private BitSet excludedNodes;
    final String label;
    final String license;
    final String hash;
    final int size;
    final GameLanguage language;
    Bot.Policy expert = Bot.BASE_EXPERT;
    boolean trained;

    private Lexicon(String label, String license, String hash, int size, GameLanguage language) {
        this.label = label;
        this.license = license;
        this.hash = hash;
        this.size = size;
        this.language = language;
    }

    static Lexicon load(Context context, GameLanguage language) throws Exception {
        String metadataFile = language == GameLanguage.FRENCH ? "lexicon.json" : "lexicon-en.json";
        Lexicon dictionary = loadAssets(context, language, metadataFile, "words-" + language.code + ".txt");
        if (language != GameLanguage.FRENCH) return dictionary;
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

    static Lexicon loadFrenchClassic(Context context, Lexicon base) throws Exception {
        JSONObject metadata;
        try {
            metadata = readMetadata(context, "lexicon-fr-classic.json");
        } catch (FileNotFoundException absent) {
            // Builds with a supplied French list omit the curated edition.
            return null;
        }
        if (base.language != GameLanguage.FRENCH || !base.hash.equals(metadata.getString("base_sha256"))) {
            throw new IOException("Le lexique classique ne correspond pas au lexique de base.");
        }
        var rawExclusions = metadata.getJSONArray("excluded");
        List<String> excluded = new ArrayList<>(rawExclusions.length());
        for (int i = 0; i < rawExclusions.length(); i++) excluded.add(rawExclusions.getString(i));
        if (base.size - excluded.size() != metadata.getInt("count")) {
            throw new IOException("Liste classique incomplète.");
        }
        return base.filtered(metadata.getString("label"), metadata.getString("sha256"), excluded);
    }

    Lexicon filtered(String label, String hash, List<String> exclusions) throws IOException {
        Lexicon filtered = new Lexicon(label, license, hash, size - exclusions.size(), language);
        // A filtered edition shares the immutable trie and masks only excluded word endings.
        filtered.childBase = childBase;
        filtered.childMask = childMask;
        filtered.wordRank = wordRank;
        filtered.excludedNodes = excludedNodes == null ? new BitSet() : (BitSet) excludedNodes.clone();
        for (String word : exclusions) {
            int node = findNode(word);
            if (rank(node) == 0 || filtered.excludedNodes.get(node)) {
                throw new IOException("Exclusion invalide dans le lexique classique.");
            }
            filtered.excludedNodes.set(node);
        }
        return filtered;
    }

    private static Lexicon loadAssets(Context context, GameLanguage language, String metadataFile, String wordsFile)
            throws Exception {
        JSONObject meta = readMetadata(context, metadataFile);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(wordsFile), StandardCharsets.US_ASCII), 65536)) {
            return read(reader, meta.getString("label"), meta.getString("license"),
                    meta.getString("sha256"), meta.getInt("count"), language);
        }
    }

    private static JSONObject readMetadata(Context context, String metadataFile) throws Exception {
        StringBuilder metadata = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(metadataFile), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) metadata.append(line);
        }
        return new JSONObject(metadata.toString());
    }

    static Lexicon read(BufferedReader reader, String label, String license, String hash, int size)
            throws java.io.IOException {
        return read(reader, label, license, hash, size, GameLanguage.FRENCH);
    }

    static Lexicon read(BufferedReader reader, String label, String license, String hash, int size,
                        GameLanguage language) throws java.io.IOException {
        Lexicon dictionary = new Lexicon(label, license, hash, size, language);
        dictionary.first = new int[16384];
        dictionary.sibling = new int[16384];
        dictionary.frequency = new int[16384];
        dictionary.character = new byte[16384];
        int rank = 0;
        for (String word; (word = reader.readLine()) != null; ) dictionary.add(word, ++rank);
        if (rank != size) throw new IllegalStateException("Liste incomplète");
        dictionary.pack();
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

    int rank(int node) {
        return excludedNodes != null && excludedNodes.get(node) ? 0 : wordRank[node];
    }

    boolean contains(String word) { return rank(findNode(word)) != 0; }

    private int findNode(String word) {
        int node = ROOT;
        for (int i = 0; i < word.length(); i++) {
            int letter = word.charAt(i) - 'A';
            if (letter < 0 || letter >= 26) return 0;
            node = child(node, letter);
            if (node == 0) return 0;
        }
        return node;
    }
}
