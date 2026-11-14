package fr.lettrine.game;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ProgressBackupTest {
    @Test public void acceptsRuleMetadataAlongsideExistingDraftAndScoreKeys() {
        assertEquals(Integer.class, ProgressBackup.type("daily_progress", "20770"));
        assertEquals(String.class, ProgressBackup.type("daily_progress", "lexicon/20770"));
        assertEquals(String.class, ProgressBackup.type("daily_draft", "2026-11-13"));
        assertEquals(String.class, ProgressBackup.type("daily_draft", "lexicon/2026-11-13"));
        assertEquals(String.class, ProgressBackup.type("two_move_draft", "lexicon/chats"));
        assertEquals(String.class, ProgressBackup.type("daily_draft-en", "lexicon/2026-11-13"));
    }

    @Test public void rejectsUnsupportedOrAmbiguousRuleMetadataKeys() {
        assertNull(ProgressBackup.type("daily_draft", "lexicon/2026-02-29"));
        assertNull(ProgressBackup.type("daily_progress", "lexicon/+20770"));
        assertNull(ProgressBackup.type("two_move_draft", "lexicon/lexicon/chats"));
        assertNull(ProgressBackup.type("two_move_challenges", "lexicon/chats"));
        assertNull(ProgressBackup.type("preferences", "lexicon/app_language"));
    }

    @Test public void includesIndependentRackSelectionAlongsideExistingSkins() {
        assertEquals(String.class, ProgressBackup.type("skin_collection", "board"));
        assertEquals(String.class, ProgressBackup.type("skin_collection", "tiles"));
        assertEquals(String.class, ProgressBackup.type("skin_collection", "rack"));
    }

    @Test public void limitsWorkshopProgressAndDraftKeysToTheTwelveLevels() {
        assertEquals(Integer.class, ProgressBackup.type("nab_campaign_v1", "stars/1"));
        assertEquals(Integer.class, ProgressBackup.type("nab_campaign_v1-en", "score/12"));
        assertEquals(String.class, ProgressBackup.type("nab_campaign_v1", "draft/4"));
        assertEquals(String.class, ProgressBackup.type("nab_campaign_v1-en", "lexicon/8"));
        assertNull(ProgressBackup.type("nab_campaign_v1", "stars/0"));
        assertNull(ProgressBackup.type("nab_campaign_v1-en", "draft/13"));
        assertNull(ProgressBackup.type("nab_campaign_v1", "score/01"));
        assertNull(ProgressBackup.type("nab_campaign_v1", "lexicon/1/2"));
    }
}
