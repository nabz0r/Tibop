package fr.lettrine.game;

record Palette(String name, int page, int paper, int ink, int muted, int line, int accent,
               int deep, int heroInk, int tile, int tileEdge, int fresh, int ordinary,
               int doubleLetter, int tripleLetter, int doubleWord, int tripleWord) {
    static final Palette[] THEMES = {
            new Palette("Forêt", 0xfff5f2e9, 0xfffffcf6, 0xff193d2e, 0xff56685a,
                    0xffdbddd1, 0xff35624b, 0xff203f31, 0xfff8f3e3, 0xffefdbac,
                    0xffa18554, 0xffcbe3b9, 0xffe8ecdf, 0xffc6dddb, 0xffa5c9cc, 0xffeac9ba, 0xffdcac97),
            new Palette("Crépuscule", 0xfff4eef0, 0xfffffaff, 0xff412944, 0xff70596d,
                    0xffe4d5e1, 0xff775071, 0xff3b2a48, 0xfffff1fb, 0xffefdbd3,
                    0xffac8297, 0xffebc9dc, 0xffe8e2ee, 0xffc6d8e4, 0xffaebedb, 0xffe9c8db, 0xffd5a9c8),
            new Palette("Sable", 0xfff7f1e6, 0xfffffcf4, 0xff493621, 0xff76644e,
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
