package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Device-local Weibo Open API transport.
 *
 * Credentials come exclusively from [ProviderCredentialStore]. They are never bundled in the APK,
 * committed to Git, uploaded to RiftLab servers, or written to diagnostics. This mirrors the public
 * token/search contract used by the MIT-licensed weibo-mcp project while keeping RiftLab's own
 * normalized data model and validation rules.
 */
internal object WeiboOpenApiClient {
    const val TOKEN_ENDPOINT = "https://open-im.api.weibo.com/open/auth/ws_token"
    const val SEARCH_ENDPOINT = "https://open-im.api.weibo.com/open/wis/search_query"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAtMs: Long = 0L
    @Volatile private var tokenCredentialFingerprint: Int = 0

    data class SearchResult(
        val query: String,
        val completed: Boolean,
        val analyzing: Boolean,
        val noContent: Boolean,
        val refused: Boolean,
        val content: String,
        val contentFormat: String,
        val messageJson: String,
        val scheme: String,
        val referenceCount: Int,
        val source: String,
        val callTime: String,
        val version: String
    )

    suspend fun verifyCredentials(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            validToken(forceRefresh = true)
            Unit
        }
    }

    suspend fun search(query: String): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = query.trim()
            require(normalized.isNotEmpty()) { "query is blank" }
            val token = validToken(forceRefresh = false)
            val url = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("open-im.api.weibo.com")
                .addPathSegments("open/wis/search_query")
                .addQueryParameter("query", normalized)
                .addQueryParameter("token", token)
                .build()
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                check(response.isSuccessful) { "search_http_${response.code}" }
                val root = JSONObject(raw)
                check(root.optInt("code", -1) == 0) {
                    root.optString("message").ifBlank { "search_code_${root.optInt("code", -1)}" }
                }
                val data = root.optJSONObject("data") ?: error("search_missing_data")
                SearchResult(
                    query = normalized,
                    completed = data.optBoolean("completed", false),
                    analyzing = data.optBoolean("analyzing", false),
                    noContent = data.optBoolean("noContent", false),
                    refused = data.optBoolean("refused", false),
                    content = data.optString("msg"),
                    contentFormat = data.optString("msg_format"),
                    messageJson = data.optString("msg_json"),
                    scheme = data.optString("scheme"),
                    referenceCount = data.optInt("reference_num", 0),
                    source = data.optString("source"),
                    callTime = data.optString("callTime"),
                    version = data.optString("version")
                )
            }
        }
    }

    fun invalidateToken() {
        cachedToken = null
        tokenExpiresAtMs = 0L
        tokenCredentialFingerprint = 0
    }

    private fun validToken(forceRefresh: Boolean): String {
        val appId = ProviderCredentialStore.readWeiboAppId() ?: error("weibo_app_id_missing")
        val appSecret = ProviderCredentialStore.readWeiboAppSecret() ?: error("weibo_app_secret_missing")
        val fingerprint = 31 * appId.hashCode() + appSecret.hashCode()
        val now = System.currentTimeMillis()
        val cached = cachedToken
        if (!forceRefresh && !cached.isNullOrBlank() && fingerprint == tokenCredentialFingerprint && now < tokenExpiresAtMs) {
            return cached
        }

        val json = JSONObject()
            .put("app_id", appId)
            .put("app_secret", appSecret)
            .toString()
        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            check(response.isSuccessful) { "token_http_${response.code}" }
            val root = JSONObject(raw)
            val data = root.optJSONObject("data") ?: error("token_missing_data")
            val token = data.optString("token").trim()
            check(token.isNotEmpty()) { "token_missing_value" }
            val expiresInSeconds = data.optLong("expire_in", 3600L).coerceAtLeast(120L)
            cachedToken = token
            tokenCredentialFingerprint = fingerprint
            tokenExpiresAtMs = now + expiresInSeconds * 1000L - 60_000L
            return token
        }
    }
}
