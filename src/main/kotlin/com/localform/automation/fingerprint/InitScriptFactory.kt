package com.localform.automation.fingerprint

import com.localform.automation.config.AutomationRuntimeConfig
import com.localform.automation.config.FingerprintProfile

class InitScriptFactory {

    fun createInitScripts(automation: AutomationRuntimeConfig): List<String> {
        val profile = automation.fingerprintProfile
        val stealthScripts = if (profile == null || !profile.enabled) {
            listOf(basicStealthScript())
        } else {
            listOf(
                basicStealthScript(),
                canvasFingerprintScript(),
                webglFingerprintScript(),
                audioContextScript(),
                fontsAndHardwareScript(profile),
                webrtcLeakScript(),
                clientHintsScript(profile)
            )
        }

        return stealthScripts + createInternalMarkerScript(automation)
    }

    private fun basicStealthScript(): String = """
        (() => {
            Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
            Object.defineProperty(navigator, 'plugins', { get: () => [1,2,3,4,5].map(() => ({ name: 'Chrome PDF Plugin' })) });
            Object.defineProperty(navigator, 'languages', { get: () => ['zh-CN', 'zh', 'en-US', 'en'] });
            window.chrome = { runtime: {}, app: {}, webstore: {} };
            Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => 8 });
        })();
    """.trimIndent()

    private fun canvasFingerprintScript(): String = """
        (() => {
            const originalGetContext = HTMLCanvasElement.prototype.getContext;
            HTMLCanvasElement.prototype.getContext = function(type, ...args) {
                const ctx = originalGetContext.apply(this, [type, ...args]);
                if (type === '2d' && ctx) {
                    const originalFillText = ctx.fillText;
                    ctx.fillText = function(text, x, y, maxWidth) {
                        arguments[0] = text + ' ';
                        return originalFillText.apply(this, arguments);
                    };
                }
                return ctx;
            };
        })();
    """.trimIndent()

    private fun webglFingerprintScript(): String = """
        (() => {
            const getParameter = WebGLRenderingContext.prototype.getParameter;
            WebGLRenderingContext.prototype.getParameter = function(parameter) {
                if (parameter === 37445) return 'Intel Inc.';
                if (parameter === 37446) return 'Intel(R) UHD Graphics';
                return getParameter.apply(this, arguments);
            };
        })();
    """.trimIndent()

    private fun audioContextScript(): String = """
        (() => {
            const originalGetChannelData = AudioBuffer.prototype.getChannelData;
            AudioBuffer.prototype.getChannelData = function(channel) {
                const data = originalGetChannelData.apply(this, arguments);
                for (let i = 0; i < data.length; i += 100) data[i] += (Math.random() * 0.0001);
                return data;
            };
        })();
    """.trimIndent()

    private fun fontsAndHardwareScript(profile: FingerprintProfile): String {
        val hw = profile.hardwareConcurrency ?: 8
        val deviceMem = profile.deviceMemory ?: 8
        val screenWidth = profile.screenWidth ?: 1920
        val screenHeight = profile.screenHeight ?: 1080
        return """
            (() => {
                Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => $hw });
                Object.defineProperty(navigator, 'deviceMemory', { get: () => $deviceMem });
                Object.defineProperty(screen, 'width', { get: () => $screenWidth });
                Object.defineProperty(screen, 'height', { get: () => $screenHeight });
            })();
        """.trimIndent()
    }

    private fun webrtcLeakScript(): String = """
        (() => {
            const originalRTCPeerConnection = window.RTCPeerConnection;
            if (!originalRTCPeerConnection) return;
            window.RTCPeerConnection = function(...args) {
                const pc = new originalRTCPeerConnection(...args);
                pc.createOffer = function() { return Promise.resolve({ sdp: '', type: 'offer' }); };
                return pc;
            };
        })();
    """.trimIndent()

    private fun clientHintsScript(profile: FingerprintProfile): String {
        val userAgent = jsString(
            profile.userAgent
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        )
        return """
            (() => {
                Object.defineProperty(navigator, 'userAgent', { get: () => $userAgent });
            })();
        """.trimIndent()
    }

    private fun createInternalMarkerScript(automation: AutomationRuntimeConfig): String {
        return """
            (() => {
              const marker = Object.freeze({
                enabled: true,
                automationProfileId: ${jsString(automation.automationProfile?.id)},
                proxyProfileId: ${jsString(automation.proxyProfile?.id)},
                fingerprintProfileId: ${jsString(automation.fingerprintProfile?.id)},
                behaviorProfileId: ${jsString(automation.behaviorProfile?.id)},
                captchaProfileId: ${jsString(automation.captchaProfile?.id)}
              });
              Object.defineProperty(window, "__LOCAL_FORM_AUTOMATION__", {
                value: marker,
                configurable: false,
                enumerable: false,
                writable: false
              });
              console.debug("Automation profile loaded", marker);
            })();
        """.trimIndent()
    }

    private fun jsString(value: String?): String {
        if (value == null) {
            return "null"
        }
        val escaped = buildString {
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\'' -> append("\\'")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
        }
        return "\"$escaped\""
    }
}
