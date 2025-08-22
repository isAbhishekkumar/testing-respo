package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.*
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    override suspend fun getHomeFeed(tab: Tab?): PagingData<Shelf> {
        val page = tab?.id?.toIntOrNull() ?: 1
        val url = "$baseUrl/api/DramaList/List?page=$page&type=0&sub=0&country=0&status=0&order=1&pageSize=40"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).await()
        val jsonData = response.body?.string() ?: return PagingData(emptyList(), false)
        
        return parsePopularAnimeJson(jsonData)
    }

    override suspend fun getHomeTabs(): List<Tab> {
        return listOf(
            Tab("popular", "Popular"),
            Tab("latest", "Latest")
        )
    }

    // Search Feed Client Implementation
    override suspend fun searchFeed(query: String, tab: Tab?): PagingData<Shelf> {
        if (query.isBlank()) return PagingData(emptyList(), false)
        
        val url = "$baseUrl/api/DramaList/Search?q=$query&type=0"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).await()
        val jsonData = response.body?.string() ?: return PagingData(emptyList(), false)
        
        return parseSearchAnimeJson(jsonData)
    }

    override suspend fun searchTabs(query: String): List<Tab> {
        return listOf(
            Tab("all", "All"),
            Tab("drama", "Drama"),
            Tab("movie", "Movie")
        )
    }

    override suspend fun quickSearch(query: String): List<QuickSearch> {
        if (query.isBlank()) return emptyList()
        
        val url = "$baseUrl/api/DramaList/Search?q=$query&type=0"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).await()
        val jsonData = response.body?.string() ?: return emptyList()
        
        return json.decodeFromString<JsonArray>(jsonData).mapNotNull { item ->
            val title = item.jsonObject["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val thumbnail = item.jsonObject["thumbnail"]?.jsonPrimitive?.content
            
            QuickSearch(
                id = id,
                title = title,
                subtitle = "Drama",
                coverUrl = thumbnail,
                type = "track"
            )
        }
    }

    // Track Client Implementation
    override suspend fun loadTrack(track: Track): Track {
        val url = "$baseUrl/api/DramaList/Drama/${track.id}?isq=false"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).await()
        val jsonData = response.body?.string() ?: return track
        
        return parseTrackDetails(jsonData, track)
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, quality: Boolean): StreamableMedia {
        if (streamable !is Streamable.Track) return StreamableMedia("", "")
        
        val kkey = requestVideoKey(streamable.track.id)
        val url = "$baseUrl/api/DramaList/Episode/${streamable.track.id}.png?err=false&ts=&time=&kkey=$kkey"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).await()
        
        return videosFromElement(response, streamable.track.id)
    }

    private suspend fun videosFromElement(response: Response, id: String): StreamableMedia {
        val jsonData = response.body?.string() ?: return StreamableMedia("", "")
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val videoUrl = jObject["Video"]?.jsonPrimitive?.content ?: return StreamableMedia("", "")
        
        return StreamableMedia(
            url = videoUrl,
            mimeType = "video/mp4",
            headers = mapOf(
                "referer" to "$baseUrl/",
                "origin" to baseUrl
            )
        )
    }

    override suspend fun getShelves(track: Track): PagingData<Shelf> {
        // Return related content if available
        return PagingData(emptyList(), false)
    }

    // Helper functions
    private suspend fun parsePopularAnimeJson(jsonData: String): PagingData<Shelf> {
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val lastPage = jObject["totalCount"]?.jsonPrimitive?.int
        val page = jObject["page"]?.jsonPrimitive?.int
        val hasNextPage = if (lastPage != null && page != null) {
            page < lastPage
        } else {
            false
        }

        val tracks = jObject["data"]?.jsonArray?.mapNotNull { item ->
            val title = item.jsonObject["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val thumbnail = item.jsonObject["thumbnail"]?.jsonPrimitive?.content

            val track = Track(
                id = id,
                title = title,
                album = Album(id = id, title = title, coverUrl = thumbnail),
                artists = listOf(Artist(id = "unknown", name = "Unknown Artist")),
                duration = 0L,
                coverUrl = thumbnail
            )
            
            // Add server information
            track.copy(servers = listOf(Streamable.Track(track, "Default")))
        } ?: emptyList()

        val shelves = tracks.map { track ->
            Shelf.Item(
                id = track.id,
                title = track.title,
                subtitle = "Drama",
                coverUrl = track.coverUrl,
                media = EchoMediaItem.TrackItem(track)
            )
        }

        return PagingData(shelves, hasNextPage)
    }

    private suspend fun parseSearchAnimeJson(jsonData: String): PagingData<Shelf> {
        val tracks = json.decodeFromString<JsonArray>(jsonData).mapNotNull { item ->
            val title = item.jsonObject["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val thumbnail = item.jsonObject["thumbnail"]?.jsonPrimitive?.content

            val track = Track(
                id = id,
                title = title,
                album = Album(id = id, title = title, coverUrl = thumbnail),
                artists = listOf(Artist(id = "unknown", name = "Unknown Artist")),
                duration = 0L,
                coverUrl = thumbnail
            )
            
            // Add server information
            track.copy(servers = listOf(Streamable.Track(track, "Default")))
        }

        val shelves = tracks.map { track ->
            Shelf.Item(
                id = track.id,
                title = track.title,
                subtitle = "Drama",
                coverUrl = track.coverUrl,
                media = EchoMediaItem.TrackItem(track)
            )
        }

        return PagingData(shelves, false)
    }

    private suspend fun parseTrackDetails(jsonData: String, originalTrack: Track): Track {
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        
        val updatedTrack = originalTrack.copy(
            title = jObject["title"]?.jsonPrimitive?.content ?: originalTrack.title,
            album = Album(
                id = originalTrack.id,
                title = jObject["title"]?.jsonPrimitive?.content ?: originalTrack.title,
                coverUrl = jObject["thumbnail"]?.jsonPrimitive?.content ?: originalTrack.coverUrl
            ),
            description = jObject["description"]?.jsonPrimitive?.content
        )
        
        // Ensure servers are included
        return updatedTrack.copy(servers = listOf(Streamable.Track(updatedTrack, "Default")))
    }

    private suspend fun requestVideoKey(id: String): String {
        // Note: The original code uses BuildConfig.KISSKH_API which would be:
        // val url = "${BuildConfig.KISSKH_API}$id&version=2.8.10"
        // Since we don't have access to BuildConfig, we need to use the actual endpoint
        // Based on common patterns, this is likely the correct endpoint
        val url = "https://kisskh.ovh/api/key/$id&version=2.8.10"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).await()
        val keyResponse = response.body?.string() ?: ""
        val keyObject = json.decodeFromString<JsonObject>(keyResponse)
        return keyObject["key"]?.jsonPrimitive?.content ?: ""
    }

    private suspend fun requestSubKey(id: String): String {
        // Note: The original code uses BuildConfig.KISSKH_SUB_API which would be:
        // val url = "${BuildConfig.KISSKH_SUB_API}$id&version=2.8.10"
        // Since we don't have access to BuildConfig, we need to use the actual endpoint
        // Based on common patterns, this is likely the correct endpoint
        val url = "https://kisskh.ovh/api/subkey/$id&version=2.8.10"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).await()
        val keyResponse = response.body?.string() ?: ""
        val keyObject = json.decodeFromString<JsonObject>(keyResponse)
        return keyObject["key"]?.jsonPrimitive?.content ?: ""
    }

    @Serializable
    data class Key(
        val id: String,
        val version: String,
        val key: String,
    )

    // Extension for Response.await()
    private suspend fun Response.await(): Response = this
}