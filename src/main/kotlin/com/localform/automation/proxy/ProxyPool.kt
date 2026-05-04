package com.localform.automation.proxy

import com.microsoft.playwright.Page
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class Proxy(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    var successCount: Int = 0,
    var failCount: Int = 0
) {
    val successRate: Double
        get() = if (successCount + failCount == 0) 1.0 else successCount.toDouble() / (successCount + failCount)
    val url: String get() = "http://$host:$port"
}

class ProxyPool(
    private val proxies: List<Proxy>,
    private val mode: ProxyMode = ProxyMode.ROTATING,
    private val sticky: Boolean = true
) {
    enum class ProxyMode { RANDOM, ROTATING, SMART }

    private val proxyStats = ConcurrentHashMap<Proxy, Proxy>()
    private val sessionProxy = ConcurrentHashMap<String, Proxy>()
    private var currentIndex = 0

    init {
        proxies.forEach { proxyStats[it] = it }
    }

    fun isEmpty(): Boolean = proxies.isEmpty()

    @Synchronized
    fun getProxyForRow(rowKey: String): Proxy? {
        if (proxies.isEmpty()) {
            return null
        }
        if (sticky) {
            sessionProxy[rowKey]?.let { return it }
        }

        val proxy = when (mode) {
            ProxyMode.RANDOM -> proxies.random()
            ProxyMode.ROTATING -> proxies[currentIndex.also { currentIndex = (currentIndex + 1) % proxies.size }]
            ProxyMode.SMART -> proxyStats.values.maxByOrNull { it.successRate } ?: proxies.random()
        }
        if (sticky) {
            sessionProxy[rowKey] = proxy
        }
        return proxy
    }

    fun markSuccess(proxy: Proxy) {
        proxyStats[proxy]?.let { it.successCount += 1 }
    }

    fun markFail(proxy: Proxy) {
        proxyStats[proxy]?.let { it.failCount += 1 }
    }

    fun rotateNextForRow(rowKey: String) {
        sessionProxy.remove(rowKey)
    }

    companion object {
        fun fromFile(file: File, mode: ProxyMode = ProxyMode.ROTATING, sticky: Boolean = true): ProxyPool {
            val proxies = if (file.exists()) {
                file.readLines(Charsets.UTF_8).mapNotNull { line -> parseLine(line) }
            } else {
                emptyList()
            }
            return ProxyPool(proxies, mode, sticky)
        }

        fun parseLine(line: String): Proxy? {
            val value = line.trim()
            if (value.isBlank() || value.startsWith("#")) {
                return null
            }

            val withoutScheme = value.substringAfter("://", value)
            val parts = withoutScheme.split(":")
            if (parts.size < 2) {
                return null
            }
            val port = parts[1].toIntOrNull() ?: return null
            return Proxy(
                host = parts[0],
                port = port,
                username = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
                password = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
            )
        }
    }
}

fun Page.getCurrentIp(timeoutMillis: Double = 3_000.0): String {
    return try {
        navigate("https://api.ipify.org?format=json", Page.NavigateOptions().setTimeout(timeoutMillis))
        val response = textContent("body").orEmpty()
        response.substringAfter("\"ip\":\"", missingDelimiterValue = "").substringBefore("\"").ifBlank {
            "IP fetch failed"
        }
    } catch (error: Throwable) {
        "IP fetch failed"
    }
}
