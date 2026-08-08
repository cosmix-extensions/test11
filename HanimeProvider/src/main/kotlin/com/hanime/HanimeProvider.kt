package com.hanime

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class HanimeProvider : MainAPI() {
    override var mainUrl              = "https://hanime.tv"
    override var name                 = "Hanime"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Anime)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    companion object {
        private var searchCache: List<HvsItem>? = null
        private var cacheTime: Long = 0L
        private const val CACHE_TTL = 3_600_000L
        private const val FAH_API = "https://guest.freeanimehentai.net"
        private const val SEARCH_API = "$FAH_API/api/v11/search_hvs"
        private const val AUTH_API = "https://auth.hanime.tv"
    }

    override val mainPage = mainPageOf(
        "latest" to "Latest Videos",
        "trending" to "Trending",
        "ahegao" to "Ahegao",
        "anal" to "Anal",
        "bdsm" to "BDSM",
        "big%20boobs" to "Big Boobs",
        "blow%20job" to "Blow Job",
        "bondage" to "Bondage",
        "boob%20job" to "Boob Job",
        "censored" to "Censored",
        "comedy" to "Comedy",
        "cosplay" to "Cosplay",
        "creampie" to "Creampie",
        "dark%20skin" to "Dark Skin",
        "facial" to "Facial",
        "fantasy" to "Fantasy",
        "filmed" to "Filmed",
        "foot%20job" to "Foot Job",
        "gangbang" to "Gangbang",
        "glasses" to "Glasses",
        "hand%20job" to "Hand Job",
        "harem" to "Harem",
        "hd" to "HD",
        "horror" to "Horror",
        "incest" to "Incest",
        "inflation" to "Inflation",
        "lactation" to "Lactation",
        "maid" to "Maid",
        "masturbation" to "Masturbation",
        "milf" to "Milf",
        "mind%20break" to "Mind Break",
        "mind%20control" to "Mind Control",
        "nekomimi" to "Nekomimi",
        "ntr" to "NTR",
        "nurse" to "Nurse",
        "orgy" to "Orgy",
        "plot" to "Plot",
        "pov" to "POV",
        "pregnant" to "Pregnant",
        "public%20sex" to "Public Sex",
        "school%20girl" to "School Girl",
        "softcore" to "Softcore",
        "swimsuit" to "Swimsuit",
        "teacher" to "Teacher",
        "tentacle" to "Tentacle",
        "threesome" to "Threesome",
        "toys" to "Toys",
        "tsundere" to "Tsundere",
        "uncensored" to "Uncensored",
        "vanilla" to "Vanilla",
        "virgin" to "Virgin",
    )

    private fun getHeaders(): Map<String, String> {
        val time = (System.currentTimeMillis() / 1000).toString()
        val signature = HanimeExtractor.generateSignature(time, mainUrl)
        return mapOf(
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
            "X-Signature-Version" to "web2",
            "X-Time" to time,
            "X-Signature" to signature,
            "Content-Type" to "application/json"
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when (request.data) {
            "latest" -> "$mainUrl/browse/new-uploads?page=$page"
            "trending" -> "$mainUrl/browse/trending?page=$page"
            else -> "$mainUrl/browse/tags/${request.data}?page=$page"
        }
        val document = app.get(url).document

        val home = document.select("div.grid.grid-cols-2 a[href^=/videos/hentai/]").mapNotNull {
            val href   = it.attr("href")
            val title  = it.selectFirst("h3")?.text() ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("abs:src") ?: return@mapNotNull null
            if (title.isBlank() || href.isBlank()) return@mapNotNull null

            newAnimeSearchResponse(
                name = title,
                url  = href,
                type = TvType.Anime
            ) {
                this.posterUrl     = poster
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            }
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    name               = request.name,
                    list               = home,
                    isHorizontalImages = true
                )
            ),
            hasNext = home.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val videos = fetchSearchCache()
        if (videos.isEmpty()) return emptyList()

        val q = query.lowercase().trim()
        if (q.isBlank()) return emptyList()

        val filtered = videos.filter { v ->
            (v.name ?: "").contains(q, ignoreCase = true) ||
                    (v.searchTitles ?: "").contains(q, ignoreCase = true) ||
                    (v.tags ?: emptyList()).any { it.contains(q, ignoreCase = true) }
        }

        return filtered.take(24).mapNotNull { v ->
            val slug = v.slug ?: return@mapNotNull null
            if (slug.isBlank()) return@mapNotNull null
            val title = v.name ?: return@mapNotNull null
            val cover = v.coverUrl ?: v.posterUrl

            newMovieSearchResponse(
                name = title,
                url = "/videos/hentai/$slug",
                type = TvType.Anime
            ) {
                this.posterUrl = cover
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            }
        }
    }

    private suspend fun fetchSearchCache(): List<HvsItem> {
        val now = System.currentTimeMillis()
        searchCache?.takeIf { now - cacheTime < CACHE_TTL }?.let { return it }
        return runCatching {
            val response = app.get(SEARCH_API, headers = getHeaders()).text
            parseJson<List<HvsItem>>(response).also {
                searchCache = it
                cacheTime = now
            }
        }.getOrNull() ?: searchCache ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc   = app.get(url, headers = getHeaders()).document
        val title = doc.selectFirst("section#VideoDetails h1")?.text() ?: return null
        val slug  = url.substringAfterLast("/")

        val poster = doc.selectFirst("img[src*='/images/covers/']")?.attr("abs:src")

        val durationText = doc.select("section#VideoDetails span.badge").firstOrNull {
            it.text().contains("min", ignoreCase = true)
        }?.text()
        val duration = durationText?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val tags = doc.select("a[href^=\"/browse/tags/\"]").map { it.text() }

        val plot = doc.select("h2:contains(Synopsis)").first()?.parent()?.selectFirst("div[data-expand-content]")?.text()

        val yearText = doc.select("button[data-tip]").firstOrNull()?.attr("data-tip")
        val year     = yearText?.let { Regex("(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val recommendations = doc.select("section#NextVideoSection a[href]").mapNotNull { a ->
            val recTitle  = a.selectFirst("span.line-clamp-2")?.text() ?: a.selectFirst("span.text-white:not(.bg-base-300\\/55)")?.text() ?: return@mapNotNull null
            val recPoster = fixUrlNull(a.selectFirst("img.aspect-video")?.attr("src")) ?: return@mapNotNull null
            val recUrl    = fixUrl(a.attr("href"))
            newMovieSearchResponse(recTitle, recUrl, TvType.Anime) {
                this.posterUrl = recPoster
            }
        }

        return newMovieLoadResponse(title, url, TvType.Anime, slug) {
            this.posterUrl       = poster
            this.posterHeaders   = mapOf("Referer" to "$mainUrl/")
            this.plot            = plot
            this.year            = year
            this.tags            = tags
            this.duration        = duration
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val time = (System.currentTimeMillis() / 1000).toString()
        val signature = HanimeExtractor.generateSignature(time, mainUrl)

        Log.d("Hanime", "slug=$data time=$time")

        // Build handshake payload
        val payloadJson = buildJsonObject {
            put("timestamp_unix", time.toLong())
            put("directive", "htv_player_handshake")
            put("slug", data)
        }.toString()

        // Encrypt the payload into a token
        val encryptedToken = runCatching {
            HanimeExtractor.encryptHandshakeToken(payloadJson)
        }.getOrNull() ?: run {
            Log.e("Hanime", "token encrypt fail")
            return false
        }

        // Build handshake request headers
        val handshakeHeaders = mapOf(
            "accept" to "application/json",
            "content-type" to "application/json",
            "x-signature-version" to "web2",
            "x-signature" to signature,
            "x-time" to time,
            "x-csrf-token" to "null",
            "origin" to mainUrl,
            "referer" to "$mainUrl/"
        )

        // Build JSON body
        val jsonBody = buildJsonObject {
            put("token", encryptedToken)
        }

        // POST handshake
        val response = runCatching {
            app.post(
                "$AUTH_API/api/v11/handshake",
                headers = handshakeHeaders,
                json = jsonBody,
                allowRedirects = true
            )
        }.getOrNull() ?: run {
            Log.e("Hanime", "handshake request fail")
            return false
        }

        if (!response.isSuccessful) {
            Log.e("Hanime", "handshake fail code=${response.code}")
            return false
        }

        // Get x-token from response headers
        val xToken = response.headers["x-token"] ?: response.headers["X-Token"] ?: run {
            Log.e("Hanime", "x-token missing")
            return false
        }

        // Decrypt x-token to get video sources
        val decryptedJson = runCatching { HanimeExtractor.decryptXToken(xToken) }.getOrNull() ?: run {
            Log.e("Hanime", "x-token decrypt fail")
            return false
        }

        val handshakeResponse = try {
            parseJson<HanimeExtractor.HandshakeResponse>(decryptedJson)
        } catch (e: Exception) {
            Log.e("Hanime", "parse fail: ${e.message}")
            return false
        }

        Log.d("Hanime", "sources=${handshakeResponse.sources.size}")

        // Add each video source
        for (source in handshakeResponse.sources) {
            if (source.kind != "normal" || source.src.isBlank()) {
                Log.d("Hanime", "skip kind=${source.kind} label=${source.label}")
                continue
            }

            val fullUrl = if (source.src.startsWith("http")) source.src else "$mainUrl${source.src}"

            Log.d("Hanime", "label=${source.label} height=${source.height} url=$fullUrl")

            callback(
                newExtractorLink(
                    source = "Hanime",
                    name = "Hanime - ${source.label.ifBlank { "${source.height}p" }}",
                    url = fullUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = source.height
                }
            )
        }
        return true
    }

    // Data classes to parse the Hanime search JSON API
    data class HvsItem(
        @JsonProperty("name") val name: String?,
        @JsonProperty("search_titles") val searchTitles: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("views") val views: Long?,
        @JsonProperty("cover_url") val coverUrl: String?,
        @JsonProperty("poster_url") val posterUrl: String?,
        @JsonProperty("likes") val likes: Int?,
        @JsonProperty("created_at_unix") val createdAtUnix: Long?,
        @JsonProperty("tags") val tags: List<String>?
    )
}
