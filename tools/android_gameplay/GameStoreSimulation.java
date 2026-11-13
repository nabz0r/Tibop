package fr.lettrine.game;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

public final class GameStoreSimulation {
    private static final int GAMES = 48;

    public static void main(String[] args) throws Exception {
        File root = new File(args[0], "storage");
        Files.createDirectories(root.toPath());
        File legacyFile = new File(root, "current-match.json");
        String legacy = " { \"id\" : \"legacy\", \"turn\" : 1 } \n";
        Files.write(legacyFile.toPath(), legacy.getBytes(StandardCharsets.UTF_8));

        GameStore[] stores = new GameStore[4];
        for (int i = 0; i < stores.length; i++) stores[i] = new GameStore(root);
        check(stores[0].loadGame("legacy").getInt("turn") == 1, "Legacy match missing");
        stores[0].saveGame(row("left-open", 3));
        for (int i = 0; i < GAMES; i++) stores[0].saveGame(row("game-" + i, i + 2));
        List<JSONObject> snapshot = stores[0].history();

        var pool = Executors.newFixedThreadPool(stores.length);
        var gate = new CountDownLatch(1);
        var calls = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (int i = 0; i < stores.length; i++) {
                int worker = i;
                calls.add(pool.submit(() -> {
                    gate.await();
                    for (int game = worker; game < GAMES; game += stores.length) {
                        String id = "game-" + game;
                        int opponent = 800 + game * 11;
                        stores[worker].archive(row(id, game + 2), opponent, game % 3 - 1);
                        stores[worker].archive(row(id, game + 9), opponent, 1);
                        stores[worker].saveGame(row(id, game + 99));
                        check(stores[worker].loadGame(id) == null, "Archived game reappeared " + id);
                    }
                    return null;
                }));
            }
            gate.countDown();
            for (var task : calls) task.get();
        } finally {
            pool.shutdownNow();
        }
        check(snapshot.isEmpty(), "Previously displayed history should remain a stable snapshot");

        GameStore restarted = new GameStore(root);
        List<JSONObject> archived = restarted.history();
        check(archived.size() == GAMES, "Missing archived games");
        int rating = GameStore.BASE_ELO;
        for (JSONObject game : archived) {
            int number = Integer.parseInt(game.getString("id").substring("game-".length()));
            int opponent = 800 + number * 11;
            check(game.getInt("turn") == number + 2, "Duplicate completion changed the archive");
            check(game.getInt("elo_before") == rating, "Elo chain split");
            check(game.getInt("opponent_reference_elo") == opponent, "Missing opponent reference");
            rating = GameStore.adjustedRating(rating, opponent, number % 3 - 1);
            check(game.getInt("elo_after") == rating, "Elo result diverged");
        }
        for (GameStore store : stores) {
            check(store.history().size() == GAMES && store.elo() == rating, "Stale history after archive");
        }
        check(restarted.savedGames().size() == 2, "Pending matches lost or archived slots resurrected");
        check(restarted.loadGame("left-open").getInt("turn") == 3, "Unrelated slot changed");
        check(legacy.equals(new String(Files.readAllBytes(legacyFile.toPath()), StandardCharsets.UTF_8)),
                "Legacy save changed");
        restarted.removeGame("legacy");
        check(new GameStore(root).loadGame("legacy") == null, "Migrated match reappeared after removal");
        System.out.println("PASS " + GAMES + " concurrent archives, linear Elo, stable pending slots and legacy migration");
    }

    private static JSONObject row(String id, int turn) throws Exception {
        return new JSONObject().put("id", id).put("turn", turn);
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
