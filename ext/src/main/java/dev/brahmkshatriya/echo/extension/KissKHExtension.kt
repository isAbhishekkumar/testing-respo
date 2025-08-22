package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.*
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.EchoMediaItem.Companion.toMediaItem
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Request.Companion.toRequest
import dev.brahmkshatriya.echo.common.models.Feed
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
                album = Album(id = id, title = title),
                artists = listOf(Artist(id = "unknown", name = "Unknown Artist")),
                duration = 0L,
                cover = thumbnail?.toImageHolder()
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
        val kkey = requestVideoKey(streamable.id)
        val url = "$baseUrl/api/DramaList/Episode/${streamable.id}.png?err=false&ts=&time=&kkey=$kkey"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        
        return videosFromElement(response, streamable.id)
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
                album = Album(id = id, title = title),
                artists = listOf(Artist(id = "unknown", name = "Unknown Artist")),
                duration = 0L,
                cover = thumbnail?.toImageHolder()
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
                album = Album(id = id, title = title),
                artists = listOf(Artist(id = "unknown", name = "Unknown Artist")),
                duration = 0L,
                cover = thumbnail?.toImageHolder()
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