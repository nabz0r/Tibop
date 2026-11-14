package fr.lettrine.game;

import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class NabCampaignTest {
    private static Lexicon french;
    private static Lexicon english;

    @BeforeClass public static void loadDictionaries() throws Exception {
        Path data = Path.of("../../data");
        try (var reader = Files.newBufferedReader(data.resolve("fr/words.txt"))) {
            french = Lexicon.read(reader, "Grammalecte", "", "", 406312).filtered("Classique", "classic",
                    Files.readAllLines(data.resolve("fr/classic-exclusions.txt")));
        }
        try (var reader = Files.newBufferedReader(data.resolve("en/words.txt"))) {
            english = Lexicon.read(reader, "ENABLE2K", "", "", 169266, GameLanguage.ENGLISH);
        }
    }

    @Test public void allTwentyFourStagesAndSixNabChallengesReplayAgainstBundledDictionaries() {
        for (Lexicon dictionary : List.of(french, english)) {
            var stages = NabCampaign.catalog(dictionary);
            assertEquals(NabCampaign.STAGE_COUNT, stages.size());
            for (int number = 1; number <= stages.size(); number++) {
                var stage = stages.get(number - 1);
                assertEquals(number, stage.number());
                assertEquals((number - 1) / NabCampaign.CHAPTER_SIZE + 1, stage.chapter());
                assertEquals(number % NabCampaign.CHAPTER_SIZE == 0, stage.rival());
                assertEquals(number <= 8 ? 1 : 2, stage.turns());

                var won = replay(stage, stage.solution(), dictionary);
                assertTrue(stage.title(), NabCampaign.qualifies(stage, won));
                assertEquals(3, NabCampaign.stars(stage, won.after()));
                var pastLimit = NabCampaign.play(stage, won.after(), List.of(), dictionary);
                assertFalse(pastLimit.preview().valid());
                assertSame(won.after(), pastLimit.after());
                if (stage.rival()) {
                    var tied = replay(stage, stage.rivalMoves(), dictionary);
                    assertEquals(stage.rivalScore(), tied.after().totalScore());
                    assertTrue(stage.rivalScore() < won.after().totalScore());
                    assertFalse(NabCampaign.qualifies(stage, tied));
                    assertEquals(0, NabCampaign.stars(stage, tied.after()));
                }
            }
        }
    }

    @Test public void anOtherwiseValidMoveDoesNotReplaceTheObjective() {
        var stage = NabCampaign.catalog(french).get(5);
        var blank = stage.solution().get(0).tiles().get(0);
        int naturalS = stage.initial().rack().indexOf('S');
        var actualLetter = new FrenchGame.Tile(blank.position(), naturalS, blank.letter(), false);
        var missedGoal = NabCampaign.play(stage, stage.initial(), List.of(actualLetter), french);
        assertEquals("NIDS", missedGoal.preview().word());
        assertTrue(missedGoal.preview().valid());
        assertFalse(NabCampaign.qualifies(stage, missedGoal));
        assertTrue(NabCampaign.qualifies(stage, replay(stage, stage.solution(), french)));
    }

    @Test public void campaignOpensOnlyNextStageAndEvolvesAtChapterBoundaries() {
        Map<String, Integer> values = new HashMap<>();
        for (int completed = 0; completed <= NabCampaign.STAGE_COUNT; completed++) {
            var progress = NabCampaignProgress.summarize(values);
            assertEquals(completed, progress.completed());
            assertEquals(completed * 2, progress.totalStars());
            assertEquals(completed / NabCampaign.CHAPTER_SIZE, progress.workshopLevel());
            assertEquals(completed == NabCampaign.STAGE_COUNT ? 0 : completed + 1, progress.nextStage());
            assertFalse(progress.unlocked(0));
            assertFalse(progress.unlocked(completed + 2));
            if (completed == NabCampaign.STAGE_COUNT) continue;
            int next = completed + 1;
            assertTrue(progress.unlocked(next));
            assertEquals(0, progress.stars(next));
            assertEquals(0, progress.bestScore(next));
            values.put(NabCampaignProgress.key("stars", next), 2);
            values.put(NabCampaignProgress.key("score", next), 20 + next);
            var updated = NabCampaignProgress.summarize(values);
            assertEquals(2, updated.stars(next));
            assertEquals(20 + next, updated.bestScore(next));
        }
    }

    @Test public void malformedOrNonSequentialSavedResultsDoNotUnlockRooms() {
        var incomplete = NabCampaignProgress.summarize(Map.of(
                "stars/1", 2, "score/1", 10, "stars/2", 4, "score/2", 14,
                "stars/3", 3, "score/3", 17, "stars/4", 3, "score/4", 20));
        assertEquals(1, incomplete.completed());
        assertEquals(2, incomplete.nextStage());
        assertEquals(0, incomplete.stars(3));
        assertEquals(0, incomplete.workshopLevel());
    }

    private static TwoMoveChallenge.Step replay(NabCampaign.Stage stage, List<TwoMoveChallenge.Step> script,
                                                Lexicon dictionary) {
        var position = stage.initial();
        TwoMoveChallenge.Step played = null;
        for (var expected : script) {
            played = NabCampaign.play(stage, position, expected.tiles(), dictionary);
            assertEquals(stage.title(), expected.preview(), played.preview());
            assertTrue(played.preview().valid());
            assertArrayEquals(expected.after().board(), played.after().board());
            assertEquals(expected.after().rack(), played.after().rack());
            position = played.after();
        }
        return played;
    }
}
