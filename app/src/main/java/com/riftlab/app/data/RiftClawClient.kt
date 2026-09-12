package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for the dedicated RiftClaw companion API, not for generic OpenClaw APIs.
 *
 * RiftLab deliberately speaks a tiny localhost protocol so OpenClaw implementation details and
 * general-purpose agent capabilities are never exposed to the app.
 */
internal object RiftClawClient {
    private const val MAX_BODY_BYTES = 512 * 1024L
    private const val MAX_HITS = 20
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    data class Status(
        val ready: Boolean,
        val protocolVersion: Int,
        val capabilities: Set<String>,
        val detail: String
    )

    suspend fun status(endpoint: String = RiftClawContract.DEFAULT_GATEWAY): Result<Status> =
        withContext(Dispatchers.IO) {
            runCatching {
                requireAllowedEndpoint(endpoint)
                val request = Request.Builder()
                    .url(endpoint.trimEnd('/') + "/v1/status")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "riftclaw_status_http_${response.code}" }
                    val root = readJson(response.body?.contentLength(), response.body?.string().orEmpty())
                    require(root.optString("service") == "riftclaw") { "riftclaw_service_mismatch" }
                    val protocol = root.optInt("protocolVersion", -1)
                    require(protocol == RiftClawContract.PROTOCOL_VERSION) { "riftclaw_protocol_mismatch" }
                    val caps = root.optJSONArray("capabilities").toStringSet()
                    require("weibo_search" in caps) { "riftclaw_weibo_search_missing" }
                    // A dedicated companion may expose health/status internally, but RiftLab-facing
                    // tool capabilities must not grow beyond the explicitly recognized set.
                    val forbidden = caps - setOf("weibo_search")
                    require(forbidden.isEmpty()) { "riftclaw_unexpected_capabilities" }
                    Status(
                        ready = root.optBoolean("ready", false),
                        protocolVersion = protocol,
                        capabilities = caps,
                        detail = root.optString("detail").take(160)
                    )
                }
            }
        }

    suspend fun searchStartingRoster(
        requestData: RiftClawContract.SearchRequest,
        endpoint: String = RiftClawContract.DEFAULT_GATEWAY
    ): Result<RiftClawContract.SearchResponse> = withContext(Dispatchers.IO) {
        runCatching {
            requireAllowedEndpoint(endpoint)
            val validation = RiftClawContract.validate(requestData)
            require(validation is RiftClawContract.Validation.Allow) {
                (validation as? RiftClawContract.Validation.Deny)?.reason ?: "riftclaw_request_invalid"
            }

            val bodyJson = JSONObject()
                .put("protocolVersion", RiftClawContract.PROTOCOL_VERSION)
                .put("requestId", requestData.requestId)
                .put("matchDate", requestData.matchDate)
                .put("league", requestData.league)
                .put("teamA", requestData.teamA)
                .put("teamB", requestData.teamB)
                .put("intent", requestData.intent)

            val httpRequest = Request.Builder()
                .url(endpoint.trimEnd('/') + "/v1/weibo/search")
                .post(bodyJson.toString().toRequestBody(jsonType))
                .header("Accept", "application/json")
                .build()

            http.newCall(httpRequest).execute().use { response ->
                require(response.isSuccessful) { "riftclaw_search_http_${response.code}" }
                val root = readJson(response.body?.contentLength(), response.body?.string().orEmpty())
                require(root.optInt("protocolVersion", -1) == RiftClawContract.PROTOCOL_VERSION) {
                    "riftclaw_protocol_mismatch"
                }
                require(root.optString("requestId") == requestData.requestId) { "riftclaw_request_id_mismatch" }
                require(root.optString("source") == "riftclaw-weibo") { "riftclaw_source_mismatch" }

                val rawHits = root.optJSONArray("hits") ?: JSONArray()
                require(rawHits.length() <= MAX_HITS) { "riftclaw_too_many_hits" }
                val hits = buildList {
                    for (i in 0 until rawHits.length()) {
                        val item = rawHits.optJSONObject(i) ?: continue
                        val hit = RiftClawContract.SearchHit(
                            title = item.optNullableString("title"),
                            text = item.optNullableString("text"),
                            source = item.optNullableString("source"),
                            scheme = item.optNullableString("scheme"),
                            publishedAt = item.optNullableString("publishedAt")
                        )
                        add(RiftClawInjectionGuard.sanitizeHit(hit))
                    }
                }
                RiftClawContract.SearchResponse(
                    requestId = requestData.requestId,
                    hits = hits,
                    source = "riftclaw-weibo",
                    protocolVersion = RiftClawContract.PROTOCOL_VERSION
                )
            }
        }
    }

    private fun requireAllowedEndpoint(endpoint: String) {
        when (val decision = OpenClawSecurityPolicy.validateEndpoint(endpoint)) {
            OpenClawSecurityPolicy.Decision.Allow -> Unit
            is OpenClawSecurityPolicy.Decision.Deny -> error(decision.reason)
        }
    }

    private fun readJson(contentLength: Long?, body: String): JSONObject {
        if (contentLength != null && contentLength > MAX_BODY_BYTES) error("riftclaw_response_too_large")
        if (body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) error("riftclaw_response_too_large")
        return JSONObject(body)
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return buildSet {
            for (i in 0 until length()) {
                val value = optString(i).trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
