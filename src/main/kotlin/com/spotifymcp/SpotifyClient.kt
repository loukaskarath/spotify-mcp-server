package com.spotifymcp

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("SpotifyClient")

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

// ─── Data classes for Spotify API responses ───────────────────────────────────

@Serializable
data class SpotifyTrack(
    val id: String,
    val name: String,
    val uri: String,
    val artists: List<SpotifyArtist> = emptyList(),
    val album: SpotifyAlbum? = null,
    @SerialName("duration_ms") val durationMs: Long = 0
)

@Serializable
data class SpotifyArtist(
    val id: String,
    val name: String,
    val uri: String
)

@Serializable
data class SpotifyAlbum(
    val id: String,
    val name: String,
    val uri: String,
    @SerialName("release_date") val releaseDate: String? = null
)

data class SearchResult(
    val name: String,
    val uri: String,
    val type: String, // "track", "album", "artist"
    val artists: String? = null,  // comma-separated artist names for tracks
    val album: String? = null,
    val confidence: String         // "exact", "partial", "not_found"
)

data class CurrentTrack(
    val trackName: String,
    val artists: String,
    val albumName: String,
    val uri: String,
    val progressMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean
)

// ─── Main Spotify Client ───────────────────────────────────────────────────────

object SpotifyClient {

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.NONE  // Set to INFO for debugging
        }
    }

    private suspend fun authHeader(): String = "Bearer ${SpotifyAuth.ensureValidToken()}"

    private val apiBase = "https://api.spotify.com/v1"

    // ─── Playback Controls ────────────────────────────────────────────────────

    /**
     * Plays a track. If [uri] is a Spotify URI, plays it directly.
     * If [query] is provided instead, searches first then plays the top result.
     */
    suspend fun play(uri: String? = null, query: String? = null): String {
        val trackUri = when {
            uri != null -> uri
            query != null -> {
                val results = search(query, "track")
                results.firstOrNull { it.confidence == "exact" }?.uri
                    ?: results.firstOrNull { it.confidence == "partial" }?.uri
                    ?: return "No track found for query: \"$query\""
            }
            else -> return "Either 'uri' or 'query' must be provided."
        }

        val body = if (trackUri.startsWith("spotify:track:")) {
            """{"uris":["$trackUri"]}"""
        } else {
            """{"context_uri":"$trackUri"}"""
        }
        val response = httpClient.put("$apiBase/me/player/play") {
            header(HttpHeaders.Authorization, authHeader())
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        return if (response.status.isSuccess() || response.status.value == 204) {
            "▶ Now playing: $trackUri"
        } else {
            "Failed to start playback (${response.status.value}). Make sure Spotify is open on an active device."
        }
    }

    /** Pauses playback on the active device. */
    suspend fun pause(): String {
        val response = httpClient.put("$apiBase/me/player/pause") {
            header(HttpHeaders.Authorization, authHeader())
        }
        return if (response.status.isSuccess() || response.status.value == 204) {
            "⏸ Playback paused."
        } else {
            "Failed to pause (${response.status.value}). Is Spotify active on a device?"
        }
    }

    /** Skips to the next track in the queue. */
    suspend fun skipNext(): String {
        val response = httpClient.post("$apiBase/me/player/next") {
            header(HttpHeaders.Authorization, authHeader())
        }
        return if (response.status.isSuccess() || response.status.value == 204) {
            "⏭ Skipped to next track."
        } else {
            "Failed to skip next (${response.status.value})."
        }
    }

    /** Goes back to the previous track. */
    suspend fun skipPrevious(): String {
        val response = httpClient.post("$apiBase/me/player/previous") {
            header(HttpHeaders.Authorization, authHeader())
        }
        return if (response.status.isSuccess() || response.status.value == 204) {
            "⏮ Went back to previous track."
        } else {
            "Failed to skip previous (${response.status.value})."
        }
    }

    /** Adds a Spotify URI to the playback queue. */
    suspend fun queueTrack(uri: String): String {
        val response = httpClient.post("$apiBase/me/player/queue") {
            header(HttpHeaders.Authorization, authHeader())
            parameter("uri", uri)
        }
        return if (response.status.isSuccess() || response.status.value == 204) {
            "➕ Added to queue: $uri"
        } else {
            "Failed to add to queue (${response.status.value})."
        }
    }

    /** Sets the playback volume (0–100). */
    suspend fun setVolume(percent: Int): String {
        val clamped = percent.coerceIn(0, 100)
        val response = httpClient.put("$apiBase/me/player/volume") {
            header(HttpHeaders.Authorization, authHeader())
            parameter("volume_percent", clamped)
        }
        return if (response.status.isSuccess() || response.status.value == 204) {
            "🔊 Volume set to $clamped%."
        } else {
            "Failed to set volume (${response.status.value}). Active device required."
        }
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    /**
     * Searches Spotify and returns results with a confidence level:
     * - "exact"   — result name matches query exactly (case-insensitive)
     * - "partial" — result name contains the query
     * - "no_match" — no similarity, but still returned as a candidate
     */
    suspend fun search(query: String, type: String = "track"): List<SearchResult> {
        val validTypes = setOf("track", "album", "artist")
        val resolvedType = if (type in validTypes) type else "track"

        val responseBody: JsonObject = httpClient.get("$apiBase/search") {
            header(HttpHeaders.Authorization, authHeader())
            parameter("q", query)
            parameter("type", resolvedType)
            parameter("limit", 10)
        }.body()

        val queryLower = query.lowercase()
        val results = mutableListOf<SearchResult>()

        when (resolvedType) {
            "track" -> {
                val items = responseBody["tracks"]?.jsonObject?.get("items")?.jsonArray ?: return emptyList()
                for (item in items) {
                    val obj = item.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.content ?: continue
                    val uri = obj["uri"]?.jsonPrimitive?.content ?: continue
                    val artistsStr = obj["artists"]?.jsonArray
                        ?.joinToString(", ") { it.jsonObject["name"]?.jsonPrimitive?.content ?: "" } ?: ""
                    val albumName = obj["album"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: ""
                    results.add(
                        SearchResult(
                            name = name,
                            uri = uri,
                            type = "track",
                            artists = artistsStr,
                            album = albumName,
                            confidence = computeConfidence(queryLower, name, artistsStr)
                        )
                    )
                }
            }
            "album" -> {
                val items = responseBody["albums"]?.jsonObject?.get("items")?.jsonArray ?: return emptyList()
                for (item in items) {
                    val obj = item.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.content ?: continue
                    val uri = obj["uri"]?.jsonPrimitive?.content ?: continue
                    val artistsStr = obj["artists"]?.jsonArray
                        ?.joinToString(", ") { it.jsonObject["name"]?.jsonPrimitive?.content ?: "" } ?: ""
                    results.add(
                        SearchResult(
                            name = name,
                            uri = uri,
                            type = "album",
                            artists = artistsStr,
                            confidence = computeConfidence(queryLower, name, artistsStr)
                        )
                    )
                }
            }
            "artist" -> {
                val items = responseBody["artists"]?.jsonObject?.get("items")?.jsonArray ?: return emptyList()
                for (item in items) {
                    val obj = item.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.content ?: continue
                    val uri = obj["uri"]?.jsonPrimitive?.content ?: continue
                    results.add(
                        SearchResult(
                            name = name,
                            uri = uri,
                            type = "artist",
                            confidence = computeConfidence(queryLower, name, null)
                        )
                    )
                }
            }
        }

        return results
    }

    // ─── Now Playing ─────────────────────────────────────────────────────────

    /** Returns information about the currently playing track, or null if nothing is playing. */
    suspend fun getCurrentTrack(): CurrentTrack? {
        val response = httpClient.get("$apiBase/me/player/currently-playing") {
            header(HttpHeaders.Authorization, authHeader())
        }

        if (response.status.value == 204 || !response.status.isSuccess()) return null

        return try {
            val body: JsonObject = response.body()
            val item = body["item"]?.jsonObject ?: return null
            val name = item["name"]?.jsonPrimitive?.content ?: "Unknown"
            val uri = item["uri"]?.jsonPrimitive?.content ?: ""
            val artists = item["artists"]?.jsonArray
                ?.joinToString(", ") { it.jsonObject["name"]?.jsonPrimitive?.content ?: "" } ?: "Unknown"
            val album = item["album"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "Unknown"
            val progressMs = body["progress_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val durationMs = item["duration_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val isPlaying = body["is_playing"]?.jsonPrimitive?.content?.toBoolean() ?: false

            CurrentTrack(
                trackName = name,
                artists = artists,
                albumName = album,
                uri = uri,
                progressMs = progressMs,
                durationMs = durationMs,
                isPlaying = isPlaying
            )
        } catch (e: Exception) {
            log.error("Failed to parse currently-playing response: ${e.message}")
            null
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun computeConfidence(queryLower: String, name: String, artists: String?): String {
        val nameLower = name.lowercase()
        val fullLower = if (artists != null) "$nameLower ${artists.lowercase()}" else nameLower
        return when {
            nameLower == queryLower -> "exact"
            fullLower == queryLower -> "exact"
            nameLower.contains(queryLower) -> "partial"
            artists != null && artists.lowercase().contains(queryLower) -> "partial"
            queryLower.contains(nameLower) -> "partial"
            else -> "no_match"
        }
    }
}
