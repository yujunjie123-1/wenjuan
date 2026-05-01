package com.localform.automation.browser

import com.localform.automation.behavior.HumanBehaviorSimulator
import com.localform.automation.config.AutomationRuntimeConfig
import com.localform.automation.fingerprint.InitScriptFactory
import com.localform.automation.proxy.ProxySessionManager
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext

data class InitializedContext(
    // Automation enhancement - optional
    val context: BrowserContext,
    // Automation enhancement - optional
    val behaviorSimulator: HumanBehaviorSimulator?
) : AutoCloseable {
    override fun close() {
        // Automation enhancement - optional
        context.close()
    }
}

class ContextInitializer(
    // Automation enhancement - optional
    private val proxySessionManager: ProxySessionManager? = null,
    // Automation enhancement - optional
    private val initScriptFactory: InitScriptFactory = InitScriptFactory()
) {
    fun createContext(
        browser: Browser,
        automation: AutomationRuntimeConfig,
        rowKey: String
    ): InitializedContext {
        // Automation enhancement - optional
        val rowAutomation = automation.withRowProxy(rowKey)
        val contextOptions = BrowserContextOptionsFactory(rowAutomation).createContextOptions(rowAutomation)
        val context = browser.newContext(contextOptions)

        // Automation enhancement - optional
        initScriptFactory.createInitScripts(rowAutomation).forEach { script ->
            context.addInitScript(script)
        }

        // Automation enhancement - optional
        return InitializedContext(
            context = context,
            behaviorSimulator = createBehaviorSimulator(rowAutomation)
        )
    }

    private fun AutomationRuntimeConfig.withRowProxy(rowKey: String): AutomationRuntimeConfig {
        // Automation enhancement - optional
        val proxyServer = proxySessionManager?.currentProxyForRow(rowKey) ?: return this
        val currentProxyProfile = proxyProfile ?: return this
        return copy(
            proxyProfile = currentProxyProfile.copy(
                server = proxyServer,
                servers = emptyList()
            )
        )
    }

    private fun createBehaviorSimulator(automation: AutomationRuntimeConfig): HumanBehaviorSimulator? {
        // Automation enhancement - optional
        val behaviorProfile = automation.behaviorProfile ?: return null
        if (!behaviorProfile.enabled) {
            return null
        }
        return HumanBehaviorSimulator(behaviorProfile)
    }
}
