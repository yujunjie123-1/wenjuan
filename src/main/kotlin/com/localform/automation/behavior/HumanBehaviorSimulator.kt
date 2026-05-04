package com.localform.automation.behavior

import com.localform.automation.config.BehaviorProfile
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import kotlin.random.Random
import kotlin.random.nextLong

class HumanBehaviorSimulator(
    private val profile: BehaviorProfile,
    var speedMultiplier: Double = 1.0
) {

    private val random = Random(System.currentTimeMillis())

    fun scaledDuration(baseMillis: Long, minMillis: Long = 20L): Long {
        return (baseMillis * speedMultiplier).toLong().coerceAtLeast(minMillis)
    }

    fun delay() {
        val minDelay = scaledDuration(profile.minDelayMillis ?: 800L, 30L)
        val maxDelay = scaledDuration(profile.maxDelayMillis ?: 2200L, minDelay + 20L)
        val mean = (minDelay + maxDelay) / 2.0
        val stdDev = ((maxDelay - minDelay) / 4.0).coerceAtLeast(1.0)
        val delayMs = gaussian(mean, stdDev).toLong().coerceIn(minDelay, maxDelay)
        Thread.sleep(delayMs)
    }

    fun typeWithBehavior(locator: Locator, text: String) {
        if (text.isBlank()) {
            return
        }

        locator.click()
        delay()

        if (speedMultiplier < 0.25 && runCatching { locator.fill(text) }.isSuccess) {
            return
        }

        text.forEach { character ->
            locator.press(character.toString())
            val typeDelay = random.nextLong(
                scaledDuration(profile.typeDelayMin ?: 30L, 8L)
                    ..scaledDuration(profile.typeDelayMax ?: 180L, 25L)
            )
            Thread.sleep(typeDelay)
        }
        delay()
    }

    fun clickWithBehavior(locator: Locator) {
        locator.scrollIntoViewIfNeeded()
        delay()

        val jitter = (profile.clickJitterPx ?: 3).coerceAtLeast(0)
        val box = runCatching { locator.boundingBox() }.getOrNull()

        if (box == null || box.width <= 0.0 || box.height <= 0.0 || speedMultiplier < 0.4) {
            locator.click()
            delay()
            return
        }

        val offsetX = random.nextDouble(-jitter.toDouble(), jitter.toDouble())
        val offsetY = random.nextDouble(-jitter.toDouble(), jitter.toDouble())
        val handle = locator.elementHandle() ?: run {
            locator.click()
            delay()
            return
        }

        val script = """
            async (target) => {
                const rect = target.getBoundingClientRect();
                const startX = Math.random() * window.innerWidth;
                const startY = Math.random() * window.innerHeight;
                const endX = rect.left + rect.width / 2 + $offsetX;
                const endY = rect.top + rect.height / 2 + $offsetY;
                for (let i = 0; i <= 12; i++) {
                    const t = i / 12;
                    const x = (1 - t) * (1 - t) * startX + 2 * (1 - t) * t * (startX + (endX - startX) * 0.4) + t * t * endX;
                    const y = (1 - t) * (1 - t) * startY + 2 * (1 - t) * t * (startY + (endY - startY) * 0.6) + t * t * endY;
                    window.dispatchEvent(new MouseEvent('mousemove', { clientX: x, clientY: y, bubbles: true }));
                    await new Promise(resolve => setTimeout(resolve, 8));
                }
                target.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
                await new Promise(resolve => setTimeout(resolve, 12));
                target.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
                target.click();
            }
        """.trimIndent()

        runCatching { locator.page().evaluate(script, handle) }.getOrElse { locator.click() }
        delay()
    }

    fun randomScroll(page: Page) {
        if (speedMultiplier < 0.4) {
            return
        }
        val scrollAmount = random.nextInt(300, 800)
        page.evaluate("window.scrollBy(0, $scrollAmount)")
        delay()
        if (random.nextBoolean()) {
            page.evaluate("window.scrollBy(0, -${random.nextInt(100, 300)})")
            delay()
        }
    }

    private fun gaussian(mean: Double, stdDev: Double): Double {
        var v1: Double
        var v2: Double
        var s: Double
        do {
            v1 = 2 * random.nextDouble() - 1
            v2 = 2 * random.nextDouble() - 1
            s = v1 * v1 + v2 * v2
        } while (s >= 1 || s == 0.0)
        val multiplier = kotlin.math.sqrt(-2.0 * kotlin.math.ln(s) / s)
        return mean + stdDev * v1 * multiplier
    }
}
