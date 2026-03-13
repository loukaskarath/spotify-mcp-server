package com.spotifymcp

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.http.*
import io.ktor.server.sse.*
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Application")

fun main(): Unit = runBlocking {
    val port = Config.serverPort
    val isHeroku = System.getenv("PORT") != null
    // Heroku requires binding to 0.0.0.0, local dev often uses 127.0.0.1
    val host = if (isHeroku) "0.0.0.0" else "127.0.0.1"

    log.info("Starting Spotify MCP Server (SSE mode), listening on $host:$port")
    log.info("Open http://$host:$port/login in your browser to authenticate with Spotify.")

    // Ensure we start the HTTP server and block so it runs continuously
    embeddedServer(Netty, port = port, host = host) {
        install(ContentNegotiation) {
            json(Json { prettyPrint = true; ignoreUnknownKeys = true })
        }
        
        // Required for MCP SSE transport
        install(SSE)

        routing {
            // ─── Health check ─────────────────────────────────────────────────
            get("/health") {
                call.respond(
                    HttpStatusCode.OK,
                    buildJsonObject {
                        put("status", "ok")
                        put("authenticated", SpotifyAuth.isAuthenticated())
                        put("message", if (SpotifyAuth.isAuthenticated())
                            "Spotify is connected."
                        else
                            "Not authenticated. Visit /login to connect Spotify."
                        )
                    }.toString()
                )
            }

            // ─── MCP Endpoint ─────────────────────────────────────────────────
            // This exposes /sse and /message routes automatically.
            mcp {
                buildMcpServer()
            }

            // ─── OAuth: Redirect user to Spotify login ────────────────────────
            get("/login") {
                val authUrl = SpotifyAuth.buildAuthorizationUrl()
                log.info("Redirecting to Spotify auth: $authUrl")
                call.respondRedirect(authUrl)
            }

            // ─── OAuth: Spotify redirects back here with auth code ────────────
            get("/callback") {
                val code = call.request.queryParameters["code"]
                val error = call.request.queryParameters["error"]

                when {
                    error != null -> {
                        log.error("Spotify auth error: $error")
                        call.respondText(
                            """
                            <!DOCTYPE html><html><body style="font-family:sans-serif;padding:40px">
                            <h2>❌ Authentication Failed</h2>
                            <p>Spotify returned an error: <strong>$error</strong></p>
                            <p><a href="/login">Try again</a></p>
                            </body></html>
                            """.trimIndent(),
                            ContentType.Text.Html,
                            HttpStatusCode.BadRequest
                        )
                    }
                    code != null -> {
                        try {
                            SpotifyAuth.exchangeCodeForTokens(code)
                            log.info("Authentication successful!")
                            call.respondText(
                                """
                                <!DOCTYPE html><html><body style="font-family:sans-serif;padding:40px">
                                <h2>✅ Spotify Connected!</h2>
                                <p>Authentication successful. You can close this tab.</p>
                                <p>Your server is authenticated and ready to receive MCP requests via SSE.</p>
                                </body></html>
                                """.trimIndent(),
                                ContentType.Text.Html
                            )
                        } catch (e: Exception) {
                            log.error("Failed to exchange code for tokens: ${e.message}", e)
                            call.respondText(
                                """
                                <!DOCTYPE html><html><body style="font-family:sans-serif;padding:40px">
                                <h2>❌ Token Exchange Failed</h2>
                                <p>${e.message}</p>
                                <p><a href="/login">Try again</a></p>
                                </body></html>
                                """.trimIndent(),
                                ContentType.Text.Html,
                                HttpStatusCode.InternalServerError
                            )
                        }
                    }
                    else -> {
                        call.respondText("Missing 'code' parameter.", status = HttpStatusCode.BadRequest)
                    }
                }
            }
        }
    }.start(wait = true)
}
