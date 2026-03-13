package com.spotifymcp

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import kotlinx.serialization.json.*

fun buildMcpServer(): Server {
    val server = Server(
        serverInfo = Implementation(name = "spotify-mcp-server", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false)
            )
        )
    )

    // ─── Play ─────────────────────────────────────────────────────────────────
    server.addTool(
        name = "spotify_play",
        description = "Play a track on Spotify. Provide a Spotify URI (e.g. spotify:track:...) or a search query string.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("uri") {
                    put("type", "string")
                    put("description", "Spotify URI of the track to play")
                }
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Search query to find and play a track")
                }
            }
        )
    ) { request ->
        val args = request.arguments
        val result = SpotifyClient.play(
            uri   = args["uri"]?.jsonPrimitive?.contentOrNull,
            query = args["query"]?.jsonPrimitive?.contentOrNull
        )
        CallToolResult(content = listOf(TextContent(text = result)))
    }

    // ─── Pause ────────────────────────────────────────────────────────────────
    server.addTool(
        name = "spotify_pause",
        description = "Pause Spotify playback.",
        inputSchema = Tool.Input()
    ) { _ ->
        CallToolResult(content = listOf(TextContent(text = SpotifyClient.pause())))
    }

    // ─── Skip next ────────────────────────────────────────────────────────────
    server.addTool(
        name = "spotify_skip_next",
        description = "Skip to the next track in the Spotify queue.",
        inputSchema = Tool.Input()
    ) { _ ->
        CallToolResult(content = listOf(TextContent(text = SpotifyClient.skipNext())))
    }

    // ─── Skip previous ────────────────────────────────────────────────────────
    server.addTool(
        name = "spotify_skip_previous",
        description = "Go back to the previous track on Spotify.",
        inputSchema = Tool.Input()
    ) { _ ->
        CallToolResult(content = listOf(TextContent(text = SpotifyClient.skipPrevious())))
    }

    // ─── Queue ────────────────────────────────────────────────────────────────
    server.addTool(
        name = "spotify_queue",
        description = "Add a track to the Spotify playback queue by Spotify URI.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("uri") {
                    put("type", "string")
                    put("description", "Spotify URI of the track to queue")
                }
            },
            required = listOf("uri")
        )
    ) { request ->
        val uri = request.arguments["uri"]?.jsonPrimitive?.contentOrNull
            ?: return@addTool CallToolResult(
                isError = true,
                content = listOf(TextContent(text = "Missing required parameter: 'uri'"))
            )
        CallToolResult(content = listOf(TextContent(text = SpotifyClient.queueTrack(uri))))
    }

    // ─── Volume ───────────────────────────────────────────────────────────────
    server.addTool(
        name = "spotify_volume",
        description = "Set Spotify playback volume to a value between 0 and 100.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("percent") {
                    put("type", "integer")
                    put("description", "Volume level 0–100")
                    put("minimum", 0)
                    put("maximum", 100)
                }
            },
            required = listOf("percent")
        )
    ) { request ->
        val percent = request.arguments["percent"]?.jsonPrimitive?.intOrNull
            ?: return@addTool CallToolResult(
                isError = true,
                content = listOf(TextContent(text = "Missing required parameter: 'percent'"))
            )
        CallToolResult(content = listOf(TextContent(text = SpotifyClient.setVolume(percent))))
    }

    // ─── Search ───────────────────────────────────────────────────────────────
    server.addTool(
        name = "spotify_search",
        description = "Search Spotify for tracks, albums, or artists.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Search query")
                }
                putJsonObject("type") {
                    put("type", "string")
                    put("description", "What to search for: track, album, or artist (default: track)")
                }
            },
            required = listOf("query")
        )
    ) { request ->
        val args = request.arguments
        val query = args["query"]?.jsonPrimitive?.contentOrNull
            ?: return@addTool CallToolResult(
                isError = true,
                content = listOf(TextContent(text = "Missing required parameter: 'query'"))
            )
        val type = args["type"]?.jsonPrimitive?.contentOrNull ?: "track"
        val results = SpotifyClient.search(query, type)
        if (results.isEmpty()) {
            CallToolResult(content = listOf(TextContent(text = "No results found for \"$query\".")))
        } else {
            val text = results.joinToString("\n") { r ->
                buildString {
                    append("[${r.confidence}] ${r.name}")
                    if (r.artists != null) append(" — ${r.artists}")
                    if (r.album != null) append(" (${r.album})")
                    append("\n  ${r.uri}")
                }
            }
            CallToolResult(content = listOf(TextContent(text = text)))
        }
    }

    // ─── Now Playing ──────────────────────────────────────────────────────────
    server.addTool(
        name = "spotify_now_playing",
        description = "Get the currently playing track on Spotify.",
        inputSchema = Tool.Input()
    ) { _ ->
        val track = SpotifyClient.getCurrentTrack()
        if (track == null) {
            CallToolResult(content = listOf(TextContent(text = "Nothing is currently playing on Spotify.")))
        } else {
            val status   = if (track.isPlaying) "▶ Playing" else "⏸ Paused"
            val progress = "${track.progressMs / 1000}s / ${track.durationMs / 1000}s"
            CallToolResult(content = listOf(TextContent(text = buildString {
                appendLine("$status: ${track.trackName}")
                appendLine("Artist(s): ${track.artists}")
                appendLine("Album: ${track.albumName}")
                appendLine("Progress: $progress")
                append("URI: ${track.uri}")
            })))
        }
    }

    return server
}
