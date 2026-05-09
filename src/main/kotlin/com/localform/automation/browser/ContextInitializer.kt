package com.localform.automation.browser

import com.localform.automation.behavior.HumanBehaviorSimulator
import com.localform.automation.config.AutomationRuntimeConfig
import com.localform.automation.fingerprint.InitScriptFactory
import com.localform.automation.proxy.ProxySessionManager
import com.microsoft.playwright.Browser
import com.microsoft.playwright.Browser.NewContextOptions
import com.microsoft.playwright.BrowserContext

data class InitializedContext(
    val context: BrowserContext,
    val behaviorSimulator: HumanBehaviorSimulator?
) : AutoCloseable {
    override fun close() {
        context.close()
    }
}

class ContextInitializer(
    private val proxySessionManager: ProxySessionManager? = null,
    private val initScriptFactory: InitScriptFactory = InitScriptFactory()
) {
    fun createContext(
        browser: Browser,
        automation: AutomationRuntimeConfig,
        rowKey: String,
        submissionSource: String = "link"
    ): InitializedContext {
        val rowAutomation = automation.withRowProxy(rowKey)
        val contextOptions = applySubmissionSourceFingerprint(
            BrowserContextOptionsFactory(rowAutomation).createContextOptions(rowAutomation),
            submissionSource
        )
        val context = browser.newContext(contextOptions)

        initScriptFactory.createInitScripts(rowAutomation).forEach { script ->
            context.addInitScript(script)
        }
        addSubmissionSourceInitScripts(context, submissionSource)

        return InitializedContext(
            context = context,
            behaviorSimulator = createBehaviorSimulator(rowAutomation)
        )
    }

    fun addSubmissionSourceInitScripts(context: BrowserContext, source: String) {
        wechatEnvironmentScript(source)?.let { script ->
            context.addInitScript(script)
        }
    }

    fun applySubmissionSourceFingerprint(options: NewContextOptions, source: String): NewContextOptions {
        return when (source) {
            "mobile" -> options
                .setUserAgent("Mozilla/5.0 (iPhone; CPU iPhone OS 18_6_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1")
                .setViewportSize(375, 812)
                .setIsMobile(true)
                .setHasTouch(true)
                .setDeviceScaleFactor(3.0)
            "wechat" -> options
                .setUserAgent("Mozilla/5.0 (iPhone; CPU iPhone OS 18_6_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 MicroMessenger/8.0.69(0x18000692) NetType/WIFI Language/zh_CN")
                .setViewportSize(375, 812)
                .setIsMobile(true)
                .setHasTouch(true)
                .setDeviceScaleFactor(3.0)
            else -> options
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
        }
    }

    private fun wechatEnvironmentScript(source: String): String? {
        if (source != "wechat") {
            return null
        }
        return """
            const wxUserAgent = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_6_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 MicroMessenger/8.0.69(0x18000692) NetType/WIFI Language/zh_CN';
            Object.defineProperty(navigator, 'userAgent', { get: () => wxUserAgent });

            window.WeixinJSBridge = {
                invoke: function(method, params, callback) {
                    if (callback) callback({ err_msg: 'ok' });
                },
                call: function(method, params, callback) {
                    if (callback) callback({ err_msg: 'ok' });
                },
                on: function(event, callback) {
                    if (callback) callback();
                }
            };

            Object.defineProperty(window, '__wxjs_environment', { value: {
                isWeChat: true,
                isIOS: true,
                isAndroid: false,
                version: '8.0.69'
            }, configurable: true });
            Object.defineProperty(window, '__wxjs_is_wechat', { value: true, configurable: true });

            window.wx = window.WeixinJSBridge;
            console.log('%cOK [WeChat Spoof v2] WeChat environment injected', 'color:#07C160;font-weight:bold');
        """.trimIndent()
    }

    private fun AutomationRuntimeConfig.withRowProxy(rowKey: String): AutomationRuntimeConfig {
        val rowProxyProfile = proxySessionManager?.currentProxyProfileForRow(rowKey) ?: return this
        val currentProxyProfile = proxyProfile ?: rowProxyProfile
        return copy(
            proxyProfile = currentProxyProfile.copy(
                server = rowProxyProfile.server,
                servers = emptyList(),
                username = rowProxyProfile.username,
                password = rowProxyProfile.password
            )
        )
    }

    private fun createBehaviorSimulator(automation: AutomationRuntimeConfig): HumanBehaviorSimulator? {
        val behaviorProfile = automation.behaviorProfile ?: return null
        if (!behaviorProfile.enabled) {
            return null
        }
        return HumanBehaviorSimulator(behaviorProfile)
    }
}
