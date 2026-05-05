package com.localform

import com.localform.automation.behavior.HumanBehaviorSimulator
import com.localform.automation.browser.ContextInitializer
import com.localform.automation.captcha.CaptchaSolverFactory
import com.localform.automation.config.AutomationConfigService
import com.localform.automation.config.AutomationRuntimeConfig
import com.localform.automation.proxy.getCurrentIp
import com.localform.automation.proxy.ProxySessionManager
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
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom

class QuestionnaireRunner(
    private val paths: AppPaths,
    private val excelService: ExcelService,
    private val taskStore: TaskStore,
    // Automation enhancement - optional
    private val automationConfigService: AutomationConfigService
) {
    private companion object {
        const val SUBMIT_COMPLETION_TIMEOUT_MILLIS = 12_000.0
        const val SUBMIT_COMPLETION_POLL_MILLIS = 500.0
        const val POST_SUBMIT_FALLBACK_SETTLE_MILLIS = 2_000.0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(taskId: String, request: StartTaskRequest) {
        scope.launch {
            runTask(taskId, request)
        }
    }

    private suspend fun runTask(taskId: String, request: StartTaskRequest) {
        taskStore.markRunning(taskId)
        try {
            // Automation enhancement - optional
            val automation = automationConfigService.resolve(request)
            val proxySessionManager = ProxySessionManager(automation.proxyProfile, paths)
            val useProxy = request.changeIp && proxySessionManager.hasProxyPool()
            val effectiveAutomation = if (useProxy) {
                automation
            } else {
                automation.copy(proxyProfile = null)
            }
            val allRows = excelService.loadRows(request.workbookId)
            val start = (request.startRow ?: 1).coerceAtLeast(1)
            val end = request.endRow?.coerceAtMost(allRows.size) ?: allRows.size
            val startIndex = (start - 1).coerceAtLeast(0)
            val endExclusive = end.coerceAtMost(allRows.size)
            val rowsInRange = if (startIndex < endExclusive) {
                allRows.subList(startIndex, endExclusive)
            } else {
                emptyList()
            }
            val rows = request.maxRows
                ?.takeIf { it > 0 }
                ?.let { limit -> rowsInRange.take(limit) }
                ?: rowsInRange
            val submittedMappings = request.mappings.filterNot { it.isMetadataMapping() }
            val mappings = normalizeSubmittedMappings(
                mappings = submittedMappings.ifEmpty { excelService.autoGenerateMappings(request.workbookId) },
                allRows = allRows
            )
            val submissionSources = generateShuffledSources(
                mobileRatio = request.sourceRatioMobile,
                linkRatio = request.sourceRatioLink,
                wechatRatio = request.sourceRatioWechat,
                totalRows = rows.size
            )
            if (request.changeIp && !useProxy) {
                taskStore.append(taskId, "warn", "Change IP is enabled, but no proxies are available. Falling back to the local network.")
            }
            if (request.submitEnabled) {
                taskStore.append(taskId, "warn", "Submit is enabled; automatic retry is disabled per row to avoid duplicate submissions.")
            }
            if (submittedMappings.isNotEmpty() && mappings.size < submittedMappings.size) {
                taskStore.append(taskId, "info", "Merged ${submittedMappings.size} submitted mappings into ${mappings.size} questionnaire questions.")
            }
            taskStore.append(taskId, "info", "Visible browser execution started. Rows: ${rows.size} (range $start-$end), duration ${request.fillDurationMinSeconds ?: 100}-${request.fillDurationMaxSeconds ?: 200}s.")

            Playwright.create(playwrightOptions()).use { playwright ->
                launchBrowser(playwright, effectiveAutomation).use browserUse@{ browser ->
                    if (effectiveAutomation.enabled) {
                        // Automation enhancement - optional
                        val activeProxySessionManager = if (useProxy) proxySessionManager else null
                        val contextInitializer = ContextInitializer(activeProxySessionManager)

                        rows.forEachIndexed { index, row ->
                            if (taskStore.isCancelled(taskId)) {
                                taskStore.append(taskId, "warn", "Task cancelled before row ${index + 1}.")
                                return@browserUse
                            }

                            val rowNumber = start + index
                            val rowKey = "$taskId-row-$rowNumber"
                            val submissionSource = submissionSources[index]
                            val rowSignature = mappings
                                .asSequence()
                                .mapNotNull { mapping -> mapping.excelColumn ?: mapping.excelColumns.firstOrNull() }
                                .distinct()
                                .take(3)
                                .joinToString(" | ") { column -> "$column=${row[column].orEmpty()}" }
                            val maxRetries = if (request.submitEnabled) 1 else 3
                            var attempt = 0
                            var success = false

                            while (attempt < maxRetries && !success) {
                                attempt += 1
                                val attemptMessage = if (attempt > 1) " (retry $attempt/$maxRetries)" else ""
                                taskStore.append(taskId, "info", "Filling row $rowNumber$attemptMessage with proxy rotation. Source: $submissionSource.")
                                if (attempt == 1 && rowSignature.isNotBlank()) {
                                    taskStore.append(taskId, "info", "Row $rowNumber sample values: $rowSignature")
                                }

                                // Automation enhancement - optional
                                contextInitializer.createContext(browser, effectiveAutomation, rowKey, submissionSource).use { initializedContext ->
                                    success = fillRow(
                                        taskId = taskId,
                                        page = initializedContext.context.newPage(),
                                        request = request,
                                        automation = effectiveAutomation,
                                        mappings = mappings,
                                        row = row,
                                        rowNumber = rowNumber,
                                        behaviorSimulator = initializedContext.behaviorSimulator
                                    )

                                    if (taskStore.isCancelled(taskId)) {
                                        taskStore.append(taskId, "warn", "Task cancelled during row $rowNumber.")
                                        return@browserUse
                                    }

                                    if (success) {
                                        activeProxySessionManager?.reportSuccess(rowKey)
                                        taskStore.append(taskId, "info", "Row $rowNumber succeeded on attempt $attempt.")
                                    } else {
                                        activeProxySessionManager?.reportFailure(rowKey)
                                        val retryNote = if (attempt < maxRetries) {
                                            "will retry with new proxy"
                                        } else {
                                            "no retry will be attempted"
                                        }
                                        taskStore.append(taskId, "warn", "Row $rowNumber failed on attempt $attempt, $retryNote.")
                                        if (attempt < maxRetries) {
                                            delay(1500)
                                        }
                                    }
                                }
                            }

                            if (taskStore.isCancelled(taskId)) {
                                taskStore.append(taskId, "warn", "Task cancelled during row $rowNumber.")
                                return@browserUse
                            }

                            if (success) {
                                taskStore.recordSuccess(taskId)
                            } else {
                                taskStore.append(taskId, "error", "Row $rowNumber failed after $maxRetries attempts.")
                                taskStore.recordFailure(taskId)
                            }

                            if (index < rows.lastIndex) {
                                delay(request.intervalSeconds.toLong() * 1000L)
                            }
                        }
                    } else {
                        val contextInitializer = ContextInitializer()
                        rows.forEachIndexed { index, row ->
                            if (taskStore.isCancelled(taskId)) {
                                taskStore.append(taskId, "warn", "Task cancelled before row ${index + 1}.")
                                return@browserUse
                            }
                            val rowNumber = start + index
                            val submissionSource = submissionSources[index]
                            taskStore.append(taskId, "info", "Filling row $rowNumber. Source: $submissionSource.")
                            val contextOptions = Browser.NewContextOptions()
                                .setViewportSize(1280, 900)
                            val finalOptions = contextInitializer.applySubmissionSourceFingerprint(contextOptions, submissionSource)
                            val success = browser.newContext(finalOptions).use { context ->
                                fillRow(
                                    taskId = taskId,
                                    page = context.newPage(),
                                    request = request,
                                    automation = effectiveAutomation,
                                    mappings = mappings,
                                    row = row,
                                    rowNumber = rowNumber,
                                    behaviorSimulator = null
                                )
                            }
                            if (taskStore.isCancelled(taskId)) {
                                taskStore.append(taskId, "warn", "Task cancelled during row $rowNumber.")
                                return@browserUse
                            }
                            if (success) {
                                taskStore.recordSuccess(taskId)
                            } else {
                                taskStore.recordFailure(taskId)
                            }
                            if (index < rows.lastIndex) {
                                delay(request.intervalSeconds.toLong() * 1000L)
                            }
                        }
                    }
                }
            }

            if (!taskStore.isCancelled(taskId)) {
                taskStore.markCompleted(taskId)
                taskStore.append(taskId, "info", "Task completed.")
            }
        } catch (error: Throwable) {
            taskStore.markFailed(taskId, error.message ?: error::class.simpleName.orEmpty())
        }
    }

    private fun launchBrowser(playwright: Playwright, automation: AutomationRuntimeConfig): Browser {
        val options = BrowserType.LaunchOptions()
            .setHeadless(false)

        if (automation.enabled) {
            // Automation enhancement - optional
            // Future proxy and launch-option merging belongs here. Current behavior remains unchanged.
        }

        val localBrowser = listOf(
            File("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"),
            File("C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe"),
            File("C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"),
            File("C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe")
        ).firstOrNull { it.exists() }

        if (localBrowser != null) {
            return playwright.chromium().launch(options.setExecutablePath(localBrowser.toPath()))
        }

        return playwright.chromium().launch(options.setChannel("chrome"))
    }

    private fun playwrightOptions(): Playwright.CreateOptions {
        return Playwright.CreateOptions().setEnv(
            mapOf(
                "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1"
            )
        )
    }

    private fun fillRow(
        taskId: String,
        page: Page,
        request: StartTaskRequest,
        automation: AutomationRuntimeConfig,
        mappings: List<FieldMapping>,
        row: Map<String, String>,
        rowNumber: Int,
        behaviorSimulator: HumanBehaviorSimulator?
    ): Boolean {
        try {
            taskStore.append(taskId, "debug", "Row $rowNumber: fillRow started")

            val startedAtMillis = System.currentTimeMillis()
            val targetDurationMillis = targetCompletionDurationMillis(request)
            val actionTimeoutMillis = actionTimeoutMillis(targetDurationMillis)
            page.setDefaultTimeout(actionTimeoutMillis.toDouble())
            page.setDefaultNavigationTimeout(actionTimeoutMillis.toDouble())

            val baseEstimateMs = 60_000L
            val speedMultiplier = (targetDurationMillis.toDouble() / baseEstimateMs)
                .coerceIn(0.05, 1.0)

            behaviorSimulator?.speedMultiplier = speedMultiplier
            taskStore.append(
                taskId,
                "info",
                "Row $rowNumber -> target: ${targetDurationMillis / 1000}s, speed x${"%.2f".format(speedMultiplier)}, submitEnabled=${request.submitEnabled}"
            )

            taskStore.append(taskId, "debug", "Row $rowNumber: starting navigate")
            page.navigate(
                request.questionnaireUrl,
                Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
            )
            taskStore.append(taskId, "debug", "Row $rowNumber: navigate completed, waiting for NETWORKIDLE")
            val networkIdleTimeoutMillis = (targetDurationMillis / 5).coerceIn(1_000L, 5_000L)
            runCatching {
                page.waitForLoadState(
                    LoadState.NETWORKIDLE,
                    Page.WaitForLoadStateOptions().setTimeout(networkIdleTimeoutMillis.toDouble())
                )
            }.onFailure { error ->
                taskStore.append(taskId, "debug", "Row $rowNumber: NETWORKIDLE wait skipped: ${error.message ?: error::class.simpleName.orEmpty()}")
            }

            taskStore.append(taskId, "debug", "Row $rowNumber: page ready, filling ${mappings.size} mappings")

            mappings.forEachIndexed { index, mapping ->
                if (taskStore.isCancelled(taskId)) {
                    taskStore.append(taskId, "warn", "Task cancelled while filling row $rowNumber.")
                    return false
                }
                taskStore.append(
                    taskId,
                    "debug",
                    "Row $rowNumber: filling ${index + 1}/${mappings.size} -> ${mapping.questionTitle.take(30)}"
                )
                val values = resolveMappingValues(mapping, row)
                if ((values.isEmpty() || values.all { it.isSkippedAnswer() }) && !mapping.required) {
                    taskStore.append(taskId, "debug", "Row $rowNumber: mapping skipped")
                    return@forEachIndexed
                }
                require(mapping.questionTitle.isNotBlank()) {
                    "Question title is blank for Excel column ${mapping.primaryColumnLabel()}."
                }
                if (mapping.offset > 0) {
                    ensureWjxQuestionVisible(page, mapping.offset, behaviorSimulator)
                }
                fillQuestion(page, mapping.normalizedForLookup(), values, behaviorSimulator)
                clickNextPageIfNeeded(page, mappings, mapping, behaviorSimulator)
            }

            taskStore.append(
                taskId,
                "info",
                "Row $rowNumber: mappings complete, waiting for target duration (${targetDurationMillis / 1000}s)"
            )
            waitUntilTargetDuration(page, startedAtMillis, targetDurationMillis)
            taskStore.append(taskId, "info", "Row $rowNumber: target duration reached, starting screenshot")
            val filledScreenshot = saveScreenshot(page, taskId, rowNumber, "filled")

            if (request.submitEnabled) {
                taskStore.append(taskId, "info", "Row $rowNumber: submitEnabled=true, solving captcha and submitting")
                val captchaSolver = CaptchaSolverFactory().create(automation)
                if (!captchaSolver.solve(page, taskId, rowNumber)) {
                    throw RuntimeException("Captcha solve failed or timed out.")
                }
                taskStore.append(taskId, "debug", "Row $rowNumber: captcha solved, clicking submit")
                clickSubmit(page, behaviorSimulator)
                taskStore.append(taskId, "info", "Row $rowNumber filled and submit button clicked.")
                waitForSubmitCompletion(page, taskId, rowNumber)
                val submitScreenshot = runCatching { saveScreenshot(page, taskId, rowNumber, "submitted") }
                    .getOrElse { filledScreenshot }
                taskStore.append(
                    taskId,
                    "info",
                    "Row $rowNumber submit artifact captured.",
                    rowNumber,
                    submitScreenshot.absolutePath,
                    artifactUrl(submitScreenshot),
                    row
                )
            } else {
                taskStore.append(taskId, "info", "Row $rowNumber filled for review. Submit is disabled.")
                taskStore.append(
                    taskId,
                    "info",
                    "Row $rowNumber review artifact captured.",
                    rowNumber,
                    filledScreenshot.absolutePath,
                    artifactUrl(filledScreenshot),
                    row
                )
            }
            recordCurrentIp(taskId, page, request, rowNumber)
            taskStore.append(taskId, "debug", "Row $rowNumber: fillRow completed successfully")
            return true
        } catch (error: Throwable) {
            taskStore.append(taskId, "error", "Row $rowNumber execution error: ${error.message ?: error::class.simpleName.orEmpty()}")
            val screenshot = runCatching { saveScreenshot(page, taskId, rowNumber, "error") }.getOrNull()
            taskStore.append(
                taskId,
                "error",
                "Row $rowNumber failed: ${error.message ?: error::class.simpleName.orEmpty()}",
                rowNumber,
                screenshot?.absolutePath,
                screenshot?.let { artifactUrl(it) },
                row
            )
            return false
        } finally {
            detachPageBeforeContextClose(page, taskId, rowNumber)
        }
    }

    private fun waitForSubmitCompletion(page: Page, taskId: String, rowNumber: Int): Boolean {
        if (page.isClosed) {
            taskStore.append(taskId, "debug", "Row $rowNumber: page already closed after submit.")
            return true
        }

        val completed = runCatching {
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
                Page.WaitForFunctionOptions()
                    .setTimeout(SUBMIT_COMPLETION_TIMEOUT_MILLIS)
                    .setPollingInterval(SUBMIT_COMPLETION_POLL_MILLIS)
            )
        }.fold(
            onSuccess = { true },
            onFailure = { error ->
                taskStore.append(
                    taskId,
                    "warn",
                    "Row $rowNumber: submit completion signal was not detected within ${SUBMIT_COMPLETION_TIMEOUT_MILLIS.toLong() / 1000}s; continuing after fallback settle. ${error.message ?: error::class.simpleName.orEmpty()}"
                )
                false
            }
        )

        if (completed) {
            taskStore.append(taskId, "debug", "Row $rowNumber: submit completion page detected at ${runCatching { page.url() }.getOrDefault("unknown")}.")
            return true
        }

        if (!page.isClosed) {
            runCatching { page.waitForTimeout(POST_SUBMIT_FALLBACK_SETTLE_MILLIS) }
        }
        return false
    }

    private fun detachPageBeforeContextClose(page: Page, taskId: String, rowNumber: Int) {
        if (page.isClosed) {
            return
        }
        runCatching {
            page.navigate(
                "about:blank",
                Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(2_000.0)
            )
        }.onFailure { error ->
            taskStore.append(taskId, "debug", "Row $rowNumber: page detach before context close skipped: ${error.message ?: error::class.simpleName.orEmpty()}")
        }
    }

    private fun targetCompletionDurationMillis(request: StartTaskRequest): Long {
        val minSeconds = (request.fillDurationMinSeconds ?: 100).coerceAtLeast(1)
        val maxSeconds = (request.fillDurationMaxSeconds ?: 200).coerceAtLeast(minSeconds)
        return ThreadLocalRandom.current().nextLong(minSeconds * 1000L, maxSeconds * 1000L + 1L)
    }

    private fun actionTimeoutMillis(targetDurationMillis: Long): Long {
        return (targetDurationMillis / 2).coerceIn(5_000L, 15_000L)
    }

    private fun recordCurrentIp(
        taskId: String,
        page: Page,
        request: StartTaskRequest,
        rowNumber: Int
    ) {
        runCatching {
            val currentIp = page.getCurrentIp()
            excelService.writeIpToRow(request.workbookId, rowNumber, currentIp)
            currentIp
        }.onSuccess { currentIp ->
            taskStore.append(taskId, "debug", "Row $rowNumber: IP recorded as $currentIp.")
        }.onFailure { error ->
            taskStore.append(taskId, "warn", "Row $rowNumber: filled/submitted, but IP recording failed: ${error.message ?: error::class.simpleName.orEmpty()}")
        }
    }

    private fun waitUntilTargetDuration(page: Page, startedAtMillis: Long, targetDurationMillis: Long) {
        val elapsedMillis = System.currentTimeMillis() - startedAtMillis
        val remainingMillis = targetDurationMillis - elapsedMillis
        if (remainingMillis > 0) {
            page.waitForTimeout(remainingMillis.toDouble())
        }
    }

    private fun generateShuffledSources(
        mobileRatio: Int,
        linkRatio: Int,
        wechatRatio: Int,
        totalRows: Int
    ): List<String> {
        if (totalRows <= 0) {
            return emptyList()
        }

        val ratios = listOf(
            "mobile" to mobileRatio.coerceAtLeast(0),
            "link" to linkRatio.coerceAtLeast(0),
            "wechat" to wechatRatio.coerceAtLeast(0)
        )
        val ratioSum = ratios.sumOf { it.second }
        if (ratioSum <= 0) {
            return List(totalRows) { "link" }
        }

        data class Allocation(
            val source: String,
            val baseCount: Int,
            val remainder: Double
        )

        val rawCounts = ratios.map { (source, ratio) ->
            val exact = ratio.toDouble() * totalRows / ratioSum.toDouble()
            Allocation(
                source = source,
                baseCount = exact.toInt(),
                remainder = exact - exact.toInt()
            )
        }

        val remaining = totalRows - rawCounts.sumOf { it.baseCount }
        val counts = rawCounts.associate { it.source to it.baseCount }.toMutableMap()
        rawCounts
            .sortedByDescending { it.remainder }
            .take(remaining)
            .forEach { counts[it.source] = counts.getValue(it.source) + 1 }

        val sources = buildList(totalRows) {
            repeat(counts.getOrDefault("mobile", 0)) { add("mobile") }
            repeat(counts.getOrDefault("link", 0)) { add("link") }
            repeat(counts.getOrDefault("wechat", 0)) { add("wechat") }
        }.toMutableList()

        sources.shuffle()
        return sources
    }

    private fun normalizeSubmittedMappings(
        mappings: List<FieldMapping>,
        allRows: List<Map<String, String>>
    ): List<FieldMapping> {
        val orderedGroups = linkedMapOf<Int, MutableList<FieldMapping>>()
        val normalized = mutableListOf<FieldMapping>()

        mappings.forEach { mapping ->
            if (mapping.offset <= 0) {
                normalized += mapping
            } else {
                orderedGroups.getOrPut(mapping.offset) { mutableListOf() } += mapping
            }
        }

        orderedGroups.forEach { (_, group) ->
            val columns = group.flatMap { it.mappedColumns() }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            if (columns.isEmpty()) {
                normalized += group.first()
                return@forEach
            }

            val first = group.first()
            val manualQuestionType = group.firstNotNullOfOrNull { mapping ->
                mapping.questionType.takeIf { it != QuestionType.AUTO }
            }
            val questionKind = if (manualQuestionType == null) classifyGroupedQuestion(columns, allRows) else null
            val questionType = manualQuestionType ?: when (questionKind) {
                GroupedQuestionKind.MULTI_BINARY -> QuestionType.MULTIPLE_CHOICE
                GroupedQuestionKind.MATRIX_SCALE -> QuestionType.SCALE
                GroupedQuestionKind.SINGLE, null -> QuestionType.AUTO
            }
            normalized += first.copy(
                excelColumn = columns.first(),
                excelColumns = columns,
                questionType = questionType,
                valueMode = valueModeForQuestionType(questionType, first.valueMode, columns, questionKind),
                required = group.any { it.required }
            )
        }

        return normalized.sortedBy { mapping ->
            mapping.offset.takeIf { it > 0 } ?: Int.MAX_VALUE
        }
    }

    private fun valueModeForQuestionType(
        questionType: QuestionType,
        currentValueMode: ValueMode,
        columns: List<String>,
        questionKind: GroupedQuestionKind?
    ): ValueMode {
        return when (questionType) {
            QuestionType.AUTO -> currentValueMode
            QuestionType.TEXT -> ValueMode.TEXT
            QuestionType.MULTIPLE_CHOICE -> {
                if (columns.size > 1 || questionKind == GroupedQuestionKind.MULTI_BINARY) {
                    ValueMode.MULTI_BINARY_COLUMNS
                } else {
                    ValueMode.ORDINAL
                }
            }
            QuestionType.SINGLE_CHOICE,
            QuestionType.SCALE,
            QuestionType.SELECT -> ValueMode.ORDINAL
        }
    }

    private enum class GroupedQuestionKind {
        SINGLE,
        MULTI_BINARY,
        MATRIX_SCALE
    }

    private fun classifyGroupedQuestion(
        columns: List<String>,
        allRows: List<Map<String, String>>
    ): GroupedQuestionKind {
        if (columns.size <= 1) {
            return GroupedQuestionKind.SINGLE
        }

        val observedValues = allRows
            .asSequence()
            .flatMap { row -> columns.asSequence().map { column -> row[column].orEmpty().trim() } }
            .filter { it.isNotBlank() && !it.isSkippedAnswer() }
            .map { it.removeSuffix(".0").lowercase(Locale.ROOT) }
            .toSet()

        if ("0" in observedValues && observedValues.all { value -> value.isBinaryToken() }) {
            return GroupedQuestionKind.MULTI_BINARY
        }

        return GroupedQuestionKind.MATRIX_SCALE
    }

    private fun String.isBinaryToken(): Boolean {
        return this == "0" ||
            this == "1" ||
            this == "false" ||
            this == "true" ||
            this == "no" ||
            this == "yes" ||
            this == "n" ||
            this == "y" ||
            this == "否" ||
            this == "是"
    }

    private fun FieldMapping.mappedColumns(): List<String> {
        return (listOfNotNull(excelColumn) + excelColumns)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun resolveMappingValues(mapping: FieldMapping, row: Map<String, String>): List<String> {
        val columns = when {
            mapping.valueMode == ValueMode.MULTI_BINARY_COLUMNS && mapping.excelColumns.isNotEmpty() -> mapping.excelColumns
            mapping.excelColumns.isNotEmpty() -> mapping.excelColumns
            !mapping.excelColumn.isNullOrBlank() -> listOf(mapping.excelColumn)
            else -> emptyList()
        }
        val values = columns.map { column -> row[column].orEmpty().trim() }
        if (columns.size > 1) {
            return values
        }
        return if (mapping.valueMode == ValueMode.MULTI_BINARY_COLUMNS) values else values.filter { it.isNotBlank() }
    }

    private fun FieldMapping.primaryColumnLabel(): String {
        return excelColumn ?: excelColumns.joinToString(", ").ifBlank { "(unmapped)" }
    }

    private fun FieldMapping.isMetadataMapping(): Boolean {
        val labels = (listOfNotNull(excelColumn, questionTitle) + excelColumns)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val metadataColumns = setOf("序号", "提交答卷时间", "所用时间", "来源", "来源详情", "来自IP", "总分")
        return offset <= 0 && labels.any { it in metadataColumns }
    }

    private fun FieldMapping.normalizedForLookup(): FieldMapping {
        if (offset <= 0) {
            return this
        }

        val normalizedTitle = questionTitle.replace(Regex("^\\s*\\d+[.、．]\\s*"), "").trim()
        return if (normalizedTitle.isBlank()) this else copy(questionTitle = normalizedTitle)
    }

    private fun fillQuestion(
        page: Page,
        mapping: FieldMapping,
        values: List<String>,
        behaviorSimulator: HumanBehaviorSimulator?
    ) {
        if (mapping.offset > 0 && mapping.questionType != QuestionType.AUTO) {
            if (fillWjxQuestionByForcedType(page, mapping, values, behaviorSimulator)) {
                return
            }
        }

        if (mapping.offset > 0 && mapping.questionType == QuestionType.AUTO && fillWjxQuestionByNumber(page, mapping.offset, values, behaviorSimulator)) {
            return
        }

        if (mapping.offset > 0 && mapping.valueMode == ValueMode.TEXT) {
            val ordinal = values.firstOrNull()?.toOrdinalOrNull()
            if (ordinal != null) {
                clickOrdinalChoice(page, mapping.questionTitle, ordinal, behaviorSimulator, mapping.offset)
                return
            }
        }

        when (mapping.valueMode) {
            ValueMode.TEXT -> fillByQuestionType(page, mapping, values, behaviorSimulator)
            ValueMode.ORDINAL -> {
                val ordinal = values.firstOrNull()?.toOrdinalOrNull()
                requireNotNull(ordinal) {
                    "Value for ${mapping.primaryColumnLabel()} must be a positive option number."
                }
                clickOrdinalChoice(page, mapping.questionTitle, ordinal, behaviorSimulator, mapping.offset)
            }
            ValueMode.MULTI_BINARY_COLUMNS -> {
                val selectedIndexes = values.mapIndexedNotNull { index, value ->
                    if (value.isSelectedBinary()) index + 1 else null
                }
                if (selectedIndexes.isEmpty()) {
                    require(!mapping.required) {
                        "No selected options found for ${mapping.primaryColumnLabel()}."
                    }
                    return
                }
                selectedIndexes.forEach { optionIndex ->
                    clickOrdinalChoice(page, mapping.questionTitle, optionIndex, behaviorSimulator, mapping.offset)
                }
            }
        }
    }

    private fun fillWjxQuestionByForcedType(
        page: Page,
        mapping: FieldMapping,
        values: List<String>,
        behaviorSimulator: HumanBehaviorSimulator?
    ): Boolean {
        val questionNumber = mapping.offset.takeIf { it > 0 } ?: return false
        val firstValue = values.firstOrNull().orEmpty()
        val firstOrdinal = firstValue.toOrdinalOrNull()

        return when (mapping.questionType) {
            QuestionType.AUTO -> false
            QuestionType.TEXT -> fillWjxText(page, questionNumber, firstValue, behaviorSimulator)
            QuestionType.SINGLE_CHOICE -> firstOrdinal
                ?.let { clickWjxSingleOrMultiple(page, questionNumber, it, behaviorSimulator) }
                ?: clickWjxChoiceByText(page, questionNumber, firstValue, behaviorSimulator)
            QuestionType.MULTIPLE_CHOICE -> fillWjxMultiple(page, questionNumber, values, behaviorSimulator)
            QuestionType.SCALE -> {
                if (values.size > 1) {
                    fillWjxMatrix(page, questionNumber, values, behaviorSimulator)
                } else {
                    firstOrdinal?.let { clickWjxScale(page, questionNumber, it, behaviorSimulator) } ?: false
                }
            }
            QuestionType.SELECT -> fillWjxSelect(page, questionNumber, firstValue, firstOrdinal, behaviorSimulator)
        }
    }

    private fun fillWjxQuestionByNumber(
        page: Page,
        questionNumber: Int,
        values: List<String>,
        behaviorSimulator: HumanBehaviorSimulator?
    ): Boolean {
        val type = wjxQuestionType(page, questionNumber) ?: return false
        val firstValue = values.firstOrNull().orEmpty()
        val firstOrdinal = firstValue.toOrdinalOrNull()

        return when (type) {
            "1", "2" -> fillWjxText(page, questionNumber, firstValue, behaviorSimulator)
            "3" -> firstOrdinal?.let { clickWjxSingleOrMultiple(page, questionNumber, it, behaviorSimulator) }
                ?: clickWjxChoiceByText(page, questionNumber, firstValue, behaviorSimulator)
            "4" -> fillWjxMultiple(page, questionNumber, values, behaviorSimulator)
            "5" -> firstOrdinal?.let { clickWjxScale(page, questionNumber, it, behaviorSimulator) } ?: false
            "6" -> fillWjxMatrix(page, questionNumber, values, behaviorSimulator)
            "7" -> fillWjxSelect(page, questionNumber, firstValue, firstOrdinal, behaviorSimulator)
            "8", "9", "12" -> fillWjxSliderOrHiddenInput(page, questionNumber, firstValue, behaviorSimulator)
            "11" -> fillWjxSort(page, questionNumber, values, behaviorSimulator)
            else -> false
        }
    }

    private fun ensureWjxQuestionVisible(
        page: Page,
        questionNumber: Int,
        behaviorSimulator: HumanBehaviorSimulator?
    ) {
        val question = page.locator("#div$questionNumber")
        require(question.count() > 0) {
            "Question #$questionNumber not found on this questionnaire."
        }

        repeat(20) {
            if (runCatching { question.first().isVisible }.getOrDefault(false)) {
                return
            }
            if (!clickWjxNextPage(page, behaviorSimulator)) {
                return
            }
            waitForPageTransition(page, behaviorSimulator)
        }

        require(runCatching { question.first().isVisible }.getOrDefault(false)) {
            "Question #$questionNumber exists but did not become visible after paging."
        }
    }

    private fun clickNextPageIfNeeded(
        page: Page,
        mappings: List<FieldMapping>,
        currentMapping: FieldMapping,
        behaviorSimulator: HumanBehaviorSimulator?
    ) {
        val currentOffset = currentMapping.offset.takeIf { it > 0 } ?: return
        val nextOffset = mappings
            .asSequence()
            .map { it.offset }
            .filter { it > currentOffset }
            .minOrNull() ?: return

        val nextQuestion = page.locator("#div$nextOffset")
        if (nextQuestion.count() > 0 && runCatching { nextQuestion.first().isVisible }.getOrDefault(false)) {
            return
        }

        if (hasVisibleWjxNextPage(page)) {
            clickWjxNextPage(page, behaviorSimulator)
            waitForPageTransition(page, behaviorSimulator)
        }
    }

    private fun waitForPageTransition(page: Page, behaviorSimulator: HumanBehaviorSimulator?) {
        val waitMillis = behaviorSimulator?.scaledDuration(500L, 30L) ?: 150L
        page.waitForTimeout(waitMillis.toDouble())
    }

    private fun hasVisibleWjxNextPage(page: Page): Boolean {
        val nextButton = page.locator("#divNext").first()
        return nextButton.count() > 0 && runCatching { nextButton.isVisible }.getOrDefault(false)
    }

    private fun clickWjxNextPage(page: Page, behaviorSimulator: HumanBehaviorSimulator?): Boolean {
        /*
        val nextButtons = listOf(
            page.locator("#divNext").first(),
            page.locator("#ctlNext").first()
            page.getByText("下一页").first(),
            page.locator("text=下一页").first()
        )
        */
        val clickedNext = listOf(
            page.locator("#divNext").first(),
            page.locator("#ctlNext").first()
        ).any { locator ->
            clickWjxNextButton(locator, behaviorSimulator)
        }
        if (clickedNext) {
            return true
        }

        val selectors = listOf(
            "#divNext",
            "#ctlNext:has-text('下一页')",
            "a:has-text('下一页')",
            "button:has-text('下一页')",
            "div:has-text('下一页')"
        )
        return selectors.any { selector ->
            val locator = page.locator(selector).first()
            if (locator.count() == 0 || !runCatching { locator.isVisible }.getOrDefault(false)) {
                false
            } else {
                tryClickFirst(locator, behaviorSimulator)
            }
        }
    }

    private fun clickWjxNextButton(locator: Locator, behaviorSimulator: HumanBehaviorSimulator?): Boolean {
        if (locator.count() == 0 || !runCatching { locator.isVisible }.getOrDefault(false)) {
            return false
        }
        return runCatching {
            locator.scrollIntoViewIfNeeded()
            if (behaviorSimulator != null) {
                behaviorSimulator.delay()
            }
            locator.click(Locator.ClickOptions().setForce(true).setTimeout(5_000.0))
        }.isSuccess
    }

    private fun wjxQuestionType(page: Page, questionNumber: Int): String? {
        val question = page.locator("#div$questionNumber")
        if (question.count() == 0) {
            return null
        }
        return runCatching { question.first().getAttribute("type") }.getOrNull()
    }

    private fun fillWjxText(
        page: Page,
        questionNumber: Int,
        value: String,
        behaviorSimulator: HumanBehaviorSimulator?
    ): Boolean {
        if (value.isBlank() || value.isSkippedAnswer()) {
            return true
        }
        val input = page.locator(
            "#div$questionNumber textarea, #div$questionNumber input:not([type=hidden]):not([type=radio]):not([type=checkbox]):not([type=submit]), #q$questionNumber"
        ).first()
        if (input.count() == 0) {
            return false
        }
        return runCatching {
            if (behaviorSimulator != null) {
                behaviorSimulator.typeWithBehavior(input, value)
            } else {
                input.fill(value)
            }
        }.isSuccess
    }

    private fun clickWjxSingleOrMultiple(
        page: Page,
        questionNumber: Int,
        optionIndex: Int,
        behaviorSimulator: HumanBehaviorSimulator?
    ): Boolean {
        val selectors = listOf(
            "#div$questionNumber > div.ui-controlgroup > div:nth-child($optionIndex)",
            "#div$questionNumber > div:nth-child(2) > div:nth-child($optionIndex)",
            "#div$questionNumber .ui-controlgroup > div:nth-child($optionIndex)",
            "#div$questionNumber .ui-radio:nth-child($optionIndex)",
            "#div$questionNumber .ui-checkbox:nth-child($optionIndex)"
        )
        return selectors.any { selector -> tryClickFirst(page.locator(selector), behaviorSimulator) }
    }

    private fun clickWjxChoiceByText(
        page: Page,
        questionNumber: Int,
        optionText: String,
        behaviorSimulator: HumanBehaviorSimulator?
    ): Boolean {
        if (optionText.isBlank()) {
            return true
        }
        val block = page.locator("#div$questionNumber")
        if (block.count() == 0) {
            return false
        }
        return tryClickFirst(block.getByText(optionText), behaviorSimulator)
    }

    private fun fillWjxMultiple(
        page: Page,
        questionNumber: Int,
        values: List<String>,
        behaviorSimulator: HumanBehaviorSimulator?
    ): Boolean {
        if (values.size > 1 && values.all { it.isBlankOrBinaryOrSkippedValue() }) {
            val selectedIndexes = values.mapIndexedNotNull { index, value ->
                if (value.isSelectedBinary()) index + 1 else null
            }
            if (selectedIndexes.isEmpty()) {
                return true
            }
            var clickedAny = false
            selectedIndexes.forEach { optionIndex ->
                clickedAny = clickWjxSingleOrMultiple(page, questionNumber, optionIndex, behaviorSimulator) || clickedAny
            }
            return clickedAny
        }

        val activeValues = values.filterNot { it.isSkippedAnswer() }
        val selections = activeValues.flatMap { value ->
            value.split(Regex("[,，;；|\\s]+")).map { it.trim() }
        }.filter { it.isNotBlank() }
        if (selections.isEmpty()) {
            return true
        }

        var clickedAny = false
        selections.forEach { selection ->
            val clicked = selection.toOrdinalOrNull()
                ?.let { clickWjxSingleOrMultiple(page, questionNumber, it, behaviorSimulator) }
                ?: clickWjxChoiceByText(page, questionNumber, selection, behaviorSimulator)
            clickedAny = clickedAny || clicked
        }
        return clickedAny
    }

    private fun clickWjxScale(
        page: Page,
        questionNumber: Int,
        optionIndex: Int,
        behaviorSimulator: HumanBehaviorSimulator?
    ): Boolean {
        val selectors = listOf(
            "#div$questionNumber > div.scale-div > div > ul > li:nth-child($optionIndex)",
            "#div$questionNumber > div:nth-child(2) > div > ul > li:nth-child($optionIndex)",
            "#div$questionNumber .scale-rating ul > li:nth-child($optionIndex)",
            "#div$questionNumber .scale-rating li:nth-child($optionIndex)",
            "#div$questionNumber a[val='$optionIndex']"
        )
        return selectors.any { selector -> tryClickFirst(page.locator(selector), behaviorSimulator) }
    }

    private fun fillWjxMatrix(
        page: Page,
        questionNumber: Int,
        values: List<String>,
        behaviorSimulator: HumanBehaviorSimulator?
    ): Boolean {
        val rows = page.locator("#divRefTab$questionNumber tr[id^='drv${questionNumber}_']:not([id$='t']), #divRefTab$questionNumber tr[rowindex]")
        val rowCount = rows.count()
        if (rowCount == 0) {
            return false
        }

        var clickedAny = false
        val ordinals = values.map { value ->
            if (value.isSkippedAnswer()) null else value.toOrdinalOrNull()
        }
        for (rowIndex in 1..rowCount) {
            val ordinal = ordinals.getOrNull(rowIndex - 1) ?: continue
            val selectors = listOf(
                "#drv${questionNumber}_$rowIndex > td:nth-child(${ordinal + 1})",
                "#drv${questionNumber}_$rowIndex a[dval='$ordinal']",
                "#drv${questionNumber}_$rowIndex a[val='$ordinal']"
            )
            val clicked = selectors.any { selector -> tryClickFirst(page.locator(selector), behaviorSimulator) }
            clickedAny = clickedAny || clicked
        }
        return clickedAny
    }

    private fun fillWjxSelect(
        page: Page,
        questionNumber: Int,
        value: String,
        ordinal: Int?,
        behaviorSimulator: HumanBehaviorSimulator?
    ): Boolean {
        if (value.isBlank() || value.isSkippedAnswer()) {
            return true
        }

        if (setWjxSelectValueByDom(page, questionNumber, value, ordinal)) {
            return true
        }

        val opened = openWjxSelect(page, questionNumber, behaviorSimulator)
        if (opened) {
            if (ordinal != null && clickVisibleDropdownOption(page, ordinal, null, behaviorSimulator)) {
                return true
            }
            if (clickVisibleDropdownOption(page, null, value, behaviorSimulator)) {
                return true
            }
        }

        val select = page.locator("#q$questionNumber, #div$questionNumber select").first()
        if (select.count() == 0) {
            return false
        }
        return runCatching { select.selectOption(value) }.isSuccess ||
            runCatching {
                select.evaluate(
                    """
                    (select, args) => {
                        const options = Array.from(select.options || []);
                        const ordinal = args.ordinal == null ? null : Number(args.ordinal);
                        const option = options.find(item => String(item.value).trim() === args.value || String(item.textContent).trim() === args.value)
                            || (ordinal == null ? null : options[ordinal])
                            || (ordinal == null ? null : options[ordinal - 1]);
                        if (!option) return false;
                        select.value = option.value;
                        option.selected = true;
                        select.dispatchEvent(new Event('input', { bubbles: true }));
                        select.dispatchEvent(new Event('change', { bubbles: true }));
                        return true;
                    }
                    """.trimIndent(),
                    mapOf("value" to value, "ordinal" to ordinal)
                ) == true
            }.getOrDefault(false)
    }

    private fun setWjxSelectValueByDom(
        page: Page,
        questionNumber: Int,
        value: String,
        ordinal: Int?
    ): Boolean {
        return runCatching {
            page.evaluate(
                """
                (args) => {
                    const questionNumber = String(args.questionNumber);
                    const value = String(args.value || '').trim();
                    const ordinal = args.ordinal == null ? null : Number(args.ordinal);
                    const root = document.querySelector('#div' + questionNumber);
                    const select = document.querySelector('#q' + questionNumber) || (root && root.querySelector('select'));
                    if (!select || select.tagName !== 'SELECT') return false;

                    const options = Array.from(select.options || []);
                    if (options.length === 0) return false;

                    let target = null;
                    if (value) {
                        target = options.find(option => {
                            const optionValue = String(option.value || '').trim();
                            const optionText = String(option.textContent || '').trim();
                            return optionValue === value || optionText === value;
                        });
                    }
                    if (!target && ordinal !== null && Number.isFinite(ordinal)) {
                        const firstText = String((options[0] && (options[0].textContent || options[0].value)) || '').trim();
                        const placeholderOffset = /请选择|please\s*select|select/i.test(firstText) ? 1 : 0;
                        target = options[ordinal - 1 + placeholderOffset] || options[ordinal] || options[ordinal - 1];
                    }
                    if (!target || target.disabled) return false;

                    select.value = target.value;
                    target.selected = true;
                    select.dispatchEvent(new Event('input', { bubbles: true }));
                    select.dispatchEvent(new Event('change', { bubbles: true }));

                    if (window.jQuery) {
                        try {
                            window.jQuery(select).trigger('input').trigger('change');
                        } catch (_) {
                        }
                    }

                    if (root) {
                        const rendered = root.querySelector('.select2-selection__rendered, .select2-chosen, #select2-q' + questionNumber + '-container');
                        if (rendered) {
                            rendered.textContent = String(target.textContent || target.value || '').trim();
                            rendered.title = rendered.textContent;
                        }
                    }
                    return true;
                }
                """.trimIndent(),
                mapOf("questionNumber" to questionNumber, "value" to value, "ordinal" to ordinal)
            ) == true
        }.getOrDefault(false)
    }

    private fun openWjxSelect(
        page: Page,
        questionNumber: Int,
        behaviorSimulator: HumanBehaviorSimulator?
    ): Boolean {
        val selectors = listOf(
            "#select2-q$questionNumber-container",
            "#select2-q$questionNumber",
            "#div$questionNumber .select2-selection",
            "#div$questionNumber .select2-selection__rendered",
            "#div$questionNumber .select2-container",
            "#div$questionNumber .select2-choice",
            "#div$questionNumber [role=combobox]",
            "#div$questionNumber > div:nth-child(2)"
        )
        return selectors.any { selector -> tryClickFirst(page.locator(selector), behaviorSimulator) }
    }

    private fun clickVisibleDropdownOption(
        page: Page,
        ordinal: Int?,
        value: String?,
        behaviorSimulator: HumanBehaviorSimulator?
    ): Boolean {
        if (ordinal == null && value.isNullOrBlank()) {
            return false
        }

        val clickedByDom = runCatching {
            page.evaluate(
                """
                (args) => {
                    const ordinal = args.ordinal == null ? null : Number(args.ordinal);
                    const value = String(args.value || '').trim();
                    const isVisible = (element) => {
                        const style = window.getComputedStyle(element);
                        const rect = element.getBoundingClientRect();
                        return style.visibility !== 'hidden' && style.display !== 'none' && rect.width > 0 && rect.height > 0;
                    };
                    const dropdownSelectors = [
                        '.select2-container--open .select2-dropdown',
                        '.select2-dropdown',
                        '.select2-drop-active',
                        '.select2-drop',
                        '.select2-results',
                        '.select2-results__options'
                    ];
                    const dropdown = dropdownSelectors
                        .flatMap(selector => Array.from(document.querySelectorAll(selector)))
                        .find(isVisible);
                    if (!dropdown) return false;
                    const options = Array.from(dropdown.querySelectorAll('.select2-results__option, .select2-result, [role=treeitem], li'))
                        .filter(option => {
                            const text = String(option.textContent || '').trim();
                            return isVisible(option) &&
                                text.length > 0 &&
                                !option.classList.contains('select2-results__option--disabled') &&
                                !option.classList.contains('select2-disabled') &&
                                option.getAttribute('aria-disabled') !== 'true';
                        });
                    if (options.length === 0) return false;

                    let target = null;
                    if (value) {
                        target = options.find(option => String(option.textContent || '').trim() === value);
                    }
                    if (!target && ordinal !== null && Number.isFinite(ordinal)) {
                        const firstText = String(options[0].textContent || '').trim();
                        const placeholderOffset = /请选择|please\s*select|select/i.test(firstText) ? 1 : 0;
                        target = options[ordinal - 1 + placeholderOffset] || options[ordinal] || options[ordinal - 1];
                    }
                    if (!target) return false;
                    target.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
                    target.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
                    target.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
                    target.click();
                    return true;
                }
                """.trimIndent(),
                mapOf("ordinal" to ordinal, "value" to value.orEmpty())
            ) == true
        }.getOrDefault(false)
        if (clickedByDom) {
            behaviorSimulator?.delay()
            return true
        }

        val optionLocators = mutableListOf<Locator>()
        if (!value.isNullOrBlank()) {
            optionLocators += page.locator(".select2-results__option, .select2-result, [role=treeitem], li")
                .filter(Locator.FilterOptions().setHasText(value))
                .first()
        }
        if (ordinal != null) {
            optionLocators += page.locator(".select2-results__option:nth-child(${ordinal + 1}), .select2-result:nth-child(${ordinal + 1}), [role=treeitem]:nth-child(${ordinal + 1})").first()
            optionLocators += page.locator(".select2-results__option:nth-child($ordinal), .select2-result:nth-child($ordinal), [role=treeitem]:nth-child($ordinal)").first()
        }
        return optionLocators.any { locator -> tryClickFirst(locator, behaviorSimulator) }
    }

    private fun fillWjxSliderOrHiddenInput(
        page: Page,
        questionNumber: Int,
        value: String,
        behaviorSimulator: HumanBehaviorSimulator?
    ): Boolean {
        if (value.isBlank() || value.isSkippedAnswer()) {
            return true
        }
        val input = page.locator("#q$questionNumber").first()
        if (input.count() > 0) {
            val filled = runCatching {
                input.evaluate("(element, value) => { element.value = value; element.dispatchEvent(new Event('input', { bubbles: true })); element.dispatchEvent(new Event('change', { bubbles: true })); }", value)
            }.isSuccess
            if (filled) {
                return true
            }
        }
        return value.toOrdinalOrNull()?.let { clickWjxScale(page, questionNumber, it, behaviorSimulator) } ?: false
    }

    private fun fillWjxSort(
        page: Page,
        questionNumber: Int,
        values: List<String>,
        behaviorSimulator: HumanBehaviorSimulator?
    ): Boolean {
        val rankedOrder = values.mapIndexedNotNull { optionIndex, value ->
            val normalized = value.trim().removeSuffix(".0")
            if (normalized == "-2" || value.isSkippedAnswer()) {
                null
            } else {
                normalized.toIntOrNull()?.takeIf { it >= 1 }?.let { rank -> rank to (optionIndex + 1) }
            }
        }.sortedBy { it.first }.map { it.second }
        if (rankedOrder.isNotEmpty()) {
            var clickedAny = false
            rankedOrder.forEach { optionIndex ->
                clickedAny = tryClickFirst(page.locator("#div$questionNumber > ul > li:nth-child($optionIndex)"), behaviorSimulator) || clickedAny
            }
            return clickedAny
        }

        val order = values.flatMap { value ->
            value.split(Regex("[,，;；|\\s]+")).mapNotNull { it.trim().toOrdinalOrNull() }
        }
        val options = page.locator("#div$questionNumber > ul > li")
        val count = options.count()
        if (count == 0) {
            return false
        }
        val finalOrder = if (order.isEmpty()) (1..count).toList() else order
        var clickedAny = false
        finalOrder.forEach { optionIndex ->
            if (optionIndex in 1..count) {
                clickedAny = tryClickFirst(page.locator("#div$questionNumber > ul > li:nth-child($optionIndex)"), behaviorSimulator) || clickedAny
            }
        }
        return clickedAny
    }

    private fun fillByQuestionType(
        page: Page,
        mapping: FieldMapping,
        values: List<String>,
        behaviorSimulator: HumanBehaviorSimulator?
    ) {
        val value = values.firstOrNull().orEmpty()
        when (mapping.questionType) {
            QuestionType.AUTO,
            QuestionType.TEXT -> fillTextQuestion(page, mapping.questionTitle, value, behaviorSimulator)
            QuestionType.SINGLE_CHOICE, QuestionType.SCALE -> clickChoice(page, mapping.questionTitle, value, behaviorSimulator)
            QuestionType.MULTIPLE_CHOICE -> values.flatMap { item ->
                item.split(Regex("[,，;；]")).map { it.trim() }
            }.filter { it.isNotBlank() }
                .forEach { option -> clickChoice(page, mapping.questionTitle, option, behaviorSimulator) }
            QuestionType.SELECT -> selectOption(page, mapping.questionTitle, value, behaviorSimulator)
        }
    }

    private fun questionBlock(page: Page, questionTitle: String): Locator {
        val selector = ".field, .ui-field-contain, div[topic], li, section"
        return page.locator(selector)
            .filter(Locator.FilterOptions().setHasText(questionTitle))
            .first()
    }

    private fun fillTextQuestion(
        page: Page,
        questionTitle: String,
        value: String,
        behaviorSimulator: HumanBehaviorSimulator?
    ) {
        val inputSelector = "textarea, input:not([type=hidden]):not([type=radio]):not([type=checkbox]):not([type=submit]), [contenteditable=true]"
        val block = questionBlock(page, questionTitle)
        val input = block.locator(inputSelector).first()
        if (input.count() == 0) {
            clickChoice(page, questionTitle, value, behaviorSimulator)
            return
        }
        if (behaviorSimulator != null) {
            // Automation enhancement - optional
            behaviorSimulator.typeWithBehavior(input, value)
        } else {
            input.fill(value)
        }
    }

    private fun clickChoice(
        page: Page,
        questionTitle: String,
        optionText: String,
        behaviorSimulator: HumanBehaviorSimulator?
    ) {
        val block = questionBlock(page, questionTitle)
        val exact = block.getByText(optionText).first()
        if (behaviorSimulator != null) {
            // Automation enhancement - optional
            behaviorSimulator.clickWithBehavior(exact)
        } else {
            exact.click()
        }
    }

    private fun clickOrdinalChoice(
        page: Page,
        questionTitle: String,
        optionIndex: Int,
        behaviorSimulator: HumanBehaviorSimulator?,
        questionNumber: Int = 0
    ) {
        require(optionIndex >= 1) { "Option number must be greater than 0." }
        if (questionNumber > 0) {
            val directSelectors = listOf(
                "#div$questionNumber > div.scale-div > div > ul > li:nth-child($optionIndex)",
                "#div$questionNumber .scale-rating ul > li:nth-child($optionIndex)",
                "#div$questionNumber .scale-rating li:nth-child($optionIndex)",
                "#div$questionNumber .ui-controlgroup > div:nth-child($optionIndex)",
                "#div$questionNumber .ui-radio:nth-child($optionIndex)",
                "#div$questionNumber .ui-checkbox:nth-child($optionIndex)",
                "#div$questionNumber a[val='$optionIndex']"
            )
            for (selector in directSelectors) {
                if (tryClickFirst(page.locator(selector), behaviorSimulator)) {
                    return
                }
            }
        }

        val block = questionBlock(page, questionTitle)
        val scaleOption = block.locator(".scale-rating a[val='$optionIndex'], a.rate-off[val='$optionIndex'], li.td a[val='$optionIndex']")
        if (tryClickFirst(scaleOption, behaviorSimulator)) {
            return
        }

        val scaleOptions = block.locator(".scale-rating a[val], a.rate-off[val], li.td a[val]")
        val labels = block.locator(".ui-radio, .ui-checkbox")
        val fallbackLabels = block.locator("label, .label, .jqradio, .jqcheck")
        val inputs = block.locator("input[type=radio], input[type=checkbox]")
        val candidates = when {
            scaleOptions.count() > 0 -> scaleOptions
            labels.count() > 0 -> labels
            fallbackLabels.count() > 0 -> fallbackLabels
            else -> inputs
        }
        val count = candidates.count()
        require(optionIndex <= count) {
            "Question \"$questionTitle\" has $count clickable options, cannot select option $optionIndex."
        }
        val candidate = candidates.nth(optionIndex - 1)
        if (behaviorSimulator != null) {
            // Automation enhancement - optional
            behaviorSimulator.clickWithBehavior(candidate)
        } else {
            candidate.click()
        }
    }

    private fun tryClickFirst(locator: Locator, behaviorSimulator: HumanBehaviorSimulator?): Boolean {
        if (locator.count() == 0) {
            return false
        }
        return runCatching {
            val candidate = locator.first()
            val clickedByDom = runCatching {
                candidate.evaluate("element => element.click()")
            }.isSuccess
            if (clickedByDom) {
                return@runCatching
            }
            if (behaviorSimulator != null) {
                behaviorSimulator.clickWithBehavior(candidate)
            } else {
                candidate.click()
            }
        }.isSuccess
    }

    private fun selectOption(
        page: Page,
        questionTitle: String,
        value: String,
        behaviorSimulator: HumanBehaviorSimulator?
    ) {
        val block = questionBlock(page, questionTitle)
        if (behaviorSimulator != null) {
            // Automation enhancement - optional
            behaviorSimulator.delay()
        }
        block.locator("select").first().selectOption(value)
    }

    private fun clickSubmit(page: Page, behaviorSimulator: HumanBehaviorSimulator?) {
        val submitCandidates = listOf(
            page.locator("#ctlNext").first(),
            page.locator("#divSubmit").first(),
            page.locator(".submitbtn").first(),
            page.locator("input[type=submit]").first(),
            page.locator("button[type=submit]").first(),
            page.getByText("提交").first(),
            page.getByText("完成").first()
        )
        if (submitCandidates.any { locator -> clickSubmitCandidate(locator, behaviorSimulator) }) {
            return
        }

        val submit = page.locator(
            "button:has-text('提交'), button:has-text('完成'), input[type=submit], a:has-text('提交')"
        ).first()
        if (behaviorSimulator != null) {
            // Automation enhancement - optional
            behaviorSimulator.clickWithBehavior(submit)
        } else {
            submit.click(Locator.ClickOptions().setTimeout(5_000.0))
        }
    }

    private fun clickSubmitCandidate(locator: Locator, behaviorSimulator: HumanBehaviorSimulator?): Boolean {
        if (locator.count() == 0 || !runCatching { locator.isVisible }.getOrDefault(false)) {
            return false
        }
        return runCatching {
            locator.scrollIntoViewIfNeeded()
            if (behaviorSimulator != null) {
                behaviorSimulator.delay()
            }
            locator.click(Locator.ClickOptions().setForce(true))
        }.isSuccess
    }

    private fun saveScreenshot(page: Page, taskId: String, rowNumber: Int, suffix: String): File {
        val taskDir = File(paths.screenshotDir, taskId).absoluteFile
        taskDir.mkdirs()
        val file = File(taskDir, "row-$rowNumber-$suffix.png").absoluteFile
        page.screenshot(
            Page.ScreenshotOptions()
                .setPath(Path.of(file.absolutePath))
                .setFullPage(true)
                .setTimeout(60000.0)
        )
        return file
    }

    private fun artifactUrl(file: File): String {
        val taskId = file.parentFile.name
        return "/api/artifacts/$taskId/${file.name}"
    }

}

fun validateStartRequest(request: StartTaskRequest) {
    require(request.workbookId.isNotBlank()) { "Workbook is required." }
    require(request.questionnaireUrl.isNotBlank()) { "Questionnaire URL is required." }
    val scheme = URI(request.questionnaireUrl).scheme?.lowercase(Locale.ROOT)
    require(scheme == "http" || scheme == "https") { "Only http and https questionnaire URLs are supported." }
    request.mappings.forEach { mapping ->
        val hasSingleColumn = !mapping.excelColumn.isNullOrBlank()
        val hasMultipleColumns = mapping.excelColumns.isNotEmpty()
        require(hasSingleColumn || hasMultipleColumns) {
            "Each mapping must include at least one Excel column."
        }
        if (mapping.valueMode == ValueMode.MULTI_BINARY_COLUMNS) {
            require(mapping.excelColumns.isNotEmpty()) {
                "Multi-column binary mappings must include excelColumns."
            }
        }
    }
    require(request.intervalSeconds >= 2) { "Interval must be at least 2 seconds." }
    val start = request.startRow ?: 1
    require(start >= 1) { "Start row must be greater than 0." }
    request.endRow?.let { end ->
        require(end >= start) { "End row must be greater than or equal to start row." }
    }
    val fillDurationMinSeconds = request.fillDurationMinSeconds ?: 100
    val fillDurationMaxSeconds = request.fillDurationMaxSeconds ?: 200
    require(fillDurationMinSeconds >= 1) { "Fill duration min seconds must be at least 1." }
    require(fillDurationMaxSeconds >= fillDurationMinSeconds) {
        "Fill duration max seconds must be greater than or equal to min seconds."
    }
    val sourceTotal = request.sourceRatioMobile + request.sourceRatioLink + request.sourceRatioWechat
    require(request.sourceRatioMobile >= 0 && request.sourceRatioLink >= 0 && request.sourceRatioWechat >= 0) {
        "Source ratios cannot be negative."
    }
    require(sourceTotal == 100) { "Source ratios must add up to 100." }
}

private fun String.toOrdinalOrNull(): Int? {
    val normalized = trim().removeSuffix(".0")
    return normalized.toIntOrNull()?.takeIf { it >= 1 }
}

private fun String.isSkippedAnswer(): Boolean {
    val normalized = trim()
    return normalized == "-3" ||
        normalized == "-3.0" ||
        normalized.equals("(跳过)", ignoreCase = true) ||
        normalized.equals("（跳过）", ignoreCase = true)
}

private fun String.isBlankOrBinaryOrSkippedValue(): Boolean {
    val normalized = trim().removeSuffix(".0").lowercase(Locale.ROOT)
    return isSkippedAnswer() ||
        normalized.isBlank() ||
        normalized == "0" ||
        normalized == "1" ||
        normalized == "false" ||
        normalized == "true" ||
        normalized == "no" ||
        normalized == "yes" ||
        normalized == "n" ||
        normalized == "y"
}

private fun String.isBlankOrBinaryValue(): Boolean {
    val normalized = trim().removeSuffix(".0").lowercase(Locale.ROOT)
    return normalized.isBlank() ||
        normalized == "0" ||
        normalized == "1" ||
        normalized == "false" ||
        normalized == "true" ||
        normalized == "no" ||
        normalized == "yes" ||
        normalized == "n" ||
        normalized == "y"
}

private fun String.isSelectedBinary(): Boolean {
    val normalized = trim().lowercase(Locale.ROOT)
    return normalized == "1" || normalized == "true" || normalized == "yes" || normalized == "y" || normalized == "是"
}
