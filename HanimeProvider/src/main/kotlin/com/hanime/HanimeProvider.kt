package com.hanime

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class HanimeProvider : MainAPI() {
    override var mainUrl              = "https://hanime.tv"
    override var name                 = "Hanime"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Others)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    companion object {
        private const val AUTH_API = "https://auth.hanime.tv"
        private const val SEARCH_API = "https://search.htv-services.com/"
        private const val VIDEO_API = "https://hw.hanime.tv/api/v8/video?id="
    }

    override val mainPage = mainPageOf(
        "recently_added" to "Recently Added",
        "trending" to "Trending",
        "top_liked" to "Top Liked",
        "uncensored" to "Uncensored",
        "virgin" to "Virgin",
        "ahegao" to "Ahegao",
        "anal" to "Anal",
        "big%20boobs" to "Big Boobs",
        "blow%20job" to "Blow Job",
        "boob%20job" to "Boob Job",
        "censored" to "Censored",
        "cosplay" to "Cosplay",
        "hand%20job" to "Hand Job",
        "harem" to "Harem",
        "horror" to "Horror",
        "incest" to "Incest",
        "maid" to "Maid",
        "masturbation" to "Masturbation",
        "milf" to "Milf",
        "monster" to "Monster",
        "nurse" to "Nurse",
        "public%20sex" to "Public Sex",
        "school%20girl" to "School Girl",
        "teacher" to "Teacher",
        "watersports" to "Watersports",
        "x-ray" to "X-ray"
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

    // Connects to Hanime's official search engine safely without complex JSON builders
    private suspend fun fetchFromSearchApi(query: String, page: Int, orderBy: String = "created_at_unix"): List<SearchResponse> {
        val payload = mapOf(
            "search_text" to query,
            "tags" to emptyList<String>(),
            "tags_mode" to "AND",
            "brands" to emptyList<String>(),
            "blacklist" to emptyList<String>(),
            "order_by" to orderBy,
            "ordering" to "desc",
            "page" to page
        )
        
        val response = try {
            app.post(
                SEARCH_API, 
                json = payload, 
                headers = mapOf("Content-Type" to "application/json", "Origin" to "https://hanime.tv")
            ).text
        } catch (e: Exception) {
            return emptyList()
        }
        
        val searchData = try {
            AppUtils.parseJson<SearchApiResult>(response)
        } catch (e: Exception) { 
            return emptyList() 
        }
        
        val hitsString = searchData.hits ?: "[]"
        val items = try {
            AppUtils.parseJson<List<HvsItem>>(hitsString)
        } catch (e: Exception) { 
            emptyList() 
        }
        
        return items.mapNotNull { item ->
            val title = item.name ?: return@mapNotNull null
            val itemUrl = item.slug?.let { "$mainUrl/videos/hentai/$it" } ?: return@mapNotNull null
            val poster = item.coverUrl ?: item.posterUrl
            newAnimeSearchResponse(title, itemUrl, TvType.Others) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "recently_added" || request.data == "top_liked") {
            val orderBy = if (request.data == "recently_added") "created_at_unix" else "likes"
            
            try {
                val items = fetchFromSearchApi(query = "", page = page - 1, orderBy = orderBy)
                if (items.isNotEmpty()) {
                    return newHomePageResponse(
                        listOf(HomePageList(request.name, items, isHorizontalImages = false)),
                        hasNext = true
                    )
                }
            } catch (e: Exception) {
                Log.e("Hanime", "Main API failed: ${e.message}")
            }

            // HTML Fallback if API fails
            if (request.data == "recently_added" && page == 1) {
                val doc = app.get(mainUrl).document
                val home = doc.select("a[href^=/videos/hentai/]").mapNotNull {
                    val href = it.attr("href")
                    val img = it.selectFirst("img") ?: return@mapNotNull null
                    val title = it.selectFirst(".tv-title, h3")?.text() 
                                ?: img.attr("alt").takeIf { a -> a.isNotBlank() } ?: return@mapNotNull null
                    val poster = img.attr("abs:src")
                    
                    if (href.isBlank() || poster.isBlank()) return@mapNotNull null

                    newAnimeSearchResponse(title, href, TvType.Others) { 
                        this.posterUrl = poster 
                    }
                }.distinctBy { it.url }
                
                return newHomePageResponse(listOf(HomePageList(request.name, home)), hasNext = false)
            }
            return newHomePageResponse(emptyList(), false)
        }

        val url = when (request.data) {
            "trending" -> "$mainUrl/browse/trending?page=$page"
            else -> "$mainUrl/browse/tags/${request.data}?page=$page"
        }
        val document = app.get(url).document

        val home = document.select("div.grid.grid-cols-2 a[href^=/videos/hentai/]").mapNotNull {
            val href   = it.attr("href")
            val title  = it.selectFirst("h3")?.text() ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("abs:src") ?: return@mapNotNull null
            if (title.isBlank() || href.isBlank()) return@mapNotNull null

            newAnimeSearchResponse(title, href, TvType.Others) {
                this.posterUrl     = poster
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            }
        }

        return newHomePageResponse(
            listOf(HomePageList(name = request.name, list = home, isHorizontalImages = false)),
            hasNext = home.isNotEmpty()
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        try {
            val items = fetchFromSearchApi(query = query, page = page - 1)
            return newSearchResponseList(items, hasNext = items.size >= 20)
        } catch (e: Exception) {
            Log.e("Hanime", "Search query error: ${e.message}")
        }
        return newSearchResponseList(emptyList(), hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast("/")

        try {
            val apiResponse = app.get("$VIDEO_API$slug", headers = getHeaders()).text
            val json = AppUtils.parseJson<HanimeVideoDetails>(apiResponse)
            
            val title = json.hentai_video?.name ?: slug
            val description = json.hentai_video?.description?.replace(Regex("<[^>]*>"), "")?.trim()
            val portrait = json.hentai_video?.cover_url     
            val landscape = json.hentai_video?.poster_url  
            val tagsList = json.hentai_video?.hentai_tags?.mapNotNull { it.text } ?: emptyList()
            
            val episodesList = json.hentai_franchise?.hentai_franchise_hentai_videos
            val episodes = if (!episodesList.isNullOrEmpty()) {
                episodesList.mapIndexedNotNull { index, ep ->
                    newEpisode("$mainUrl/videos/hentai/${ep.slug}") {
                        this.name = ep.name
                        this.episode = index + 1
                        this.posterUrl = ep.poster_url ?: ep.cover_url
                    }
                }
            } else {
                listOf(
                    newEpisode(url) {
                        this.name = title
                        this.episode = 1
                        this.posterUrl = landscape ?: portrait
                    }
                )
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.Others, episodes) {
                this.posterUrl = portrait ?: landscape 
                this.backgroundPosterUrl = landscape ?: portrait 
                this.plot = description
                this.tags = tagsList
            }
            
        } catch (e: Exception) {
            val doc = app.get(url, headers = getHeaders()).document
            val script = doc.select("script:containsData(window.__NUXT__)").html()
            
            val coverMatch = Regex(""""cover_url":"([^"]+)"""").find(script)?.groupValues?.get(1)
            val posterMatch = Regex(""""poster_url":"([^"]+)"""").find(script)?.groupValues?.get(1)
            
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - Hanime") ?: slug
            val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            
            val landscapeHtml = posterMatch ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            val portraitHtml = coverMatch ?: landscapeHtml
            
            val moreFromHeader = doc.select("h2:contains(More from)").firstOrNull()
            val seriesTitle = moreFromHeader?.text()?.replace("More from", "", ignoreCase = true)?.trim() ?: title
            val recommendationSection = moreFromHeader?.parents()?.select("section")?.firstOrNull() ?: moreFromHeader?.parent()
            
            val episodes = recommendationSection?.select("a[href^=/videos/hentai/]")?.mapIndexedNotNull { index, a ->
                val epTitle  = a.selectFirst("span.line-clamp-2")?.text() 
                               ?: a.selectFirst("span.text-white:not(.bg-base-300\\/55)")?.text() ?: return@mapIndexedNotNull null
                val epPoster = fixUrlNull(a.selectFirst("img")?.attr("abs:src"))
                val epUrl    = fixUrl(a.attr("href"))
                
                newEpisode(epUrl) {
                    this.name = epTitle
                    this.episode = index + 1
                    this.posterUrl = epPoster
                }
            } ?: listOf(
                newEpisode(url) {
                    this.name = title
                    this.episode = 1
                    this.posterUrl = landscapeHtml ?: portraitHtml
                }
            )
            
            return newTvSeriesLoadResponse(seriesTitle, url, TvType.Others, episodes) {
                this.posterUrl = portraitHtml ?: landscapeHtml
                this.backgroundPosterUrl = landscapeHtml ?: portraitHtml
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val slug = data.trimEnd('/').substringAfterLast("/").substringBefore("?")
        val time = (System.currentTimeMillis() / 1000).toString()
        val signature = HanimeExtractor.generateSignature(time, mainUrl)

        Log.d("Hanime", "slug=$slug time=$time")

        // Simple string JSON - no complex builders needed
        val payloadJson = """{"timestamp_unix":${time.toLong()},"directive":"htv_player_handshake","slug":"$slug"}"""

        val encryptedToken = runCatching {
            HanimeExtractor.encryptHandshakeToken(payloadJson)
        }.getOrNull() ?: run {
            Log.e("Hanime", "token encrypt fail")
            return false
        }

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

        // Standard map for JSON payload
        val jsonBody = mapOf("token" to encryptedToken)

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

        val xToken = response.headers["x-token"] ?: response.headers["X-Token"] ?: run {
            Log.e("Hanime", "x-token missing")
            return false
        }

        val decryptedJson = runCatching { HanimeExtractor.decryptXToken(xToken) }.getOrNull() ?: run {
            Log.e("Hanime", "x-token decrypt fail")
            return false
        }

        val handshakeResponse = try {
            AppUtils.parseJson<HanimeExtractor.HandshakeResponse>(decryptedJson)
        } catch (e: Exception) {
            Log.e("Hanime", "parse fail: ${e.message}")
            return false
        }

        for (source in handshakeResponse.sources) {
            if (source.kind != "normal" || source.src.isBlank()) continue

            val fullUrl = if (source.src.startsWith("http")) source.src else "$mainUrl${source.src}"

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

    // Safe Data Classes
    data class SearchApiResult(
        @JsonProperty("hits") val hits: String?
    )

    data class HvsItem(
        @JsonProperty("name") val name: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("cover_url") val coverUrl: String?,
        @JsonProperty("poster_url") val posterUrl: String?
    )

    data class HanimeVideoDetails(
        @JsonProperty("hentai_video") val hentai_video: HentaiVideo?,
        @JsonProperty("hentai_franchise") val hentai_franchise: HentaiFranchise?
    )
    
    data class HentaiVideo(
        @JsonProperty("name") val name: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("cover_url") val cover_url: String?,
        @JsonProperty("poster_url") val poster_url: String?,
        @JsonProperty("hentai_tags") val hentai_tags: List<HentaiTag>?
    )
    
    data class HentaiTag(
        @JsonProperty("text") val text: String?
    )
    
    data class HentaiFranchise(
        @JsonProperty("hentai_franchise_hentai_videos") val hentai_franchise_hentai_videos: List<HentaiFranchiseVideo>?
    )
    
    data class HentaiFranchiseVideo(
        @JsonProperty("name") val name: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("cover_url") val cover_url: String?,
        @JsonProperty("poster_url") val poster_url: String?
    )
}
