package com.noop.ui

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * App-owned UI language. Units and time zone remain independent, while locale-sensitive display
 * formatting follows this selection through [Locale.setDefault]. Adding a language here therefore
 * requires auditing default-locale parsers and formatters, especially persistent day keys: storage
 * formats must pin their locale and chronology before supporting different numeral or calendar systems.
 */
enum class AppLanguage(val storageValue: String?, val autonym: String) {
    SYSTEM(null, ""),
    ENGLISH("en", "English"),
    GERMAN("de", "Deutsch"),
    SPANISH("es", "Español"),
    FRENCH("fr", "Français"),
    ITALIAN("it", "Italiano"),
    PORTUGUESE("pt-PT", "Português"),
    POLISH("pl", "Polski"),
    RUSSIAN("ru", "Русский"),
    CHINESE("zh", "中文");

    companion object {
        fun fromStorage(raw: String?): AppLanguage =
            entries.firstOrNull { it.storageValue == raw } ?: SYSTEM
    }
}

/**
 * Process-wide locale owner. Both the Application and Activity wrap their base contexts through this
 * object, which keeps composable `stringResource`, non-composable `uiString`, services, and widgets on
 * one locale. `Locale.setDefault` also keeps date/month words and locale-aware casing aligned with the
 * selected UI language instead of leaking the phone language into otherwise translated copy.
 */
object AppLanguagePrefs {
    private const val FILE = "noop_prefs"
    private const val KEY = "noop.appLanguage"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun selected(context: Context): AppLanguage =
        AppLanguage.fromStorage(prefs(context).getString(KEY, null))

    fun wrap(context: Context): Context {
        val language = selected(context)
        val locales = localesFor(language, context)
        Locale.setDefault(locales[0])
        if (language == AppLanguage.SYSTEM) return context

        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(locales)
        return context.createConfigurationContext(configuration)
    }

    fun set(context: Context, language: AppLanguage) {
        prefs(context).edit().apply {
            if (language == AppLanguage.SYSTEM) remove(KEY)
            else putString(KEY, language.storageValue)
        }.apply()

        // The Application outlives Activity.recreate(), so update its Resources too. That keeps
        // uiString() and a running foreground service from retaining the previous language.
        val appResources = context.applicationContext.resources
        val configuration = Configuration(appResources.configuration)
        val locales = localesFor(language, context)
        configuration.setLocales(locales)
        Locale.setDefault(locales[0])
        @Suppress("DEPRECATION")
        appResources.updateConfiguration(configuration, appResources.displayMetrics)
    }

    private fun localesFor(language: AppLanguage, context: Context? = null): LocaleList {
        if (language == AppLanguage.SYSTEM) {
            // Android 13+: "System" means the per-app language the system holds for reNOOP when one is
            // set (Settings > Apps > reNOOP > Language), else the phone's own. Resources already follow
            // it; this keeps date and number formatting on the same locale.
            systemAppLocales(context)?.let { return it }
            val system = Resources.getSystem().configuration.locales
            return if (system.isEmpty) LocaleList(Locale.ENGLISH) else system
        }
        return LocaleList(Locale.forLanguageTag(checkNotNull(language.storageValue)))
    }

    /** The system-held per-app locales (Android 13+), or null when none are set or before 13. */
    private fun systemAppLocales(context: Context?): LocaleList? {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val locales = runCatching { context.getSystemService(LocaleManager::class.java)?.applicationLocales }
            .getOrNull()
        return locales?.takeIf { !it.isEmpty }
    }

    /**
     * Android 13+ keeps an app's language in the system (Settings > Apps > reNOOP > Language), which is
     * where Settings > General > Language sends the reader. A language picked in-app before is handed to
     * the system first, so the system page opens on it and nothing in-app overrides the system choice.
     */
    fun handOverToSystem(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val stored = selected(context)
        if (stored == AppLanguage.SYSTEM) return
        prefs(context).edit().remove(KEY).apply()
        runCatching {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                LocaleList.forLanguageTags(checkNotNull(stored.storageValue))
        }
    }

    /** The language the app is showing, as the Language row's value: its own name, or null for the phone's. */
    fun currentLanguageName(context: Context): String? {
        val stored = selected(context)
        if (stored != AppLanguage.SYSTEM) return stored.autonym
        val locale = systemAppLocales(context)?.get(0) ?: return null
        return locale.getDisplayName(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
}
