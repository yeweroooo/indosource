package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
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
import java.util.EnumSet
import java.util.Locale

/**
 * LumosKomik (https://03.lumosgg.com — portal https://lumosgg.com, numbered
 * mirrors like 03.lumosgg.com). Astro SSR site — everything is plain server-
 * rendered HTML over HTTP:
 *
 *  - Browse + search + filters: GET /browse?page=N&q={title}&status=ongoing|
 *    completed|hiatus&type=manga|manhwa|manhua&genre={slug}&sort=latest|
 *    popular|rating|az&minChapters=N (all combinable, server-side)
 *  - Card: a[href="/comic/{slug}"] with img cover + h3 title (+ rating badge)
 *  - Details: /comic/{slug} -> schema.org ComicSeries JSON-LD (title,
 *    description, author, illustrator, genres incl. type, rating) +
 *    Status/Type blocks + chapter list a[href="/read/{slug}/chapter-N"]
 *  - Reader: /read/{slug}/chapter-N -> img src on lms.imgsvr.my.id CDN
 */
public class LumosKomikParser(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.LUMOSKOMIK,
    pageSize = 24,
) {
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(
        "03.lumosgg.com",
        "lumosgg.com",
        "02.lumosgg.com",
        "01.lumosgg.com",
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.ALPHABETICAL,
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
        val builder = "${baseUrl()}browse".toHttpUrlOrNull()!!.newBuilder()
        if (page > 1) builder.addQueryParameter("page", page.toString())
        filter.query?.takeIf(String::isNotBlank)?.let { builder.addQueryParameter("q", it) }
        builder.addQueryParameter("sort", orderToSite(order))
        filter.states.firstNotNullOfOrNull(::stateToSite)?.let { builder.addQueryParameter("status", it) }
        filter.types.firstNotNullOfOrNull(::typeToSite)?.let { builder.addQueryParameter("type", it) }
        val genres = filter.tags.map { it.key }.filter(String::isNotBlank)
        if (genres.isNotEmpty()) {
            // The site joins selected genre slugs with a comma into a single
            // `genre` param (see its browse-form JS: slugs.join(",")) and
            // intersects them server-side.
            builder.addQueryParameter("genre", genres.joinToString(","))
        }
        val document = getHtml(builder.build().toString(), documentHeaders(baseUrl()))
        return parseCards(document).distinctBy { it.url }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val mangaUrl = manga.url.toAbsoluteUrl(domain).substringBefore("?").removeSuffix("/")
        val document = getHtml(mangaUrl, documentHeaders(mangaUrl))

        val ld = parseComicSeriesJsonLd(document)
        val title = ld?.optString("name")?.takeIf(String::isNotBlank) ?: manga.title
        val description = ld?.optString("description")?.takeIf(String::isNotBlank)?.trim()
        val authors = buildSet {
            if (ld != null) {
                for (field in arrayOf("author", "illustrator")) {
                    when (val v = ld.opt(field)) {
                        is String -> v.takeIf(String::isNotBlank)?.let(::add)
                        is JSONObject -> v.optString("name").takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }
        }
        val tags = parseGenres(document, ld)
        val state: MangaState? = parseStateLabel(document)?.let(::parseState)
            ?: ld?.optJSONArray("genre")?.let { genres ->
                var found: MangaState? = null
                for (i in 0 until genres.length()) {
                    when (genres.optString(i).lowercase(Locale.ROOT)) {
                        "ongoing" -> { found = MangaState.ONGOING; return@let found }
                        "completed" -> { found = MangaState.FINISHED; return@let found }
                        "hiatus" -> { found = MangaState.PAUSED; return@let found }
                    }
                }
                found
            }
        val rating = ld?.optJSONObject("aggregateRating")?.optString("ratingValue")
            ?.toFloatOrNull()?.div(10f)?.coerceIn(0f, 1f) ?: RATING_UNKNOWN
        val coverUrl = document.selectFirst("meta[property=\"og:image\"]")?.attrOrNull("content")
            ?: ld?.optString("image")?.takeIf(String::isNotBlank)
            ?: manga.coverUrl

        val chapters = document.select(CHAPTER_LINK_SELECTOR)
            .mapNotNull(::chapterFromElement)

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
            description = description ?: manga.description,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val document = getHtml(chapterUrl, documentHeaders(chapterUrl))
        return document.select(PAGE_IMAGE_SELECTOR)
            .mapNotNull { it.attrAsAbsoluteUrlOrNull("src") }
            .distinct()
            .mapIndexed { index, url ->
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
            val document = getHtml("${baseUrl()}browse", documentHeaders(baseUrl()))
            document.select(GENRE_CHECKBOX_SELECTOR).forEach { checkbox ->
                val slug = checkbox.attrOrNull("value")?.takeIf(String::isNotBlank) ?: return@forEach
                tags.add(MangaTag(genreTitle(slug), slug, source))
            }
        }
        return MangaListFilterOptions(
            availableTags = tags.toList().sortedBy { it.title.lowercase(Locale.ROOT) }.toSet(),
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
            availableLocales = setOf(Locale("id")),
        )
    }

    override suspend fun resolveLink(link: HttpUrl): Manga? {
        val allowedHosts = configKeyDomain.presetValues.toSet()
        if (link.host !in allowedHosts) return null
        val segments = link.pathSegments.filter(String::isNotBlank)
        if (segments.size < 2 || segments[0] != "comic") return null
        val relativeUrl = "/comic/${segments[1]}"
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

    private fun parseCards(document: Document): List<Manga> = document.select(CARD_SELECTOR)
        .mapNotNull(::parseCard)

    private fun parseCard(element: Element): Manga? {
        val href = element.attrOrNull("href") ?: return null
        val relativeUrl = comicRelativeUrl(href) ?: return null
        val container = element.closest("div.group") ?: element.parent() ?: element
        val title = container.selectFirst("h3")?.textOrNull()?.trim()
            ?: container.selectFirst("img")?.attrOrNull("alt")
            ?: titleFromUrl(relativeUrl)
        val cover = container.selectFirst("img")?.let(::imageFromElement)
        val rating = container.selectFirst(RATING_BADGE_SELECTOR)
            ?.textOrNull()?.trim()?.toFloatOrNull()?.div(10f)?.coerceIn(0f, 1f) ?: RATING_UNKNOWN
        return Manga(
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
    }

    private fun chapterFromElement(element: Element): MangaChapter? {
        val href = element.attrOrNull("href") ?: return null
        val absoluteUrl = resolveUrl(href) ?: return null
        val relativeUrl = absoluteUrl.substringAfter(domain, absoluteUrl)
        val number = element.attrOrNull("data-chapter")?.trim()?.toFloatOrNull()
            ?: CHAPTER_NUMBER_REGEX.find(href.substringAfterLast('/'))
                ?.groupValues?.getOrNull(1)?.toFloatOrNull()
            ?: 0f
        return MangaChapter(
            id = generateUid(source, relativeUrl),
            title = null,
            number = number,
            volume = 0,
            url = relativeUrl,
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = source,
        )
    }

    private fun parseComicSeriesJsonLd(document: Document): JSONObject? {
        for (script in document.select("script[type=\"application/ld+json\"]")) {
            val data = script.data()
            val root = runCatching { JSONObject(data) }.getOrNull() ?: continue
            // The block is an array of schema objects; ComicSeries carries "name".
            findComicSeries(root)?.let { return it }
        }
        return null
    }

    private fun findComicSeries(node: Any?): JSONObject? {
        return when (node) {
            is JSONObject -> {
                node.optJSONArray("@graph")?.let { graph ->
                    for (i in 0 until graph.length()) {
                        findComicSeries(graph.opt(i))?.let { return it }
                    }
                }
                node.optJSONArray("itemListElement")?.let { list ->
                    for (i in 0 until list.length()) {
                        findComicSeries(list.opt(i))?.let { return it }
                    }
                }
                val type = node.opt("@type")
                val isSeries = when (type) {
                    is JSONArray -> (0 until type.length()).any { type.optString(it) == "ComicSeries" }
                    else -> type?.toString() == "ComicSeries"
                }
                if (isSeries && node.optString("name").isNotBlank()) node else null
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findComicSeries(node.opt(i))?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    private fun parseGenres(document: Document, ld: JSONObject?): Set<MangaTag> {
        val fromLinks = document.select("a[href*=\"/browse?genre=\"]")
            .mapNotNull { it.textOrNull()?.trim()?.takeIf(String::isNotEmpty) }
            .toCollection(LinkedHashSet())
        val result = LinkedHashSet<MangaTag>()
        if (fromLinks.isNotEmpty()) {
            fromLinks.forEach { result.add(MangaTag(it, it.slugify(), source)) }
        } else {
            ld?.optJSONArray("genre")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val name = arr.optString(i).trim()
                    if (name.isNotEmpty()) result.add(MangaTag(name, name.slugify(), source))
                }
            }
        }
        return result
    }

    /**
     * Status block: div.bg-surface-800/50 > div.text-xs "Status" followed by
     * a sibling span with the value (e.g. "ongoing"). Match structurally to
     * avoid Tailwind class churn.
     */
    private fun parseStateLabel(document: Document): String? {
        for (label in document.select("div.text-xs")) {
            if (!label.text().equals("Status", ignoreCase = true)) continue
            var sibling = label.nextElementSibling()
            while (sibling != null) {
                val text = sibling.textOrNull()?.trim()
                if (!text.isNullOrBlank()) return text
                sibling = sibling.nextElementSibling()
            }
            label.parent()?.let { parent ->
                parent.select("span.capitalize, span.font-bold").firstOrNull()
                    ?.textOrNull()?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
            }
        }
        return null
    }

    private fun parseState(raw: String?): MangaState? {
        val n = raw?.lowercase(Locale.ROOT)?.trim() ?: return null
        return when {
            n.contains("ongoing") -> MangaState.ONGOING
            n.contains("completed") || n.contains("tamat") || n.contains("end") -> MangaState.FINISHED
            n.contains("hiatus") -> MangaState.PAUSED
            n.contains("cancelled") || n.contains("dropped") -> MangaState.ABANDONED
            else -> null
        }
    }

    private fun imageFromElement(image: Element): String? {
        for (attribute in IMAGE_ATTRIBUTES) {
            val value = image.attrOrNull(attribute) ?: continue
            if (value.startsWith("data:", ignoreCase = true)) continue
            val candidate = if (attribute.contains("srcset", ignoreCase = true)) {
                value.split(',').firstOrNull()?.trim()?.substringBefore(' ')
            } else {
                value.trim()
            }?.takeIf(String::isNotBlank) ?: continue
            image.attrAsAbsoluteUrlOrNull(attribute)?.let { return it }
            return resolveUrl(candidate)
        }
        return null
    }

    private fun resolveUrl(raw: String): String? {
        val clean = raw.trim().substringBefore(' ').takeIf(String::isNotBlank) ?: return null
        if (clean.startsWith("data:", ignoreCase = true)) return null
        if (clean.startsWith("//")) return "https:$clean"
        // Root-relative hrefs (e.g. /read/{slug}/chapter-N) — toHttpUrlOrNull
        // rejects them, which previously dropped every chapter from the list.
        if (clean.startsWith("/")) return "https://$domain$clean"
        return clean.toHttpUrlOrNull()?.toString()
            ?: clean.takeIf { it.startsWith("http", ignoreCase = true) }
    }

    private fun orderToSite(order: SortOrder): String = when (order) {
        SortOrder.POPULARITY -> "popular"
        SortOrder.RATING -> "rating"
        SortOrder.ALPHABETICAL -> "az"
        else -> "latest"
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

    private fun genreTitle(slug: String): String = slug.split('-', '_')
        .joinToString(" ") { part ->
            ACRONYMS.firstOrNull { acr -> part.equals(acr, ignoreCase = true) }?.uppercase(Locale.ROOT)
                ?: part.replaceFirstChar { c -> c.titlecase(Locale.ROOT) }
        }

    private fun comicRelativeUrl(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#')
        val segments = path.split('/').filter(String::isNotBlank)
        if (segments.size < 2 || segments[0] != "comic") return null
        // The site plants a bot-trap link (/comic/honeypot-trap-XXXX -> /api/honeypot/trap);
        // requesting it gets the client IP banned. Never follow it.
        if (segments[1].startsWith(HONEYPOT_PREFIX)) return null
        return "/comic/${segments[1]}"
    }

    private fun titleFromUrl(url: String): String = url
        .substringBefore('?').trimEnd('/').substringAfterLast('/')
        .replace('-', ' ')
        .replace('_', ' ')
        .split(' ')
        .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } }
        .ifBlank { "LumosKomik" }

    private fun baseUrl(): String = "https://$domain/"

    private fun documentHeaders(referer: String): Headers = Headers.Builder()
        .add(CommonHeaders.ACCEPT, HTML_ACCEPT)
        .add(CommonHeaders.REFERER, referer)
        .add(CommonHeaders.SEC_FETCH_DEST, "document")
        .add(CommonHeaders.SEC_FETCH_MODE, "navigate")
        .add(CommonHeaders.SEC_FETCH_SITE, "same-origin")
        .add(CommonHeaders.UPGRADE_INSECURE_REQUESTS, "1")
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

    private fun attrAsAbsoluteUrlOrNull(name: String, element: Element): String? {
        val raw = element.attrOrNull(name) ?: return null
        if (raw.startsWith("data:", ignoreCase = true)) return null
        return resolveUrl(raw)
    }

    private fun Element.attrAsAbsoluteUrlOrNull(name: String): String? =
        this@LumosKomikParser.attrAsAbsoluteUrlOrNull(name, this)

    private companion object {
        private const val HTML_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"

        /** each browse card anchor */
        private const val CARD_SELECTOR = "a[href^=\"/comic/\"]"
        private const val CHAPTER_LINK_SELECTOR = "a[href*=\"/read/\"][data-chapter]"
        private const val GENRE_CHECKBOX_SELECTOR = "input.bf-genre-cb[value]"
        private const val PAGE_IMAGE_SELECTOR = "img[src*=\"/file/comic/\"]"
        private const val RATING_BADGE_SELECTOR = "span.text-amber-400"

        private val IMAGE_ATTRIBUTES = arrayOf("src", "data-src", "data-lazy-src")
        private const val HONEYPOT_PREFIX = "honeypot"
        private val ACRONYMS = listOf("4-koma", "sci-fi", "bl", "gl")

        private val CHAPTER_NUMBER_REGEX =
            Regex("(?:chapter|chap|ch\\.?)[-_ ]*([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)

        private fun String.slugify(): String = lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }
}
