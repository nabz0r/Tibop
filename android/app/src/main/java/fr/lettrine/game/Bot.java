package fr.lettrine.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.CancellationException;

final class Bot {
    static final int ADAPTIVE = 5;
    static final int[] RATINGS = {0, 800, 1050, 1300, 1550, 0};
    private static final int ALL_LETTERS = (1 << 26) - 1;
    private static final int SHORTLIST = 36;
    private static final int IMMEDIATE_SCORE_LEVEL = 3;
    private static final int[] KEEP = {
            2, -4, -3, -1, 3, -6, -4, -5, 2, -9, -12, 1, -2, 2, 0, -3, -14, 3, 9, 2, -4, -7,
            -13, -5, -12, -9
    };
    static final Policy BASE_EXPERT = new Policy(100, 23, 9, 0, 3, 12);
    record Policy(int leaveScale, int blankWeight, int sWeight, int turnover, int endPenalty, int width) {}

    record Choice(List<FrenchGame.Tile> tiles, List<Integer> exchange, int level, int score) {}
    private record Candidate(List<FrenchGame.Tile> tiles, int score, double evaluation, String key) {}

    static int adaptiveLevel(int rating, int[] scores, int turns) {
        int target = rating;
        if (turns >= 6 && scores.length == 2) {
            int gap = scores[0] - scores[1];
            target += Math.max(-225, Math.min(225, gap * 3));
        }
        if (target < 925) return 1;
        if (target < 1175) return 2;
        return target < 1425 ? 3 : 4;
    }

    static Choice choose(FrenchGame game, Lexicon lexicon, int level, int rating) {
        int effective = level == ADAPTIVE ? adaptiveLevel(rating, game.scores, game.history.size()) : level;
        return choose(game.board, game.blanks, game.racks.get(game.active), game.bag.size(),
                lexicon, effective, game.seed ^ game.history.size() * 9137L);
    }

    static Choice choose(char[] board, boolean[] blanks, List<Character> rack, int bagSize,
                         Lexicon lexicon, int level, long seed) {
        Search search = new Search(board, blanks, rack, lexicon, bagSize, level, false);
        List<Candidate> top = search.run();
        if (!top.isEmpty()) {
            Candidate best = top.get(0);
            if (level < 4 && top.size() > 1) {
                double target = best.score * (level == 1 ? 0.56 : level == 2 ? 0.76 : 0.94);
                double jitter = level == 1 ? 3.0 : level == 2 ? 2.0 : 0.65;
                Random random = new Random(seed);
                double nearest = Double.POSITIVE_INFINITY;
                for (Candidate candidate : top) {
                    double distance = Math.abs(candidate.score - target) + random.nextDouble() * jitter;
                    if (distance < nearest) {
                        best = candidate;
                        nearest = distance;
                    }
                }
            }
            return new Choice(best.tiles, List.of(), level, best.score);
        }
        List<Integer> returned = new ArrayList<>();
        if (bagSize >= FrenchGame.RACK_SIZE) {
            for (int i = 0; i < rack.size(); i++) {
                char tile = rack.get(i);
                if (tile != FrenchGame.JOKER && (lexicon.language.value(tile) >= 4 || rack.indexOf(tile) != i)) returned.add(i);
            }
            if (returned.isEmpty()) {
                for (int i = 0; i < rack.size(); i++) {
                    if (rack.get(i) != FrenchGame.JOKER) returned.add(i);
                }
            }
        }
        return new Choice(List.of(), returned, level, 0);
    }

    static List<List<FrenchGame.Tile>> scoreCandidates(char[] board, boolean[] blanks, List<Character> rack,
                                                       Lexicon lexicon) {
        Search search = new Search(board, blanks, rack, lexicon, 0, IMMEDIATE_SCORE_LEVEL, true);
        List<List<FrenchGame.Tile>> result = new ArrayList<>();
        for (Candidate candidate : search.run()) result.add(candidate.tiles);
        return result;
    }

    private static final class Search {
        private final char[] board;
        private final boolean[] blanks;
        private final List<Character> rack;
        private final Lexicon lexicon;
        private final int bagSize;
        private final int level;
        private final boolean interruptible;
        private final int[] counts = new int[26];
        private int jokers;
        private final int[] crossMask = new int[FrenchGame.CELLS];
        private final int[] crossScore = new int[FrenchGame.CELLS];
        private final boolean[] crossWord = new boolean[FrenchGame.CELLS];
        private final boolean[] anchors = new boolean[FrenchGame.CELLS];
        private final boolean[] starts = new boolean[FrenchGame.CELLS];
        private final int[] chosenPosition = new int[FrenchGame.RACK_SIZE];
        private final int[] chosenLetter = new int[FrenchGame.RACK_SIZE];
        private final boolean[] chosenBlank = new boolean[FrenchGame.RACK_SIZE];
        private final Policy policy;
        private final int limit;
        private final PriorityQueue<Candidate> shortlist;
        private final HashSet<String> known = new HashSet<>();
        private int direction;

        Search(char[] board, boolean[] blanks, List<Character> rack, Lexicon lexicon, int bagSize, int level,
               boolean interruptible) {
            this.board = board;
            this.blanks = blanks;
            this.rack = rack;
            this.lexicon = lexicon;
            this.bagSize = bagSize;
            this.level = level;
            this.interruptible = interruptible;
            policy = lexicon.expert;
            limit = level == 4 ? policy.width : SHORTLIST;
            shortlist = new PriorityQueue<>(level == 4 ? Comparator.comparingInt(Candidate::score)
                    .thenComparingDouble(Candidate::evaluation)
                    : Comparator.comparingDouble(Candidate::evaluation));
            for (char letter : rack) {
                if (letter == FrenchGame.JOKER) jokers++;
                else counts[letter - 'A']++;
            }
            boolean opening = FrenchGame.empty(board);
            for (int i = 0; i < board.length; i++) {
                anchors[i] = board[i] == 0 && (opening ? i == FrenchGame.CENTER : FrenchGame.touches(board, i));
            }
        }

        List<Candidate> run() {
            for (int axis : new int[]{1, FrenchGame.SIZE}) {
                direction = axis;
                java.util.Arrays.fill(starts, false);
                for (int pos = 0; pos < board.length; pos++) {
                    if (board[pos] == 0) prepareCross(pos);
                    if (!anchors[pos]) continue;
                    int empties = 0;
                    for (int start = pos; start >= 0; start = FrenchGame.step(start, direction, -1)) {
                        if (board[start] == 0 && ++empties > rack.size()) break;
                        int previous = FrenchGame.step(start, direction, -1);
                        if (previous < 0 || board[previous] == 0) starts[start] = true;
                    }
                }
                int mask = 0;
                for (int i = 0; i < counts.length; i++) {
                    if (counts[i] > 0) mask |= 1 << i;
                }
                for (int start = 0; start < board.length; start++) {
                    if (!starts[start]) continue;
                    if (interruptible && Thread.currentThread().isInterrupted()) throw new CancellationException();
                    extend(start, lexicon.root(), mask, 0, 0, 0, 1, 0, false);
                }
            }
            List<Candidate> sorted = new ArrayList<>(shortlist);
            sorted.sort(Comparator.comparingDouble(Candidate::evaluation).reversed().thenComparing(Candidate::key));
            return sorted;
        }

        private void prepareCross(int pos) {
            int perpendicular = direction == 1 ? FrenchGame.SIZE : 1;
            int start = pos;
            int existing = 0;
            for (int previous; (previous = FrenchGame.step(start, perpendicular, -1)) >= 0
                    && board[previous] != 0; ) {
                if (!blanks[previous]) existing += lexicon.language.value(board[previous]);
                start = previous;
            }
            int after = FrenchGame.step(pos, perpendicular, 1);
            crossWord[pos] = start != pos || (after >= 0 && board[after] != 0);
            crossMask[pos] = ALL_LETTERS;
            crossScore[pos] = 0;
            if (!crossWord[pos]) return;
            for (int next = after; next >= 0 && board[next] != 0; next = FrenchGame.step(next, perpendicular, 1)) {
                if (!blanks[next]) existing += lexicon.language.value(board[next]);
            }
            crossScore[pos] = existing;
            int prefix = lexicon.root();
            for (int next = start; next != pos && prefix != 0; next = FrenchGame.step(next, perpendicular, 1)) {
                prefix = lexicon.child(prefix, board[next] - 'A');
            }
            crossMask[pos] = 0;
            if (prefix == 0) return;
            for (int mask = lexicon.mask(prefix); mask != 0; mask &= mask - 1) {
                int letter = Integer.numberOfTrailingZeros(mask);
                int node = lexicon.child(prefix, letter);
                for (int next = after; node != 0 && next >= 0 && board[next] != 0;
                     next = FrenchGame.step(next, perpendicular, 1)) {
                    node = lexicon.child(node, board[next] - 'A');
                }
                if (node != 0 && lexicon.rank(node) != 0) crossMask[pos] |= 1 << letter;
            }
        }

        private void extend(int pos, int node, int rackMask, int placed, int length,
                            int mainScore, int wordMultiplier, int sideScore, boolean connected) {
            if (placed > 0 && length >= 2 && connected && lexicon.rank(node) != 0
                    && (pos < 0 || board[pos] == 0)) {
                int total = mainScore * wordMultiplier + sideScore + (placed == FrenchGame.RACK_SIZE ? 50 : 0);
                consider(placed, total, lexicon.rank(node));
            }
            if (pos < 0) return;
            int next = FrenchGame.step(pos, direction, 1);
            if (board[pos] != 0) {
                int child = lexicon.child(node, board[pos] - 'A');
                if (child != 0) {
                    extend(next, child, rackMask, placed, length + 1,
                            mainScore + (blanks[pos] ? 0 : lexicon.language.value(board[pos])),
                            wordMultiplier, sideScore, connected);
                }
                return;
            }
            if (placed == rack.size()) return;
            int mask = lexicon.mask(node) & crossMask[pos] & (jokers > 0 ? ALL_LETTERS : rackMask);
            int wm = FrenchGame.WORD_MULTIPLIER[pos];
            boolean linked = connected || anchors[pos];
            chosenPosition[placed] = pos;
            while (mask != 0) {
                int letter = Integer.numberOfTrailingZeros(mask);
                int bit = 1 << letter;
                mask &= mask - 1;
                int child = lexicon.child(node, letter);
                chosenLetter[placed] = letter;
                if (counts[letter] > 0) {
                    int val = lexicon.language.value((char) ('A' + letter)) * FrenchGame.LETTER_MULTIPLIER[pos];
                    int cross = crossWord[pos] ? (crossScore[pos] + val) * wm : 0;
                    chosenBlank[placed] = false;
                    if (--counts[letter] == 0) rackMask &= ~bit;
                    extend(next, child, rackMask, placed + 1, length + 1, mainScore + val,
                            wordMultiplier * wm, sideScore + cross, linked);
                    counts[letter]++;
                    rackMask |= bit;
                }
                if (jokers > 0) {
                    int cross = crossWord[pos] ? crossScore[pos] * wm : 0;
                    chosenBlank[placed] = true;
                    jokers--;
                    extend(next, child, rackMask, placed + 1, length + 1, mainScore,
                            wordMultiplier * wm, sideScore + cross, linked);
                    jokers++;
                }
            }
        }

        private void consider(int placed, int score, int rank) {
            double evaluation = score;
            if (level == 4) {
                if (shortlist.size() == limit && score < shortlist.peek().score) return;
                evaluation = 4 * score + leaveValue(placed);
                if (shortlist.size() == limit && score == shortlist.peek().score
                        && evaluation <= shortlist.peek().evaluation) return;
            }
            if (level == 1) evaluation -= Math.min(9, Math.log1p(rank) * 0.65);
            if (level < 4 && shortlist.size() == limit && evaluation <= shortlist.peek().evaluation) return;
            StringBuilder keyBuilder = new StringBuilder(placed * 2);
            for (int i = 0; i < placed; i++) {
                keyBuilder.append((char) chosenPosition[i]);
                keyBuilder.append((char) (chosenLetter[i] + (chosenBlank[i] ? 26 : 0)));
            }
            String key = keyBuilder.toString();
            if (!known.add(key)) return;
            boolean[] used = new boolean[rack.size()];
            List<FrenchGame.Tile> tiles = new ArrayList<>(placed);
            for (int i = 0; i < placed; i++) {
                char letter = (char) ('A' + chosenLetter[i]);
                char tile = chosenBlank[i] ? FrenchGame.JOKER : letter;
                for (int index = 0; index < rack.size(); index++) {
                    if (!used[index] && rack.get(index) == tile) {
                        used[index] = true;
                        tiles.add(new FrenchGame.Tile(chosenPosition[i], index, letter, chosenBlank[i]));
                        break;
                    }
                }
            }
            if (shortlist.size() == limit) known.remove(shortlist.remove().key);
            shortlist.add(new Candidate(List.copyOf(tiles), score, evaluation, key));
        }

        private int leaveValue(int placed) {
            int remaining = rack.size() - placed;
            if (bagSize == 0) {
                if (remaining == 0) return 160;
                int residue = 0;
                for (int i = 0; i < counts.length; i++) {
                    residue += counts[i] * lexicon.language.value((char) ('A' + i));
                }
                return -policy.endPenalty * residue;
            }
            int vowels = 0;
            int doubles = 0;
            int letters = policy.blankWeight * jokers;
            for (int i = 0; i < counts.length; i++) {
                int copies = counts[i];
                if (copies == 0) continue;
                char letter = (char) ('A' + i);
                if ("AEIOU".indexOf(letter) >= 0) vowels += copies;
                letters += copies * (letter == 'S' ? policy.sWeight : KEEP[i]);
                doubles += Math.max(0, copies - 1);
            }
            int imbalance = Math.max(0, Math.abs(2 * vowels - (remaining - jokers)) - 1);
            return policy.leaveScale * (letters - 5 * doubles - 4 * imbalance) / 100
                    - policy.turnover * remaining;
        }
    }
}
