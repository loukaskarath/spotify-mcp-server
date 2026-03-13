package com.spotifymcp

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

private val log = LoggerFactory.getLogger("SpotifyAuth")

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("scope") val scope: String? = null
)

@Serializable
private data class PersistedTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long
)

object SpotifyAuth {

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient(CIO)

    private val tokenFile: Path = Path.of(System.getProperty("user.home"), ".spotify-mcp", "tokens.json")

    @Volatile private var accessToken: String? = null
    @Volatile private var refreshToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0L

    private val scopes = listOf(
        "user-read-playback-state",
        "user-modify-playback-state",
        "user-read-currently-playing",
        "streaming"
    ).joinToString(" ")

    init {
        loadTokensFromDisk()
        // On cloud deployments (ephemeral filesystem), bootstrap from env var if disk has nothing
        if (refreshToken == null) {
            val envRefreshToken = System.getenv("SPOTIFY_REFRESH_TOKEN")
            if (envRefreshToken != null) {
                refreshToken = envRefreshToken
                log.info("Loaded refresh token from SPOTIFY_REFRESH_TOKEN environment variable.")
            }
        }
    }

    fun buildAuthorizationUrl(): String {
        val encodedRedirect = URLEncoder.encode(Config.redirectUri, "UTF-8")
        val encodedScopes  = URLEncoder.encode(scopes, "UTF-8")
        return "https://accounts.spotify.com/authorize" +
            "?client_id=${Config.clientId}" +
            "&response_type=code" +
            "&redirect_uri=$encodedRedirect" +
            "&scope=$encodedScopes"
    }

    suspend fun exchangeCodeForTokens(code: String) {
        val response = httpClient.post("https://accounts.spotify.com/api/token") {
            header(HttpHeaders.Authorization, basicAuthHeader())
            setBody(FormDataContent(Parameters.build {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", Config.redirectUri)
            }))
        }
        if (!response.status.isSuccess()) {
            throw Exception("Token exchange failed (${response.status.value}): ${response.bodyAsText()}")
        }
        storeTokens(json.decodeFromString(response.bodyAsText()))
        log.info("Spotify authentication successful.")
    }

    suspend fun ensureValidToken(): String {
        if (accessToken != null && System.currentTimeMillis() < tokenExpiresAt) {
            return accessToken!!
        }
        val rt = refreshToken
            ?: throw Exception("Not authenticated. Visit http://127.0.0.1:${Config.serverPort}/login")

        log.info("Access token expired — refreshing…")
        val response = httpClient.post("https://accounts.spotify.com/api/token") {
            header(HttpHeaders.Authorization, basicAuthHeader())
            setBody(FormDataContent(Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", rt)
            }))
        }
        if (!response.status.isSuccess()) {
            throw Exception("Token refresh failed (${response.status.value}): ${response.bodyAsText()}")
        }
        storeTokens(json.decodeFromString(response.bodyAsText()))
        return accessToken!!
    }

    fun isAuthenticated(): Boolean = refreshToken != null

    fun getRefreshToken(): String? = refreshToken

    // ─── Token persistence ────────────────────────────────────────────────────

    private fun loadTokensFromDisk() {
        try {
            if (!Files.exists(tokenFile)) return
            val persisted = json.decodeFromString<PersistedTokens>(Files.readString(tokenFile))
            accessToken    = persisted.accessToken
            refreshToken   = persisted.refreshToken
            tokenExpiresAt = persisted.expiresAt
            log.info("Loaded saved Spotify tokens from disk.")
        } catch (e: Exception) {
            log.warn("Could not load saved tokens: ${e.message}")
        }
    }

    private fun saveTokensToDisk() {
        try {
            Files.createDirectories(tokenFile.parent)
            val persisted = PersistedTokens(
                accessToken  = accessToken ?: return,
                refreshToken = refreshToken ?: return,
                expiresAt    = tokenExpiresAt
            )
            Files.writeString(tokenFile, json.encodeToString(PersistedTokens.serializer(), persisted))
        } catch (e: Exception) {
            log.warn("Could not save tokens to disk: ${e.message}")
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun basicAuthHeader(): String {
        val encoded = Base64.getEncoder()
            .encodeToString("${Config.clientId}:${Config.clientSecret}".toByteArray())
        return "Basic $encoded"
    }

    private fun storeTokens(token: TokenResponse) {
        accessToken    = token.accessToken
        if (token.refreshToken != null) refreshToken = token.refreshToken
        tokenExpiresAt = System.currentTimeMillis() + (token.expiresIn - 60) * 1000L
        saveTokensToDisk()
        log.debug("Token stored, expires in ${token.expiresIn}s. Scopes: ${token.scope}")
    }
}
