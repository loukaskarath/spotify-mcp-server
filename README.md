# Spotify MCP Server 🎵

A fully functional [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server for Spotify built in Kotlin. 

This server allows AI agents and LLMs (like Claude, Gemini, etc.) to securely connect to your Spotify account and execute actions such as playing songs, searching for tracks, pausing playback, and managing your queue.

## Features

- **OAuth 2.0 Flow**: Built-in Ktor web server handles the Spotify authentication flow gracefully.
- **SSE Transport**: Communicates with MCP clients over HTTP Server-Sent Events (SSE), making it perfect for remote hosting.
- **Rich Tools**:
  - `spotify_search`: Search for tracks, artists, or albums.
  - `spotify_play`: Play a specific track via URI or search query.
  - `spotify_pause`: Pause playback.
  - `spotify_skip_next` / `spotify_skip_previous`: Control playback queue.
  - `spotify_queue`: Add a track to the queue.
  - `spotify_volume`: Set output volume.
  - `spotify_now_playing`: Get real-time info on the currently playing track.

## Architecture

This project is divided into two transport layers conceptually:
1. **OAuth Handler**: An HTTP server (default port `8080`) that handles the `http://<host>/login` and `/callback` flows to acquire Spotify tokens.
2. **MCP SSE Endpoints**: The exact same HTTP server exposes `/sse` and `/message` endpoints using the Kotlin MCP SDK to accept commands from the remote MCP Client (e.g., your voice app or AI agent).

## Setup & Local Development

### Prerequisites
1. JDK 17 or higher
2. A Spotify Premium Account (required by Spotify API to control playback)
3. A Spotify Developer Application. Go to the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard), create an app, and add `http://127.0.0.1:8080/callback` to the Redirect URIs.

### Running Locally

1. Export your Spotify Developer credentials:
   ```bash
   export SPOTIFY_CLIENT_ID="your_client_id"
   export SPOTIFY_CLIENT_SECRET="your_client_secret"
   ```

2. Run the server:
   ```bash
   ./gradlew run
   ```

3. Open `http://127.0.0.1:8080/login` in your browser to authenticate.

4. Once authenticated, the server is ready to accept MCP connections on `http://127.0.0.1:8080/sse`.

## Cloud Hosting

The server is designed to be hosted remotely! It automatically respects the `PORT` environment variable.

If deploying to a PaaS (like Render, Railway, or Fly.io):
1. Set the following environment variables on your host:
   - `SPOTIFY_CLIENT_ID`
   - `SPOTIFY_CLIENT_SECRET`
   - `APP_HOST` (e.g., `https://my-spotify-app.onrender.com`. This is required so Spotify knows where to redirect after login).
2. Ensure your Spotify App's Redirect URIs matches your `APP_HOST + "/callback"`.
3. Use `./gradlew build` to generate the fat JAR or run directly.

*(Note: Includes a `Procfile` for easy deployment to buildpack-driven platforms).*
