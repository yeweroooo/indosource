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

/**
 * MangaDex (https://mangadex.org, API at https://api.mangadex.org) — legacy core-parsers variant.
 */
public class MangaDexParser(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.MANGADEX,
    pageSize = 32,
) {
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(
        "api.mangadex.org",
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
        isTagsExclusionSupported = true,
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isYearSupported = true,
        isOriginalLocaleSupported = true,
        isAuthorSearchSupported = true,
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(ConfigKey.SplitByTranslations(true))
    }

    override suspend fun getFavicons(): Favicons = Favicons(
        listOf(Favicon("https://mangadex.org/favicon.ico", 64, "icon")),
        "https://mangadex.org",
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val offset = (page - 1) * pageSize
        if (offset >= 10000) return emptyList()

        val urlBuilder = "https://$domain/manga".toHttpUrl().newBuilder()
        urlBuilder.addQueryParameter("limit", pageSize.toString())
        urlBuilder.addQueryParameter("offset", offset.toString())
        urlBuilder.addQueryParameter("includes[]", "cover_art")
        urlBuilder.addQueryParameter("includes[]", "author")
        urlBuilder.addQueryParameter("includes[]", "artist")

        // Content rating: default to all ratings so nothing is hidden unless the user restricts it
        if (filter.contentRating.isNotEmpty()) {
            for (rating in filter.contentRating) {
                when (rating) {
                    ContentRating.SAFE -> urlBuilder.addQueryParameter("contentRating[]", "safe")
                    ContentRating.SUGGESTIVE -> urlBuilder.addQueryParameter("contentRating[]", "suggestive")
                    ContentRating.ADULT -> {
                        urlBuilder.addQueryParameter("contentRating[]", "erotica")
                        urlBuilder.addQueryParameter("contentRating[]", "pornographic")
                    }
                }
            }
        } else {
            urlBuilder.addQueryParameter("contentRating[]", "safe")
            urlBuilder.addQueryParameter("contentRating[]", "suggestive")
            urlBuilder.addQueryParameter("contentRating[]", "erotica")
            urlBuilder.addQueryParameter("contentRating[]", "pornographic")
        }

        // Sorting
        when (order) {
            SortOrder.RELEVANCE -> urlBuilder.addQueryParameter("order[relevance]", "desc")
            SortOrder.UPDATED -> urlBuilder.addQueryParameter("order[latestUploadedChapter]", "desc")
            SortOrder.POPULARITY,
            SortOrder.POPULARITY_WEEK,
            SortOrder.POPULARITY_MONTH,
            SortOrder.POPULARITY_HOUR,
            -> urlBuilder.addQueryParameter("order[followedCount]", "desc")
            SortOrder.RATING -> urlBuilder.addQueryParameter("order[rating]", "desc")
            SortOrder.NEWEST,
            SortOrder.ADDED,
            -> urlBuilder.addQueryParameter("order[createdAt]", "desc")
            SortOrder.ALPHABETICAL -> urlBuilder.addQueryParameter("order[title]", "asc")
            SortOrder.ALPHABETICAL_DESC -> urlBuilder.addQueryParameter("order[title]", "desc")
            else -> urlBuilder.addQueryParameter("order[latestUploadedChapter]", "desc")
        }

        // Search query
        filter.query?.takeIf(String::isNotBlank)?.let { q ->
            urlBuilder.addQueryParameter("title", q.trim())
        }

        // Tags inclusion
        for (tag in filter.tags) {
            urlBuilder.addQueryParameter("includedTags[]", tag.key)
        }

        // Tags exclusion
        for (tag in filter.tagsExclude) {
            urlBuilder.addQueryParameter("excludedTags[]", tag.key)
        }

        // Publication status
        for (state in filter.states) {
            when (state) {
                MangaState.ONGOING -> "ongoing"
                MangaState.FINISHED -> "completed"
                MangaState.PAUSED -> "hiatus"
                MangaState.ABANDONED -> "cancelled"
                else -> null
            }?.let { urlBuilder.addQueryParameter("status[]", it) }
        }

        // Content types -> originalLanguage
        for (type in filter.types) {
            when (type) {
                ContentType.MANGA -> "ja"
                ContentType.MANHWA -> "ko"
                ContentType.MANHUA -> "zh"
                else -> null
            }?.let { urlBuilder.addQueryParameter("originalLanguage[]", it) }
        }

        // Demographic
        for (demographic in filter.demographics) {
            when (demographic) {
                Demographic.SHOUNEN -> "shounen"
                Demographic.SHOUJO -> "shoujo"
                Demographic.SEINEN -> "seinen"
                Demographic.JOSEI -> "josei"
                else -> null
            }?.let { urlBuilder.addQueryParameter("publicationDemographic[]", it) }
        }

        // Year
        if (filter.year > 0) {
            urlBuilder.addQueryParameter("year", filter.year.toString())
        }

        // Author / Artist search
        val authorQuery = filter.author?.trim()?.takeIf(String::isNotBlank)
        if (authorQuery != null) {
            val authorIds = searchAuthorIds(authorQuery)
            if (authorIds.isNotEmpty()) {
                for (authorId in authorIds) {
                    urlBuilder.addQueryParameter("authors[]", authorId)
                    urlBuilder.addQueryParameter("artists[]", authorId)
                }
            } else {
                return emptyList()
            }
        }

        // Multi-language: if a locale is chosen, filter by it; if null, parse ANY language!
        val filterLocale = filter.locale
        if (filterLocale != null) {
            urlBuilder.addQueryParameter("availableTranslatedLanguage[]", filterLocale.language)
        }
        val origLocale = filter.originalLocale
        if (origLocale != null) {
            urlBuilder.addQueryParameter("originalLanguage[]", origLocale.language)
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
        val mangaId = manga.url.removeSuffix("/").substringAfterLast('/')
        val detailUrl = "https://$domain/manga/$mangaId".toHttpUrl().newBuilder()
            .addQueryParameter("includes[]", "cover_art")
            .addQueryParameter("includes[]", "author")
            .addQueryParameter("includes[]", "artist")
            .build()
            .toString()

        val json = getJson(detailUrl)
        val mangaData = json.optJSONObject("data") ?: return manga
        val parsed = parseManga(mangaData)

        // Rating from statistics
        val rating = runCatching {
            val statJson = getJson("https://$domain/statistics/manga/$mangaId")
            val statObj = statJson.optJSONObject("statistics")?.optJSONObject(mangaId)
            val score = statObj?.optJSONObject("rating")?.optDouble("bayesian", 0.0)?.toFloat()
                ?: statObj?.optJSONObject("rating")?.optDouble("average", 0.0)?.toFloat()
                ?: 0f
            if (score > 0f) (score / 10f).coerceIn(0f, 1f) else RATING_UNKNOWN
        }.getOrDefault(RATING_UNKNOWN)

        // All chapters feed
        val chapters = fetchChapters(mangaId)

        return parsed.copy(
            rating = rating.takeUnless { it == RATING_UNKNOWN } ?: manga.rating,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.removeSuffix("/").substringAfterLast('/')
        val json = getJson("https://$domain/at-home/server/$chapterId")
        val baseUrl = json.getString("baseUrl")
        val chapterObj = json.getJSONObject("chapter")
        val hash = chapterObj.getString("hash")
        val data = chapterObj.getJSONArray("data")
        val dataSaver = chapterObj.optJSONArray("dataSaver")

        val pages = ArrayList<MangaPage>(data.length())
        for (i in 0 until data.length()) {
            val filename = data.getString(i)
            val saverFilename = dataSaver?.optString(i)?.takeIf(String::isNotBlank) ?: filename
            val pageUrl = "$baseUrl/data/$hash/$filename"
            val previewUrl = "$baseUrl/data-saver/$hash/$saverFilename"
            pages.add(
                MangaPage(
                    id = generateUid(source, "${chapter.url}#$i:$filename"),
                    url = pageUrl,
                    preview = previewUrl,
                    source = source,
                ),
            )
        }
        return pages
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    override suspend fun resolveLink(link: HttpUrl): Manga? {
        val host = link.host
        if (host != "mangadex.org" && host != "api.mangadex.org" && host != domain) return null
        val segments = link.pathSegments.filter(String::isNotBlank)
        if (segments.isEmpty()) return null
        val type = segments[0]
        when (type) {
            "title", "manga" -> {
                if (segments.size < 2) return null
                val id = segments[1]
                val seed = Manga(
                    id = generateUid(source, id),
                    title = "",
                    altTitles = emptySet(),
                    url = "/manga/$id",
                    publicUrl = "https://mangadex.org/title/$id",
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
            "chapter" -> {
                if (segments.size < 2) return null
                val chId = segments[1]
                val chJson = runCatching { getJson("https://$domain/chapter/$chId") }.getOrNull() ?: return null
                val rels = chJson.optJSONObject("data")?.optJSONArray("relationships") ?: return null
                var mangaId: String? = null
                for (i in 0 until rels.length()) {
                    val rel = rels.optJSONObject(i) ?: continue
                    if (rel.optString("type") == "manga") {
                        mangaId = rel.optString("id").takeIf(String::isNotBlank)
                        if (mangaId != null) break
                    }
                }
                val id = mangaId ?: return null
                val seed = Manga(
                    id = generateUid(source, id),
                    title = "",
                    altTitles = emptySet(),
                    url = "/manga/$id",
                    publicUrl = "https://mangadex.org/title/$id",
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
            else -> return null
        }
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val dynamicTags = LinkedHashSet<MangaTag>()
        runCatching {
            val json = getJson("https://$domain/manga/tag")
            val data = json.optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
                    val name = item.optJSONObject("attributes")?.optJSONObject("name")?.optString("en")
                        ?.takeIf(String::isNotBlank) ?: continue
                    dynamicTags.add(MangaTag(name, id, source))
                }
            }
        }

        val tags = if (dynamicTags.isNotEmpty()) {
            dynamicTags
        } else {
            buildSet {
                ALL_TAGS.forEach { (name, id) -> add(MangaTag(name, id, source)) }
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
                ContentRating.ADULT,
            ),
            availableDemographics = EnumSet.of(
                Demographic.SHOUNEN,
                Demographic.SHOUJO,
                Demographic.SEINEN,
                Demographic.JOSEI,
            ),
            availableLocales = setOf(
                Locale("id"),
                Locale("en"),
                Locale("ja"),
                Locale("ko"),
                Locale("zh"),
                Locale("es"),
                Locale("fr"),
                Locale("ru"),
                Locale("pt"),
                Locale("de"),
                Locale("it"),
                Locale("ar"),
                Locale("vi"),
                Locale("th"),
                Locale("tr"),
                Locale("pl"),
            ),
        )
    }

    private suspend fun fetchChapters(mangaId: String): List<MangaChapter> {
        val chapters = ArrayList<MangaChapter>()
        var offset = 0
        val maxChapters = 3000

        while (offset < maxChapters) {
            val feedUrl = "https://$domain/manga/$mangaId/feed".toHttpUrl().newBuilder()
                .addQueryParameter("limit", "500")
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("includes[]", "scanlation_group")
                .addQueryParameter("order[volume]", "asc")
                .addQueryParameter("order[chapter]", "asc")
                .addQueryParameter("contentRating[]", "safe")
                .addQueryParameter("contentRating[]", "suggestive")
                .addQueryParameter("contentRating[]", "erotica")
                .addQueryParameter("contentRating[]", "pornographic")
                .build()
                .toString()

            val feedJson = runCatching { getJson(feedUrl) }.getOrNull() ?: break
            val feedData = feedJson.optJSONArray("data") ?: break
            val len = feedData.length()
            if (len == 0) break

            for (i in 0 until len) {
                val chObj = feedData.optJSONObject(i) ?: continue
                val chAttr = chObj.optJSONObject("attributes") ?: continue
                val externalUrl = chAttr.optString("externalUrl").takeIf(String::isNotBlank)
                if (externalUrl != null) continue // Skip external reader links (e.g. MangaPlus)

                val chId = chObj.optString("id").takeIf(String::isNotBlank) ?: continue
                val chNum = chAttr.optString("chapter").toFloatOrNull() ?: 0f
                val chVol = chAttr.optString("volume").toIntOrNull() ?: 0
                val rawTitle = chAttr.optString("title").trim()
                val chNumStr = chAttr.optString("chapter").trim()
                val lang = chAttr.optString("translatedLanguage").trim().ifBlank { "en" }
                val branch = lang.uppercase(Locale.ROOT)
                val uploadDate = parseIsoDate(chAttr.optString("publishAt").ifBlank { chAttr.optString("createdAt") })

                var scanlator: String? = null
                val rels = chObj.optJSONArray("relationships")
                if (rels != null) {
                    for (r in 0 until rels.length()) {
                        val rel = rels.optJSONObject(r) ?: continue
                        if (rel.optString("type") == "scanlation_group") {
                            scanlator = rel.optJSONObject("attributes")?.optString("name")?.takeIf(String::isNotBlank)
                            if (scanlator != null) break
                        }
                    }
                }

                val title = when {
                    rawTitle.isNotBlank() && chNumStr.isNotBlank() -> "Ch. $chNumStr - $rawTitle"
                    rawTitle.isNotBlank() -> rawTitle
                    chNumStr.isNotBlank() -> "Chapter $chNumStr"
                    else -> "Chapter ${formatChapterNumber(chNum)}"
                }

                chapters.add(
                    MangaChapter(
                        id = generateUid(source, chId),
                        title = title,
                        number = chNum,
                        volume = chVol,
                        url = "/chapter/$chId",
                        scanlator = scanlator,
                        uploadDate = uploadDate,
                        branch = branch,
                        source = source,
                    ),
                )
            }

            val total = feedJson.optInt("total", 0)
            offset += len
            if (offset >= total) break
        }

        chapters.sortWith(compareBy({ it.volume }, { it.number }))
        return chapters
    }

    private suspend fun searchAuthorIds(name: String): List<String> {
        val url = "https://$domain/author".toHttpUrl().newBuilder()
            .addQueryParameter("name", name)
            .addQueryParameter("limit", "5")
            .build()
            .toString()
        val json = runCatching { getJson(url) }.getOrNull() ?: return emptyList()
        val data = json.optJSONArray("data") ?: return emptyList()
        val ids = ArrayList<String>(data.length())
        for (i in 0 until data.length()) {
            data.optJSONObject(i)?.optString("id")?.takeIf(String::isNotBlank)?.let(ids::add)
        }
        return ids
    }

    private fun parseManga(obj: JSONObject): Manga {
        val id = obj.getString("id")
        val attr = obj.optJSONObject("attributes") ?: JSONObject()
        val titlesObj = attr.optJSONObject("title")

        val title = titlesObj?.optString("id")?.takeIf(String::isNotBlank)
            ?: titlesObj?.optString("en")?.takeIf(String::isNotBlank)
            ?: titlesObj?.optString("ja-ro")?.takeIf(String::isNotBlank)
            ?: titlesObj?.keys()?.asSequence()?.firstOrNull()?.let { titlesObj.optString(it) }?.takeIf(String::isNotBlank)
            ?: "MangaDex $id"

        val altTitles = buildSet {
            attr.optJSONArray("altTitles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    for (key in item.keys()) {
                        val v = item.optString(key).trim()
                        if (v.isNotEmpty()) add(v)
                    }
                }
            }
        }

        val descObj = attr.optJSONObject("description")
        val description = descObj?.optString("id")?.takeIf(String::isNotBlank)
            ?: descObj?.optString("en")?.takeIf(String::isNotBlank)
            ?: descObj?.keys()?.asSequence()?.firstOrNull()?.let { descObj.optString(it) }?.takeIf(String::isNotBlank)

        val state = when (attr.optString("status")) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "cancelled" -> MangaState.ABANDONED
            else -> MangaState.ONGOING
        }

        val contentRating = when (attr.optString("contentRating")) {
            "safe" -> ContentRating.SAFE
            "suggestive" -> ContentRating.SUGGESTIVE
            "erotica", "pornographic" -> ContentRating.ADULT
            else -> ContentRating.SAFE
        }

        val rels = obj.optJSONArray("relationships")
        var coverFileName: String? = null
        val authors = LinkedHashSet<String>()
        if (rels != null) {
            for (i in 0 until rels.length()) {
                val rel = rels.optJSONObject(i) ?: continue
                val type = rel.optString("type")
                val relAttr = rel.optJSONObject("attributes")
                if (type == "cover_art" && relAttr != null) {
                    coverFileName = relAttr.optString("fileName").takeIf(String::isNotBlank)
                } else if ((type == "author" || type == "artist") && relAttr != null) {
                    relAttr.optString("name").takeIf(String::isNotBlank)?.let(authors::add)
                }
            }
        }

        val coverUrl = coverFileName?.let { "https://uploads.mangadex.org/covers/$id/$it.512.jpg" }
        val largeCoverUrl = coverFileName?.let { "https://uploads.mangadex.org/covers/$id/$it" }

        val tags = buildSet {
            attr.optJSONArray("tags")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val tagObj = arr.optJSONObject(i) ?: continue
                    val tagId = tagObj.optString("id")
                    val nameObj = tagObj.optJSONObject("attributes")?.optJSONObject("name")
                    val name = nameObj?.optString("en")?.takeIf(String::isNotBlank) ?: tagId
                    if (tagId.isNotBlank()) {
                        add(MangaTag(name, tagId, source))
                    }
                }
            }
        }

        val relativeUrl = "/manga/$id"
        val publicUrl = "https://mangadex.org/title/$id"

        return Manga(
            id = generateUid(source, id),
            title = title,
            altTitles = altTitles,
            url = relativeUrl,
            publicUrl = publicUrl,
            rating = RATING_UNKNOWN,
            contentRating = contentRating,
            coverUrl = coverUrl,
            tags = tags,
            state = state,
            authors = authors,
            largeCoverUrl = largeCoverUrl,
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

    private companion object {
        private val DATE_FORMATS = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
        )

        private val ALL_TAGS = listOf(
            // content
            "Gore" to "b29d6a3d-1569-4e7a-8caf-7557bc92cd5d",
            "Sexual Violence" to "97893a4c-12af-4dac-b6be-0dffb353568e",
            // format
            "4-Koma" to "b11fda93-8f1d-4bef-b2ed-8803d3733170",
            "Adaptation" to "f4122d1c-3b44-44d0-9936-ff7502c39ad3",
            "Anthology" to "51d83883-4103-437c-b4b1-731cb73d786c",
            "Award Winning" to "0a39b5a1-b235-4886-a747-1d05d216532d",
            "Doujinshi" to "b13b2a48-c720-44a9-9c77-39c9979373fb",
            "Fan Colored" to "7b2ce280-79ef-4c09-9b58-12b7c23a9b78",
            "Full Color" to "f5ba408b-0e7a-484d-8d49-4e9125ac96de",
            "Long Strip" to "3e2b8dae-350e-4ab8-a8ce-016e844b9f0d",
            "Official Colored" to "320831a8-4026-470b-94f6-8353740e6f04",
            "Oneshot" to "0234a31e-a729-4e28-9d6a-3f87c4966b9e",
            "Self-Published" to "891cf039-b895-47f0-9229-bef4c96eccd4",
            "Web Comic" to "e197df38-d0e7-43b5-9b09-2842d0c326dd",
            // genre
            "Action" to "391b0423-d847-456f-aff0-8b0cfc03066b",
            "Adventure" to "87cc87cd-a395-47af-b27a-93258283bbc6",
            "Boys' Love" to "5920b825-4181-4a17-beeb-9918b0ff7a30",
            "Comedy" to "4d32cc48-9f00-4cca-9b5a-a839f0764984",
            "Crime" to "5ca48985-9a9d-4bd8-be29-80dc0303db72",
            "Drama" to "b9af3a63-f058-46de-a9a0-e0c13906197a",
            "Fantasy" to "cdc58593-87dd-415e-bbc0-2ec27bf404cc",
            "Girls' Love" to "a3c67850-4684-404e-9b7f-c69850ee5da6",
            "Historical" to "33771934-028e-4cb3-8744-691e866a923e",
            "Horror" to "cdad7e68-1419-41dd-bdce-27753074a640",
            "Isekai" to "ace04997-f6bd-436e-b261-779182193d3d",
            "Magical Girls" to "81c836c9-914a-4eca-981a-560dad663e73",
            "Mecha" to "50880a9d-5440-4732-9afb-8f457127e836",
            "Medical" to "c8cbe35b-1b2b-4a3f-9c37-db84c4514856",
            "Mystery" to "ee968100-4191-4968-93d3-f82d72be7e46",
            "Philosophical" to "b1e97889-25b4-4258-b28b-cd7f4d28ea9b",
            "Psychological" to "3b60b75c-a2d7-4860-ab56-05f391bb889c",
            "Romance" to "423e2eae-a7a2-4a8b-ac03-a8351462d71d",
            "Sci-Fi" to "256c8bd9-4904-4360-bf4f-508a76d67183",
            "Slice of Life" to "e5301a23-ebd9-49dd-a0cb-2add944c7fe9",
            "Sports" to "69964a64-2f90-4d33-beeb-f3ed2875eb4c",
            "Superhero" to "7064a261-a137-4d3a-8848-2d385de3a99c",
            "Thriller" to "07251805-a27e-4d59-b488-f0bfbec15168",
            "Tragedy" to "f8f62932-27da-4fe4-8ee1-6779a8c5edba",
            "Wuxia" to "acc803a4-c95a-4c22-86fc-eb6b582d82a2",
            // theme
            "Aliens" to "e64f6742-c834-471d-8d72-dd51fc02b835",
            "Animals" to "3de8c75d-8ee3-48ff-98ee-e20a65c86451",
            "Cooking" to "ea2bc92d-1c26-4930-9b7c-d5c0dc1b6869",
            "Crossdressing" to "9ab53f92-3eed-4e9b-903a-917c86035ee3",
            "Delinquents" to "da2d50ca-3018-4cc0-ac7a-6b7d472a29ea",
            "Demons" to "39730448-9a5f-48a2-85b0-a70db87b1233",
            "Genderswap" to "2bd2e8d0-f146-434a-9b51-fc9ff2c5fe6a",
            "Ghosts" to "3bb26d85-09d5-4d2e-880c-c34b974339e9",
            "Gyaru" to "fad12b5e-68ba-460e-b933-9ae8318f5b65",
            "Harem" to "aafb99c1-7f60-43fa-b75f-fc9502ce29c7",
            "Incest" to "5bd0e105-4481-44ca-b6e7-7544da56b1a3",
            "Loli" to "2d1f5d56-a1e5-4d0d-a961-2193588b08ec",
            "Mafia" to "85daba54-a71c-4554-8a28-9901a8b0afad",
            "Magic" to "a1f53773-c69a-4ce5-8cab-fffcd90b1565",
            "Mahjong" to "cb562697-929f-4d28-9d66-6d3995bf2592",
            "Martial Arts" to "799c202e-7daa-44eb-9cf7-8a3c0441531e",
            "Military" to "ac72833b-c4e9-4878-b9db-6c8a4a99444a",
            "Monster Girls" to "dd1f77c5-dea9-4e2b-97ae-224af09caf99",
            "Monsters" to "36fd93ea-e8b8-445e-b836-358f02b3d33d",
            "Music" to "f42fbf9e-188a-447b-9fdc-f19dc1e4d685",
            "Ninja" to "489dd859-9b61-4c37-af75-5b18e88daafc",
            "Office Workers" to "92d6d951-ca5e-429c-ac78-451071cbf064",
            "Police" to "df33b754-73a3-4c54-80e6-1a74a8058539",
            "Post-Apocalyptic" to "9467335a-1b83-4497-9231-765337a00b96",
            "Reincarnation" to "0bc90acb-ccc1-44ca-a34a-b9f3a73259d0",
            "Reverse Harem" to "65761a2a-415e-47f3-bef2-a9dababba7a6",
            "Samurai" to "81183756-1453-4c81-aa9e-f6e1b63be016",
            "School Life" to "caaa44eb-cd40-4177-b930-79d3ef2afe87",
            "Shota" to "ddefd648-5140-4e5f-ba18-4eca4071d19b",
            "Supernatural" to "eabc5b4c-6aff-42f3-b657-3e90cbd00b75",
            "Survival" to "5fff9cde-849c-4d78-aab0-0d52b2ee1d25",
            "Time Travel" to "292e862b-2d17-4062-90a2-0356caa4ae27",
            "Traditional Games" to "31932a7e-5b8e-49a6-9f12-2afa39dc544c",
            "Vampires" to "d7d1730f-6eb0-4ba6-9437-602cac38664c",
            "Video Games" to "9438db5a-7e2a-4ac0-b39e-e0d95a34b8a8",
            "Villainess" to "d14322ac-4d6f-4e9b-afd9-629d5f4d8a41",
            "Virtual Reality" to "8c86611e-fab7-4986-9dec-d1a2f44acdd5",
            "Zombies" to "631ef465-9aba-4afb-b0fc-ea10efe274a8",
        )
    }
}
