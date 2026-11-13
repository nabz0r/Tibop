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
        return create(history, moveIndex, GameLanguage.FRENCH);
    }

    static MoveExplanation create(List<FrenchGame.Move> history, int moveIndex, GameLanguage language) {
        if (moveIndex < 0 || moveIndex >= history.size()) return null;
        FrenchGame.Move move = history.get(moveIndex);
        return switch (move.type()) {
            case "play" -> placement(history, moveIndex, move, language);
            case "pass" -> pass(history, moveIndex, language);
            case "exchange" -> exchange(move.exchanged(), language);
            default -> null;
        };
    }

    String facts() { return facts; }

    List<String> options() { return focuses.stream().map(Focus::label).collect(Collectors.toList()); }

    String text(int index) {
        if (focuses.isEmpty()) return summary;
        return summary + "\n\n" + focuses.get(index).text();
    }

    private static MoveExplanation placement(List<FrenchGame.Move> history, int index, FrenchGame.Move move,
                                             GameLanguage language) {
        String word = move.word();
        if (word == null || !word.matches("[A-Z]{2,15}")) return null;
        String score = points(move.score());
        String summary = language.text("« " + word + " » rapporte " + score, word + " scores " + score) + ".";
        char[] board = new char[FrenchGame.CELLS];
        boolean[] blanks = new boolean[FrenchGame.CELLS];
        for (int turn = 0; turn < index; turn++) {
            for (FrenchGame.Tile tile : history.get(turn).tiles()) {
                board[tile.position()] = tile.letter();
                blanks[tile.position()] = tile.blank();
            }
        }
        List<FrenchGame.FormedWord> words = FrenchGame.recordedWords(board, blanks, move.tiles(), language);
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
            String wordPoints = points(move.score() - 50);
            focuses.add(new Focus("50-point bonus for placing all seven tiles", language.text(
                    "Les sept lettres ont été posées en un seul tour. Ce scrabble ajoute 50 points aux "
                            + wordPoints + " des mots formés.",
                    "Playing all seven tiles in one turn adds 50 points to the " + wordPoints + " from the words formed.")));
        }
        if (words.size() > 1) {
            String scores = String.join(", ", wordScores);
            String bonus = bingo ? language.text(", puis 50 points de prime de scrabble", ", plus the 50-point bonus") : "";
            focuses.add(new Focus("Crosswords and individual word scores " + scores, language.text(
                    "Le coup compte le mot principal et les mots croisés. ",
                    "The move scores for the main word and the crosswords. ") + scores + bonus + "."));
        }
        List<String> premiums = new ArrayList<>();
        List<String> premiumFacts = new ArrayList<>();
        List<String> jokers = new ArrayList<>();
        for (FrenchGame.Tile tile : move.tiles()) {
            String cell = cellName(tile.position());
            if (tile.blank()) jokers.add(tile.letter() + language.text(" en ", " at ") + cell);
            int letterBonus = FrenchGame.LETTER_MULTIPLIER[tile.position()];
            int wordBonus = FrenchGame.WORD_MULTIPLIER[tile.position()];
            if (wordBonus > 1) {
                premiums.add(cell + " (" + language.text("mot " + multiplier(wordBonus), multiplier(wordBonus) + " word") + ")");
                premiumFacts.add(cell + " (word x" + wordBonus + ")");
            } else if (letterBonus > 1 && !tile.blank()) {
                premiums.add(cell + " (" + language.text("lettre " + multiplier(letterBonus), multiplier(letterBonus) + " letter") + ")");
                premiumFacts.add(cell + " (letter x" + letterBonus + ")");
            }
        }
        if (!premiums.isEmpty()) {
            focuses.add(new Focus("Premium squares " + String.join(", ", premiumFacts), language.text(
                    "Cases bonus utilisées par les nouvelles lettres du coup. ", "The new tiles use these premium squares. ")
                    + String.join(", ", premiums) + "."));
        }
        if (!jokers.isEmpty()) {
            String rule = jokers.size() == 1 ? language.text("Joker posé. ", "Blank played. ")
                    : language.text("Jokers posés. ", "Blanks played. ");
            focuses.add(new Focus(jokers.size() + " blank tiles, worth zero points",
                    rule + String.join(", ", jokers) + language.text(". Un joker vaut zéro point.", ". A blank scores zero points.")));
        }
        if (board[FrenchGame.CENTER] == 0 && move.tiles().stream().anyMatch(tile -> tile.position() == FrenchGame.CENTER)) {
            focuses.add(new Focus("Opening move through the center star", language.text(
                    "Le premier mot couvre l’étoile centrale, une case mot double.",
                    "The first word covers the center star, a double-word square.")));
        } else if (words.get(0).touchesExisting()) {
            focuses.add(new Focus("The main word reuses letters already on the board", language.text(
                    "Le mot principal reprend des lettres déjà sur le plateau. Les cases bonus sous ces lettres ont déjà été utilisées.",
                    "The main word reuses tiles on the board. Their premium squares have already been used.")));
        }
        int count = move.tiles().size();
        String origin = words.size() == 1 ? language.text(" Le score du mot principal est de ", " The main word scores ")
                + points(words.get(0).score()) + "." : language.text(" Les mots formés rapportent ", " The words formed score ")
                + points(total - (bingo ? 50 : 0)) + ".";
        String placement = count == 1 ? language.text("Une nouvelle lettre a été posée.", "One new tile was placed.")
                : count + language.text(" nouvelles lettres ont été posées.", " new tiles were placed.");
        focuses.add(new Focus(count + " newly placed tiles and " + words.size() + " formed words",
                placement + origin + (bingo ? language.text(" La prime de scrabble ajoute 50 points.", " The seven-tile bonus adds 50 points.") : "")));
        return new MoveExplanation(summary, facts, focuses);
    }

    private static MoveExplanation pass(List<FrenchGame.Move> history, int index, GameLanguage language) {
        if (language == GameLanguage.ENGLISH) {
            return new MoveExplanation("A pass scores zero points.", "Passed for zero points.", List.of(
                    new Focus("Scoreless turns and the end of the game",
                            "An English two-player game ends after six consecutive scoreless turns. At larger tables, only consecutive passes count toward ending the game."),
                    new Focus("Passing leaves the rack and board unchanged", "Passing leaves the rack and board as they are.")));
        }
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

    private static MoveExplanation exchange(int count, GameLanguage language) {
        String summary = count <= 0 ? language.text("Un échange rapporte 0 point.", "An exchange scores zero points.")
                : (count == 1 ? language.text("Une lettre a été échangée.", "One tile was exchanged.")
                : count + language.text(" lettres ont été échangées.", " tiles were exchanged."))
                + language.text(" Le coup rapporte 0 point.", " The turn scores zero points.");
        String facts = count > 0 ? "Exchanged " + count + " tiles." : "Exchanged tiles. Count unavailable.";
        return new MoveExplanation(summary, facts, List.of(
                new Focus("The bag must contain at least seven tiles to exchange", language.text(
                        "L’échange est possible quand il reste au moins sept lettres dans le sac.",
                        "You can exchange tiles when at least seven remain in the bag.")),
                new Focus("An exchange changes the rack and leaves the board unchanged", language.text(
                        "L’échange renouvelle le chevalet et termine le tour. Le plateau reste tel quel.",
                        "Exchanging changes the rack and ends the turn. The board stays as it is."))));
    }

    private static String cellName(int position) {
        return Character.toString((char) ('A' + position / FrenchGame.SIZE)) + (position % FrenchGame.SIZE + 1);
    }

    private static String multiplier(int value) { return value == 2 ? "double" : "triple"; }

    private static String points(int score) { return score + (score == 1 ? " point" : " points"); }
}
