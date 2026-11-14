package fr.lettrine.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

final class MatchReview {
    private static final int TURN_LIMIT = 3;
    private static final Comparator<RankedTurn> REVIEW_ORDER = Comparator.comparingInt(RankedTurn::gain).reversed()
            .thenComparing(Comparator.comparingInt(RankedTurn::publicInterest).reversed())
            .thenComparingInt(RankedTurn::index);

    record Alternative(FrenchGame.Preview preview, List<FrenchGame.Tile> tiles) {}

    record Turn(int index, FrenchGame.Move played, char[] board, boolean[] blanks, boolean rackAvailable,
                Alternative alternative, PracticePosition practice, String summary, String facts,
                List<String> options, List<String> details) {
        String text(int option) { return details.isEmpty() ? summary : summary + "\n\n" + details.get(option); }
    }

    record Highlight(Turn turn, String explanation, List<Integer> cells) {}

    record Review(List<Turn> turns, Highlight highlight) {}

    private record Lesson(int priority, String explanation, List<Integer> cells) {}

    private record RankedTurn(int index, char[] board, boolean[] blanks, List<Character> rack,
                              Alternative alternative, int gain, int publicInterest) {
        RankedTurn snapshot() {
            return new RankedTurn(index, board.clone(), blanks.clone(), rack, alternative, gain, publicInterest);
        }
    }

    static List<Turn> analyze(List<FrenchGame.Move> moves, Lexicon lexicon, int focusSeat,
                              boolean compatibleLexicon) {
        return review(moves, lexicon, focusSeat, compatibleLexicon).turns();
    }

    static Review review(List<FrenchGame.Move> moves, Lexicon lexicon, int focusSeat,
                         boolean compatibleLexicon) {
        char[] board = new char[FrenchGame.CELLS];
        boolean[] blanks = new boolean[FrenchGame.CELLS];
        List<RankedTurn> ranked = new ArrayList<>();
        GameLanguage language = lexicon == null ? GameLanguage.FRENCH : lexicon.language;
        RankedTurn featured = null;
        Lesson featuredLesson = null;
        for (int index = 0; index < moves.size(); index++) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            FrenchGame.Move move = moves.get(index);
            if (focusSeat < 0 || move.player() == focusSeat) {
                List<Character> rack = originalRack(move.rack());
                Alternative alternative = compatibleLexicon && lexicon != null && !rack.isEmpty()
                        ? alternative(board, blanks, rack, move, lexicon) : null;
                int gain = alternative == null ? Integer.MIN_VALUE : alternative.preview().score() - move.score();
                int publicInterest = move.type().equals("play") ? move.score() : -1;
                RankedTurn candidate = new RankedTurn(index, board, blanks, rack, alternative,
                        gain, publicInterest);
                if (gain > 0) {
                    Lesson lesson = lesson(candidate, language);
                    if (featuredLesson == null || lesson.priority() > featuredLesson.priority()
                            || (lesson.priority() == featuredLesson.priority() && gain > featured.gain())) {
                        featured = candidate.snapshot();
                        featuredLesson = lesson;
                    }
                }
                if (ranked.size() < TURN_LIMIT || REVIEW_ORDER.compare(candidate, ranked.get(TURN_LIMIT - 1)) < 0) {
                    ranked.add(candidate.snapshot());
                    ranked.sort(REVIEW_ORDER);
                    if (ranked.size() > TURN_LIMIT) ranked.remove(TURN_LIMIT);
                }
            }
            for (FrenchGame.Tile tile : move.tiles()) {
                board[tile.position()] = tile.letter();
                blanks[tile.position()] = tile.blank();
            }
        }
        List<Turn> turns = ranked.stream().map(row -> turn(moves, row, language)).collect(Collectors.toList());
        Highlight highlight = null;
        if (featured != null) {
            int featuredIndex = featured.index();
            RankedTurn featuredRow = featured;
            Turn featuredTurn = turns.stream().filter(turn -> turn.index() == featuredIndex).findFirst()
                    .orElseGet(() -> turn(moves, featuredRow, language));
            highlight = new Highlight(featuredTurn, featuredLesson.explanation(), featuredLesson.cells());
        }
        return new Review(turns, highlight);
    }

    private static Lesson lesson(RankedTurn row, GameLanguage language) {
        Alternative alternative = row.alternative();
        List<FrenchGame.Tile> tiles = alternative.tiles();
        List<String> words = alternative.preview().words();
        String gain = points(row.gain(), language);
        String extra = language.text(" Ce placement rapporte " + gain + " de plus.",
                " This placement scores " + gain + " more.");
        if (tiles.size() == 1 && words.size() == 2) {
            FrenchGame.Tile tile = tiles.get(0);
            String message = language.text("En posant « " + tile.letter() + " » ici, vous formez "
                    + words.get(0) + " et " + words.get(1) + ".",
                    "Placing " + tile.letter() + " here forms " + words.get(0) + " and " + words.get(1) + ".");
            return new Lesson(4, message + extra, List.of(tile.position()));
        }
        List<Integer> cells = tiles.stream().map(FrenchGame.Tile::position).collect(Collectors.toList());
        if (words.size() > 1) {
            boolean horizontal = cells.get(0) / FrenchGame.SIZE == cells.get(1) / FrenchGame.SIZE;
            int across = horizontal ? FrenchGame.SIZE : 1;
            List<Integer> crossings = cells.stream().filter(square -> {
                int before = FrenchGame.step(square, across, -1);
                int after = FrenchGame.step(square, across, 1);
                return (before >= 0 && row.board()[before] != 0) || (after >= 0 && row.board()[after] != 0);
            }).collect(Collectors.toList());
            String message = language.text("Vous formez " + words.size() + " mots à la fois et marquez "
                    + gain + " de plus.", "Forming " + words.size() + " words at once scores " + gain + " more.");
            return new Lesson(3, message, crossings);
        }
        if (tiles.size() == FrenchGame.RACK_SIZE) {
            String message = language.text("Poser les sept lettres ajoute la prime de 50 points.",
                    "Placing all seven tiles earns the 50-point bonus.");
            return new Lesson(2, message + extra, cells);
        }
        for (FrenchGame.Tile tile : tiles) {
            int square = tile.position();
            if (square == FrenchGame.CENTER) continue;
            int word = FrenchGame.WORD_MULTIPLIER[square];
            int letter = FrenchGame.LETTER_MULTIPLIER[square];
            if (word > 1) {
                String message = language.text(word == 2 ? "Cette case double le mot." : "Cette case triple le mot.",
                        word == 2 ? "This square doubles the word score." : "This square triples the word score.");
                return new Lesson(1, message + extra, List.of(square));
            }
            if (letter > 1 && !tile.blank()) {
                String message = language.text(letter == 2 ? "Cette case double la valeur de la lettre « " + tile.letter() + " »."
                                : "Cette case triple la valeur de la lettre « " + tile.letter() + " ».",
                        letter == 2 ? "This square doubles the value of " + tile.letter() + "."
                                : "This square triples the value of " + tile.letter() + ".");
                return new Lesson(1, message + extra, List.of(square));
            }
        }
        String word = alternative.preview().word();
        String message = language.text("« " + word + " » rapporte " + gain + " de plus sur ce plateau.",
                word + " scores " + gain + " more on this board.");
        return new Lesson(0, message, cells);
    }

    private static List<Character> originalRack(String recorded) {
        if (recorded == null || !recorded.matches("[A-Z?]{1,7}")) return List.of();
        List<Character> rack = new ArrayList<>(recorded.length());
        for (int i = 0; i < recorded.length(); i++) rack.add(recorded.charAt(i));
        return rack;
    }

    private static Alternative alternative(char[] board, boolean[] blanks, List<Character> rack,
                                           FrenchGame.Move played, Lexicon lexicon) {
        if (played.type().equals("play")) {
            // Archives preserve the letters and board positions, while the rack indices are assigned on read.
            List<Character> unused = new ArrayList<>(rack);
            List<FrenchGame.Tile> replay = new ArrayList<>(played.tiles().size());
            for (FrenchGame.Tile tile : played.tiles()) {
                int index = unused.indexOf(tile.blank() ? FrenchGame.JOKER : tile.letter());
                if (index < 0) return null;
                unused.set(index, '\0');
                replay.add(new FrenchGame.Tile(tile.position(), index, tile.letter(), tile.blank()));
            }
            FrenchGame.Preview preview = FrenchGame.preview(board, blanks, rack, lexicon, replay);
            if (!preview.valid() || preview.score() != played.score() || !preview.word().equals(played.word())) return null;
        } else if ((!played.type().equals("pass") && !played.type().equals("exchange")) || !played.tiles().isEmpty()) {
            return null;
        }
        for (List<FrenchGame.Tile> tiles : Bot.scoreCandidates(board, blanks, rack, lexicon)) {
            if (samePlacement(tiles, played.tiles())) continue;
            FrenchGame.Preview preview = FrenchGame.preview(board, blanks, rack, lexicon, tiles);
            if (preview.valid()) return new Alternative(preview, tiles);
        }
        return null;
    }

    private static boolean samePlacement(List<FrenchGame.Tile> first, List<FrenchGame.Tile> second) {
        return first.size() == second.size() && first.stream().allMatch(a -> second.stream().anyMatch(b ->
                a.position() == b.position() && a.letter() == b.letter() && a.blank() == b.blank()));
    }

    private static Turn turn(List<FrenchGame.Move> moves, RankedTurn row, GameLanguage language) {
        FrenchGame.Move move = moves.get(row.index());
        FrenchGame.Move played = new FrenchGame.Move(move.player(), move.type(), move.word(), move.score(),
                List.copyOf(move.tiles()), move.exchanged(), null, -1);
        List<String> options = new ArrayList<>();
        List<String> details = new ArrayList<>();
        String summary;
        String facts;
        Alternative alternative = row.alternative();
        if (alternative == null) {
            MoveExplanation explanation = MoveExplanation.create(moves, row.index(), language);
            facts = explanation == null ? "Recorded move." : explanation.facts();
            summary = explanation == null ? language.text("Détails du coup indisponibles.", "Move details are unavailable.")
                    : explanation.summary();
            if (explanation != null) {
                options.addAll(explanation.options());
                details.addAll(explanation.details());
            }
        } else {
            FrenchGame.Preview preview = alternative.preview();
            int delta = preview.score() - played.score();
            String alternativePoints = points(preview.score(), language);
            String playedPoints = points(played.score(), language);
            summary = language.text("« " + preview.word() + " » rapporte " + alternativePoints + " contre "
                    + playedPoints + " pour le coup joué.", preview.word() + " scores " + alternativePoints
                    + ". Your move scored " + playedPoints + ".");
            facts = "Recorded " + played.type() + " " + played.word() + " for " + played.score() + " points. Legal placement "
                    + preview.word() + " scores " + preview.score() + " points on the same board with the same rack.";
            options.add("Immediate score difference " + delta + " points");
            String scoreDifference = points(Math.abs(delta), language);
            details.add(delta == 0
                    ? language.text("Les deux coups ont le même score immédiat.", "Both moves score the same.")
                    : language.text("L’alternative rapporte " + scoreDifference + (delta > 0 ? " de plus." : " de moins."),
                    "The alternative scores " + scoreDifference + (delta > 0 ? " more." : " less.")));

            int playedCount = played.tiles().size();
            int alternativeCount = alternative.tiles().size();
            if (playedCount != alternativeCount) {
                options.add("Number of tiles played and retained. The alternative plays " + alternativeCount
                        + " tiles and the recorded turn plays " + playedCount + ".");
                String counts;
                if (played.type().equals("exchange")) {
                    String newTiles = tiles(alternativeCount, language);
                    counts = language.text("L’alternative place " + newTiles + " sur le plateau. Un échange renouvelle le chevalet.",
                            "The alternative places " + newTiles + " on the board. An exchange replaces tiles on your rack.");
                } else {
                    int rackSize = move.rack().length();
                    String alternativeRemaining = tiles(rackSize - alternativeCount, language);
                    String playedRemaining = tiles(rackSize - playedCount, language);
                    counts = language.text("Avant de piocher, l’alternative laisse " + alternativeRemaining
                            + " sur le chevalet, contre " + playedRemaining + " pour le coup joué.",
                            "Before drawing, the alternative leaves " + alternativeRemaining + " on your rack. Your move leaves "
                                    + playedRemaining + ".");
                }
                details.add(counts);
            }
            List<FrenchGame.FormedWord> words = FrenchGame.recordedWords(row.board(), row.blanks(), alternative.tiles(), language);
            if (words.size() > 1) {
                String breakdown = words.stream().map(word -> word.word() + " (" + points(word.score(), language) + ")")
                        .collect(Collectors.joining(", "));
                options.add("Alternative main word and crosswords " + breakdown);
                String bonus = alternativeCount == FrenchGame.RACK_SIZE
                        ? language.text(" et reçoit 50 points de prime de scrabble", " and earns the 50-point bonus") : "";
                details.add(language.text("L’alternative forme ", "The alternative forms ") + breakdown + bonus + ".");
            } else if (alternativeCount == FrenchGame.RACK_SIZE) {
                options.add("Seven-tile bonus of 50 points for the alternative");
                details.add(language.text("L’alternative pose sept lettres et reçoit la prime de 50 points.",
                        "The alternative places all seven tiles and earns the 50-point bonus."));
            }
            String cells = alternative.tiles().stream().map(tile -> cell(tile.position())).collect(Collectors.joining(", "));
            options.add("Alternative board squares " + cells);
            details.add(language.text("Cases où poser les nouvelles lettres. ", "Place the new tiles at ") + cells + ".");
        }
        PracticePosition practice = row.gain() > 0
                ? new PracticePosition(row.board(), row.blanks(), row.rack(), alternative, played.score()) : null;
        return new Turn(row.index(), played, row.board(), row.blanks(), !row.rack().isEmpty(),
                alternative, practice, summary, facts, List.copyOf(options), List.copyOf(details));
    }

    private static String points(int score, GameLanguage language) {
        return score + (score == 1 || score == 0 && language == GameLanguage.FRENCH ? " point" : " points");
    }

    private static String tiles(int count, GameLanguage language) {
        return count + (count == 1 ? language.text(" lettre", " tile") : language.text(" lettres", " tiles"));
    }

    private static String cell(int position) {
        return Character.toString((char) ('A' + position / FrenchGame.SIZE)) + (position % FrenchGame.SIZE + 1);
    }
}
