package fr.lettrine.game;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

final class AppLocale {
    private static final String KEY = "app_language";

    private AppLocale() {}

    static GameLanguage language(Context context) {
        return GameLanguage.fromCode(context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
                .getString(KEY, "fr"));
    }

    static void select(Context context, GameLanguage language) {
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit()
                .putString(KEY, language.code).apply();
    }

    static Locale locale(Context context) {
        return Locale.forLanguageTag(language(context).code);
    }

    static Context wrap(Context context) {
        return wrap(context, language(context));
    }

    static Context wrap(Context context, GameLanguage language) {
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocales(new LocaleList(Locale.forLanguageTag(language.code)));
        return context.createConfigurationContext(configuration);
    }
}
