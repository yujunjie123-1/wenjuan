package com.localform.automation.browser

import com.localform.automation.config.AutomationRuntimeConfig
import com.microsoft.playwright.Browser
import com.microsoft.playwright.options.Proxy

class BrowserContextOptionsFactory(
    // Automation enhancement - optional
    private val defaultAutomation: AutomationRuntimeConfig
) {
    fun createContextOptions(automation: AutomationRuntimeConfig = defaultAutomation): Browser.NewContextOptions {
        val options = Browser.NewContextOptions()

        if (!automation.enabled) {
            // Automation enhancement - optional
            // No automation profiles are active, so return an empty options object and avoid changing behavior.
            return options
        }

        val fingerprintProfile = automation.fingerprintProfile
        if (fingerprintProfile != null) {
            // Automation enhancement - optional
            fingerprintProfile.userAgent
                ?.takeIf { it.isNotBlank() }
                ?.let { options.setUserAgent(it) }

            // Automation enhancement - optional
            fingerprintProfile.locale
                ?.takeIf { it.isNotBlank() }
                ?.let { options.setLocale(it) }

            // Automation enhancement - optional
            fingerprintProfile.timezoneId
                ?.takeIf { it.isNotBlank() }
                ?.let { options.setTimezoneId(it) }

            val viewportWidth = fingerprintProfile.viewportWidth?.takeIf { it > 0 }
            val viewportHeight = fingerprintProfile.viewportHeight?.takeIf { it > 0 }
            if (viewportWidth != null && viewportHeight != null) {
                // Automation enhancement - optional
                options.setViewportSize(viewportWidth, viewportHeight)
            }

            val extraHttpHeaders = fingerprintProfile.extraHttpHeaders
                .orEmpty()
                .filterKeys { it.isNotBlank() }
                .filterValues { it.isNotBlank() }
            if (extraHttpHeaders.isNotEmpty()) {
                // Automation enhancement - optional
                options.setExtraHTTPHeaders(extraHttpHeaders)
            }
        }

        val proxyProfile = automation.proxyProfile
        val proxyServer = proxyProfile?.server?.takeIf { it.isNotBlank() }
            ?: proxyProfile?.servers.orEmpty().firstOrNull { it.isNotBlank() }
        if (proxyServer != null) {
            // Automation enhancement - optional
            val proxy = Proxy(proxyServer)
            proxyProfile?.username
                ?.takeIf { it.isNotBlank() }
                ?.let { proxy.setUsername(it) }
            proxyProfile?.password
                ?.takeIf { it.isNotBlank() }
                ?.let { proxy.setPassword(it) }
            options.setProxy(proxy)
        }

        return options
    }
}
