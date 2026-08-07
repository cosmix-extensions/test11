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

    override val mainPage = mainPageOf(
        "$mainUrl/trending" to "Trending",
        "$mainUrl/browse/recently_added" to "Recently Added",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        
        // This is a basic implementation placeholder. 
        // Hanime typically uses Nuxt or APIs for main page data as well.
        // We parse standard anchor tags for videos.
        val items = document.select("a[href^='/videos/hentai/']").mapNotNull { el ->
            val href = el.attr("abs:href")
            val title = el.select("div.tv-title").text().takeIf { it.isNotBlank() } ?: href.substringAfterLast("/")
            val poster = el.select("img").attr("src")
            
            if (title.isBlank()) return@mapNotNull null
            
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document
        
        val items = document.select("a[href^='/videos/hentai/']").mapNotNull { el ->
            val href = el.attr("abs:href")
            val title = el.select("div.tv-title").text().takeIf { it.isNotBlank() } ?: href.substringAfterLast("/")
            val poster = el.select("img").attr("src")
            
            if (title.isBlank()) return@mapNotNull null
            
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

        return items
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
        val interceptor = WebViewResolver(Regex("freeanimehentai"))
        
        try {
            val responseText = app.get(videoApiUrl, interceptor = interceptor).text
            
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
                            name = "${name} ${server.name} ${height}p",
                            url = streamUrl,
                            referer = mainUrl,
                            quality = quality,
                            type = ExtractorLinkType.M3U8
                        )
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
