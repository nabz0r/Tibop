package fr.lettrine.game;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.json.JSONObject;

public final class MultiplayerValidation {
    private static final int SIDE = 15;
    private static final int MID = 112;
    private static final int MAX = 250;
    private static final String SYMBOLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ?";
    // Keep the independent scoring oracle separate from the Android engine constants.
    private static final int[] FR_VALUES = {1, 3, 3, 2, 1, 4, 2, 4, 1, 8, 10, 1, 2, 1, 1, 3, 8, 1, 1, 1, 1, 4, 10, 10, 10, 10, 0};
    private static final int[] EN_VALUES = {1, 3, 3, 2, 1, 4, 2, 4, 1, 8, 5, 1, 3, 1, 1, 3, 10, 1, 1, 1, 1, 4, 4, 8, 4, 10, 0};
    private static final int[] FR_COUNTS = {9, 2, 2, 3, 15, 2, 2, 2, 8, 1, 1, 5, 3, 6, 6, 2, 1, 6, 6, 6, 6, 2, 1, 1, 1, 1, 2};
    private static final int[] EN_COUNTS = {9, 2, 2, 4, 12, 2, 3, 2, 9, 1, 1, 4, 2, 6, 8, 2, 1, 6, 4, 6, 4, 2, 2, 1, 2, 1, 2};
    private static final String[] BOARD_TOP = {
            "T..a...T...a..T", ".D...b...b...D.", "..D...a.a...D..", "a..D...a...D..a",
            "....D.....D....", ".b...b...b...b.", "..a...a.a...a..", "T..a...D...a..T"};
    private record Dictionary(Lexicon lexicon, Set<String> words, int[] values, int[] counts) {}
    private static class Counts {
        int games, moves, plays, exchanges, passes, jokers, bingos, crosses, rejections, restores, rackOut, endsOnPasses, ties;
        void add(Counts table) {
            games += table.games;
            moves += table.moves;
            plays += table.plays;
            exchanges += table.exchanges;
            passes += table.passes;
            jokers += table.jokers;
            bingos += table.bingos;
            crosses += table.crosses;
            rejections += table.rejections;
            restores += table.restores;
            rackOut += table.rackOut;
            endsOnPasses += table.endsOnPasses;
            ties += table.ties;
        }
        @Override public String toString() {
            return String.format(Locale.ROOT,
                    "games=%d turns=%d plays=%d exchanges=%d passes=%d jokers=%d bingos=%d cross=%d "
                            + "rejected=%d restores=%d rack_out=%d scoreless_end=%d ties=%d",
                    games, moves, plays, exchanges, passes, jokers, bingos, crosses, rejections, restores,
                    rackOut, endsOnPasses, ties);
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        int games = Integer.parseInt(args[1]);
        long seed = Long.parseLong(args[2]);
        long[] seeds = new Random(seed).longs(games).toArray();
        var executor = Executors.newFixedThreadPool(Integer.parseInt(args[3]));
        Counts all = new Counts();
        try {
            for (GameLanguage language : GameLanguage.values()) {
                Dictionary dictionary = load(root, language);
                System.out.printf("dictionary=%s, digest=%s, forms=%d%n", language.code, dictionary.lexicon.hash, dictionary.words.size());
                for (int seats = 2; seats <= 4; seats++) {
                    int count = seats;
                    List<Callable<Counts>> tasks = Arrays.stream(seeds).<Callable<Counts>>mapToObj(
                            draw -> () -> play(dictionary, draw, count)).toList();
                    Counts total = new Counts();
                    for (var future : executor.invokeAll(tasks)) total.add(future.get());
                    all.add(total);
                    System.out.printf("lang=%s seats=%d %s%n", language.code, seats, total);
                }
            }
        } finally {
            executor.shutdown();
        }
        System.out.println("TOTAL " + all);
    }
    private static Dictionary load(Path root, GameLanguage language) throws Exception {
        Path source = root.resolve("data/" + language.code + "/words.txt");
        List<String> lines = Files.readAllLines(source);
        boolean fr = language == GameLanguage.FRENCH;
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        String baseHash = HexFormat.of().formatHex(sha256.digest(Files.readAllBytes(source)));
        Lexicon lexicon;
        try (var reader = Files.newBufferedReader(source)) {
            lexicon = Lexicon.read(reader, fr ? "Grammalecte 7.7" : "ENABLE2K", "", baseHash, lines.size(), language);
        }
        Set<String> words = new HashSet<>(lines);
        if (fr) {
            List<String> excluded = Files.readAllLines(root.resolve("data/fr/classic-exclusions.txt"));
            words.removeAll(excluded);
            var filtered = String.join("\n", lines.stream().filter(words::contains).toList()) + "\n";
            String digest = HexFormat.of().formatHex(sha256.digest(filtered.getBytes(StandardCharsets.US_ASCII)));
            lexicon = lexicon.filtered("Grammalecte 7.7 · Classique", digest, excluded);
        }
        return new Dictionary(lexicon, words, fr ? FR_VALUES : EN_VALUES, fr ? FR_COUNTS : EN_COUNTS);
    }
    private static Counts play(Dictionary dictionary, long seed, int seats) throws Exception {
        GameLanguage language = dictionary.lexicon.language;
        String[] names = IntStream.range(0, seats).mapToObj(index -> "Seat " + index).toArray(String[]::new);
        FrenchGame game = new FrenchGame(names, IntStream.rangeClosed(1, seats).toArray(), seed, language);
        Random random = new Random(seed ^ 0xDE213L);
        var counts = new Counts();
        int[] totals = new int[seats];
        boolean onlyPass = random.nextInt(5) == 0;
        boolean duel = language == GameLanguage.ENGLISH && seats == 2;
        int zero = 0;
        checkInventory(game, dictionary.counts);
        while (game.end == null && counts.moves < MAX) {
            int seat = game.active;
            int bagBefore = game.bag.size();
            List<Character> rackBefore = List.copyOf(game.racks.get(seat));
            char[] boardBefore = game.board.clone();
            boolean[] blanksBefore = game.blanks.clone();
            int turn = counts.moves;
            if (turn % 4 == 0) {
                char absent = rackBefore.get(0) != 'Z' ? 'Z' : 'A';
                String snapshot = encode(game, dictionary.lexicon).toString();
                var wrongTile = new FrenchGame.Tile(MID, 0, absent, false);
                check(!game.play(List.of(wrongTile), dictionary.lexicon).valid(), game, "invalid tile accepted");
                check(snapshot.equals(encode(game, dictionary.lexicon).toString()), game, "invalid tile changed state");
                for (List<Integer> bad : List.of(List.of(-1), List.of(0, 0), List.<Integer>of())) {
                    try {
                        game.exchange(bad);
                        throw new AssertionError("invalid exchange accepted");
                    } catch (IllegalArgumentException correct) {
                        check(snapshot.equals(encode(game, dictionary.lexicon).toString()), game, "invalid exchange changed state");
                        counts.rejections++;
                    }
                }
                counts.rejections++;
            }
            int action = onlyPass && turn >= 7 ? 0 : random.nextInt(10);
            Bot.Choice choice = action >= 3 || (action > 0 && bagBefore < 7)
                    ? Bot.choose(game, dictionary.lexicon, game.levels[seat], 1100) : null;
            int score = 0;
            if (action == 0 || choice != null && choice.tiles().isEmpty() && choice.exchange().isEmpty()) {
                game.pass();
                counts.passes++;
            } else if (choice == null || choice.tiles().isEmpty()) {
                List<Integer> selected = choice != null ? choice.exchange() : random.ints(0, rackBefore.size())
                        .distinct().limit(1 + random.nextInt(rackBefore.size())).boxed().toList();
                game.exchange(selected);
                counts.exchanges++;
                check(game.bag.size() == bagBefore, game, "exchange changed bag size");
                check(game.history.get(turn).exchanged() == selected.size(), game, "wrong recorded exchange count");
            } else {
                var independent = score(boardBefore, blanksBefore, choice.tiles(), dictionary);
                var preview = game.play(choice.tiles(), dictionary.lexicon);
                check(preview.valid() && preview.score() == independent.score && preview.score() == choice.score(), game,
                        "scoring mismatch for " + preview.word() + " independent=" + independent + " model=" + preview + " bot=" + choice.score());
                check(new HashSet<>(preview.words()).equals(new HashSet<>(independent.words)), game, "bad crosswords");
                check(independent.words.contains(preview.word()), game, "main word not scored");
                counts.plays++;
                counts.jokers += choice.tiles().stream().filter(FrenchGame.Tile::blank).count();
                if (choice.tiles().size() == 7) counts.bingos++;
                if (independent.words.size() > 1) counts.crosses++;
                score = independent.score;
            }
            check(game.history.size() == turn + 1, game, "unrecorded turn");
            var move = game.history.get(turn);
            check(move.player() == seat && move.score() == score && move.bagBefore() == bagBefore
                    && rackText(rackBefore).equals(move.rack()), game, "turn data lost");
            if (!move.type().equals("play")) check(Arrays.equals(boardBefore, game.board) && Arrays.equals(blanksBefore, game.blanks), game, "no-play changed board");
            totals[seat] += score;
            zero = (duel ? score == 0 : move.type().equals("pass")) ? zero + 1 : 0;
            checkInventory(game, dictionary.counts);
            if (game.end == null) {
                check(game.active == (seat + 1) % seats, game, "wrong next player");
                check(Arrays.equals(totals, game.scores), game, "wrong in-game totals");
                check(game.passes == zero && zero < 3 * seats, game, "wrong consecutive passes");
            } else {
                boolean rackOut = game.bag.isEmpty() && game.racks.get(seat).isEmpty() && move.type().equals("play");
                check(Objects.equals(game.end, rackOut ? "rack_out" : "consecutive_passes"), game, "wrong end reason");
                check(rackOut || zero == 3 * seats, game, "premature scoreless end");
                int remainder = 0;
                for (int i = 0; i < seats; i++) {
                    int penalty = 0;
                    for (char tile : game.racks.get(i)) penalty += dictionary.values[SYMBOLS.indexOf(tile)];
                    remainder += penalty;
                    if (!rackOut || !duel) totals[i] -= penalty;
                }
                if (rackOut) totals[seat] += duel ? 2 * remainder : remainder;
                check(Arrays.equals(totals, game.scores), game, "wrong final scores, expected " + Arrays.toString(totals));
                if (rackOut) counts.rackOut++;
                else counts.endsOnPasses++;
            }
            // Reload through the application's JSON codec after each action, independently of the replay scorer.
            JSONObject saved = encode(game, dictionary.lexicon);
            String snapshot = saved.toString();
            game = MatchCodec.restore(new JSONObject(snapshot));
            check(snapshot.equals(encode(game, dictionary.lexicon).toString()) && game.language == language && game.seed == seed,
                    game, "restoration changed saved state");
            counts.restores++;
            counts.moves++;
        }
        check(game.end != null, game, "no ending by turn " + MAX);
        int maximum = Arrays.stream(totals).max().orElseThrow();
        var winners = IntStream.range(0, seats).filter(i -> totals[i] == maximum).toArray();
        if (winners.length > 1) counts.ties++;
        var archive = MatchCodec.finished(MatchCodec.forLocal(game, game.active), "local", dictionary.lexicon.label, seed);
        check(archive.getJSONArray("winners").length() == winners.length && archive.getInt("turns") == counts.moves
                && archive.getString("language").equals(language.code), game, "archive metadata diverges");
        for (int i = 0; i < winners.length; i++) check(archive.getJSONArray("winners").getInt(i) == winners[i], game, "wrong archived winner");
        var review = MatchReview.review(MatchCodec.readMoves(archive.getJSONArray("moves")), dictionary.lexicon, -1, true);
        for (var reviewed : review.turns()) {
            var alternative = reviewed.alternative();
            if (alternative == null) continue;
            var score = score(reviewed.board(), reviewed.blanks(), alternative.tiles(), dictionary);
            check(score.score == alternative.preview().score(), game, "review shows wrong score");
        }
        counts.games++;
        return counts;
    }
    private record Scored(int score, List<String> words) {}
    private static JSONObject encode(FrenchGame game, Lexicon lexicon) {
        JSONObject saved = MatchCodec.saved(game, "local", 1100, 0, 0, false);
        MatchCodec.put(saved, "lexicon_sha256", lexicon.hash);
        return saved;
    }
    private static String rackText(List<Character> rack) {
        var text = new StringBuilder();
        for (char tile : rack) text.append(tile);
        return text.toString();
    }
    private static void checkInventory(FrenchGame game, int[] expected) {
        int[] counts = new int[27];
        for (char tile : game.bag) counts[SYMBOLS.indexOf(tile)]++;
        for (List<Character> rack : game.racks) {
            check(rack.size() <= 7, game, "overfull rack");
            for (char tile : rack) counts[SYMBOLS.indexOf(tile)]++;
        }
        for (int i = 0; i < game.board.length; i++) {
            if (game.board[i] != 0) counts[SYMBOLS.indexOf(game.blanks[i] ? '?' : game.board[i])]++;
        }
        check(Arrays.equals(counts, expected), game, "lost tile, " + Arrays.toString(counts));
    }
    private static Scored score(char[] before, boolean[] oldBlanks, List<FrenchGame.Tile> placement, Dictionary dictionary) {
        char[] updated = before.clone();
        boolean[] blank = oldBlanks.clone();
        Set<Integer> added = new HashSet<>();
        for (var tile : placement) {
            if (updated[tile.position()] != 0) throw new AssertionError("played occupied square");
            updated[tile.position()] = tile.letter();
            blank[tile.position()] = tile.blank();
            added.add(tile.position());
        }
        var words = new LinkedHashMap<Integer, String>();
        int total = placement.size() == 7 ? 50 : 0;
        for (int position : added) {
            for (boolean across : List.of(true, false)) {
                int row = position / SIDE;
                int col = position % SIDE;
                int along = across ? col : row;
                int fixed = across ? row : col;
                while (along > 0 && updated[cell(across, fixed, along - 1)] != 0) along--;
                int key = cell(across, fixed, along) * 2 + (across ? 0 : 1);
                if (words.containsKey(key)) continue;
                StringBuilder word = new StringBuilder();
                int points = 0;
                int multiplier = 1;
                for (; along < SIDE && updated[cell(across, fixed, along)] != 0; along++) {
                    int at = cell(across, fixed, along);
                    word.append(updated[at]);
                    char premium = added.contains(at) ? BOARD_TOP[Math.min(at / SIDE, 14 - at / SIDE)].charAt(at % SIDE) : '.';
                    if (!blank[at]) points += dictionary.values[SYMBOLS.indexOf(updated[at])]
                            * (premium == 'a' ? 2 : premium == 'b' ? 3 : 1);
                    multiplier *= premium == 'D' ? 2 : premium == 'T' ? 3 : 1;
                }
                if (word.length() < 2) continue;
                String text = word.toString();
                if (!dictionary.words.contains(text)) throw new AssertionError("Independent dictionary rejected " + text);
                words.put(key, text);
                total += points * multiplier;
            }
        }
        if (words.isEmpty()) throw new AssertionError("No words formed");
        return new Scored(total, List.copyOf(words.values()));
    }
    private static int cell(boolean across, int line, int along) { return across ? line * SIDE + along : along * SIDE + line; }
    private static void check(boolean valid, FrenchGame game, String message) {
        if (!valid) throw new AssertionError(game.language + " " + game.names.length + " seats, seed=" + game.seed + ", history=" + game.history.size() + " " + message);
    }
}
