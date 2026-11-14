package fr.lettrine.game;

import org.json.JSONObject;

import java.util.Arrays;

final class BluetoothSession {
    enum Decision { START, RESUME, INCOMPATIBLE, DIFFERENT_GAME }

    private BluetoothSession() {}

    static JSONObject hello(String name, String lexiconHash, boolean host, String resumeId) {
        JSONObject result = MatchCodec.object("type", "hello", "version", MatchCodec.VERSION,
                "name", name, "lexicon", lexiconHash, "host", host);
        if (resumeId != null && !resumeId.isEmpty()) MatchCodec.put(result, "resume", resumeId);
        return result;
    }

    static Decision negotiate(JSONObject remote, String lexiconHash, boolean host, String resumeId) {
        Object role = remote.opt("host");
        Object requested = remote.opt("resume");
        if ((role != null && !(role instanceof Boolean)) || (requested != null && !(requested instanceof String))) {
            return Decision.INCOMPATIBLE;
        }
        return negotiate(remote.optInt("version", -1), remote.optString("lexicon"), (Boolean) role,
                (String) requested, lexiconHash, host, resumeId);
    }

    static Decision negotiate(int version, String remoteLexicon, Boolean remoteHost, String remoteId,
                              String lexiconHash, boolean host, String resumeId) {
        if (version != MatchCodec.VERSION || !lexiconHash.equals(remoteLexicon)
                || (remoteHost != null && remoteHost == host)) return Decision.INCOMPATIBLE;

        boolean resuming = resumeId != null && !resumeId.isEmpty();
        boolean remoteResuming = remoteId != null && !remoteId.isEmpty();
        if (!resuming && !remoteResuming) return Decision.START;
        if (resuming && resumeId.equals(remoteId) && remoteHost != null) return Decision.RESUME;
        return Decision.DIFFERENT_GAME;
    }

    static boolean keepGuestDraft(MatchCodec.State previous, MatchCodec.State next) {
        return previous != null && previous.seat() == 1 && next.seat() == 1
                && previous.end() == null && next.end() == null && previous.id().equals(next.id())
                && previous.language() == next.language() && previous.active() == 1 && next.active() == 1
                && previous.history().size() == next.history().size() && previous.rack().equals(next.rack())
                && Arrays.equals(previous.board(), next.board()) && Arrays.equals(previous.blanks(), next.blanks());
    }
}
