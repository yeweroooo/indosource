package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.HttpStatusException
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
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.EnumSet
import java.util.Locale

/**
 * Parser for MG Komik v2 (https://web1.mgkomik.cc).
 *
 * The v2 site is a custom server-rendered frontend (no WordPress/Madara AJAX anymore):
 *  - Browse archive:  /komik/?filter=&order_by={latest|alphabet|trending|views|new-manga}&page=N
 *  - Search:          /search/?q=...&status=...&types[]=...&genres[]=...&order_by=...&page=N
 *  - Details:         /komik/{slug}/
 *  - Reader:          /komik/{slug}/chapter-{n}/
 */
public class MGKomikParser(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.MGKOMIK,
    pageSize = 20,
) {
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(
        "web1.mgkomik.cc",
        "id.mgkomik.cc",
        "mgkomik.id",
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.ADDED,
        SortOrder.ALPHABETICAL,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_WEEK,
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

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add(CommonHeaders.USER_AGENT, config[userAgentKey])
        .add(CommonHeaders.REFERER, baseUrl())
        .add(CommonHeaders.ACCEPT_LANGUAGE, "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        .build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host.equals(domain, ignoreCase = true)) {
            return chain.proceed(
                request.newBuilder()
                    .header(CommonHeaders.SEC_FETCH_DEST, "document")
                    .header(CommonHeaders.SEC_FETCH_MODE, "navigate")
                    .header(CommonHeaders.SEC_FETCH_SITE, "same-origin")
                    .header(CommonHeaders.UPGRADE_INSECURE_REQUESTS, "1")
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
        val list = if (filter.isEmpty()) {
            getArchiveList(page, order)
        } else {
            getSearchList(page, order, filter)
        }
        return list.distinctBy { it.url }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val mangaUrl = manga.url.toAbsoluteUrl(domain).substringBefore("?").removeSuffix("/")
        val document = getHtml(mangaUrl, documentHeaders(mangaUrl))

        val title = document.selectFirst("div.manga-info-col h1.manga-title")
            ?.ownText()
            ?.ifBlank { null }
            ?: manga.title
        val altTitles = document.selectFirst("div.manga-title-alt")
            ?.textOrNull()
            ?.split('\u2022', ',', ';')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?.toSet()
            .orEmpty()
        val tags = parseDetailTags(document)
        val description = parseDescription(document)
        val coverUrl = document.selectFirst("div.manga-cover-wrapper img")
            ?.let(::imageFromElement)
            ?: manga.coverUrl
        val state = parseState(document.selectFirst("div.manga-meta .meta-item.status-badge")?.text())
        val chapters = loadChapterElements(document)
            .map(::chapterFromElement)
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
            description = description ?: manga.description,
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
                preview = url,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
        availableTags = fetchGenres(),
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

    override suspend fun resolveLink(link: HttpUrl): Manga? {
        val allowedHosts = configKeyDomain.presetValues.toSet()
        if (link.host !in allowedHosts) return null
        val segments = link.pathSegments.filter(String::isNotBlank)
        if (segments.size != 2 || segments.first() != MANGA_PATH) return null

        val relativeUrl = "/$MANGA_PATH/${segments[1]}/"
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

    private suspend fun getArchiveList(page: Int, order: SortOrder): List<Manga> {
        val url = archiveUrl(page, order)
        return parseMangaList(getHtml(url, documentHeaders(url)))
    }

    private suspend fun getSearchList(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val url = searchUrl(page, order, filter)
        return parseMangaList(getHtml(url, documentHeaders(url)))
    }

    private fun archiveUrl(page: Int, order: SortOrder): String {
        return "${baseUrl()}komik/".toHttpUrl()
            .newBuilder()
            .addQueryParameter("filter", "")
            .addQueryParameter("order_by", archiveSortQuery(order))
            .addQueryParameter("page", page.toString())
            .build()
            .toString()
    }

    private fun searchUrl(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): String {
        val builder = "${baseUrl()}search/".toHttpUrl().newBuilder()
            .addQueryParameter("q", filter.query.orEmpty())
            .addQueryParameter("page", page.toString())

        val orderBy = archiveSortQuery(order)
        if (order != SortOrder.RELEVANCE) builder.addQueryParameter("order_by", orderBy)

        filter.states.firstNotNullOfOrNull(::stateToSite)
            ?.let { builder.addQueryParameter("status", it) }
        filter.types.forEach { type ->
            typeToSite(type)?.let { builder.addQueryParameter("types[]", it) }
        }
        filter.tags.forEach { tag ->
            builder.addQueryParameter("genres[]", tag.key)
        }
        return builder.build().toString()
    }

    private fun archiveSortQuery(order: SortOrder): String = when (order) {
        SortOrder.UPDATED -> "latest"
        SortOrder.NEWEST,
        SortOrder.ADDED,
        -> "new-manga"
        SortOrder.ALPHABETICAL -> "alphabet"
        SortOrder.POPULARITY -> "views"
        SortOrder.POPULARITY_WEEK,
        SortOrder.RELEVANCE,
        -> "trending"
        else -> "latest"
    }

    private fun stateToSite(state: MangaState): String? = when (state) {
        MangaState.ONGOING -> "on-going"
        MangaState.FINISHED -> "end"
        MangaState.PAUSED -> "on-hold"
        else -> null
    }

    private fun typeToSite(type: ContentType): String? = when (type) {
        ContentType.MANGA -> "manga"
        ContentType.MANHWA -> "manhwa"
        ContentType.MANHUA -> "manhua"
        else -> null
    }

    private fun parseMangaList(document: Document): List<Manga> {
        return document.select(MANGA_LIST_SELECTOR).mapNotNull(::parseMangaCard)
    }

    private fun parseMangaCard(element: Element): Manga? {
        val linkElement = if (element.tagName().equals("a", ignoreCase = true)) {
            element.takeIf { mangaRelativeUrl(it.attr("href")) != null }
        } else {
            element.select("a[href]").firstOrNull { mangaRelativeUrl(it.attr("href")) != null }
        } ?: return null
        val relativeUrl = mangaRelativeUrl(linkElement.attr("href")) ?: return null

        val title = element.selectFirst("a.manga-title")?.textOrNull()
            ?: element.selectFirst("a.manga-title-link .manga-title")?.textOrNull()
            ?: element.selectFirst("a.manga-card .manga-title")?.textOrNull()
            ?: element.selectFirst(".manga-title")?.textOrNull()
            ?: element.selectFirst("img")?.attrOrNull("alt")
            ?: titleFromUrl(relativeUrl)
        val cover = element.selectFirst("img.manga-cover")?.let(::imageFromElement)
            ?: element.selectFirst("img")?.let(::imageFromElement)

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
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    private fun loadChapterElements(document: Document): List<Element> {
        return document.select(CHAPTER_SELECTOR)
    }

    private fun chapterFromElement(element: Element): MangaChapter {
        val link = element.selectFirst("a.chapter-link") ?: error("Chapter link not found")
        val absoluteUrl = link.attrAsAbsoluteUrl("href") ?: error("Chapter URL not found")
        val relativeUrl = absoluteUrl.substringBefore("?").toRelativeUrl(domain)
        val title = element.selectFirst("span.chapter-number")?.textOrNull()
            ?: link.textOrNull()
            ?: titleFromUrl(relativeUrl)
        val number = parseChapterNumber(title)
        val uploadDate = element.selectFirst("span.chapter-date")?.textOrNull()

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
        document.select("div.genre-list a.genre-tag").forEach { a ->
            val title = a.text().trim().takeIf(String::isNotEmpty) ?: return@forEach
            val key = a.attr("href")
                .substringAfter("filter=", "")
                .substringBefore('&')
                .takeIf(String::isNotBlank)
                ?: title.slugify()
            add(MangaTag(title, key, source))
        }
    }

    private fun parseDescription(document: Document): String? {
        return document.selectFirst("div.manga-description #sinopsisContainer")?.textOrNull()
    }

    private fun parseState(raw: String?): MangaState? {
        val normalized = raw?.lowercase(Locale.ROOT)?.filter { it.isLetterOrDigit() || it.isWhitespace() }?.trim()
            ?: return null
        return when {
            normalized.contains("ongoing") || normalized.contains("on going") || normalized.contains("berjalan") -> MangaState.ONGOING
            normalized.contains("completed") || normalized.contains("complete") || normalized.contains("end") || normalized.contains("finished") || normalized.contains("tamat") -> MangaState.FINISHED
            normalized.contains("hold") || normalized.contains("hiatus") || normalized.contains("pause") -> MangaState.PAUSED
            normalized.contains("cancel") || normalized.contains("dropped") -> MangaState.ABANDONED
            else -> null
        }
    }

    private fun parseRating(element: Element): Float {
        val text = element.select(".score, .rating, .average, #averagerate, .rating-section").text()
        if (text.isBlank()) return RATING_UNKNOWN
        val score = RATING_REGEX.find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
            ?.toFloatOrNull()
            ?: return RATING_UNKNOWN
        return (score / 5f).coerceIn(0f, 1f)
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
        val lower = date.lowercase(Locale.ROOT)
        val number = RELATIVE_NUMBER_REGEX.find(lower)?.value?.toIntOrNull()
        if (number != null) {
            val calendar = Calendar.getInstance()
            when {
                lower.contains("hari") || lower.contains("day") -> calendar.add(Calendar.DAY_OF_MONTH, -number)
                lower.contains("jam") || lower.contains("hour") -> calendar.add(Calendar.HOUR_OF_DAY, -number)
                lower.contains("menit") || lower.contains("minute") || lower.contains("min") -> calendar.add(Calendar.MINUTE, -number)
                lower.contains("detik") || lower.contains("second") -> calendar.add(Calendar.SECOND, -number)
                lower.contains("minggu") || lower.contains("week") -> calendar.add(Calendar.DAY_OF_MONTH, -number * 7)
                lower.contains("bulan") || lower.contains("month") -> calendar.add(Calendar.MONTH, -number)
                lower.contains("tahun") || lower.contains("year") -> calendar.add(Calendar.YEAR, -number)
                else -> return 0L
            }
            return calendar.timeInMillis
        }
        if (lower.startsWith("today") || lower.startsWith("hari ini")) {
            return Calendar.getInstance().startOfDay().timeInMillis
        }
        if (lower.startsWith("yesterday") || lower.startsWith("kemarin")) {
            return Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }.startOfDay().timeInMillis
        }
        return DATE_FORMATS.firstNotNullOfOrNull { pattern ->
            try {
                SimpleDateFormat(pattern, Locale.US).parse(date)?.time
            } catch (_: ParseException) {
                null
            }
        } ?: 0L
    }

    private fun Calendar.startOfDay(): Calendar = apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
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
        if (lower.contains("banner") || lower.contains("logo") || lower.contains("avatar") || lower.contains("ads")) {
            return false
        }
        return true
    }

    private fun mangaRelativeUrl(url: String): String? {
        // Card links are relative (/komik/{slug}/) on every list page; HttpUrl.parse
        // cannot handle relative URLs, so split the path manually.
        val path = url.substringBefore('?').substringBefore('#')
        val segments = path.split('/').filter(String::isNotBlank)
        if (segments.size != 2 || segments.first() != MANGA_PATH) return null
        return "/$MANGA_PATH/${segments[1]}/"
    }

    private fun titleFromUrl(url: String): String = url
        .substringBefore('?')
        .trimEnd('/')
        .substringAfterLast('/')
        .replace('-', ' ')
        .split(' ')
        .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }
        .ifBlank { "MG Komik" }

    private fun String.slugify(): String = lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

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
        return execute(request).parseHtml()
    }

    private suspend fun execute(request: Request): Response {
        val response = context.httpClient.newCall(request).await()
        if (response.code in 400..599) {
            val exception = HttpStatusException(response.message, response.code, response.request.url.toString())
            response.close()
            throw exception
        }
        return response
    }

    private suspend fun fetchGenres(): Set<MangaTag> {
        return try {
            val url = searchUrl(page = 1, order = SortOrder.RELEVANCE, filter = MangaListFilter.EMPTY)
            val document = getHtml(url, documentHeaders(baseUrl()))
            val tags = document.select("input.genre-checkbox").mapNotNull { input ->
                val key = input.attrOrNull("value")?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                if (key.contains("ago", ignoreCase = true)) return@mapNotNull null
                val title = input.nextElementSibling()?.textOrNull()?.trim()?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                MangaTag(title, key, source)
            }.toSet()
            tags.ifEmpty { fallbackTags() }
        } catch (e: Exception) {
            fallbackTags()
        }
    }

    private fun fallbackTags(): Set<MangaTag> = FALLBACK_GENRES.mapTo(LinkedHashSet()) { (title, key) ->
        MangaTag(title, key, source)
    }

    private companion object {
        private const val MANGA_PATH = "komik"
        private const val HTML_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        private const val MANGA_LIST_SELECTOR = "div.manga-card, a.manga-card"
        private const val CHAPTER_SELECTOR = "ul.chapter-list li.chapter-list-item"
        private const val PAGE_IMAGE_SELECTOR = "div#readingContent img, img[data-page]"

        private val IMAGE_ATTRIBUTES = arrayOf(
            "data-src",
            "data-lazy-src",
            "data-original",
            "data-original-src",
            "data-cfsrc",
            "data-manga-src",
            "data-srcset",
            "srcset",
            "src",
        )
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val RATING_REGEX = Regex("(?:Average\\s*)?([0-5](?:\\.\\d+)?)\\s*/\\s*5|Average\\s+([0-5](?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
        private val CHAPTER_NUMBER_REGEX = Regex("(?:chapter|chap|ch\\.?|episode|ep\\.?)\\s*([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
        private val RELATIVE_NUMBER_REGEX = Regex("\\d+")
        private val DATE_FORMATS = arrayOf(
            "dd MMM yy",
            "dd MMM yyyy",
            "d MMM yy",
            "d MMM yyyy",
            "MMMM dd, yyyy",
        )
        private val FALLBACK_GENRES = listOf(
            "Action" to "action",
            "Adaptation" to "adaptation",
            "Adult" to "adult",
            "Adventure" to "adventure",
            "Animals" to "animals",
            "Apocalypse" to "apocalypse",
            "Comedy" to "comedy",
            "Cooking" to "cooking",
            "Crime" to "crime",
            "Demon" to "demon",
            "Demons" to "demons",
            "Drama" to "drama",
            "Dungeons" to "dungeons",
            "Ecchi" to "ecchi",
            "Fantasy" to "fantasy",
            "Fight" to "fight",
            "Fighting" to "fighting",
            "Full Color" to "full-color",
            "Game" to "game",
            "Gender Bender" to "gender-bender",
            "Gore" to "gore",
            "Harem" to "harem",
            "Historical" to "historical",
            "Horror" to "horror",
            "Isekai" to "isekai",
            "Josei" to "josei",
            "Kids" to "kids",
            "Magic" to "magic",
            "Manga" to "manga",
            "Mangatoon" to "mangatoon",
            "Manhua" to "manhua",
            "Manhwa" to "manhwa",
            "Martial Arts" to "martial-arts",
            "Mature" to "mature",
            "Mecha" to "mecha",
            "Medical" to "medical",
            "Military" to "military",
            "Modern" to "modern",
            "Monsters" to "monsters",
            "Murim" to "murim",
            "Music" to "music",
            "Mystery" to "mystery",
            "Office Workers" to "office-workers",
            "One-Shot" to "one-shot",
            "Overpowered" to "overpowered",
            "Police" to "police",
            "Psychological" to "psychological",
            "Regression" to "regression",
            "Reincarnation" to "reincarnation",
            "Revenge" to "revenge",
            "Reverse Harem" to "reverse-harem",
            "Rofan" to "rofan",
            "Romance" to "romance",
            "School" to "school",
            "School Life" to "school-life",
            "Sci-fi" to "sci-fi",
            "Seinen" to "seinen",
            "Shoujo" to "shoujo",
            "Shoujo Ai" to "shoujo-ai",
            "Shoujo AI" to "shouai",
            "Shoujo(G)" to "shoujog",
            "Shounen" to "shounen",
            "Slice of Life" to "slice-of-life",
            "Smut" to "smut",
            "Sports" to "sports",
            "Super Power" to "super-power",
            "Superhero" to "superhero",
            "Supernatural" to "supernatural",
            "Survival" to "survival",
            "System" to "system",
            "Thriller" to "thriller",
            "Time Travel" to "time-travel",
            "Tragedy" to "tragedy",
            "Transmigration" to "transmigration",
            "Vampire" to "vampire",
            "Villainess" to "villainess",
            "Violence" to "violence",
            "War" to "war",
            "Webtoon" to "webtoon",
            "Webtoons" to "webtoons",
            "Wuxia" to "wuxia",
            "Yuri" to "yuri",
        )
    }
}
