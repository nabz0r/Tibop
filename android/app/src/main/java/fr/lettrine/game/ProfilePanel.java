package fr.lettrine.game;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.Collator;
import java.text.Normalizer;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ProfilePanel {
    private static final int[] SEX_LABELS = {R.string.profile_unset, R.string.profile_sex_female, R.string.profile_sex_male,
            R.string.profile_sex_non_binary, R.string.profile_sex_other};

    private record Country(String code, String name) {
        @Override public String toString() { return name; }
    }

    private final ViewKit ui;
    private final ProfileStore store;
    private final LinearLayout layout;
    private final ImageView image;
    private final TextView initials;
    private final TextView displayedName;
    private final TextView photoButton;
    private final TextView removePhoto;
    private final EditText nickname;
    private final EditText age;
    private final TextView countryButton;
    private final TextView sexButton;
    private final TextView status;
    private String country;
    private ProfileStore.Sex sex;

    ProfilePanel(ViewKit ui, ProfileStore store, GameStore games, DailyProgress daily, Runnable choosePhoto) {
        this.ui = ui;
        this.store = store;
        ProfileStore.Profile profile = store.read();
        country = profile.country();
        sex = profile.sex();

        layout = ui.column();
        layout.setFocusableInTouchMode(true);
        LinearLayout hero = ui.column();
        hero.setBackground(ui.shape(ui.palette.deep(), 18));
        ui.inset(hero, 16, 16);

        LinearLayout identity = ui.row();
        FrameLayout avatar = new FrameLayout(ui.context);
        avatar.setBackground(ui.shape(ui.palette.ordinary(), 18));
        avatar.setClipToOutline(true);
        initials = ui.title("", 32);
        initials.setGravity(Gravity.CENTER);
        initials.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        avatar.addView(initials, new FrameLayout.LayoutParams(-1, -1));
        image = new ImageView(ui.context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setContentDescription(label(R.string.profile_avatar));
        avatar.addView(image, new FrameLayout.LayoutParams(-1, -1));
        identity.addView(avatar, new LinearLayout.LayoutParams(ui.dp(68), ui.dp(68)));
        ui.space(identity, 14);
        displayedName = ui.text("", 26, ui.palette.heroInk(), ui.serif);
        displayedName.setMaxLines(2);
        displayedName.setEllipsize(TextUtils.TruncateAt.END);
        ui.weight(identity, displayedName);
        hero.addView(identity);
        ui.gap(hero, 14);
        LinearLayout photoActions = ui.row();
        photoButton = ui.button("", false, choosePhoto);
        photoButton.setMinHeight(ui.dp(48));
        ui.weight(photoActions, photoButton);
        removePhoto = ui.button(label(R.string.profile_photo_remove), false, () -> {
            store.clearPhoto();
            updateAvatar();
            toast(R.string.profile_photo_removed);
        });
        removePhoto.setMinHeight(ui.dp(48));
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(0, -2, 1);
        removeParams.setMarginStart(ui.dp(8));
        photoActions.addView(removePhoto, removeParams);
        hero.addView(photoActions);
        layout.addView(hero);
        ui.gap(layout, 10);
        layout.addView(ui.body(label(R.string.profile_private)));

        ui.gap(layout, 18);
        LinearLayout fields = ui.card();
        fields.addView(ui.title(label(R.string.profile_details), 24));
        ui.gap(fields, 12);
        nickname = input(profile.nickname(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        nickname.setFilters(new InputFilter[]{new InputFilter.LengthFilter(ProfileStore.MAX_NICKNAME * 2)});
        addField(fields, R.string.profile_nickname, nickname);
        countryButton = ui.button(countryLabel(country), false, this::selectCountry);
        addField(fields, R.string.profile_country, countryButton);
        age = input(profile.age() > 0 ? Integer.toString(profile.age()) : "", InputType.TYPE_CLASS_NUMBER);
        age.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
        addField(fields, R.string.profile_age, age);
        sexButton = ui.button(label(SEX_LABELS[sex.ordinal()]), false, this::selectSex);
        addField(fields, R.string.profile_sex, sexButton);
        ui.gap(fields, 4);
        fields.addView(ui.button(label(R.string.profile_save), true, this::save));
        status = ui.body("");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(View.GONE);
        fields.addView(status);
        layout.addView(fields);

        ui.gap(layout, 19);
        addAchievements(games, daily);
        updateAvatar();
        layout.requestFocus();
    }

    View view() { return layout; }

    static Intent photoPicker() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
    }

    void photoSelected(Uri photo) {
        if (photo == null || !photoButton.isEnabled()) return;
        ui.enabled(photoButton, false);
        ui.enabled(removePhoto, false);
        new Thread(() -> {
            int message;
            try {
                store.savePhoto(photo);
                message = R.string.profile_photo_saved;
            } catch (IOException | RuntimeException invalid) {
                message = R.string.profile_photo_error;
            }
            int result = message;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!layout.isAttachedToWindow()) return;
                ui.enabled(photoButton, true);
                ui.enabled(removePhoto, true);
                updateAvatar();
                toast(result);
            });
        }, "Lettrine profile photo").start();
    }

    private void updateAvatar() {
        Bitmap picture = store.photo();
        boolean hasPhoto = picture != null;
        image.setImageBitmap(picture);
        image.setVisibility(hasPhoto ? View.VISIBLE : View.GONE);
        photoButton.setText(label(hasPhoto ? R.string.profile_photo_change : R.string.profile_photo_choose));
        removePhoto.setVisibility(hasPhoto ? View.VISIBLE : View.GONE);
        String name = store.nickname();
        displayedName.setText(name.isEmpty() ? label(R.string.profile_guest) : name);
        initials.setText(name.isEmpty() ? label(R.string.app_name).substring(0, 1)
                : new String(Character.toChars(name.codePointAt(0))).toUpperCase(locale()));
    }

    private EditText input(String value, int type) {
        EditText field = new EditText(ui.context);
        field.setTextColor(ui.palette.ink());
        field.setHintTextColor(ui.palette.muted());
        field.setTextSize(15);
        field.setInputType(type);
        field.setSingleLine(true);
        field.setMinHeight(ui.dp(48));
        field.setBackground(ui.shape(ui.palette.page(), 12, ui.palette.line()));
        ui.inset(field, 12, 9);
        field.setHint(R.string.profile_optional);
        field.setText(value);
        return field;
    }

    private void addField(LinearLayout form, int title, View field) {
        field.setId(View.generateViewId());
        TextView fieldLabel = ui.text(label(title), 14, ui.palette.ink(), ui.medium);
        fieldLabel.setLabelFor(field.getId());
        form.addView(fieldLabel);
        ui.gap(form, 5);
        form.addView(field);
        ui.gap(form, 10);
    }

    private void selectCountry() {
        List<Country> countries = new ArrayList<>();
        for (String code : ProfileStore.countryCodes()) countries.add(new Country(code, countryLabel(code)));
        Collator collator = Collator.getInstance(locale());
        countries.sort((left, right) -> collator.compare(left.name(), right.name()));
        countries.add(0, new Country("", countryLabel("")));
        LinearLayout chooser = ui.column();
        SearchView search = new SearchView(ui.context);
        search.setIconifiedByDefault(false);
        search.setQueryHint(label(R.string.profile_country_search));
        chooser.addView(search);
        ListView results = new ListView(ui.context);
        int screenHeight = ui.context.getResources().getConfiguration().screenHeightDp;
        chooser.addView(results, new LinearLayout.LayoutParams(-1, ui.dp(Math.min(330, screenHeight * 0.43f))));
        ArrayAdapter<Country> adapter = new ArrayAdapter<>(ui.context, android.R.layout.simple_list_item_1,
                new ArrayList<>(countries));
        results.setAdapter(adapter);
        AlertDialog dialog = new AlertDialog.Builder(ui.context).setTitle(R.string.profile_country).setView(chooser)
                .setNegativeButton(R.string.profile_cancel, null).create();
        results.setOnItemClickListener((parent, item, position, id) -> {
            Country selected = adapter.getItem(position);
            if (selected == null) return;
            country = selected.code();
            countryButton.setText(countryLabel(country));
            dialog.dismiss();
        });
        search.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String query) {
                search.clearFocus();
                return true;
            }

            @Override public boolean onQueryTextChange(String query) {
                String text = searchable(query.trim());
                List<Country> filtered = new ArrayList<>();
                for (Country entry : countries) {
                    if (searchable(entry.name()).contains(text) || entry.code().equalsIgnoreCase(text)) filtered.add(entry);
                }
                adapter.setNotifyOnChange(false);
                adapter.clear();
                adapter.addAll(filtered);
                adapter.notifyDataSetChanged();
                return true;
            }
        });
        dialog.show();
    }

    private void selectSex() {
        String[] names = new String[SEX_LABELS.length];
        for (int i = 0; i < names.length; i++) names[i] = label(SEX_LABELS[i]);
        new AlertDialog.Builder(ui.context).setTitle(R.string.profile_sex)
                .setSingleChoiceItems(names, sex.ordinal(), (dialog, position) -> {
                    sex = ProfileStore.Sex.values()[position];
                    sexButton.setText(names[position]);
                    dialog.dismiss();
                }).setNegativeButton(R.string.profile_cancel, null).show();
    }

    private void save() {
        nickname.setError(null);
        age.setError(null);
        String name = nickname.getText().toString().trim();
        if (name.codePointCount(0, name.length()) > ProfileStore.MAX_NICKNAME
                || name.codePoints().anyMatch(Character::isISOControl)) {
            nickname.setError(label(R.string.profile_nickname_error));
            nickname.requestFocus();
            return;
        }
        String years = age.getText().toString().trim();
        int value = 0;
        if (!years.isEmpty()) {
            try { value = Integer.parseInt(years); }
            catch (NumberFormatException invalid) { value = -1; }
            if (value < 1 || value > ProfileStore.MAX_AGE) {
                age.setError(label(R.string.profile_age_error));
                age.requestFocus();
                return;
            }
        }
        boolean saved = store.save(new ProfileStore.Profile(name, country, value, sex));
        status.setText(label(saved ? R.string.profile_saved : R.string.profile_save_error));
        status.setVisibility(View.VISIBLE);
        if (saved) {
            InputMethodManager keyboard = ui.context.getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(layout.getWindowToken(), 0);
            updateAvatar();
        }
    }

    private void addAchievements(GameStore games, DailyProgress daily) {
        int solo = 0;
        boolean victory = false;
        boolean bingo = false;
        List<JSONObject> history = games == null ? List.of() : games.history();
        for (JSONObject match : history) {
            if (!"solo".equals(match.optString("mode"))) continue;
            solo++;
            victory |= match.optInt("winner", -1) == 0;
            JSONArray moves = match.optJSONArray("moves");
            if (moves == null || bingo) continue;
            for (int i = 0; i < moves.length(); i++) {
                JSONObject move = moves.optJSONObject(i);
                if (move == null || move.optInt("player", -1) != 0) continue;
                JSONArray tiles = move.optJSONArray("tiles");
                if (tiles != null && tiles.length() == FrenchGame.RACK_SIZE) {
                    bingo = true;
                    break;
                }
            }
        }
        DailyProgress.Achievements challenges = daily.achievements(LocalDate.now());
        layout.addView(ui.title(label(R.string.profile_achievements), 25));
        ui.gap(layout, 9);
        addStats(R.string.profile_stat_elo, games == null ? GameStore.BASE_ELO : games.elo(),
                R.string.profile_stat_games, solo);
        addStats(R.string.profile_stat_days, challenges.days(), R.string.profile_stat_streak, challenges.longestStreak());
        addAchievement(R.string.profile_first_win, R.string.profile_first_win_detail, victory);
        addAchievement(R.string.profile_bingo, R.string.profile_bingo_detail, bingo);
        addAchievement(R.string.profile_three_days, R.string.profile_three_days_detail, challenges.longestStreak() >= 3);
    }

    private void addStats(int left, int leftValue, int right, int rightValue) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.TOP);
        ui.weight(row, stat(left, leftValue));
        ui.space(row, 8);
        ui.weight(row, stat(right, rightValue));
        layout.addView(row);
        ui.gap(layout, 8);
    }

    private View stat(int title, int value) {
        LinearLayout card = ui.card();
        card.addView(ui.title(NumberFormat.getIntegerInstance(locale()).format(value), 25));
        ui.gap(card, 4);
        card.addView(ui.body(label(title)));
        return card;
    }

    private void addAchievement(int title, int detail, boolean unlocked) {
        LinearLayout card = ui.card();
        LinearLayout heading = ui.row();
        TextView name = ui.text(label(title), 16, ui.palette.ink(), ui.medium);
        ui.weight(heading, name);
        ui.space(heading, 8);
        TextView state = ui.text(label(unlocked ? R.string.profile_unlocked : R.string.profile_locked),
                12, ui.palette.ink(), ui.medium);
        ui.inset(state, 7, 5);
        state.setBackground(ui.shape(unlocked ? ui.palette.fresh() : ui.palette.ordinary(), 10));
        heading.addView(state);
        card.addView(heading);
        ui.gap(card, 5);
        card.addView(ui.body(label(detail)));
        layout.addView(card);
        ui.gap(layout, 7);
    }

    private String countryLabel(String code) {
        return code.isEmpty() ? label(R.string.profile_unset) : new Locale("", code).getDisplayCountry(locale());
    }

    private String searchable(String value) {
        return Normalizer.normalize(value.toLowerCase(locale()), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    private Locale locale() { return AppLocale.locale(ui.context); }

    private String label(int resource) { return ui.context.getString(resource); }

    private void toast(int resource) { Toast.makeText(ui.context, resource, Toast.LENGTH_SHORT).show(); }
}
