package org.koitharu.kotatsu.parsers.site.id.legacy

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
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
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumSet
import java.util.Locale

/**
 * IKIRU (portal https://ikiru.id, current mirror https://09.ikiru.wtf).
 *
 * Migrated from WordPress to Nuxt SSR + REST API:
 *  - Browse / Search / Filters: GET /api/public/library/search
 *    query params: page, query, sortBy (popular|updated|title|rating|created),
 *    sort (asc|desc), type (MANGA|MANHWA|MANHUA), status (ONGOING|COMPLETED),
 *    genre (UUID genre id, repeatable)
 *  - Details: GET /api/public/manga/{slug} (JSON API) with HTML fallback (/manga/{slug}/)
 *  - Chapters: data.chapters.chapters -> /manga/{slug}/chapter-{num}
 *  - Reader: GET /manga/{slug}/chapter-{num} -> .rpage img
 *  - Genres: GET /api/user/genres -> data.allGenres (name, id, slug)
 */
public class IkiruParser(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.IKIRU,
    pageSize = 21,
) {
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(
        "09.ikiru.wtf",
        "ikiru.id",
        "08.ikiru.wtf",
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
        val builder = "${baseUrl()}api/public/library/search".toHttpUrlOrNull()!!.newBuilder()
        builder.addQueryParameter("page", page.toString())

        val (sortBy, sortDir) = orderToQuery(order)
        builder.addQueryParameter("sortBy", sortBy)
        builder.addQueryParameter("sort", sortDir)

        filter.query?.takeIf(String::isNotBlank)?.let { query ->
            builder.addQueryParameter("query", query.trim())
        }

        filter.types.firstNotNullOfOrNull(::typeToSite)?.let {
            builder.addQueryParameter("type", it)
        }

        filter.states.firstNotNullOfOrNull(::stateToSite)?.let {
            builder.addQueryParameter("status", it)
        }

        for (tag in filter.tags) {
            val genreId = resolveGenreId(tag.key)
            if (genreId.isNotBlank()) {
                builder.addQueryParameter("genre", genreId)
            }
        }

        val json = getJson(builder.build().toString())
        val mangasArray = json.optJSONObject("data")?.optJSONArray("mangas") ?: return emptyList()

        val result = ArrayList<Manga>(mangasArray.length())
        for (i in 0 until mangasArray.length()) {
            val item = mangasArray.optJSONObject(i) ?: continue
            val slug = item.optString("slug").takeIf(String::isNotBlank) ?: continue
            val title = item.optString("title").ifBlank { titleFromUrl(slug) }
            val cover = item.optString("featuredImage").takeIf(String::isNotBlank)
            val relativeUrl = "/manga/$slug/"
            val score = item.optJSONObject("metadata")?.optDouble("score")?.toFloat() ?: 0f
            val rating = if (score > 0f) (score / 10f).coerceIn(0f, 1f) else RATING_UNKNOWN

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
        val json = getJson("${baseUrl()}api/public/manga/$slug")
        val data = json.optJSONObject("data") ?: throw IllegalStateException("Empty manga data")

        val title = data.optString("title").ifBlank { manga.title }
        val descRaw = data.optString("description")
        val description = if (descRaw.isNotBlank()) {
            Jsoup.parse(descRaw).text()
        } else {
            manga.description
        }
        val coverUrl = data.optString("featuredImage").takeIf(String::isNotBlank) ?: manga.coverUrl
        val state = parseState(data.optString("status")) ?: manga.state

        val meta = data.optJSONObject("metadata")
        val score = meta?.optDouble("score")?.toFloat() ?: 0f
        val rating = if (score > 0f) (score / 10f).coerceIn(0f, 1f) else manga.rating

        val altTitles = buildSet {
            meta?.optJSONArray("alternateTitles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.optString(i).trim()
                    if (t.isNotEmpty()) add(t)
                }
            }
        }

        val authors = buildSet {
            meta?.optJSONArray("author")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("name")?.takeIf(String::isNotBlank)?.let(::add)
                }
            }
            meta?.optJSONArray("artist")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("name")?.takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }

        val tags = buildSet {
            meta?.optJSONArray("genre")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val g = arr.optJSONObject(i) ?: continue
                    val name = g.optString("name").trim()
                    val id = g.optString("id").trim()
                    if (name.isNotEmpty() && id.isNotEmpty()) {
                        add(MangaTag(name, id, source))
                    }
                }
            }
        }

        val chaptersList = mutableListOf<MangaChapter>()
        val chaptersObj = data.optJSONObject("chapters")
        val chaptersArr = chaptersObj?.optJSONArray("chapters")
        if (chaptersArr != null) {
            for (i in 0 until chaptersArr.length()) {
                val c = chaptersArr.optJSONObject(i) ?: continue
                val chNumber = c.optDouble("number", 0.0).toFloat()
                val chTitle = c.optString("title").trim()
                val numStr = formatChapterNumber(chNumber)
                val relativeUrl = "/manga/$slug/chapter-$numStr"
                val createdAt = c.optString("createdAt")
                chaptersList.add(
                    MangaChapter(
                        id = generateUid(source, relativeUrl),
                        title = if (chTitle.isNotBlank()) chTitle else "Chapter $numStr",
                        number = chNumber,
                        volume = 0,
                        url = relativeUrl,
                        scanlator = null,
                        uploadDate = parseIsoDate(createdAt),
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
        val mangaUrl = "${baseUrl()}manga/$slug"
        val document = getHtml(mangaUrl, documentHeaders(mangaUrl))

        val ld = parseSchemaJsonLd(document)
        val title = ld?.optString("name")?.ifBlank {
            document.selectFirst("h1")?.textOrNull() ?: manga.title
        } ?: manga.title

        val description = ld?.optString("description")
            ?.takeIf(String::isNotBlank)
            ?.let { org.jsoup.parser.Parser.unescapeEntities(it, false) }
            ?: document.selectFirst("meta[property=\"og:description\"]")?.attrOrNull("content")
            ?: manga.description

        val authors = parseAuthors(ld)
        val tags = parseGenres(document, ld)
        val state = parseState(ld?.optString("creativeWorkStatus")) ?: manga.state

        val rating = parseRating(document, ld)
        val coverUrl = document.selectFirst("meta[property=\"og:image\"]")?.attrOrNull("content")
            ?: document.selectFirst(".detail-cover img, img[itemprop=image]")?.let(::imageFromElement)
            ?: manga.coverUrl

        val chapters = document.select(".clist a.crow, .clist-items a.crow, a[class*=crow]")
            .mapNotNull { chapterFromElement(it, slug) }
            .distinctBy { it.url }
            .sortedBy { it.number }

        return manga.copy(
            title = title,
            altTitles = emptySet(),
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
            val url = "${baseUrl()}api/user/genres"
            val json = getJson(url)
            val allGenres = json.optJSONObject("data")?.optJSONArray("allGenres")
            if (allGenres != null) {
                for (i in 0 until allGenres.length()) {
                    val item = allGenres.optJSONObject(i) ?: continue
                    val name = item.optString("name").trim()
                    val id = item.optString("id").trim()
                    if (name.isNotEmpty() && id.isNotEmpty()) {
                        tags.add(MangaTag(name, id, source))
                    }
                }
            }
        }
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
        val allowedHosts = configKeyDomain.presetValues.toSet() + setOf("07.ikiru.wtf", "05.ikiru.wtf")
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

    private fun orderToQuery(order: SortOrder): Pair<String, String> = when (order) {
        SortOrder.ALPHABETICAL -> "title" to "asc"
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_WEEK,
        -> "popular" to "desc"
        SortOrder.RATING -> "rating" to "desc"
        SortOrder.NEWEST -> "created" to "desc"
        SortOrder.RELEVANCE -> "popular" to "desc"
        else -> "updated" to "desc"
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

    private fun chapterFromElement(element: Element, mangaSlug: String): MangaChapter? {
        val link = if (element.`is`("a[href]")) element else element.selectFirst("a[href]") ?: return null
        val href = link.attr("href").trim()
        val relativeUrl = href.toRelativeUrl(domain)
        val title = element.selectFirst(".cnum")?.textOrNull()
            ?: link.selectFirst("span, p")?.textOrNull()
            ?: link.textOrNull()
            ?: titleFromUrl(relativeUrl)
        val number = parseChapterNumber(if (title.isBlank()) relativeUrl else title)
        val dateText = element.selectFirst(".cdate")?.textOrNull()
            ?: element.selectFirst("time")?.attrOrNull("datetime")
            ?: element.selectFirst("time")?.textOrNull()

        return MangaChapter(
            id = generateUid(source, relativeUrl),
            title = title,
            number = number,
            volume = 0,
            url = relativeUrl,
            scanlator = null,
            uploadDate = parseRelativeOrDate(dateText),
            branch = null,
            source = source,
        )
    }

    private fun parseSchemaJsonLd(document: Document): JSONObject? {
        for (script in document.select("script[type=\"application/ld+json\"]")) {
            val graphJson = script.data()
            val start = graphJson.indexOf('{')
            if (start < 0) continue
            val end = findJsonEnd(graphJson, start)
            if (end < 0) continue
            val root = runCatching { JSONObject(graphJson.substring(start, end + 1)) }.getOrNull()
                ?: continue
            findBookOrComicSeries(root)?.let { return it }
        }
        return null
    }

    private fun findBookOrComicSeries(node: JSONObject): JSONObject? {
        node.optJSONArray("@graph")?.let { graph ->
            for (i in 0 until graph.length()) {
                graph.optJSONObject(i)?.let { findBookOrComicSeries(it) }?.let { return it }
            }
        }
        node.optJSONObject("mainEntity")?.let { findBookOrComicSeries(it)?.let { return it } }
        val type = node.opt("@type")
        val isBookOrSeries = when (type) {
            is JSONArray -> (0 until type.length()).any {
                val s = type.optString(it)
                s.contains("Book") || s.contains("ComicSeries")
            }
            else -> type?.toString()?.let { it.contains("Book") || it.contains("ComicSeries") } == true
        }
        if (isBookOrSeries && node.optString("name").isNotBlank()) return node
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
                    ?: resolveGenreId(name)
                add(MangaTag(name, key, source))
            }
        }
        if (fromMeta.isNotEmpty()) return fromMeta

        val genreVal = ld?.opt("genre")
        return buildSet {
            when (genreVal) {
                is JSONArray -> {
                    for (i in 0 until genreVal.length()) {
                        val name = genreVal.optString(i).trim()
                        if (name.isNotEmpty()) add(MangaTag(name, resolveGenreId(name), source))
                    }
                }
                is String -> {
                    genreVal.split(',').forEach { part ->
                        val name = part.trim()
                        if (name.isNotEmpty()) add(MangaTag(name, resolveGenreId(name), source))
                    }
                }
            }
        }
    }

    private fun parseAuthors(ld: JSONObject?): Set<String> = buildSet {
        if (ld == null) return@buildSet
        for (field in arrayOf("author", "illustrator", "creator")) {
            val v = ld.opt(field)
            when (v) {
                is String -> add(v)
                is JSONObject -> v.optString("name").takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun parseRating(element: Element, ld: JSONObject?): Float {
        val ldScore = ld?.optJSONObject("aggregateRating")?.optDouble("ratingValue")?.toFloat()
        if (ldScore != null && ldScore > 0f) {
            return (ldScore / 10f).coerceIn(0f, 1f)
        }
        val text = element.select("meta[itemprop=ratingValue]").eachAttr("content")
            .filter(String::isNotBlank)
            .ifEmpty { element.select(".numscore, .num, .score").eachText() }
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

    private fun formatChapterNumber(number: Float): String {
        return if (number % 1f == 0f) number.toInt().toString() else number.toString()
    }

    private fun parseIsoDate(raw: String?): Long {
        val date = raw?.trim()?.takeIf(String::isNotEmpty) ?: return 0L
        return DATE_FORMATS.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).parse(date)?.time
            }.getOrNull()
        } ?: 0L
    }

    private fun parseRelativeOrDate(raw: String?): Long {
        val text = raw?.trim()?.lowercase(Locale.ROOT) ?: return 0L
        val now = System.currentTimeMillis()
        val match = Regex("(\\d+)\\s*(detik|menit|jam|hari|minggu|bulan|tahun)").find(text)
        if (match != null) {
            val amount = match.groupValues[1].toLongOrNull() ?: return 0L
            val unit = match.groupValues[2]
            val millis = when (unit) {
                "detik" -> amount * 1000L
                "menit" -> amount * 60 * 1000L
                "jam" -> amount * 60 * 60 * 1000L
                "hari" -> amount * 24 * 60 * 60 * 1000L
                "minggu" -> amount * 7 * 24 * 60 * 60 * 1000L
                "bulan" -> amount * 30 * 24 * 60 * 60 * 1000L
                "tahun" -> amount * 365 * 24 * 60 * 60 * 1000L
                else -> 0L
            }
            return now - millis
        }
        return parseIsoDate(raw)
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

    private fun titleFromUrl(url: String): String = url
        .substringBefore('?').trimEnd('/').substringAfterLast('/')
        .replace('-', ' ')
        .split(' ')
        .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } }
        .ifBlank { "IKIRU" }

    private fun baseUrl(): String = "https://$domain/"

    private fun documentHeaders(referer: String): Headers = Headers.Builder()
        .add(CommonHeaders.ACCEPT, HTML_ACCEPT)
        .add(CommonHeaders.REFERER, referer)
        .add(CommonHeaders.SEC_FETCH_DEST, "document")
        .add(CommonHeaders.SEC_FETCH_MODE, "navigate")
        .add(CommonHeaders.SEC_FETCH_SITE, "same-origin")
        .add(CommonHeaders.UPGRADE_INSECURE_REQUESTS, "1")
        .build()

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

    private fun resolveGenreId(key: String): String {
        if (key.length == 36 && key.contains('-')) return key
        return FALLBACK_GENRES_MAP[key.lowercase(Locale.ROOT)]
            ?: FALLBACK_GENRES_MAP[key.slugify()]
            ?: key
    }

    private fun String.slugify(): String = lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

    private fun fallbackTags(): Set<MangaTag> = buildSet {
        FALLBACK_GENRES.forEach { (name, id) -> add(MangaTag(name, id, source)) }
    }

    private companion object {
        private const val HTML_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        private const val PAGE_IMAGE_SELECTOR = ".rpage img, div[class*=rpage] img, section[data-image-data=\"1\"] img"

        private val IMAGE_ATTRIBUTES = arrayOf(
            "data-src", "data-lazy-src", "data-original", "data-srcset", "srcset", "src",
        )
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val CHAPTER_NUMBER_REGEX = Regex("(?:chapter|chap|ch\\.?|episode|ep\\.?)\\s*([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
        private val DATE_FORMATS = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
        )

        private val FALLBACK_GENRES = listOf(
            "Fantasy" to "01a102f5-709a-732a-9656-05d3d83f712e",
            "Action" to "01a10313-2bb6-722c-9497-925e7b423c24",
            "Shounen" to "01a10313-2bb9-744f-b1ae-18f74252dd88",
            "Drama" to "01a102ef-6ba9-723d-ba9a-5ebb04dc0929",
            "Comedy" to "01a102f5-7099-7109-9bc2-9d5cb158ab54",
            "Romance" to "01a10319-9754-761b-9160-b1ab24806289",
            "Adventure" to "01a102f5-7098-77bc-9a1f-e1b12154fda0",
            "Seinen" to "01a102ef-6baa-757f-aeaf-d47ae169d241",
            "School Life" to "01a10319-9755-754e-92f3-a6c5cd8880eb",
            "Supernatural" to "01a102ef-6bac-72ca-abe5-f66a111a850a",
            "Slice of Life" to "01a102ef-6bab-72ea-82b6-aab5fc10bc80",
            "Ecchi" to "01a106ea-26e6-768e-81cb-d9fc14505433",
            "Harem" to "01a10319-9753-77af-a80b-b0b4517e71f3",
            "Martial Arts" to "01a106b8-81f1-76af-800c-3aa006417294",
            "Sci-fi" to "01a10648-6664-759e-9be9-b9d14931e84e",
            "Mature" to "01a106e5-6106-765b-98ae-d4eb4b16357c",
            "Mystery" to "01a10644-5385-7778-b347-a3bb2f354b11",
            "Shoujo" to "01a1067e-6943-720e-90c7-a3c6e442960b",
            "Historical" to "01a106e3-f9b0-7508-9191-d47788c973fc",
            "Psychological" to "01a10694-5e5f-768d-94fe-78d03c45373e",
            "Horror" to "01a10694-5e5e-7555-bd40-a3e145b5a7f2",
            "Tragedy" to "01a10697-0e7a-75a3-97fc-a13f8a3fe53d",
            "Adult" to "01a10720-3e1f-73c8-8c46-c6408f30e826",
            "Gender Bender" to "01a106f3-9f51-774b-bc61-840e690e766a",
            "Josei" to "01a10644-5385-7778-b347-9c53be2aae91",
            "Sports" to "01a10677-e30b-7298-b280-63596f2cb99d",
            "Yuri" to "01a10725-ff6a-74f8-a955-79e16845bda6",
            "Shoujo Ai" to "01a10725-ff69-74f8-8811-d4e12b2a6e66",
            "Mecha" to "01a10934-11af-7519-bcfe-571b79aed954",
            "Lolicon" to "01a10c75-22cb-723d-817e-fb82b7d8befb",
            "Shounen Ai" to "01a10f05-0394-769e-a9c0-cf23fd23271e",
            "Smut" to "01a11053-1271-7658-b8f2-20670bfe872f",
            "Hentai" to "01a111f0-1504-71e1-92d9-3ff60d417ea3",
            "Shotacon" to "01a10920-de9b-7553-b728-a31d31475d6a",
            "Doujinshi" to "01a11053-1270-73b8-9314-5c6489b59d97",
            "One-Shot" to "01a106f8-8290-70ce-a46a-514575525feb",
            "Yaoi" to "01a12040-7792-75fc-879c-3b14e50c120d",
        )

        private val FALLBACK_GENRES_MAP = buildMap {
            FALLBACK_GENRES.forEach { (name, id) ->
                put(name.lowercase(Locale.ROOT), id)
                put(name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-'), id)
            }
        }
    }
}
