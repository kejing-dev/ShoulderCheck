package io.embodit.shouldercheck.ui.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.embodit.shouldercheck.R
import io.embodit.shouldercheck.domain.Language
import java.util.Locale

/**
 * In-app language (design `i18n`: 中文 / English switched inside the app, first run follows the system), changed at
 * run time without restarting the Stage: the screen provides a [Resources] object for that locale through
 * [LocalScResources] and every panel reads its strings with [rs] (rehearsal-hall mechanism). Strings live in
 * `res/values/strings.xml` (English) and `res/values-zh/strings.xml` (中文).
 *
 *     val res = remember(lang, context) { localizedResources(context, lang) }
 *     CompositionLocalProvider(LocalScResources provides res) { ... Text(rs(R.string.sit)) ... }
 */
val LocalScResources = staticCompositionLocalOf<Resources> { error("LocalScResources not provided") }

fun localizedResources(context: Context, language: Language): Resources {
    val tag =
        when (language) {
            Language.SYSTEM -> return context.resources
            Language.ZH -> "zh-CN"
            Language.EN -> "en"
        }
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return context.createConfigurationContext(configuration).resources
}

/** The language the system would pick for this app on first run (design: 首次按系统语言). */
fun systemLanguage(context: Context): Language = if (context.resources.getString(R.string.lang_code) == "zh") Language.ZH else Language.EN

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int): String = LocalScResources.current.getString(id)

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int, vararg args: Any): String = LocalScResources.current.getString(id, *args)

fun Resources.isZh(): Boolean = getString(R.string.lang_code) == "zh"
