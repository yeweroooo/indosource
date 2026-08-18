package org.koitharu.kotatsu.parsers.site.id.legacy

import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.attrOrNull
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumSet
import java.util.Locale

/**
 * Kiryuu ID (https://v7.kiryuu.to, webtree https://kiryuu.io).
 *
 * WordPress backend with a custom Tailwind frontend:
 *  - Browse: GET /manga/ (date order) or POST admin-ajax.php?action=advanced_search
 *    (supports orderby=date|title|popular|rating + genre/type/status filters as
 *    JSON-array form fields + project=1 for project-only + query for search)
 *  - Details: /manga/{slug}/ (Yoast meta + #chapter-list)
 *  - Reader: /manga/{slug}/chapter-{n}.{id}/ -> section[data-image-data="1"] img
 *  - Filter taxonomies: embedded as `var searchTerms = {...}` in /advanced-search/
 */
public class KiryuuParser(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.KIRYUU,
    pageSize = 24,
) {
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(
        "v7.kiryuu.to",
        "kiryuu.io",
        "kiryuu.to",
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.ALPHABETICAL,
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
        val document = search(page, order, filter)
        return document.select(CARD_SELECTOR)
            .mapNotNull(::parseMangaCard)
            .distinctBy { it.url }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val mangaUrl = manga.url.toAbsoluteUrl(domain).substringBefore("?").removeSuffix("/")
        val document = getHtml(mangaUrl, documentHeaders(mangaUrl))

        val title = document.selectFirst("h1[itemprop=name]")?.textOrNull()
            ?: manga.title
        val altTitles = document.selectFirst("div.block.text-sm.text-text.line-clamp-1")
            ?.textOrNull()
            ?.split(',', ';')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?.toSet()
            .orEmpty()
        val tags = parseDetailTags(document)
        val description = document.selectFirst("div[itemprop=description][data-show=false]")?.textOrNull()
            ?: document.selectFirst("meta[name=description]")?.attrOrNull("content")
            ?: document.selectFirst("meta[property=\"og:description\"]")?.attrOrNull("content")
            ?: manga.description
        val coverUrl = document.selectFirst("img.wp-post-image")?.let(::imageFromElement)
            ?: manga.coverUrl
        val state = parseState(detailMetaValue(document, "Status"))
        val chapters = document.select("#chapter-list div[data-chapter-number]")
            .mapNotNull(::chapterFromElement)
            .sortedBy { it.number }

        return manga.copy(
            title = title,
            altTitles = altTitles.ifEmpty { manga.altTitles },
            rating = parseRating(document).takeUnless { it == RATING_UNKNOWN } ?: manga.rating,
            contentRating = sourceContentRating,
            coverUrl = coverUrl,
            largeCoverUrl = coverUrl,
            tags = tags.ifEmpty { manga.tags },
            state = state ?: manga.state,
            authors = emptySet(),
            description = description,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val document = getHtml(chapterUrl, documentHeaders(chapterUrl))
        val images = document.select(PAGE_IMAGE_SELECTOR)
            .mapNotNull(::imageFromElement)
            .filter(::isLikelyPageImage)
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

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = LinkedHashSet<MangaTag>()
        runCatching {
            val searchTerms = parseSearchTerms()
            searchTerms.optJSONArray("genre")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val name = item.optString("name").trim().takeIf(String::isNotEmpty) ?: continue
                    val slug = item.optString("slug").trim().takeIf(String::isNotEmpty) ?: continue
                    tags.add(MangaTag(name, slug, source))
                }
            }
        }
        tags.add(MangaTag(PROJECT_TAG_TITLE, PROJECT_TAG_KEY, source))
        return MangaListFilterOptions(
            availableTags = tags.ifEmpty { fallbackTags() },
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
            availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE),
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
            title = "Kiryuu manga",
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

    private suspend fun search(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): Document {
        val form = FormBody.Builder()
            .add("page", page.toString())
            .add("orderby", orderToQuery(order))
        filter.query?.takeIf(String::isNotBlank)?.let { form.add("query", it) }
        filter.tags.forEach { tag ->
            when (tag.key) {
                PROJECT_TAG_KEY -> form.add("project", "1")
                else -> form.add("genre", jsonArrayOf(tag.key))
            }
        }
        filter.types.mapNotNull(::typeToSite).forEach { form.add("type", jsonArrayOf(it)) }
        filter.states.firstNotNullOfOrNull(::stateToSite)?.let { form.add("status", jsonArrayOf(it)) }
        return postHtml(adminAjaxUrl("advanced_search"), form.build(), ajaxHeaders(baseUrl()))
    }

    private fun jsonArrayOf(value: String): String = """["$value"]"""

    private fun orderToQuery(order: SortOrder): String = when (order) {
        SortOrder.ALPHABETICAL -> "title"
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_WEEK,
        -> "popular"
        SortOrder.RATING -> "rating"
        else -> "date"
    }

    private fun stateToSite(state: MangaState): String? = when (state) {
        MangaState.ONGOING -> "ongoing"
        MangaState.FINISHED -> "completed"
        MangaState.PAUSED -> "on-hiatus"
        MangaState.ABANDONED -> "cancelled"
        else -> null
    }

    private fun typeToSite(type: ContentType): String? = when (type) {
        ContentType.MANGA -> "manga"
        ContentType.MANHWA -> "manhwa"
        ContentType.MANHUA -> "manhua"
        else -> null
    }

    private fun parseMangaCard(element: Element): Manga? {
        val link = element.select("a[href]").firstOrNull { mangaRelativeUrl(it.attr("href")) != null }
            ?: return null
        val relativeUrl = mangaRelativeUrl(link.attr("href")) ?: return null

        val title = element.selectFirst("a.text-base.font-medium, h1[class*=line-clamp-2]")?.textOrNull()
            ?: element.parent()?.selectFirst("a.text-base.font-medium, h1[class*=line-clamp-2]")?.textOrNull()
            ?: element.selectFirst("img.wp-post-image")?.attrOrNull("alt")
            ?: titleFromUrl(relativeUrl)
        val cover = element.selectFirst("img.wp-post-image")?.let(::imageFromElement)

        return Manga(
            id = generateUid(source, relativeUrl),
            title = title,
            altTitles = emptySet(),
            url = relativeUrl,
            publicUrl = relativeUrl.toAbsoluteUrl(domain),
            rating = parseRating(element),
            contentRating = sourceContentRating,
            coverUrl = cover,
            tags = emptySet(),
            state = parseState(element.selectFirst("span.bg-accent, p.font-normal.text-xs")?.text()),
            authors = emptySet(),
            source = source,
        )
    }

    private fun chapterFromElement(element: Element): MangaChapter? {
        val link = element.selectFirst("a[href]") ?: return null
        val absoluteUrl = link.attrAsAbsoluteUrl("href") ?: return null
        val relativeUrl = absoluteUrl.toRelativeUrl(domain)
        val title = link.selectFirst("span, p")?.textOrNull() ?: titleFromUrl(relativeUrl)
        val number = parseChapterNumber(title)
        val uploadDate = element.selectFirst("time")?.attrOrNull("datetime")
            ?: element.selectFirst("time")?.textOrNull()

        return MangaChapter(
            id = generateUid(source, relativeUrl),
            title = title,
            number = number,
            volume = 0,
            url = relativeUrl,
            scanlator = null,
            uploadDate = parseChapterDate(uploadDate),
            branch = null,
            source = source,
        )
    }

    private fun parseDetailTags(document: Document): Set<MangaTag> = buildSet {
        document.select("a[itemprop=genre]").forEach { a ->
            val title = a.text().trim().takeIf(String::isNotEmpty) ?: return@forEach
            val key = a.attrAsAbsoluteUrl("href")
                ?.removeSuffix("/")
                ?.substringAfterLast('/')
                ?.takeIf(String::isNotEmpty)
                ?: title.slugify()
            add(MangaTag(title, key, source))
        }
    }

    private fun detailMetaValue(document: Document, label: String): String? {
        val rows = document.select("div.flex.sm\\:justify-between")
        for (row in rows) {
            val heading = row.selectFirst("span.font-semibold")?.text()?.trim() ?: continue
            if (heading.equals(label, ignoreCase = true)) {
                return row.selectFirst("p.font-normal")?.textOrNull()
                    ?: row.selectFirst("p.inline")?.textOrNull()
            }
        }
        return null
    }

    private fun parseState(raw: String?): MangaState? {
        val normalized = raw?.lowercase(Locale.ROOT) ?: return null
        return when {
            normalized.contains("ongoing") -> MangaState.ONGOING
            normalized.contains("completed") || normalized.contains("complete") || normalized.contains("tamat") -> MangaState.FINISHED
            normalized.contains("hiatus") || normalized.contains("hold") || normalized.contains("pause") -> MangaState.PAUSED
            normalized.contains("cancel") || normalized.contains("dropped") -> MangaState.ABANDONED
            else -> null
        }
    }

    private fun parseRating(element: Element): Float {
        val text = element.select("meta[itemprop=ratingValue], .numscore, .text-yellow-400").eachAttr("content")
            .filter(String::isNotBlank)
            .ifEmpty {
                element.select("meta[itemprop=ratingValue], .numscore, .text-yellow-400").eachText()
            }
            .firstOrNull()
            ?: return RATING_UNKNOWN
        val score = text.toFloatOrNull() ?: return RATING_UNKNOWN
        if (score <= 0f) return RATING_UNKNOWN
        return (score / 10f).coerceIn(0f, 1f)
    }

    private fun parseChapterNumber(title: String): Float {
        return CHAPTER_NUMBER_REGEX.find(title)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
            ?: 0f
    }

    private fun parseChapterDate(raw: String?): Long {
        val date = raw?.trim()?.takeIf(String::isNotEmpty) ?: return 0L
        return DATE_FORMATS.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).parse(date)?.time
            }.getOrNull()
        } ?: 0L
    }

    private fun imageFromElement(image: Element): String? {
        for (attribute in IMAGE_ATTRIBUTES) {
            val raw = image.attrOrNull(attribute) ?: continue
            if (raw.startsWith("data:", ignoreCase = true)) continue
            val src = if (attribute.contains("srcset", ignoreCase = true)) {
                bestFromSrcSet(raw, image.baseUri())
            } else {
                resolveUrl(raw, image.baseUri())
            }
            if (!src.isNullOrBlank()) return src
        }
        return null
    }

    private fun bestFromSrcSet(srcSet: String, baseUri: String): String? {
        return srcSet.split(',')
            .mapNotNull { candidate ->
                val parts = candidate.trim().split(WHITESPACE_REGEX, limit = 2)
                val url = parts.firstOrNull()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val weight = parts.getOrNull(1)?.filter { it.isDigit() || it == '.' }?.toFloatOrNull() ?: 0f
                resolveUrl(url, baseUri)?.let { it to weight }
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun resolveUrl(raw: String, baseUri: String): String? {
        val clean = raw.trim().substringBefore(' ').takeIf(String::isNotBlank) ?: return null
        if (clean.startsWith("data:", ignoreCase = true)) return null
        if (clean.startsWith("//")) return "https:$clean"
        val base = baseUri.toHttpUrlOrNull()
        return base?.resolve(clean)?.toString()
            ?: clean.toHttpUrlOrNull()?.toString()
            ?: clean.takeIf { it.startsWith("http", ignoreCase = true) }
    }

    private fun isLikelyPageImage(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        // note: do NOT filter on "ads" — it matches "uploads" (up-loads)
        if (lower.contains("logo") || lower.contains("banner") || lower.contains("avatar") || lower.contains("iklan")) {
            return false
        }
        return true
    }

    private fun mangaRelativeUrl(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#')
        val segments = path.split('/').filter(String::isNotBlank)
        // Card links are absolute (https://host/manga/slug/): strip scheme+host.
        val filtered = when {
            segments.firstOrNull()?.contains(':') == true -> segments.drop(2)
            segments.firstOrNull()?.contains('.') == true -> segments.drop(1)
            else -> segments
        }
        if (filtered.size != 2 || filtered.first() != MANGA_PATH) return null
        return "/$MANGA_PATH/${filtered[1]}/"
    }

    private fun titleFromUrl(url: String): String = url
        .substringBefore('?')
        .trimEnd('/')
        .substringAfterLast('/')
        .replace('-', ' ')
        .split(' ')
        .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }
        .ifBlank { "Kiryuu" }

    private fun String.slugify(): String = lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

    private suspend fun parseSearchTerms(): JSONObject {
        val url = "${baseUrl()}advanced-search/"
        val document = getHtml(url, documentHeaders(url))
        val script = document.select("script").firstOrNull { it.data().contains("var searchTerms = ") }?.data()
            ?: return JSONObject()
        val start = script.indexOf("var searchTerms = ")
        if (start < 0) return JSONObject()
        val jsonStart = start + "var searchTerms = ".length
        val jsonEnd = script.indexOf("};", jsonStart)
        if (jsonEnd < 0) return JSONObject()
        return runCatching {
            JSONObject(script.substring(jsonStart, jsonEnd + 1))
        }.getOrDefault(JSONObject())
    }

    private fun baseUrl(): String = "https://$domain/"

    private fun adminAjaxUrl(action: String): String =
        "${baseUrl()}wp-admin/admin-ajax.php?action=$action"

    private fun documentHeaders(referer: String): Headers = Headers.Builder()
        .add(CommonHeaders.ACCEPT, HTML_ACCEPT)
        .add(CommonHeaders.REFERER, referer)
        .add(CommonHeaders.SEC_FETCH_DEST, "document")
        .add(CommonHeaders.SEC_FETCH_MODE, "navigate")
        .add(CommonHeaders.SEC_FETCH_SITE, "same-origin")
        .add(CommonHeaders.UPGRADE_INSECURE_REQUESTS, "1")
        .build()

    private fun ajaxHeaders(referer: String): Headers = Headers.Builder()
        .add(CommonHeaders.ACCEPT, "text/html, */*; q=0.01")
        .add(CommonHeaders.REFERER, referer)
        .add(CommonHeaders.ORIGIN, baseUrl().removeSuffix("/"))
        .add(CommonHeaders.X_REQUESTED_WITH, "XMLHttpRequest")
        .add(CommonHeaders.SEC_FETCH_DEST, "empty")
        .add(CommonHeaders.SEC_FETCH_MODE, "cors")
        .add(CommonHeaders.SEC_FETCH_SITE, "same-origin")
        .build()

    private suspend fun getHtml(url: String, headers: Headers): Document {
        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .tag(MangaSource::class.java, source)
            .get()
            .build()
        return execute(request).parseHtml()
    }

    private suspend fun postHtml(
        url: String,
        body: FormBody,
        headers: Headers,
    ): Document {
        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .tag(MangaSource::class.java, source)
            .post(body)
            .build()
        return execute(request).parseHtml()
    }

    private suspend fun execute(request: Request): okhttp3.Response {
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
        return response
    }

    private fun fallbackTags(): Set<MangaTag> = buildSet {
        FALLBACK_GENRES.forEach { (name, slug) -> add(MangaTag(name, slug, source)) }
        add(MangaTag(PROJECT_TAG_TITLE, PROJECT_TAG_KEY, source))
    }

    private companion object {
        private const val MANGA_PATH = "manga"
        private const val PROJECT_TAG_KEY = "project"
        private const val PROJECT_TAG_TITLE = "Project"
        private const val HTML_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        private const val CARD_SELECTOR = "div:has(> a[href*=\"/manga/\"])"
        private const val PAGE_IMAGE_SELECTOR = "section[data-image-data=\"1\"] img"

        private val IMAGE_ATTRIBUTES = arrayOf(
            "data-src",
            "data-lazy-src",
            "data-original",
            "data-srcset",
            "srcset",
            "src",
        )
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val CHAPTER_NUMBER_REGEX = Regex("(?:chapter|chap|ch\\.?|episode|ep\\.?)\\s*([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
        private val DATE_FORMATS = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )

        private val FALLBACK_GENRES = listOf(
            "Action" to "action",
            "Adventure" to "adventure",
            "Comedy" to "comedy",
            "Drama" to "drama",
            "Fantasy" to "fantasy",
            "Harem" to "harem",
            "Horror" to "horror",
            "Isekai" to "isekai",
            "Magic" to "magic",
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
