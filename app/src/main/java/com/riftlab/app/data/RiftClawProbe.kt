package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Read-only preflight checks for the optional RiftClaw companion.
 *
 * These probes never execute OpenClaw tools. They only verify that a loopback listener exists and,
 * optionally, that a local OpenAI-compatible model endpoint answers /v1/models.
 */
internal object RiftClawProbe {
    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    data class GatewayResult(
        val reachable: Boolean,
        val endpoint: String,
        val detail: String
    )

    data class ModelResult(
        val reachable: Boolean,
        val endpoint: String,
        val modelIds: List<String> = emptyList(),
        val detail: String
    )

    suspend fun probeGateway(endpoint: String = RiftClawContract.DEFAULT_GATEWAY): GatewayResult =
        withContext(Dispatchers.IO) {
            val policy = OpenClawSecurityPolicy.validateEndpoint(endpoint)
            if (policy is OpenClawSecurityPolicy.Decision.Deny) {
                return@withContext GatewayResult(false, endpoint, policy.reason)
            }

            val uri = runCatching { URI(endpoint) }.getOrNull()
                ?: return@withContext GatewayResult(false, endpoint, "gateway_url_invalid")
            val host = uri.host ?: return@withContext GatewayResult(false, endpoint, "gateway_host_missing")
            val port = when {
                uri.port > 0 -> uri.port
                uri.scheme.equals("https", true) || uri.scheme.equals("wss", true) -> 443
                else -> 80
            }

            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 1500)
                }
            }.fold(
                onSuccess = { GatewayResult(true, endpoint, "loopback_listener_reachable") },
                onFailure = { GatewayResult(false, endpoint, it::class.java.simpleName) }
            )
        }

    suspend fun probeOpenAiModel(baseUrl: String): ModelResult = withContext(Dispatchers.IO) {
        val normalized = baseUrl.trim().trimEnd('/')
        val uri = runCatching { URI(normalized) }.getOrNull()
            ?: return@withContext ModelResult(false, baseUrl, detail = "model_url_invalid")
        val host = uri.host?.lowercase().orEmpty()
        if (host !in setOf("127.0.0.1", "localhost", "::1", "[::1]")) {
            return@withContext ModelResult(false, baseUrl, detail = "model_endpoint_must_be_loopback")
        }
        if (uri.scheme?.lowercase() !in setOf("http", "https")) {
            return@withContext ModelResult(false, baseUrl, detail = "model_scheme_not_allowed")
        }

        val modelsUrl = if (normalized.endsWith("/v1")) "$normalized/models" else "$normalized/v1/models"
        val request = Request.Builder().url(modelsUrl).get().build()
        runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use ModelResult(false, normalized, detail = "http_${response.code}")
                }
                val body = response.body?.string().orEmpty()
                val root = JSONObject(body)
                val data = root.optJSONArray("data")
                val ids = buildList {
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val id = data.optJSONObject(i)?.optString("id")?.trim().orEmpty()
                            if (id.isNotEmpty()) add(id.take(160))
                        }
                    }
                }.distinct().take(12)
                ModelResult(true, normalized, ids, if (ids.isEmpty()) "models_endpoint_ok" else "models_found")
            }
        }.getOrElse { ModelResult(false, normalized, detail = it::class.java.simpleName) }
    }
}
