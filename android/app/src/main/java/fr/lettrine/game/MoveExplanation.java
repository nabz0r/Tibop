package fr.lettrine.game;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

final class MoveExplanation {
    private record Focus(String label, String text) {}

    private final String summary;
    private final String facts;
    private final List<Focus> focuses;

    private MoveExplanation(String summary, String facts, List<Focus> focuses) {
        this.summary = summary;
        this.facts = facts;
        this.focuses = List.copyOf(focuses);
    }

    static MoveExplanation create(List<FrenchGame.Move> history, int moveIndex) {
        if (moveIndex < 0 || moveIndex >= history.size()) return null;
        FrenchGame.Move move = history.get(moveIndex);
        return switch (move.type()) {
            case "play" -> placement(history, moveIndex, move);
            case "pass" -> pass(history, moveIndex);
            case "exchange" -> exchange(move.exchanged());
            default -> null;
        };
    }

    String facts() { return facts; }

    List<String> options() { return focuses.stream().map(Focus::label).collect(Collectors.toList()); }

    String text(int index) {
        if (focuses.isEmpty()) return summary;
        return summary + "\n\n" + focuses.get(index).text();
    }

    private static MoveExplanation placement(List<FrenchGame.Move> history, int index, FrenchGame.Move move) {
        String word = move.word();
        if (word == null || !word.matches("[A-Z]{2,15}")) return null;
        String summary = "« " + word + " » rapporte " + points(move.score()) + ".";
        char[] board = new char[FrenchGame.CELLS];
        boolean[] blanks = new boolean[FrenchGame.CELLS];
        for (int turn = 0; turn < index; turn++) {
            for (FrenchGame.Tile tile : history.get(turn).tiles()) {
                board[tile.position()] = tile.letter();
                blanks[tile.position()] = tile.blank();
            }
        }
        List<FrenchGame.FormedWord> words = FrenchGame.recordedWords(board, blanks, move.tiles());
        boolean bingo = move.tiles().size() == FrenchGame.RACK_SIZE;
        int total = words.stream().mapToInt(FrenchGame.FormedWord::score).sum() + (bingo ? 50 : 0);
        String facts = "Played word " + word + " for " + move.score() + " points.";
        if (words.isEmpty() || !words.get(0).word().equals(word) || total != move.score()) {
            return new MoveExplanation(summary, facts, List.of());
        }

        List<Focus> focuses = new ArrayList<>();
        List<String> wordScores = words.stream()
                .map(formed -> formed.word() + " (" + points(formed.score()) + ")").collect(Collectors.toList());
        if (bingo) {
            focuses.add(new Focus("50-point bonus for placing all seven tiles",
                    "Les sept lettres ont été posées en un seul tour. Ce scrabble ajoute 50 points aux "
                            + points(move.score() - 50) + " des mots formés."));
        }
        if (words.size() > 1) {
            focuses.add(new Focus("Crosswords and individual word scores " + String.join(", ", wordScores),
                    "Le coup compte le mot principal et les mots croisés. " + String.join(", ", wordScores)
                            + (bingo ? ", puis 50 points de prime de scrabble." : ".")));
        }
        List<String> premiums = new ArrayList<>();
        List<String> premiumFacts = new ArrayList<>();
        List<String> jokers = new ArrayList<>();
        for (FrenchGame.Tile tile : move.tiles()) {
            String cell = cellName(tile.position());
            if (tile.blank()) jokers.add(tile.letter() + " en " + cell);
            int letterBonus = FrenchGame.LETTER_MULTIPLIER[tile.position()];
            int wordBonus = FrenchGame.WORD_MULTIPLIER[tile.position()];
            if (wordBonus > 1) {
                premiums.add(cell + " (mot " + multiplier(wordBonus) + ")");
                premiumFacts.add(cell + " (word x" + wordBonus + ")");
            } else if (letterBonus > 1 && !tile.blank()) {
                premiums.add(cell + " (lettre " + multiplier(letterBonus) + ")");
                premiumFacts.add(cell + " (letter x" + letterBonus + ")");
            }
        }
        if (!premiums.isEmpty()) {
            focuses.add(new Focus("Premium squares " + String.join(", ", premiumFacts),
                    "Cases bonus utilisées par les nouvelles lettres du coup. " + String.join(", ", premiums) + "."));
        }
        if (!jokers.isEmpty()) {
            String rule = jokers.size() == 1 ? "Joker posé. " : "Jokers posés. ";
            focuses.add(new Focus(jokers.size() + " blank tiles, worth zero points",
                    rule + String.join(", ", jokers) + ". Un joker vaut zéro point."));
        }
        if (board[FrenchGame.CENTER] == 0 && move.tiles().stream().anyMatch(tile -> tile.position() == FrenchGame.CENTER)) {
            focuses.add(new Focus("Opening move through the center star",
                    "Le premier mot couvre l’étoile centrale, une case mot double."));
        } else if (words.get(0).touchesExisting()) {
            focuses.add(new Focus("The main word reuses letters already on the board",
                    "Le mot principal reprend des lettres déjà sur le plateau. Les cases bonus sous ces lettres ont déjà été utilisées."));
        }
        int count = move.tiles().size();
        String origin = words.size() == 1 ? " Le score du mot principal est de " + points(words.get(0).score()) + "."
                : " Les mots formés rapportent " + points(total - (bingo ? 50 : 0)) + ".";
        focuses.add(new Focus(count + " newly placed tiles and " + words.size() + " formed words",
                (count == 1 ? "Une nouvelle lettre a été posée." : count + " nouvelles lettres ont été posées.")
                        + origin + (bingo ? " La prime de scrabble ajoute 50 points." : "")));
        return new MoveExplanation(summary, facts, focuses);
    }

    private static MoveExplanation pass(List<FrenchGame.Move> history, int index) {
        int streak = 1;
        for (int turn = index - 1; turn >= 0 && history.get(turn).type().equals("pass"); turn--) streak++;
        String detail = streak == 1 ? "C’est la première passe consécutive."
                : "C’est la " + streak + "ᵉ passe consécutive.";
        return new MoveExplanation("Une passe rapporte 0 point.", "Passed. " + streak + " consecutive passes.",
                List.of(new Focus("Consecutive passes and the end of the game",
                                detail + " Pour terminer la partie, le total des passes consécutives doit atteindre trois fois le nombre de joueurs."),
                        new Focus("Passing leaves the rack and board unchanged",
                                "Passer laisse le plateau et le chevalet tels quels.")));
    }

    private static MoveExplanation exchange(int count) {
        String summary = count <= 0 ? "Un échange rapporte 0 point."
                : (count == 1 ? "Une lettre a été échangée." : count + " lettres ont été échangées.") + " Le coup rapporte 0 point.";
        String facts = count > 0 ? "Exchanged " + count + " tiles." : "Exchanged tiles. Count unavailable.";
        return new MoveExplanation(summary, facts, List.of(
                new Focus("The bag must contain at least seven tiles to exchange",
                        "L’échange est possible quand il reste au moins sept lettres dans le sac."),
                new Focus("An exchange changes the rack and leaves the board unchanged",
                        "L’échange renouvelle le chevalet et termine le tour. Le plateau reste tel quel.")));
    }

    private static String cellName(int position) {
        return Character.toString((char) ('A' + position / FrenchGame.SIZE)) + (position % FrenchGame.SIZE + 1);
    }

    private static String multiplier(int value) { return value == 2 ? "double" : "triple"; }

    private static String points(int score) { return score + (score == 1 ? " point" : " points"); }
}
