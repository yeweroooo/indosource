package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import tsuki.MangaLoaderContext
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Favicon
import tsuki.model.Favicons
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
import tsuki.util.attrOrNull
import tsuki.util.await
import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.textOrNull
import tsuki.util.toAbsoluteUrl
import java.util.EnumSet
import java.util.Locale

/**
 * Voratoon (portal https://voratoon.id — live web mirrors: 13.voratoon.com,
 * voratoon.co; v3.voratoon.com 301-redirects to 13).
 *
 * The JSON API at api.voratoon.com now encrypts every response envelope
 * ({"encrypted":"v1.<iv>.<ct>.<hmac>"} + x-payload-encrypted: v1 header) with a
 * key that only lives server-side, so the API is unusable from a plugin. The
 * Next.js web frontend, however, is fully server-rendered — every page ships
 * its data as plain HTML — so this parser scrapes the web frontend instead:
 *
 *  - List/search/filters: GET /browse?page=N&q={title}&sort=newseries|bookmark|
 *    popular|rating|az&status=ongoing|completed|hiatus|cancelled&format=manga|
 *    manhwa|manhua|mangatoon&type=project|mirror&anime=yes|no&genre=action,fantasy
 *    (SSR always returns 30 cards; an empty grid past the last page ends paging)
 *    Card: article.card > a[href="/series/{slug}"] + img[alt=title][src=cover]
 *  - Details: GET /series/{slug} -> h1.series-title, p.alternative-title,
 *    p.synopsis, .tags-container a.tag, .info-grid (.info-label Status/Format/Type),
 *    .info-list-item Author, .stat-item Rating, cover img[src*=cvr.voratoon.id],
 *    chapters: ul.chapter-list a.chapter-item[href="/series/{slug}/chapter/{n}"]
 *  - Reader: GET /series/{slug}/chapter/{n} -> img[data-chapter-page] (CDN pages)
 *  - Images: cvr.voratoon.id (presigned covers) and cdn.voratoon.com (chapter
 *    pages, hotlink-protected: 403 without a web Referer — set below).
 */
public class VoratoonParser(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.VORATOON,
    pageSize = 30,
) {
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(
        "13.voratoon.com",
        "voratoon.co",
        "v3.voratoon.com",
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.ADDED,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_WEEK,
        SortOrder.RATING,
        SortOrder.ALPHABETICAL,
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

    override suspend fun getFavicons(): Favicons = Favicons(
        listOf(
            Favicon("https://$domain/icon.png", 64, "icon"),
            Favicon("https://$domain/apple-icon.png", 180, "apple-touch-icon"),
        ),
        "https://$domain/",
    )

    // Reader images on cdn.voratoon.com are hotlink-protected: they return 403
    // without a Referer. The app merges getRequestHeaders() into every request
    // for this source (including reader page fetches), so advertise the web
    // frontend referer here — otherwise the chapter pages never load.
    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add(CommonHeaders.USER_AGENT, config[userAgentKey])
        .add(CommonHeaders.REFERER, baseUrl().removeSuffix("/"))
        .build()

    // Belt and suspenders: the parser is also registered as an Interceptor in
    // the app's HTTP pipeline, so inject the referer directly at request time.
    // This covers app builds that do not merge getRequestHeaders() into reader
    // page fetches.
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(CommonHeaders.REFERER) == null) {
            return chain.proceed(
                request.newBuilder()
                    .header(CommonHeaders.REFERER, baseUrl().removeSuffix("/"))
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
        val builder = "${baseUrl()}browse".toHttpUrlOrNull()!!.newBuilder()
        builder.addQueryParameter("page", page.toString())
        filter.query?.takeIf(String::isNotBlank)?.let {
            builder.addQueryParameter("q", it)
        }
        orderToSort(order)?.let {
            builder.addQueryParameter("sort", it)
        }
        val genres = mutableListOf<String>()
        filter.tags.forEach { tag ->
            when (tag.key) {
                PROJECT_TAG_KEY -> builder.addQueryParameter("type", "project")
                MIRROR_TAG_KEY -> builder.addQueryParameter("type", "mirror")
                ANIME_TAG_KEY -> builder.addQueryParameter("anime", "yes")
                else -> if (tag.key.isNotBlank()) genres.add(tag.key.lowercase(Locale.ROOT))
            }
        }
        if (genres.isNotEmpty()) {
            builder.addQueryParameter("genre", genres.joinToString(","))
        }
        filter.types.firstNotNullOfOrNull(::typeToSite)?.let {
            builder.addQueryParameter("format", it)
        }
        filter.states.firstNotNullOfOrNull(::stateToSite)?.let {
            builder.addQueryParameter("status", it)
        }

        val document = getHtml(builder.build().toString())
        return document.select("article.card").mapNotNull { card ->
            val link = card.selectFirst("a[href^=\"/series/\"]") ?: return@mapNotNull null
            val href = link.attr("href").trimEnd('/')
            if (!href.startsWith("/series/")) return@mapNotNull null
            val img = card.selectFirst("img")
            val title = img?.attrOrNull("alt")?.trim()?.takeIf(String::isNotEmpty)
                ?: link.textOrNull()?.trim()?.takeIf(String::isNotEmpty)
                ?: href.substringAfterLast('/')
            Manga(
                id = generateUid(source, href),
                title = title,
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = sourceContentRating,
                coverUrl = img?.attrOrNull("src")?.takeIf(String::isNotBlank),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.substringAfterLast('/').trimEnd('/')
        if (slug.isBlank()) return manga
        val document = getHtml("${baseUrl()}series/$slug")

        val title = document.selectFirst("h1.series-title")?.textOrNull()?.trim()
            ?: manga.title
        val altTitle = document.selectFirst("p.alternative-title")?.textOrNull()?.trim()
        val description = document.selectFirst("p.synopsis")?.textOrNull()?.trim()
            ?: manga.description
        val cover = document.selectFirst(".cover-wrapper img")?.attrOrNull("src")
            ?: document.selectFirst(".mobile-top-cover img")?.attrOrNull("src")
            ?: manga.coverUrl
        val tags = document.select(".tags-container a.tag").mapNotNull { tag ->
            tag.textOrNull()?.trim()?.takeIf(String::isNotEmpty)?.let { MangaTag(it, it, source) }
        }.toSet()
        val author = document.select("div.info-list-item").firstNotNullOfOrNull { item ->
            val label = item.selectFirst("span")?.textOrNull().orEmpty()
            if (label.contains("Author", ignoreCase = true)) {
                item.selectFirst("strong")?.textOrNull()?.trim()?.takeIf(String::isNotEmpty)
            } else null
        }
        var state: MangaState? = null
        document.select(".info-grid .info-item").forEach { item ->
            val label = item.selectFirst(".info-label")?.textOrNull().orEmpty()
            val value = item.selectFirst(".info-val")?.textOrNull()?.trim().orEmpty()
            if (label.equals("Status", ignoreCase = true)) {
                state = parseState(value)
            }
        }
        val rating = document.select(".stat-item").firstNotNullOfOrNull { item ->
            val label = item.selectFirst(".stat-label")?.textOrNull().orEmpty()
            if (label.equals("Rating", ignoreCase = true)) {
                parseRating(item.selectFirst(".stat-val")?.textOrNull())
            } else null
        }

        val chapters = document.select("ul.chapter-list a.chapter-item").mapNotNull { a ->
            val href = a.attr("href").trimEnd('/')
            val number = href.substringAfterLast('/').toFloatOrNull()
                ?: a.selectFirst(".chapter-number")?.textOrNull()
                    ?.lowercase(Locale.ROOT)?.substringAfter("chapter")
                    ?.trim()?.toFloatOrNull()
                ?: return@mapNotNull null
            MangaChapter(
                id = generateUid(source, href),
                title = a.selectFirst(".chapter-number")?.textOrNull()?.trim()
                    ?: "Chapter $number",
                number = number,
                volume = 0,
                url = href,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }.sortedBy { it.number }

        return manga.copy(
            title = title,
            altTitles = setOfNotNull(altTitle) + manga.altTitles,
            rating = rating?.takeUnless { it == RATING_UNKNOWN } ?: manga.rating,
            contentRating = sourceContentRating,
            coverUrl = cover,
            largeCoverUrl = cover,
            tags = tags.ifEmpty { manga.tags },
            state = state ?: manga.state,
            authors = setOfNotNull(author),
            description = description,
            chapters = chapters,
            url = "/series/$slug",
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val document = getHtml(chapter.url.toAbsoluteUrl(domain))
        return document.select("img[data-chapter-page]").mapIndexed { index, img ->
            val imageUrl = img.attr("data-chapter-page").trim()
            MangaPage(
                id = generateUid(source, "${chapter.url}#$index:$imageUrl"),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = LinkedHashSet<MangaTag>()
        runCatching {
            val document = getHtml("${baseUrl()}browse")
            document.select("button.genre-box").forEach { box ->
                val name = box.textOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: return@forEach
                if (name.equals("Semua", ignoreCase = true)) return@forEach
                tags.add(MangaTag(name, name, source))
            }
        }
        tags.add(MangaTag(PROJECT_TAG_TITLE, PROJECT_TAG_KEY, source))
        tags.add(MangaTag(MIRROR_TAG_TITLE, MIRROR_TAG_KEY, source))
        tags.add(MangaTag(ANIME_TAG_TITLE, ANIME_TAG_KEY, source))
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
                ContentType.COMICS,
            ),
            availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE, ContentRating.ADULT),
            availableLocales = setOf(Locale("id")),
        )
    }

    override suspend fun resolveLink(link: HttpUrl): Manga? {
        // Accept links shared from the portal and any web mirror — details and
        // chapters are scraped from the configured web frontend.
        val allowedHosts = configKeyDomain.presetValues.toSet() + WEB_HOSTS
        if (link.host !in allowedHosts) return null
        val segments = link.pathSegments.filter(String::isNotBlank)
        if (segments.size < 2 || segments.first() != "series") return null
        val slug = segments[1]
        val relativeUrl = "/series/$slug"
        val seed = Manga(
            id = generateUid(source, relativeUrl),
            title = slug,
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

    private fun orderToSort(order: SortOrder): String? = when (order) {
        SortOrder.ADDED -> "newseries"
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_WEEK,
        -> "popular"
        SortOrder.RATING -> "rating"
        SortOrder.ALPHABETICAL -> "az"
        // UPDATED and RELEVANCE: server default (latest update / search relevance).
        else -> null
    }

    private fun stateToSite(state: MangaState): String? = when (state) {
        MangaState.ONGOING -> "ongoing"
        MangaState.FINISHED -> "completed"
        MangaState.PAUSED -> "hiatus"
        MangaState.ABANDONED -> "cancelled"
        else -> null
    }

    private fun typeToSite(type: ContentType): String? = when (type) {
        ContentType.MANGA -> "manga"
        ContentType.MANHWA -> "manhwa"
        ContentType.MANHUA -> "manhua"
        ContentType.COMICS -> "mangatoon"
        else -> null
    }

    private fun parseState(raw: String?): MangaState? {
        return when (raw?.lowercase(Locale.ROOT)) {
            "ongoing" -> MangaState.ONGOING
            "completed", "complete" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "cancelled", "canceled", "dropped" -> MangaState.ABANDONED
            else -> null
        }
    }

    private fun parseRating(raw: String?): Float {
        val score = raw?.substringBefore(' ')?.toFloatOrNull() ?: return RATING_UNKNOWN
        if (score <= 0f) return RATING_UNKNOWN
        return (score / 10f).coerceIn(0f, 1f)
    }

    private suspend fun getHtml(url: String): Document {
        val request = Request.Builder()
            .url(url)
            .headers(documentHeaders())
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

    private fun documentHeaders(): Headers = Headers.Builder()
        .add(CommonHeaders.USER_AGENT, config[userAgentKey])
        .add(CommonHeaders.ACCEPT, HTML_ACCEPT)
        .add(CommonHeaders.REFERER, baseUrl().removeSuffix("/"))
        .build()

    private fun baseUrl(): String = "https://$domain/"

    private fun fallbackTags(): Set<MangaTag> = buildSet {
        FALLBACK_GENRES.forEach { name -> add(MangaTag(name, name, source)) }
        add(MangaTag(PROJECT_TAG_TITLE, PROJECT_TAG_KEY, source))
        add(MangaTag(MIRROR_TAG_TITLE, MIRROR_TAG_KEY, source))
        add(MangaTag(ANIME_TAG_TITLE, ANIME_TAG_KEY, source))
    }

    private companion object {
        private const val HTML_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"

        private const val PROJECT_TAG_KEY = "project"
        private const val PROJECT_TAG_TITLE = "Project"
        private const val MIRROR_TAG_KEY = "mirror"
        private const val MIRROR_TAG_TITLE = "Mirror"
        private const val ANIME_TAG_KEY = "animeAdaptation"
        private const val ANIME_TAG_TITLE = "Anime Adaptation"

        // Portal + old web mirrors — valid for shared links, never for scraping.
        private val WEB_HOSTS = setOf(
            "voratoon.id",
            "voratoon.co",
            "www.voratoon.com",
            "v1.voratoon.com",
            "v2.voratoon.com",
            "v3.voratoon.com",
            "v8.voratoon.com",
            "13.voratoon.com",
        )

        private val FALLBACK_GENRES = listOf(
            "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Harem", "Horror",
            "Isekai", "Magic", "Martial Arts", "Mecha", "Mystery", "Romance",
            "School Life", "Sci-Fi", "Seinen", "Shounen", "Slice of Life", "Sports",
            "Supernatural", "Tragedy",
        )
    }
}
