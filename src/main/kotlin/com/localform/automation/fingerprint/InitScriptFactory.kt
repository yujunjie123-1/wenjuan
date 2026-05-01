package com.localform.automation.fingerprint

import com.localform.automation.config.AutomationRuntimeConfig

class InitScriptFactory {
    fun createInitScripts(automation: AutomationRuntimeConfig): List<String> {
    if (!automation.enabled || automation.fingerprintProfile == null) {
        // Automation enhancement - optional
        return emptyList()
    }

    val fp = automation.fingerprintProfile
    val profileId = fp.id 

    // === 加强版 Stealth 脚本（用于降低检测概率）===
    val stealthScript = """
        (() => {
            'use strict';
            console.log('%c[Stealth] Anti-detection profile loaded: ${profileId}', 'color: #22c55e; font-weight: bold');

            // 1. 隐藏自动化特征
            Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
            Object.defineProperty(navigator, 'automation', { get: () => undefined });

            // 2. Plugins & MimeTypes 伪装
            Object.defineProperty(navigator, 'plugins', {
                get: () => Object.create(null, {
                    length: { value: 5 },
                    0: { value: { name: 'Chrome PDF Plugin' } },
                    1: { value: { name: 'Chrome PDF Viewer' } }
                })
            });
            Object.defineProperty(navigator, 'mimeTypes', { get: () => [] });

            // 3. Languages 伪装
            Object.defineProperty(navigator, 'languages', { 
                get: () => ${if (fp.locale?.startsWith("zh") == true) "['zh-CN', 'zh', 'en-US', 'en']" else "['en-US', 'en']"} 
            });

            // 4. Canvas 指纹轻度噪声
            const canvasNoiseSeed = "${profileId}".split('').reduce((a, c) => a + c.charCodeAt(0), 0);
            const originalToDataURL = HTMLCanvasElement.prototype.toDataURL;
            HTMLCanvasElement.prototype.toDataURL = function(type, encoderOptions) {
                let result = originalToDataURL.apply(this, [type, encoderOptions]);
                if (result && result.startsWith('data:image/png')) {
                    result = result.substring(0, result.length - 8) + 
                            (canvasNoiseSeed % 10000).toString().padStart(4, '0');
                }
                return result;
            };

            // 5. WebGL 伪装
            const getParameter = WebGLRenderingContext.prototype.getParameter;
            WebGLRenderingContext.prototype.getParameter = function(parameter) {
                if (parameter === 37445) return "Google Inc. (Intel)";
                if (parameter === 37446) return "ANGLE (Intel, Intel(R) UHD Graphics 620, OpenGL ES 3.0)";
                return getParameter.apply(this, arguments);
            };

            // 6. AudioContext 基础伪装
            const originalGetChannelData = AudioBuffer.prototype.getChannelData;
            AudioBuffer.prototype.getChannelData = function(channel) {
                const data = originalGetChannelData.apply(this, [channel]);
                for (let i = 0; i < data.length; i += 100) {
                    data[i] = data[i] + (Math.sin(i + canvasNoiseSeed) * 0.0001);
                }
                return data;
            };

            console.log('%c[Stealth] Core anti-detection scripts injected successfully', 'color: #eab308');
        })();
    """.trimIndent()

    val markerScript = createInternalMarkerScript(automation);

    return listOf(stealthScript, markerScript);
}

    private fun createInternalMarkerScript(automation: AutomationRuntimeConfig): String {
        // Automation enhancement - optional
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
        // Automation enhancement - optional
        if (value == null) {
            return "null"
        }
        val escaped = buildString {
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
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
