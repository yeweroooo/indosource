package org.koitharu.kotatsu.parsers.site.id.legacy

import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
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
 * IKIRU (https://07.ikiru.wtf, webtree https://ikiru.id — a portal that links to
 * the current mirror; ikiru.id -> 05.ikiru.wtf -> 06 (ISP-blocked) -> 07.ikiru.wtf).
 *
 * WordPress + custom "readerkiru" theme (Tailwind + htmx), very similar to Kiryuu:
 *  - Browse: GET /manga/?the_page=N&the_genre={slugs}&the_status=&the_type=
 *    &orderby=date|title|popular|rating&order=asc|desc&project=1
 *  - Search + filters: POST wp-admin/admin-ajax.php?action=advanced_search (no nonce)
 *    FormData: page, query={title}, genre=["slug"..], status=[..], type=[..],
 *              orderby, order, project
 *  - Details: /manga/{slug}/ via embedded schema.org ComicSeries JSON-LD +
 *    #chapter-list div[data-chapter-number]
 *  - Reader: /manga/{slug}/chapter-{n}.{id}/ -> section[data-image-data="1"] img
 *  - Genres: 200 terms in `var searchTerms = {...}` on /advanced-search/ (dedupe by slug)
 */
public class IkiruParser(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.IKIRU,
    pageSize = 24,
) {
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(
        "07.ikiru.wtf",
        "ikiru.id",
        "05.ikiru.wtf",
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.ALPHABETICAL,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_WEEK,
        SortOrder.RATING,
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
        val document = if (query != null) {
            searchAdvanced(page, order, filter, query)
        } else {
            browseArchive(page, order, filter)
        }
        return document.select(CARD_SELECTOR)
            .mapNotNull(::parseMangaCard)
            .distinctBy { it.url }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val mangaUrl = manga.url.toAbsoluteUrl(domain).substringBefore("?").removeSuffix("/")
        val document = getHtml(mangaUrl, documentHeaders(mangaUrl))

        // schema.org JSON-LD (ComicSeries) carries title/synopsis/author/genres/status.
        val ld = parseSchemaJsonLd(document)
        val title = ld?.optString("name")?.ifBlank { manga.title } ?: manga.title
        // JSON-LD description may contain HTML entities (&#8220; etc.) — decode them.
        val description = ld?.optString("description")
            ?.takeIf(String::isNotBlank)
            ?.let { org.jsoup.parser.Parser.unescapeEntities(it, false) }
            ?: manga.description
        val authors = parseAuthors(ld)
        val tags = parseGenres(document, ld)
        val state = parseState(ld?.optString("creativeWorkStatus"))
        val altTitles = emptySet<String>()
        val rating = parseRating(document)
        val coverUrl = document.selectFirst("meta[property=\"og:image\"]")?.attrOrNull("content")
            ?: document.selectFirst("img[itemprop=image]")?.let(::imageFromElement)
            ?: manga.coverUrl
        val chapters = document.select("#chapter-list div[data-chapter-number], .chapter-list div[data-chapter]")
            .mapNotNull(::chapterFromElement)
            .sortedBy { it.number }

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
            val url = "${baseUrl()}advanced-search/"
            val document = getHtml(url, documentHeaders(url))
            val script = document.select("script").firstOrNull { it.data().contains("var searchTerms = ") }?.data()
                ?: return@runCatching
            val start = script.indexOf("var searchTerms = ")
            if (start < 0) return@runCatching
            val jsonStart = start + "var searchTerms = ".length
            val jsonEnd = script.indexOf("};", jsonStart)
            if (jsonEnd < 0) return@runCatching
            val terms = runCatching { JSONObject(script.substring(jsonStart, jsonEnd + 1)) }.getOrNull()
                ?: return@runCatching
            val genres = terms.optJSONObject("genre") ?: return@runCatching
            val seen = HashSet<String>()
            for (key in genres.keys()) {
                val item = genres.optJSONObject(key) ?: continue
                val name = item.optString("name").trim()
                val slug = item.optString("slug").trim()
                if (name.isEmpty() || slug.isEmpty()) continue
                if (!seen.add(slug)) continue
                tags.add(MangaTag(name, slug, source))
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
        if (!link.pathSegments.firstOrNull().equals("manga")) return null
        if (link.pathSegments.size < 2) return null
        val slug = link.pathSegments[1]
        val relativeUrl = "/manga/$slug/"
        val seed = Manga(
            id = generateUid(source, relativeUrl),
            title = "IKIRU manga",
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

    private suspend fun browseArchive(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): Document {
        // IKIRU ignores query-based pagination (?the_page/N); use the WordPress
        // path style /manga/page/N/ for page 2+ (page 1 = /manga/).
        val base = if (page > 1) "${baseUrl()}manga/page/$page/" else "${baseUrl()}manga/"
        val builder = base.toHttpUrlOrNull()!!.newBuilder()
        builder.addQueryParameter("orderby", orderToQuery(order).first)
        builder.addQueryParameter("order", orderToQuery(order).second)
        val genreSlugs = filter.tags
            .filterNot { it.key == PROJECT_TAG_KEY }
            .map { it.key }
        if (genreSlugs.isNotEmpty()) builder.addQueryParameter("the_genre", genreSlugs.joinToString(","))
        filter.states.firstNotNullOfOrNull(::stateToSite)?.let { builder.addQueryParameter("the_status", it) }
        filter.types.firstNotNullOfOrNull(::typeToSite)?.let { builder.addQueryParameter("the_type", it) }
        if (filter.tags.any { it.key == PROJECT_TAG_KEY }) builder.addQueryParameter("project", "1")
        return getHtml(builder.build().toString(), documentHeaders(baseUrl()))
    }

    private suspend fun searchAdvanced(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
        query: String,
    ): Document {
        val form = FormBody.Builder()
            .add("page", page.toString())
            .add("query", query)
        val (orderby, orderVal) = orderToQuery(order)
        form.add("orderby", orderby)
        form.add("order", orderVal)
        if (filter.tags.any { it.key == PROJECT_TAG_KEY }) form.add("project", "1")
        val genres = filter.tags.filterNot { it.key == PROJECT_TAG_KEY }.map { it.key }
        if (genres.isNotEmpty()) form.add("genre", JSONArrayOfStrings(genres))
        filter.states.firstNotNullOfOrNull(::stateToSite)?.let { form.add("status", "[\"$it\"]") }
        filter.types.firstNotNullOfOrNull(::typeToSite)?.let { form.add("type", "[\"$it\"]") }
        return postHtml(adminAjaxUrl("advanced_search"), form.build(), ajaxHeaders())
    }

    private fun JSONArrayOfStrings(values: List<String>): String =
        values.joinToString(",", "[", "]") { "\"$it\"" }

    private fun orderToQuery(order: SortOrder): Pair<String, String> = when (order) {
        SortOrder.ALPHABETICAL -> "title" to "asc"
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_WEEK,
        -> "popular" to "desc"
        SortOrder.RATING -> "rating" to "desc"
        else -> "date" to "desc"
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
        ContentType.COMICS -> "comic"
        else -> null
    }

    private fun parseMangaCard(element: Element): Manga? {
        val link = element.select("a[href]").firstOrNull { mangaRelativeUrl(it.attr("href")) != null }
            ?: return null
        val relativeUrl = mangaRelativeUrl(link.attr("href")) ?: return null
        val title = element.selectFirst("h1[class*=line-clamp-2], a[title], .title, div.tt")?.textOrNull()
            ?: element.selectFirst("img")?.attrOrNull("alt")
            ?: titleFromUrl(relativeUrl)
        val cover = element.selectFirst("img.wp-post-image, img[itemprop=image]")?.let(::imageFromElement)

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
            state = parseState(element.selectText(".text-accent, .status, span[class*=status]")),
            authors = emptySet(),
            source = source,
        )
    }

    private fun chapterFromElement(element: Element): MangaChapter? {
        val link = element.selectFirst("a[href]") ?: return null
        val absoluteUrl = link.attrAsAbsoluteUrl("href") ?: return null
        val relativeUrl = absoluteUrl.toRelativeUrl(domain)
        val title = link.selectFirst("span, p")?.textOrNull() ?: titleFromUrl(relativeUrl)
        val number = parseChapterNumber(if (title.isBlank()) relativeUrl else title)
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

    private fun parseSchemaJsonLd(document: Document): JSONObject? {
        // The page embeds several schema.org blocks (WebPage @graph wrapper,
        // ComicSeries, BreadcrumbList). The ComicSeries one is NOT always first,
        // so scan all blocks and descend @graph / mainEntity wrappers to find it.
        for (script in document.select("script[type=\"application/ld+json\"]")) {
            val graphJson = script.data()
            val start = graphJson.indexOf('{')
            if (start < 0) continue
            val end = findJsonEnd(graphJson, start)
            if (end < 0) continue
            val root = runCatching { JSONObject(graphJson.substring(start, end + 1)) }.getOrNull()
                ?: continue
            findComicSeries(root)?.let { return it }
        }
        return null
    }

    private fun findComicSeries(node: JSONObject): JSONObject? {
        node.optJSONArray("@graph")?.let { graph ->
            for (i in 0 until graph.length()) {
                graph.optJSONObject(i)?.let { findComicSeries(it) }?.let { return it }
            }
        }
        node.optJSONObject("mainEntity")?.let { findComicSeries(it)?.let { return it } }
        val type = node.opt("@type")
        val isComicSeries = when (type) {
            is JSONArray -> (0 until type.length()).any { type.optString(it).contains("ComicSeries") }
            else -> type?.toString()?.contains("ComicSeries") == true
        }
        if (isComicSeries && node.optString("name").isNotBlank()) return node
        return null
    }

    private fun parseGenres(element: Document, ld: JSONObject?): Set<MangaTag> {
        val fromMeta = buildSet {
            element.select("a[rel=tag]").forEach { a ->
                val name = a.text().trim().takeIf(String::isNotEmpty) ?: return@forEach
                val key = a.attrAsAbsoluteUrl("href")
                    ?.removeSuffix("/")
                    ?.substringAfterLast('/')
                    ?.takeIf(String::isNotEmpty)
                    ?: name.slugify()
                add(MangaTag(name, key, source))
            }
        }
        if (fromMeta.isNotEmpty()) return fromMeta
        val fromLd = ld?.optJSONArray("genre") ?: return emptySet()
        return buildSet {
            for (i in 0 until fromLd.length()) {
                val name = fromLd.optString(i).trim()
                if (name.isNotEmpty()) add(MangaTag(name, name.slugify(), source))
            }
        }
    }

    private fun parseAuthors(ld: JSONObject?): Set<String> = buildSet {
        if (ld == null) return@buildSet
        for (field in arrayOf("author", "illustrator")) {
            val v = ld.opt(field)
            when (v) {
                is String -> add(v)
                is JSONObject -> v.optString("name").takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun parseRating(element: Element): Float {
        val text = element.select("meta[itemprop=ratingValue]").eachAttr("content")
            .filter(String::isNotBlank)
            .ifEmpty { element.select("meta[itemprop=ratingValue], .numscore, .num, .text-accent").eachText() }
            .firstOrNull()
            ?: return RATING_UNKNOWN
        val score = text.toFloatOrNull() ?: return RATING_UNKNOWN
        if (score <= 0f) return RATING_UNKNOWN
        return (score / 10f).coerceIn(0f, 1f)
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

    private fun parseChapterNumber(input: String): Float {
        return CHAPTER_NUMBER_REGEX.find(input)
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
        val filtered = when {
            segments.firstOrNull()?.contains(':') == true -> segments.drop(2)
            segments.firstOrNull()?.contains('.') == true -> segments.drop(1)
            else -> segments
        }
        if (filtered.size < 2 || filtered.first() != "manga") return null
        return "/manga/${filtered[1]}/"
    }

    private fun String.slugify(): String = lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

    private fun titleFromUrl(url: String): String = url
        .substringBefore('?').trimEnd('/').substringAfterLast('/')
        .replace('-', ' ')
        .split(' ')
        .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } }
        .ifBlank { "IKIRU" }

    private fun Element.selectText(selector: String): String? =
        selectFirst(selector)?.textOrNull()

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

    private fun ajaxHeaders(): Headers = Headers.Builder()
        .add(CommonHeaders.ACCEPT, "text/html, */*; q=0.01")
        .add(CommonHeaders.ORIGIN, baseUrl().removeSuffix("/"))
        .add(CommonHeaders.X_REQUESTED_WITH, "XMLHttpRequest")
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
        FALLBACK_GENRES.forEach { slug -> add(MangaTag(slug.titlecase(), slug, source)) }
        add(MangaTag(PROJECT_TAG_TITLE, PROJECT_TAG_KEY, source))
    }

    private fun String.titlecase(): String = replaceFirstChar { it.titlecase(Locale.ROOT) }

    private companion object {
        private const val PROJECT_TAG_KEY = "project"
        private const val PROJECT_TAG_TITLE = "Project"
        private const val HTML_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        private const val CARD_SELECTOR = "div:has(> a[href*=\"/manga/\"])"
        private const val PAGE_IMAGE_SELECTOR = "section[data-image-data=\"1\"] img"

        private val IMAGE_ATTRIBUTES = arrayOf(
            "data-src", "data-lazy-src", "data-original", "data-srcset", "srcset", "src",
        )
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val CHAPTER_NUMBER_REGEX = Regex("(?:chapter|chap|ch\\.?|episode|ep\\.?)\\s*([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
        private val DATE_FORMATS = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )

        private val FALLBACK_GENRES = listOf(
            "action", "adventure", "comedy", "drama", "fantasy", "harem", "horror",
            "isekai", "magic", "manhua", "manhwa", "martial-arts", "mecha", "mystery",
            "romance", "school-life", "sci-fi", "seinen", "shounen", "slice-of-life",
            "sports", "supernatural", "tragedy", "yuri",
        )
    }
}