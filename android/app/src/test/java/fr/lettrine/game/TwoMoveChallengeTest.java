package fr.lettrine.game;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TwoMoveChallengeTest {
    private static Lexicon french;
    private static Lexicon english;
    private static List<TwoMoveChallenge.Puzzle> puzzles;

    @BeforeClass public static void loadBundledDictionary() throws Exception {
        try (BufferedReader reader = Files.newBufferedReader(Path.of("../../data/fr/words.txt"))) {
            french = Lexicon.read(reader, "Grammalecte 7.7", "", "", 406312);
        }
        try (BufferedReader reader = Files.newBufferedReader(Path.of("../../data/en/words.txt"))) {
            english = Lexicon.read(reader, "ENABLE2K", "Public domain", "", 169266, GameLanguage.ENGLISH);
        }
        puzzles = TwoMoveChallenge.catalog(french);
    }

    @Test public void allTwelvePuzzlesHaveVerifiedTwoMoveSolutionsWithoutDrawing() {
        verifyCatalog(puzzles, french, "Les deux coups sont déjà joués.");
    }

    @Test public void englishPuzzlesHaveDistinctVerifiedSolutionsWithEnglishTileValues() {
        var catalog = TwoMoveChallenge.catalog(english);
        verifyCatalog(catalog, english, "Both moves have already been played.");
        assertTrue(catalog.stream().allMatch(puzzle -> puzzle.id().startsWith("en-")));
        var queen = catalog.stream().filter(puzzle -> puzzle.id().equals("en-queen")).findFirst().orElseThrow();
        assertEquals("QUEEN", queen.solution().get(1).preview().word());
        assertEquals(14, queen.solution().get(1).preview().score());
        var starters = catalog.stream().filter(puzzle -> puzzle.id().equals("en-starters")).findFirst().orElseThrow();
        assertEquals(24, starters.solution().get(1).preview().score());
        var crosses = catalog.stream().filter(puzzle -> puzzle.id().equals("en-into")).findFirst().orElseThrow();
        assertEquals(List.of("TO", "IT", "NO"), crosses.solution().get(0).preview().words());
    }

    @Test public void filteredDictionaryKeepsEveryFrenchPuzzleAndBonusCircuitPlayable() throws Exception {
        Lexicon filtered = french.filtered("Classique", "filtered",
                Files.readAllLines(Path.of("../../data/fr/classic-exclusions.txt")));
        var catalog = TwoMoveChallenge.catalog(filtered);
        assertSamePositions(puzzles, catalog);
        verifyCatalog(catalog, filtered, "Les deux coups sont déjà joués.");
        var originalCircuits = TwoMoveChallenge.bonusCircuits(french);
        var filteredCircuits = TwoMoveChallenge.bonusCircuits(filtered);
        assertEquals(originalCircuits.stream().map(TwoMoveChallenge.BonusSet::id).toList(),
                filteredCircuits.stream().map(TwoMoveChallenge.BonusSet::id).toList());
        for (int i = 0; i < originalCircuits.size(); i++) {
            var rounds = filteredCircuits.get(i).puzzles();
            assertSamePositions(originalCircuits.get(i).puzzles(), rounds);
            verifyCatalog(rounds, filtered, "Les deux coups sont déjà joués.", BonusEconomy.ROUND_COUNT);
        }
    }

    private static void assertSamePositions(List<TwoMoveChallenge.Puzzle> original,
                                            List<TwoMoveChallenge.Puzzle> curated) {
        assertEquals(original.stream().map(TwoMoveChallenge.Puzzle::id).toList(),
                curated.stream().map(TwoMoveChallenge.Puzzle::id).toList());
        for (int i = 0; i < original.size(); i++) {
            var before = original.get(i);
            var after = curated.get(i);
            assertArrayEquals(before.id(), before.initial().board(), after.initial().board());
            assertEquals(before.id(), before.initial().rack(), after.initial().rack());
        }
    }

    @Test public void bonusRotationHasNineDistinctPlayablePuzzlesInEachLanguage() {
        for (Lexicon dictionary : List.of(french, english)) {
            var circuits = TwoMoveChallenge.bonusCircuits(dictionary);
            assertEquals(List.of("two-moves-bonus-v1", "two-moves-bonus-2", "two-moves-bonus-3"),
                    circuits.stream().map(TwoMoveChallenge.BonusSet::id).toList());
            assertTrue(circuits.stream().allMatch(set -> set.puzzles().size() == BonusEconomy.ROUND_COUNT));
            var bonus = circuits.stream().flatMap(set -> set.puzzles().stream()).toList();
            Set<String> baseIds = new HashSet<>();
            for (var puzzle : TwoMoveChallenge.catalog(dictionary)) baseIds.add(puzzle.id());
            for (var puzzle : bonus) assertFalse(baseIds.contains(puzzle.id()));
            verifyCatalog(bonus, dictionary, dictionary.language == GameLanguage.ENGLISH
                    ? "Both moves have already been played." : "Les deux coups sont déjà joués.", 9);
        }
    }

    @Test public void detectsAValidFirstMoveThatLeavesNoLegalSecondMoveInTheCircuit() {
        var puzzle = TwoMoveChallenge.bonusCircuits(french).get(0).puzzles().get(2);
        var deadEnd = TwoMoveChallenge.play(puzzle.initial(), List.of(
                new FrenchGame.Tile(cell('F', 7), 0, 'E', false),
                new FrenchGame.Tile(cell('G', 7), 4, 'G', false),
                new FrenchGame.Tile(cell('H', 7), 5, 'A', false),
                new FrenchGame.Tile(cell('I', 7), 6, 'L', false),
                new FrenchGame.Tile(cell('J', 7), 3, 'E', false),
                new FrenchGame.Tile(cell('K', 7), 1, 'R', false)), french);
        assertTrue(deadEnd.preview().valid());
        assertEquals("EGALER", deadEnd.preview().word());
        assertEquals(List.of('I'), deadEnd.after().rack());
        assertFalse(TwoMoveChallenge.hasSecondMove(deadEnd.after(), french));
        for (Lexicon dictionary : List.of(french, english)) {
            for (var set : TwoMoveChallenge.bonusCircuits(dictionary)) {
                for (var authored : set.puzzles()) {
                    assertTrue(TwoMoveChallenge.hasSecondMove(authored.solution().get(0).after(), dictionary));
                }
            }
        }
    }

    @Test public void validFirstMoveCanLeaveNoSecondMoveInTheFrenchCatalog() throws Exception {
        Lexicon classic = french.filtered("Classique", "filtered",
                Files.readAllLines(Path.of("../../data/fr/classic-exclusions.txt")));
        var puzzle = TwoMoveChallenge.catalog(classic).stream()
                .filter(candidate -> candidate.id().equals("ponts")).findFirst().orElseThrow();
        var deadEnd = TwoMoveChallenge.play(puzzle.initial(), List.of(
                new FrenchGame.Tile(cell('K', 5), 0, 'S', false),
                new FrenchGame.Tile(cell('K', 6), 2, 'U', false),
                new FrenchGame.Tile(cell('K', 7), 5, 'R', false),
                new FrenchGame.Tile(cell('K', 9), 3, 'E', false),
                new FrenchGame.Tile(cell('K', 10), 6, 'N', false),
                new FrenchGame.Tile(cell('K', 11), 4, 'E', false)), classic);

        assertTrue(deadEnd.preview().valid());
        assertEquals("SURMENE", deadEnd.preview().word());
        assertEquals(List.of('F'), deadEnd.after().rack());
        assertFalse(TwoMoveChallenge.hasSecondMove(deadEnd.after(), classic));
        assertTrue(TwoMoveChallenge.hasSecondMove(puzzle.solution().get(0).after(), classic));
    }

    private static void verifyCatalog(List<TwoMoveChallenge.Puzzle> catalog, Lexicon lexicon, String finishedError) {
        verifyCatalog(catalog, lexicon, finishedError, 12);
    }

    private static void verifyCatalog(List<TwoMoveChallenge.Puzzle> catalog, Lexicon lexicon,
                                      String finishedError, int size) {
        assertEquals(size, catalog.size());
        Set<String> ids = new HashSet<>();
        Set<List<Character>> racks = new HashSet<>();
        for (var puzzle : catalog) {
            String id = puzzle.id();
            assertTrue(id, ids.add(id));
            assertTrue(id, racks.add(puzzle.initial().rack()));
            assertEquals(id, 0, puzzle.initial().moves());
            assertEquals(id, FrenchGame.RACK_SIZE, puzzle.initial().rack().size());
            assertEquals(id, 2, puzzle.solution().size());
            assertTrue(id, puzzle.solution().get(0).tiles().stream()
                    .anyMatch(tile -> tile.position() == puzzle.hintPosition()));
            var position = puzzle.initial();
            int total = 0;
            for (var authored : puzzle.solution()) {
                var played = TwoMoveChallenge.play(position, authored.tiles(), lexicon);
                assertTrue(id, played.preview().valid());
                assertEquals(id, authored.preview(), played.preview());
                assertTrue(id, played.preview().score() > 0);
                assertArrayEquals(id, authored.after().board(), played.after().board());
                assertArrayEquals(id, authored.after().blanks(), played.after().blanks());
                var remaining = new ArrayList<>(position.rack());
                for (var tile : played.tiles()) {
                    char used = tile.blank() ? FrenchGame.JOKER : tile.letter();
                    assertTrue(id, remaining.remove(Character.valueOf(used)));
                    assertEquals(id, 0, position.board()[tile.position()]);
                    assertEquals(id, tile.letter(), played.after().board()[tile.position()]);
                    assertEquals(id, tile.blank(), played.after().blanks()[tile.position()]);
                }
                total += played.preview().score();
                assertEquals(id, position.moves() + 1, played.after().moves());
                assertEquals(id, total, played.after().totalScore());
                assertEquals(id, remaining, played.after().rack());
                position = played.after();
            }
            var third = TwoMoveChallenge.play(position, List.of(), lexicon);
            assertEquals(id, finishedError, third.preview().error());
            assertSame(id, position, third.after());
        }
    }

    @Test public void playerCanTryAlternateFirstMoveAndRetryFromAnUnchangedPosition() {
        var puzzle = puzzles.get(0);
        var start = puzzle.initial();
        var alternate = new FrenchGame.Tile(cell('G', 8), 4, 'T', false);
        var first = TwoMoveChallenge.play(start, List.of(alternate), french);
        assertEquals("TCHAT", first.preview().word());
        assertEquals(6, first.after().rack().size());

        var occupied = new FrenchGame.Tile(cell('G', 8), 0, 'S', false);
        var invalid = TwoMoveChallenge.play(first.after(), List.of(occupied), french);
        assertFalse(invalid.preview().valid());
        assertSame(first.after(), invalid.after());

        char[] detached = start.board();
        detached[cell('G', 8)] = 'Z';
        assertEquals(0, start.board()[cell('G', 8)]);
        assertEquals(7, start.rack().size());
        assertEquals(0, start.totalScore());
        var retry = TwoMoveChallenge.play(start, puzzle.solution().get(0).tiles(), french);
        assertEquals("CHATS", retry.preview().word());
        assertEquals(11, retry.preview().score());
        assertEquals(21, puzzle.solution().get(1).after().totalScore());
    }

    @Test public void firstMoveCannotConsumeTheWholeRack() {
        var start = puzzles.get(0).initial();
        List<FrenchGame.Tile> claustre = List.of(
                new FrenchGame.Tile(cell('H', 9), 2, 'L', false),
                new FrenchGame.Tile(cell('H', 10), 1, 'A', false),
                new FrenchGame.Tile(cell('H', 11), 3, 'U', false),
                new FrenchGame.Tile(cell('H', 12), 0, 'S', false),
                new FrenchGame.Tile(cell('H', 13), 4, 'T', false),
                new FrenchGame.Tile(cell('H', 14), 6, 'R', false),
                new FrenchGame.Tile(cell('H', 15), 5, 'E', false));
        assertEquals("CLAUSTRE", FrenchGame.preview(start.board(), start.blanks(), start.rack(), french, claustre).word());
        var rejected = TwoMoveChallenge.play(start, claustre, french);
        assertEquals("Garde au moins une lettre pour le deuxième coup.", rejected.preview().error());
        assertSame(start, rejected.after());
    }

    @Test public void jokerStaysOnTheBoardAndKeepsItsZeroValueForTheSecondMove() {
        var puzzle = puzzles.stream().filter(p -> p.id().equals("nids")).findFirst().orElseThrow();
        var first = puzzle.solution().get(0);
        var second = puzzle.solution().get(1);
        int square = cell('K', 8);
        assertTrue(first.tiles().get(0).blank());
        assertEquals('S', first.after().board()[square]);
        assertTrue(first.after().blanks()[square]);
        assertFalse(first.after().rack().contains(FrenchGame.JOKER));
        assertTrue(first.after().rack().contains('S'));
        assertTrue(second.after().blanks()[square]);
        assertEquals("TASSE", second.preview().word());
        assertEquals(4, second.preview().score());
    }

    @Test public void customLexiconOnlyExposesCompatiblePuzzles() throws Exception {
        var supported = dictionary("CHAT", "CHATS", "SALUT");
        var unsupported = dictionary("CHAT", "CHATS");
        assertEquals(List.of("chats"), TwoMoveChallenge.catalog(supported).stream()
                .map(TwoMoveChallenge.Puzzle::id).toList());
        assertTrue(TwoMoveChallenge.catalog(unsupported).isEmpty());
    }

    private static int cell(char column, int row) {
        return (row - 1) * FrenchGame.SIZE + column - 'A';
    }

    private static Lexicon dictionary(String... words) throws Exception {
        return Lexicon.read(new BufferedReader(new StringReader(String.join("\n", words))),
                "Test", "", "", words.length);
    }
}
