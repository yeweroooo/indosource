package org.koitharu.kotatsu.parsers.site.id.legacy

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
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
import org.koitharu.kotatsu.parsers.util.CryptoAES
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.security.MessageDigest
import java.util.EnumSet
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Westmanga (https://v1.westmanga.my) — signed JSON API source.
 *
 * The website is a React Router v7 SPA (empty SSR shell, all content fetched
 * client-side) backed by a Laravel-style JSON API at https://data.mantweh.online/api/:
 *  - List/search/filter: GET /api/contents?page=N&per_page=40&project=false
 *                   &q={query}&genre[]={id}&status=Ongoing|Completed|Hiatus
 *                   &type=Comic|Novel&country=JP|KR|CN&orderBy=Az|Za|Update|Added|Popular
 *                   &color=Colored|Uncolored
 *  - Genres:        GET /api/contents/genres        ({id, name}, filter by NUMERIC id)
 *  - Detail:        GET /api/comic/{slug}           (full manga, ALL chapters inline)
 *  - Reader:        GET /api/v/{chapterSlug}        (data.images[] = direct CDN URLs)
 *  - Config:        GET /api/framework/config       (unsigned; supplies accsesKey)
 *
 * Every request (except config) needs 4 headers:
 *   x-wm-accses-key: WM_WEB_FRONT_END
 *   x-wm-request-time: <unix seconds>
 *   x-wm-request-signature: 64-hex HMAC (see below)
 *   Authorization: Bearer Bearer HAHAHA Jones   (static guest token)
 *
 * SIGNATURE FORMULA (cracked from the obfuscated bundle, verified 4/4 samples
 * incl. a fresh in-app capture):
 *   msg = time + "GET" + pathname + accsesKey + secret
 *   signature = HMAC-SHA256(key = msg, data = "wm-api-request") -> hex lowercase
 * The bundle calls CryptoJS.HmacSHA256("wm-api-request", msg) which is
 * (message, key) — so the KEY is the concatenated string and the hashed DATA
 * is the constant "wm-api-request". Signature covers PATHNAME ONLY (query
 * string excluded). TTL of a signature >= 180 s, so signing per request is fine.
 *
 * secret = CryptoJS-AES decrypt of a "Salted__" blob (passphrase in the bundle):
 *   AES.decrypt("U2FsdGVkX1/d15RGXgbiDR9ygJcJxQG6sP14ws+Uzzw=",
 *               "LuVwFghhgMGEbqptN4uY0osS45rQWHWxIZ+0oTb5jy8" x2) = "xxxoidj"
 * If the site rotates keys: re-extract the passphrase (Ob=...) and the salted
 * blob (fnbwJ) from the new bundle, update the two constants below — the
 * formula itself never changes.
 *
 * Image CDN storage.westmanga.blog is hotlink-open (verified HTTP 200 without
 * auth); covers come from the same host, so no per-host Referer rewrite is
 * needed for images.
 */
public class WestmangaParser(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context = context,
    source = MangaParserSource.WESTMANGA,
    pageSize = 40,
) {
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(
        "v1.westmanga.my",
        "v1.westmanga.top",
        "westmanga.site",
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.ADDED,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_HOUR,
        SortOrder.POPULARITY_WEEK,
        SortOrder.POPULARITY_MONTH,
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
        .add(CommonHeaders.ACCEPT, "application/json")
        .add(CommonHeaders.ACCEPT_LANGUAGE, "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        .add(CommonHeaders.AUTHORIZATION, GUEST_AUTH)
        .add(CommonHeaders.REFERER, FRONTEND_URL)
        .build()

    // Inject the per-request signed headers (time + signature are path/clock
    // dependent, so they cannot live in getRequestHeaders). Signature covers
    // the API pathname only; non-API hosts (image CDN) are left untouched.
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()
        val host = request.url.host
        if (host.equals(API_HOST, ignoreCase = true)) {
            val pathname = request.url.encodedPath
            val time = System.currentTimeMillis() / 1000
            builder.header("x-wm-accses-key", ACCSES_KEY)
            builder.header("x-wm-request-time", time.toString())
            builder.header("x-wm-request-signature", sign(pathname, time))
            builder.header(CommonHeaders.AUTHORIZATION, GUEST_AUTH)
        } else if (request.header(CommonHeaders.REFERER) == null) {
            builder.header(CommonHeaders.REFERER, FRONTEND_URL)
        }
        return chain.proceed(builder.build())
    }

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val builder = "${API_BASE_URL}contents".toHttpUrl().newBuilder()
        builder.addQueryParameter("page", page.toString())
        builder.addQueryParameter("per_page", pageSize.toString())
        builder.addQueryParameter("project", "false")
        filter.query?.takeIf(String::isNotBlank)?.let {
            builder.addQueryParameter("q", it)
        }
        filter.tags.forEach { tag ->
            when (tag.key) {
                COLOR_TAG_KEY_COLORED -> builder.addQueryParameter("color", "Colored")
                COLOR_TAG_KEY_UNCOLORED -> builder.addQueryParameter("color", "Uncolored")
                else -> builder.addQueryParameter("genre[]", tag.key)
            }
        }
        filter.types.firstNotNullOfOrNull(::typeToSite)?.let {
            builder.addQueryParameter("type", it)
        }
        filter.types.firstNotNullOfOrNull(::countryToSite)?.let {
            builder.addQueryParameter("country", it)
        }
        filter.states.firstNotNullOfOrNull(::stateToSite)?.let {
            builder.addQueryParameter("status", it)
        }
        orderToQuery(order)?.let {
            builder.addQueryParameter("orderBy", it)
        }

        val json = getJson(builder.build().toString())
        val data = json.optJSONArray("data") ?: return emptyList()
        if (data.length() == 0) return emptyList()
        // Server paginates; stop when the API returns an earlier page than asked.
        val paginator = json.optJSONObject("paginator")
        val currentPage = paginator?.getIntOrDefaultCompat("current_page", page) ?: page
        if (currentPage < page) return emptyList()
        return data.mapJSONNotNull { parseManga(it) }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.removeSuffix("/").substringAfterLast('/')
        if (slug.isBlank()) return manga
        val json = getJson("${API_BASE_URL}comic/$slug")
        val data = json.optJSONObject("data") ?: return manga
        if (!data.has("id")) return manga

        val title = data.getStringOrNull("title") ?: manga.title
        val altTitles = data.getStringOrNull("alternative_name")
            ?.split(',', ';')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?.toSet()
            .orEmpty()
        val cover = data.getStringOrNull("cover") ?: manga.coverUrl
        val tags = parseGenres(data.optJSONArray("genres"))
        val chapters = data.optJSONArray("chapters")
            ?.let { array -> parseChapters(array) }
            .orEmpty()
            .sortedBy { it.number }

        return manga.copy(
            title = title,
            altTitles = altTitles.ifEmpty { manga.altTitles },
            rating = parseRating(data),
            contentRating = parseContentRating(tags, data),
            coverUrl = cover,
            largeCoverUrl = cover,
            tags = tags.ifEmpty { manga.tags },
            state = parseState(data.getStringOrNull("status")),
            authors = setOfNotNull(data.getStringOrNull("author"))
                .filter { it != "Unknown" }
                .toSet(),
            description = data.getStringOrNull("sinopsis") ?: manga.description,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterSlug = chapter.url.removeSuffix("/").substringAfterLast('/')
            .takeIf(String::isNotBlank) ?: return emptyList()
        val json = getJson("${API_BASE_URL}v/$chapterSlug")
        val data = json.optJSONObject("data") ?: return emptyList()
        val images = data.optJSONArray("images") ?: return emptyList()
        return buildList {
            for (i in 0 until images.length()) {
                val url = images.optString(i).takeIf(String::isNotBlank) ?: continue
                add(
                    MangaPage(
                        id = generateUid(source, "$chapterSlug#$i:$url"),
                        url = url,
                        preview = url,
                        source = source,
                    ),
                )
            }
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = LinkedHashSet<MangaTag>()
        runCatching {
            val json = getJson("${API_BASE_URL}contents/genres")
            val data = json.optJSONArray("data") ?: return@runCatching
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val name = item.getStringOrNull("name")?.trim()?.takeIf(String::isNotEmpty) ?: continue
                val id = item.optLong("id", 0L)
                if (id <= 0L) continue
                tags.add(MangaTag(name, id.toString(), source))
            }
        }
        tags.add(MangaTag(COLOR_TAG_TITLE_COLORED, COLOR_TAG_KEY_COLORED, source))
        tags.add(MangaTag(COLOR_TAG_TITLE_UNCOLORED, COLOR_TAG_KEY_UNCOLORED, source))
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
                ContentType.NOVEL,
            ),
            availableContentRating = EnumSet.of(
                ContentRating.SAFE,
                ContentRating.SUGGESTIVE,
                ContentRating.ADULT,
            ),
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

    private fun parseManga(item: JSONObject): Manga? {
        val slug = item.getStringOrNull("slug")?.takeIf(String::isNotBlank) ?: return null
        val title = item.getStringOrNull("title") ?: slug
        val relativeUrl = "/$MANGA_PATH/$slug/"
        val cover = item.getStringOrNull("cover")

        return Manga(
            id = generateUid(source, relativeUrl),
            title = title,
            altTitles = emptySet(),
            url = relativeUrl,
            publicUrl = relativeUrl.toAbsoluteUrl(domain),
            rating = parseRating(item),
            contentRating = parseContentRating(emptySet(), item),
            coverUrl = cover,
            largeCoverUrl = cover,
            tags = emptySet(),
            state = parseState(item.getStringOrNull("status")),
            authors = emptySet(),
            source = source,
        )
    }

    private fun parseChapters(array: JSONArray): List<MangaChapter> = buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val slug = item.getStringOrNull("slug")?.takeIf(String::isNotBlank) ?: continue
            val title = item.getStringOrNull("title")
                ?: item.getStringOrNull("number")?.let { "Chapter $it" }
            val number = item.getStringOrNull("number")?.toFloatOrNull() ?: 0f
            // API time is unix SECONDS; MangaChapter.uploadDate is MILLISECONDS
            val uploadDate = (
                item.optJSONObject("created_at")?.optLong("time", 0L)
                    ?: item.optLong("created_at", 0L)
                ) * 1000L
            add(
                MangaChapter(
                    id = generateUid(source, slug),
                    title = title,
                    number = number,
                    volume = 0,
                    url = "/view/$slug",
                    scanlator = null,
                    uploadDate = uploadDate,
                    branch = null,
                    source = source,
                ),
            )
        }
    }

    private fun parseGenres(array: JSONArray?): Set<MangaTag> = buildSet {
        if (array == null) return@buildSet
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.getStringOrNull("name")?.trim()?.takeIf(String::isNotEmpty) ?: continue
            val id = item.optLong("id", 0L)
            if (id > 0L) add(MangaTag(name, id.toString(), source))
        }
    }

    private fun orderToQuery(order: SortOrder): String? = when (order) {
        SortOrder.UPDATED -> "Update"
        SortOrder.NEWEST,
        SortOrder.ADDED,
        -> "Added"
        SortOrder.ALPHABETICAL -> "Az"
        SortOrder.ALPHABETICAL_DESC -> "Za"
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_HOUR,
        SortOrder.POPULARITY_WEEK,
        SortOrder.POPULARITY_MONTH,
        -> "Popular"
        // Search relevance: omit orderBy entirely (server default).
        SortOrder.RELEVANCE -> null
        else -> "Update"
    }

    // "Content type" dimension: Comic | Novel  ->  type=...
    private fun typeToSite(type: ContentType): String? = when (type) {
        ContentType.COMICS -> "Comic"
        ContentType.NOVEL -> "Novel"
        else -> null
    }

    // "Type" dimension (origin country): Manga=JP, Manhua=CN, Manhwa=KR  ->  country=...
    private fun countryToSite(type: ContentType): String? = when (type) {
        ContentType.MANGA -> "JP"
        ContentType.MANHUA -> "CN"
        ContentType.MANHWA -> "KR"
        else -> null
    }

    private fun stateToSite(state: MangaState): String? = when (state) {
        MangaState.ONGOING -> "Ongoing"
        MangaState.FINISHED -> "Completed"
        MangaState.PAUSED -> "Hiatus"
        else -> null
    }

    private fun parseState(raw: String?): MangaState? {
        return when (raw?.lowercase(Locale.ROOT)) {
            "ongoing" -> MangaState.ONGOING
            "complete", "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "cancelled", "dropped" -> MangaState.ABANDONED
            else -> null
        }
    }

    private fun parseContentRating(tags: Set<MangaTag>, json: JSONObject): ContentRating? {
        val isSafe = json.optBoolean("is_safe", true)
        if (isSafe && tags.isEmpty()) return ContentRating.SAFE
        val titles = tags.mapTo(HashSet()) { it.title.lowercase(Locale.ROOT) }
        return when {
            titles.any { it in ADULT_GENRES } -> ContentRating.ADULT
            titles.any { it in SUGGESTIVE_GENRES } -> ContentRating.SUGGESTIVE
            !isSafe && titles.isNotEmpty() -> ContentRating.SUGGESTIVE
            else -> ContentRating.SAFE
        }
    }

    private fun parseRating(json: JSONObject): Float {
        val score = json.optDouble("rating", -1.0)
        if (score <= 0.0) return RATING_UNKNOWN
        return (score / 10.0).toFloat().coerceIn(0f, 1f)
    }

    private fun titleFromUrl(url: String): String = url
        .substringBefore('?')
        .trimEnd('/')
        .substringAfterLast('/')

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

    // HMAC-SHA256(key = msg, data = HMAC_DATA) -> 64 hex lowercase.
    // Matches the bundle's CryptoJS.HmacSHA256(HMAC_DATA, msg) where CryptoJS
    // arg order is (message, key).
    private fun sign(pathname: String, time: Long): String {
        val msg = "$time" + "GET" + pathname + ACCSES_KEY + secret
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(msg.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(HMAC_DATA.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format(Locale.ROOT, "%02x", b))
        return sb.toString()
    }

    private val secret: String by lazy {
        runCatching {
            CryptoAES(context).decrypt(ENCRYPTED_SECRET, PASSPHRASE)
        }.getOrElse {
            // Failsafe: the already-decrypted value (bundle constant), in case
            // the CryptoJS EVP_BytesToKey implementation ever diverges.
            FALLBACK_SECRET
        }
    }

    private fun fallbackTags(): Set<MangaTag> = buildSet {
        FALLBACK_GENRES.forEach { (name, id) -> add(MangaTag(name, id, source)) }
        add(MangaTag(COLOR_TAG_TITLE_COLORED, COLOR_TAG_KEY_COLORED, source))
        add(MangaTag(COLOR_TAG_TITLE_UNCOLORED, COLOR_TAG_KEY_UNCOLORED, source))
    }

    private companion object {
        private const val API_BASE_URL = "https://data.mantweh.online/api/"
        private const val API_HOST = "data.mantweh.online"
        private const val FRONTEND_URL = "https://v1.westmanga.my/"
        private const val MANGA_PATH = "comic"

        private const val ACCSES_KEY = "WM_WEB_FRONT_END"
        private const val GUEST_AUTH = "Bearer Bearer HAHAHA Jones"
        private const val HMAC_DATA = "wm-api-request"

        // CryptoJS AES "Salted__" blob + passphrase recovered from the bundle
        // (fnbwJ / Ob). Rotation recipe in README — formula stays the same.
        private const val ENCRYPTED_SECRET = "U2FsdGVkX1/d15RGXgbiDR9ygJcJxQG6sP14ws+Uzzw="
        private const val PASSPHRASE = "LuVwFghhgMGEbqptN4uY0osS45rQWHWxIZ+0oTb5jy8" +
            "LuVwFghhgMGEbqptN4uY0osS45rQWHWxIZ+0oTb5jy8"
        private const val FALLBACK_SECRET = "xxxoidj"

        private const val COLOR_TAG_KEY_COLORED = "colored"
        private const val COLOR_TAG_TITLE_COLORED = "Colored"
        private const val COLOR_TAG_KEY_UNCOLORED = "uncolored"
        private const val COLOR_TAG_TITLE_UNCOLORED = "Uncolored"

        private val ADULT_GENRES = setOf("adult", "smut", "mature", "yaoi", "yuri", "shotacon")
        private val SUGGESTIVE_GENRES = setOf("ecchi")

        private val FALLBACK_GENRES = listOf(
            "Action" to "1",
            "Adventure" to "2",
            "Comedy" to "3",
            "Drama" to "4",
            "Fantasy" to "5",
            "Harem" to "6",
            "Horror" to "7",
            "Isekai" to "8",
            "Romance" to "9",
            "School Life" to "10",
            "Shounen" to "11",
            "Slice of Life" to "12",
        )
    }
}

private fun JSONObject.getIntOrDefaultCompat(name: String, defaultValue: Int): Int {
    val value = opt(name) ?: return defaultValue
    return when (value) {
        is Int -> value
        is Long -> value.toInt()
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: defaultValue
        else -> defaultValue
    }
}
