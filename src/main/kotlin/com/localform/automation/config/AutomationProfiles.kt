package com.localform.automation.config

import kotlinx.serialization.Serializable

@Serializable
data class AutomationProfilesConfig(
    val automationProfiles: List<AutomationProfile> = emptyList(),
    val proxyProfiles: List<ProxyProfile> = emptyList(),
    val fingerprintProfiles: List<FingerprintProfile> = emptyList(),
    val behaviorProfiles: List<BehaviorProfile> = emptyList(),
    val captchaProfiles: List<CaptchaProfile> = emptyList()
)

@Serializable
data class AutomationProfile(
    val id: String,
    val enabled: Boolean = true,
    val description: String? = null,
    val proxyProfileId: String? = null,
    val fingerprintProfileId: String? = null,
    val behaviorProfileId: String? = null,
    val captchaProfileId: String? = null
)

@Serializable
data class ProxyProfile(
    val id: String,
    val enabled: Boolean = true,
    val description: String? = null,
    val server: String? = null,
    val servers: List<String> = emptyList(),
    val username: String? = null,
    val password: String? = null,
    val mode: String = "ROTATING",
    val sticky: Boolean = true,
    val proxiesFile: String? = null
)

@Serializable
data class FingerprintProfile(
    val id: String,
    val enabled: Boolean = true,
    val description: String? = null,
    val userAgent: String? = null,
    val locale: String? = null,
    val timezoneId: String? = null,
    val viewportWidth: Int? = null,
    val viewportHeight: Int? = null,
    val hardwareConcurrency: Int? = null,
    val deviceMemory: Int? = null,
    val screenWidth: Int? = null,
    val screenHeight: Int? = null,
    val hasTouch: Boolean? = false,
    val extraHttpHeaders: Map<String, String>? = null
)

@Serializable
data class BehaviorProfile(
    val id: String,
    val enabled: Boolean = true,
    val description: String? = null,
    // Automation enhancement - optional
    val minDelayMillis: Long? = 800,
    // Automation enhancement - optional
    val maxDelayMillis: Long? = 2200,
    // Automation enhancement - optional
    val typeDelayMin: Long? = 30,
    // Automation enhancement - optional
    val typeDelayMax: Long? = 180,
    // Automation enhancement - optional
    val clickJitterPx: Int? = 3
)

@Serializable
data class CaptchaProfile(
    val id: String,
    val enabled: Boolean = true,
    val description: String? = null,
    val mode: String? = null
)
