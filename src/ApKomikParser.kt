package org.koitharu.kotatsu.parsers.site.id

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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
import tsuki.util.attrAsAbsoluteUrl
import tsuki.util.attrOrNull
import tsuki.util.await
import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.textOrNull
import tsuki.util.toAbsoluteUrl
import tsuki.util.toRelativeUrl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumSet
import java.util.Locale

/**
 * APKOMIK (https://01.apkomik.com, webtree https://apkomik.com).
 *
 * WordPress + themesia "mangastream12.0" theme. Cloudflare-protected on /manga/
 * for datacenter IPs (bypass needed for scraping from a VPS; residential/mobile
 * IPs usually pass through).
 *  - Browse: GET /manga/?order=latest|update|title|titlereverse|popular&page=N
 *    + genre[]={term_id}&status={ongoing|completed|hiatus}&type={manga|manhwa|...}
 *  - Search: GET /?s={query} (pagination /page/N/?s={query})
 *  - Details: /manga/{slug}/ (tsinfo panel + #chapterlist)
 *  - Chapter URLs: /{slug}-chapter-{num}-bahasa-indonesia/ (top-level slug)
 *  - Reader: ts_reader.run({...}) JSON inline -> sources[].images (cdnap.site CDN)
 *  - 63 genres scraped live from the /manga/ filter form (term_id + name)
 */
public class ApKomikParser(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.APKOMIK,
    pageSize = 30,
) {
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(
        "01.apkomik.com",
        "apkomik.com",
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.POPULARITY,
        SortOrder.RELEVANCE,
    )

    override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = true,
        isSearchSupported = true,
        isSearchWithFiltersSupported = false,
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
        val query = filter.query?.takeIf(String::isNotBlank)
        val url = if (query != null) {
            if (page <= 1) {
                "${baseUrl()}?s=${query.urlEncoded()}"
            } else {
                "${baseUrl()}page/$page/?s=${query.urlEncoded()}"
            }
        } else {
            val builder = "${baseUrl()}manga/".toHttpUrlOrNull()!!.newBuilder()
            builder.addQueryParameter("order", orderToQuery(order))
            filter.tags.forEach { tag ->
                if (tag.key.isNotBlank() && tag.key.all(Char::isDigit)) {
                    builder.addQueryParameter("genre[]", tag.key)
                }
            }
            filter.states.firstNotNullOfOrNull(::stateToSite)?.let {
                builder.addQueryParameter("status", it)
            }
            filter.types.firstNotNullOfOrNull(::typeToSite)?.let {
                builder.addQueryParameter("type", it)
            }
            if (page > 1) {
                builder.addQueryParameter("page", page.toString())
            }
            builder.build().toString()
        }
        val document = getHtml(url, documentHeaders(url))
        return document.select(CARD_SELECTOR)
            .mapNotNull(::parseMangaCard)
            .distinctBy { it.url }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val mangaUrl = manga.url.toAbsoluteUrl(domain).substringBefore("?").removeSuffix("/")
        val document = getHtml(mangaUrl, documentHeaders(mangaUrl))

        val title = document.selectFirst("h1.entry-title")?.textOrNull() ?: manga.title
        val altTitles = document.select("div.wd-full")
            .firstOrNull { it.selectFirst("b")?.text()?.contains("Alternative") == true }
            ?.selectFirst("span")
            ?.textOrNull()
            ?.split(',', ';')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?.toSet()
            .orEmpty()
        val tags = document.select("span.mgen a[rel=tag]").mapNotNull { a ->
            val tagTitle = a.text().trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val key = a.attrAsAbsoluteUrl("href")
                ?.removeSuffix("/")
                ?.substringAfterLast('/')
                ?.takeIf(String::isNotEmpty)
                ?: tagTitle.lowercase(Locale.ROOT)
            MangaTag(tagTitle, key, source)
        }.toSet()
        val description = document.selectFirst("div.entry-content[itemprop=description]")?.textOrNull()
            ?: manga.description
        val coverUrl = document.selectFirst("div.thumb img")?.let(::imageFromElement)
            ?: manga.coverUrl
        val state = parseState(tsInfoValue(document, "Status"))
        val type = tsInfoValue(document, "Type")
        val chapters = document.select("#chapterlist li[data-num]")
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

        val images = parseReaderImages(document)
            .ifEmpty {
                document.select("#readerarea img, .readercontent img, .chapterbody img")
                    .mapNotNull(::imageFromElement)
            }
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
            val url = "${baseUrl()}manga/"
            val document = getHtml(url, documentHeaders(url))
            val form = document.selectFirst("form.filters") ?: return@runCatching
            form.select("input[name=\"genre[]\"]").forEach { input ->
                val name = input.parent()?.selectFirst("label")?.text()?.trim()
                    ?: return@forEach
                val id = input.attr("value").trim()
                if (name.isNotEmpty() && id.isNotEmpty()) {
                    tags.add(MangaTag(name, id, source))
                }
            }
        }
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
                ContentType.HENTAI,
            ),
            availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE, ContentRating.ADULT),
            availableLocales = setOf(Locale("id")),
        )
    }

    override suspend fun resolveLink(link: HttpUrl): Manga? {
        val allowedHosts = configKeyDomain.presetValues.toSet()
        if (link.host !in allowedHosts) return null
        val path = link.pathSegments.filter(String::isNotBlank).firstOrNull() ?: return null

        val relativeUrl = when {
            path == MANGA_PATH && link.pathSegments.size >= 2 -> {
                "/$MANGA_PATH/${link.pathSegments[1]}/"
            }
            CHAPTER_URL_REGEX.matches(path) -> {
                val slug = CHAPTER_URL_REGEX.find(path)?.groupValues?.get(1) ?: return null
                "/$MANGA_PATH/$slug/"
            }
            else -> return null
        }
        val seed = Manga(
            id = generateUid(source, relativeUrl),
            title = "APKOMIK manga",
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

    private fun orderToQuery(order: SortOrder): String = when (order) {
        SortOrder.NEWEST -> "latest"
        SortOrder.ALPHABETICAL -> "title"
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_WEEK,
        -> "popular"
        SortOrder.UPDATED -> "update"
        else -> ""
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
        ContentType.HENTAI -> "novel"
        else -> null
    }

    private fun parseMangaCard(element: Element): Manga? {
        val link = element.select("a[href]").firstOrNull { mangaRelativeUrl(it.attr("href")) != null }
            ?: return null
        val relativeUrl = mangaRelativeUrl(link.attr("href")) ?: return null

        val title = element.selectFirst("div.tt")?.textOrNull()
            ?: element.selectFirst("a[title]")?.attrOrNull("title")
            ?: element.selectFirst("img")?.attrOrNull("alt")
            ?: titleFromUrl(relativeUrl)
        val cover = element.selectFirst("div.limit img")?.let(::imageFromElement)

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
            state = parseState(element.selectFirst("span.type")?.text()),
            authors = emptySet(),
            source = source,
        )
    }

    private fun chapterFromElement(element: Element): MangaChapter? {
        val link = element.selectFirst(".eph-num a[href]") ?: return null
        val absoluteUrl = link.attrAsAbsoluteUrl("href") ?: return null
        val relativeUrl = absoluteUrl.toRelativeUrl(domain)
        val title = link.selectFirst(".chapternum")?.textOrNull() ?: titleFromUrl(relativeUrl)
        val number = parseChapterNumber(title)
        val uploadDate = link.selectFirst(".chapterdate")?.textOrNull()

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

    private fun parseReaderImages(document: Document): List<String> {
        val script = document.select("script").firstOrNull { it.data().contains("ts_reader.run") }?.data()
            ?: return emptyList()
        val start = script.indexOf("ts_reader.run(")
        if (start < 0) return emptyList()
        val jsonStart = script.indexOf('{', start)
        if (jsonStart < 0) return emptyList()
        val jsonEnd = findJsonEnd(script, jsonStart)
        if (jsonEnd < 0) return emptyList()
        return runCatching {
            val json = JSONObject(script.substring(jsonStart, jsonEnd + 1))
            val sources = json.optJSONArray("sources") ?: return emptyList()
            val images = sources.optJSONObject(0)?.optJSONArray("images") ?: return emptyList()
            buildList {
                for (i in 0 until images.length()) {
                    images.optString(i).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun findJsonEnd(text: String, openBrace: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in openBrace until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
        }
        return -1
    }

    private fun tsInfoValue(document: Document, label: String): String? {
        return document.select("div.tsinfo div.imptdt").firstOrNull { row ->
            row.text().trimStart().startsWith(label)
        }?.selectFirst("i, a")?.textOrNull()
    }

    private fun parseState(raw: String?): MangaState? {
        val normalized = raw?.lowercase(Locale.ROOT) ?: return null
        return when {
            normalized.contains("ongoing") -> MangaState.ONGOING
            normalized.contains("completed") || normalized.contains("tamat") -> MangaState.FINISHED
            normalized.contains("hiatus") -> MangaState.PAUSED
            normalized.contains("cancel") || normalized.contains("dropped") -> MangaState.ABANDONED
            else -> null
        }
    }

    private fun parseRating(element: Element): Float {
        val text = element.select("meta[itemprop=ratingValue]").eachAttr("content")
            .filter(String::isNotBlank)
            .ifEmpty {
                element.select("meta[itemprop=ratingValue], .num, .numscore").eachText()
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
        return DATE_FORMATS.firstNotNullOfOrNull { (pattern, locale) ->
            runCatching {
                SimpleDateFormat(pattern, locale).parse(date)?.time
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
        if (lower.contains("logo") || lower.contains("banner") || lower.contains("avatar") || lower.contains("readerarea")) {
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
        .ifBlank { "APKOMIK" }

    private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, "UTF-8")

    private fun baseUrl(): String = "https://$domain/"

    private fun documentHeaders(referer: String): okhttp3.Headers = okhttp3.Headers.Builder()
        .add(CommonHeaders.ACCEPT, HTML_ACCEPT)
        .add(CommonHeaders.REFERER, referer)
        .add(CommonHeaders.SEC_FETCH_DEST, "document")
        .add(CommonHeaders.SEC_FETCH_MODE, "navigate")
        .add(CommonHeaders.SEC_FETCH_SITE, "same-origin")
        .add(CommonHeaders.UPGRADE_INSECURE_REQUESTS, "1")
        .build()

    private suspend fun getHtml(url: String, headers: okhttp3.Headers): Document {
        val request = Request.Builder()
            .url(url)
            .headers(headers)
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
        FALLBACK_GENRES.forEach { (name, id) -> add(MangaTag(name, id, source)) }
    }

    private companion object {
        private const val MANGA_PATH = "manga"
        private const val HTML_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        private const val CARD_SELECTOR = "div.bsx"

        private val IMAGE_ATTRIBUTES = arrayOf(
            "data-src",
            "data-lazy-src",
            "data-original",
            "data-srcset",
            "srcset",
            "src",
        )
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val CHAPTER_URL_REGEX = Regex("^(.+)-chapter-([0-9]+(?:\\.[0-9]+)?)-bahasa-indonesia$")
        private val CHAPTER_NUMBER_REGEX = Regex("(?:chapter|chap|ch\\.?|episode|ep\\.?)\\s*([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
        private val DATE_FORMATS = arrayOf(
            "MMMM d, yyyy" to Locale("id", "ID"),
            "MMMM dd, yyyy" to Locale("id", "ID"),
            "d MMMM yyyy" to Locale("id", "ID"),
            "MMMM d, yyyy" to Locale.US,
            "yyyy-MM-dd'T'HH:mm:ss" to Locale.US,
        )

        private val FALLBACK_GENRES = listOf(
            "Action" to "14",
            "Adventure" to "15",
            "Comedy" to "2",
            "Drama" to "11",
            "Fantasy" to "16",
            "Harem" to "29",
            "Horror" to "136",
            "Isekai" to "17",
            "Magic" to "41",
            "Martial Arts" to "18",
            "Mecha" to "111",
            "Mystery" to "49",
            "Romance" to "22",
            "School Life" to "25",
            "Sci-fi" to "31",
            "Seinen" to "42",
            "Shounen" to "27",
            "Slice of Life" to "36",
            "Supernatural" to "24",
            "Sports" to "44",
            "Tragedy" to "56",
        )
    }
}
