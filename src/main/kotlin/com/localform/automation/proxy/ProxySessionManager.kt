package com.localform.automation.proxy

import com.localform.AppPaths
import com.localform.automation.config.ProxyProfile
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class ProxySessionManager(
    private val proxyProfile: ProxyProfile? = null,
    private val paths: AppPaths? = null
) {
    private val failures = ConcurrentHashMap<String, Int>()
    private val rowProxies = ConcurrentHashMap<String, Proxy>()
    private val pool = createPool()

    fun currentProxyForRow(rowKey: String): String? {
        return currentProxyProfileForRow(rowKey)?.server
    }

    fun currentProxyProfileForRow(rowKey: String): ProxyProfile? {
        val profile = proxyProfile?.takeIf { it.enabled } ?: return null
        val proxy = pool?.getProxyForRow(rowKey)
        if (proxy != null) {
            rowProxies[rowKey] = proxy
            return profile.copy(
                server = proxy.url,
                servers = emptyList(),
                username = proxy.username,
                password = proxy.password
            )
        }

        val server = profile.server?.takeIf { it.isNotBlank() }
            ?: profile.servers.firstOrNull { it.isNotBlank() }
            ?: return null
        return profile.copy(server = server, servers = emptyList())
    }

    fun reportFailure(rowKey: String): String? {
        failures.merge(rowKey, 1, Int::plus)
        rowProxies[rowKey]?.let { proxy -> pool?.markFail(proxy) }
        pool?.rotateNextForRow(rowKey)
        rowProxies.remove(rowKey)
        return currentProxyForRow(rowKey)
    }

    fun reportSuccess(rowKey: String) {
        rowProxies[rowKey]?.let { proxy -> pool?.markSuccess(proxy) }
    }

    fun failureCount(rowKey: String): Int {
        return failures[rowKey] ?: 0
    }

    fun hasProxyPool(): Boolean {
        return pool != null
    }

    private fun createPool(): ProxyPool? {
        val profile = proxyProfile?.takeIf { it.enabled } ?: return null
        val proxiesFile = profile.proxiesFile?.takeIf { it.isNotBlank() } ?: return null
        val file = resolveProxyFile(proxiesFile)
        val mode = runCatching {
            ProxyPool.ProxyMode.valueOf(profile.mode.uppercase(Locale.ROOT))
        }.getOrDefault(ProxyPool.ProxyMode.ROTATING)
        return ProxyPool.fromFile(file, mode, profile.sticky).takeUnless { it.isEmpty() }
    }

    private fun resolveProxyFile(path: String): File {
        val file = File(path)
        if (file.isAbsolute) {
            return file.absoluteFile
        }
        val root = paths?.projectRoot ?: File(System.getProperty("user.dir"))
        return File(root, path).absoluteFile
    }
}
