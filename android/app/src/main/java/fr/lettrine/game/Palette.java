package fr.lettrine.game;

record Palette(int name, int page, int paper, int ink, int muted, int line, int accent,
               int deep, int heroInk, int tile, int tileEdge, int fresh, int ordinary,
               int doubleLetter, int tripleLetter, int doubleWord, int tripleWord) {
    static final Palette[] THEMES = {
            new Palette(R.string.theme_rivet, 0xfff7f3ec, 0xfffffcf8, 0xff201c3d, 0xff5e566c,
                    0xffded8e7, 0xffa83933, 0xff29214f, 0xfffff8ee, 0xffffefd0,
                    0xff968277, 0xffffa68f, 0xffeae4f7, 0xffddd4f8, 0xffc6b7f1, 0xffffd6c6, 0xfff6a38d),
            new Palette(R.string.theme_dusk, 0xfff4eef0, 0xfffffaff, 0xff412944, 0xff70596d,
                    0xffe4d5e1, 0xff775071, 0xff3b2a48, 0xfffff1fb, 0xffefdbd3,
                    0xffac8297, 0xffebc9dc, 0xffe8e2ee, 0xffc6d8e4, 0xffaebedb, 0xffe9c8db, 0xffd5a9c8),
            new Palette(R.string.theme_sand, 0xfff7f1e6, 0xfffffcf4, 0xff493621, 0xff76644e,
                    0xffe4d8c4, 0xff835b3e, 0xff4b3728, 0xfffff3d9, 0xfff0dbac,
                    0xffab8757, 0xfff1ca9f, 0xfff0e7d4, 0xffe1dcc1, 0xffcfcf9f, 0xffe9cbb7, 0xffdca994),
            new Palette(R.string.bonus_indigo_theme, 0xffeeeff8, 0xfff8f8ff, 0xff252359, 0xff57566f,
                    0xffd6d9eb, 0xff43458a, 0xff303478, 0xfffdf6e9, 0xfffff2db,
                    0xff9389ab, 0xffd9c8fa, 0xffdcdff4, 0xffbfdef5, 0xff9abbe8, 0xfff5d1e9, 0xffe6aecd),
            new Palette(R.string.bonus_poppy_theme, 0xfffaf0e6, 0xfffffbf6, 0xff57231f, 0xff794d47,
                    0xffeddbce, 0xffa32e27, 0xff752c2b, 0xfffff2dd, 0xffffe3bd,
                    0xffb68160, 0xfff6bc9a, 0xfff3dfd7, 0xffe9dac9, 0xffd8b9b0, 0xffffc1a4, 0xffef9b86)
    };

    static String unlockId(int theme) {
        return switch (theme) {
            case 3 -> "indigo";
            case 4 -> "poppy";
            default -> "";
        };
    }

    int bonus(int position) {
        if (FrenchGame.WORD_MULTIPLIER[position] == 3) return tripleWord;
        if (FrenchGame.WORD_MULTIPLIER[position] == 2) return doubleWord;
        if (FrenchGame.LETTER_MULTIPLIER[position] == 3) return tripleLetter;
        if (FrenchGame.LETTER_MULTIPLIER[position] == 2) return doubleLetter;
        return ordinary;
    }
}
