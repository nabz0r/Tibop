package fr.lettrine.game;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ProfileStoreTest {
    @Test public void emptyFieldsAndAccentedNamesAreAccepted() {
        ProfileStore.Profile guest = profile("", "", 0);
        assertEquals("", guest.nickname());
        assertEquals("", guest.country());
        assertEquals(0, guest.age());
        assertEquals("Élise 🐱", profile("  Élise 🐱 ", "lu", 32).nickname());
        assertEquals("LU", profile("Léo", "lu", 32).country());
    }

    @Test public void invalidAgeCountryAndNicknameAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> profile("Léo", "LU", -1));
        assertThrows(IllegalArgumentException.class, () -> profile("Léo", "LU", 131));
        assertThrows(IllegalArgumentException.class, () -> profile("Léo", "XX", 19));
        assertThrows(IllegalArgumentException.class, () -> profile("Léo\nNAB", "LU", 19));
        assertThrows(IllegalArgumentException.class, () -> profile("🧩".repeat(25), "", 0));
        assertEquals("🧩".repeat(24), profile("🧩".repeat(24), "", 0).nickname());
    }

    private static ProfileStore.Profile profile(String nickname, String country, int age) {
        return new ProfileStore.Profile(nickname, country, age, ProfileStore.Sex.UNSPECIFIED);
    }
}
