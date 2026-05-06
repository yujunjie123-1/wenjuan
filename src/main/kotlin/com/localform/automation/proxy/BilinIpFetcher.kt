package com.localform.automation.proxy

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class BilinIpFetcher {

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    fun fetchFreshProxy(): String? {
        val apiUrl = System.getenv("BILIN_IP_API_URL")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("bilin.ip.api.url")?.takeIf { it.isNotBlank() }
            ?: return null

        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                return null
            }

            val body = response.body().trim()
            if (body.isBlank() || !body.contains(":")) {
                return null
            }
            body
        } catch (_: Exception) {
            null
        }
    }
}
