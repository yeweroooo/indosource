package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
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
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumSet
import java.util.Locale

/**
 * Komikcast (https://be.komikcast.cc, webtree https://komikcast.to — portal that
 * links to v1/v2/v3.komikcast.fit mirrors; komikcast.com / .io are Kominfo-blocked).
 *
 * Custom backend with a JSON API on be.komikcast.cc (the .fit mirrors are the
 * React frontend):
 *  - List: GET /series?page=N&take=12&includeMeta=true
 *  - Search: /series?title={query}
 *  - Filters: /series?genreIds={GenreName}&format=manga|manhwa|manhua
 *    &status=ongoing|completed|hiatus&type=mirror|project&isHot=true&isRecommended=true
 *  - Sort: /series?sort=latest|rating|popularity&sortOrder=asc|desc
 *  - Genres: /genres (48 items: id + name — filter by NAME, e.g. genreIds=Action)
 *  - Details: /series/{id} or /series/{slug}
 *  - Chapters: /series/{id}/chapters ; pages: /series/{slug}/chapters/{index}
 *  - Images: minio.imgkc1.my.id (signed covers) and sv1.imgkc1.my.id (chapter pages)
 */
public class KomikcastParser(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.KOMIKCAST,
    pageSize = 12,
) {
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(
        "be.komikcast.cc",
        "v3.komikcast.fit",
        "komikcast.to",
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_WEEK,
        SortOrder.RATING,
        SortOrder.RELEVANCE,
    )

    override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = false,
        isSearchSupported = true,
        isSearchWithFiltersSupported = false,
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    // Reader images on sv1.imgkc1.my.id are hotlink-protected: they return 403
    // without a Referer. The app merges getRequestHeaders() into every request
    // for this source (including reader page fetches), so advertise the site
    // referer here — otherwise the chapter pages never load.
    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add(CommonHeaders.USER_AGENT, config[userAgentKey])
        .add(CommonHeaders.REFERER, baseUrl().removeSuffix("/"))
        .build()

    // Belt and suspenders: the parser is also registered as an Interceptor in
    // the app's HTTP pipeline, so inject the referer directly at request time.
    // This covers app builds that do not merge getRequestHeaders() into reader
    // page fetches. The API ignores the extra header (verified live).
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(CommonHeaders.REFERER) == null) {
            return chain.proceed(
                request.newBuilder()
                    .header(CommonHeaders.REFERER, baseUrl().removeSuffix("/"))
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
        val builder = "${baseUrl()}series".toHttpUrlOrNull()!!.newBuilder()
        builder.addQueryParameter("page", page.toString())
        builder.addQueryParameter("take", pageSize.toString())
        builder.addQueryParameter("includeMeta", "true")
        filter.query?.takeIf(String::isNotBlank)?.let {
            builder.addQueryParameter("title", it)
        }
        filter.tags.forEach { tag ->
            if (tag.key == PROJECT_TAG_KEY) {
                builder.addQueryParameter("type", "project")
            } else if (tag.key.isNotBlank()) {
                builder.addQueryParameter("genreIds", tag.key)
            }
        }
        filter.types.firstNotNullOfOrNull(::typeToSite)?.let {
            builder.addQueryParameter("format", it)
        }
        filter.states.firstNotNullOfOrNull(::stateToSite)?.let {
            builder.addQueryParameter("status", it)
        }
        val (sort, sortOrder) = orderToQuery(order)
        builder.addQueryParameter("sort", sort)
        builder.addQueryParameter("sortOrder", sortOrder)

        val json = getJson(builder.build().toString())
        val data = json.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until data.length()) {
                parseManga(data.optJSONObject(i))?.let(::add)
            }
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val seriesId = manga.url.substringAfterLast('/').trimEnd('/')
        val url = "${baseUrl()}series/$seriesId"
        val json = getJson(url)
        val data = json.optJSONObject("data") ?: return manga
        val inner = data.optJSONObject("data") ?: return manga
        // Fall back to the numeric id when the detail payload has no slug, so the
        // chapter URLs stay valid (/series/{id}/chapters/... instead of /series//...).
        val slug = inner.getStringOrNull("slug") ?: seriesId
        val genres = parseGenres(inner.optJSONArray("genres"))
        val chapters = getChapters(data.optLong("id", 0L), slug).sortedBy { it.number }

        return manga.copy(
            title = inner.getStringOrNull("title") ?: manga.title,
            altTitles = setOfNotNull(
                inner.getStringOrNull("nativeTitle"),
            ) + manga.altTitles,
            rating = parseRating(inner.getStringOrNull("rating")).takeUnless { it == RATING_UNKNOWN } ?: manga.rating,
            contentRating = sourceContentRating,
            coverUrl = inner.getStringOrNull("coverImage") ?: manga.coverUrl.orEmpty().takeIf { it.isNotBlank() },
            largeCoverUrl = inner.getStringOrNull("coverImage"),
            tags = genres.ifEmpty { manga.tags },
            state = parseState(inner.getStringOrNull("status")).let { state ->
                if (state != null) state else manga.state
            },
            authors = setOfNotNull(inner.getStringOrNull("author")),
            description = inner.getStringOrNull("synopsis") ?: manga.description,
            chapters = chapters,
            url = if (slug.isNotBlank()) "/series/$slug" else manga.url,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val index = chapter.number.toInt().coerceAtLeast(1)
        // Old cached chapters (from the pre-fix build that used optString on a JSON
        // null slug) may carry a literal "null" segment in place of the index. The API
        // 404s on /chapters/null, so rebuild the reader URL with the numeric index.
        var url = chapter.url.toAbsoluteUrl(domain)
        if (url.endsWith("/null")) {
            val base = url.removeSuffix("/null")
            url = "$base/${index}"
        }
        val json = getJson(url)
        val data = json.optJSONObject("data")?.optJSONObject("data") ?: return emptyList()
        val images = data.optJSONArray("images") ?: return emptyList()
        return buildList {
            for (i in 0 until images.length()) {
                val imageUrl = images.optString(i)
                if (imageUrl.isNotBlank() && isLikelyPageImage(imageUrl)) {
                    add(
                        MangaPage(
                            id = generateUid(source, "${chapter.url}#$i:$imageUrl"),
                            url = imageUrl,
                            preview = null,
                            source = source,
                        ),
                    )
                }
            }
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = LinkedHashSet<MangaTag>()
        runCatching {
            val json = getJson("${baseUrl()}genres")
            val data = json.optJSONArray("data") ?: return@runCatching
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i)?.optJSONObject("data") ?: continue
                val name = item.optString("name").trim().takeIf(String::isNotEmpty) ?: continue
                tags.add(MangaTag(name, name, source))
            }
        }
        tags.add(MangaTag(PROJECT_TAG_TITLE, PROJECT_TAG_KEY, source))
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
            availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE, ContentRating.ADULT),
            availableLocales = setOf(Locale("id")),
        )
    }

    override suspend fun resolveLink(link: HttpUrl): Manga? {
        val allowedHosts = configKeyDomain.presetValues.toSet()
        if (link.host !in allowedHosts) return null
        val segments = link.pathSegments.filter(String::isNotBlank)
        if (segments.isEmpty() || segments.first() != "series") return null
        if (segments.size < 2) return null
        val seriesRef = segments[1]
        val seed = Manga(
            id = generateUid(source, "/series/$seriesRef"),
            title = "Komikcast manga",
            altTitles = emptySet(),
            url = "/series/$seriesRef",
            publicUrl = "/series/$seriesRef".toAbsoluteUrl(domain),
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

    private suspend fun getChapters(seriesId: Long, slug: String): List<MangaChapter> {
        if (seriesId <= 0L) return emptyList()
        return runCatching {
            val json = getJson("${baseUrl()}series/$seriesId/chapters")
            val data = json.optJSONArray("data") ?: return emptyList()
            buildList {
                for (i in 0 until data.length()) {
                    chapterFromJson(data.optJSONObject(i), slug)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseManga(item: JSONObject?): Manga? {
        if (item == null) return null
        val data = item.optJSONObject("data") ?: return null
        val id = item.optLong("id", 0L)
        val slug = data.getStringOrNull("slug").orEmpty()
        val title = data.getStringOrNull("title") ?: slug
        if (id <= 0L || title.isBlank()) return null
        val relativeUrl = "/series/$id"
        val cover = data.getStringOrNull("coverImage")

        return Manga(
            id = generateUid(source, relativeUrl),
            title = title,
            altTitles = setOfNotNull(data.getStringOrNull("nativeTitle")),
            url = relativeUrl,
            publicUrl = relativeUrl.toAbsoluteUrl(domain),
            rating = parseRating(data.getStringOrNull("rating")),
            contentRating = sourceContentRating,
            coverUrl = cover,
            tags = emptySet(),
            state = parseState(data.getStringOrNull("status").orEmpty()),
            authors = setOfNotNull(data.getStringOrNull("author")),
            source = source,
        )
    }

    private fun chapterFromJson(item: JSONObject?, seriesSlug: String): MangaChapter? {
        if (item == null) return null
        val data = item.optJSONObject("data") ?: return null
        val index = data.optLong("index", 0L)
        if (index <= 0L) return null
        val slug = data.getStringOrNull("slug") ?: index.toString()
        val chapterTitle = data.getStringOrNull("title") ?: "Chapter $index"
        val uploadDate = parseChapterDate(item.optString("createdAt"))
        val relativeUrl = "/series/$seriesSlug/chapters/$slug"

        return MangaChapter(
            id = generateUid(source, relativeUrl),
            title = chapterTitle,
            number = index.toFloat(),
            volume = 0,
            url = relativeUrl,
            scanlator = null,
            uploadDate = uploadDate,
            branch = null,
            source = source,
        )
    }

    private fun parseGenres(array: JSONArray?): Set<MangaTag> = buildSet {
        if (array == null) return@buildSet
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optJSONObject("data")?.getStringOrNull("name")?.trim() ?: continue
            if (name.isNotEmpty()) add(MangaTag(name, name, source))
        }
    }

    private fun orderToQuery(order: SortOrder): Pair<String, String> = when (order) {
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_WEEK,
        -> "popularity" to "desc"
        SortOrder.RATING -> "rating" to "desc"
        else -> "latest" to "desc"
    }

    private fun stateToSite(state: MangaState): String? = when (state) {
        MangaState.ONGOING -> "ongoing"
        MangaState.FINISHED -> "completed"
        MangaState.PAUSED -> "hiatus"
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
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "cancelled", "dropped" -> MangaState.ABANDONED
            else -> null
        }
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

    private fun isLikelyPageImage(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (lower.contains("logo") || lower.contains("banner") || lower.contains("avatar") || lower.contains("readerarea")) {
            return false
        }
        return true
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

    private fun baseUrl(): String = "https://$domain/"

    private fun fallbackTags(): Set<MangaTag> = buildSet {
        FALLBACK_GENRES.forEach { name -> add(MangaTag(name, name, source)) }
        add(MangaTag(PROJECT_TAG_TITLE, PROJECT_TAG_KEY, source))
    }

    private companion object {
        private const val PROJECT_TAG_KEY = "project"
        private const val PROJECT_TAG_TITLE = "Project"

        private val DATE_FORMATS = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss",
        )

        private val FALLBACK_GENRES = listOf(
            "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Harem", "Horror",
            "Isekai", "Magic", "Manhua", "Manhwa", "Martial Arts", "Mecha", "Mystery",
            "Romance", "School Life", "Sci-Fi", "Seinen", "Shounen", "Slice of Life",
            "Sports", "Supernatural", "Tragedy", "Yuri",
        )
    }
}
