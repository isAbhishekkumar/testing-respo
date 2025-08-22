package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.*
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.EchoMediaItem.Companion.toMediaItem
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Request.Companion.toRequest
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
    override fun getHomeFeed(tab: Tab?): PagedData<Shelf> = PagedData.Single {
        val page = tab?.id?.toIntOrNull() ?: 1
        val url = "$baseUrl/api/DramaList/List?page=$page&type=0&sub=0&country=0&status=0&order=1&pageSize=40"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val jsonData = response.body?.string() ?: return@Single emptyList()
        
        parsePopularAnimeJson(jsonData)
    }

    override suspend fun getHomeTabs(): List<Tab> {
        return listOf(
            Tab("popular", "Popular"),
            Tab("latest", "Latest")
        )
    }

    // Search Feed Client Implementation
    override fun searchFeed(query: String, tab: Tab?): PagedData<Shelf> = PagedData.Single {
        if (query.isBlank()) return@Single emptyList()
        
        val url = "$baseUrl/api/DramaList/Search?q=$query&type=0"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val jsonData = response.body?.string() ?: return@Single emptyList()
        
        parseSearchAnimeJson(jsonData)
    }

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
            
            val track = Track(
                id = id,
                title = title,
                album = Album(id = id, title = title),
                artists = listOf(Artist(id = "unknown", name = "Unknown Artist")),
                duration = 0L
            ).apply {
                if (thumbnail != null) {
                    cover = thumbnail.toImageHolder()
                }
            }
            
            QuickSearchItem.Media(track.toMediaItem(), false)
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
        if (streamable !is Streamable.Track) return Streamable.Media.Server(emptyList(), false)
        
        val kkey = requestVideoKey(streamable.track.id)
        val url = "$baseUrl/api/DramaList/Episode/${streamable.track.id}.png?err=false&ts=&time=&kkey=$kkey"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        
        return videosFromElement(response, streamable.track.id)
    }

    override fun getShelves(track: Track): PagedData<Shelf> = PagedData.Single {
        // Return related content if available
        emptyList()
    }

    // Helper functions
    private fun parsePopularAnimeJson(jsonData: String): List<Shelf> {
        val jObject = json.decodeFromString<JsonObject>(jsonData)

        val tracks = jObject["data"]?.jsonArray?.mapNotNull { item ->
            val title = item.jsonObject["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val thumbnail = item.jsonObject["thumbnail"]?.jsonPrimitive?.content

            Track(
                id = id,
                title = title,
                album = Album(id = id, title = title),
                artists = listOf(Artist(id = "unknown", name = "Unknown Artist")),
                duration = 0L
            ).apply {
                if (thumbnail != null) {
                    cover = thumbnail.toImageHolder()
                }
            }
        } ?: emptyList()

        return listOf(
            Shelf.Lists.Items(
                title = "Popular Dramas",
                list = tracks.map { track ->
                    EchoMediaItem.TrackItem(track)
                }
            )
        )
    }

    private fun parseSearchAnimeJson(jsonData: String): List<Shelf> {
        val tracks = json.decodeFromString<JsonArray>(jsonData).mapNotNull { item ->
            val title = item.jsonObject["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val thumbnail = item.jsonObject["thumbnail"]?.jsonPrimitive?.content

            Track(
                id = id,
                title = title,
                album = Album(id = id, title = title),
                artists = listOf(Artist(id = "unknown", name = "Unknown Artist")),
                duration = 0L
            ).apply {
                if (thumbnail != null) {
                    cover = thumbnail.toImageHolder()
                }
            }
        }

        return listOf(
            Shelf.Lists.Items(
                title = "Search Results",
                list = tracks.map { track ->
                    EchoMediaItem.TrackItem(track)
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
                title = jObject["title"]?.jsonPrimitive?.content ?: originalTrack.title
            ).apply {
                val thumbnail = jObject["thumbnail"]?.jsonPrimitive?.content
                if (thumbnail != null) {
                    cover = thumbnail.toImageHolder()
                }
            },
            description = jObject["description"]?.jsonPrimitive?.content
        )
    }

    private suspend fun videosFromElement(response: Response, id: String): Streamable.Media {
        val jsonData = response.body?.string() ?: return Streamable.Media.Server(emptyList(), false)
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val videoUrl = jObject["Video"]?.jsonPrimitive?.content ?: return Streamable.Media.Server(emptyList(), false)
        
        val source = Streamable.Source.Http(
            request = videoUrl.toRequest()
        )
        
        return Streamable.Media.Server(listOf(source), false)
    }

    private suspend fun requestVideoKey(id: String): String {
        val url = "https://kisskh.ovh/api/key/$id&version=2.8.10"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val keyResponse = response.body?.string() ?: ""
        val keyObject = json.decodeFromString<JsonObject>(keyResponse)
        return keyObject["key"]?.jsonPrimitive?.content ?: ""
    }

    private suspend fun requestSubKey(id: String): String {
        val url = "https://kisskh.ovh/api/subkey/$id&version=2.8.10"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
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
}