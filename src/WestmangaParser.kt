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
import tsuki.util.toAbsoluteUrl
import java.util.EnumSet
import java.util.Locale

/**
 * Westmanga (web app https://v1.westmanga.top — v1.westmanga.my and
 * westmanga.site are mirrors) — JSON API source, same-origin with the frontend.
 *
 * The old separate API host data.mantweh.online now serves a Cloudflare block
 * page; the API moved to the web frontend itself:
 *  - List/search/filter: GET /api/contents?page=N&per_page=25&project=false
 *                   &q={query}&genre[]={id}&status=Ongoing|Completed|Hiatus
 *                   &type=Comic|Novel&country=JP|KR|CN&orderBy=Az|Za|Update|Added|Popular
 *                   &color=Colored|Uncolored   (server clamps per_page to 25)
 *  - Genres:        GET /api/contents/genres        ({id, name}, filter by NUMERIC id)
 *  - Detail:        GET /api/comic/{slug}           (full manga, ALL chapters inline)
 *  - Reader:        GET /api/v/{chapterSlug}        (data.images[] = /img/... paths)
 *
 * Bot gate ("Halamannya perlu dimuat ulang dulu ya." 403): before API calls,
 * fetch GET /api/visit-token -> {"token": "v1....", "expires_at": <unix>} and
 * send it as the `X-WM-Visit` header on every API request (cached 300 s; on a
 * 403 carrying an X-WM-Visit-Error response header the token is refetched once
 * and the request retried). The old x-wm-request-* HMAC headers are no longer
 * required. Covers and reader images are relative /img/... paths on the same
 * host and are hotlink-open (no Referer needed).
 */
public class WestmangaParser(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.WESTMANGA,
    pageSize = 25,
) {
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(
        "v1.westmanga.top",
        "v1.westmanga.my",
        "westmanga.site",
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.ADDED,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_HOUR,
        SortOrder.POPULARITY_WEEK,
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

    override fun getRequestHeaders(): Headers = baseHeaders()

    // Make sure image/CDN requests carry the site Referer (harmless when the
    // image host ignores it, required by hotlink-protected hosts).
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(CommonHeaders.REFERER) == null && !request.url.encodedPath.startsWith("/api/")) {
            return chain.proceed(
                request.newBuilder()
                    .header(CommonHeaders.REFERER, frontendUrl())
                    .build(),
            )
        }
        return chain.proceed(request)
    }

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val builder = "${apiBaseUrl()}contents".toHttpUrl().newBuilder()
        builder.addQueryParameter("page", page.toString())
        builder.addQueryParameter("per_page", pageSize.toString())
        builder.addQueryParameter("project", "false")
        filter.query?.takeIf(String::isNotBlank)?.let {
            builder.addQueryParameter("q", it)
        }
        filter.tags.forEach { tag ->
            when (tag.key) {
                COLOR_TAG_KEY_COLORED -> builder.addQueryParameter("color", "Colored")
                COLOR_TAG_KEY_UNCOLORED -> builder.addQueryParameter("color", "Uncolored")
                else -> builder.addQueryParameter("genre[]", tag.key)
            }
        }
        filter.types.firstNotNullOfOrNull(::typeToSite)?.let {
            builder.addQueryParameter("type", it)
        }
        filter.types.firstNotNullOfOrNull(::countryToSite)?.let {
            builder.addQueryParameter("country", it)
        }
        filter.states.firstNotNullOfOrNull(::stateToSite)?.let {
            builder.addQueryParameter("status", it)
        }
        orderToQuery(order)?.let {
            builder.addQueryParameter("orderBy", it)
        }

        val json = getJson(builder.build().toString())
        val data = json.optJSONArray("data") ?: return emptyList()
        if (data.length() == 0) return emptyList()
        // Server paginates; stop when the API returns an earlier page than asked.
        val paginator = json.optJSONObject("paginator")
        val currentPage = paginator?.getIntOrDefaultCompat("current_page", page) ?: page
        if (currentPage < page) return emptyList()
        return data.mapJSONNotNull { parseManga(it) }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.removeSuffix("/").substringAfterLast('/')
        if (slug.isBlank()) return manga
        val json = getJson("${apiBaseUrl()}comic/$slug")
        val data = json.optJSONObject("data") ?: return manga
        if (!data.has("id")) return manga

        val title = data.getStringOrNull("title") ?: manga.title
        val altTitles = data.getStringOrNull("alternative_name")
            ?.split(',', ';')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?.toSet()
            .orEmpty()
        val cover = absUrl(data.getStringOrNull("cover")) ?: manga.coverUrl
        val tags = parseGenres(data.optJSONArray("genres"))
        val chapters = data.optJSONArray("chapters")
            ?.let { array -> parseChapters(array) }
            .orEmpty()
            .sortedBy { it.number }

        return manga.copy(
            title = title,
            altTitles = altTitles.ifEmpty { manga.altTitles },
            rating = parseRating(data),
            contentRating = parseContentRating(tags, data),
            coverUrl = cover,
            largeCoverUrl = cover,
            tags = tags.ifEmpty { manga.tags },
            state = parseState(data.getStringOrNull("status")),
            authors = setOfNotNull(data.getStringOrNull("author"))
                .filter { it != "Unknown" }
                .toSet(),
            description = data.getStringOrNull("sinopsis") ?: manga.description,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterSlug = chapter.url.removeSuffix("/").substringAfterLast('/')
            .takeIf(String::isNotBlank) ?: return emptyList()
        val json = getJson("${apiBaseUrl()}v/$chapterSlug")
        val data = json.optJSONObject("data") ?: return emptyList()
        val images = data.optJSONArray("images") ?: return emptyList()
        return buildList {
            for (i in 0 until images.length()) {
                val url = absUrl(images.optString(i)) ?: continue
                add(
                    MangaPage(
                        id = generateUid(source, "$chapterSlug#$i:$url"),
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
            val json = getJson("${apiBaseUrl()}contents/genres")
            val data = json.optJSONArray("data") ?: return@runCatching
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val name = item.getStringOrNull("name")?.trim()?.takeIf(String::isNotEmpty) ?: continue
                val id = item.optLong("id", 0L)
                if (id <= 0L) continue
                tags.add(MangaTag(name, id.toString(), source))
            }
        }
        tags.add(MangaTag(COLOR_TAG_TITLE_COLORED, COLOR_TAG_KEY_COLORED, source))
        tags.add(MangaTag(COLOR_TAG_TITLE_UNCOLORED, COLOR_TAG_KEY_UNCOLORED, source))
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
                ContentType.NOVEL,
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

    private fun parseManga(item: JSONObject): Manga? {
        val slug = item.getStringOrNull("slug")?.takeIf(String::isNotBlank) ?: return null
        val title = item.getStringOrNull("title") ?: slug
        val relativeUrl = "/$MANGA_PATH/$slug/"
        val cover = absUrl(item.getStringOrNull("cover"))

        return Manga(
            id = generateUid(source, relativeUrl),
            title = title,
            altTitles = emptySet(),
            url = relativeUrl,
            publicUrl = relativeUrl.toAbsoluteUrl(domain),
            rating = parseRating(item),
            contentRating = parseContentRating(emptySet(), item),
            coverUrl = cover,
            largeCoverUrl = cover,
            tags = emptySet(),
            state = parseState(item.getStringOrNull("status")),
            authors = emptySet(),
            source = source,
        )
    }

    private fun parseChapters(array: JSONArray): List<MangaChapter> = buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val slug = item.getStringOrNull("slug")?.takeIf(String::isNotBlank) ?: continue
            val title = item.getStringOrNull("title")
                ?: item.getStringOrNull("number")?.let { "Chapter $it" }
            val number = item.getStringOrNull("number")?.toFloatOrNull() ?: 0f
            // API time is unix SECONDS; MangaChapter.uploadDate is MILLISECONDS
            val uploadDate = (
                item.optJSONObject("created_at")?.optLong("time", 0L)
                    ?: item.optLong("created_at", 0L)
                ) * 1000L
            add(
                MangaChapter(
                    id = generateUid(source, slug),
                    title = title,
                    number = number,
                    volume = 0,
                    url = "/view/$slug",
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

    private fun orderToQuery(order: SortOrder): String? = when (order) {
        SortOrder.UPDATED -> "Update"
        SortOrder.NEWEST,
        SortOrder.ADDED,
        -> "Added"
        SortOrder.ALPHABETICAL -> "Az"
        SortOrder.ALPHABETICAL_DESC -> "Za"
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_HOUR,
        SortOrder.POPULARITY_WEEK,
        SortOrder.POPULARITY_MONTH,
        -> "Popular"
        // Search relevance: omit orderBy entirely (server default).
        SortOrder.RELEVANCE -> null
        else -> "Update"
    }

    // "Content type" dimension: Comic | Novel  ->  type=...
    private fun typeToSite(type: ContentType): String? = when (type) {
        ContentType.COMICS -> "Comic"
        ContentType.NOVEL -> "Novel"
        else -> null
    }

    // "Type" dimension (origin country): Manga=JP, Manhua=CN, Manhwa=KR  ->  country=...
    private fun countryToSite(type: ContentType): String? = when (type) {
        ContentType.MANGA -> "JP"
        ContentType.MANHUA -> "CN"
        ContentType.MANHWA -> "KR"
        else -> null
    }

    private fun stateToSite(state: MangaState): String? = when (state) {
        MangaState.ONGOING -> "Ongoing"
        MangaState.FINISHED -> "Completed"
        MangaState.PAUSED -> "Hiatus"
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

    private fun parseContentRating(tags: Set<MangaTag>, json: JSONObject): ContentRating? {
        val isSafe = json.optBoolean("is_safe", true)
        if (isSafe && tags.isEmpty()) return ContentRating.SAFE
        val titles = tags.mapTo(HashSet()) { it.title.lowercase(Locale.ROOT) }
        return when {
            titles.any { it in ADULT_GENRES } -> ContentRating.ADULT
            titles.any { it in SUGGESTIVE_GENRES } -> ContentRating.SUGGESTIVE
            !isSafe && titles.isNotEmpty() -> ContentRating.SUGGESTIVE
            else -> ContentRating.SAFE
        }
    }

    private fun parseRating(json: JSONObject): Float {
        val score = json.optDouble("rating", -1.0)
        if (score <= 0.0) return RATING_UNKNOWN
        return (score / 10.0).toFloat().coerceIn(0f, 1f)
    }

    private fun titleFromUrl(url: String): String = url
        .substringBefore('?')
        .trimEnd('/')
        .substringAfterLast('/')

    // Covers and reader images come back as relative /img/... paths.
    private fun absUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            "https://$domain$path"
        }
    }

    private fun baseHeaders(): Headers = Headers.Builder()
        .add(CommonHeaders.USER_AGENT, config[userAgentKey])
        .add(CommonHeaders.ACCEPT, "application/json")
        .add(CommonHeaders.ACCEPT_LANGUAGE, "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        .add(CommonHeaders.REFERER, frontendUrl())
        .build()

    private fun frontendUrl(): String = "https://$domain/"

    private fun apiBaseUrl(): String = "https://$domain/api/"

    // --- X-WM-Visit bot-gate token (cached 300 s, like the web app) ---
    @Volatile
    private var visitToken: String? = null

    @Volatile
    private var visitTokenAt: Long = 0L

    private suspend fun currentVisitToken(forceRefresh: Boolean = false): String {
        val nowSec = System.currentTimeMillis() / 1000
        val cached = visitToken
        if (!forceRefresh && cached != null && nowSec - visitTokenAt < 300) return cached
        val request = Request.Builder()
            .url("${apiBaseUrl()}visit-token")
            .headers(baseHeaders())
            .tag(MangaSource::class.java, source)
            .get()
            .build()
        val response = context.httpClient.newCall(request).await()
        if (response.code in 400..599) {
            val exception = org.jsoup.HttpStatusException(
                response.message, response.code, response.request.url.toString(),
            )
            response.close()
            throw exception
        }
        val token = response.parseJson().optString("token").takeIf(String::isNotBlank)
        response.close()
        visitToken = token
        visitTokenAt = nowSec
        return token ?: throw IllegalStateException("Westmanga: visit-token response without token")
    }

    private suspend fun getJson(url: String): JSONObject {
        var response = apiRequest(url, currentVisitToken())
        if (response.code == 403 && response.header("X-WM-Visit-Error") != null) {
            // Token rejected/expired — refetch once and retry.
            response.close()
            response = apiRequest(url, currentVisitToken(forceRefresh = true))
        }
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

    private suspend fun apiRequest(url: String, token: String): Response {
        val request = Request.Builder()
            .url(url)
            .headers(
                Headers.Builder()
                    .add(CommonHeaders.USER_AGENT, config[userAgentKey])
                    .add(CommonHeaders.ACCEPT, "application/json")
                    .add(CommonHeaders.ACCEPT_LANGUAGE, "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                    .add(CommonHeaders.REFERER, frontendUrl())
                    .add("X-WM-Visit", token)
                    .build(),
            )
            .tag(MangaSource::class.java, source)
            .get()
            .build()
        return context.httpClient.newCall(request).await()
    }

    private fun fallbackTags(): Set<MangaTag> = buildSet {
        FALLBACK_GENRES.forEach { (name, id) -> add(MangaTag(name, id, source)) }
        add(MangaTag(COLOR_TAG_TITLE_COLORED, COLOR_TAG_KEY_COLORED, source))
        add(MangaTag(COLOR_TAG_TITLE_UNCOLORED, COLOR_TAG_KEY_UNCOLORED, source))
    }

    private companion object {
        private const val MANGA_PATH = "comic"

        private const val COLOR_TAG_KEY_COLORED = "colored"
        private const val COLOR_TAG_TITLE_COLORED = "Colored"
        private const val COLOR_TAG_KEY_UNCOLORED = "uncolored"
        private const val COLOR_TAG_TITLE_UNCOLORED = "Uncolored"

        private val ADULT_GENRES = setOf("adult", "smut", "mature", "yaoi", "yuri", "shotacon")
        private val SUGGESTIVE_GENRES = setOf("ecchi")

        private val FALLBACK_GENRES = listOf(
            "Action" to "1",
            "Adventure" to "2",
            "Comedy" to "3",
            "Drama" to "4",
            "Fantasy" to "5",
            "Harem" to "6",
            "Horror" to "7",
            "Isekai" to "8",
            "Romance" to "9",
            "School Life" to "10",
            "Shounen" to "11",
            "Slice of Life" to "12",
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
