package com.hanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Document

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
        val document = app.get(url).document
        
        // Extract basic metadata from OpenGraph tags
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.title().takeIf { it.isNotBlank() }
            ?: return null
            
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")

        val slug = url.substringAfterLast("/")
        
        // Return a MovieLoadResponse (Anime is generally single-video on Hanime)
        // We pass the slug as data so loadLinks can fetch the API.
        return newMovieLoadResponse(title, url, TvType.Anime, slug) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Data contains the slug from load()
        val videoApiUrl = "$apiUrl/api/v8/video?id=$data"
        
        // Use WebViewResolver to attempt bypassing Cloudflare on the guest API
        val interceptor = WebViewResolver(Regex("""guest\.freeanimehentai\.net"""))
        
        try {
            var responseText = app.get(videoApiUrl, interceptor = interceptor).text
            
            // If Android WebView wraps the JSON response in HTML <pre> or <body> tags
            val startIndex = responseText.indexOf('{')
            val endIndex = responseText.lastIndexOf('}')
            if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
                responseText = responseText.substring(startIndex, endIndex + 1)
            } else {
                // Fallback: If the JSON was completely downloaded instead of displayed,
                // the WebView response might be empty or missing JSON.
                // We can re-fetch via app.get() because the Turnstile clearance cookies are now saved.
                responseText = app.get(videoApiUrl).text
            }
            
            // Parse JSON manually or use AppUtils.parseJson
            val json = AppUtils.parseJson<HanimeVideoResponse>(responseText)
            
            // Extract the actual video stream URLs
            json.videos_manifest?.servers?.forEach { server ->
                server.streams?.forEach { stream ->
                    val streamUrl = stream.url ?: return@forEach
                    val height = stream.height?.toString() ?: "Unknown"
                    val quality = getQualityFromName(height)
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "${name} ${server.name ?: ""} ${height}p".trim(),
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = quality
                            this.headers = mapOf("Referer" to "$mainUrl/")
                        }
                    )
                }
            }
            return true
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
        @JsonProperty("views") val views: Long?,
        @JsonProperty("cover_url") val coverUrl: String?,
        @JsonProperty("poster_url") val posterUrl: String?,
        @JsonProperty("likes") val likes: Int?,
        @JsonProperty("created_at_unix") val createdAtUnix: Long?
    ) {
        fun toSearchResponse(): SearchResponse? {
            val title = name ?: return null
            val url = slug?.let { "https://hanime.tv/videos/hentai/$it" } ?: return null
            return newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = this@HvsItem.posterUrl ?: this@HvsItem.coverUrl
            }
        }
    }

    data class HanimeVideoResponse(
        @JsonProperty("videos_manifest") val videos_manifest: VideosManifest?
    )

    data class VideosManifest(
        @JsonProperty("servers") val servers: List<Server>?
    )

    data class Server(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("streams") val streams: List<Stream>?
    )

    data class Stream(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("height") val height: Int?,
        @JsonProperty("url") val url: String?
    )
}
