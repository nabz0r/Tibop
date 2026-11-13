package fr.lettrine.game;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import java.util.function.BooleanSupplier;

final class RewardedPractice {
    private final Activity activity;
    private final ConsentInformation consent;
    private final SharedPreferences settings;
    private boolean busy;

    RewardedPractice(Activity activity) {
        this.activity = activity;
        consent = UserMessagingPlatform.getConsentInformation(activity);
        settings = activity.getSharedPreferences("advertising", Context.MODE_PRIVATE);
    }

    boolean available() { return true; }

    void refreshConsent(Runnable refreshed) {
        if (!settings.getBoolean("started", false)) return;
        updateConsent(() -> {
            if (!busy && active()) refreshed.run();
        }, () -> {});
    }

    boolean hasPrivacyOptions() {
        return settings.getBoolean("privacy_options", false);
    }

    void privacyOptions(Runnable refreshed) {
        if (consent.getPrivacyOptionsRequirementStatus() == ConsentInformation.PrivacyOptionsRequirementStatus.UNKNOWN) {
            updateConsent(() -> showPrivacyOptions(refreshed), () -> toast(R.string.rewarded_unavailable));
        } else {
            showPrivacyOptions(refreshed);
        }
    }

    private void showPrivacyOptions(Runnable refreshed) {
        if (!active()) return;
        UserMessagingPlatform.showPrivacyOptionsForm(activity, error -> {
            if (error != null) toast(R.string.rewarded_unavailable);
            if (active()) refreshed.run();
        });
    }

    void request(BooleanSupplier interested, Runnable reward, Runnable refreshed) {
        if (busy || !active()) return;
        busy = true;
        toast(R.string.rewarded_loading);
        settings.edit().putBoolean("started", true).apply();
        updateConsent(() -> {
            if (!active() || !interested.getAsBoolean()) {
                complete(refreshed);
                return;
            }
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity, error -> {
                    if (error != null || !consent.canRequestAds()) unavailable(refreshed);
                    else load(interested, reward, refreshed);
                });
        }, () -> unavailable(refreshed));
    }

    private void updateConsent(Runnable updated, Runnable failed) {
        ConsentRequestParameters parameters = new ConsentRequestParameters.Builder().build();
        consent.requestConsentInfoUpdate(activity, parameters, () -> {
            settings.edit().putBoolean("privacy_options", consent.getPrivacyOptionsRequirementStatus()
                    == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED).apply();
            updated.run();
        }, error -> failed.run());
    }

    private void load(BooleanSupplier interested, Runnable reward, Runnable refreshed) {
        if (!active() || !interested.getAsBoolean()) {
            complete(refreshed);
            return;
        }
        MobileAds.initialize(activity, ignored -> activity.runOnUiThread(() -> {
            if (!active() || !interested.getAsBoolean()) {
                complete(refreshed);
                return;
            }
            RewardedAd.load(activity, BuildConfig.ADMOB_REWARDED_ID, new AdRequest.Builder().build(),
                    new RewardedAdLoadCallback() {
                        @Override public void onAdLoaded(@NonNull RewardedAd ad) {
                            show(ad, interested, reward, refreshed);
                        }
                        @Override public void onAdFailedToLoad(@NonNull LoadAdError error) { unavailable(refreshed); }
                    });
        }));
    }

    private void show(RewardedAd ad, BooleanSupplier interested, Runnable reward, Runnable refreshed) {
        if (!active() || !interested.getAsBoolean()) {
            complete(refreshed);
            return;
        }
        if (!activity.hasWindowFocus()) {
            unavailable(refreshed);
            return;
        }
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override public void onAdFailedToShowFullScreenContent(@NonNull AdError error) { unavailable(refreshed); }
            @Override public void onAdDismissedFullScreenContent() { complete(refreshed); }
        });
        ad.show(activity, ignored -> reward.run());
    }

    private void unavailable(Runnable refreshed) {
        toast(R.string.rewarded_unavailable);
        complete(refreshed);
    }

    private void complete(Runnable refreshed) {
        busy = false;
        if (active()) refreshed.run();
    }

    private boolean active() { return !activity.isFinishing() && !activity.isDestroyed(); }

    private void toast(int message) {
        if (active()) Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}
