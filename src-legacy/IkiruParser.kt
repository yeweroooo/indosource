package org.koitharu.kotatsu.parsers.site.id.legacy

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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
import org.koitharu.kotatsu.parsers.util.attrOrNull
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

/**
 * IKIRU (portal https://ikiru.id, current mirror https://v1.ikiru.wtf;
 * 09.ikiru.wtf / 08.ikiru.wtf 301-redirect ke v1).
 *
 * Nuxt SSR + REST API v1:
 *  - List/Search/Filters: GET /api/v1/series
 *    params: page, limit, sort (latest|popular|rating|title), order (asc|desc),
 *    search (text query), genre (slug, comma-separated), status (ONGOING|COMPLETED),
 *    type (MANGA|MANHWA|MANHUA)
 *  - Details + chapters: GET /api/v1/series/{slug}  (chapters embedded in response)
 *  - Reader: GET /series/{slug}/chapter/{num}  ->  img.reader-page-img (CDN open)
 *  - Genres: GET /api/v1/genres  ->  data[] { name, slug }
 */
public class IkiruParser(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.IKIRU,
    pageSize = 21,
) {
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(
        "v1.ikiru.wtf",
        "09.ikiru.wtf",
        "ikiru.id",
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.RELEVANCE,
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_WEEK,
        SortOrder.ALPHABETICAL,
        SortOrder.RATING,
        SortOrder.NEWEST,
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
        val builder = "${baseUrl()}api/v1/series".toHttpUrl().newBuilder()
        builder.addQueryParameter("page", page.toString())
        builder.addQueryParameter("limit", pageSize.toString())

        val (sortBy, sortDir) = orderToQuery(order)
        builder.addQueryParameter("sort", sortBy)
        builder.addQueryParameter("order", sortDir)

        filter.query?.takeIf(String::isNotBlank)?.let {
            builder.addQueryParameter("search", it.trim())
        }

        filter.types.firstNotNullOfOrNull(::typeToSite)?.let {
            builder.addQueryParameter("type", it)
        }

        filter.states.firstNotNullOfOrNull(::stateToSite)?.let {
            builder.addQueryParameter("status", it)
        }

        val genres = filter.tags.mapNotNull { tag ->
            tag.key.takeIf(String::isNotBlank)
        }
        if (genres.isNotEmpty()) {
            builder.addQueryParameter("genre", genres.joinToString(","))
        }

        val json = getJson(builder.build().toString())
        val mangasArray = json.optJSONArray("data") ?: return emptyList()

        val result = ArrayList<Manga>(mangasArray.length())
        for (i in 0 until mangasArray.length()) {
            val item = mangasArray.optJSONObject(i) ?: continue
            val slug = item.optString("slug").takeIf(String::isNotBlank) ?: continue
            val title = item.optString("title").ifBlank { titleFromSlug(slug) }
            val cover = item.optString("coverUrl").takeIf(String::isNotBlank)
            val relativeUrl = "/series/$slug"
            val rating = item.optDouble("rating", 0.0).toFloat()
                .takeIf { it > 0f }
                ?.let { (it / 10f).coerceIn(0f, 1f) }
                ?: RATING_UNKNOWN

            result.add(
                Manga(
                    id = generateUid(source, relativeUrl),
                    title = title,
                    altTitles = emptySet(),
                    url = relativeUrl,
                    publicUrl = relativeUrl.toAbsoluteUrl(domain),
                    rating = rating,
                    contentRating = sourceContentRating,
                    coverUrl = cover,
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    source = source,
                )
            )
        }
        return result
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.split('/').filter(String::isNotBlank).lastOrNull() ?: return manga

        return runCatching {
            getDetailsFromApi(manga, slug)
        }.getOrElse {
            getDetailsFromHtml(manga, slug)
        }
    }

    private suspend fun getDetailsFromApi(manga: Manga, slug: String): Manga {
        val json = getJson("${baseUrl()}api/v1/series/$slug")
        val data = json.optJSONObject("data") ?: throw IllegalStateException("Empty series data")

        val title = data.optString("title").ifBlank { manga.title }
        val descRaw = data.optString("synopsis")
        val description = if (descRaw.isNotBlank()) Jsoup.parse(descRaw).text() else manga.description
        val coverUrl = data.optString("coverUrl").takeIf(String::isNotBlank) ?: manga.coverUrl
        val state = parseState(data.optString("status")) ?: manga.state

        val rating = data.optDouble("rating", 0.0).toFloat()
            .takeIf { it > 0f }
            ?.let { (it / 10f).coerceIn(0f, 1f) }
            ?: manga.rating

        val altTitles = buildSet {
            data.optJSONArray("altTitles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i).trim().takeIf(String::isNotEmpty)?.let(::add)
                }
            }
        }

        val authors = buildSet {
            data.optString("author").takeIf(String::isNotBlank)?.let(::add)
            data.optString("artist").takeIf(String::isNotBlank)?.let(::add)
            data.optJSONArray("creators")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("name")?.takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }

        val tags = buildSet {
            data.optJSONArray("genres")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val name = arr.optString(i).trim()
                    if (name.isNotEmpty()) add(MangaTag(name, name.slugify(), source))
                }
            }
        }

        val chaptersList = mutableListOf<MangaChapter>()
        val chaptersArr = data.optJSONArray("chapters")
        if (chaptersArr != null) {
            for (i in 0 until chaptersArr.length()) {
                val c = chaptersArr.optJSONObject(i) ?: continue
                val chNumber = c.optDouble("chapterNumber", 0.0).toFloat()
                val chTitle = c.optString("title").trim()
                val numStr = formatChapterNumber(chNumber)
                val relativeUrl = "/series/$slug/chapter/$numStr"
                val publishedAt = c.optString("publishedAt")
                chaptersList.add(
                    MangaChapter(
                        id = generateUid(source, relativeUrl),
                        title = if (chTitle.isNotBlank()) chTitle else "Chapter $numStr",
                        number = chNumber,
                        volume = 0,
                        url = relativeUrl,
                        scanlator = null,
                        uploadDate = parseIsoDate(publishedAt),
                        branch = null,
                        source = source,
                    )
                )
            }
        }
        chaptersList.sortBy { it.number }

        return manga.copy(
            title = title,
            altTitles = altTitles,
            rating = rating.takeUnless { it == RATING_UNKNOWN } ?: manga.rating,
            contentRating = sourceContentRating,
            coverUrl = coverUrl,
            largeCoverUrl = coverUrl,
            tags = tags.ifEmpty { manga.tags },
            state = state ?: manga.state,
            authors = authors.ifEmpty { manga.authors },
            description = description,
            chapters = chaptersList,
        )
    }

    private suspend fun getDetailsFromHtml(manga: Manga, slug: String): Manga {
        val seriesUrl = "${baseUrl()}series/$slug"
        val document = getHtml(seriesUrl)

        val title = document.selectFirst("h1")?.textOrNull()?.trim()?.takeIf(String::isNotEmpty)
            ?: manga.title
        val description = document.selectFirst("meta[name=\"description\"]")?.attrOrNull("content")
            ?: manga.description
        val coverUrl = document.selectFirst("meta[property=\"og:image\"]")?.attrOrNull("content")
            ?: manga.coverUrl
        val tags = document.select("a[href*=\"/genre/\"], a[href*=\"genre=\"]").mapNotNull { a ->
            a.textOrNull()?.trim()?.takeIf(String::isNotEmpty)?.let { MangaTag(it, it.slugify(), source) }
        }.toSet()
        val chapters = document.select("a[href*=\"/chapter/\"]").mapNotNull { a ->
            val href = a.attr("href").trim()
            if (!href.contains("/chapter/")) return@mapNotNull null
            val numStr = href.substringAfterLast("/chapter/").substringBefore("?")
            val number = numStr.toFloatOrNull() ?: return@mapNotNull null
            val relativeUrl = href.toHttpUrlOrNull()?.let { "/series/$slug/chapter/$numStr" } ?: return@mapNotNull null
            MangaChapter(
                id = generateUid(source, relativeUrl),
                title = "Chapter $numStr",
                number = number,
                volume = 0,
                url = relativeUrl,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }.distinctBy { it.url }.sortedBy { it.number }

        return manga.copy(
            title = title,
            altTitles = emptySet(),
            rating = manga.rating,
            contentRating = sourceContentRating,
            coverUrl = coverUrl,
            largeCoverUrl = coverUrl,
            tags = tags.ifEmpty { manga.tags },
            state = manga.state,
            authors = manga.authors,
            description = description,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val document = getHtml(chapterUrl)
        val images = document.select("img.reader-page-img")
            .mapNotNull { it.attrOrNull("src")?.trim()?.takeIf(String::isNotBlank) }
            .ifEmpty {
                // Fallback: any img whose src points to the CDN
                document.select("img[src*=\"cdn.ikiru.id\"]")
                    .mapNotNull { it.attrOrNull("src")?.trim()?.takeIf(String::isNotBlank) }
            }
            .distinct()
        return images.mapIndexed { index, url ->
            MangaPage(
                id = generateUid(source, "${chapter.url}#$index:$url"),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    @Volatile
    private var cachedRemoteTags: Set<MangaTag>? = null

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = LinkedHashSet<MangaTag>()
        val cached = cachedRemoteTags
        if (cached != null) {
            tags.addAll(cached)
        } else runCatching {
            val json = getJson("${baseUrl()}api/v1/genres")
            val arr = json.optJSONArray("data")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val name = item.optString("name").trim()
                    val slug = item.optString("slug").trim().ifBlank { name.slugify() }
                    if (name.isNotEmpty()) tags.add(MangaTag(name, slug, source))
                }
            }
        }
        if (tags.isNotEmpty()) cachedRemoteTags = tags.toSet()
        return MangaListFilterOptions(
            availableTags = tags.ifEmpty { fallbackTags() },
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
            ),
            availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE, ContentRating.ADULT),
            availableLocales = setOf(Locale("id")),
        )
    }

    override suspend fun resolveLink(link: HttpUrl): Manga? {
        val allowedHosts = configKeyDomain.presetValues.toSet() + setOf("07.ikiru.wtf", "08.ikiru.wtf", "05.ikiru.wtf")
        if (link.host !in allowedHosts) return null
        val segments = link.pathSegments.filter(String::isNotBlank)
        // Accept both /series/{slug} and legacy /manga/{slug}
        val slug = when (segments.firstOrNull()) {
            "series", "manga" -> segments.getOrNull(1)
            else -> null
        } ?: return null
        val relativeUrl = "/series/$slug"
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

    private fun orderToQuery(order: SortOrder): Pair<String, String> = when (order) {
        SortOrder.ALPHABETICAL -> "title" to "asc"
        SortOrder.POPULARITY, SortOrder.POPULARITY_WEEK, SortOrder.RELEVANCE -> "popular" to "desc"
        SortOrder.RATING -> "rating" to "desc"
        SortOrder.NEWEST -> "latest" to "desc"
        else -> "latest" to "desc" // UPDATED
    }

    private fun stateToSite(state: MangaState): String? = when (state) {
        MangaState.ONGOING -> "ONGOING"
        MangaState.FINISHED -> "COMPLETED"
        else -> null
    }

    private fun typeToSite(type: ContentType): String? = when (type) {
        ContentType.MANGA -> "MANGA"
        ContentType.MANHWA -> "MANHWA"
        ContentType.MANHUA -> "MANHUA"
        else -> null
    }

    private fun parseState(raw: String?): MangaState? {
        val n = raw?.lowercase(Locale.ROOT) ?: return null
        return when {
            n.contains("ongoing") -> MangaState.ONGOING
            n.contains("completed") || n.contains("tamat") -> MangaState.FINISHED
            n.contains("hiatus") -> MangaState.PAUSED
            n.contains("cancelled") || n.contains("dropped") -> MangaState.ABANDONED
            else -> null
        }
    }

    private fun formatChapterNumber(number: Float): String {
        return if (number % 1f == 0f) number.toInt().toString() else number.toString()
    }

    private fun parseIsoDate(raw: String?): Long {
        val date = raw?.trim()?.takeIf(String::isNotEmpty) ?: return 0L
        return DATE_FORMATTERS.firstNotNullOfOrNull { sdf ->
            runCatching {
                synchronized(sdf) { sdf.parse(date) }?.time
            }.getOrNull()
        } ?: 0L
    }

    private fun titleFromSlug(slug: String): String = slug
        .substringBefore('?').trimEnd('/').substringAfterLast('/')
        .replace('-', ' ')
        .split(' ')
        .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } }
        .ifBlank { "IKIRU" }

    private fun String.slugify(): String = lowercase(Locale.ROOT)
        .replace(NON_ALNUM_REGEX, "-")
        .trim('-')

    private fun baseUrl(): String = "https://$domain/"

    private suspend fun getJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .addHeader(CommonHeaders.ACCEPT, "application/json")
            .addHeader(CommonHeaders.USER_AGENT, config[userAgentKey])
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

    private suspend fun getHtml(url: String): Document {
        val request = Request.Builder()
            .url(url)
            .addHeader(CommonHeaders.USER_AGENT, config[userAgentKey])
            .addHeader(CommonHeaders.ACCEPT, HTML_ACCEPT)
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
        return response.parseHtml()
    }

    private fun fallbackTags(): Set<MangaTag> = buildSet {
        FALLBACK_GENRES.forEach { name -> add(MangaTag(name, name.slugify(), source)) }
    }

    private companion object {
        private const val HTML_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"

        private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")

        private val DATE_FORMATTERS = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
        ).map { SimpleDateFormat(it, Locale.US) }.toTypedArray()

        private val FALLBACK_GENRES = listOf(
            "Action", "Adult", "Adventure", "Bocil", "Business", "Comedy",
            "Demons", "Doujinshi", "Drama", "Ecchi", "Fantasy", "Harem",
            "Horror", "Isekai", "Josei", "Lolicon", "Martial Arts", "Mature",
            "Mecha", "Mystery", "Psychological", "Romance", "School Life",
            "Sci-fi", "Seinen", "Shoujo", "Shoujo Ai", "Shounen", "Shounen Ai",
            "Slice of Life", "Sports", "Supernatural", "Tragedy", "Yaoi", "Yuri",
        )
    }
}
