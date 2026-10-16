package org.koitharu.kotatsu.parsers.site.id.legacy

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Demographic
import org.koitharu.kotatsu.parsers.model.Favicon
import org.koitharu.kotatsu.parsers.model.Favicons
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
import org.koitharu.kotatsu.parsers.util.parseJson
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

/**
 * Asura Scans (https://asurascans.com/comics, API at https://api.asurascans.com) — legacy core-parsers variant.
 */
public class AsuraScansParser(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.ASURASCANS,
    pageSize = 20,
) {
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(
        "asurascans.com",
        "asuracomic.net",
        "asuracomics.com",
        "asuratoons.com",
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.RELEVANCE,
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
    )

    override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = false,
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isAuthorSearchSupported = true,
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override suspend fun getFavicons(): Favicons = Favicons(
        listOf(Favicon("https://asurascans.com/images/logo.webp", 192, "icon")),
        "https://asurascans.com",
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val offset = (page - 1) * pageSize
        val urlBuilder = "${apiBaseUrl()}/api/series".toHttpUrl().newBuilder()
        urlBuilder.addQueryParameter("limit", pageSize.toString())
        urlBuilder.addQueryParameter("offset", offset.toString())

        // Sorting
        when (order) {
            SortOrder.RELEVANCE -> {
                if (filter.query.isNullOrBlank()) {
                    urlBuilder.addQueryParameter("sort", "latest")
                    urlBuilder.addQueryParameter("order", "desc")
                }
            }
            SortOrder.UPDATED -> {
                urlBuilder.addQueryParameter("sort", "latest")
                urlBuilder.addQueryParameter("order", "desc")
            }
            SortOrder.POPULARITY,
            SortOrder.POPULARITY_WEEK,
            SortOrder.POPULARITY_MONTH,
            SortOrder.POPULARITY_HOUR,
            -> {
                urlBuilder.addQueryParameter("sort", "popular")
                urlBuilder.addQueryParameter("order", "desc")
            }
            SortOrder.RATING -> {
                urlBuilder.addQueryParameter("sort", "rating")
                urlBuilder.addQueryParameter("order", "desc")
            }
            SortOrder.NEWEST,
            SortOrder.ADDED,
            -> {
                urlBuilder.addQueryParameter("sort", "newest")
                urlBuilder.addQueryParameter("order", "desc")
            }
            SortOrder.ALPHABETICAL -> {
                urlBuilder.addQueryParameter("sort", "title")
                urlBuilder.addQueryParameter("order", "asc")
            }
            SortOrder.ALPHABETICAL_DESC -> {
                urlBuilder.addQueryParameter("sort", "title")
                urlBuilder.addQueryParameter("order", "desc")
            }
            else -> {
                urlBuilder.addQueryParameter("sort", "latest")
                urlBuilder.addQueryParameter("order", "desc")
            }
        }

        // Query
        filter.query?.trim()?.takeIf(String::isNotEmpty)?.let {
            urlBuilder.addQueryParameter("search", it)
        }

        // Genres
        if (filter.tags.isNotEmpty()) {
            val genreSlugs = filter.tags.joinToString(",") { it.key }
            urlBuilder.addQueryParameter("genres", genreSlugs)
        }

        // Status
        for (state in filter.states) {
            when (state) {
                MangaState.ONGOING -> "ongoing"
                MangaState.FINISHED -> "completed"
                MangaState.PAUSED -> "hiatus"
                MangaState.ABANDONED -> "dropped"
                else -> null
            }?.let { urlBuilder.addQueryParameter("status", it) }
        }

        // Types
        for (type in filter.types) {
            when (type) {
                ContentType.MANGA -> "manga"
                ContentType.MANHWA -> "manhwa"
                ContentType.MANHUA -> "manhua"
                else -> null
            }?.let { urlBuilder.addQueryParameter("type", it) }
        }

        // Author
        filter.author?.trim()?.takeIf(String::isNotEmpty)?.let {
            urlBuilder.addQueryParameter("author", it)
        }

        val json = getJson(urlBuilder.build().toString())
        val data = json.optJSONArray("data") ?: return emptyList()
        val result = ArrayList<Manga>(data.length())
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            result.add(parseManga(item))
        }
        return result
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.removeSuffix("/").substringAfterLast('/')
        val detailUrl = "${apiBaseUrl()}/api/series/$slug"

        val json = runCatching { getJson(detailUrl) }.getOrNull()
        val seriesObj = json?.optJSONObject("series")
        val baseManga = if (seriesObj != null) parseManga(seriesObj) else manga

        // Fetch all chapters
        val chapters = fetchChapters(slug, baseManga.url)

        return baseManga.copy(
            chapters = chapters.ifEmpty { manga.chapters },
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = "${webBaseUrl()}${chapter.url}"
        val html = getHtml(chapterUrl)

        val matcher = PAGE_IMAGE_PATTERN.matcher(html)
        val seen = LinkedHashSet<String>()
        while (matcher.find()) {
            seen.add(matcher.group(0))
        }

        val pages = ArrayList<MangaPage>(seen.size)
        var idx = 0
        for (url in seen) {
            pages.add(
                MangaPage(
                    id = generateUid(source, "${chapter.url}#$idx:$url"),
                    url = url,
                    preview = null,
                    source = source,
                ),
            )
            idx++
        }
        return pages
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    override suspend fun resolveLink(link: HttpUrl): Manga? {
        val host = link.host
        val allowedHosts = configKeyDomain.presetValues.toSet() + setOf("asurascans.com", "api.asurascans.com")
        if (host !in allowedHosts) return null

        val segments = link.pathSegments.filter(String::isNotBlank)
        if (segments.isEmpty()) return null

        val prefix = segments[0]
        if (prefix != "comics" && prefix != "manga" && prefix != "series") return null
        if (segments.size < 2) return null

        val slug = segments[1]
        val seed = Manga(
            id = generateUid(source, "/comics/$slug"),
            title = "",
            altTitles = emptySet(),
            url = "/comics/$slug",
            publicUrl = "${webBaseUrl()}/comics/$slug",
            rating = RATING_UNKNOWN,
            contentRating = sourceContentRating,
            coverUrl = null,
            tags = emptySet(),
            state = MangaState.ONGOING,
            authors = emptySet(),
            largeCoverUrl = null,
            description = null,
            chapters = emptyList(),
            source = source,
        )
        return getDetails(seed)
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val dynamicTags = LinkedHashSet<MangaTag>()
        runCatching {
            val json = getJson("${apiBaseUrl()}/api/genres")
            val data = json.optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val name = item.optString("name").trim()
                    val slug = item.optString("slug").trim()
                    if (name.isNotEmpty() && slug.isNotEmpty()) {
                        dynamicTags.add(MangaTag(name, slug, source))
                    }
                }
            }
        }

        val tags = if (dynamicTags.isNotEmpty()) {
            dynamicTags
        } else {
            buildSet {
                ALL_GENRES.forEach { (name, slug) -> add(MangaTag(name, slug, source)) }
            }
        }

        return MangaListFilterOptions(
            availableTags = tags,
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.PAUSED,
                MangaState.ABANDONED,
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
            ),
            availableContentRating = EnumSet.of(
                ContentRating.SAFE,
                ContentRating.SUGGESTIVE,
            ),
            availableDemographics = EnumSet.of(
                Demographic.SHOUNEN,
                Demographic.SHOUJO,
            ),
            availableLocales = setOf(Locale.ENGLISH),
        )
    }

    private suspend fun fetchChapters(seriesSlug: String, mangaUrl: String): List<MangaChapter> {
        val url = "${apiBaseUrl()}/api/series/$seriesSlug/chapters"
        val json = runCatching { getJson(url) }.getOrNull() ?: return emptyList()
        val data = json.optJSONArray("data") ?: return emptyList()

        val chapters = ArrayList<MangaChapter>(data.length())
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val id = item.optLong("id")
            val number = item.optDouble("number", 0.0).toFloat()
            val chapterSlug = item.optString("slug").takeIf(String::isNotBlank)
                ?: "chapter-${item.optInt("number")}"
            val rawTitle = item.optString("title").trim()
            val publishedAt = item.optString("published_at")
            val numStr = formatChapterNumber(number)

            val title = when {
                rawTitle.isNotBlank() && !rawTitle.equals("null", true) -> "Ch. $numStr - $rawTitle"
                else -> "Chapter $numStr"
            }

            val chapterRelativeUrl = "${mangaUrl.removeSuffix("/")}/chapter/$chapterSlug"

            chapters.add(
                MangaChapter(
                    id = generateUid(source, "$seriesSlug#$id"),
                    title = title,
                    number = number,
                    volume = 0,
                    url = chapterRelativeUrl,
                    scanlator = "Asura Scans",
                    uploadDate = parseIsoDate(publishedAt),
                    branch = "ENG",
                    source = source,
                ),
            )
        }

        chapters.sortBy { it.number }
        return chapters
    }

    private fun parseManga(obj: JSONObject): Manga {
        val id = obj.optInt("id", 0)
        val slug = obj.optString("slug").trim()
        val title = obj.optString("title").trim().ifBlank { "Asura $id" }

        val altTitles = buildSet {
            obj.optJSONArray("alt_titles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.optString(i).trim()
                    if (t.isNotEmpty()) add(t)
                }
            }
            obj.optJSONArray("alternative_titles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.optString(i).trim()
                    if (t.isNotEmpty()) add(t)
                }
            }
        }

        val rawDesc = obj.optString("description")
        val description = if (rawDesc.isNotBlank()) {
            Jsoup.parse(rawDesc).text().trim()
        } else {
            null
        }

        val cover = obj.optString("cover").takeIf(String::isNotBlank)
            ?: obj.optString("cover_url").takeIf(String::isNotBlank)
        val banner = obj.optString("banner").takeIf(String::isNotBlank)

        val state = when (obj.optString("status").lowercase(Locale.ROOT)) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "dropped" -> MangaState.ABANDONED
            else -> MangaState.ONGOING
        }

        val rawRating = obj.optDouble("rating", 0.0).toFloat()
        val rating = if (rawRating > 0f) (rawRating / 10f).coerceIn(0f, 1f) else RATING_UNKNOWN

        val authors = buildSet {
            obj.optString("author").takeIf(String::isNotBlank)?.let(::add)
            obj.optString("artist").takeIf(String::isNotBlank)?.let(::add)
        }

        val tags = buildSet {
            obj.optJSONArray("genres")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val g = arr.optJSONObject(i) ?: continue
                    val name = g.optString("name").trim()
                    val gSlug = g.optString("slug").trim()
                    if (name.isNotEmpty() && gSlug.isNotEmpty()) {
                        add(MangaTag(name, gSlug, source))
                    }
                }
            }
        }

        val publicUrlPath = obj.optString("public_url").trim().takeIf(String::isNotBlank)
            ?: "/comics/$slug"
        val relativeUrl = if (publicUrlPath.startsWith("/comics/")) publicUrlPath else "/comics/$slug"

        return Manga(
            id = generateUid(source, relativeUrl),
            title = title,
            altTitles = altTitles,
            url = relativeUrl,
            publicUrl = "${webBaseUrl()}$relativeUrl",
            rating = rating,
            contentRating = sourceContentRating,
            coverUrl = cover,
            tags = tags,
            state = state,
            authors = authors,
            largeCoverUrl = banner ?: cover,
            description = description,
            chapters = emptyList(),
            source = source,
        )
    }

    private fun formatChapterNumber(number: Float): String {
        return if (number % 1f == 0f) number.toInt().toString() else number.toString()
    }

    private fun parseIsoDate(raw: String?): Long {
        val date = raw?.trim()?.takeIf(String::isNotEmpty) ?: return 0L
        return DATE_FORMATS.firstNotNullOfOrNull { pattern ->
            runCatching {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                sdf.parse(date)?.time
            }.getOrNull()
        } ?: 0L
    }

    private fun apiBaseUrl(): String = "https://api.asurascans.com"

    private fun webBaseUrl(): String = "https://$domain"

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

    private suspend fun getHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .addHeader(CommonHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
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
        return response.body?.string().orEmpty()
    }

    private companion object {
        private val PAGE_IMAGE_PATTERN = Pattern.compile("https://cdn\\.asurascans\\.com/asura-images/chapters/[^\"'\\\\&\\s]+\\.(?:webp|jpg|jpeg|png)")

        private val DATE_FORMATS = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
        )

        private val ALL_GENRES = listOf(
            "Action" to "action",
            "Adventure" to "adventure",
            "Comedy" to "comedy",
            "Crazy MC" to "crazy-mc",
            "Dark Fantasy" to "dark-fantasy",
            "Demon" to "demon",
            "Drama" to "drama",
            "Dungeons" to "dungeons",
            "Fantasy" to "fantasy",
            "Game" to "game",
            "Genius MC" to "genius-mc",
            "Isekai" to "isekai",
            "Kuchikuchi" to "kuchikuchi",
            "Magic" to "magic",
            "Martial Arts" to "martial-arts",
            "Murim" to "murim",
            "Mystery" to "mystery",
            "Necromancer" to "necromancer",
            "Overpowered" to "overpowered",
            "Psychological" to "psychological",
            "Regression" to "regression",
            "Reincarnation" to "reincarnation",
            "Revenge" to "revenge",
            "Romance" to "romance",
            "School Life" to "school-life",
            "Sci-fi" to "sci-fi",
            "Shoujo" to "shoujo",
            "Shounen" to "shounen",
            "Supernatural" to "supernatural",
            "System" to "system",
            "Tower" to "tower",
            "Tragedy" to "tragedy",
            "Transmigration" to "transmigration",
            "Villain" to "villain",
            "Violence" to "violence",
        )
    }
}
