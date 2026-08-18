package org.koitharu.kotatsu.parsers.site.id

import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import tsuki.util.json.getIntOrDefault
import tsuki.util.json.getStringOrNull
import tsuki.util.json.mapJSON
import tsuki.util.json.mapJSONNotNull
import tsuki.util.parseJson
import tsuki.util.toAbsoluteUrl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumSet
import java.util.Locale

/**
 * Shinigami Scans (https://11.shinigami.asia) — JSON API based source.
 *
 * The website is a SvelteKit SPA; all data comes from the JSON API at
 * https://api.shngm.io/v1/:
 *  - manga/list     — browse/search with filters (genre_include, format, type,
 *                     status, sort, sort_order, q, is_recommended, is_update)
 *  - manga/detail/{id}
 *  - chapter/{id}/list  — chapters of a manga
 *  - chapter/detail/{id} — reader pages (base_url + chapter.path + image names)
 *  - genre/list, format/list, type/list — filter taxonomies
 *
 * Manga links are /series/{manga_id}/, chapter links /chapter/{chapter_id}/.
 */
public class ShinigamiParser(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.SHINIGAMI,
    pageSize = 24,
) {
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(
        "11.shinigami.asia",
        "shinigami.to",
        "shinigami.asia",
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_WEEK,
        SortOrder.RATING,
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

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val json = getJson("manga/list", buildListParams(page, order, filter))
        return json.getJSONArray("data").mapJSON(::parseManga)
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val mangaId = manga.idFromSeriesUrl() ?: return manga
        val json = getJson("manga/detail/$mangaId", emptyMap())
        val mangaJson = json.getJSONObject("data")

        val title = mangaJson.getStringOrNull("title") ?: manga.title
        val altTitles = mangaJson.getStringOrNull("alternative_title")
            ?.split(',', ';')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?.toSet()
            .orEmpty()
        val cover = mangaJson.getStringOrNull("cover_portrait_url")
            ?: mangaJson.getStringOrNull("cover_image_url")
        val state = parseState(mangaJson.getIntOrDefault("status", 0))
        val tags = parseTaxonomyTags(mangaJson)
        val chapters = loadChapters(mangaId)

        return manga.copy(
            title = title,
            altTitles = altTitles.ifEmpty { manga.altTitles },
            rating = parseRating(mangaJson),
            contentRating = sourceContentRating,
            coverUrl = cover ?: manga.coverUrl,
            largeCoverUrl = cover ?: manga.coverUrl,
            tags = tags.ifEmpty { manga.tags },
            state = state ?: manga.state,
            authors = parseAuthors(mangaJson),
            description = mangaJson.getStringOrNull("description") ?: manga.description,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.removeSuffix("/").substringAfterLast('/')
        val json = getJson("chapter/detail/$chapterId", emptyMap())
        val data = json.getJSONObject("data")
        val baseUrl = data.getStringOrNull("base_url") ?: return emptyList()
        val chapterJson = data.optJSONObject("chapter") ?: return emptyList()
        val path = chapterJson.getStringOrNull("path") ?: return emptyList()
        val files = chapterJson.getJSONArray("data")

        return (0 until files.length()).map { index ->
            val fileName = files.getString(index)
            MangaPage(
                id = generateUid(source, "${chapter.url}#$index:$fileName"),
                url = baseUrl + path + fileName,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = LinkedHashSet<MangaTag>()
        runCatching {
            getJson("genre/list", emptyMap()).getJSONArray("data").mapJSONNotNull { item ->
                val name = item.getStringOrNull("name") ?: return@mapJSONNotNull null
                val slug = item.getStringOrNull("slug") ?: return@mapJSONNotNull null
                MangaTag(name, slug, source)
            }.filterTo(tags) { it.key !in TYPE_SLUGS }
        }
        runCatching {
            getJson("type/list", emptyMap()).getJSONArray("data").mapJSONNotNull { item ->
                val name = item.getStringOrNull("name") ?: return@mapJSONNotNull null
                val slug = item.getStringOrNull("slug") ?: return@mapJSONNotNull null
                MangaTag(name, slug, source)
            }.filterTo(tags) { it.key in TYPE_SLUGS }
        }
        tags.add(MangaTag(RECOMMENDED_TAG_TITLE, RECOMMENDED_TAG_KEY, source))
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
            ),
            availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE),
            availableLocales = setOf(Locale("id")),
        )
    }

    override suspend fun resolveLink(link: okhttp3.HttpUrl): Manga? {
        val allowedHosts = configKeyDomain.presetValues.toSet()
        if (link.host !in allowedHosts) return null
        val segments = link.pathSegments.filter(String::isNotBlank)
        if (segments.size != 2 || segments.first() != "series") return null
        val relativeUrl = "/series/${segments[1]}/"
        val seed = Manga(
            id = generateUid(source, relativeUrl),
            title = "Shinigami manga",
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

    private fun buildListParams(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): Map<String, String> {
        val params = LinkedHashMap<String, String>()
        params["page"] = page.toString()
        params["page_size"] = pageSize.toString()

        filter.query?.takeIf(String::isNotBlank)?.let { params["q"] = it }
        if (order != SortOrder.RELEVANCE) {
            sortToQuery(order)?.let {
                params["sort"] = it
                params["sort_order"] = "desc"
            }
        }

        val types = filter.types.mapNotNull(::typeToSite)
        if (types.isNotEmpty()) params["format"] = types.joinToString(",")

        filter.states.firstNotNullOfOrNull(::stateToSite)?.let { params["status"] = it }

        filter.tags.forEach { tag ->
            when (tag.key) {
                TYPE_PROJECT, TYPE_MIRROR -> params.appendComma("type", tag.key)
                RECOMMENDED_TAG_KEY -> params["is_recommended"] = "true"
                else -> params.appendComma("genre_include", tag.key)
            }
        }
        return params
    }

    private fun MutableMap<String, String>.appendComma(key: String, value: String) {
        merge(key, value) { old, new -> "$old,$new" }
    }

    private fun sortToQuery(order: SortOrder): String? = when (order) {
        SortOrder.UPDATED -> "latest"
        SortOrder.POPULARITY -> "bookmark"
        SortOrder.POPULARITY_WEEK -> "rank"
        SortOrder.RATING -> "rating"
        SortOrder.RELEVANCE -> "rank"
        else -> null
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
        else -> null
    }

    private fun parseManga(json: JSONObject): Manga {
        val mangaId = json.getStringOrNull("manga_id") ?: return error("manga_id missing")
        val relativeUrl = "/series/$mangaId/"
        val title = json.getStringOrNull("title") ?: mangaId
        val cover = json.getStringOrNull("cover_portrait_url")
            ?: json.getStringOrNull("cover_image_url")
        return Manga(
            id = generateUid(source, relativeUrl),
            title = title,
            altTitles = emptySet(),
            url = relativeUrl,
            publicUrl = relativeUrl.toAbsoluteUrl(domain),
            rating = parseRating(json),
            contentRating = sourceContentRating,
            coverUrl = cover,
            tags = emptySet(),
            state = parseState(json.getIntOrDefault("status", 0)),
            authors = parseAuthors(json),
            source = source,
        )
    }

    private fun parseTaxonomyTags(json: JSONObject): Set<MangaTag> = buildSet {
        val taxonomy = json.optJSONObject("taxonomy") ?: return@buildSet
        taxonomy.optJSONArray("Genre")?.mapJSONNotNull { item ->
            val name = item.getStringOrNull("name") ?: return@mapJSONNotNull null
            val slug = item.getStringOrNull("slug") ?: return@mapJSONNotNull null
            add(MangaTag(name, slug, source))
        }
    }

    private fun parseAuthors(json: JSONObject): Set<String> = buildSet {
        val taxonomy = json.optJSONObject("taxonomy") ?: return@buildSet
        taxonomy.optJSONArray("Author")?.mapJSONNotNull { item ->
            item.getStringOrNull("name")?.takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun parseRating(json: JSONObject): Float {
        val score = json.optDouble("user_rate", -1.0)
        if (score <= 0.0) return RATING_UNKNOWN
        return (score / 10.0).toFloat().coerceIn(0f, 1f)
    }

    private fun parseState(status: Int): MangaState? = when (status) {
        1 -> MangaState.ONGOING
        2 -> MangaState.FINISHED
        3 -> MangaState.PAUSED
        else -> null
    }

    private suspend fun loadChapters(mangaId: String): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        var page = 1
        while (true) {
            val json = getJson(
                "chapter/$mangaId/list",
                mapOf(
                    "page" to page.toString(),
                    "page_size" to "100",
                    "sort_by" to "chapter_number",
                    "sort_order" to "desc",
                ),
            )
            val data = json.optJSONArray("data") ?: break
            data.mapJSONNotNull { item ->
                val chapterId = item.getStringOrNull("chapter_id") ?: return@mapJSONNotNull null
                val number = item.optDouble("chapter_number", 0.0).toFloat()
                val chapterTitle = item.getStringOrNull("chapter_title")
                    ?.takeIf(String::isNotBlank)
                    ?: "Chapter ${number.toInt()}"
                MangaChapter(
                    id = generateUid(source, "/chapter/$chapterId/"),
                    title = chapterTitle,
                    number = number,
                    volume = 0,
                    url = "/chapter/$chapterId/",
                    scanlator = null,
                    uploadDate = parseDate(item.getStringOrNull("release_date")),
                    branch = null,
                    source = source,
                )
            }.filterTo(chapters) { it.id != 0L }

            val totalPage = json.optJSONObject("meta")?.getIntOrDefault("total_page", 1)
                ?: 1
            if (page >= totalPage || data.length() < 100) break
            page++
        }
        return chapters.sortedBy { it.number }
    }

    private suspend fun getJson(
        path: String,
        params: Map<String, String>,
    ): JSONObject {
        val urlBuilder = API_BASE_URL.toHttpUrl().newBuilder()
        urlBuilder.addPathSegments(path)
        params.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
        val url = urlBuilder.build().toString()
        val request = okhttp3.Request.Builder()
            .url(url)
            .headers(
                okhttp3.Headers.Builder()
                    .add(CommonHeaders.ACCEPT, "application/json")
                    .add(CommonHeaders.REFERER, baseUrl())
                    .build(),
            )
            .tag(MangaSource::class.java, source)
            .get()
            .build()
        return context.httpClient.newCall(request).await().parseJson()
    }

    private fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(raw.substringBefore('Z'))?.time
                ?: 0L
        }.getOrDefault(0L)
    }

    private fun Manga.idFromSeriesUrl(): String? =
        url.substringAfter("/series/", "").substringBefore('/').takeIf(String::isNotBlank)

    private fun baseUrl(): String = "https://$domain/"

    private fun fallbackTags(): Set<MangaTag> = buildSet {
        FALLBACK_GENRES.forEach { (name, slug) -> add(MangaTag(name, slug, source)) }
        add(MangaTag(TYPE_PROJECT_TITLE, TYPE_PROJECT, source))
        add(MangaTag(TYPE_MIRROR_TITLE, TYPE_MIRROR, source))
        add(MangaTag(RECOMMENDED_TAG_TITLE, RECOMMENDED_TAG_KEY, source))
    }

    private companion object {
        private const val API_BASE_URL = "https://api.shngm.io/v1/"
        private const val TYPE_PROJECT = "project"
        private const val TYPE_MIRROR = "mirror"
        private const val TYPE_PROJECT_TITLE = "Project"
        private const val TYPE_MIRROR_TITLE = "Mirror"
        private const val RECOMMENDED_TAG_KEY = "recommended"
        private const val RECOMMENDED_TAG_TITLE = "Rekomendasi"
        private val TYPE_SLUGS = setOf(TYPE_PROJECT, TYPE_MIRROR)

        private val FALLBACK_GENRES = listOf(
            "Action" to "action",
            "Adventure" to "adventure",
            "Comedy" to "comedy",
            "Drama" to "drama",
            "Fantasy" to "fantasy",
            "Harem" to "harem",
            "Horror" to "horror",
            "Isekai" to "isekai",
            "Manhua" to "manhua",
            "Manhwa" to "manhwa",
            "Martial Arts" to "martial-arts",
            "Mystery" to "mystery",
            "Romance" to "romance",
            "School Life" to "school-life",
            "Sci-fi" to "sci-fi",
            "Seinen" to "seinen",
            "Shounen" to "shounen",
            "Slice of Life" to "slice-of-life",
            "Supernatural" to "supernatural",
        )
    }
}
