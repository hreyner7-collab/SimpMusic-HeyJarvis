package com.maxrave.simpmusic.backup

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess

interface CloudBackupHttpClient {
    suspend fun uploadGitHubGist(
        token: String,
        existingGistId: String?,
        filename: String,
        jsonContent: String,
    ): Result<String>

    suspend fun downloadGitHubGist(
        token: String,
        gistId: String,
        filename: String,
    ): Result<String>

    suspend fun uploadCustomEndpoint(
        url: String,
        authHeader: String?,
        jsonContent: String,
    ): Result<Unit>

    suspend fun downloadCustomEndpoint(
        url: String,
        authHeader: String?,
    ): Result<String>
}

class KtorCloudBackupHttpClient(
    private val githubApiBaseUrl: String = DEFAULT_GITHUB_API_BASE,
) : CloudBackupHttpClient {
    private val httpClient: HttpClient by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 20_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 20_000
            }
        }
    }

    override suspend fun uploadGitHubGist(
        token: String,
        existingGistId: String?,
        filename: String,
        jsonContent: String,
    ): Result<String> =
        runCatching {
            val cleanToken = token.trim()
            require(cleanToken.isNotEmpty()) { "GitHub token is required." }
            val cleanBase = githubApiBaseUrl.trimEnd('/')
            val cleanGistId = existingGistId?.trim()?.takeIf { it.isNotEmpty() }
            val bodyJson =
                CloudBackupProtocol.buildGitHubGistRequestBody(
                    filename = filename,
                    content = jsonContent,
                    isNewGist = cleanGistId == null,
                )
            val response =
                if (cleanGistId == null) {
                    httpClient.post("$cleanBase/gists") {
                        header(HttpHeaders.Authorization, "Bearer $cleanToken")
                        header(HttpHeaders.Accept, "application/vnd.github+json")
                        header("X-GitHub-Api-Version", "2022-11-28")
                        header(HttpHeaders.UserAgent, "SimpMusic-CloudBackup")
                        contentType(ContentType.Application.Json)
                        setBody(bodyJson)
                    }
                } else {
                    httpClient.patch("$cleanBase/gists/$cleanGistId") {
                        header(HttpHeaders.Authorization, "Bearer $cleanToken")
                        header(HttpHeaders.Accept, "application/vnd.github+json")
                        header("X-GitHub-Api-Version", "2022-11-28")
                        header(HttpHeaders.UserAgent, "SimpMusic-CloudBackup")
                        contentType(ContentType.Application.Json)
                        setBody(bodyJson)
                    }
                }
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) {
                error("GitHub Gist upload failed (HTTP ${response.status.value})")
            }
            val parsed = CloudBackupProtocol.parseGitHubGistResponse(text, filename)
            parsed?.gistId?.takeIf { it.isNotBlank() } ?: cleanGistId ?: error("GitHub response missing Gist ID")
        }

    override suspend fun downloadGitHubGist(
        token: String,
        gistId: String,
        filename: String,
    ): Result<String> =
        runCatching {
            val cleanToken = token.trim()
            val cleanGistId = gistId.trim()
            require(cleanGistId.isNotEmpty()) { "GitHub Gist ID is required to restore." }
            val cleanBase = githubApiBaseUrl.trimEnd('/')
            val response =
                httpClient.get("$cleanBase/gists/$cleanGistId") {
                    if (cleanToken.isNotEmpty()) {
                        header(HttpHeaders.Authorization, "Bearer $cleanToken")
                    }
                    header(HttpHeaders.Accept, "application/vnd.github+json")
                    header("X-GitHub-Api-Version", "2022-11-28")
                    header(HttpHeaders.UserAgent, "SimpMusic-CloudBackup")
                }
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) {
                error("GitHub Gist fetch failed (HTTP ${response.status.value})")
            }
            val parsed = CloudBackupProtocol.parseGitHubGistResponse(text, filename)
                ?: error("Invalid GitHub Gist response")
            if (parsed.truncated && !parsed.rawUrl.isNullOrBlank()) {
                val rawResp =
                    httpClient.get(parsed.rawUrl) {
                        if (cleanToken.isNotEmpty()) {
                            header(HttpHeaders.Authorization, "Bearer $cleanToken")
                        }
                        header(HttpHeaders.UserAgent, "SimpMusic-CloudBackup")
                    }
                if (!rawResp.status.isSuccess()) {
                    error("Failed to fetch full Gist backup file (HTTP ${rawResp.status.value})")
                }
                rawResp.bodyAsText()
            } else {
                parsed.content ?: error("Backup file '$filename' not found in Gist")
            }
        }

    override suspend fun uploadCustomEndpoint(
        url: String,
        authHeader: String?,
        jsonContent: String,
    ): Result<Unit> =
        runCatching {
            val cleanUrl = url.trim()
            require(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
                "Cloud endpoint URL must start with http:// or https://"
            }
            val response =
                httpClient.put(cleanUrl) {
                    authHeader?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        header(HttpHeaders.Authorization, it)
                    }
                    header(HttpHeaders.UserAgent, "SimpMusic-CloudBackup")
                    contentType(ContentType.Application.Json)
                    setBody(jsonContent)
                }
            if (!response.status.isSuccess()) {
                error("Cloud backup upload failed (HTTP ${response.status.value})")
            }
        }

    override suspend fun downloadCustomEndpoint(
        url: String,
        authHeader: String?,
    ): Result<String> =
        runCatching {
            val cleanUrl = url.trim()
            require(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
                "Cloud endpoint URL must start with http:// or https://"
            }
            val response =
                httpClient.get(cleanUrl) {
                    authHeader?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        header(HttpHeaders.Authorization, it)
                    }
                    header(HttpHeaders.UserAgent, "SimpMusic-CloudBackup")
                }
            if (!response.status.isSuccess()) {
                error("Cloud backup download failed (HTTP ${response.status.value})")
            }
            response.bodyAsText()
        }

    companion object {
        const val DEFAULT_GITHUB_API_BASE = "https://api.github.com"
    }
}
