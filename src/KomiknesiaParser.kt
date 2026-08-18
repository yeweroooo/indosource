package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import tsuki.MangaLoaderContext
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder
import tsuki.network.CommonHeaders
import tsuki.util.await
import tsuki.util.generateUid
import tsuki.util.json.getStringOrNull
import tsuki.util.json.mapJSONNotNull
import tsuki.util.parseJson
import tsuki.util.parseJsonArray
import tsuki.util.toAbsoluteUrl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumSet
import java.util.Locale

/**
 * Komiknesia (https://komiknesia.id / https://v1.komiknesiaku.com) — JSON API source.
 *
 * The website is a React SPA (shell only, content fetched client-side) backed by a
 * Laravel-style JSON API at https://api-be.komiknesia.my.id/api/:
 *  - List/search:   GET /contents?page=N&per_page=24&q=...&genre[]={id}&status=Ongoing
 *                   &type=manga|manhua|manhwa|comic&orderBy=Az|Za|Update|Added|Popular
 *                   &project=true|false&source=kiryu|apkomik&popularWindow=day|week|month
 *  - Genres:        GET /contents/genres            ({id, name, slug}, filter by NUMERIC id)
 *  - Detail:        GET /manga/slug/{slug}          (full manga, ALL chapters inline)
 *  - Reader:        GET /chapters/{id}/images       (array of {id, image_path, page_number})
 *
 * The API requires an `Origin: https://v1.komiknesiaku.com` header (whitelist of the
 * frontend mirror domains; komiknesia.id itself is NOT accepted, and no Origin at all
 * gets "Access denied: direct API access is not permitted."). The device-id header the
 * frontend sends is optional. List pagination: per_page clamps to [10, 60], page clamps
 * to 200 (requesting page > 200 silently returns page 200 again — detect via meta.page
 * < requested page and stop). Reader images on data.cdnesia.my.id are hotlink-open.
 */
public class KomiknesiaParser(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.KOMIKNESIA,
    pageSize = 24,
) {
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(
        "v1.komiknesiaku.com",
        "komiknesia.id",
        "02.komiknesia.asia",
        "03.komiknesia.asia",
        "id.komiknesia.net",
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.ADDED,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_WEEK,
        SortOrder.POPULARITY_HOUR,
        SortOrder.POPULARITY_MONTH,
        SortOrder.RELEVANCE,
    )

    override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = true,
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add(CommonHeaders.USER_AGENT, config[userAgentKey])
        .add(CommonHeaders.ACCEPT, "application/json")
        .add(CommonHeaders.ORIGIN, ORIGIN_HEADER)
        .add(CommonHeaders.ACCEPT_LANGUAGE, "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        .build()

    // Belt and suspenders: inject the required Origin header at request time so the
    // API never sees a bare request (the app merges getRequestHeaders() into every
    // request for this source, but a direct interceptor covers all paths).
    // Manga covers are hotlinked from partner sites (01.apkomik.com, v6.kiryuu.to,
    // data.cdnesia.my.id, ...) whose hotlink protection rejects the app's default
    // Referer (the komiknesia frontend domain) with 403. Override the Referer with
    // a same-origin one for every non-API host — verified: apkomik covers 403 with
    // the komiknesia referer, 200 with https://01.apkomik.com/.
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()
        val host = request.url.host
        if (request.header(CommonHeaders.ORIGIN) == null) {
            builder.header(CommonHeaders.ORIGIN, ORIGIN_HEADER)
        }
        if (!host.equals(API_HOST, ignoreCase = true)) {
            builder.header(CommonHeaders.REFERER, "${request.url.scheme}://$host/")
        }
        return chain.proceed(builder.build())
    }

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val builder = "${API_BASE_URL}contents".toHttpUrl().newBuilder()
        builder.addQueryParameter("page", page.toString())
        builder.addQueryParameter("per_page", pageSize.toString())
        filter.query?.takeIf(String::isNotBlank)?.let {
            builder.addQueryParameter("q", it)
        }
        filter.tags.forEach { tag ->
            when (tag.key) {
                PROJECT_TAG_KEY_TRUE -> builder.addQueryParameter("project", "true")
                PROJECT_TAG_KEY_FALSE -> builder.addQueryParameter("project", "false")
                SOURCE_TAG_KEY_KIRYU -> builder.addQueryParameter("source", "kiryu")
                SOURCE_TAG_KEY_APKOMIK -> builder.addQueryParameter("source", "apkomik")
                else -> builder.addQueryParameter("genre[]", tag.key)
            }
        }
        filter.types.firstNotNullOfOrNull(::typeToSite)?.let {
            builder.addQueryParameter("type", it)
        }
        filter.states.firstNotNullOfOrNull(::stateToSite)?.let {
            builder.addQueryParameter("status", it)
        }
        orderToQuery(order)?.let { (orderBy, popularWindow) ->
            builder.addQueryParameter("orderBy", orderBy)
            popularWindow?.let { builder.addQueryParameter("popularWindow", it) }
        }

        val json = getJson(builder.build().toString())
        val data = json.optJSONArray("data") ?: return emptyList()
        if (data.length() == 0) return emptyList()
        // Server clamps page to 200; requesting beyond silently returns page 200 again.
        val meta = json.optJSONObject("meta")
        val returnedPage = meta?.getIntOrDefaultCompat("page", page) ?: page
        if (returnedPage < page) return emptyList()
        return buildList {
            for (i in 0 until data.length()) {
                parseManga(data.optJSONObject(i))?.let(::add)
            }
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.removeSuffix("/").substringAfterLast('/')
        if (slug.isBlank()) return manga
        val json = getJson("${API_BASE_URL}manga/slug/$slug")
        if (!json.has("id")) return manga

        val title = json.getStringOrNull("title") ?: manga.title
        val altTitles = json.getStringOrNull("alternative_name")
            ?.split(',', ';')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?.toSet()
            .orEmpty()
        val cover = json.getStringOrNull("thumbnail")
            ?: json.getStringOrNull("cover")
            ?: manga.coverUrl
        val tags = parseGenres(json.optJSONArray("genres"))
        val chapters = json.optJSONArray("chapters")
            ?.let { array -> parseChapters(array) }
            .orEmpty()
            .sortedBy { it.number }

        return manga.copy(
            title = title,
            altTitles = altTitles.ifEmpty { manga.altTitles },
            rating = parseRating(json),
            contentRating = parseContentRating(tags),
            coverUrl = cover,
            largeCoverUrl = cover,
            tags = tags.ifEmpty { manga.tags },
            state = parseState(json.getStringOrNull("status")),
            authors = setOfNotNull(json.getStringOrNull("author")).filter { it != "Unknown" }.toSet(),
            description = json.getStringOrNull("synopsis") ?: manga.description,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.removeSuffix("/").substringAfterLast('/')
            .toLongOrNull() ?: return emptyList()
        val array = getJsonArray("${API_BASE_URL}chapters/$chapterId/images")
        return array.mapJSONNotNull { item ->
            val url = item.optString("image_path").takeIf(String::isNotBlank) ?: return@mapJSONNotNull null
            MangaPage(
                id = generateUid(source, "${chapter.url}#${item.optInt("page_number", 0)}:$url"),
                url = url,
                preview = url,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = LinkedHashSet<MangaTag>()
        runCatching {
            val json = getJson("${API_BASE_URL}contents/genres")
            val data = json.optJSONArray("data") ?: return@runCatching
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val name = item.getStringOrNull("name")?.trim()?.takeIf(String::isNotEmpty) ?: continue
                val id = item.optLong("id", 0L)
                if (id <= 0L) continue
                tags.add(MangaTag(name, id.toString(), source))
            }
        }
        tags.add(MangaTag(PROJECT_TAG_TITLE_TRUE, PROJECT_TAG_KEY_TRUE, source))
        tags.add(MangaTag(PROJECT_TAG_TITLE_FALSE, PROJECT_TAG_KEY_FALSE, source))
        tags.add(MangaTag(SOURCE_TAG_TITLE_KIRYU, SOURCE_TAG_KEY_KIRYU, source))
        tags.add(MangaTag(SOURCE_TAG_TITLE_APKOMIK, SOURCE_TAG_KEY_APKOMIK, source))
        return MangaListFilterOptions(
            availableTags = tags.ifEmpty { fallbackTags() },
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.PAUSED,
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
                ContentType.COMICS,
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
        if (segments.size != 2 || segments.first() != MANGA_PATH) return null

        val relativeUrl = "/$MANGA_PATH/${segments[1]}/"
        val seed = Manga(
            id = generateUid(source, relativeUrl),
            title = titleFromUrl(relativeUrl),
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
        val slug = item.getStringOrNull("slug")?.takeIf(String::isNotBlank) ?: return null
        val title = item.getStringOrNull("title") ?: slug
        val relativeUrl = "/$MANGA_PATH/$slug/"
        val cover = item.getStringOrNull("cover")
            ?: item.getStringOrNull("thumbnail")

        return Manga(
            id = generateUid(source, relativeUrl),
            title = title,
            altTitles = setOfNotNull(item.getStringOrNull("alternative_name")),
            url = relativeUrl,
            publicUrl = relativeUrl.toAbsoluteUrl(domain),
            rating = parseRating(item),
            contentRating = parseContentRating(parseGenres(item.optJSONArray("genres"))),
            coverUrl = cover,
            tags = emptySet(),
            state = parseState(item.getStringOrNull("status")),
            authors = emptySet(),
            source = source,
        )
    }

    private fun parseChapters(array: JSONArray): List<MangaChapter> = buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optLong("id", 0L)
            if (id <= 0L) continue
            val title = item.getStringOrNull("title")
                ?: item.getStringOrNull("chapter_number")?.let { "Chapter $it" }
            val number = item.optString("chapter_number").toFloatOrNull() ?: 0f
            val uploadDate = parseChapterDate(item.getStringOrNull("created_at"))
            add(
                MangaChapter(
                    id = generateUid(source, id),
                    title = title,
                    number = number,
                    volume = 0,
                    url = "/view/$id",
                    scanlator = null,
                    uploadDate = uploadDate,
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
            val id = item.optLong("id", 0L)
            if (id > 0L) add(MangaTag(name, id.toString(), source))
        }
    }

    private fun orderToQuery(order: SortOrder): Pair<String, String?>? = when (order) {
        SortOrder.UPDATED -> "Update" to null
        SortOrder.NEWEST,
        SortOrder.ADDED,
        -> "Added" to null
        SortOrder.ALPHABETICAL -> "Az" to null
        SortOrder.ALPHABETICAL_DESC -> "Za" to null
        SortOrder.POPULARITY -> "Popular" to null
        SortOrder.POPULARITY_HOUR -> "Popular" to "day"
        SortOrder.POPULARITY_WEEK -> "Popular" to "week"
        SortOrder.POPULARITY_MONTH -> "Popular" to "month"
        // Search relevance: omit orderBy entirely (server default).
        SortOrder.RELEVANCE -> null
        else -> "Update" to null
    }

    private fun stateToSite(state: MangaState): String? = when (state) {
        MangaState.ONGOING -> "Ongoing"
        MangaState.FINISHED -> "Completed"
        MangaState.PAUSED -> "Hiatus"
        else -> null
    }

    private fun typeToSite(type: ContentType): String? = when (type) {
        ContentType.MANGA -> "manga"
        ContentType.MANHWA -> "manhwa"
        ContentType.MANHUA -> "manhua"
        ContentType.COMICS -> "comic"
        else -> null
    }

    private fun parseState(raw: String?): MangaState? {
        return when (raw?.lowercase(Locale.ROOT)) {
            "ongoing" -> MangaState.ONGOING
            "complete", "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "cancelled", "dropped" -> MangaState.ABANDONED
            else -> null
        }
    }

    private fun parseContentRating(tags: Set<MangaTag>): ContentRating? {
        if (tags.isEmpty()) return ContentRating.SAFE
        val titles = tags.mapTo(HashSet()) { it.title.lowercase(Locale.ROOT) }
        return when {
            titles.any { it in ADULT_GENRES } -> ContentRating.ADULT
            titles.any { it in SUGGESTIVE_GENRES } -> ContentRating.SUGGESTIVE
            else -> ContentRating.SAFE
        }
    }

    private fun parseRating(json: JSONObject): Float {
        val score = json.optDouble("rating", -1.0)
        if (score <= 0.0) return RATING_UNKNOWN
        return (score / 10.0).toFloat().coerceIn(0f, 1f)
    }

    private fun parseChapterDate(raw: String?): Long {
        val date = raw?.trim()?.takeIf(String::isNotEmpty) ?: return 0L
        return DATE_FORMATS.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).parse(date)?.time
            }.getOrNull()
        } ?: 0L
    }

    private fun titleFromUrl(url: String): String = url
        .substringBefore('?')
        .trimEnd('/')
        .substringAfterLast('/')

    private suspend fun getJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .addHeader(CommonHeaders.ACCEPT, "application/json")
            .addHeader(CommonHeaders.ORIGIN, ORIGIN_HEADER)
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
            .addHeader(CommonHeaders.ORIGIN, ORIGIN_HEADER)
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

    private fun fallbackTags(): Set<MangaTag> = buildSet {
        FALLBACK_GENRES.forEach { (name, id) -> add(MangaTag(name, id, source)) }
        add(MangaTag(PROJECT_TAG_TITLE_TRUE, PROJECT_TAG_KEY_TRUE, source))
        add(MangaTag(PROJECT_TAG_TITLE_FALSE, PROJECT_TAG_KEY_FALSE, source))
        add(MangaTag(SOURCE_TAG_TITLE_KIRYU, SOURCE_TAG_KEY_KIRYU, source))
        add(MangaTag(SOURCE_TAG_TITLE_APKOMIK, SOURCE_TAG_KEY_APKOMIK, source))
    }

    private companion object {
        private const val API_BASE_URL = "https://api-be.komiknesia.my.id/api/"
        private const val API_HOST = "api-be.komiknesia.my.id"
        private const val ORIGIN_HEADER = "https://v1.komiknesiaku.com"
        private const val MANGA_PATH = "komik"

        private const val PROJECT_TAG_KEY_TRUE = "project"
        private const val PROJECT_TAG_TITLE_TRUE = "Project"
        private const val PROJECT_TAG_KEY_FALSE = "non-project"
        private const val PROJECT_TAG_TITLE_FALSE = "Bukan Project"
        private const val SOURCE_TAG_KEY_KIRYU = "kiryu"
        private const val SOURCE_TAG_TITLE_KIRYU = "Source 1 (Kiryuu)"
        private const val SOURCE_TAG_KEY_APKOMIK = "apkomik"
        private const val SOURCE_TAG_TITLE_APKOMIK = "Source 2 (ApKomik)"

        private val ADULT_GENRES = setOf("adult", "smut", "mature", "yaoi", "yuri", "shotacon")
        private val SUGGESTIVE_GENRES = setOf("ecchi")

        private val DATE_FORMATS = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
        )

        private val FALLBACK_GENRES = listOf(
            "Action" to "2",
            "Romance" to "58",
            "Fantasy" to "16",
            "Comedy" to "6",
            "Adventure" to "4",
            "Drama" to "13",
            "Harem" to "24",
            "Horror" to "26",
            "Isekai" to "27",
            "Manhua" to "34",
            "Manhwa" to "35",
            "Mystery" to "46",
            "School Life" to "60",
            "Sci-fi" to "61",
            "Seinen" to "62",
            "Shounen" to "67",
            "Slice of Life" to "69",
            "Supernatural" to "73",
        )
    }
}

private fun JSONObject.getIntOrDefaultCompat(name: String, defaultValue: Int): Int {
    val value = opt(name) ?: return defaultValue
    return when (value) {
        is Int -> value
        is Long -> value.toInt()
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: defaultValue
        else -> defaultValue
    }
}
