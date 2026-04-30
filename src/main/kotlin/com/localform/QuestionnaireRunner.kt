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
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.util.Locale

class QuestionnaireRunner(
    private val paths: AppPaths,
    private val excelService: ExcelService,
    private val taskStore: TaskStore
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
            val rows = excelService.loadRows(request.workbookId).take(resolveRowLimit(request))
            val intervalMillis = request.intervalSeconds.coerceAtLeast(2) * 1000L
            taskStore.append(taskId, "info", "Visible browser execution started. Rows: ${rows.size}.")

            Playwright.create(playwrightOptions()).use { playwright ->
                launchBrowser(playwright).use { browser ->
                    val context = browser.newContext(
                        Browser.NewContextOptions()
                            .setViewportSize(1280, 900)
                    )

                    rows.forEachIndexed { index, row ->
                        if (taskStore.isCancelled(taskId)) {
                            taskStore.append(taskId, "warn", "Task cancelled before row ${index + 1}.")
                            return@use
                        }
                        fillRow(taskId, context.newPage(), request, row, index + 1)
                        if (index < rows.lastIndex) {
                            delay(intervalMillis)
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

    private fun launchBrowser(playwright: Playwright): Browser {
        val options = BrowserType.LaunchOptions()
            .setHeadless(false)
            .setSlowMo(120.0)

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
        row: Map<String, String>,
        rowNumber: Int
    ) {
        try {
            page.navigate(
                request.questionnaireUrl,
                Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
            )
            page.waitForLoadState(LoadState.NETWORKIDLE)

            request.mappings.forEach { mapping ->
                val value = row[mapping.excelColumn].orEmpty().trim()
                if (value.isBlank() && !mapping.required) {
                    return@forEach
                }
                require(mapping.questionTitle.isNotBlank()) {
                    "Question title is blank for Excel column ${mapping.excelColumn}."
                }
                fillQuestion(page, mapping, value)
            }

            val screenshot = saveScreenshot(page, taskId, rowNumber, "filled")
            if (request.submitEnabled) {
                clickSubmit(page)
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

    private fun fillQuestion(page: Page, mapping: FieldMapping, value: String) {
        when (mapping.questionType) {
            QuestionType.TEXT -> fillTextQuestion(page, mapping.questionTitle, value)
            QuestionType.SINGLE_CHOICE -> clickChoice(page, mapping.questionTitle, value)
            QuestionType.MULTIPLE_CHOICE -> value.split(Regex("[,，;；]"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { option -> clickChoice(page, mapping.questionTitle, option) }
            QuestionType.SELECT -> selectOption(page, mapping.questionTitle, value)
        }
    }

    private fun questionBlock(page: Page, questionTitle: String): Locator {
        val selector = "fieldset, li, section, div"
        return page.locator(selector)
            .filter(Locator.FilterOptions().setHasText(questionTitle))
            .first()
    }

    private fun fillTextQuestion(page: Page, questionTitle: String, value: String) {
        val inputSelector = "textarea, input:not([type=radio]):not([type=checkbox]):not([type=submit]), [contenteditable=true]"
        val block = questionBlock(page, questionTitle)
        val input = block.locator(inputSelector).first()
        input.fill(value)
    }

    private fun clickChoice(page: Page, questionTitle: String, optionText: String) {
        val block = questionBlock(page, questionTitle)
        val exact = block.getByText(optionText).first()
        exact.click()
    }

    private fun selectOption(page: Page, questionTitle: String, value: String) {
        val block = questionBlock(page, questionTitle)
        block.locator("select").first().selectOption(value)
    }

    private fun clickSubmit(page: Page) {
        val submit = page.locator(
            "button:has-text('提交'), button:has-text('完成'), input[type=submit], a:has-text('提交')"
        ).first()
        submit.click()
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
    require(request.mappings.isNotEmpty()) { "At least one field mapping is required." }
    require(request.intervalSeconds >= 2) { "Interval must be at least 2 seconds." }
}
