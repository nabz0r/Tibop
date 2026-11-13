package fr.lettrine.game;

import java.util.ArrayList;
import java.util.List;

final class TwoMoveChallenge {
    private static final int TURN_LIMIT = 2;

    record Position(char[] board, boolean[] blanks, List<Character> rack, List<Integer> scores) {
        Position {
            board = board.clone();
            blanks = blanks.clone();
            rack = List.copyOf(rack);
            scores = List.copyOf(scores);
        }

        @Override public char[] board() { return board.clone(); }
        @Override public boolean[] blanks() { return blanks.clone(); }
        int moves() { return scores.size(); }
        int totalScore() { return scores.stream().mapToInt(Integer::intValue).sum(); }
    }

    record Step(Position before, List<FrenchGame.Tile> tiles, FrenchGame.Preview preview, Position after) {}

    record Puzzle(String id, String title, String objective, String hint, String explanation,
                  int hintPosition, Position initial, List<Step> solution) {}

    private record Line(String word, int start, int direction) {}

    private record Design(String id, String title, String objective, String hint, String explanation,
                          String letters, List<Line> seeds, Line first, Line second) {}

    private static final List<Design> DESIGNS = List.of(
            new Design("chats", "Le S qui change tout", "Crée un appui avec une seule lettre.",
                    "Essaie d’allonger CHAT d’une lettre. Regarde ensuite sous la nouvelle case.",
                    "Le S de CHATS devient le début de SALUT. Une seule lettre a ouvert le deuxième mot.",
                    "SALUTER", List.of(across("CHAT", "H8")),
                    across("CHATS", "H8"), down("SALUT", "L8")),
            new Design("lunes", "Au clair de la lune", "Ouvre un nouveau chemin vers la droite.",
                    "Le bout de LUNE peut servir de départ à un mot horizontal.",
                    "Le S ajouté à LUNE permet de faire partir SOLEIL vers la droite.",
                    "LIESOLM", List.of(down("LUNE", "H8")),
                    down("LUNES", "H8"), across("SOLEIL", "H12")),
            new Design("retour", "En marche arrière", "Cherche de la place avant le mot déjà posé.",
                    "Deux lettres devant TOUR suffisent pour créer un appui à gauche.",
                    "RETOUR place un R à gauche de TOUR. Tu peux alors écrire RÊVE vers le bas.",
                    "REVEEAN", List.of(across("TOUR", "H8"), down("TABLE", "H8")),
                    across("RETOUR", "F8"), down("REVE", "F8")),
            new Design("place", "Fais de la place", "Prolonge LAC par ses deux bouts.",
                    "Garde le groupe LAC au milieu de ton premier mot.",
                    "PLACE ajoute un P au-dessus de LAC. Ce P lance POMME vers la droite.",
                    "PEOMMER", List.of(down("LAC", "H8"), across("SOL", "F8")),
                    down("PLACE", "H7"), across("POMME", "H7")),
            new Design("deboise", "De part et d’autre", "Utilise une nouvelle lettre au milieu du second mot.",
                    "Transforme BOIS en ajoutant des lettres à gauche et à droite.",
                    "Le E final de DÉBOISE sert de milieu à SEL. Tu poses une lettre au-dessus et une en dessous.",
                    "DEESLAT", List.of(across("BOIS", "H8"), down("BAL", "H8")),
                    across("DEBOISE", "F8"), down("SEL", "L7")),
            new Design("amer", "Marée haute", "Crée un appui au-dessus de MER.",
                    "Une lettre au-dessus de MER peut aussi entrer dans un mot de quatre lettres.",
                    "Le A d’AMER devient la deuxième lettre de MARE. Le second mot s’étend de chaque côté de l’appui.",
                    "AMREITS", List.of(down("MER", "H8")),
                    down("AMER", "H7"), across("MARE", "G7")),
            new Design("nids", "Entre les branches", "Utilise le joker pour créer un appui.",
                    "Donne la valeur S au joker pour garder le vrai S en main.",
                    "Le joker transforme NID en NIDS. Il reste un S sans valeur sur le plateau, et le vrai S sert à écrire TASSE.",
                    "?TASEEL", List.of(across("NID", "H8"), down("NAGE", "H8")),
                    across("NIDs", "H8"), down("TASSE", "K6")),
            new Design("eau", "Le bruit de l’eau", "Pose un mot qui en crée trois autres.",
                    "Essaie trois lettres juste sous C, H et A. Garde D, I et O pour la suite.",
                    "EAU forme aussi CE, HA et AU. Le dernier croisement, AU, se prolonge en AUDIO.",
                    "EAUDIOZ", List.of(across("CHAT", "H8")),
                    across("EAU", "H9"), down("AUDIO", "J8")),
            new Design("plateau", "Le quai des lettres", "Garde le Q pour l’appui que tu vas créer.",
                    "Allonge PLAT vers la droite pour donner une place à ton Q au prochain coup.",
                    "PLATEAU pose le A de QUAI. Les lettres Q et U restées en main se jouent au-dessus de lui.",
                    "EAUQUIS", List.of(across("PLAT", "H8"), down("PILE", "H8")),
                    across("PLATEAU", "H8"), down("QUAI", "M6")),
            new Design("ponts", "Mise à feu", "Prépare un mot qui traverse le nouveau bout du pont.",
                    "Une petite rallonge laisse les deux E nécessaires pour le second coup.",
                    "PONTS crée l’appui central de FUSÉE. Les deux E restent disponibles après le premier coup.",
                    "SFUEERN", List.of(down("PONT", "H8"), across("POMME", "H8")),
                    down("PONTS", "H8"), across("FUSEE", "F12")),
            new Design("fleurs", "Jardin de sable", "Repère la case lettre triple près d’un nouvel appui.",
                    "Un S au bout de FLEUR te laisse partir vers le bas.",
                    "Le S de FLEURS ouvre SABLE vers le bas. Le B de SABLE profite de la case lettre triple.",
                    "SABLENR", List.of(across("FLEUR", "E8"), down("UN", "H8")),
                    across("FLEURS", "E8"), down("SABLE", "J8")),
            new Design("partage", "À la plage", "Prépare un passage sur une case lettre triple.",
                    "Ajoute trois lettres après PART pour faire apparaître un G près de la case bonus.",
                    "Le G de PARTAGE devient l’appui de PLAGE. Le L de PLAGE tombe sur la case lettre triple.",
                    "AGEPLAE", List.of(across("PART", "E8")),
                    across("PARTAGE", "E8"), down("PLAGE", "J5"))
    );

    private static final List<Design> ENGLISH_DESIGNS = List.of(
            new Design("en-cats", "The S that starts it", "Create a starting point with one letter.",
                    "Try adding one letter to CAT. Then look below the new tile.",
                    "The S in CATS is also the first letter of STOP. That one tile gives you a new line to play.",
                    "STOPALE", List.of(across("CAT", "H8")),
                    across("CATS", "H8"), down("STOP", "K8")),
            new Design("en-moons", "Moon and stone", "Use a new tile to open a path to the right.",
                    "The bottom of MOON can become the start of a word that runs across.",
                    "The S added to MOON lets you play STONE to the right.",
                    "STONELA", List.of(down("MOON", "H8")),
                    down("MOONS", "H8"), across("STONE", "H12")),
            new Design("en-return", "Backtrack", "Find space before the word on the board.",
                    "Two letters before TURN will give you a new starting point to the left.",
                    "RETURN puts an R before TURN. You can then play RAIN down from that R.",
                    "REAINOS", List.of(across("TURN", "H8"), down("TOP", "H8")),
                    across("RETURN", "F8"), down("RAIN", "F8")),
            new Design("en-place", "Make room", "Extend ACE upwards to create a new crossing.",
                    "Keep ACE at the end of your first word, and look to the right of the new P.",
                    "PLACE adds a P above ACE. That P is the first letter of PIANO.",
                    "PLIANOS", List.of(down("ACE", "H8"), across("TEA", "F8")),
                    down("PLACE", "H6"), across("PIANO", "H6")),
            new Design("en-train", "On the right track", "Build your second word around the new tile.",
                    "One tile turns RAIN into something that runs on rails.",
                    "The T added to RAIN goes in the middle of STONE, with new tiles on both sides.",
                    "TSONELD", List.of(across("RAIN", "H8")),
                    across("TRAIN", "G8"), down("STONE", "G7")),
            new Design("en-ice", "First snow", "Create a crossing one square above ICE.",
                    "The new tile above ICE can become the second letter of a winter word.",
                    "The N in NICE lets you play SNOW across. There is room on both sides of the N.",
                    "NSOWARE", List.of(down("ICE", "H8")),
                    down("NICE", "H7"), across("SNOW", "G7")),
            new Design("en-nest", "In the branches", "Use the blank tile to create a crossing.",
                    "Play the blank as an S so you can keep the real S for your second word.",
                    "The blank turns NEST into NESTS. It keeps its zero value, and the real S helps make ASSET.",
                    "?ASETEL", List.of(across("NEST", "H8"), down("NIGHT", "H8")),
                    across("NESTs", "H8"), down("ASSET", "L7")),
            new Design("en-into", "Side by side", "Make three words with a single placement.",
                    "Try placing a two-letter word just below IN. Keep E and M for the second turn.",
                    "TO also makes IT and NO. You can extend IT down into ITEM.",
                    "TOEMASR", List.of(across("IN", "H8")),
                    across("TO", "H9"), down("ITEM", "H8")),
            new Design("en-queen", "Save the Q", "Keep the Q for the crossing you are about to make.",
                    "Extend PLAY so you have an E on the board for a word starting with QU.",
                    "PLAYED puts an E in the middle of QUEEN. Keep the other E for the fourth letter.",
                    "EDQUENA", List.of(across("PLAY", "H8"), down("PINE", "H8")),
                    across("PLAYED", "H8"), down("QUEEN", "L6")),
            new Design("en-boats", "Across the bay", "Turn a vertical word into a horizontal crossing.",
                    "Start at the bottom of BOAT and keep R, O and E in your rack.",
                    "BOATS gives you an S to build ROSE around on the bottom row.",
                    "SROENIA", List.of(down("BOAT", "H8"), across("BREAD", "H8")),
                    down("BOATS", "H8"), across("ROSE", "F12")),
            new Design("en-flower", "A triple in bloom", "Aim for the triple-letter square on the second turn.",
                    "An S at the end of FLOWER opens a line going down.",
                    "The S in FLOWERS starts SOBER. The B lands on a triple-letter square.",
                    "SOBERTA", List.of(across("FLOWER", "D8"), down("WATER", "G8")),
                    across("FLOWERS", "D8"), down("SOBER", "J8")),
            new Design("en-starters", "To the edge", "Reach a triple-word square on the second turn.",
                    "Extend STAR by a single letter first. You can reach the edge on the next turn.",
                    "START keeps E, R and S in your rack. Use all three to make STARTERS and reach the triple-word square.",
                    "TERSMLN", List.of(down("STAR", "H8"), across("SAND", "H8")),
                    down("START", "H8"), down("STARTERS", "H8"))
    );

    private TwoMoveChallenge() {}

    static List<Puzzle> catalog(Lexicon lexicon) {
        List<Design> designs = lexicon.language == GameLanguage.ENGLISH ? ENGLISH_DESIGNS : DESIGNS;
        List<Puzzle> puzzles = new ArrayList<>(designs.size());
        for (Design design : designs) {
            Puzzle puzzle = prepare(design, lexicon);
            if (puzzle != null) puzzles.add(puzzle);
        }
        return List.copyOf(puzzles);
    }

    static Step play(Position position, List<FrenchGame.Tile> tiles, Lexicon lexicon) {
        List<FrenchGame.Tile> placed = List.copyOf(tiles);
        if (position.moves() == TURN_LIMIT) {
            return new Step(position, placed, FrenchGame.Preview.invalid(
                    lexicon.language == GameLanguage.ENGLISH ? "Both moves have already been played." : "Les deux coups sont déjà joués."), position);
        }
        FrenchGame.Preview preview = FrenchGame.preview(position.board, position.blanks, position.rack,
                lexicon, placed);
        if (preview.valid() && position.moves() == 0 && placed.size() == position.rack.size()) {
            preview = FrenchGame.Preview.invalid(lexicon.language == GameLanguage.ENGLISH
                    ? "Keep at least one tile for the second move." : "Garde au moins une lettre pour le deuxième coup.");
        }
        if (!preview.valid()) return new Step(position, placed, preview, position);

        char[] board = position.board();
        boolean[] blanks = position.blanks();
        boolean[] consumed = new boolean[position.rack.size()];
        for (FrenchGame.Tile tile : placed) {
            board[tile.position()] = tile.letter();
            blanks[tile.position()] = tile.blank();
            consumed[tile.rackIndex()] = true;
        }
        List<Character> rack = new ArrayList<>(position.rack.size() - placed.size());
        for (int i = 0; i < consumed.length; i++) {
            if (!consumed[i]) rack.add(position.rack.get(i));
        }
        List<Integer> scores = new ArrayList<>(position.scores);
        scores.add(preview.score());
        return new Step(position, placed, preview, new Position(board, blanks, rack, scores));
    }

    private static Puzzle prepare(Design design, Lexicon lexicon) {
        char[] board = new char[FrenchGame.CELLS];
        boolean[] blanks = new boolean[FrenchGame.CELLS];
        for (Line line : design.seeds) {
            List<Character> seedRack = new ArrayList<>(FrenchGame.RACK_SIZE);
            for (int i = 0; i < line.word.length(); i++) {
                int position = FrenchGame.step(line.start, line.direction, i);
                if (position < 0) return null;
                if (board[position] == 0) seedRack.add(line.word.charAt(i));
            }
            List<FrenchGame.Tile> tiles = placement(line, board, seedRack);
            if (!FrenchGame.preview(board, blanks, seedRack, lexicon, tiles).valid()) return null;
            for (FrenchGame.Tile tile : tiles) board[tile.position()] = tile.letter();
        }

        List<Character> rack = new ArrayList<>(FrenchGame.RACK_SIZE);
        for (int i = 0; i < design.letters.length(); i++) rack.add(design.letters.charAt(i));
        Position initial = new Position(board, blanks, rack, List.of());
        Step first = play(initial, placement(design.first, initial.board, initial.rack), lexicon);
        if (!first.preview.valid()) return null;
        Step second = play(first.after, placement(design.second, first.after.board, first.after.rack), lexicon);
        if (!second.preview.valid()) return null;
        return new Puzzle(design.id, design.title, design.objective, design.hint, design.explanation,
                first.tiles.get(0).position(), initial, List.of(first, second));
    }

    private static List<FrenchGame.Tile> placement(Line line, char[] board, List<Character> rack) {
        boolean[] used = new boolean[rack.size()];
        List<FrenchGame.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < line.word.length(); i++) {
            int position = FrenchGame.step(line.start, line.direction, i);
            char symbol = line.word.charAt(i);
            boolean blank = Character.isLowerCase(symbol);
            char letter = Character.toUpperCase(symbol);
            if (position < 0) return List.of();
            if (board[position] == letter) continue;
            if (board[position] != 0) return List.of();
            char needed = blank ? FrenchGame.JOKER : letter;
            int index = 0;
            while (index < rack.size() && (used[index] || rack.get(index) != needed)) index++;
            if (index == rack.size()) return List.of();
            used[index] = true;
            tiles.add(new FrenchGame.Tile(position, index, letter, blank));
        }
        return tiles;
    }

    private static Line across(String word, String cell) { return line(word, cell, 1); }

    private static Line down(String word, String cell) { return line(word, cell, FrenchGame.SIZE); }

    private static Line line(String word, String cell, int direction) {
        int row = Integer.parseInt(cell.substring(1)) - 1;
        int column = cell.charAt(0) - 'A';
        return new Line(word, row * FrenchGame.SIZE + column, direction);
    }
}
