package fr.lettrine.game;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class MatchCodec {
    static final int VERSION = 1;

    record State(String id, String[] names, int[] levels, int[] scores, int active, int bagSize,
                 int seat, char[] board, boolean[] blanks, List<Character> rack,
                 List<FrenchGame.Move> history, String end) {}

    static JSONObject object(Object... fields) {
        JSONObject result = new JSONObject();
        for (int i = 0; i < fields.length; i += 2) put(result, (String) fields[i], fields[i + 1]);
        return result;
    }

    static void put(JSONObject obj, String key, Object value) {
        try {
            obj.put(key, value == null ? JSONObject.NULL : value);
        } catch (JSONException exception) {
            throw new IllegalArgumentException("Valeur non sérialisable.", exception);
        }
    }

    static JSONArray ints(int[] values) {
        JSONArray result = new JSONArray();
        for (int value : values) result.put(value);
        return result;
    }

    private static String rackText(List<Character> rack) {
        StringBuilder result = new StringBuilder(rack.size());
        for (char letter : rack) result.append(letter);
        return result.toString();
    }

    private static ArrayList<Character> readRack(String text) throws JSONException {
        ArrayList<Character> result = new ArrayList<>(text.length());
        if (!text.matches("[A-Z?]*")) throw new JSONException("Chevalet invalide");
        for (char letter : text.toCharArray()) result.add(letter);
        return result;
    }

    static JSONObject tile(FrenchGame.Tile tile, boolean rackIndex) {
        JSONObject encoded = object("row", tile.position() / FrenchGame.SIZE,
                "col", tile.position() % FrenchGame.SIZE, "letter", String.valueOf(tile.letter()),
                "blank", tile.blank());
        if (rackIndex) put(encoded, "rack_index", tile.rackIndex());
        return encoded;
    }

    static JSONArray tiles(List<FrenchGame.Tile> tiles, boolean rackIndex) {
        JSONArray result = new JSONArray();
        for (FrenchGame.Tile tile : tiles) result.put(tile(tile, rackIndex));
        return result;
    }

    static List<FrenchGame.Tile> readTiles(JSONArray list, boolean rackIndex) throws JSONException {
        if (list.length() > FrenchGame.RACK_SIZE) throw new JSONException("Trop de lettres");
        List<FrenchGame.Tile> tiles = new ArrayList<>(list.length());
        for (int i = 0; i < list.length(); i++) {
            JSONObject tile = list.getJSONObject(i);
            int row = tile.getInt("row");
            int col = tile.getInt("col");
            String letter = tile.getString("letter");
            if (row < 0 || row >= FrenchGame.SIZE || col < 0 || col >= FrenchGame.SIZE
                    || !letter.matches("[A-Z]")) throw new JSONException("Case invalide");
            tiles.add(new FrenchGame.Tile(row * FrenchGame.SIZE + col, rackIndex ? tile.getInt("rack_index") : i,
                    letter.charAt(0), tile.getBoolean("blank")));
        }
        return tiles;
    }

    static JSONArray moves(List<FrenchGame.Move> history, boolean privateRacks) {
        JSONArray result = new JSONArray();
        for (FrenchGame.Move move : history) {
            JSONObject row = object("turn", result.length() + 1, "player", move.player(), "type", move.type(),
                    "word", move.word(), "score", move.score(), "tiles", tiles(move.tiles(), false));
            if (move.exchanged() != 0) put(row, "exchange_count", move.exchanged());
            if (privateRacks && move.rack() != null) {
                put(row, "rack", move.rack());
                put(row, "bag_before", move.bagBefore());
            }
            result.put(row);
        }
        return result;
    }

    static List<FrenchGame.Move> readMoves(JSONArray list) throws JSONException {
        List<FrenchGame.Move> moves = new ArrayList<>(list.length());
        for (int i = 0; i < list.length(); i++) {
            JSONObject move = list.getJSONObject(i);
            moves.add(new FrenchGame.Move(move.getInt("player"), move.getString("type"), move.getString("word"),
                    move.getInt("score"), readTiles(move.getJSONArray("tiles"), false),
                    move.optInt("exchange_count"), move.optString("rack", null), move.optInt("bag_before", -1)));
        }
        return moves;
    }

    private static String boardText(char[] board, boolean[] blanks) {
        StringBuilder encoded = new StringBuilder(FrenchGame.CELLS);
        for (int i = 0; i < board.length; i++) {
            encoded.append(board[i] == 0 ? '.' : blanks[i] ? Character.toLowerCase(board[i]) : board[i]);
        }
        return encoded.toString();
    }

    static State forLocal(FrenchGame game, int seat) {
        return new State(game.id, game.names, game.levels, game.scores, game.active, game.bag.size(), seat,
                game.board, game.blanks, game.racks.get(seat), game.history, game.end);
    }

    static JSONObject visible(FrenchGame game, int seat) {
        State state = forLocal(game, seat);
        return object("type", "state", "version", VERSION, "id", state.id, "names", new JSONArray(Arrays.asList(state.names)),
                "levels", ints(state.levels), "scores", ints(state.scores), "active", state.active,
                "bag_count", state.bagSize, "seat", state.seat, "board", boardText(state.board, state.blanks),
                "rack", rackText(state.rack), "moves", moves(state.history, false), "end", state.end);
    }

    static State readState(JSONObject row) throws JSONException {
        String encoded = row.getString("board");
        if (encoded.length() != FrenchGame.CELLS) throw new JSONException("Plateau incomplet");
        char[] board = new char[FrenchGame.CELLS];
        boolean[] blanks = new boolean[FrenchGame.CELLS];
        for (int i = 0; i < encoded.length(); i++) {
            char letter = encoded.charAt(i);
            if (letter == '.') continue;
            if (letter >= 'a' && letter <= 'z') blanks[i] = true;
            else if (letter < 'A' || letter > 'Z') throw new JSONException("Plateau invalide");
            board[i] = Character.toUpperCase(letter);
        }
        JSONArray array = row.getJSONArray("names");
        if (array.length() < 2 || array.length() > 4) throw new JSONException("Nombre de joueurs invalide");
        String[] names = new String[array.length()];
        for (int i = 0; i < names.length; i++) names[i] = array.getString(i);
        int[] levels = readInts(row.getJSONArray("levels"), names.length);
        int[] scores = readInts(row.getJSONArray("scores"), names.length);
        int active = row.getInt("active");
        int seat = row.getInt("seat");
        if (active < 0 || active >= names.length || seat < 0 || seat >= names.length) throw new JSONException("Tour invalide");
        List<Character> rack = readRack(row.getString("rack"));
        if (rack.size() > FrenchGame.RACK_SIZE) throw new JSONException("Chevalet trop long");
        List<FrenchGame.Move> moves = readMoves(row.getJSONArray("moves"));
        for (FrenchGame.Move move : moves) {
            if (move.player() < 0 || move.player() >= names.length) throw new JSONException("Historique invalide");
        }
        return new State(row.getString("id"), names, levels, scores, active, row.getInt("bag_count"), seat,
                board, blanks, rack, moves, row.isNull("end") ? null : row.getString("end"));
    }

    private static int[] readInts(JSONArray array, int expected) throws JSONException {
        if (array.length() != expected) throw new JSONException("Taille invalide");
        int[] values = new int[expected];
        for (int i = 0; i < values.length; i++) values[i] = array.getInt(i);
        return values;
    }

    static JSONObject saved(FrenchGame game, String mode, int startRating, int levelSum, int levelCount, boolean assisted) {
        JSONObject row = visible(game, 0);
        put(row, "moves", moves(game.history, true));
        JSONArray racks = new JSONArray();
        for (List<Character> rack : game.racks) racks.put(rackText(rack));
        put(row, "racks", racks);
        put(row, "bag", rackText(game.bag));
        put(row, "seed", Long.toString(game.seed));
        put(row, "passes", game.passes);
        put(row, "mode", mode);
        put(row, "start_rating", startRating);
        put(row, "level_sum", levelSum);
        put(row, "level_count", levelCount);
        put(row, "assisted", assisted);
        return row;
    }

    static FrenchGame restore(JSONObject row) throws JSONException {
        State state = readState(row);
        FrenchGame game = new FrenchGame(state.id, Long.parseLong(row.getString("seed")), state.names, state.levels);
        JSONArray racks = row.getJSONArray("racks");
        for (int i = 0; i < game.racks.size(); i++) game.racks.get(i).addAll(readRack(racks.getString(i)));
        game.bag.addAll(readRack(row.getString("bag")));
        game.history.addAll(state.history);
        game.active = state.active;
        game.end = state.end;
        game.passes = row.getInt("passes");
        System.arraycopy(state.board, 0, game.board, 0, game.board.length);
        System.arraycopy(state.blanks, 0, game.blanks, 0, game.blanks.length);
        System.arraycopy(state.scores, 0, game.scores, 0, game.scores.length);
        return game;
    }

    static JSONObject finished(State state, String mode, String lexicon, Long seed) {
        int maximum = Arrays.stream(state.scores).max().orElse(0);
        JSONArray winners = new JSONArray();
        for (int i = 0; i < state.scores.length; i++) {
            if (state.scores[i] == maximum) winners.put(i);
        }
        int[] adjustments = state.scores.clone();
        for (FrenchGame.Move move : state.history) adjustments[move.player()] -= move.score();
        JSONObject row = object("schema", "lettrine.game.v1", "id", state.id, "source", "android",
                "mode", mode, "language", "fr", "lexicon", lexicon, "finished_at_ms", System.currentTimeMillis(),
                "names", new JSONArray(Arrays.asList(state.names)), "levels", ints(state.levels),
                "scores", ints(state.scores), "adjustments", ints(adjustments), "turns", state.history.size(),
                "moves", moves(state.history, !mode.equals("bluetooth")),
                "winner", winners.length() == 1 ? winners.optInt(0) : null, "winners", winners,
                "end", state.end, "board", boardText(state.board, state.blanks));
        if (seed != null) put(row, "seed", Long.toString(seed));
        return row;
    }
}
