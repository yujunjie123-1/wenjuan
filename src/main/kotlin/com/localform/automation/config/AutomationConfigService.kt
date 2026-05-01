package com.localform.automation.config

import com.localform.AppPaths
import com.localform.StartTaskRequest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

data class AutomationRuntimeConfig(
    val automationProfile: AutomationProfile? = null,
    val proxyProfile: ProxyProfile? = null,
    val fingerprintProfile: FingerprintProfile? = null,
    val behaviorProfile: BehaviorProfile? = null,
    val captchaProfile: CaptchaProfile? = null
) {
    val enabled: Boolean
        get() = automationProfile != null ||
            proxyProfile != null ||
            fingerprintProfile != null ||
            behaviorProfile != null ||
            captchaProfile != null
}

class AutomationConfigService(
    private val paths: AppPaths
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun load(): AutomationProfilesConfig {
        val file = paths.automationProfilesFile
        if (!file.exists() || file.length() == 0L) {
            return AutomationProfilesConfig()
        }

        val content = file.readText(Charsets.UTF_8).trim()
        if (content.isEmpty()) {
            return AutomationProfilesConfig()
        }

        return json.decodeFromString<AutomationProfilesConfig>(content).also { config ->
            validateUniqueIds("automationProfiles", config.automationProfiles.map { it.id })
            validateUniqueIds("proxyProfiles", config.proxyProfiles.map { it.id })
            validateUniqueIds("fingerprintProfiles", config.fingerprintProfiles.map { it.id })
            validateUniqueIds("behaviorProfiles", config.behaviorProfiles.map { it.id })
            validateUniqueIds("captchaProfiles", config.captchaProfiles.map { it.id })
        }
    }

    fun resolve(request: StartTaskRequest): AutomationRuntimeConfig {
        val config = load()
        val automationProfile = request.automationProfileId
            ?.takeIf { it.isNotBlank() }
            ?.let { profileId ->
                config.automationProfiles.findEnabledProfile(profileId, "automation profile")
            }

        val proxyProfile = resolveProfile(
            explicitId = request.proxyProfileId,
            inheritedId = automationProfile?.proxyProfileId,
            profiles = config.proxyProfiles,
            label = "proxy profile"
        )
        val fingerprintProfile = resolveProfile(
            explicitId = request.fingerprintProfileId,
            inheritedId = automationProfile?.fingerprintProfileId,
            profiles = config.fingerprintProfiles,
            label = "fingerprint profile"
        )
        val behaviorProfile = resolveProfile(
            explicitId = request.behaviorProfileId,
            inheritedId = automationProfile?.behaviorProfileId,
            profiles = config.behaviorProfiles,
            label = "behavior profile"
        )
        val captchaProfile = resolveProfile(
            explicitId = request.captchaProfileId,
            inheritedId = automationProfile?.captchaProfileId,
            profiles = config.captchaProfiles,
            label = "captcha profile"
        )

        return AutomationRuntimeConfig(
            automationProfile = automationProfile,
            proxyProfile = proxyProfile,
            fingerprintProfile = fingerprintProfile,
            behaviorProfile = behaviorProfile,
            captchaProfile = captchaProfile
        )
    }

    private fun validateUniqueIds(groupName: String, ids: List<String>) {
        val duplicates = ids
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys

        require(duplicates.isEmpty()) {
            "Duplicate ids in $groupName: ${duplicates.joinToString(", ")}"
        }
    }

    private fun <T : Any> resolveProfile(
        explicitId: String?,
        inheritedId: String?,
        profiles: List<T>,
        label: String
    ): T? {
        val profileId = explicitId?.takeIf { it.isNotBlank() } ?: inheritedId?.takeIf { it.isNotBlank() }
        return profileId?.let { profiles.findEnabledProfile(it, label) }
    }

    private fun <T : Any> List<T>.findEnabledProfile(profileId: String, label: String): T {
        val profile = firstOrNull { it.profileId() == profileId }
            ?: error("$label not found: $profileId")
        require(profile.profileEnabled()) {
            "$label is disabled: $profileId"
        }
        return profile
    }

    private fun Any.profileId(): String = when (this) {
        is AutomationProfile -> id
        is ProxyProfile -> id
        is FingerprintProfile -> id
        is BehaviorProfile -> id
        is CaptchaProfile -> id
        else -> error("Unsupported profile type: ${this::class.simpleName}")
    }

    private fun Any.profileEnabled(): Boolean = when (this) {
        is AutomationProfile -> enabled
        is ProxyProfile -> enabled
        is FingerprintProfile -> enabled
        is BehaviorProfile -> enabled
        is CaptchaProfile -> enabled
        else -> error("Unsupported profile type: ${this::class.simpleName}")
    }
}
