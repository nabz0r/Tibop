package fr.lettrine.game;

import java.util.List;

record PracticePosition(char[] board, boolean[] blanks, List<Character> rack,
                        MatchReview.Alternative reference, Integer originalScore) {
    PracticePosition {
        board = board.clone();
        blanks = blanks.clone();
        rack = List.copyOf(rack);
    }

    @Override public char[] board() { return board.clone(); }
    @Override public boolean[] blanks() { return blanks.clone(); }

    static PracticePosition first(Lexicon lexicon) {
        int center = FrenchGame.CENTER;
        char[] board = new char[FrenchGame.CELLS];
        boolean[] blanks = new boolean[FrenchGame.CELLS];
        boolean english = lexicon.language == GameLanguage.ENGLISH;
        String opening = english ? "SHIP" : "CHAT";
        if (!lexicon.contains(opening) || !lexicon.contains(opening + 'S')) return null;
        opening.getChars(0, opening.length(), board, center);

        List<Character> rack = english ? List.of('S', 'M', 'E', 'N', 'T', 'A', 'R')
                : List.of('S', 'O', 'N', 'E', 'R', 'I', 'L');
        List<FrenchGame.Tile> reference = english ? List.of(
                new FrenchGame.Tile(center + 4, 1, 'M', false),
                new FrenchGame.Tile(center + 5, 2, 'E', false),
                new FrenchGame.Tile(center + 6, 3, 'N', false),
                new FrenchGame.Tile(center + 7, 4, 'T', false)) : List.of(
                new FrenchGame.Tile(center + 4, 1, 'O', false),
                new FrenchGame.Tile(center + 5, 2, 'N', false),
                new FrenchGame.Tile(center + 6, 0, 'S', false));
        FrenchGame.Preview preview = FrenchGame.preview(board, blanks, rack, lexicon, reference);
        if (!preview.valid()) return null;
        return new PracticePosition(board, blanks, rack, new MatchReview.Alternative(preview, reference), null);
    }

    FrenchGame.Preview preview(List<FrenchGame.Tile> tiles, Lexicon lexicon) {
        return FrenchGame.preview(board, blanks, rack, lexicon, tiles);
    }
}
