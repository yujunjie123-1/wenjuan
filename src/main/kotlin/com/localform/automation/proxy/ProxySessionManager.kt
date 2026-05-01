package com.localform.automation.proxy

import com.localform.automation.config.ProxyProfile

class ProxySessionManager(
    private val proxyProfile: ProxyProfile? = null
) {
    fun currentProxyForRow(rowKey: String): String? {
        return null
    }

    fun reportFailure(rowKey: String): String? {
        return null
    }

    fun failureCount(rowKey: String): Int {
        return 0
    }
}
