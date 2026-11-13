package fr.lettrine.game;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.view.View;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.function.Consumer;
import java.util.regex.Pattern;

final class SocialPanel {
    private static final String WEB_INVITE = "https://nabz0r.github.io/scrabnble/amis/#";
    private static final String APP_INVITE = "lettrine://amis/v1/";
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32}");

    private enum Tab {
        DUELS(R.string.social_duels), FRIENDS(R.string.social_friends), RANKING(R.string.social_ranking);

        private final int title;
        Tab(int title) { this.title = title; }
    }

    private final ViewKit ui;
    private final SocialClient client;
    private final Consumer<JSONObject> openDuel;
    private final Consumer<String> connectGoogle;
    private final Runnable openTournament;
    private final LinearLayout root;
    private final TextView notice;
    private final LinearLayout body;
    private String invitation;
    private JSONObject dashboard;
    private Tab tab = Tab.DUELS;
    private boolean busy;
    private boolean deleted;

    SocialPanel(ViewKit ui, SocialClient client, Consumer<JSONObject> openDuel, String invitation,
                Consumer<String> connectGoogle, Runnable openTournament) {
        this.ui = ui;
        this.client = client;
        this.openDuel = openDuel;
        this.invitation = invitation;
        this.connectGoogle = connectGoogle;
        this.openTournament = openTournament;

        root = ui.column();
        notice = ui.body("");
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        notice.setVisibility(View.GONE);
        root.addView(notice);
        root.addView(TournamentPanel.entry(ui, this.openTournament));
        ui.gap(root, 14);
        body = ui.column();
        root.addView(body);
        if (client.configured()) load();
        else {
            showNotice(label(R.string.social_not_configured));
            render();
        }
    }

    View view() { return root; }

    static String invitationToken(String value) {
        if (value == null) return null;
        value = value.strip();
        if (value.startsWith(WEB_INVITE)) value = value.substring(WEB_INVITE.length());
        else if (value.startsWith(APP_INVITE)) value = value.substring(APP_INVITE.length());
        return TOKEN.matcher(value).matches() ? value : null;
    }

    private void load() {
        if (!begin()) return;
        client.dashboard((result, error) -> {
            if (!finish(error)) return;
            dashboard = result;
            render();
        });
    }

    private boolean begin() {
        if (busy) return false;
        busy = true;
        showNotice(label(R.string.social_loading));
        return true;
    }

    private boolean finish(String error) {
        busy = false;
        showNotice(error);
        if (error != null && dashboard == null) render();
        return error == null;
    }

    private void showNotice(String message) {
        notice.setText(message);
        notice.setVisibility(message == null ? View.GONE : View.VISIBLE);
    }

    private void render() {
        body.removeAllViews();
        if (dashboard == null) {
            if (deleted) body.addView(ui.body(label(R.string.social_deleted)));
            addAction(body, R.string.social_refresh, false, this::load);
            if (!deleted) {
                if (invitation != null) showInvitation();
                addAction(body, R.string.social_enter_link, false, this::enterLink);
            }
            return;
        }
        JSONObject profile = dashboard.optJSONObject("profile");
        if (profile != null) {
            LinearLayout identity = ui.card();
            identity.addView(ui.title(name(profile), 23));
            ui.gap(identity, 4);
            identity.addView(ui.body(label(profile.optBoolean("google_linked")
                    ? R.string.social_google_linked : R.string.social_guest)));
            if (!profile.optBoolean("google_linked") && !dashboard.isNull("google_client_id")
                    && !dashboard.optString("google_client_id").isBlank()) {
                String clientId = dashboard.optString("google_client_id");
                addAction(identity, R.string.social_google_connect, false, () -> connectGoogle.accept(clientId));
            }
            body.addView(identity);
        }
        if (invitation != null) showInvitation();
        ui.gap(body, 12);
        LinearLayout tabs = ui.row();
        for (Tab entry : Tab.values()) {
            if (entry != Tab.DUELS) ui.space(tabs, 4);
            TextView button = ui.button(label(entry.title), entry == tab, () -> {
                tab = entry;
                render();
            });
            button.setSelected(entry == tab);
            ui.inset(button, 3, 8);
            ui.weight(tabs, button);
        }
        body.addView(tabs);
        ui.gap(body, 12);
        switch (tab) {
            case DUELS -> showDuels(profile == null ? "" : profile.optString("id"));
            case FRIENDS -> showFriends();
            case RANKING -> showRanking();
        }
        addAction(body, R.string.social_refresh, false, this::load);
        addAction(body, R.string.social_delete, false, this::confirmDelete);
    }

    private void showInvitation() {
        ui.gap(body, 10);
        LinearLayout invite = ui.card();
        invite.addView(ui.body(label(R.string.social_invite_ready)));
        addAction(invite, R.string.social_accept_invite, true, () -> accept(invitation));
        body.addView(invite);
    }

    private void showDuels(String viewer) {
        JSONArray duels = dashboard.optJSONArray("duels");
        if (duels == null || duels.length() == 0) {
            body.addView(ui.body(label(R.string.social_no_duels)));
            return;
        }
        for (int i = 0; i < duels.length(); i++) {
            JSONObject duel = duels.optJSONObject(i);
            if (duel == null) continue;
            String id = duel.optString("id");
            String opponent = name(duel.optJSONObject("opponent"));
            String status = duel.optString("status");
            String details = switch (status) {
                case "your_turn" -> label(R.string.social_your_turn);
                case "finished" -> label(R.string.social_duel_result, outcome(duel, viewer), duel.optInt("score"),
                        duel.optInt("opponent_score"));
                default -> label(R.string.social_waiting);
            };
            LinearLayout card = ui.card();
            card.addView(ui.title(opponent, 22));
            ui.gap(card, 5);
            card.addView(ui.body(details));
            if (status.equals("your_turn")) addAction(card, R.string.social_play, true, () -> openDuel.accept(duel));
            if (status.equals("finished")) addAction(card, R.string.social_rematch, false, () -> rematch(id));
            body.addView(card);
            ui.gap(body, 8);
        }
    }

    private String outcome(JSONObject duel, String viewer) {
        if (duel.isNull("winner")) return label(R.string.social_duel_draw);
        return label(viewer.equals(duel.optString("winner")) ? R.string.social_duel_win : R.string.social_duel_loss);
    }

    private void showFriends() {
        addAction(body, R.string.social_invite, true, this::createInvitation);
        addAction(body, R.string.social_enter_link, false, this::enterLink);
        ui.gap(body, 12);
        JSONArray friends = dashboard.optJSONArray("friends");
        if (friends == null || friends.length() == 0) {
            body.addView(ui.body(label(R.string.social_no_friends)));
            return;
        }
        for (int i = 0; i < friends.length(); i++) {
            JSONObject friend = friends.optJSONObject(i);
            if (friend == null) continue;
            LinearLayout card = ui.card();
            card.addView(ui.title(name(friend), 22));
            ui.gap(card, 8);
            LinearLayout actions = ui.row();
            ui.weight(actions, ui.button(label(R.string.social_new_duel), true, () -> startDuel(friend.optString("id"))));
            ui.space(actions, 5);
            ui.weight(actions, ui.button(label(R.string.social_friend_manage), false, () -> manageFriend(friend)));
            card.addView(actions);
            body.addView(card);
            ui.gap(body, 8);
        }
    }

    private void showRanking() {
        JSONArray ranking = dashboard.optJSONArray("ranking");
        if (ranking == null || ranking.length() == 0) {
            body.addView(ui.body(label(R.string.social_no_ranking)));
            return;
        }
        body.addView(ui.body(label(R.string.social_rank_legend)));
        ui.gap(body, 8);
        for (int i = 0; i < ranking.length(); i++) {
            JSONObject member = ranking.optJSONObject(i);
            if (member == null) continue;
            LinearLayout card = ui.card();
            card.addView(ui.title(label(R.string.social_rank_name, i + 1, name(member)), 21));
            ui.gap(card, 5);
            card.addView(ui.body(label(R.string.social_rank_stats, member.optInt("wins"), member.optInt("draws"),
                    member.optInt("losses"), member.optInt("points"))));
            body.addView(card);
            ui.gap(body, 8);
        }
    }

    private void createInvitation() {
        if (!begin()) return;
        client.createInvitation((result, error) -> {
            if (!finish(error) || !root.isAttachedToWindow()) return;
            String token = invitationToken(result.optString("token"));
            if (token == null) {
                showNotice(label(R.string.social_invalid_invite));
                return;
            }
            String message = label(R.string.social_share_invitation, WEB_INVITE + token);
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message);
            try { ui.context.startActivity(Intent.createChooser(send, label(R.string.social_invite))); }
            catch (ActivityNotFoundException exception) { showNotice(label(R.string.social_share_error)); }
        });
    }

    private void enterLink() {
        EditText input = new EditText(ui.context);
        input.setSingleLine();
        input.setHint(R.string.social_link_hint);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout entry = ui.column();
        ui.inset(entry, 20, 4);
        entry.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(ui.context).setTitle(R.string.social_enter_link).setView(entry)
                .setNegativeButton(R.string.social_cancel, null).setPositiveButton(R.string.social_confirm, null).create();
        dialog.setOnShowListener(view -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String token = invitationToken(input.getText().toString());
            if (token == null) input.setError(label(R.string.social_invalid_invite));
            else {
                dialog.dismiss();
                invitation = token;
                render();
                for (ViewParent parent = root.getParent(); parent != null; parent = parent.getParent()) {
                    if (parent instanceof ScrollView scroll) {
                        scroll.smoothScrollTo(0, 0);
                        break;
                    }
                }
            }
        }));
        dialog.show();
    }

    private void accept(String token) {
        if (!begin()) return;
        client.acceptInvitation(token, (result, error) -> {
            if (!finish(error)) return;
            invitation = null;
            tab = Tab.FRIENDS;
            load();
        });
    }

    private void startDuel(String friend) {
        if (begin()) client.startDuel(friend, this::createdDuel);
    }

    private void rematch(String duel) {
        if (begin()) client.rematch(duel, this::createdDuel);
    }

    private void createdDuel(JSONObject result, String error) {
        if (!finish(error) || !root.isAttachedToWindow()) return;
        JSONObject duel = result.optJSONObject("duel");
        if (duel != null && duel.optString("status").equals("your_turn")) openDuel.accept(duel);
        else load();
    }

    private void manageFriend(JSONObject friend) {
        String[] actions = {label(R.string.social_friend_remove), label(R.string.social_friend_block)};
        new AlertDialog.Builder(ui.context).setTitle(name(friend)).setItems(actions, (dialog, choice) -> {
            boolean block = choice == 1;
            confirm(block ? R.string.social_friend_block : R.string.social_friend_remove,
                    label(block ? R.string.social_friend_block_confirm : R.string.social_friend_remove_confirm, name(friend)),
                    () -> changeFriend(friend.optString("id"), block));
        }).setNegativeButton(R.string.social_cancel, null).show();
    }

    private void changeFriend(String id, boolean block) {
        if (!begin()) return;
        SocialClient.Callback changed = (result, error) -> {
            if (finish(error)) load();
        };
        if (block) client.blockFriend(id, changed);
        else client.removeFriend(id, changed);
    }

    private void confirmDelete() {
        confirm(R.string.social_delete, label(R.string.social_delete_details), () -> {
            if (!begin()) return;
            client.deleteProfile((result, error) -> {
                if (!finish(error)) return;
                deleted = true;
                dashboard = null;
                invitation = null;
                render();
            });
        });
    }

    private void confirm(int title, String message, Runnable action) {
        new AlertDialog.Builder(ui.context).setTitle(title).setMessage(message)
                .setNegativeButton(R.string.social_cancel, null)
                .setPositiveButton(R.string.social_confirm, (dialog, which) -> action.run()).show();
    }

    private void addAction(LinearLayout parent, int text, boolean primary, Runnable action) {
        ui.gap(parent, 7);
        parent.addView(ui.button(label(text), primary, action));
    }

    private static String name(JSONObject profile) { return profile == null ? "" : profile.optString("nickname"); }

    private String label(int resource, Object... values) { return ui.context.getString(resource, values); }
}
