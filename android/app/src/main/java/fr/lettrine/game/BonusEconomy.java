package fr.lettrine.game;

import android.content.Context;
import android.util.AtomicFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class BonusEconomy {
    static final int FREE_CAP = 3;
    static final int EXCHANGE_COST = 100;
    static final int ROUND_COUNT = 3;
    static final long REFILL_MILLIS = TimeUnit.HOURS.toMillis(2);

    private static final int LEGACY_FILE_VERSION = 1;
    private static final int RECORDS_FILE_VERSION = 2;
    private static final int FILE_VERSION = 3;
    private static final int MAX_DRAFT_LENGTH = 16_384;
    private static final int MAX_ENTRIES = 100_000;
    private static final int MAX_ROUND_SCORE = 9_999;
    private static final int NO_BEST_SCORE = -1;
    static final Object LOCK = new Object();
    private static final Wallet UNAVAILABLE = new Wallet(0, 0, 0, false, 0);

    record Wallet(int coins, int freeTickets, int bonusTickets, boolean freeEntry, long nextRefillAtMillis) {
        boolean canStart() { return freeEntry || freeTickets > 0 || bonusTickets > 0; }
    }

    record Circuit(String id, String attemptId, List<Integer> scores, String lexiconHash) {
        Circuit {
            scores = List.copyOf(scores);
        }

        int round() { return scores.size(); }
        boolean complete() { return round() == ROUND_COUNT; }
        int totalScore() { return scores.stream().mapToInt(Integer::intValue).sum(); }
    }

    interface Store {
        byte[] read() throws IOException;
        void write(byte[] data) throws IOException;
    }

    private record Run(Circuit circuit, String draft) {
        Run withDraft(String draft) { return new Run(circuit, draft); }
    }

    private static final class State {
        int coins;
        int freeTickets = FREE_CAP;
        int bonusTickets;
        boolean freeEntry = true;
        long refillAt;
        long videoDay = Long.MIN_VALUE;
        final Map<GameLanguage, Run> runs = new EnumMap<>(GameLanguage.class);
        final Map<GameLanguage, Integer> bestScores = new EnumMap<>(GameLanguage.class);
        final Map<String, Integer> referrals = new HashMap<>();
        final Set<String> awards = new HashSet<>();

        void refill(long now) {
            if (refillAt == 0 || now < refillAt) return;
            long gained = 1 + (now - refillAt) / REFILL_MILLIS;
            if (gained >= FREE_CAP - freeTickets) {
                freeTickets = FREE_CAP;
                refillAt = 0;
            } else {
                freeTickets += (int) gained;
                refillAt += gained * REFILL_MILLIS;
            }
        }

        Wallet wallet() { return new Wallet(coins, freeTickets, bonusTickets, freeEntry, refillAt); }
    }

    private final Store store;

    BonusEconomy(Context context) {
        this(new FileStore(new File(context.getFilesDir(), "bonus-economy-v1.bin")));
    }

    BonusEconomy(Store store) { this.store = store; }

    static void validateBackup(byte[] bytes) throws IOException {
        if (bytes == null || decode(bytes) == null) throw new IOException("Invalid economy data");
    }

    Wallet wallet(long nowMillis) {
        synchronized (LOCK) {
            State state = load();
            if (state == null) return UNAVAILABLE;
            state.refill(nowMillis);
            return state.wallet();
        }
    }

    Circuit active(GameLanguage language) {
        synchronized (LOCK) {
            State state = load();
            Run run = state == null ? null : state.runs.get(language);
            return run == null ? null : run.circuit;
        }
    }

    Integer bestScore(GameLanguage language) {
        synchronized (LOCK) {
            State state = load();
            return state == null ? null : state.bestScores.get(language);
        }
    }

    Circuit start(GameLanguage language, List<String> circuitIds, long nowMillis) {
        return start(language, circuitIds, nowMillis, "");
    }

    Circuit start(GameLanguage language, List<String> circuitIds, long nowMillis, String lexiconHash) {
        if (circuitIds.isEmpty() || !circuitIds.stream().allMatch(BonusEconomy::validKey)
                || !validLexiconHash(lexiconHash)
                || nowMillis < 0 || nowMillis > Long.MAX_VALUE - REFILL_MILLIS) return null;
        synchronized (LOCK) {
            State state = load();
            if (state == null) return null;
            Run existing = state.runs.get(language);
            if (existing != null && !existing.circuit.complete()) return existing.circuit;
            int previous = existing == null ? -1 : circuitIds.indexOf(existing.circuit.id());
            String circuitId = circuitIds.get((previous + 1) % circuitIds.size());
            state.refill(nowMillis);
            if (state.freeEntry) state.freeEntry = false;
            else if (state.freeTickets > 0) {
                if (state.freeTickets == FREE_CAP) state.refillAt = nowMillis + REFILL_MILLIS;
                state.freeTickets--;
            } else if (state.bonusTickets > 0) state.bonusTickets--;
            else return null;
            Circuit circuit = new Circuit(circuitId, UUID.randomUUID().toString(), List.of(), lexiconHash);
            state.runs.put(language, new Run(circuit, ""));
            return save(state) ? circuit : null;
        }
    }

    Circuit completeRound(GameLanguage language, String circuitId, String attemptId, int round, int score) {
        if (round < 0 || round >= ROUND_COUNT || score < 0 || score > MAX_ROUND_SCORE) return null;
        synchronized (LOCK) {
            State state = load();
            Run run = matchingRun(state, language, circuitId, attemptId);
            if (run == null || round > run.circuit.round()) return null;
            if (round < run.circuit.round()) return run.circuit;
            var scores = new ArrayList<>(run.circuit.scores());
            scores.add(score);
            Circuit circuit = new Circuit(circuitId, attemptId, scores, run.circuit.lexiconHash());
            state.runs.put(language, new Run(circuit, ""));
            if (circuit.complete()) state.bestScores.merge(language, circuit.totalScore(), Math::max);
            return save(state) ? circuit : null;
        }
    }

    String draft(GameLanguage language, String circuitId, String attemptId, int round) {
        synchronized (LOCK) {
            Run run = matchingRun(load(), language, circuitId, attemptId);
            return run == null || run.circuit.complete() || run.circuit.round() != round ? "" : run.draft;
        }
    }

    boolean saveDraft(GameLanguage language, String circuitId, String attemptId, int round, String serialized) {
        if (serialized == null || serialized.length() > MAX_DRAFT_LENGTH) return false;
        synchronized (LOCK) {
            State state = load();
            Run run = matchingRun(state, language, circuitId, attemptId);
            if (run == null || run.circuit.complete() || run.circuit.round() != round) return false;
            if (run.draft.equals(serialized)) return true;
            state.runs.put(language, run.withDraft(serialized));
            return save(state);
        }
    }

    private static Run matchingRun(State state, GameLanguage language, String circuitId, String attemptId) {
        Run run = state == null ? null : state.runs.get(language);
        return run != null && run.circuit.id().equals(circuitId) && run.circuit.attemptId().equals(attemptId)
                ? run : null;
    }

    boolean daily(GameLanguage language, LocalDate date) { return award("daily", language, date.toString(), 25); }
    boolean puzzle(GameLanguage language, String id) { return award("puzzle", language, id, 50); }
    boolean tournament(GameLanguage language, String id) { return award("tournament", language, id, 75); }

    private boolean award(String source, GameLanguage language, String id, int coins) {
        if (!validKey(id)) return false;
        String key = source + '\0' + language.code + '\0' + id;
        synchronized (LOCK) {
            State state = load();
            if (state == null || state.awards.size() == MAX_ENTRIES || !state.awards.add(key)) return false;
            state.coins = add(state.coins, coins);
            return save(state);
        }
    }

    boolean exchange() {
        synchronized (LOCK) {
            State state = load();
            if (state == null || state.coins < EXCHANGE_COST || state.bonusTickets == Integer.MAX_VALUE) return false;
            state.coins -= EXCHANGE_COST;
            state.bonusTickets++;
            return save(state);
        }
    }

    static int themeCost(String id) {
        if ("indigo".equals(id)) return 250;
        if ("poppy".equals(id)) return 500;
        return -1;
    }

    boolean themeUnlocked(String id) {
        if (themeCost(id) < 0) return false;
        synchronized (LOCK) {
            State state = load();
            return state != null && state.awards.contains("theme\0" + id);
        }
    }

    boolean unlockTheme(String id) {
        int cost = themeCost(id);
        if (cost < 0) return false;
        synchronized (LOCK) {
            State state = load();
            if (state == null) return false;
            String key = "theme\0" + id;
            if (state.awards.contains(key)) return true;
            if (state.coins < cost || state.awards.size() == MAX_ENTRIES) return false;
            state.coins -= cost;
            state.awards.add(key);
            return save(state);
        }
    }

    boolean videoAvailable(LocalDate date) {
        synchronized (LOCK) {
            State state = load();
            return state != null && date.toEpochDay() > state.videoDay && state.bonusTickets < Integer.MAX_VALUE;
        }
    }

    boolean video(LocalDate date) {
        synchronized (LOCK) {
            State state = load();
            if (state == null || date.toEpochDay() <= state.videoDay || state.bonusTickets == Integer.MAX_VALUE) return false;
            state.videoDay = date.toEpochDay();
            state.bonusTickets++;
            return save(state);
        }
    }

    boolean referrals(GameLanguage language, String serverScope, String accountId, int cumulative) {
        if (!validKey(serverScope) || !validKey(accountId) || cumulative <= 0) return false;
        String key = language.code + '\0' + serverScope + '\0' + accountId;
        synchronized (LOCK) {
            State state = load();
            if (state == null) return false;
            int previous = state.referrals.getOrDefault(key, 0);
            if (cumulative <= previous || (previous == 0 && state.referrals.size() == MAX_ENTRIES)) return false;
            state.bonusTickets = add(state.bonusTickets, cumulative - previous);
            state.referrals.put(key, cumulative);
            return save(state);
        }
    }

    private static boolean validKey(String value) {
        return value != null && !value.isBlank() && value.length() <= 256 && value.indexOf('\0') < 0;
    }

    private static boolean validLexiconHash(String value) {
        return value != null && (value.isEmpty() || value.matches("[0-9a-f]{64}"));
    }

    private static int add(int left, int right) {
        return (int) Math.min((long) left + right, Integer.MAX_VALUE);
    }

    private State load() {
        try {
            byte[] bytes = store.read();
            return bytes == null ? new State() : decode(bytes);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static State decode(byte[] bytes) {
        try {
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                int version = input.readInt();
                if (version < LEGACY_FILE_VERSION || version > FILE_VERSION) return null;
                State state = new State();
                state.coins = input.readInt();
                state.freeTickets = input.readInt();
                state.bonusTickets = input.readInt();
                state.freeEntry = input.readBoolean();
                state.refillAt = input.readLong();
                state.videoDay = input.readLong();
                if (state.coins < 0 || state.bonusTickets < 0 || state.freeTickets < 0 || state.freeTickets > FREE_CAP
                        || state.refillAt < 0 || (state.refillAt == 0) != (state.freeTickets == FREE_CAP)) return null;
                int awards = readCount(input, MAX_ENTRIES);
                for (int i = 0; i < awards; i++) state.awards.add(input.readUTF());
                int referrals = readCount(input, MAX_ENTRIES);
                for (int i = 0; i < referrals; i++) {
                    String key = input.readUTF();
                    int count = input.readInt();
                    if (count <= 0) return null;
                    state.referrals.put(key, count);
                }
                for (GameLanguage language : GameLanguage.values()) {
                    if (!input.readBoolean()) continue;
                    String id = input.readUTF();
                    String attempt = input.readUTF();
                    int completedRounds = readCount(input, ROUND_COUNT);
                    List<Integer> scores = new ArrayList<>(completedRounds);
                    for (int i = 0; i < completedRounds; i++) {
                        int score = input.readInt();
                        if (score < 0 || score > MAX_ROUND_SCORE) return null;
                        scores.add(score);
                    }
                    String draft = input.readUTF();
                    String lexiconHash = version == FILE_VERSION ? input.readUTF() : "";
                    if (!validKey(id) || !validKey(attempt) || draft.length() > MAX_DRAFT_LENGTH
                            || !validLexiconHash(lexiconHash)) return null;
                    Circuit circuit = new Circuit(id, attempt, scores, lexiconHash);
                    state.runs.put(language, new Run(circuit, draft));
                    if (version == LEGACY_FILE_VERSION && circuit.complete()) {
                        state.bestScores.put(language, circuit.totalScore());
                    }
                }
                if (version >= RECORDS_FILE_VERSION) {
                    for (GameLanguage language : GameLanguage.values()) {
                        int best = input.readInt();
                        if (best < NO_BEST_SCORE || best > ROUND_COUNT * MAX_ROUND_SCORE) return null;
                        if (best != NO_BEST_SCORE) state.bestScores.put(language, best);
                    }
                }
                return input.read() == -1 ? state : null;
            }
        } catch (IOException ignored) {
            return null;
        }
    }

    private static int readCount(DataInputStream input, int maximum) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) throw new IOException("Invalid economy data");
        return count;
    }

    private boolean save(State state) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(FILE_VERSION);
                output.writeInt(state.coins);
                output.writeInt(state.freeTickets);
                output.writeInt(state.bonusTickets);
                output.writeBoolean(state.freeEntry);
                output.writeLong(state.refillAt);
                output.writeLong(state.videoDay);
                output.writeInt(state.awards.size());
                for (String award : state.awards) output.writeUTF(award);
                output.writeInt(state.referrals.size());
                for (var referral : state.referrals.entrySet()) {
                    output.writeUTF(referral.getKey());
                    output.writeInt(referral.getValue());
                }
                for (GameLanguage language : GameLanguage.values()) {
                    Run run = state.runs.get(language);
                    output.writeBoolean(run != null);
                    if (run == null) continue;
                    output.writeUTF(run.circuit.id());
                    output.writeUTF(run.circuit.attemptId());
                    output.writeInt(run.circuit.round());
                    for (int score : run.circuit.scores()) output.writeInt(score);
                    output.writeUTF(run.draft);
                    output.writeUTF(run.circuit.lexiconHash());
                }
                for (GameLanguage language : GameLanguage.values()) {
                    output.writeInt(state.bestScores.getOrDefault(language, NO_BEST_SCORE));
                }
            }
            store.write(bytes.toByteArray());
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static final class FileStore implements Store {
        private final AtomicFile file;

        FileStore(File file) { this.file = new AtomicFile(file); }

        @Override public byte[] read() throws IOException {
            try {
                return file.readFully();
            } catch (FileNotFoundException missing) {
                if (file.getBaseFile().exists()) throw missing;
                return null;
            }
        }

        @Override public void write(byte[] data) throws IOException {
            FileOutputStream stream = file.startWrite();
            try {
                stream.write(data);
                stream.getFD().sync();
                file.finishWrite(stream);
            } catch (IOException exception) {
                file.failWrite(stream);
                throw exception;
            }
        }
    }
}
