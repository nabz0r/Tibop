package fr.lettrine.game;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.List;

final class OfflineMoveModel {
    private static final String TAG = "LettrineOfflineModel";
    private static final String WEIGHTS = "smollm2-135m-instruct-q4_0.gguf";
    private static final long WEIGHTS_SIZE = 91_893_088;
    private static final int MODEL_LOAD_FAILED = -2;
    private static final String WEIGHTS_SHA256 =
            "bcc3af2849ad6095af57e9b5cd43775256efdc66e306acb529172f92d0c04b03";

    private final Context context;

    OfflineMoveModel(Context context) {
        this.context = context.getApplicationContext();
    }

    int choose(String facts, List<String> options) throws IOException, InterruptedException {
        if (options.isEmpty() || options.size() > 8) throw new IllegalArgumentException("Invalid model choices");
        checkInterrupted();
        try {
            NativeLibrary.load();
        } catch (LinkageError error) {
            throw new IOException("L’inférence locale est indisponible sur cet appareil.", error);
        }

        String prefix = "<|im_start|>system\nYou are a Scrabble coach. For a beginner, prioritize a rule "
                + "that explains how the recorded score was produced. Reply with Yes or No only.<|im_end|>\n"
                + "<|im_start|>user\nThis move: " + plain(facts) + "\nProposed coaching topic: ";
        String suffix = "\nIs this an important reason for the score?<|im_end|>\n<|im_start|>assistant\n";
        String[] prompts = options.stream().map(option -> prefix + plain(option) + suffix).toArray(String[]::new);

        long start = SystemClock.elapsedRealtime();
        int threads = Runtime.getRuntime().availableProcessors();
        String path = prepareWeights(false).getAbsolutePath();
        checkInterrupted();
        int choice = chooseNative(path, prompts, threads);
        if (choice == MODEL_LOAD_FAILED) {
            checkInterrupted();
            choice = chooseNative(prepareWeights(true).getAbsolutePath(), prompts, threads);
        }
        if (choice < 0) throw new IOException("Le modèle embarqué n’a pas pu être chargé.");
        checkInterrupted();
        Log.i(TAG, "Inférence terminée en " + (SystemClock.elapsedRealtime() - start) + " ms");
        return choice;
    }

    private File prepareWeights(boolean replace) throws IOException, InterruptedException {
        File directory = context.getNoBackupFilesDir();
        File weights = new File(directory, WEIGHTS_SHA256 + ".gguf");
        if (!replace && weights.isFile() && weights.length() == WEIGHTS_SIZE) return weights;

        File temporary = File.createTempFile("lettrine-model-", ".tmp", directory);
        try {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException error) {
                throw new IOException(error);
            }
            try (InputStream asset = context.getAssets().open(WEIGHTS);
                 DigestInputStream input = new DigestInputStream(asset, digest);
                 var output = Files.newOutputStream(temporary.toPath())) {
                byte[] buffer = new byte[128 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkInterrupted();
                    output.write(buffer, 0, count);
                }
            }
            if (temporary.length() != WEIGHTS_SIZE ||
                    !MessageDigest.isEqual(digest.digest(), expectedDigest())) {
                throw new IOException("Le modèle embarqué est illisible.");
            }
            checkInterrupted();
            Files.move(temporary.toPath(), weights.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return weights;
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private static byte[] expectedDigest() {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(WEIGHTS_SHA256.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static String plain(String value) {
        return value.replace('<', '(').replace('>', ')');
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }

    private static native int chooseNative(String path, String[] prompts, int threads) throws IOException;

    private static final class NativeLibrary {
        static { System.loadLibrary("lettrine_offline"); }
        static void load() {}
    }
}
