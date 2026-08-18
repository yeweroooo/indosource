package org.koitharu.kotatsu.parsers.site.id.legacy

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.network.CommonHeaders
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

/**
 * Ainz Scans ID (https://v3.ainzscans01.com) — plain JSON API source.
 *
 * The site migrated from WordPress (mangareader theme) to a SvelteKit SPA backed
 * by an open JSON API (no auth, no signature, no Cloudflare block):
 *  - Browse/search/filter: GET /api/search?type=COMIC&limit=30&page=N
 *      &sort=latest|created|views|rating|az&order=asc|desc&q={query}
 *      &genre={slug}&status=ONGOING|COMPLETED|HIATUS&comic_type=MANGA|MANHWA|MANHUA
 *      &color_format=full_color|bw&reading_format=vertical_scroll|page
 *      &author=&artist=&publisher=
 *      -> {data: [manga], page, limit, total, total_pages}
 *  - Genres:         GET /api/genres              -> [{slug, name, ...}] (53 genres)
 *  - Detail:         GET /api/series/comic/{slug} -> full manga + ALL units (chapters)
 *                                                    inline (newest first) + genres
 *  - Reader:         GET /api/series/comic/{slug}/chapter/{unitSlug}
 *                    -> {series, chapter: {pages: [{page_number, image_url}]},
 *                        units (all), previous_chapter, next_chapter}
 *
 * Verified (Aug 2026): single `genre` param only — multi genre[] -> HTTP 500.
 * Chapter unit slugs sometimes carry a random suffix (`chapter-46785247`) — always
 * use the slug from the API. Chapter image CDN rotates (cdn.uqni.net / yuucdn.com)
 * but URLs are absolute in the JSON, so no referer/header work is needed.
 * rating_average is a STRING ("8.00", scale 0-10); number is a STRING ("341.00").
 */
public class AinzScansParser(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.AINZSCANS,
    pageSize = 30,
) {
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(
        "v3.ainzscans01.com",
        "ainzscans01.com",
        "ainzscans.net",
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
    )

    override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
        // The API returns HTTP 500 for multiple genre params — single tag only.
        isMultipleTagsSupported = false,
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val builder = (apiBaseUrl() + SEARCH_PATH).toHttpUrl().newBuilder()
        builder.addQueryParameter("type", "COMIC")
        builder.addQueryParameter("limit", pageSize.toString())
        builder.addQueryParameter("page", page.toString())
        filter.query?.takeIf(String::isNotBlank)?.let { builder.addQueryParameter("q", it) }
        // Single genre: the first non-synthetic tag. Synthetic color/reading tags
        // map to their own query params.
        for (tag in filter.tags) {
            when {
                tag.key.startsWith(COLOR_TAG_PREFIX) ->
                    builder.addQueryParameter("color_format", tag.key.removePrefix(COLOR_TAG_PREFIX))
                tag.key.startsWith(READING_TAG_PREFIX) ->
                    builder.addQueryParameter("reading_format", tag.key.removePrefix(READING_TAG_PREFIX))
                else -> {
                    builder.addQueryParameter("genre", tag.key)
                    break
                }
            }
        }
        filter.states.firstNotNullOfOrNull(::stateToSite)?.let {
            builder.addQueryParameter("status", it)
        }
        filter.types.firstNotNullOfOrNull(::typeToSite)?.let {
            builder.addQueryParameter("comic_type", it)
        }
        orderToQuery(order)?.let { (sort, dir) ->
            builder.addQueryParameter("sort", sort)
            builder.addQueryParameter("order", dir)
        }

        val json = getJson(builder.build().toString())
        val data = json.optJSONArray("data") ?: return emptyList()
        // Stop pagination at the end of the catalog.
        val totalPages = json.optInt("total_pages", 0)
        if (totalPages in 1 until page) return emptyList()
        return data.mapJSONNotNull { parseManga(it) }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.removeSuffix("/").substringAfterLast('/')
        if (slug.isBlank()) return manga
        val json = getJson("${apiBaseUrl()}series/comic/$slug")
        if (!json.has("id")) return manga

        val title = json.getStringOrNull("title") ?: manga.title
        val altTitles = parseAltTitles(json.getStringOrNull("alternative_titles"))
        val cover = parseCoverUrl(json.getStringOrNull("poster_image_url")) ?: manga.coverUrl
        val tags = parseGenres(json.optJSONArray("genres"))
        val chapters = json.optJSONArray("units")
            ?.let { array -> parseChapters(slug, array) }
            .orEmpty()
            .sortedBy { it.number }
        val isAdult = json.optInt("is_adult", 0) == 1

        return manga.copy(
            title = title,
            altTitles = altTitles.ifEmpty { manga.altTitles },
            rating = parseRating(json.getStringOrNull("rating_average")),
            contentRating = if (isAdult) ContentRating.ADULT else sourceContentRating,
            coverUrl = cover,
            largeCoverUrl = cover,
            tags = tags.ifEmpty { manga.tags },
            state = parseState(json.getStringOrNull("comic_status")),
            authors = parseAuthors(json),
            description = json.getStringOrNull("synopsis") ?: manga.description,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val path = chapter.url
        if (!path.startsWith(SERIES_PATH)) return emptyList()
        val json = getJson(apiBaseUrl() + path.removePrefix("/"))
        val data = json.optJSONObject("chapter") ?: return emptyList()
        val pages = data.optJSONArray("pages") ?: return emptyList()
        return buildList {
            for (i in 0 until pages.length()) {
                val item = pages.optJSONObject(i) ?: continue
                val url = item.getStringOrNull("image_url")?.takeIf(String::isNotBlank) ?: continue
                add(
                    MangaPage(
                        id = generateUid(source, "$path#$i:$url"),
                        url = url,
                        preview = url,
                        source = source,
                    ),
                )
            }
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = LinkedHashSet<MangaTag>()
        runCatching {
            val json = getJsonArray(apiBaseUrl() + "genres")
            for (i in 0 until json.length()) {
                val item = json.optJSONObject(i) ?: continue
                val name = item.getStringOrNull("name")?.trim()?.takeIf(String::isNotEmpty) ?: continue
                val slug = item.getStringOrNull("slug")?.trim()?.takeIf(String::isNotEmpty) ?: continue
                tags.add(MangaTag(name, slug, source))
            }
        }
        if (tags.isEmpty()) {
            GENRES.forEach { (slug, name) -> tags.add(MangaTag(name, slug, source)) }
        }
        tags.add(MangaTag(COLOR_TAG_TITLE_COLORED, COLOR_TAG_KEY_COLORED, source))
        tags.add(MangaTag(COLOR_TAG_TITLE_BW, COLOR_TAG_KEY_BW, source))
        tags.add(MangaTag(READING_TAG_TITLE_VERTICAL, READING_TAG_KEY_VERTICAL, source))
        tags.add(MangaTag(READING_TAG_TITLE_PAGE, READING_TAG_KEY_PAGE, source))
        return MangaListFilterOptions(
            availableTags = tags,
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.PAUSED,
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
            ),
            availableContentRating = EnumSet.of(
                ContentRating.SAFE,
                ContentRating.SUGGESTIVE,
                ContentRating.ADULT,
            ),
            availableLocales = setOf(Locale("id")),
        )
    }

    override suspend fun resolveLink(link: HttpUrl): Manga? {
        val allowedHosts = configKeyDomain.presetValues.toSet()
        if (link.host !in allowedHosts) return null
        val segments = link.pathSegments.filter(String::isNotBlank)
        if (segments.size != 2 || segments.first() != COMIC_PATH) return null
        val slug = segments[1]
        val relativeUrl = "/$COMIC_PATH/$slug"
        val seed = Manga(
            id = generateUid(source, relativeUrl),
            title = titleFromSlug(slug),
            altTitles = emptySet(),
            url = relativeUrl,
            publicUrl = relativeUrl.toAbsoluteUrl(domain),
            rating = RATING_UNKNOWN,
            contentRating = sourceContentRating,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
        return getDetails(seed)
    }

    private fun parseManga(item: JSONObject?): Manga? {
        if (item == null) return null
        val slug = item.getStringOrNull("slug")?.trim()?.takeIf(String::isNotEmpty).orEmpty()
        val title = item.getStringOrNull("title")?.trim()?.takeIf(String::isNotEmpty) ?: slug
        if (slug.isEmpty() || title.isEmpty()) return null
        val relativeUrl = "/$COMIC_PATH/$slug"
        return Manga(
            id = generateUid(source, relativeUrl),
            title = title,
            altTitles = parseAltTitles(item.getStringOrNull("alternative_titles")),
            url = relativeUrl,
            publicUrl = relativeUrl.toAbsoluteUrl(domain),
            rating = parseRating(item.getStringOrNull("rating_average")),
            contentRating = sourceContentRating,
            coverUrl = parseCoverUrl(item.getStringOrNull("poster_image_url")),
            tags = emptySet(),
            state = parseState(item.getStringOrNull("comic_status")),
            authors = emptySet(),
            source = source,
        )
    }

    private fun parseChapters(slug: String, array: JSONArray): List<MangaChapter> = buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val unitSlug = item.getStringOrNull("slug")?.trim().orEmpty()
            val number = item.getStringOrNull("number")?.toFloatOrNull()
            if (unitSlug.isEmpty() || number == null) continue
            val title = item.getStringOrNull("title")?.trim()
                ?.takeIf(String::isNotEmpty) ?: "Chapter ${number.trimZero()}"
            val relativeUrl = "$SERIES_PATH$slug/chapter/$unitSlug"
            add(
                MangaChapter(
                    id = generateUid(source, relativeUrl),
                    title = title,
                    number = number,
                    volume = 0,
                    url = relativeUrl,
                    scanlator = null,
                    uploadDate = parseChapterDate(item.getStringOrNull("created_at")),
                    branch = null,
                    source = source,
                ),
            )
        }
    }

    private fun parseGenres(array: JSONArray?): Set<MangaTag> = buildSet {
        if (array == null) return@buildSet
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.getStringOrNull("name")?.trim()?.takeIf(String::isNotEmpty) ?: continue
            val slug = item.getStringOrNull("slug")?.trim()?.takeIf(String::isNotEmpty) ?: continue
            add(MangaTag(name, slug, source))
        }
    }

    private fun parseAltTitles(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val trimmed = raw.trim()
        return if (trimmed.startsWith('[')) {
            // The API stores alternative titles as a JSON-ENCODED string:
            // "[\"Alt One\", \"Alt Two\"]"
            buildSet {
                val array = runCatching { JSONArray(trimmed) }.getOrNull() ?: return@buildSet
                for (i in 0 until array.length()) {
                    val title = array.optString(i).trim().takeIf(String::isNotEmpty) ?: continue
                    add(title)
                }
            }
        } else {
            trimmed.split(',', ';')
                .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .toSet()
        }
    }

    private fun parseAuthors(json: JSONObject): Set<String> = buildSet {
        json.getStringOrNull("author_name")?.let { addNames(it) }
        json.getStringOrNull("artist_name")?.let { addNames(it) }
        val uploaders = json.optJSONArray("uploaders") ?: return@buildSet
        for (i in 0 until uploaders.length()) {
            val username = uploaders.optJSONObject(i)?.getStringOrNull("username")?.trim()
                ?.takeIf(String::isNotEmpty) ?: continue
            add(username)
        }
    }

    private fun MutableSet<String>.addNames(raw: String) {
        raw.split(',', ';')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .filter { it != "Unknown" }
            .forEach(::add)
    }

    private fun parseCoverUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // Legacy WordPress CDN host (ainzscans01.com/wp-content/...) — every cover
        // there returns 404/403 since the platform migration (verified Aug 2026:
        // all 183 covers on this host in the 3591-series catalog are dead; api. and
        // adminv2. hosts are fine). Emit null so the app shows its placeholder
        // instead of a broken card image.
        val host = raw.substringAfter("://", "").substringBefore('/')
        if (host == LEGACY_COVER_HOST) return null
        return raw
    }

    private fun parseState(raw: String?): MangaState? = when (raw?.uppercase(Locale.ROOT)) {
        "ONGOING" -> MangaState.ONGOING
        "COMPLETED" -> MangaState.FINISHED
        "HIATUS" -> MangaState.PAUSED
        "DROPPED", "CANCELLED" -> MangaState.ABANDONED
        else -> null
    }

    private fun stateToSite(state: MangaState): String? = when (state) {
        MangaState.ONGOING -> "ONGOING"
        MangaState.FINISHED -> "COMPLETED"
        MangaState.PAUSED -> "HIATUS"
        MangaState.ABANDONED -> "DROPPED"
        else -> null
    }

    private fun typeToSite(type: ContentType): String? = when (type) {
        ContentType.MANGA -> "MANGA"
        ContentType.MANHWA -> "MANHWA"
        ContentType.MANHUA -> "MANHUA"
        else -> null
    }

    private fun orderToQuery(order: SortOrder): Pair<String, String>? = when (order) {
        SortOrder.UPDATED -> "latest" to "desc"
        SortOrder.NEWEST -> "created" to "desc"
        SortOrder.POPULARITY -> "views" to "desc"
        SortOrder.RATING -> "rating" to "desc"
        SortOrder.ALPHABETICAL -> "az" to "asc"
        SortOrder.ALPHABETICAL_DESC -> "az" to "desc"
        else -> null
    }

    private fun parseRating(raw: String?): Float {
        val score = raw?.toFloatOrNull() ?: return RATING_UNKNOWN
        if (score <= 0f) return RATING_UNKNOWN
        return (score / 10f).coerceIn(0f, 1f)
    }

    private fun parseChapterDate(raw: String?): Long {
        val date = raw?.trim()?.takeIf(String::isNotEmpty) ?: return 0L
        return DATE_FORMATS.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).parse(date)?.time
            }.getOrNull()
        } ?: 0L
    }

    private fun Float.trimZero(): String {
        if (this % 1f == 0f) return toInt().toString()
        return toString()
    }

    private fun titleFromSlug(slug: String): String = slug
        .replace('-', ' ')
        .split(' ')
        .joinToString(" ") { part ->
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }

    private suspend fun getJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .addHeader(CommonHeaders.ACCEPT, "application/json")
            .tag(MangaSource::class.java, source)
            .get()
            .build()
        val response = context.httpClient.newCall(request).await()
        if (response.code in 400..599) {
            val exception = org.jsoup.HttpStatusException(
                response.message,
                response.code,
                response.request.url.toString(),
            )
            response.close()
            throw exception
        }
        return response.parseJson()
    }

    private suspend fun getJsonArray(url: String): JSONArray {
        val request = Request.Builder()
            .url(url)
            .addHeader(CommonHeaders.ACCEPT, "application/json")
            .tag(MangaSource::class.java, source)
            .get()
            .build()
        val response = context.httpClient.newCall(request).await()
        if (response.code in 400..599) {
            val exception = org.jsoup.HttpStatusException(
                response.message,
                response.code,
                response.request.url.toString(),
            )
            response.close()
            throw exception
        }
        return response.parseJsonArray()
    }

    private fun apiBaseUrl(): String = "https://$domain/api/"

    private companion object {
        private const val COMIC_PATH = "comic"
        private const val SEARCH_PATH = "search"
        private const val SERIES_PATH = "series/comic/"
        private const val LEGACY_COVER_HOST = "ainzscans01.com"

        private const val COLOR_TAG_PREFIX = "color:"
        private const val COLOR_TAG_KEY_COLORED = "color:full_color"
        private const val COLOR_TAG_KEY_BW = "color:bw"
        private const val COLOR_TAG_TITLE_COLORED = "Full Color"
        private const val COLOR_TAG_TITLE_BW = "B&W"

        private const val READING_TAG_PREFIX = "reading:"
        private const val READING_TAG_KEY_VERTICAL = "reading:vertical_scroll"
        private const val READING_TAG_KEY_PAGE = "reading:page"
        private const val READING_TAG_TITLE_VERTICAL = "Vertical Scroll"
        private const val READING_TAG_TITLE_PAGE = "Page"

        private val DATE_FORMATS = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        )

        // Fallback list (live /api/genres preferred); verified Aug 2026.
        private val GENRES = listOf(
            "action" to "Action",
            "adult" to "Adult",
            "adventure" to "Adventure",
            "award-winning" to "Award Winning",
            "beasts" to "Beasts",
            "comedy" to "Comedy",
            "cooking" to "Cooking",
            "crime" to "Crime",
            "drama" to "Drama",
            "ecchi" to "Ecchi",
            "fantasy" to "Fantasy",
            "gender-bender" to "Gender Bender",
            "gore" to "Gore",
            "harem" to "Harem",
            "historical" to "Historical",
            "horror" to "Horror",
            "isekai" to "Isekai",
            "josei" to "Josei",
            "long-strip" to "Long Strip",
            "magic" to "Magic",
            "manga" to "Manga",
            "manhwa" to "Manhwa",
            "martial-art" to "Martial Art",
            "martial-arts" to "Martial Arts",
            "mature" to "Mature",
            "mecha" to "Mecha",
            "medical" to "Medical",
            "military" to "Military",
            "monsters" to "Monsters",
            "murim" to "Murim",
            "music" to "Music",
            "mystery" to "Mystery",
            "psychological" to "Psychological",
            "regression" to "Regression",
            "reincarnation" to "Reincarnation",
            "romance" to "Romance",
            "school-life" to "School Life",
            "sci-fi" to "Sci Fi",
            "seinen" to "Seinen",
            "shotacon" to "Shotacon",
            "shoujo" to "Shoujo",
            "shounen" to "Shounen",
            "shounen-ai" to "Shounen Ai",
            "slice-of-life" to "Slice Of Life",
            "smut" to "Smut",
            "sports" to "Sports",
            "supernatural" to "Supernatural",
            "survival" to "Survival",
            "suspense" to "Suspense",
            "system" to "System",
            "thriller" to "Thriller",
            "tragedy" to "Tragedy",
            "wuxia" to "Wuxia",
        )
    }
}
