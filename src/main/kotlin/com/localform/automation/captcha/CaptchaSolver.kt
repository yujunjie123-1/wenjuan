package com.localform.automation.captcha

import com.localform.automation.config.AutomationRuntimeConfig
import com.microsoft.playwright.Page
import java.util.Locale

interface CaptchaSolver {
    fun solve(page: Page, taskId: String, rowNumber: Int): Boolean
    fun isCaptchaPresent(page: Page): Boolean
}

class ManualCaptchaSolver : CaptchaSolver {
    override fun isCaptchaPresent(page: Page): Boolean {
        val selector = "img[src*='captcha'], #captcha, .captcha, [id*='captcha'], [id*='verify'], [class*='verify']"
        val textSelector = "text=/验证码|验证|Verification|captcha/i"
        return runCatching {
            page.locator(selector).count() > 0 || page.locator(textSelector).count() > 0
        }.getOrDefault(false)
    }

    override fun solve(page: Page, taskId: String, rowNumber: Int): Boolean {
        if (!isCaptchaPresent(page)) {
            return true
        }

        println("Manual captcha required for task $taskId row $rowNumber. Complete it in the browser; waiting up to 5 minutes.")
        return runCatching {
            page.waitForTimeout(300_000.0)
            true
        }.getOrDefault(false)
    }
}

class NoopCaptchaSolver : CaptchaSolver {
    override fun isCaptchaPresent(page: Page): Boolean = false

    override fun solve(page: Page, taskId: String, rowNumber: Int): Boolean = true
}

class CaptchaSolverFactory {
    fun create(automation: AutomationRuntimeConfig): CaptchaSolver {
        val profile = automation.captchaProfile
        if (profile != null && !profile.enabled) {
            return NoopCaptchaSolver()
        }

        return when (profile?.mode?.lowercase(Locale.ROOT)) {
            null, "", "manual" -> ManualCaptchaSolver()
            "none", "disabled", "off" -> NoopCaptchaSolver()
            else -> ManualCaptchaSolver()
        }
    }
}
