package fr.lettrine.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

final class FrenchGame {
    static final int SIZE = 15;
    static final int CELLS = SIZE * SIZE;
    static final int CENTER = 7 * SIZE + 7;
    static final int RACK_SIZE = 7;
    static final char JOKER = '?';
    static final int[] LETTER_MULTIPLIER = new int[CELLS];
    static final int[] WORD_MULTIPLIER = new int[CELLS];

    static {
        Arrays.fill(LETTER_MULTIPLIER, 1);
        Arrays.fill(WORD_MULTIPLIER, 1);
        symmetries(WORD_MULTIPLIER, 3, new int[][]{{0,0},{0,7}});
        symmetries(WORD_MULTIPLIER, 2, new int[][]{{1,1},{2,2},{3,3},{4,4},{7,7}});
        symmetries(LETTER_MULTIPLIER, 3, new int[][]{{1,5},{5,5}});
        symmetries(LETTER_MULTIPLIER, 2, new int[][]{{0,3},{2,6},{3,7},{6,6}});
    }

    private static void symmetries(int[] board, int value, int[][] coordinates) {
        for (int[] coord : coordinates) {
            for (int row : new int[]{coord[0], SIZE - 1 - coord[0]}) {
                for (int col : new int[]{coord[1], SIZE - 1 - coord[1]}) {
                    board[row * SIZE + col] = value;
                    board[col * SIZE + row] = value;
                }
            }
        }
    }

    static int value(char letter) {
        return GameLanguage.FRENCH.value(letter);
    }

    static int tileCount(char letter) { return GameLanguage.FRENCH.tileCount(letter); }

    static int step(int pos, int direction, int amount) {
        if (direction == 1) {
            int col = pos % SIZE + amount;
            return col >= 0 && col < SIZE ? pos + amount : -1;
        }
        int row = pos / SIZE + amount;
        return row >= 0 && row < SIZE ? pos + SIZE * amount : -1;
    }

    static boolean touches(char[] board, int pos) {
        for (int direction : new int[]{1, SIZE}) {
            int before = step(pos, direction, -1);
            int after = step(pos, direction, 1);
            if ((before >= 0 && board[before] != 0) || (after >= 0 && board[after] != 0)) return true;
        }
        return false;
    }

    static boolean empty(char[] board) { return board[CENTER] == 0; }

    record Tile(int position, int rackIndex, char letter, boolean blank) {
        Tile {
            if (position < 0 || position >= CELLS || rackIndex < 0 || rackIndex >= RACK_SIZE
                    || letter < 'A' || letter > 'Z') {
                throw new IllegalArgumentException("Jeton invalide.");
            }
        }
    }

    record Preview(int score, String word, List<String> words, String error) {
        static Preview invalid(String message) { return new Preview(0, "", List.of(), message); }
        boolean valid() { return error == null; }
    }

    record FormedWord(String word, int score, boolean touchesExisting) {}

    record Move(int player, String type, String word, int score, List<Tile> tiles, int exchanged,
                String rack, int bagBefore) {}

    private String activeRack() {
        StringBuilder result = new StringBuilder(RACK_SIZE);
        for (char letter : racks.get(active)) result.append(letter);
        return result.toString();
    }

    static Preview preview(char[] board, boolean[] blanks, List<Character> rack,
                           Lexicon lexicon, List<Tile> tiles) {
        GameLanguage language = lexicon.language;
        if (tiles.isEmpty()) return Preview.invalid(language.text("Posez des lettres sur le plateau.", "Place tiles on the board."));
        if (tiles.size() > RACK_SIZE) return Preview.invalid(language.text("Trop de lettres.", "Too many tiles."));
        Tile[] placement = new Tile[CELLS];
        boolean[] used = new boolean[RACK_SIZE];
        int first = tiles.get(0).position;
        boolean sameRow = true;
        boolean sameCol = true;
        int from = first;
        int to = first;
        for (Tile tile : tiles) {
            int pos = tile.position;
            if (board[pos] != 0 || placement[pos] != null || used[tile.rackIndex]
                    || tile.rackIndex >= rack.size()) {
                return Preview.invalid(language.text("Cette case ou cette lettre est indisponible.", "That square or tile is unavailable."));
            }
            char actual = rack.get(tile.rackIndex);
            if ((tile.blank && actual != JOKER) || (!tile.blank && tile.letter != actual)) {
                return Preview.invalid(language.text("Cette lettre n’est pas sur votre chevalet.", "That tile is not on your rack."));
            }
            placement[pos] = tile;
            used[tile.rackIndex] = true;
            sameRow &= pos / SIZE == first / SIZE;
            sameCol &= pos % SIZE == first % SIZE;
            from = Math.min(from, pos);
            to = Math.max(to, pos);
        }
        if (!sameRow && !sameCol) return Preview.invalid(language.text("Alignez les nouvelles lettres.", "Place the new tiles in one row or column."));
        boolean opening = empty(board);
        if (opening && placement[CENTER] == null) return Preview.invalid(language.text("Le premier mot couvre l’étoile centrale.", "The first word must cover the centre star."));
        int direction = wordDirection(board, blanks, placement, tiles, sameRow, language);
        for (int pos = from; pos <= to; pos += direction) {
            if (board[pos] == 0 && placement[pos] == null) {
                return Preview.invalid(language.text("Comblez les trous entre les lettres.", "Fill the gaps between tiles."));
            }
        }
        List<FormedWord> formed = formedWords(board, blanks, placement, tiles, direction, language);
        FormedWord main = formed.get(0);
        if (main.word.length() < 2) return Preview.invalid(language.text("Formez un mot d’au moins deux lettres.", "Make a word of at least two letters."));
        if (!opening && !main.touchesExisting && formed.size() == 1) {
            return Preview.invalid(language.text("Reliez le mot aux lettres déjà posées.", "Connect your word to the tiles already on the board."));
        }
        List<String> words = new ArrayList<>(formed.size());
        int score = 0;
        for (FormedWord word : formed) {
            if (!lexicon.contains(word.word)) return Preview.invalid(language.text("« " + word.word + " » est absent du lexique.", word.word + " is not in the dictionary."));
            words.add(word.word);
            score += word.score;
        }
        if (tiles.size() == RACK_SIZE) score += 50;
        return new Preview(score, main.word, List.copyOf(words), null);
    }

    private static int wordDirection(char[] board, boolean[] blanks, Tile[] placement,
                                     List<Tile> tiles, boolean sameRow, GameLanguage language) {
        if (!sameRow || (tiles.size() == 1 && run(board, blanks, placement, tiles.get(0).position, 1, language).word.length() < 2)) {
            return SIZE;
        }
        return 1;
    }

    private static List<FormedWord> formedWords(char[] board, boolean[] blanks, Tile[] placement,
                                                List<Tile> tiles, int direction, GameLanguage language) {
        List<FormedWord> words = new ArrayList<>();
        words.add(run(board, blanks, placement, tiles.get(0).position, direction, language));
        int other = direction == 1 ? SIZE : 1;
        for (Tile tile : tiles) {
            FormedWord cross = run(board, blanks, placement, tile.position, other, language);
            if (cross.word.length() > 1) words.add(cross);
        }
        return words;
    }

    static List<FormedWord> recordedWords(char[] board, boolean[] blanks, List<Tile> tiles) {
        return recordedWords(board, blanks, tiles, GameLanguage.FRENCH);
    }

    static List<FormedWord> recordedWords(char[] board, boolean[] blanks, List<Tile> tiles, GameLanguage language) {
        if (tiles.isEmpty() || tiles.size() > RACK_SIZE) return List.of();
        Tile[] placement = new Tile[CELLS];
        int first = tiles.get(0).position;
        boolean sameRow = true;
        boolean sameCol = true;
        int from = first;
        int to = first;
        for (Tile tile : tiles) {
            int pos = tile.position;
            if (board[pos] != 0 || placement[pos] != null) return List.of();
            placement[pos] = tile;
            sameRow &= pos / SIZE == first / SIZE;
            sameCol &= pos % SIZE == first % SIZE;
            from = Math.min(from, pos);
            to = Math.max(to, pos);
        }
        if (!sameRow && !sameCol) return List.of();
        int direction = wordDirection(board, blanks, placement, tiles, sameRow, language);
        for (int pos = from; pos <= to; pos += direction) {
            if (board[pos] == 0 && placement[pos] == null) return List.of();
        }
        return formedWords(board, blanks, placement, tiles, direction, language);
    }

    private static FormedWord run(char[] board, boolean[] blanks, Tile[] placement, int pos, int direction,
                                  GameLanguage language) {
        for (int previous; (previous = step(pos, direction, -1)) >= 0; ) {
            if (board[previous] == 0 && placement[previous] == null) break;
            pos = previous;
        }
        StringBuilder word = new StringBuilder();
        int score = 0;
        int multiplier = 1;
        boolean touchesExisting = false;
        for (; pos >= 0; pos = step(pos, direction, 1)) {
            Tile tile = placement[pos];
            if (tile != null) {
                word.append(tile.letter);
                if (!tile.blank) score += language.value(tile.letter) * LETTER_MULTIPLIER[pos];
                multiplier *= WORD_MULTIPLIER[pos];
            } else if (board[pos] != 0) {
                word.append(board[pos]);
                if (!blanks[pos]) score += language.value(board[pos]);
                touchesExisting = true;
            } else {
                break;
            }
        }
        return new FormedWord(word.toString(), score * multiplier, touchesExisting);
    }

    final String id;
    final long seed;
    final String[] names;
    final int[] levels;
    final GameLanguage language;
    final ArrayList<ArrayList<Character>> racks = new ArrayList<>();
    final ArrayList<Character> bag = new ArrayList<>(102);
    final char[] board = new char[CELLS];
    final boolean[] blanks = new boolean[CELLS];
    final int[] scores;
    final ArrayList<Move> history = new ArrayList<>();
    int active;
    int passes;
    String end;

    FrenchGame(String[] names, int[] levels) {
        this(names, levels, GameLanguage.FRENCH);
    }

    FrenchGame(String[] names, int[] levels, GameLanguage language) {
        this(names, levels, new Random().nextLong(), language);
    }

    FrenchGame(String[] names, int[] levels, long seed) {
        this(names, levels, seed, GameLanguage.FRENCH);
    }

    FrenchGame(String[] names, int[] levels, long seed, GameLanguage language) {
        this(UUID.randomUUID().toString(), seed, names, levels, language);
        for (int i = 0; i < 26; i++) {
            char letter = (char) ('A' + i);
            for (int copies = 0; copies < language.tileCount(letter); copies++) bag.add(letter);
        }
        bag.add(JOKER);
        bag.add(JOKER);
        Collections.shuffle(bag, new Random(seed));
        active = new Random(seed ^ 0x4C65747472696E65L).nextInt(names.length);
        for (int i = 0; i < names.length; i++) draw(i);
    }

    FrenchGame(String id, long seed, String[] names, int[] levels) {
        this(id, seed, names, levels, GameLanguage.FRENCH);
    }

    FrenchGame(String id, long seed, String[] names, int[] levels, GameLanguage language) {
        if (names.length < 2 || names.length > 4 || levels.length != names.length) {
            throw new IllegalArgumentException("La partie accepte deux à quatre joueurs.");
        }
        this.id = id;
        this.seed = seed;
        this.names = names.clone();
        this.levels = levels.clone();
        this.language = language;
        scores = new int[names.length];
        for (String name : names) racks.add(new ArrayList<>(RACK_SIZE));
    }

    private void draw(int player) {
        ArrayList<Character> rack = racks.get(player);
        while (rack.size() < RACK_SIZE && !bag.isEmpty()) rack.add(bag.remove(bag.size() - 1));
    }

    Preview play(List<Tile> tiles, Lexicon lexicon) {
        checkActive();
        Preview preview = preview(board, blanks, racks.get(active), lexicon, tiles);
        if (!preview.valid()) return preview;
        String rackBefore = activeRack();
        int bagBefore = bag.size();
        boolean[] used = new boolean[RACK_SIZE];
        for (Tile tile : tiles) {
            board[tile.position] = tile.letter;
            blanks[tile.position] = tile.blank;
            used[tile.rackIndex] = true;
        }
        ArrayList<Character> rack = racks.get(active);
        for (int i = rack.size() - 1; i >= 0; i--) {
            if (used[i]) rack.remove(i);
        }
        scores[active] += preview.score;
        history.add(new Move(active, "play", preview.word, preview.score, List.copyOf(tiles), 0, rackBefore, bagBefore));
        boolean zeroTurn = language.usesDuelRules(names.length) && preview.score == 0;
        draw(active);
        if (rack.isEmpty() && bag.isEmpty()) finish(active);
        else nextTurn(zeroTurn);
        return preview;
    }

    void pass() {
        checkActive();
        history.add(new Move(active, "pass", "", 0, List.of(), 0, activeRack(), bag.size()));
        nextTurn(true);
    }

    void exchange(List<Integer> indices) {
        checkActive();
        ArrayList<Character> rack = racks.get(active);
        if (bag.size() < RACK_SIZE) {
            throw new IllegalArgumentException(language.text("Il faut sept lettres dans le sac.",
                    "The bag must contain at least seven tiles."));
        }
        boolean[] selected = new boolean[RACK_SIZE];
        for (int index : indices) {
            if (index < 0 || index >= rack.size() || selected[index]) {
                throw new IllegalArgumentException(language.text("Sélection de lettres invalide.", "Invalid tile selection."));
            }
            selected[index] = true;
        }
        if (indices.isEmpty()) {
            throw new IllegalArgumentException(language.text("Sélectionnez des lettres à échanger.",
                    "Select the tiles you want to exchange."));
        }
        String rackBefore = activeRack();
        int bagBefore = bag.size();
        ArrayList<Character> returned = new ArrayList<>(indices.size());
        for (int i = rack.size() - 1; i >= 0; i--) {
            if (selected[i]) returned.add(rack.remove(i));
        }
        draw(active);
        bag.addAll(returned);
        Collections.shuffle(bag, new Random(seed ^ (history.size() * 0x9E3779B97F4A7C15L)));
        history.add(new Move(active, "exchange", "", 0, List.of(), indices.size(), rackBefore, bagBefore));
        nextTurn(language.usesDuelRules(names.length));
    }

    private void finish(int outgoing) {
        int total = 0;
        boolean doubleResidue = outgoing >= 0 && language.usesDuelRules(names.length);
        for (int i = 0; i < names.length; i++) {
            int residue = racks.get(i).stream().mapToInt(language::value).sum();
            total += residue;
            if (!doubleResidue) scores[i] -= residue;
        }
        if (outgoing >= 0) scores[outgoing] += doubleResidue ? total * 2 : total;
        end = outgoing >= 0 ? "rack_out" : "consecutive_passes";
    }

    private void nextTurn(boolean zeroTurn) {
        passes = zeroTurn ? passes + 1 : 0;
        if (passes >= names.length * 3) finish(-1);
        else advance();
    }

    private void advance() { active = (active + 1) % names.length; }

    private void checkActive() {
        if (end != null) throw new IllegalStateException(language.text("La partie est terminée.", "The game has ended."));
    }
}
