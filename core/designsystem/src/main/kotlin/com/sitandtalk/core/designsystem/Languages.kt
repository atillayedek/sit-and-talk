package com.sitandtalk.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Languages offered for profiles, matching and rooms (ISO 639-1). */
val SupportedLanguages = listOf("tr", "en", "de", "fr", "es", "ar", "ru", "az", "ku", "it", "nl", "pt", "ja", "ko")

@Composable
fun languageLabel(code: String): String = when (code) {
    "tr" -> stringResource(R.string.ds_lang_tr)
    "en" -> stringResource(R.string.ds_lang_en)
    "de" -> stringResource(R.string.ds_lang_de)
    "fr" -> stringResource(R.string.ds_lang_fr)
    "es" -> stringResource(R.string.ds_lang_es)
    "ar" -> stringResource(R.string.ds_lang_ar)
    "ru" -> stringResource(R.string.ds_lang_ru)
    "az" -> stringResource(R.string.ds_lang_az)
    "ku" -> stringResource(R.string.ds_lang_ku)
    "it" -> stringResource(R.string.ds_lang_it)
    "nl" -> stringResource(R.string.ds_lang_nl)
    "pt" -> stringResource(R.string.ds_lang_pt)
    "ja" -> stringResource(R.string.ds_lang_ja)
    "ko" -> stringResource(R.string.ds_lang_ko)
    else -> code.uppercase()
}

/** UI language, read observably so a locale change recomposes. */
@Composable
fun currentLanguage(): String =
    androidx.compose.ui.platform.LocalConfiguration.current.locales.get(0)?.language ?: "tr"

@Composable
fun interestLabel(interest: com.sitandtalk.core.model.Interest): String =
    if (currentLanguage() == "tr") interest.nameTr else interest.nameEn

@Composable
fun interestLabel(catalog: List<com.sitandtalk.core.model.Interest>, slug: String?): String? {
    if (slug == null) return null
    val interest = catalog.firstOrNull { it.slug == slug } ?: return slug
    return interestLabel(interest)
}
