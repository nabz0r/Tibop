package fr.lettrine.game;

import android.app.Activity;
import android.os.CancellationSignal;

import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import java.util.function.Consumer;

final class SocialGoogle {
    private SocialGoogle() {}

    static void request(Activity activity, String clientId, String nonce, CancellationSignal cancellation,
                        Consumer<String> success, Consumer<Integer> failure) {
        GetSignInWithGoogleOption option = new GetSignInWithGoogleOption.Builder(clientId).setNonce(nonce).build();
        GetCredentialRequest request = new GetCredentialRequest.Builder().addCredentialOption(option).build();
        CredentialManager.create(activity).getCredentialAsync(activity, request, cancellation,
                activity::runOnUiThread, new CredentialManagerCallback<>() {
                    @Override public void onResult(GetCredentialResponse response) {
                        Credential credential = response.getCredential();
                        if (credential instanceof CustomCredential custom &&
                                GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(custom.getType())) {
                            try {
                                success.accept(GoogleIdTokenCredential.createFrom(custom.getData()).getIdToken());
                                return;
                            } catch (IllegalArgumentException ignored) { }
                        }
                        failure.accept(R.string.social_google_error);
                    }

                    @Override public void onError(GetCredentialException error) {
                        if (error instanceof GetCredentialCancellationException) failure.accept(0);
                        else if (error instanceof NoCredentialException) failure.accept(R.string.social_google_no_account);
                        else failure.accept(R.string.social_google_error);
                    }
                });
    }
}
