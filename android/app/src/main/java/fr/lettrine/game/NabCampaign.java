package fr.lettrine.game;

import static fr.lettrine.game.TwoMoveChallenge.across;
import static fr.lettrine.game.TwoMoveChallenge.down;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class NabCampaign {
    static final int CHAPTER_SIZE = 4;
    static final int STAGE_COUNT = 12;

    enum Goal {
        ONE_TILE, PREFIX, LETTER_BONUS, WORD_BONUS, CROSS, JOKER, SCORE
    }

    record Stage(int number, TwoMoveChallenge.Puzzle puzzle, Goal goal,
                 int passScore, int silverScore, int goldScore, List<TwoMoveChallenge.Step> rivalMoves) {
        Stage {
            rivalMoves = List.copyOf(rivalMoves);
        }

        int chapter() { return (number - 1) / CHAPTER_SIZE + 1; }
        boolean rival() { return !rivalMoves.isEmpty(); }
        int rivalScore() {
            return rival() ? rivalMoves.get(rivalMoves.size() - 1).after().totalScore() : 0;
        }
        String title() { return puzzle.title(); }
        String objective() { return puzzle.objective(); }
        String hint() { return puzzle.hint(); }
        int turns() { return puzzle.solution().size(); }
        TwoMoveChallenge.Position initial() { return puzzle.initial(); }
        List<TwoMoveChallenge.Step> solution() { return puzzle.solution(); }
    }

    private record Design(String title, String objective, String hint, String explanation, Goal goal,
                          String rack, TwoMoveChallenge.Line opening, TwoMoveChallenge.Line solution,
                          TwoMoveChallenge.Line rival) {}
    private record Route(String puzzleId, int passScore, List<TwoMoveChallenge.Line> rival) {}

    private static final List<Design> FRENCH = List.of(
            new Design("Le S de NAB", "Prolonge le mot d’une seule lettre.",
                    "Ajoute le S juste après CHAT.", "Le S transforme CHAT en CHATS et double sa valeur sur la case L2.",
                    Goal.ONE_TILE, "SONERIL", across("CHAT", "H8"), across("CHATS", "H8"), null),
            new Design("Par la gauche", "Ajoute des lettres avant le mot.",
                    "Remonte au début de TOUR. R et E peuvent ouvrir un nouveau mot.",
                    "RE donne une rallonge avant TOUR.", Goal.PREFIX, "REASTON",
                    across("TOUR", "H8"), across("RETOUR", "F8"), null),
            new Design("La lettre qui compte", "Recouvre une case Lettre Double ou Lettre Triple.",
                    "Prolonge LUNE vers le bas et vise la case L2.",
                    "Le S de LUNES tombe sur la case Lettre Double.", Goal.LETTER_BONUS, "LIESOLM",
                    down("LUNE", "H8"), down("LUNES", "H8"), null),
            new Design("Premier face-à-face", "", "Essaie d’accrocher des lettres des deux côtés de BOIS.",
                    "DÉBOISE joue la case Lettre Double et ajoute des lettres aux deux extrémités.",
                    Goal.SCORE, "DEBEXAU", across("BOIS", "H8"),
                    across("DEBOISE", "F8"), across("BOISE", "H8")),
            new Design("Deux mots à la fois", "Forme au moins deux mots dans le même coup.",
                    "Commence un mot vertical avec le S de MERS.",
                    "Le S participe à la fois à MERS et à SEL.", Goal.CROSS, "ARESLTE",
                    across("MER", "H8"), down("SEL", "K8"), null),
            new Design("Le joker en action", "Joue le joker dans un coup valide.",
                    "Essaie le joker en S, juste après NID.",
                    "Même à zéro point, le joker peut ouvrir une colonne.", Goal.JOKER, "?TASEEL",
                    across("NID", "H8"), across("NIDs", "H8"), null),
            new Design("Le mot qui triple", "Recouvre une case Mot Double ou Mot Triple.",
                    "Prolonge PART vers le bord droit du plateau.",
                    "Le S de PARTAGES atteint la case Mot Triple.", Goal.WORD_BONUS, "AGEPAUS",
                    across("PART", "H8"), across("PARTAGES", "H8"), null),
            new Design("Face à NAB", "", "Un mot juste sous CHAT peut en créer plusieurs autres.",
                    "EAU forme aussi CE, HA et AU. Tous les mots marquent.",
                    Goal.CROSS, "EAUDIOZ", across("CHAT", "H8"), across("EAU", "H9"),
                    down("AIDE", "J8")));

    private static final List<Design> ENGLISH = List.of(
            new Design("NAB’s extra S", "Extend the word by one tile.",
                    "Add the S just after SHIP.", "The S turns SHIP into SHIPS and counts double on 2L.",
                    Goal.ONE_TILE, "SMENTAR", across("SHIP", "H8"), across("SHIPS", "H8"), null),
            new Design("From the left", "Add letters before the word.",
                    "Look to the left of TURN. Try putting R and E in front.",
                    "RE extends the word TURN from the left.", Goal.PREFIX, "REAINOS",
                    across("TURN", "H8"), across("RETURN", "F8"), null),
            new Design("Make a letter count", "Cover a Double or Triple Letter square.",
                    "Extend MOON downwards onto the 2L square.",
                    "The S of MOONS lands on Double Letter.", Goal.LETTER_BONUS, "STONELA",
                    down("MOON", "H8"), down("MOONS", "H8"), null),
            new Design("First face-off", "", "Look to both ends of the word. Does TEA fit inside a longer word?",
                    "Putting letters on both sides of TEA gives you a longer word.",
                    Goal.SCORE, "SLSTARI", across("TEA", "H8"),
                    across("STEALS", "G8"), across("TEAS", "H8")),
            new Design("Two words at once", "Make at least two words in the same move.",
                    "Start a word going down with the L at the end of TEAL.",
                    "The L is part of both TEAL and LEARN.", Goal.CROSS, "LSEARNR",
                    across("TEA", "H8"), down("LEARN", "K8"), null),
            new Design("Play the blank", "Use a blank in a valid move.",
                    "Try a blank S just after NEST.",
                    "A blank scores zero, but it can open a new word.",
                    Goal.JOKER, "?ASETEL", across("NEST", "H8"), across("NESTs", "H8"), null),
            new Design("Triple it", "Cover a Double or Triple Word square.",
                    "Keep going to the far right of BOOK.",
                    "The final S of BOOKLETS reaches Triple Word.",
                    Goal.WORD_BONUS, "LETSNOR", across("BOOK", "H8"), across("BOOKLETS", "H8"), null),
            new Design("NAB’s challenge", "", "A word right below IN can score three words at once.",
                    "TO also makes IT and NO. All three words score.",
                    Goal.CROSS, "TOEMASR", across("IN", "H8"), across("TO", "H9"),
                    across("INTO", "H8")));

    private static final List<Route> FRENCH_ROUTES = List.of(
            new Route("chats", 12, List.of()),
            new Route("fleurs", 18, List.of()),
            new Route("plateau", 20, List.of()),
            new Route("bonus-egal", 0, List.of(across("LITRE", "H8"), down("RAIE", "K8"))));

    private static final List<Route> ENGLISH_ROUTES = List.of(
            new Route("en-cats", 12, List.of()),
            new Route("en-flower", 20, List.of()),
            new Route("en-queen", 22, List.of()),
            new Route("en-starters", 0, List.of(across("SANDS", "H8"), down("START", "H8"))));

    private NabCampaign() {}

    static List<Stage> catalog(Lexicon lexicon) {
        boolean english = lexicon.language == GameLanguage.ENGLISH;
        List<Design> designs = english ? ENGLISH : FRENCH;
        List<Stage> stages = new ArrayList<>(STAGE_COUNT);
        for (Design design : designs) {
            Stage stage = prepare(stages.size() + 1, design, lexicon);
            if (stage == null) return List.of();
            stages.add(stage);
        }
        List<TwoMoveChallenge.Puzzle> puzzles = new ArrayList<>(TwoMoveChallenge.catalog(lexicon));
        for (var set : TwoMoveChallenge.bonusCircuits(lexicon)) puzzles.addAll(set.puzzles());
        for (Route route : english ? ENGLISH_ROUTES : FRENCH_ROUTES) {
            TwoMoveChallenge.Puzzle source = puzzles.stream().filter(puzzle -> puzzle.id().equals(route.puzzleId()))
                    .findFirst().orElse(null);
            if (source == null) return List.of();
            var rival = replay(source.initial(), route.rival(), lexicon, 2);
            if (rival.size() != route.rival().size()) return List.of();
            String objective = route.rival().isEmpty()
                    ? String.format(Locale.ROOT, lexicon.language.text("Marque %d points en deux coups avec le même chevalet.",
                            "Score %d points in two moves with the same rack."), route.passScore())
                    : rivalObjective(lexicon.language, rival.get(1).after().totalScore(), 2, Goal.SCORE);
            var puzzle = new TwoMoveChallenge.Puzzle(campaignId(stages.size() + 1), source.title(), objective,
                    source.hint(), source.explanation(), source.hintPosition(), source.initial(), source.solution());
            var stage = stage(stages.size() + 1, puzzle, Goal.SCORE, route.passScore(), rival);
            if (stage == null) return List.of();
            stages.add(stage);
        }
        return List.copyOf(stages);
    }

    private static Stage prepare(int number, Design design, Lexicon lexicon) {
        var initial = TwoMoveChallenge.initial(List.of(design.opening()), design.rack(), lexicon);
        if (initial == null) return null;
        var solution = replay(initial, List.of(design.solution()), lexicon, 1);
        if (solution.isEmpty()) return null;
        var rival = design.rival() == null ? List.<TwoMoveChallenge.Step>of()
                : replay(initial, List.of(design.rival()), lexicon, 1);
        if (design.rival() != null && rival.isEmpty()) return null;
        String objective = rival.isEmpty() ? design.objective()
                : rivalObjective(lexicon.language, rival.get(0).preview().score(), 1, design.goal());
        var puzzle = new TwoMoveChallenge.Puzzle(campaignId(number), design.title(), objective, design.hint(),
                design.explanation(), solution.get(0).tiles().get(0).position(), initial, solution);
        return stage(number, puzzle, design.goal(), 1, rival);
    }

    private static Stage stage(int number, TwoMoveChallenge.Puzzle puzzle, Goal goal, int pass,
                               List<TwoMoveChallenge.Step> rival) {
        var last = puzzle.solution().get(puzzle.solution().size() - 1);
        int gold = last.after().totalScore();
        int minimum = rival.isEmpty() ? pass : rival.get(rival.size() - 1).after().totalScore() + 1;
        int silver = minimum + Math.max(1, (gold - minimum) / 2);
        var stage = new Stage(number, puzzle, goal, minimum, Math.min(gold, silver), gold, rival);
        return qualifies(stage, last) ? stage : null;
    }

    private static String campaignId(int number) { return "atelier-v1-" + number; }

    private static List<TwoMoveChallenge.Step> replay(TwoMoveChallenge.Position start,
                                                      List<TwoMoveChallenge.Line> lines,
                                                      Lexicon lexicon, int turns) {
        var position = start;
        List<TwoMoveChallenge.Step> moves = new ArrayList<>(lines.size());
        for (var line : lines) {
            var tiles = TwoMoveChallenge.placement(line, position.board(), position.rack());
            var step = TwoMoveChallenge.play(position, tiles, lexicon, turns);
            if (!step.preview().valid()) return List.of();
            moves.add(step);
            position = step.after();
        }
        return List.copyOf(moves);
    }

    private static String rivalObjective(GameLanguage language, int score, int turns, Goal goal) {
        if (goal == Goal.CROSS) {
            return String.format(Locale.ROOT, language.text("Forme au moins deux mots en un coup pour dépasser les %d points de NAB.",
                    "Beat NAB’s %d points by making at least two words in one move."), score);
        }
        String objective = turns == 1 ? language.text("Dépasse les %d points de NAB en un coup.",
                "Beat NAB’s %d points in one move.")
                : language.text("Dépasse les %d points de NAB en deux coups.",
                        "Beat NAB’s %d points in two moves.");
        return String.format(Locale.ROOT, objective, score);
    }

    static TwoMoveChallenge.Step play(Stage stage, TwoMoveChallenge.Position position,
                                     List<FrenchGame.Tile> tiles, Lexicon lexicon) {
        if (position.moves() >= stage.turns()) {
            return new TwoMoveChallenge.Step(position, List.copyOf(tiles), FrenchGame.Preview.invalid(
                    lexicon.language.text("Tu as déjà joué tous les coups de ce niveau.",
                            "You’ve played all the moves in this level.")), position);
        }
        return TwoMoveChallenge.play(position, tiles, lexicon, stage.turns());
    }

    static boolean qualifies(Stage stage, TwoMoveChallenge.Step step) {
        if (!step.preview().valid() || step.after().moves() != stage.turns()
                || step.after().totalScore() < stage.passScore()) return false;
        return switch (stage.goal()) {
            case ONE_TILE -> step.tiles().size() == 1 && step.preview().word().startsWith(opening(stage));
            case PREFIX -> {
                String word = opening(stage);
                yield step.preview().word().endsWith(word) && step.preview().word().length() > word.length();
            }
            case LETTER_BONUS -> step.tiles().stream().anyMatch(tile ->
                    !tile.blank() && FrenchGame.LETTER_MULTIPLIER[tile.position()] > 1);
            case WORD_BONUS -> step.tiles().stream().anyMatch(tile -> FrenchGame.WORD_MULTIPLIER[tile.position()] > 1);
            case CROSS -> step.preview().words().size() >= 2;
            case JOKER -> step.tiles().stream().anyMatch(FrenchGame.Tile::blank);
            case SCORE -> true;
        };
    }

    private static String opening(Stage stage) {
        var reference = stage.solution().get(0);
        String fullWord = reference.preview().word();
        return stage.goal() == Goal.PREFIX ? fullWord.substring(reference.tiles().size())
                : fullWord.substring(0, fullWord.length() - reference.tiles().size());
    }

    static int stars(Stage stage, TwoMoveChallenge.Position position) {
        int score = position.totalScore();
        if (position.moves() != stage.turns() || score < stage.passScore()) return 0;
        if (score >= stage.goldScore()) return 3;
        return score >= stage.silverScore() ? 2 : 1;
    }
}
