package fr.lettrine.game;

record Palette(int name, int page, int paper, int ink, int muted, int line, int accent,
               int deep, int heroInk, int tile, int tileEdge, int fresh, int ordinary,
               int doubleLetter, int tripleLetter, int doubleWord, int tripleWord) {
    static final Palette[] THEMES = {
            new Palette(R.string.theme_rivet, 0xfff3efe6, 0xfffffcf6, 0xff1c2440, 0xff535a70,
                    0xffdcd9d2, 0xffb83224, 0xff252e61, 0xfffff7e9, 0xfff4e8d1,
                    0xff9d8e80, 0xffffb19c, 0xffe2e2ea, 0xffd1d3ed, 0xffb9bcf1, 0xffffd1bd, 0xffeea58e),
            new Palette(R.string.theme_dusk, 0xfff4eef0, 0xfffffaff, 0xff412944, 0xff70596d,
                    0xffe4d5e1, 0xff775071, 0xff3b2a48, 0xfffff1fb, 0xffefdbd3,
                    0xffac8297, 0xffebc9dc, 0xffe8e2ee, 0xffc6d8e4, 0xffaebedb, 0xffe9c8db, 0xffd5a9c8),
            new Palette(R.string.theme_sand, 0xfff7f1e6, 0xfffffcf4, 0xff493621, 0xff76644e,
                    0xffe4d8c4, 0xff835b3e, 0xff4b3728, 0xfffff3d9, 0xfff0dbac,
                    0xffab8757, 0xfff1ca9f, 0xfff0e7d4, 0xffe1dcc1, 0xffcfcf9f, 0xffe9cbb7, 0xffdca994)
    };

    int bonus(int position) {
        if (FrenchGame.WORD_MULTIPLIER[position] == 3) return tripleWord;
        if (FrenchGame.WORD_MULTIPLIER[position] == 2) return doubleWord;
        if (FrenchGame.LETTER_MULTIPLIER[position] == 3) return tripleLetter;
        if (FrenchGame.LETTER_MULTIPLIER[position] == 2) return doubleLetter;
        return ordinary;
    }
}
