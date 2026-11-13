package fr.lettrine.game;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

final class GameplaySimulation {
    private static final int TURN_LIMIT = 250;
    private static final int START_ELO = 1100;

    private record Pair(String name, int player, int opponent) {}
    private record Result(int score, int opponentScore, int turns, int passes, int exchanges, int invalid,
                          int bingoes, int opening, int earlyMargin, boolean rackOut, int[] botLevels) {}

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path input = Path.of(args[0]);
        List<String> metadata = Files.readAllLines(input.resolve("inputs.txt"));
        int deals = Integer.parseInt(args[1]);
        long seed = Long.parseLong(args[2]);
        int workers = Integer.parseInt(args[3]);
        if (deals < 1 || workers < 1) throw new IllegalArgumentException("Il faut au moins un tirage et un thread.");
        Lexicon lexicon;
        try (var reader = Files.newBufferedReader(input.resolve("words-fr.txt"))) {
            lexicon = Lexicon.read(reader, "Grammalecte 7.7", "MPL-2.0", metadata.get(0),
                    Integer.parseInt(metadata.get(1)));
        }
        if (metadata.size() == 8) {
            int[] weight = metadata.subList(2, 8).stream().mapToInt(Integer::parseInt).toArray();
            lexicon.expert = new Bot.Policy(weight[0], weight[1], weight[2], weight[3], weight[4], weight[5]);
            lexicon.trained = true;
        }
        List<Pair> pairs = List.of(new Pair("Promeneur / Habitué", 1, 2), new Pair("Habitué / Stratège", 2, 3),
                new Pair("Stratège / Maître", 3, 4), new Pair("Maître / Maître", 4, 4),
                new Pair("Promeneur / Adaptatif", 1, Bot.ADAPTIVE), new Pair("Stratège / Adaptatif", 3, Bot.ADAPTIVE));
        long[] seeds = new Random(seed).longs(deals).toArray();
        System.out.printf("seed=%d; deals=%d; games/pair=%d; lexicon=%s; words=%d; trained=%s%n",
                seed, deals, deals * 2, lexicon.hash, lexicon.size, lexicon.trained);
        var pool = Executors.newFixedThreadPool(workers);
        try {
            for (Pair pair : pairs) {
                List<Callable<Result>> tasks = new ArrayList<>();
                for (long deal : seeds) {
                    tasks.add(() -> play(lexicon, pair, deal, false));
                    tasks.add(() -> play(lexicon, pair, deal, true));
                }
                List<Result> results = new ArrayList<>();
                for (var future : pool.invokeAll(tasks)) results.add(future.get());
                report(pair, results);
            }
        } finally {
            pool.shutdown();
        }
        tutorial(lexicon);
        challenges(lexicon, LocalDate.of(2026, 11, 13), 30);
    }

    private static Result play(Lexicon lexicon, Pair pair, long seed, boolean swap) {
        FrenchGame game = seededGame(pair, seed, swap);
        int invalid = 0, opening = 0, early = 0;
        int[] botLevels = new int[4];
        while (game.end == null && game.history.size() < TURN_LIMIT) {
            int seat = game.active;
            Bot.Choice choice = Bot.choose(game, lexicon, game.levels[seat], START_ELO);
            if (seat == 1) botLevels[choice.level() - 1]++;
            if (!choice.tiles().isEmpty()) {
                FrenchGame.Preview preview = game.play(choice.tiles(), lexicon);
                if (!preview.valid()) {
                    invalid++;
                    game.pass();
                } else {
                    if (opening == 0) opening = preview.score();
                    if (preview.score() != choice.score()) throw new IllegalStateException("Bot score differs from rules.");
                }
            } else if (!choice.exchange().isEmpty()) game.exchange(choice.exchange());
            else game.pass();
            if (game.history.size() == 8) early = game.scores[0] - game.scores[1];
        }
        if (game.end == null) throw new IllegalStateException("Partie trop longue, graine " + seed);
        int passes = 0, exchanges = 0, bingoes = 0;
        for (FrenchGame.Move move : game.history) {
            if ("pass".equals(move.type())) passes++;
            if ("exchange".equals(move.type())) exchanges++;
            if (move.tiles().size() == FrenchGame.RACK_SIZE) bingoes++;
        }
        return new Result(game.scores[0], game.scores[1], game.history.size(), passes, exchanges, invalid,
                bingoes, opening, early, "rack_out".equals(game.end), botLevels);
    }

    private static FrenchGame seededGame(Pair pair, long seed, boolean swap) {
        // Same draw, joker count and seat assignment as the production constructor, with controlled seed.
        FrenchGame game = new FrenchGame("simulation", seed, new String[]{"Joueur", "NAB"},
                new int[]{pair.player(), pair.opponent()});
        for (char letter = 'A'; letter <= 'Z'; letter++) {
            for (int count = 0; count < FrenchGame.tileCount(letter); count++) game.bag.add(letter);
        }
        game.bag.add(FrenchGame.JOKER);
        game.bag.add(FrenchGame.JOKER);
        Collections.shuffle(game.bag, new Random(seed));
        game.active = new Random(seed ^ 0x4C65747472696E65L).nextInt(2);
        for (var rack : game.racks) {
            while (rack.size() < FrenchGame.RACK_SIZE) rack.add(game.bag.remove(game.bag.size() - 1));
        }
        if (swap) {
            Collections.swap(game.racks, 0, 1);
            game.active = 1 - game.active;
        }
        return game;
    }

    private static void report(Pair pair, List<Result> results) {
        int count = results.size();
        long wins = results.stream().filter(row -> row.score() > row.opponentScore()).count();
        long draws = results.stream().filter(row -> row.score() == row.opponentScore()).count();
        long rackOut = results.stream().filter(Result::rackOut).count();
        int[] turns = results.stream().mapToInt(Result::turns).sorted().toArray();
        int[] levels = IntStream.range(0, 4)
                .map(index -> results.stream().mapToInt(row -> row.botLevels()[index]).sum()).toArray();
        System.out.printf("%s: %dW/%dD/%dL; mean %.1f–%.1f; turns P10/P50/P90 %d/%d/%d; "
                        + "passes %d; exchanges %d; bingos %d; rack-out %d; invalid %d; opening %.1f; "
                        + "8-turn margin %.1f; opponent levels %s%n",
                pair.name(), wins, draws, count - wins - draws, mean(results, Result::score),
                mean(results, Result::opponentScore), percentile(turns, 0.1), percentile(turns, 0.5),
                percentile(turns, 0.9), sum(results, Result::passes), sum(results, Result::exchanges),
                sum(results, Result::bingoes), rackOut, sum(results, Result::invalid), mean(results, Result::opening),
                mean(results, Result::earlyMargin), Arrays.toString(levels));
    }

    private static int sum(List<Result> results, java.util.function.ToIntFunction<Result> value) {
        return results.stream().mapToInt(value).sum();
    }

    private static double mean(List<Result> results, java.util.function.ToIntFunction<Result> value) {
        return sum(results, value) / (double) results.size();
    }

    private static int percentile(int[] ordered, double p) {
        return ordered[(int) Math.ceil(p * ordered.length) - 1];
    }

    private static void tutorial(Lexicon lexicon) {
        PracticePosition tutorial = PracticePosition.first(lexicon);
        if (tutorial == null) throw new IllegalStateException("Le premier exercice est indisponible.");
        FrenchGame.Tile extension = new FrenchGame.Tile(FrenchGame.CENTER + 4, 0, 'S', false);
        FrenchGame.Preview result = tutorial.preview(List.of(extension), lexicon);
        if (!result.valid() || !"CHATS".equals(result.word())) {
            throw new IllegalStateException("Le premier exercice n'accepte pas CHATS.");
        }
        var reference = tutorial.reference().preview();
        System.out.printf("First tutorial: %s +%d pts; NAB %s +%d pts%n",
                result.word(), result.score(), reference.word(), reference.score());
    }

    private static void challenges(Lexicon lexicon, LocalDate date, int count) {
        int[] choices = new int[count], scores = new int[count], bonus = new int[count];
        for (int i = 0; i < count; i++) {
            LocalDate day = date.plusDays(i);
            DailyChallenge challenge = DailyChallenge.forDate(day, lexicon);
            if (challenge == null) throw new IllegalStateException("Défi indisponible : " + day);
            PracticePosition position = challenge.position();
            char[] board = position.board();
            boolean[] blanks = position.blanks();
            List<Character> rack = position.rack();
            int best = 0;
            for (int cell = 0; cell < board.length; cell++) {
                if (board[cell] != 0 || !FrenchGame.touches(board, cell)) continue;
                for (int index = 0; index < rack.size(); index++) {
                    char letter = rack.get(index);
                    if (rack.indexOf(letter) != index) continue;
                    var move = new FrenchGame.Tile(cell, index, letter, false);
                    var result = FrenchGame.preview(board, blanks, rack, lexicon, List.of(move));
                    if (result.valid()) {
                        choices[i]++;
                        best = Math.max(best, result.score());
                    }
                }
            }
            if (choices[i] == 0) throw new IllegalStateException("Défi sans pose d'une lettre : " + day);
            scores[i] = best;
            bonus[i] = position.reference().preview().score() - best;
        }
        long exceeded = Arrays.stream(bonus).filter(difference -> difference < 0).count();
        for (int[] values : List.of(choices, scores, bonus)) Arrays.sort(values);
        System.out.printf("Daily %s, %d dates: one-tile placements %d/%d/%d min/med/max; best one-tile points "
                        + "%d/%d/%d; NAB suggested score minus best one-tile %d/%d/%d; surpassed %d/%d%n", date, count,
                choices[0], percentile(choices, 0.5), choices[count - 1],
                scores[0], percentile(scores, 0.5), scores[count - 1],
                bonus[0], percentile(bonus, 0.5), bonus[count - 1], exceeded, count);
    }
}
