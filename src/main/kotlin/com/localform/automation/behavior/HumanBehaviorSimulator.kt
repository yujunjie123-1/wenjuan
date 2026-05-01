package com.localform.automation.behavior

import com.localform.automation.config.BehaviorProfile
import com.microsoft.playwright.Locator
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.max
import kotlin.math.min

class HumanBehaviorSimulator(
    // Automation enhancement - optional
    private val profile: BehaviorProfile
) {
    fun delay() {
        // Automation enhancement - optional
        Thread.sleep(randomDelayMillis(resolveMinDelay(), resolveMaxDelay()))
    }

    fun typeWithBehavior(locator: Locator, text: String) {
        // Automation enhancement - optional
        delay()
        locator.fill("")
        text.forEach { character ->
            // Automation enhancement - optional
            locator.pressSequentially(character.toString())
            Thread.sleep(randomDelayMillis(resolveTypeDelayMin(), resolveTypeDelayMax()))
        }
    }

    fun clickWithBehavior(locator: Locator) {
        // Automation enhancement - optional
        delay()
        val jitter = (profile.clickJitterPx ?: 3).coerceAtLeast(0)
        if (jitter == 0) {
            locator.click()
            return
        }

        val box = runCatching { locator.boundingBox() }.getOrNull()
        if (box == null || box.width <= 0.0 || box.height <= 0.0) {
            // Automation enhancement - optional
            locator.click()
            return
        }

        val centerX = box.width / 2.0
        val centerY = box.height / 2.0
        val offsetX = randomInt(-jitter, jitter).toDouble()
        val offsetY = randomInt(-jitter, jitter).toDouble()
        val x = clamp(centerX + offsetX, 1.0, max(1.0, box.width - 1.0))
        val y = clamp(centerY + offsetY, 1.0, max(1.0, box.height - 1.0))

        // Automation enhancement - optional
        locator.click(
            Locator.ClickOptions()
                .setPosition(x, y)
        )
    }

    private fun resolveMinDelay(): Long {
        // Automation enhancement - optional
        return (profile.minDelayMillis ?: 800L).coerceAtLeast(0L)
    }

    private fun resolveMaxDelay(): Long {
        // Automation enhancement - optional
        return (profile.maxDelayMillis ?: 2200L).coerceAtLeast(resolveMinDelay())
    }

    private fun resolveTypeDelayMin(): Long {
        // Automation enhancement - optional
        return (profile.typeDelayMin ?: 30L).coerceAtLeast(0L)
    }

    private fun resolveTypeDelayMax(): Long {
        // Automation enhancement - optional
        return (profile.typeDelayMax ?: 180L).coerceAtLeast(resolveTypeDelayMin())
    }

    private fun randomDelayMillis(minMillis: Long, maxMillis: Long): Long {
        // Automation enhancement - optional
        if (maxMillis <= minMillis) {
            return minMillis
        }
        return ThreadLocalRandom.current().nextLong(minMillis, maxMillis + 1)
    }

    private fun randomInt(minValue: Int, maxValue: Int): Int {
        // Automation enhancement - optional
        if (maxValue <= minValue) {
            return minValue
        }
        return ThreadLocalRandom.current().nextInt(minValue, maxValue + 1)
    }

    private fun clamp(value: Double, minValue: Double, maxValue: Double): Double {
        // Automation enhancement - optional
        return min(max(value, minValue), maxValue)
    }
}
