package fr.lettrine.game;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class ExpertBenchmark {
    private static final Pattern COORDINATE = Pattern.compile("([A-O])([1-9]|1[0-5])|([1-9]|1[0-5])([A-O])");
    private static final int MASTER = 4;

    private record Turn(String game, String edition, int index, int player, String rack, String action,
                        String word, String coordinate, int score, Integer top, List<Integer> blankOffsets,
                        String otherRack) {
        static Turn read(String line) {
            String[] fields = line.split("\\t", -1);
            if (fields.length != 12) throw new IllegalArgumentException("Ligne d’archive invalide");
            var blanks = new ArrayList<Integer>();
            if (!fields[10].isEmpty()) for (String offset : fields[10].split(",")) blanks.add(Integer.parseInt(offset));
            return new Turn(fields[0], fields[1], Integer.parseInt(fields[2]), Integer.parseInt(fields[3]),
                    fields[4], fields[5], fields[6], fields[7], Integer.parseInt(fields[8]),
                    fields[9].isEmpty() ? null : Integer.valueOf(fields[9]), blanks, fields[11]);
        }
    }

    private static final class Counts {
        int compared, sameMove, higher, equal, lower, playedPoints, botPoints;
        int archiveTopKnown, botOverArchiveTop;
        final Map<String, Integer> exclusions = new LinkedHashMap<>();

        void exclude(String reason) { exclusions.merge(reason, 1, Integer::sum); }

        void compare(Turn turn, List<FrenchGame.Tile> played, Bot.Choice choice) {
            compared++;
            playedPoints += turn.score();
            botPoints += choice.score();
            if (choice.score() > turn.score()) higher++;
            else if (choice.score() == turn.score()) equal++;
            else lower++;
            if (tileSignature(played).equals(tileSignature(choice.tiles()))) sameMove++;
            if (turn.top() != null) {
                archiveTopKnown++;
                if (choice.score() > turn.top()) botOverArchiveTop++;
            }
        }

        void print(String label) {
            System.out.printf("%s : %d comparés, %d mêmes placements, NAB %d/%d/%d (+/=/-), points NAB %d / humains %d%n",
                    label, compared, sameMove, higher, equal, lower, botPoints, playedPoints);
            System.out.printf("  Exclusions : %s. NAB dépasse le top de l’archive sur %d/%d positions renseignées.%n",
                    exclusions, botOverArchiveTop, archiveTopKnown);
        }
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path input = Path.of(args[0]);
        List<String> metadata = Files.readAllLines(input.resolve("inputs.txt"));
        Lexicon stock;
        try (var reader = Files.newBufferedReader(input.resolve("words-fr.txt"))) {
            stock = Lexicon.read(reader, "Grammalecte 7.7", "MPL-2.0", metadata.get(0), Integer.parseInt(metadata.get(1)));
        }
        if (metadata.size() == 8) {
            int[] weights = metadata.subList(2, 8).stream().mapToInt(Integer::parseInt).toArray();
            stock.expert = new Bot.Policy(weights[0], weights[1], weights[2], weights[3], weights[4], weights[5]);
            stock.trained = true;
        }
        System.out.printf("NAB Android Maître · %s · %s · politique entraînée=%s%n", stock.label, stock.hash, stock.trained);
        System.out.println("Comparaison du score immédiat sur les mêmes positions. Référentiel de validation différent de l’ODS historique.");
        Counts total = new Counts();
        Map<String, Counts> games = new LinkedHashMap<>();
        char[] board = new char[FrenchGame.CELLS];
        boolean[] blanks = new boolean[FrenchGame.CELLS];
        String lastGame = "";
        boolean intact = true;
        for (String line : Files.readAllLines(input.resolve("positions.tsv"))) {
            Turn turn = Turn.read(line);
            if (!turn.game().equals(lastGame)) {
                board = new char[FrenchGame.CELLS];
                blanks = new boolean[FrenchGame.CELLS];
                lastGame = turn.game();
                intact = true;
            }
            Counts game = games.computeIfAbsent(turn.game() + " · " + turn.edition(), ignored -> new Counts());
            if (!"place".equals(turn.action())) {
                exclude(total, game, "sans placement comparé : " + turn.action());
                continue;
            }
            if (!intact) {
                exclude(total, game, "plateau non reconstructible après un tour antérieur");
                continue;
            }
            List<Character> rack = turn.rack().chars().mapToObj(value -> (char) value).toList();
            List<FrenchGame.Tile> played;
            List<FrenchGame.FormedWord> formed;
            try {
                played = reconstruct(turn, board, blanks, rack);
                formed = FrenchGame.recordedWords(board, blanks, played);
                if (formed.stream().noneMatch(word -> word.word().equals(turn.word()))
                        || FrenchGame.empty(board) && played.stream().noneMatch(tile -> tile.position() == FrenchGame.CENTER)
                        || !FrenchGame.empty(board) && formed.stream().noneMatch(FrenchGame.FormedWord::touchesExisting)) {
                    throw new IllegalArgumentException("placement incompatible avec le plateau");
                }
            } catch (IllegalArgumentException invalid) {
                reject(turn, total, game, invalid.getMessage());
                intact = false;
                continue;
            }
            int rescore = formed.stream().mapToInt(FrenchGame.FormedWord::score).sum();
            if (played.size() == FrenchGame.RACK_SIZE) rescore += 50;
            int bag = bagSize(turn, board, blanks);
            if (bag < 0) {
                reject(turn, total, game, "composition du sac non vérifiable");
            } else if (rescore != turn.score()) {
                reject(turn, total, game, "score incohérent (archive=" + turn.score() + ", moteur=" + rescore + ")");
            } else {
                List<String> unknown = formed.stream().map(FrenchGame.FormedWord::word)
                        .filter(word -> !stock.contains(word)).distinct().toList();
                if (!unknown.isEmpty()) {
                    exclude(total, game, "mots absents de Grammalecte");
                    System.out.printf("  %s T%d exclu : absent de Grammalecte %s%n", turn.game(), turn.index(), unknown);
                } else {
                    long seed = 31L * turn.game().hashCode() + turn.index();
                    Bot.Choice choice = Bot.choose(board, blanks, rack, bag, stock, MASTER, seed);
                    if (!choice.tiles().isEmpty()) {
                        FrenchGame.Preview validated = FrenchGame.preview(board, blanks, rack, stock, choice.tiles());
                        if (!validated.valid() || validated.score() != choice.score()) {
                            throw new IllegalStateException("Coup NAB invalide : " + turn.game() + " T" + turn.index());
                        }
                    }
                    total.compare(turn, played, choice);
                    game.compare(turn, played, choice);
                }
            }
            for (FrenchGame.Tile tile : played) {
                board[tile.position()] = tile.letter();
                blanks[tile.position()] = tile.blank();
            }
        }
        games.forEach((game, counts) -> counts.print(game));
        total.print("Total");
        System.out.println("Le sac est calculé à partir du plateau et des deux chevalets archivés. NAB reçoit uniquement le nombre de tuiles dans le sac.");
    }

    private static List<FrenchGame.Tile> reconstruct(Turn turn, char[] board, boolean[] blanks, List<Character> rack) {
        if (turn.player() < 0 || turn.player() > 1 || rack.isEmpty() || rack.size() > FrenchGame.RACK_SIZE
                || !turn.rack().matches("[A-Z?]+") || !turn.word().matches("[A-Z]{2,15}")) {
            throw new IllegalArgumentException("données du tour invalides");
        }
        var coordinate = COORDINATE.matcher(turn.coordinate());
        if (!coordinate.matches()) throw new IllegalArgumentException("coordonnée invalide");
        boolean vertical = coordinate.group(3) != null;
        String rowLetter = coordinate.group(vertical ? 4 : 1);
        int row = rowLetter.charAt(0) - 'A';
        int col = Integer.parseInt(coordinate.group(vertical ? 3 : 2)) - 1;
        int length = turn.word().length();
        if ((vertical ? row : col) + length > FrenchGame.SIZE
                || turn.blankOffsets().stream().anyMatch(offset -> offset < 0 || offset >= length)) {
            throw new IllegalArgumentException("placement ou joker hors du plateau");
        }
        boolean[] used = new boolean[rack.size()];
        List<FrenchGame.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            int square = (row + (vertical ? i : 0)) * FrenchGame.SIZE + col + (vertical ? 0 : i);
            char letter = turn.word().charAt(i);
            boolean blank = turn.blankOffsets().contains(i);
            if (board[square] != 0) {
                if (board[square] != letter || blank && !blanks[square]) {
                    throw new IllegalArgumentException("lettre d’appui incompatible");
                }
                continue;
            }
            int rackIndex = 0;
            while (rackIndex < rack.size() && (used[rackIndex] || rack.get(rackIndex) != (blank ? '?' : letter))) rackIndex++;
            if (rackIndex == rack.size()) throw new IllegalArgumentException("lettre absente du chevalet");
            used[rackIndex] = true;
            tiles.add(new FrenchGame.Tile(square, rackIndex, letter, blank));
        }
        if (tiles.isEmpty()) throw new IllegalArgumentException("aucune tuile posée");
        return tiles;
    }

    private static int bagSize(Turn turn, char[] board, boolean[] blanks) {
        if (!turn.otherRack().matches("[A-Z?]{0,7}")) return -1;
        int[] used = new int[27];
        int count = turn.rack().length() + turn.otherRack().length();
        for (int i = 0; i < board.length; i++) {
            if (board[i] == 0) continue;
            used[blanks[i] ? 26 : board[i] - 'A']++;
            count++;
        }
        String racks = turn.rack() + turn.otherRack();
        for (char letter : racks.toCharArray()) used[letter == '?' ? 26 : letter - 'A']++;
        for (int i = 0; i < used.length; i++) {
            if (used[i] > GameLanguage.FRENCH.tileCount(i == 26 ? '?' : (char) ('A' + i))) return -1;
        }
        return 102 - count;
    }

    private static List<String> tileSignature(List<FrenchGame.Tile> tiles) {
        return tiles.stream().sorted(Comparator.comparingInt(FrenchGame.Tile::position))
                .map(tile -> tile.position() + ":" + tile.letter() + ":" + tile.blank()).toList();
    }

    private static void exclude(Counts total, Counts game, String reason) {
        total.exclude(reason);
        game.exclude(reason);
    }

    private static void reject(Turn turn, Counts total, Counts game, String reason) {
        exclude(total, game, "plateau, chevalet ou score non vérifiable");
        System.out.printf("  %s T%d exclu : %s%n", turn.game(), turn.index(), reason);
    }
}
