package com.localform

import com.localform.automation.behavior.HumanBehaviorSimulator
import com.localform.automation.browser.ContextInitializer
import com.localform.automation.config.AutomationConfigService
import com.localform.automation.config.AutomationRuntimeConfig
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
            if (automation.proxyProfile != null) {
                println("[DEBUG] Proxy profile detected; proxy is temporarily disabled for testing.")
            }
            val effectiveAutomation = automation.copy(
                proxyProfile = null
            )
            val rows = excelService.loadRows(request.workbookId).take(resolveRowLimit(request))
            val submittedMappings = request.mappings.filterNot { it.isMetadataMapping() }
            val generatedMappings = excelService.autoGenerateMappings(request.workbookId)
            val mappings = generatedMappings.ifEmpty { submittedMappings }
            val intervalMillis = request.intervalSeconds.coerceAtLeast(2) * 1000L
            taskStore.append(taskId, "info", "Visible browser execution started. Rows: ${rows.size}.")

            Playwright.create(playwrightOptions()).use { playwright ->
                launchBrowser(playwright, effectiveAutomation).use { browser ->
                    if (effectiveAutomation.enabled) {
                        // Automation enhancement - optional
                        val proxySessionManager = ProxySessionManager(effectiveAutomation.proxyProfile)
                        val contextInitializer = ContextInitializer(proxySessionManager)

                        rows.forEachIndexed { index, row ->
                            if (taskStore.isCancelled(taskId)) {
                                taskStore.append(taskId, "warn", "Task cancelled before row ${index + 1}.")
                                return@use
                            }

                            val rowNumber = index + 1
                            val rowKey = "$taskId-row-$rowNumber"
                            // Automation enhancement - optional
                            contextInitializer.createContext(browser, effectiveAutomation, rowKey).use { initializedContext ->
                                fillRow(
                                    taskId = taskId,
                                    page = initializedContext.context.newPage(),
                                    request = request,
                                    mappings = mappings,
                                    row = row,
                                    rowNumber = rowNumber,
                                    behaviorSimulator = initializedContext.behaviorSimulator
                                )
                            }

                            if (index < rows.lastIndex) {
                                delay(intervalMillis)
                            }
                        }
                    } else {
                        val context = browser.newContext(
                            Browser.NewContextOptions()
                                .setViewportSize(1280, 900)
                        )

                        rows.forEachIndexed { index, row ->
                            if (taskStore.isCancelled(taskId)) {
                                taskStore.append(taskId, "warn", "Task cancelled before row ${index + 1}.")
                                return@use
                            }
                            fillRow(taskId, context.newPage(), request, mappings, row, index + 1, null)
                            if (index < rows.lastIndex) {
                                delay(intervalMillis)
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
            .setSlowMo(120.0)

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
        mappings: List<FieldMapping>,
        row: Map<String, String>,
        rowNumber: Int,
        behaviorSimulator: HumanBehaviorSimulator?
    ) {
        try {
            val startedAtMillis = System.currentTimeMillis()
            val targetDurationMillis = targetCompletionDurationMillis(mappings)

            page.navigate(
                request.questionnaireUrl,
                Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
            )
            page.waitForLoadState(LoadState.NETWORKIDLE)

            mappings.forEach { mapping ->
                val values = resolveMappingValues(mapping, row)
                if ((values.isEmpty() || values.all { it.isSkippedAnswer() }) && !mapping.required) {
                    return@forEach
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

            waitUntilTargetDuration(page, startedAtMillis, targetDurationMillis)
            val screenshot = saveScreenshot(page, taskId, rowNumber, "filled")
            if (request.submitEnabled) {
                clickSubmit(page, behaviorSimulator)
                taskStore.append(
                    taskId,
                    "info",
                    "Row $rowNumber filled and submit button clicked.",
                    rowNumber,
                    screenshot.absolutePath,
                    artifactUrl(screenshot),
                    row
                )
            } else {
                taskStore.append(
                    taskId,
                    "info",
                    "Row $rowNumber filled for review. Submit is disabled.",
                    rowNumber,
                    screenshot.absolutePath,
                    artifactUrl(screenshot),
                    row
                )
            }
            taskStore.recordSuccess(taskId)
        } catch (error: Throwable) {
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
            taskStore.recordFailure(taskId)
        } finally {
            runCatching { page.close() }
        }
    }

    private fun targetCompletionDurationMillis(mappings: List<FieldMapping>): Long {
        val questionCount = mappings
            .mapNotNull { mapping -> mapping.offset.takeIf { it > 0 } }
            .distinct()
            .size
            .takeIf { it > 0 } ?: mappings.size.coerceAtLeast(1)
        val group = (questionCount - 1) / 5
        val minSeconds = 40 + group * 20
        val maxSeconds = 60 + group * 20
        return ThreadLocalRandom.current().nextLong(minSeconds * 1000L, maxSeconds * 1000L + 1L)
    }

    private fun waitUntilTargetDuration(page: Page, startedAtMillis: Long, targetDurationMillis: Long) {
        val elapsedMillis = System.currentTimeMillis() - startedAtMillis
        val remainingMillis = targetDurationMillis - elapsedMillis
        if (remainingMillis > 0) {
            page.waitForTimeout(remainingMillis.toDouble())
        }
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
        if (mapping.offset > 0 && fillWjxQuestionByNumber(page, mapping.offset, values, behaviorSimulator)) {
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
            page.waitForTimeout(500.0)
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
            page.waitForTimeout(500.0)
        }
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
            locator.click(Locator.ClickOptions().setForce(true))
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
        if (ordinal != null) {
            val opened = tryClickFirst(page.locator("#select2-q$questionNumber-container, #div$questionNumber > div:nth-child(2)"), behaviorSimulator)
            if (opened) {
                val option = page.locator("#select2-q$questionNumber-results > li:nth-child(${ordinal + 1})")
                if (tryClickFirst(option, behaviorSimulator)) {
                    return true
                }
            }
        }

        val select = page.locator("#q$questionNumber, #div$questionNumber select").first()
        if (select.count() == 0) {
            return false
        }
        return runCatching { select.selectOption(value) }.isSuccess
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
            submit.click()
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
        page.screenshot(Page.ScreenshotOptions().setPath(Path.of(file.absolutePath)).setFullPage(true))
        return file
    }

    private fun artifactUrl(file: File): String {
        val taskId = file.parentFile.name
        return "/api/artifacts/$taskId/${file.name}"
    }

    private fun resolveRowLimit(request: StartTaskRequest): Int {
        val requested = request.maxRows ?: 20
        return requested.coerceIn(1, 50)
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
