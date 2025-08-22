package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.*
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.EchoMediaItem.Companion.toMediaItem
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Request.Companion.toRequest
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class KissKHExtension : ExtensionClient, HomeFeedClient, SearchFeedClient, TrackClient {
    private val client = OkHttpClient()
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    // Configuration
    private var baseUrl = "https://kisskh.ovh"
    
    override suspend fun onExtensionSelected() {
        // Initialize extension
    }

    override val settingItems: List<Setting> = emptyList()

    private lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
    }

    // Home Feed Client Implementation
    override fun getHomeFeed(tab: Tab?) = PagedData.Single {
        parsePopularAnimeJsonData(tab)
    }.toFeed()

    override suspend fun getHomeTabs(): List<Tab> {
        return listOf(
            Tab("popular", "Popular"),
            Tab("latest", "Latest")
        )
    }

    // Search Feed Client Implementation
    override fun searchFeed(query: String, tab: Tab?) = if (query.isNotBlank()) PagedData.Single {
        parseSearchAnimeJsonData(query, tab)
    }.toFeed() else PagedData.Single<Shelf> { listOf() }.toFeed()

    override suspend fun searchTabs(query: String): List<Tab> {
        return listOf(
            Tab("all", "All"),
            Tab("drama", "Drama"),
            Tab("movie", "Movie")
        )
    }

    override suspend fun quickSearch(query: String): List<QuickSearchItem> {
        if (query.isBlank()) return emptyList()
        
        val url = "$baseUrl/api/DramaList/Search?q=$query&type=0"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val jsonData = response.body?.string() ?: return emptyList()
        
        return json.decodeFromString<JsonArray>(jsonData).mapNotNull { item ->
            val title = item.jsonObject["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val thumbnail = item.jsonObject["thumbnail"]?.jsonPrimitive?.content
            
            val trackItem = Track(
                id = id,
                title = title,
                artists = listOf(Artist(id = "unknown", name = "Unknown Artist")),
                cover = thumbnail?.toImageHolder(),
                album = Album(id = id, title = title)
            )
            
            QuickSearchItem.Media(trackItem.toMediaItem(), false)
        }
    }

    override suspend fun deleteQuickSearch(item: QuickSearchItem) {
        // Implementation for deleting quick search items
        // Can be empty if not needed
    }

    // Track Client Implementation
    override suspend fun loadTrack(track: Track): Track {
        val url = "$baseUrl/api/DramaList/Drama/${track.id}?isq=false"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val jsonData = response.body?.string() ?: return track
        
        return parseTrackDetails(jsonData, track)
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        try {
            // First, try to get the video key
            val kkey = requestVideoKey(streamable.id)
            if (kkey.isBlank()) {
                throw Exception("Failed to get video key for streamable: ${streamable.id}")
            }
            
            // Try different API endpoints to get the video URL
            val videoUrl = getVideoUrl(streamable.id, kkey)
            if (videoUrl.isBlank()) {
                throw Exception("Failed to get video URL for streamable: ${streamable.id}")
            }
            
            // Check if the URL is an M3U8 playlist or direct video
            return if (isM3u8Url(videoUrl)) {
                // Handle M3U8 playlist
                println("Detected M3U8 URL: $videoUrl")
                val m3u8Sources = parseM3u8Playlist(videoUrl)
                if (m3u8Sources.isEmpty()) {
                    throw Exception("Failed to parse M3U8 playlist or no streams found")
                }
                Streamable.Media.Server(m3u8Sources, false)
            } else {
                // Handle direct video URL
                println("Detected direct video URL: $videoUrl")
                val source = Streamable.Source.Http(
                    request = videoUrl.toRequest()
                )
                Streamable.Media.Server(listOf(source), false)
            }
            
        } catch (e: Exception) {
            // Log the error and return a fallback or throw
            println("Error loading streamable media: ${e.message}")
            throw Exception("Failed to load streamable media: ${e.message}")
        }
    }

    override fun getShelves(track: Track): PagedData<Shelf> = PagedData.Single {
        // Return related content if available
        emptyList()
    }

    // Helper functions
    private fun parsePopularAnimeJsonData(tab: Tab?): List<Shelf> {
        val page = tab?.id?.toIntOrNull() ?: 1
        val url = "$baseUrl/api/DramaList/List?page=$page&type=0&sub=0&country=0&status=0&order=1&pageSize=40"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val jsonData = response.body?.string() ?: return emptyList()
        
        return parsePopularAnimeJson(jsonData)
    }

    private fun parseSearchAnimeJsonData(query: String, tab: Tab?): List<Shelf> {
        if (query.isBlank()) return emptyList()
        
        val url = "$baseUrl/api/DramaList/Search?q=$query&type=0"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val jsonData = response.body?.string() ?: return emptyList()
        
        return parseSearchAnimeJson(jsonData)
    }

    private fun parsePopularAnimeJson(jsonData: String): List<Shelf> {
        val jObject = json.decodeFromString<JsonObject>(jsonData)

        val trackItems = jObject["data"]?.jsonArray?.mapNotNull { item ->
            val title = item.jsonObject["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val thumbnail = item.jsonObject["thumbnail"]?.jsonPrimitive?.content

            Track(
                id = id,
                title = title,
                artists = listOf(Artist(id = "unknown", name = "Unknown Artist")),
                cover = thumbnail?.toImageHolder(),
                album = Album(id = id, title = title)
            )
        } ?: emptyList()

        return listOf(
            Shelf.Lists.Items(
                title = "Popular Dramas",
                list = trackItems.map { trackItem ->
                    EchoMediaItem.TrackItem(trackItem)
                }
            )
        )
    }

    private fun parseSearchAnimeJson(jsonData: String): List<Shelf> {
        val trackItems = json.decodeFromString<JsonArray>(jsonData).mapNotNull { item ->
            val title = item.jsonObject["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val thumbnail = item.jsonObject["thumbnail"]?.jsonPrimitive?.content

            Track(
                id = id,
                title = title,
                artists = listOf(Artist(id = "unknown", name = "Unknown Artist")),
                cover = thumbnail?.toImageHolder(),
                album = Album(id = id, title = title)
            )
        }

        return listOf(
            Shelf.Lists.Items(
                title = "Search Results",
                list = trackItems.map { trackItem ->
                    EchoMediaItem.TrackItem(trackItem)
                }
            )
        )
    }

    private suspend fun parseTrackDetails(jsonData: String, originalTrack: Track): Track {
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        
        return originalTrack.copy(
            title = jObject["title"]?.jsonPrimitive?.content ?: originalTrack.title,
            album = Album(
                id = originalTrack.id,
                title = jObject["title"]?.jsonPrimitive?.content ?: originalTrack.title,
                cover = jObject["thumbnail"]?.jsonPrimitive?.content?.toImageHolder()
            ),
            description = jObject["description"]?.jsonPrimitive?.content
        )
    }

    private suspend fun getVideoUrl(episodeId: String, kkey: String): String {
        return try {
            // Try multiple endpoints to get the video URL
            val endpoints = listOf(
                "$baseUrl/api/DramaList/Episode/$episodeId.png?err=false&ts=&time=&kkey=$kkey",
                "$baseUrl/api/DramaList/Episode/$episodeId?err=false&ts=&time=&kkey=$kkey",
                "$baseUrl/api/DramaList/Episode/$episodeId.mp4?err=false&ts=&time=&kkey=$kkey",
                "$baseUrl/api/DramaList/Episode/$episodeId.m3u8?err=false&ts=&time=&kkey=$kkey"
            )
            
            for (endpoint in endpoints) {
                try {
                    val request = Request.Builder().url(endpoint).build()
                    val response = client.newCall(request).execute()
                    val jsonData = response.body?.string() ?: continue
                    
                    val jObject = json.decodeFromString<JsonObject>(jsonData)
                    val videoUrl = jObject["Video"]?.jsonPrimitive?.content ?: 
                                  jObject["videoUrl"]?.jsonPrimitive?.content ?: 
                                  jObject["url"]?.jsonPrimitive?.content
                    
                    if (!videoUrl.isNullOrBlank()) {
                        println("Found video URL from endpoint $endpoint: $videoUrl")
                        return videoUrl
                    }
                } catch (e: Exception) {
                    println("Failed to get video URL from endpoint $endpoint: ${e.message}")
                    continue
                }
            }
            
            throw Exception("All video URL endpoints failed")
            
        } catch (e: Exception) {
            println("Error getting video URL: ${e.message}")
            throw e
        }
    }

    private suspend fun requestVideoKey(id: String): String {
        return try {
            val url = "https://kisskh.ovh/api/key/$id&version=2.8.10"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                throw Exception("Failed to get video key: HTTP ${response.code}")
            }
            
            val keyResponse = response.body?.string() ?: throw Exception("Empty response for video key")
            val keyObject = json.decodeFromString<JsonObject>(keyResponse)
            val key = keyObject["key"]?.jsonPrimitive?.content ?: throw Exception("Key not found in response")
            
            println("Successfully retrieved video key for ID: $id")
            return key
            
        } catch (e: Exception) {
            println("Error requesting video key: ${e.message}")
            throw Exception("Failed to get video key: ${e.message}")
        }
    }

    // M3U8 Support Functions
    private fun isM3u8Url(url: String): Boolean {
        return url.lowercase().contains(".m3u8") || 
               url.lowercase().contains("/master.m3u8") ||
               url.lowercase().contains("/playlist.m3u8") ||
               url.lowercase().contains("hls") ||
               url.lowercase().contains(".m3u")
    }

    private suspend fun parseM3u8Playlist(m3u8Url: String): List<Streamable.Source.Http> {
        return try {
            println("Parsing M3U8 playlist: $m3u8Url")
            val request = Request.Builder().url(m3u8Url).build()
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                throw Exception("Failed to fetch M3U8 playlist: HTTP ${response.code}")
            }
            
            val playlistContent = response.body?.string() ?: throw Exception("Empty M3U8 playlist response")
            
            // Parse M3U8 content
            val sources = mutableListOf<Streamable.Source.Http>()
            val lines = playlistContent.split("\n")
            
            var currentBandwidth = 0
            var currentResolution = ""
            var currentUrl = ""
            
            for (line in lines) {
                val trimmedLine = line.trim()
                
                when {
                    trimmedLine.startsWith("#EXT-X-STREAM-INF") -> {
                        // Parse stream info
                        currentBandwidth = extractBandwidth(trimmedLine)
                        currentResolution = extractResolution(trimmedLine)
                    }
                    trimmedLine.startsWith("#EXT-X-") -> {
                        // Other M3U8 tags, ignore for now
                    }
                    trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#") -> {
                        // This is a URL
                        currentUrl = trimmedLine
                        if (currentUrl.isNotBlank()) {
                            // Handle relative URLs
                            val absoluteUrl = if (currentUrl.startsWith("http")) {
                                currentUrl
                            } else {
                                resolveRelativeUrl(m3u8Url, currentUrl)
                            }
                            
                            val source = Streamable.Source.Http(
                                request = absoluteUrl.toRequest()
                            )
                            sources.add(source)
                            
                            println("Added M3U8 stream: $absoluteUrl (Bandwidth: $currentBandwidth, Resolution: $currentResolution)")
                        }
                        // Reset for next stream
                        currentBandwidth = 0
                        currentResolution = ""
                        currentUrl = ""
                    }
                }
            }
            
            if (sources.isEmpty()) {
                // If no streams were parsed, try to use the original M3U8 URL directly
                println("No streams parsed from M3U8, using original URL")
                sources.add(Streamable.Source.Http(request = m3u8Url.toRequest()))
            }
            
            println("Parsed ${sources.size} streams from M3U8 playlist")
            sources
            
        } catch (e: Exception) {
            println("Error parsing M3U8 playlist: ${e.message}")
            // Fallback: return the original M3U8 URL as a single source
            listOf(Streamable.Source.Http(request = m3u8Url.toRequest()))
        }
    }

    private fun extractBandwidth(streamInfo: String): Int {
        val bandwidthMatch = Regex("BANDWIDTH=(\\d+)").find(streamInfo)
        return bandwidthMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun extractResolution(streamInfo: String): String {
        val resolutionMatch = Regex("RESOLUTION=(\\d+x\\d+)").find(streamInfo)
        return resolutionMatch?.groupValues?.get(1) ?: ""
    }

    private fun resolveRelativeUrl(baseUrl: String, relativeUrl: String): String {
        return try {
            val baseUri = java.net.URI(baseUrl)
            if (relativeUrl.startsWith("/")) {
                // Absolute path
                java.net.URI(
                    baseUri.scheme,
                    baseUri.userInfo,
                    baseUri.host,
                    baseUri.port,
                    relativeUrl,
                    null,
                    null
                ).toString()
            } else {
                // Relative path
                val basePath = baseUri.path?.substring(0, baseUri.path.lastIndexOf('/')) ?: ""
                java.net.URI(
                    baseUri.scheme,
                    baseUri.userInfo,
                    baseUri.host,
                    baseUri.port,
                    "$basePath/$relativeUrl",
                    null,
                    null
                ).toString()
            }
        } catch (e: Exception) {
            println("Error resolving relative URL: $baseUrl + $relativeUrl, error: ${e.message}")
            relativeUrl
        }
    }

    @Serializable
    data class Key(
        val id: String,
        val version: String,
        val key: String,
    )
}
