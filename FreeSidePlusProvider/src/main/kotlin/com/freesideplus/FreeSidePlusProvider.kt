package com.freesideplus

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.util.Base64

class FreeSidePlusProvider : MainAPI() {
    override var mainUrl = "https://www.free-sideplus.com"
    override var name = "Free Side+"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie)

    private val wpApiUrl = "$mainUrl/wp-json/wp/v2"

    override val mainPage = mainPageOf(
        "41" to "Sidecast",
        "43" to "Side+ Saturdays",
        "44" to "Inside",
        "53" to "Inside Out",
        "42" to "BTS",
        "32" to "1v100"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val categoryId = request.data
        val posts = app.get("$wpApiUrl/posts?categories=$categoryId&per_page=20&page=$page")
            .parsedSafe<List<WPPost>>() ?: emptyList()

        val items = posts.mapNotNull { post ->
            post.toSearchResponse()
        }

        return newHomePageResponse(request.name, items, hasNext = items.size >= 20)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val posts = app.get("$wpApiUrl/posts?search=$query&per_page=50")
            .parsedSafe<List<WPPost>>() ?: emptyList()

        return posts.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        // Extract post ID from URL
        val postId = url.split("/").filter { it.isNotEmpty() }.lastOrNull()?.let {
            // Try to get post by slug
            val posts = app.get("$wpApiUrl/posts?slug=$it")
                .parsedSafe<List<WPPost>>()
            posts?.firstOrNull()?.id
        } ?: throw ErrorLoadingException("Could not extract post ID")

        val post = app.get("$wpApiUrl/posts/$postId").parsedSafe<WPPost>()
            ?: throw ErrorLoadingException("Could not load post")

        val title = post.title.rendered.cleanHtml()
        val description = post.excerpt.rendered.cleanHtml()
        val posterUrl = getMediaUrl(post.featured_media)

        // Extract episode number from title if present (e.g., "#236" or "Episode 236")
        val episodeNumber = extractEpisodeNumber(title)

        // Parse video payloads from content
        val payloads = parseDataPayloads(post.content.rendered)

        // Create data string containing all payloads
        val dataJson = payloads.joinToString("|||")

        return newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
            this.posterUrl = posterUrl
            this.plot = description
            this.year = post.date.split("-").firstOrNull()?.toIntOrNull()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data contains base64 payloads separated by |||
        val payloads = data.split("|||")

        payloads.forEachIndexed { index, payload ->
            try {
                val serverName = "Server ${index + 1}"
                
                // Step 1: Decode base64 to get dvt_video URL
                val dvtVideoUrl = String(Base64.getDecoder().decode(payload))

                // Step 2: Request dvt_video URL to get iframe
                val iframeDoc = app.get(
                    dvtVideoUrl,
                    referer = mainUrl,
                    headers = mapOf("User-Agent" to "Mozilla/5.0")
                ).document

                val iframeSrc = iframeDoc.selectFirst("iframe")?.attr("src") ?: return@forEachIndexed

                // Step 3: Request iframe page to extract stream URL
                val streamPageHtml = app.get(
                    iframeSrc,
                    referer = dvtVideoUrl,
                    headers = mapOf("User-Agent" to "Mozilla/5.0")
                ).text

                // Extract stream.php URL from JavaScript
                val streamUrlRegex = """sourceElement\.src\s*=\s*["']([^"']+)["']""".toRegex()
                val streamPath = streamUrlRegex.find(streamPageHtml)?.groupValues?.get(1) ?: return@forEachIndexed

                // Build full stream URL
                val baseUrl = iframeSrc.substringBefore("/index.php")
                val fullStreamUrl = if (streamPath.startsWith("http")) {
                    streamPath
                } else {
                    "$baseUrl/$streamPath"
                }

                // Step 4: Follow stream.php to get final video URL
                val finalResponse = app.get(
                    fullStreamUrl,
                    referer = iframeSrc,
                    allowRedirects = true,
                    headers = mapOf("User-Agent" to "Mozilla/5.0")
                )

                val finalVideoUrl = finalResponse.url

                // Step 5: Add the video link
                val linkType = if (finalVideoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                val link = newExtractorLink(
                    source = name,
                    name = "$name - $serverName",
                    url = finalVideoUrl,
                    type = linkType
                ) {
                    this.referer = baseUrl
                    this.quality = Qualities.Unknown.value
                }
                callback.invoke(link)
            } catch (e: Exception) {
                // Continue to next server if this one fails
            }
        }

        return true
    }

    // Helper functions
    private suspend fun getMediaUrl(mediaId: Int): String? {
        return try {
            val media = app.get("$wpApiUrl/media/$mediaId")
                .parsedSafe<WPMedia>()
            media?.source_url
        } catch (e: Exception) {
            null
        }
    }

    private fun parseDataPayloads(htmlContent: String): List<String> {
        val regex = """data-payload=["']([^"']+)["']""".toRegex()
        return regex.findAll(htmlContent).map { it.groupValues[1] }.toList()
    }

    private fun extractEpisodeNumber(title: String): Int? {
        // Try to extract episode number from patterns like "#236" or "Episode 236"
        val patterns = listOf(
            """#(\d+)""".toRegex(),
            """Episode\s+(\d+)""".toRegex(RegexOption.IGNORE_CASE),
            """Ep\s+(\d+)""".toRegex(RegexOption.IGNORE_CASE),
            """E(\d+)""".toRegex()
        )

        for (pattern in patterns) {
            val match = pattern.find(title)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }
        return null
    }

    private fun String.cleanHtml(): String {
        return this.replace("<[^>]*>".toRegex(), "")
            .replace("&#8211;", "-")
            .replace("&#8217;", "'")
            .replace("&amp;", "&")
            .trim()
    }

    private suspend fun WPPost.toSearchResponse(): SearchResponse? {
        val title = this.title.rendered.cleanHtml()
        val posterUrl = getMediaUrl(this.featured_media)

        return newMovieSearchResponse(title, this.link, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    // Data models
    data class WPPost(
        val id: Int,
        val date: String,
        val title: WPRendered,
        val content: WPRendered,
        val excerpt: WPRendered,
        val link: String,
        val featured_media: Int,
        val categories: List<Int>
    )

    data class WPRendered(val rendered: String)

    data class WPMedia(
        val id: Int,
        val source_url: String
    )
}
