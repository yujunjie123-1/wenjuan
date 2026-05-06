package com.localform

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitUntilState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.random.Random

private enum class HighAlphaQuestionKind {
    LIKERT,
    SINGLE_CHOICE
}

private data class HighAlphaQuestion(
    val id: String,
    val title: String,
    val root: Locator,
    val optionCount: Int,
    val kind: HighAlphaQuestionKind
)

class OneClickHighAlphaFiller(
    private val taskStore: TaskStore
) {
    private companion object {
        const val NAVIGATION_TIMEOUT_MILLIS = 30_000.0
        const val NETWORK_IDLE_TIMEOUT_MILLIS = 8_000.0
        const val SUBMIT_COMPLETION_TIMEOUT_MILLIS = 12_000.0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val optionCounters = ConcurrentHashMap<Int, AtomicInteger>()

    fun start(taskId: String, request: HighAlphaTaskRequest) {
        scope.launch {
            runBatch(taskId, request)
        }
    }

    private suspend fun runBatch(taskId: String, request: HighAlphaTaskRequest) {
        taskStore.markRunning(taskId)
        try {
            taskStore.append(
                taskId,
                "info",
                "High-alpha batch started. Count: ${request.count}, duration ${request.fillDurationMinSeconds ?: 100}-${request.fillDurationMaxSeconds ?: 200}s."
            )

            Playwright.create().use { playwright ->
                launchBrowser(playwright).use browserUse@{ browser ->
                    repeat(request.count) { index ->
                        if (taskStore.isCancelled(taskId)) {
                            taskStore.append(taskId, "warn", "Task cancelled before row ${index + 1}.")
                            return@browserUse
                        }
                        val rowNumber = index + 1
                        val success = fillOneInstance(taskId, browser, request, rowNumber)
                        if (success) {
                            taskStore.recordSuccess(taskId)
                        } else {
                            taskStore.recordFailure(taskId)
                        }
                    }
                }
            }

            if (!taskStore.isCancelled(taskId)) {
                taskStore.markCompleted(taskId)
                taskStore.append(taskId, "info", "High-alpha batch completed.")
            }
        } catch (error: Throwable) {
            taskStore.markFailed(taskId, error.message ?: error::class.simpleName.orEmpty())
        }
    }

    private suspend fun fillOneInstance(
        taskId: String,
        browser: Browser,
        request: HighAlphaTaskRequest,
        rowNumber: Int
    ): Boolean {
        val context = browser.newContext(
            Browser.NewContextOptions()
                .setViewportSize(1440, 900)
        )
        return try {
            val page = context.newPage()
            taskStore.append(taskId, "info", "High-alpha row $rowNumber started.", rowNumber)
            navigateToQuestionnaire(page, request.questionnaireUrl, taskId, rowNumber)

            val startedAtMillis = System.currentTimeMillis()
            val questions = extractQuestionCandidates(page)
            val likertQuestions = questions.filter { it.kind == HighAlphaQuestionKind.LIKERT }
            val singleChoiceQuestions = questions.filter { it.kind == HighAlphaQuestionKind.SINGLE_CHOICE }
            if (likertQuestions.isEmpty() && singleChoiceQuestions.isEmpty()) {
                throw IllegalStateException("No supported single-choice or likert questions were detected on the questionnaire page.")
            }

            logDetectedQuestions(taskId, rowNumber, questions, likertQuestions.size, singleChoiceQuestions.size)
            handleScreeningQuestions(page, rowNumber, taskId)
            fillLikertQuestions(likertQuestions)
            fillSingleChoiceQuestions(singleChoiceQuestions.filterNot { it.id == "1" || it.id == "div1" })
            fillDemographicsRandomly(page)

            val targetDurationMillis = targetCompletionDurationMillis(request)
            waitUntilTargetDuration(startedAtMillis, targetDurationMillis)

            if (request.submitEnabled) {
                submitAndHandleLottery(page, taskId, rowNumber)
                taskStore.append(taskId, "info", "High-alpha row $rowNumber submitted.", rowNumber)
            } else {
                taskStore.append(taskId, "info", "High-alpha row $rowNumber filled without submit.", rowNumber)
            }
            true
        } catch (error: Throwable) {
            taskStore.append(
                taskId,
                "error",
                "High-alpha row $rowNumber failed: ${error.message ?: error::class.simpleName.orEmpty()}",
                rowNumber
            )
            false
        } finally {
            runCatching { context.close() }
        }
    }

    private fun navigateToQuestionnaire(page: Page, url: String, taskId: String, rowNumber: Int) {
        var navigateSuccess = false
        for (attempt in 1..2) {
            try {
                page.navigate(
                    url,
                    Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(NAVIGATION_TIMEOUT_MILLIS)
                )
                navigateSuccess = true
                break
            } catch (error: Exception) {
                taskStore.append(
                    taskId,
                    "warn",
                    "High-alpha row $rowNumber: Navigate attempt $attempt failed: ${error.message?.take(100) ?: error::class.simpleName.orEmpty()}",
                    rowNumber
                )
                if (attempt < 2) {
                    page.waitForTimeout(2_000.0)
                }
            }
        }
        if (!navigateSuccess) {
            throw RuntimeException("Navigate failed after retries.")
        }
        runCatching {
            page.waitForLoadState(
                LoadState.NETWORKIDLE,
                Page.WaitForLoadStateOptions().setTimeout(NETWORK_IDLE_TIMEOUT_MILLIS)
            )
        }.onFailure { error ->
            taskStore.append(
                taskId,
                "debug",
                "High-alpha row $rowNumber: NETWORKIDLE wait skipped: ${error.message ?: error::class.simpleName.orEmpty()}",
                rowNumber
            )
        }
    }

    private fun launchBrowser(playwright: Playwright): Browser {
        val options = BrowserType.LaunchOptions()
            .setHeadless(false)
            .setSlowMo(60.0)
        return playwright.chromium().launch(options)
    }

    private fun extractQuestionCandidates(page: Page): List<HighAlphaQuestion> {
        val wjxQuestions = extractWjxQuestionCandidates(page)
        if (wjxQuestions.isNotEmpty()) {
            return wjxQuestions
        }
        return extractGenericQuestionCandidates(page)
    }

    private fun extractWjxQuestionCandidates(page: Page): List<HighAlphaQuestion> {
        val candidates = page.locator(".field.ui-field-contain[topic], div[topic][type]")
        val questions = mutableListOf<HighAlphaQuestion>()
        val seen = mutableSetOf<String>()
        val count = candidates.count()
        for (index in 0 until count) {
            val root = candidates.nth(index)
            if (!runCatching { root.isVisible }.getOrDefault(false)) {
                continue
            }
            val topic = root.getAttribute("topic")?.trim().orEmpty()
            val id = topic.ifBlank { root.getAttribute("id") ?: "wjx_${index + 1}" }
            if (!seen.add(id)) {
                continue
            }
            val type = root.getAttribute("type")?.trim().orEmpty()
            val title = questionTitle(root, id)
            when (type) {
                "5" -> addWjxLikertQuestion(questions, id, title, root)
                "6" -> questions += extractMatrixLikertQuestions(root, id, title)
                "3" -> addChoiceQuestion(questions, id, title, root, HighAlphaQuestionKind.SINGLE_CHOICE)
                else -> addUnknownWjxQuestion(questions, id, title, root)
            }
        }
        return questions
    }

    private fun addWjxLikertQuestion(
        questions: MutableList<HighAlphaQuestion>,
        id: String,
        title: String,
        root: Locator
    ) {
        addChoiceQuestion(questions, id, title, root, HighAlphaQuestionKind.LIKERT)
    }

    private fun addChoiceQuestion(
        questions: MutableList<HighAlphaQuestion>,
        id: String,
        title: String,
        root: Locator,
        kind: HighAlphaQuestionKind
    ) {
        val optionCount = clickableOptionCount(root)
        if (optionCount >= 2) {
            questions += HighAlphaQuestion(
                id = id,
                title = title,
                root = root,
                optionCount = optionCount,
                kind = kind
            )
        }
    }

    private fun addUnknownWjxQuestion(
        questions: MutableList<HighAlphaQuestion>,
        id: String,
        title: String,
        root: Locator
    ) {
        val optionCount = clickableOptionCount(root)
        if (optionCount < 2) {
            return
        }
        val allText = root.textContent().orEmpty()
        val kind = if ((optionCount == 5 && !hasLetterLabels(allText)) || isLikertScale(allText)) {
            HighAlphaQuestionKind.LIKERT
        } else {
            HighAlphaQuestionKind.SINGLE_CHOICE
        }
        questions += HighAlphaQuestion(
            id = id,
            title = title,
            root = root,
            optionCount = optionCount,
            kind = kind
        )
    }

    private fun extractMatrixLikertQuestions(root: Locator, questionId: String, questionTitle: String): List<HighAlphaQuestion> {
        val rowSelectors = listOf("tr", ".matrix-row", ".matrixRow", ".row", "li")
        val rows = mutableListOf<HighAlphaQuestion>()
        rowSelectors.forEach { selector ->
            val rowCandidates = root.locator(selector)
            val rowCount = rowCandidates.count()
            for (index in 0 until rowCount) {
                val row = rowCandidates.nth(index)
                if (!runCatching { row.isVisible }.getOrDefault(false)) {
                    continue
                }
                val optionCount = clickableOptionCount(row)
                if (optionCount < 2) {
                    continue
                }
                val rowTitle = row.textContent().orEmpty().lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
                rows += HighAlphaQuestion(
                    id = "$questionId-${rows.size + 1}",
                    title = listOf(questionTitle, rowTitle).filter { it.isNotBlank() }.joinToString(" - ").take(120),
                    root = row,
                    optionCount = optionCount,
                    kind = HighAlphaQuestionKind.LIKERT
                )
            }
            if (rows.isNotEmpty()) {
                return rows
            }
        }
        val fallbackOptionCount = clickableOptionCount(root)
        return if (fallbackOptionCount >= 2) {
            listOf(
                HighAlphaQuestion(
                    id = questionId,
                    title = questionTitle,
                    root = root,
                    optionCount = fallbackOptionCount,
                    kind = HighAlphaQuestionKind.LIKERT
                )
            )
        } else {
            emptyList()
        }
    }

    private fun extractGenericQuestionCandidates(page: Page): List<HighAlphaQuestion> {
        val candidates = page.locator(
            ".field, .question, .question-wrapper, .div_question, [class*='question'], div[data-qid], .matrix-row, .scale-question, .choice-question, .radio-group"
        )
        val questions = mutableListOf<HighAlphaQuestion>()
        val count = candidates.count()
        for (index in 0 until count) {
            val root = candidates.nth(index)
            if (!runCatching { root.isVisible }.getOrDefault(false)) {
                continue
            }
            val optionCount = clickableOptionCount(root)
            if (optionCount < 2) {
                continue
            }
            val allText = root.textContent().orEmpty()
            val kind = if ((optionCount == 5 && !hasLetterLabels(allText)) || isLikertScale(allText)) {
                HighAlphaQuestionKind.LIKERT
            } else {
                HighAlphaQuestionKind.SINGLE_CHOICE
            }
            val id = root.getAttribute("data-id")
                ?: root.getAttribute("data-qid")
                ?: root.getAttribute("topic")
                ?: root.getAttribute("id")
                ?: "question_${index + 1}"
            questions += HighAlphaQuestion(
                id = id,
                title = questionTitle(root, id),
                root = root,
                optionCount = optionCount,
                kind = kind
            )
        }
        return questions
    }

    private fun questionTitle(root: Locator, fallback: String): String {
        val titleSelectors = listOf(".topichtml", ".field-label", ".question-title", ".title")
        titleSelectors.forEach { selector ->
            val locator = root.locator(selector).first()
            if (locator.count() > 0) {
                val text = runCatching { locator.textContent() }.getOrDefault("")
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != "*" }
                    .joinToString(" ")
                    .trim()
                if (text.isNotBlank()) {
                    return text
                }
            }
        }
        return root.textContent().orEmpty().lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && it != "*" }
            ?.ifBlank { fallback }
            ?: fallback
    }

    private fun clickableOptionCount(root: Locator): Int {
        val selectors = listOf(
            "a[val]",
            ".scale-rating a",
            ".scale-div a",
            "input[type='radio']",
            ".ui-radio",
            ".label[for]",
            "label",
            ".radio-item",
            ".choice-item",
            "[role='radio']"
        )
        for (selector in selectors) {
            val count = root.locator(selector).count()
            if (count >= 2) {
                return count
            }
        }
        return 0
    }

    private fun isLikertScale(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower.contains("非常不同意") ||
            lower.contains("不同意") ||
            lower.contains("同意") ||
            lower.contains("非常同意") ||
            lower.contains("非常满意") ||
            lower.contains("满意")
    }

    private fun hasLetterLabels(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower.contains("a.") ||
            lower.contains("b.") ||
            lower.contains("c.") ||
            lower.contains("d.") ||
            lower.contains("e.") ||
            lower.contains("f.")
    }

    private fun logDetectedQuestions(
        taskId: String,
        rowNumber: Int,
        questions: List<HighAlphaQuestion>,
        likertCount: Int,
        singleChoiceCount: Int
    ) {
        questions.forEach { question ->
            val label = if (question.kind == HighAlphaQuestionKind.LIKERT) "Likert" else "Single-choice"
            taskStore.append(
                taskId,
                "debug",
                "$label detected (${question.optionCount} options): ${question.title.take(60)}...",
                rowNumber
            )
        }
        taskStore.append(
            taskId,
            "debug",
            "High-alpha row $rowNumber detected $likertCount likert questions and $singleChoiceCount single-choice questions.",
            rowNumber
        )
    }

    private suspend fun fillLikertQuestions(questions: List<HighAlphaQuestion>) {
        questions.chunked(4).forEach { dimension ->
            val latentScore = Random.nextDouble(3.7, 4.8)
            dimension.forEach { question ->
                val answer = (latentScore + Random.nextDouble(-0.55, 0.55))
                    .coerceIn(1.0, question.optionCount.toDouble())
                    .roundToInt()
                selectNthOption(question.root, answer)
                delay(Random.nextLong(120L, 360L))
            }
        }
    }

    private suspend fun fillSingleChoiceQuestions(questions: List<HighAlphaQuestion>) {
        questions.forEach { question ->
            val chosen = getEvenlyDistributedOption(question.optionCount)
            selectNthOption(question.root, chosen)
            delay(Random.nextLong(120L, 320L))
        }
    }

    private fun getEvenlyDistributedOption(optionCount: Int): Int {
        val counter = optionCounters.computeIfAbsent(optionCount) { AtomicInteger(0) }
        return (counter.getAndIncrement() % optionCount) + 1
    }

    private fun handleScreeningQuestions(page: Page, rowNumber: Int, taskId: String) {
        val potentialScreenings = page.locator(
            ".field.ui-field-contain[topic='1'], #div1, .field.ui-field-contain[topic]"
        )
        val count = potentialScreenings.count()
        for (index in 0 until count) {
            val question = potentialScreenings.nth(index)
            val text = question.textContent().orEmpty().trim()
            if (!text.contains("是否") && !text.contains("是/否") && !text.contains("是否使用")) {
                continue
            }
            taskStore.append(
                taskId,
                "debug",
                "Screening detected: ${text.take(30)}... selecting a positive/first option.",
                rowNumber
            )
            val options = question.locator(".label[for], label, .option, .choice-item, .radio-item, .ui-radio, input[type='radio']")
            val optionCount = options.count()
            for (optionIndex in 0 until optionCount) {
                val optionText = options.nth(optionIndex).textContent().orEmpty()
                if (optionText.contains("是") || optionText.contains("A.", ignoreCase = true)) {
                    selectNthOption(question, optionIndex + 1)
                    return
                }
            }
            selectNthOption(question, 1)
            return
        }
    }

    private fun selectNthOption(root: Locator, n: Int) {
        val oneBased = n.coerceAtLeast(1)
        val zeroBased = oneBased - 1

        runCatching {
            val exactScaleOption = root.locator("a[val='$oneBased']").first()
            if (exactScaleOption.count() > 0) {
                exactScaleOption.scrollIntoViewIfNeeded()
                exactScaleOption.click(Locator.ClickOptions().setForce(true).setTimeout(5_000.0))
                return
            }
        }

        val radios = root.locator("input[type='radio']")
        val radioCount = radios.count()
        if (radioCount > zeroBased) {
            val radio = radios.nth(zeroBased)
            runCatching {
                radio.check(Locator.CheckOptions().setForce(true).setTimeout(5_000.0))
            }.onSuccess { return }
            runCatching {
                radio.evaluate("element => element.click()")
            }.onSuccess { return }
        }

        val selectors = listOf(
            "a[val]",
            ".scale-rating a",
            ".scale-div a",
            ".ui-radio",
            ".label[for]",
            "label",
            ".choice-item",
            ".radio-item",
            "[role='radio']"
        )
        for (selector in selectors) {
            if (clickNthVisibleOption(root, selector, zeroBased)) {
                return
            }
        }

        throw IllegalStateException("Failed to select option $oneBased.")
    }

    private fun clickNthVisibleOption(root: Locator, selector: String, zeroBased: Int): Boolean {
        val options = root.locator(selector)
        val count = options.count()
        var visibleIndex = 0
        for (index in 0 until count) {
            val option = options.nth(index)
            if (!runCatching { option.isVisible }.getOrDefault(false)) {
                continue
            }
            if (visibleIndex == zeroBased) {
                return runCatching {
                    option.scrollIntoViewIfNeeded()
                    option.click(Locator.ClickOptions().setForce(true).setTimeout(5_000.0))
                }.recoverCatching {
                    option.evaluate("element => element.click()")
                }.isSuccess
            }
            visibleIndex += 1
        }
        return false
    }

    private suspend fun fillDemographicsRandomly(page: Page) {
        val textInputs = page.locator("input[type='text'], textarea")
        val count = textInputs.count()
        for (index in 0 until count) {
            val input = textInputs.nth(index)
            if (!input.isVisible) {
                continue
            }
            runCatching {
                input.fill(Random.nextInt(1, 5).toString())
                delay(Random.nextLong(80L, 220L))
            }
        }
    }

    private fun submitAndHandleLottery(page: Page, taskId: String, rowNumber: Int) {
        val submitCandidates = listOf(
            page.locator("#ctlNext").first(),
            page.locator("#divSubmit").first(),
            page.locator(".submitbtn").first(),
            page.locator("input[type='submit']").first(),
            page.locator("button[type='submit']").first()
        )
        val clicked = submitCandidates.firstOrNull { it.count() > 0 && runCatching { it.isVisible }.getOrDefault(false) }
            ?.let { submit ->
                runCatching {
                    submit.scrollIntoViewIfNeeded()
                    submit.click(Locator.ClickOptions().setForce(true).setTimeout(5_000.0))
                }.isSuccess
            }
            ?: false
        if (!clicked) {
            throw IllegalStateException("Submit button was not found or could not be clicked.")
        }
        runCatching {
            page.waitForFunction(
                """
                () => {
                    const url = window.location.href || "";
                    const text = document.body?.innerText || document.documentElement?.innerText || "";
                    return url.includes("completemobile") ||
                        url.includes("/wjx/join/complete") ||
                        url.includes("/join/complete") ||
                        text.includes("您的答卷已经提交") ||
                        text.includes("感谢您的参与") ||
                        text.includes("恭喜您获得了") ||
                        text.includes("立即抽奖") ||
                        text.includes("抽奖机会");
                }
                """.trimIndent(),
                null,
                Page.WaitForFunctionOptions().setTimeout(SUBMIT_COMPLETION_TIMEOUT_MILLIS)
            )
        }.onFailure { error ->
            taskStore.append(
                taskId,
                "warn",
                "High-alpha row $rowNumber: Submit completion signal was not detected: ${error.message?.take(100) ?: error::class.simpleName.orEmpty()}",
                rowNumber
            )
        }
        runCatching {
            page.locator("text=关闭, text=取消抽奖, text=跳过").first().click(Locator.ClickOptions().setTimeout(1_000.0))
        }
    }

    private suspend fun waitUntilTargetDuration(startedAtMillis: Long, targetDurationMillis: Long) {
        val remaining = targetDurationMillis - (System.currentTimeMillis() - startedAtMillis)
        if (remaining > 0L) {
            delay(remaining)
        }
    }

    private fun targetCompletionDurationMillis(request: HighAlphaTaskRequest): Long {
        val minSeconds = (request.fillDurationMinSeconds ?: 100).coerceAtLeast(1)
        val maxSeconds = (request.fillDurationMaxSeconds ?: 200).coerceAtLeast(minSeconds)
        return ThreadLocalRandom.current().nextLong(minSeconds * 1000L, maxSeconds * 1000L + 1L)
    }

    fun simulateExpectedAlpha(): Double = 0.88
}

fun validateHighAlphaRequest(request: HighAlphaTaskRequest) {
    require(request.questionnaireUrl.isNotBlank()) { "Questionnaire URL is required." }
    val scheme = URI(request.questionnaireUrl).scheme?.lowercase(Locale.ROOT)
    require(scheme == "http" || scheme == "https") { "Only http and https questionnaire URLs are supported." }
    require(request.count > 0) { "Count must be greater than 0." }
    val fillDurationMinSeconds = request.fillDurationMinSeconds ?: 100
    val fillDurationMaxSeconds = request.fillDurationMaxSeconds ?: 200
    require(fillDurationMinSeconds >= 1) { "Fill duration min seconds must be at least 1." }
    require(fillDurationMaxSeconds >= fillDurationMinSeconds) {
        "Fill duration max seconds must be greater than or equal to min seconds."
    }
}
