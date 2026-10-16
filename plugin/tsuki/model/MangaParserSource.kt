package tsuki.model

/**
 * Plugin source list. Ship this enum inside the plugin jar — the app
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
    MANGADEX("MangaDex", "all", ContentType.MANGA, false),
    ASURASCANS("Asura Scans", "en", ContentType.MANGA, false),
}