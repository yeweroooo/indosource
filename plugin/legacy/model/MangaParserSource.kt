package org.koitharu.kotatsu.parsers.model

/**
 * Legacy plugin source list (org.koitharu style) for Usagi builds before the
 * Tsuki migration (0.0.31 and older). Ship inside the plugin jar — the app
 * enumerates every constant as a selectable manga source.
 */
public enum class MangaParserSource(
    override val title: String,
    override val locale: String,
    override val contentType: ContentType,
    override val isBroken: Boolean,
) : MangaSource {
    MGKOMIK("MG Komik", "id", ContentType.MANGA, false),
    SHINIGAMI("Shinigami", "id", ContentType.MANGA, false),
    KIRYUU("Kiryuu", "id", ContentType.MANGA, false),
    APKOMIK("APKOMIK", "id", ContentType.MANGA, false),
    VORATOON("Voratoon", "id", ContentType.MANGA, false),
    IKIRU("IKIRU", "id", ContentType.MANGA, false),
    COSMICTOONS("Cosmic Toons", "id", ContentType.MANGA, false),
    KOMIKNESIA("Komiknesia", "id", ContentType.MANGA, false),
    WESTMANGA("Westmanga", "id", ContentType.MANGA, false),
    AINZSCANS("Ainz Scans", "id", ContentType.MANGA, false),
    LUMOSKOMIK("LumosKomik", "id", ContentType.MANGA, false),
}