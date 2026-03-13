package com.spotifymcp

object Config {
    val clientId: String = System.getenv("SPOTIFY_CLIENT_ID")
        ?: error("SPOTIFY_CLIENT_ID environment variable not set")

    val clientSecret: String = System.getenv("SPOTIFY_CLIENT_SECRET")
        ?: error("SPOTIFY_CLIENT_SECRET environment variable not set")

    // Heroku provides the port via the 'PORT' env variable.
    val serverPort: Int = System.getenv("PORT")?.toIntOrNull() 
        ?: System.getenv("SERVER_PORT")?.toIntOrNull() 
        ?: 8080

    val appHost: String = System.getenv("APP_HOST") ?: "https://localhost:$serverPort"
    val redirectUri: String = "$appHost/callback"
}
