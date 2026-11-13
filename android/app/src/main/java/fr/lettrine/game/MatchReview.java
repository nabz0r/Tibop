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
                Alternative alternative, PracticePosition practice, String facts,
                List<String> options, List<String> explanations) {
        String text(int option) { return explanations.get(option); }
    }

    private record RankedTurn(int index, char[] board, boolean[] blanks, List<Character> rack,
                              Alternative alternative, int gain, int publicInterest) {}

    static List<Turn> analyze(List<FrenchGame.Move> moves, Lexicon lexicon, int focusSeat,
                              boolean compatibleLexicon) {
        char[] board = new char[FrenchGame.CELLS];
        boolean[] blanks = new boolean[FrenchGame.CELLS];
        List<RankedTurn> ranked = new ArrayList<>();
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
                if (ranked.size() < TURN_LIMIT || REVIEW_ORDER.compare(candidate, ranked.get(TURN_LIMIT - 1)) < 0) {
                    ranked.add(new RankedTurn(index, board.clone(), blanks.clone(), rack, alternative,
                            gain, publicInterest));
                    ranked.sort(REVIEW_ORDER);
                    if (ranked.size() > TURN_LIMIT) ranked.remove(TURN_LIMIT);
                }
            }
            for (FrenchGame.Tile tile : move.tiles()) {
                board[tile.position()] = tile.letter();
                blanks[tile.position()] = tile.blank();
            }
        }
        GameLanguage language = lexicon == null ? GameLanguage.FRENCH : lexicon.language;
        return ranked.stream().map(row -> turn(moves, row, language)).collect(Collectors.toList());
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
        List<String> explanations = new ArrayList<>();
        String facts;
        Alternative alternative = row.alternative();
        if (alternative == null) {
            MoveExplanation explanation = MoveExplanation.create(moves, row.index(), language);
            facts = explanation == null ? "Recorded move." : explanation.facts();
            if (explanation != null) {
                options.addAll(explanation.options());
                for (int option = 0; option < options.size(); option++) explanations.add(explanation.text(option));
            }
            if (explanations.isEmpty()) {
                explanations.add(explanation == null
                        ? language.text("Détails du coup indisponibles.", "Move details are unavailable.") : explanation.text(0));
            }
        } else {
            FrenchGame.Preview preview = alternative.preview();
            int delta = preview.score() - played.score();
            String alternativePoints = points(preview.score(), language);
            String playedPoints = points(played.score(), language);
            String comparison = language.text("« " + preview.word() + " » rapporte " + alternativePoints + " contre "
                    + playedPoints + " pour le coup joué.", preview.word() + " scores " + alternativePoints
                    + ". Your move scored " + playedPoints + ".");
            facts = "Recorded " + played.type() + " " + played.word() + " for " + played.score() + " points. Legal placement "
                    + preview.word() + " scores " + preview.score() + " points on the same board with the same rack.";
            options.add("Immediate score difference " + delta + " points");
            String scoreDifference = points(Math.abs(delta), language);
            explanations.add(comparison + "\n\n" + (delta == 0
                    ? language.text("Les deux coups ont le même score immédiat.", "Both moves score the same.")
                    : language.text("L’alternative rapporte " + scoreDifference + (delta > 0 ? " de plus." : " de moins."),
                    "The alternative scores " + scoreDifference + (delta > 0 ? " more." : " less."))));

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
                explanations.add(comparison + "\n\n" + counts);
            }
            List<FrenchGame.FormedWord> words = FrenchGame.recordedWords(row.board(), row.blanks(), alternative.tiles(), language);
            if (words.size() > 1) {
                String breakdown = words.stream().map(word -> word.word() + " (" + points(word.score(), language) + ")")
                        .collect(Collectors.joining(", "));
                options.add("Alternative main word and crosswords " + breakdown);
                String bonus = alternativeCount == FrenchGame.RACK_SIZE
                        ? language.text(" et reçoit 50 points de prime de scrabble", " and earns the 50-point bonus") : "";
                explanations.add(comparison + "\n\n" + language.text("L’alternative forme ", "The alternative forms ") + breakdown + bonus + ".");
            } else if (alternativeCount == FrenchGame.RACK_SIZE) {
                options.add("Seven-tile bonus of 50 points for the alternative");
                explanations.add(comparison + "\n\n" + language.text("L’alternative pose sept lettres et reçoit la prime de 50 points.",
                        "The alternative places all seven tiles and earns the 50-point bonus."));
            }
            String cells = alternative.tiles().stream().map(tile -> cell(tile.position())).collect(Collectors.joining(", "));
            options.add("Alternative board squares " + cells);
            explanations.add(comparison + "\n\n" + language.text("Cases où poser les nouvelles lettres. ", "Place the new tiles at ") + cells + ".");
        }
        PracticePosition practice = row.gain() > 0
                ? new PracticePosition(row.board(), row.blanks(), row.rack(), alternative, played.score()) : null;
        return new Turn(row.index(), played, row.board(), row.blanks(), !row.rack().isEmpty(),
                alternative, practice, facts, List.copyOf(options), List.copyOf(explanations));
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
