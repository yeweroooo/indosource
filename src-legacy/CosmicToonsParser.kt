package org.koitharu.kotatsu.parsers.site.id.legacy

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
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
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cosmic Toons (https://02.cosmicscans.to, API at https://cdncid.csmcscns.id).
 *
 * SvelteKit frontend backed by a JSON API (base discovered from the embedded
 * data-sveltekit-fetched payload -> dash.csmcscns.id, API host cdncid.csmcscns.id):
 *  - List/filter: GET /v1/manga/filter?order_by=update|az|za|added|popular
 *    &limit=20&after={cursor}&genres_slug={slug}&release_status={Ongoing|Completed|Hiatus|Dropped}
 *    &type_manga={Manga|Manhwa|Manhua|Webtoon}
 *  - Latest: /v1/manga/latest | Popular: /v1/manga/popularToday
 *  - Project: /v1/manga/latestProject | Search: /v1/manga/search?q={query}
 *  - Details: /v1/manga/mangaDetail/{slug} | Reader: /v1/manga/readingPage/{slug}
 *  - Pagination is cursor-based (`after` = nextCursor); pages are cached per list key.
 *  - 18 genres + status/type enums hardcoded (no public genre-list endpoint).
 *
 * Reader images are behind a token wall: readingPage returns trap URLs
 * (/v1/manga/img/trap/N) in `chapters`. Real page URLs are minted per page:
 *   POST /v1/manga/img/session {turnstile_token, chapter_slug}
 *     -> {sessionId, axisKeys:[hex k0, hex k1]}   (protectionEnabled=false,
 *        so the server accepts the literal token "dev-bypass")
 *   per page i: msg = "{chapterSlug}:{i}:{Date.now()}", a = msg XOR k1,
 *     sig = HMAC-SHA256(k0, a), token = "{sessionId}.{b64url(sig)}.{b64url(a)}"
 *   page URL = /v1/manga/img/r/{token}   (hotlink-open, no Referer needed)
 */
public class CosmicToonsParser(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.COSMICTOONS,
    pageSize = 20,
) {
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(
        "cdncid.csmcscns.id",
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.RELEVANCE,
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_WEEK,
    )

    override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = true,
        isSearchSupported = true,
        isSearchWithFiltersSupported = false,
    )

    private val pageCursors = ConcurrentHashMap<String, ConcurrentHashMap<Int, String>>()
    private val pageHasNext = ConcurrentHashMap<String, ConcurrentHashMap<Int, Boolean>>()

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    // The app draws the source icon from getFavicons(). The default implementation
    // scrapes https://$domain/, and `domain` here is the JSON API host (answers
    // 404), so no icon was ever found. The site itself only inlines its favicon as
    // a base64 data URL (settings/general -> faviconDataUrl) which the app cannot
    // load (Favicon requires an http(s) URL), but the exact same file is served
    // from their upload CDN — point at it directly.
    override suspend fun getFavicons(): Favicons = Favicons(
        listOf(Favicon(FAVICON_URL, 192, "icon")),
        SITE_URL,
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val key = listKey(order, filter)
        // Bound the per-list-key cursor caches so long sessions do not grow unbounded.
        if (pageCursors.size > MAX_CACHED_LISTS) {
            pageCursors.clear()
            pageHasNext.clear()
        }
        val cursors = pageCursors.computeIfAbsent(key) { ConcurrentHashMap() }
        val hasNextPages = pageHasNext.computeIfAbsent(key) { ConcurrentHashMap() }

        if (page > 1) {
            if (hasNextPages[page - 1] == false) return emptyList()
            var cursor = cursors[page - 1]
            if (cursor == null) {
                // Rebuild the cursor chain from page 1 when the app jumps ahead.
                var prev: String? = null
                var keepGoing = true
                for (p in 1 until page) {
                    val res = fetchList(p, order, filter, prev)
                    cursors[p] = res.cursor
                    hasNextPages[p] = res.hasNext
                    prev = res.cursor
                    if (!res.hasNext) {
                        keepGoing = false
                        break
                    }
                }
                if (!keepGoing) return emptyList()
                cursor = cursors[page - 1]
            }
            val result = fetchList(page, order, filter, cursor)
            cursors[page] = result.cursor
            hasNextPages[page] = result.hasNext
            return result.items
        }
        val result = fetchList(1, order, filter, null)
        cursors[1] = result.cursor
        hasNextPages[1] = result.hasNext
        return result.items
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.trimEnd('/').substringAfterLast('/')
        val json = getJson("${apiBaseUrl()}/v1/manga/mangaDetail/$slug")
        val data = json.optJSONObject("data") ?: return manga
        val title = data.optString("title").ifBlank { manga.title }
        val genres = parseGenreNames(data.optJSONArray("genre"))
        val chapters = parseChapters(data.optJSONArray("chapters")).sortedBy { it.number }
        val rating = parseRating(data.optString("rating"))
        val cover = data.optString("cover").takeIf(String::isNotBlank) ?: manga.coverUrl

        return manga.copy(
            title = title,
            altTitles = emptySet(),
            rating = rating.takeUnless { it == RATING_UNKNOWN } ?: manga.rating,
            contentRating = sourceContentRating,
            coverUrl = cover,
            largeCoverUrl = cover,
            tags = genres.ifEmpty { manga.tags },
            state = parseState(data.optString("status")).let { if (it != null) it else manga.state },
            authors = setOfNotNull(data.optString("author").takeIf(String::isNotBlank))
                .ifEmpty { manga.authors },
            description = data.optString("sinopsis").takeIf(String::isNotBlank) ?: manga.description,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterSlug = chapter.url.trimEnd('/').substringAfterLast('/')
        val url = chapter.url.toAbsoluteUrl(configKeyDomain.presetValues.first())
        val json = getJson(url)
        val data = json.optJSONObject("data") ?: return emptyList()

        // Token-wall path: mint a signed /v1/manga/img/r/{token} URL per page.
        val pageCount = data.optInt("pageCount", 0)
        if (pageCount > 0) {
            val minted = runCatching { mintPageUrls(chapterSlug, pageCount) }.getOrNull()
            if (!minted.isNullOrEmpty()) return minted
        }

        // Legacy path: chapters array carries raw <img src> HTML fragments.
        val chapters = data.optJSONArray("chapters") ?: return emptyList()
        return buildList {
            for (i in 0 until chapters.length()) {
                val htmlFragment = chapters.optString(i)
                val src = IMAGE_SRC_REGEX.find(htmlFragment)?.groupValues?.get(1) ?: continue
                if (src.isNotBlank() && isLikelyPageImage(src)) {
                    add(
                        MangaPage(
                            id = generateUid(source, "${chapter.url}#$i"),
                            url = src,
                            preview = null,
                            source = source,
                        ),
                    )
                }
            }
        }
    }

    // Mirrors the frontend's mintImageToken (sealedStore slots cancel out, so the
    // effective keys are just the hex-decoded axisKeys):
    //   a   = utf8("{chapterSlug}:{i}:{now}") XOR k1
    //   sig = HMAC-SHA256(k0, a)
    //   url = /v1/manga/img/r/{sessionId}.{b64url(sig)}.{b64url(a)}
    private suspend fun mintPageUrls(chapterSlug: String, pageCount: Int): List<MangaPage> {
        val session = postJson(
            "${apiBaseUrl()}/v1/manga/img/session",
            JSONObject()
                .put("turnstile_token", "dev-bypass")
                .put("chapter_slug", chapterSlug),
        ).optJSONObject("data") ?: return emptyList()
        val sessionId = session.optString("sessionId")
        val axisKeys = session.optJSONArray("axisKeys") ?: return emptyList()
        if (sessionId.isBlank() || axisKeys.length() < 2) return emptyList()
        val k0 = hexDecode(axisKeys.optString(0))
        val k1 = hexDecode(axisKeys.optString(1))
        if (k0.isEmpty() || k1.isEmpty()) return emptyList()
        return buildList {
            for (i in 0 until pageCount) {
                val msg = "$chapterSlug:$i:${System.currentTimeMillis()}".toByteArray(Charsets.UTF_8)
                val a = xorBytes(msg, k1)
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(k0, "HmacSHA256"))
                val sig = mac.doFinal(a)
                val token = "$sessionId.${base64Url(sig)}.${base64Url(a)}"
                add(
                    MangaPage(
                        id = generateUid(source, "${chapterSlug}#$i"),
                        url = "${apiBaseUrl()}/v1/manga/img/r/$token",
                        preview = null,
                        source = source,
                    ),
                )
            }
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = buildSet {
                GENRES.forEach { (slug, name) -> add(MangaTag(name, slug, source)) }
                add(MangaTag(PROJECT_TAG_TITLE, PROJECT_TAG_KEY, source))
            },
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
            availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE),
            availableLocales = setOf(Locale("id")),
        )
    }

    override suspend fun resolveLink(link: HttpUrl): Manga? {
        val allowedHosts = configKeyDomain.presetValues.toSet() + WEB_HOSTS
        if (link.host !in allowedHosts) return null
        val segments = link.pathSegments.filter(String::isNotBlank)
        if (segments.size < 2 || segments.first() != "comics") return null
        val slug = segments[1]
        val seed = Manga(
            id = generateUid(source, "/manga/$slug"),
            title = "Cosmic Toons manga",
            altTitles = emptySet(),
            url = "/manga/$slug",
            publicUrl = "$SITE_URL/comics/$slug",
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

    private suspend fun fetchList(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
        cursor: String?,
    ): ListResult {
        val query = filter.query?.takeIf(String::isNotBlank)
        // buildListUrl(page, order, filter, cursor, query) — argument order matters!
        val url = buildListUrl(page, order, filter, cursor, query)
        val json = getJson(url)
        val items = buildList {
            val data = json.optJSONArray("data") ?: return@buildList
            for (i in 0 until data.length()) {
                parseManga(data.optJSONObject(i))?.let(::add)
            }
        }
        val cursorObj = json.optJSONObject("cursor")
        val nextCursor = cursorObj?.optString("nextCursor") ?: ""
        // Search endpoint returns ALL matches in one page and no cursor object —
        // treat that as hasNext=false so the app stops cleanly after page 1.
        val hasNext = cursorObj?.optBoolean("hasNext", false) ?: false
        return ListResult(items, nextCursor, hasNext)
    }

    private fun buildListUrl(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
        cursor: String?,
        query: String?,
    ): String {
        val projectOnly = filter.tags.any { it.key == PROJECT_TAG_KEY }
        val endpoint = when {
            projectOnly -> "/v1/manga/latestProject"
            query != null -> "/v1/manga/search"
            order == SortOrder.POPULARITY || order == SortOrder.POPULARITY_WEEK -> "/v1/manga/popularToday"
            else -> "/v1/manga/filter"
        }
        val builder = (apiBaseUrl() + endpoint).toHttpUrl().newBuilder()
        if (query != null) {
            builder.addQueryParameter("q", query)
        } else {
            when {
                projectOnly || order == SortOrder.UPDATED -> builder.addQueryParameter("order_by", "update")
                order == SortOrder.NEWEST -> builder.addQueryParameter("order_by", "added")
                order == SortOrder.ALPHABETICAL -> builder.addQueryParameter("order_by", "az")
                else -> builder.addQueryParameter("order_by", "update")
            }
            filter.tags.filterNot { it.key == PROJECT_TAG_KEY }
                .map { it.key }
                .forEach { builder.addQueryParameter("genres_slug", it) }
            filter.states.firstNotNullOfOrNull(::stateToSite)?.let {
                builder.addQueryParameter("release_status", it)
            }
            filter.types.firstNotNullOfOrNull(::typeToSite)?.let {
                builder.addQueryParameter("type_manga", it)
            }
        }
        builder.addQueryParameter("limit", pageSize.toString())
        cursor?.takeIf(String::isNotBlank)?.let { builder.addQueryParameter("after", it) }
        return builder.build().toString()
    }

    private fun parseManga(item: JSONObject?): Manga? {
        if (item == null) return null
        val slug = item.optString("slug")
        val title = item.optString("title").ifBlank { slug }
        if (slug.isBlank() || title.isBlank()) return null
        val relativeUrl = "/manga/$slug"
        return Manga(
            id = generateUid(source, relativeUrl),
            title = title,
            altTitles = emptySet(),
            url = relativeUrl,
            publicUrl = relativeUrl.toAbsoluteUrl(domain),
            rating = parseRating(item.optString("rating")),
            contentRating = sourceContentRating,
            coverUrl = item.optString("cover").takeIf(String::isNotBlank),
            tags = emptySet(),
            state = parseState(item.optString("status")),
            authors = emptySet(),
            source = source,
        )
    }

    private fun parseChapters(array: JSONArray?): List<MangaChapter> = buildList {
        if (array == null) return@buildList
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val chapterSlug = item.optString("slug")
            val num = item.optLong("chapterNum")
            if (chapterSlug.isBlank() || num <= 0L) continue
            val relativeUrl = "/v1/manga/readingPage/$chapterSlug"
            add(
                MangaChapter(
                    id = generateUid(source, relativeUrl),
                    title = "Chapter $num",
                    number = num.toFloat(),
                    volume = 0,
                    url = relativeUrl,
                    scanlator = null,
                    uploadDate = parseChapterDate(item.optString("time")),
                    branch = null,
                    source = source,
                ),
            )
        }
    }

    private fun parseGenreNames(array: JSONArray?): Set<MangaTag> = buildSet {
        if (array == null) return@buildSet
        for (i in 0 until array.length()) {
            val name = array.optString(i).trim()
            if (name.isNotEmpty()) add(MangaTag(name, name.slugify(), source))
        }
    }

    private fun parseState(raw: String): MangaState? {
        return when (raw.lowercase(Locale.ROOT)) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus", "on hold" -> MangaState.PAUSED
            "dropped", "cancelled" -> MangaState.ABANDONED
            else -> null
        }
    }

    private fun stateToSite(state: MangaState): String? = when (state) {
        MangaState.ONGOING -> "Ongoing"
        MangaState.FINISHED -> "Completed"
        MangaState.PAUSED -> "Hiatus"
        MangaState.ABANDONED -> "Dropped"
        else -> null
    }

    private fun typeToSite(type: ContentType): String? = when (type) {
        ContentType.MANGA -> "Manga"
        ContentType.MANHWA -> "Manhwa"
        ContentType.MANHUA -> "Manhua"
        ContentType.COMICS -> "Webtoon"
        else -> null
    }

    private fun parseRating(raw: String): Float {
        val score = raw.toFloatOrNull() ?: return RATING_UNKNOWN
        if (score <= 0f) return RATING_UNKNOWN
        return (score / 10f).coerceIn(0f, 1f)
    }

    private fun parseChapterDate(raw: String?): Long {
        val date = raw?.trim()?.takeIf(String::isNotEmpty) ?: return 0L
        return DATE_FORMATTERS.firstNotNullOfOrNull { sdf ->
            runCatching {
                synchronized(sdf) { sdf.parse(date) }?.time
            }.getOrNull()
        } ?: 0L
    }

    private fun isLikelyPageImage(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (lower.contains("logo") || lower.contains("banner") || lower.contains("avatar") || lower.contains("readerarea")) {
            return false
        }
        // Anti-bot trap URLs served in place of real pages.
        if (lower.contains("/img/trap/")) return false
        return true
    }

    private fun listKey(order: SortOrder, filter: MangaListFilter): String =
        "${order.name}|${filter.query}|${filter.tags.joinToString(",") { it.key }}|${filter.states}|${filter.types}"

    private fun String.slugify(): String = lowercase(Locale.ROOT)
        .replace(NON_ALNUM_REGEX, "-")
        .trim('-')

    private suspend fun getJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .addHeader(CommonHeaders.ACCEPT, "application/json")
            .tag(MangaSource::class.java, source)
            .get()
            .build()
        return executeJson(request)
    }

    private suspend fun postJson(url: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url)
            .addHeader(CommonHeaders.ACCEPT, "application/json")
            .tag(MangaSource::class.java, source)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return executeJson(request)
    }

    private suspend fun executeJson(request: Request): JSONObject {
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

    private fun hexDecode(hex: String): ByteArray {
        val clean = hex.filterNot(Char::isWhitespace)
        if (clean.length % 2 != 0) return ByteArray(0)
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun xorBytes(data: ByteArray, key: ByteArray): ByteArray {
        if (key.isEmpty()) return data
        return ByteArray(data.size) { i -> (data[i].toInt() xor key[i % key.size].toInt()).toByte() }
    }

    // base64url without padding (java.util.Base64 needs API 26+, plugin targets 24).
    private fun base64Url(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val n = (data[i].toInt() and 0xFF shl 16) or (data[i + 1].toInt() and 0xFF shl 8) or (data[i + 2].toInt() and 0xFF)
            sb.append(alphabet[n shr 18 and 63]).append(alphabet[n shr 12 and 63])
                .append(alphabet[n shr 6 and 63]).append(alphabet[n and 63])
            i += 3
        }
        val rem = data.size - i
        if (rem == 1) {
            val n = (data[i].toInt() and 0xFF shl 16)
            sb.append(alphabet[n shr 18 and 63]).append(alphabet[n shr 12 and 63])
        } else if (rem == 2) {
            val n = (data[i].toInt() and 0xFF shl 16) or (data[i + 1].toInt() and 0xFF shl 8)
            sb.append(alphabet[n shr 18 and 63]).append(alphabet[n shr 12 and 63]).append(alphabet[n shr 6 and 63])
        }
        return sb.toString()
    }

    private fun apiBaseUrl(): String = "https://$domain"

    private data class ListResult(val items: List<Manga>, val cursor: String, val hasNext: Boolean)

    private companion object {
        private const val PROJECT_TAG_KEY = "project"
        private const val PROJECT_TAG_TITLE = "Project"

        // Byte-identical to the base64 favicon the site inlines in its HTML.
        private const val FAVICON_URL = "https://cdncid.csmcscns.id/uploads/2024/01/logo_cosmic.png"
        private const val SITE_URL = "https://04.cosmicscans.to/"

        // Live web mirrors (02.cosmicscans.to is dead) — valid for shared links.
        private val WEB_HOSTS = setOf(
            "02.cosmicscans.to",
            "04.cosmicscans.to",
            "05.cosmicscans.to",
            "cosmicscans.to",
        )
        private const val MAX_CACHED_LISTS = 32
        private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")
        private val IMAGE_SRC_REGEX = Regex("<img[^>]+src=['\"]([^'\"]+)['\"]")

        private val DATE_FORMATTERS = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        ).map { SimpleDateFormat(it, Locale.US) }.toTypedArray()

        private val GENRES = listOf(
            "action" to "Action",
            "adventure" to "Adventure",
            "comedy" to "Comedy",
            "drama" to "Drama",
            "ecchi" to "Ecchi",
            "fantasy" to "Fantasy",
            "historical" to "Historical",
            "horror" to "Horror",
            "isekai" to "Isekai",
            "mystery" to "Mystery",
            "psychological" to "Psychological",
            "romance" to "Romance",
            "sci-fi" to "Sci-Fi",
            "slice-of-life" to "Slice of Life",
            "sports" to "Sports",
            "supernatural" to "Supernatural",
            "thriller" to "Thriller",
            "tragedy" to "Tragedy",
        )
    }
}