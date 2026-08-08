package com.hanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.fasterxml.jackson.annotation.JsonProperty

class HanimeProvider : MainAPI() {
    override var mainUrl = "https://hanime.tv"
    override var name = "Hanime"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    private val apiUrl = "https://guest.freeanimehentai.net"

    private var hvsCache: List<HvsItem>? = null

    private suspend fun getHvsList(): List<HvsItem> {
        return hvsCache ?: run {
            val url = "$apiUrl/api/v11/search_hvs"
            val interceptor = WebViewResolver(Regex("""guest\.freeanimehentai\.net"""))
            
            var responseText = ""
            try {
                responseText = app.get(url, interceptor = interceptor).text
                if (responseText.trim().startsWith("<") || !responseText.trim().startsWith("[")) {
                     responseText = app.get(url).text
                }
            } catch(e: Exception) {
                responseText = app.get(url).text
            }
            
            val startIndex = responseText.indexOf('[')
            val endIndex = responseText.lastIndexOf(']')
            if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
                responseText = responseText.substring(startIndex, endIndex + 1)
            }
            
            val list = AppUtils.parseJson<List<HvsItem>>(responseText)
            hvsCache = list
            list
        }
    }

    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "recently_added" to "Recently Added",
        "top_liked" to "Top Liked"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val hvsList = getHvsList()
        if (hvsList.isEmpty()) return newHomePageResponse(request.name, emptyList())

        val sortedList = when (request.data) {
            "trending" -> hvsList.sortedByDescending { it.views ?: 0L }
            "recently_added" -> hvsList.sortedByDescending { it.createdAtUnix ?: 0L }
            "top_liked" -> hvsList.sortedByDescending { it.likes ?: 0 }
            else -> hvsList
        }

        val itemsPerPage = 50
        val startIndex = (page - 1) * itemsPerPage
        val endIndex = minOf(startIndex + itemsPerPage, sortedList.size)
        
        if (startIndex >= sortedList.size) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val pageItems = sortedList.subList(startIndex, endIndex).mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(request.name, pageItems, hasNext = endIndex < sortedList.size)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val hvsList = getHvsList()
        val lowerQuery = query.lowercase()
        return hvsList.filter { 
            (it.name?.lowercase()?.contains(lowerQuery) == true) || 
            (it.searchTitles?.lowercase()?.contains(lowerQuery) == true) 
        }.take(100).mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast("/")
        
        // Fetch from cached HVS list for accurate metadata
        val hvsList = getHvsList()
        val item = hvsList.find { it.slug == slug } ?: return null
        
        val title = item.name ?: return null
        val description = item.description?.replace(Regex("<[^>]*>"), "")?.trim()
        val cover = item.coverUrl
        val background = item.posterUrl
        val tagsList = item.tags
        
        // Return a MovieLoadResponse (Anime is generally single-video on Hanime)
        // We pass the slug as data so loadLinks can fetch the API.
        return newMovieLoadResponse(title, url, TvType.Anime, slug) {
            this.posterUrl = cover ?: background
            this.backgroundPosterUrl = background
            this.plot = description
            this.tags = tagsList
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Data contains the slug from load()
        val videoPageUrl = "$mainUrl/videos/hentai/$data"
        
        // Load the actual video page via WebView. The page's JavaScript will:
        // 1. POST to /api/v11/handshake with an AES-GCM encrypted token
        // 2. Decrypt the response to get the HLS manifest URL
        // 3. Fetch the HLS manifest from /hls/<video_id>/<token>
        // We intercept that /hls/ request to get the real m3u8 URL.
        val interceptor = WebViewResolver(
            Regex("""/hls/\d+/"""),
            additionalUrls = listOf(Regex(""".*""")),
        )
        
        try {
            val response = app.get(videoPageUrl, interceptor = interceptor)
            val hlsUrl = response.url
            
            if (hlsUrl.contains("/hls/")) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name Auto",
                        url = hlsUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Referer" to "$mainUrl/")
                    }
                )
                
                // Also try to parse the m3u8 manifest for individual quality streams
                try {
                    val m3u8Text = app.get(hlsUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                    val baseUrl = hlsUrl.substringBeforeLast("/") + "/"
                    
                    val qualityRegex = Regex("""#EXT-X-STREAM-INF:.*?RESOLUTION=\d+x(\d+).*?\n(.+)""")
                    qualityRegex.findAll(m3u8Text).forEach { match ->
                        val height = match.groupValues[1]
                        val streamPath = match.groupValues[2].trim()
                        val streamUrl = if (streamPath.startsWith("http")) streamPath 
                                        else if (streamPath.startsWith("/")) "$mainUrl$streamPath"
                                        else "$baseUrl$streamPath"
                        val quality = getQualityFromName(height)
                        
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name ${height}p",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = quality
                                this.headers = mapOf("Referer" to "$mainUrl/")
                            }
                        )
                    }
                } catch (_: Exception) {
                    // Individual quality parsing failed, but we already have the Auto stream
                }
                
                return true
            }
            return false
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
    
    // Data classes to parse the Hanime JSON API
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

    private fun HvsItem.toSearchResponse(): SearchResponse? {
        val title = name ?: return null
        val url = slug?.let { "https://hanime.tv/videos/hentai/$it" } ?: return null
        // cover_url is the portrait image, poster_url is the landscape thumbnail
        val cover = coverUrl ?: posterUrl
        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = cover
        }
    }

}

