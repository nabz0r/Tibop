package fr.lettrine.game;

import android.app.Activity;
import java.util.function.BooleanSupplier;

final class RewardedPractice {
    RewardedPractice(Activity activity) {}

    boolean available() { return false; }
    void refreshConsent(Runnable refreshed) {}
    void request(BooleanSupplier interested, Runnable reward, Runnable refreshed) {}
    boolean hasPrivacyOptions() { return false; }
    void privacyOptions(Runnable refreshed) {}
}
