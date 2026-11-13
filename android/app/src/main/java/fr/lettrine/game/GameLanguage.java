package fr.lettrine.game;

enum GameLanguage {
    FRENCH("fr", "fr-classic-v1",
            new int[]{9,2,2,3,15,2,2,2,8,1,1,5,3,6,6,2,1,6,6,6,6,2,1,1,1,1},
            new int[]{1,3,3,2,1,4,2,4,1,8,10,1,2,1,1,3,8,1,1,1,1,4,10,10,10,10}),
    ENGLISH("en", "en-wespa-v1",
            new int[]{9,2,2,4,12,2,3,2,9,1,1,4,2,6,8,2,1,6,4,6,4,2,2,1,2,1},
            new int[]{1,3,3,2,1,4,2,4,1,8,5,1,3,1,1,3,10,1,1,1,1,4,4,8,4,10});

    final String code;
    final String profile;
    private final int[] counts;
    private final int[] values;

    GameLanguage(String code, String profile, int[] counts, int[] values) {
        this.code = code;
        this.profile = profile;
        this.counts = counts;
        this.values = values;
    }

    static GameLanguage fromCode(String code) { return "en".equalsIgnoreCase(code) ? ENGLISH : FRENCH; }

    int tileCount(char letter) { return letter == '?' ? 2 : counts[letter - 'A']; }

    int value(char letter) { return letter >= 'A' && letter <= 'Z' ? values[letter - 'A'] : 0; }

    boolean usesDuelRules(int players) { return this == ENGLISH && players == 2; }

    String text(String french, String english) { return this == ENGLISH ? english : french; }

    String storageName(String base) { return this == ENGLISH ? base + "-en" : base; }
}
