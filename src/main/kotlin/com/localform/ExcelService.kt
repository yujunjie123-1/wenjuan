package com.localform

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private data class UploadedWorkbook(
    val id: String,
    val originalFileName: String,
    val file: File,
    val createdAt: Instant
)

class ExcelService(private val paths: AppPaths) {
    private val uploads = ConcurrentHashMap<String, UploadedWorkbook>()
    private val workbookLocks = ConcurrentHashMap<String, Any>()
    private val formatter = DataFormatter(Locale.CHINA)

    fun saveAndPreview(originalFileName: String, bytes: ByteArray): WorkbookPreview {
        val safeName = originalFileName.substringAfterLast('\\').substringAfterLast('/').ifBlank { "workbook.xlsx" }
        val extension = safeName.substringAfterLast('.', "xlsx").lowercase(Locale.ROOT)
        require(extension == "xlsx" || extension == "xls") { "Only .xlsx and .xls files are supported." }
        require(bytes.isNotEmpty()) { "Uploaded Excel file is empty." }

        val id = UUID.randomUUID().toString()
        val file = File(paths.uploadDir, "$id-$safeName").absoluteFile
        file.writeBytes(bytes)
        require(file.length() > 0L) { "Saved Excel file is empty." }

        val parsed = parseRows(file)
        val workbook = UploadedWorkbook(id, safeName, file, Instant.now())
        uploads[id] = workbook

        return WorkbookPreview(
            workbookId = id,
            originalFileName = safeName,
            headers = parsed.headers,
            rows = parsed.rows.take(10),
            rowCount = parsed.rows.size
        )
    }

    fun loadRows(workbookId: String): List<Map<String, String>> {
        val workbook = uploads[workbookId] ?: error("Workbook not found or server was restarted.")
        return synchronized(lockFor(workbookId)) {
            parseRows(workbook.file).rows
        }
    }

    fun loadRowsInRange(workbookId: String, start: Int, end: Int): List<Map<String, String>> {
        val allRows = loadRows(workbookId)
        val startIndex = (start - 1).coerceAtLeast(0)
        val endExclusive = end.coerceAtMost(allRows.size)
        return if (startIndex < endExclusive) allRows.subList(startIndex, endExclusive) else emptyList()
    }

    fun writeIpToRow(workbookId: String, rowIndex: Int, ip: String) {
        val uploaded = uploads[workbookId] ?: error("Workbook not found or server was restarted.")
        synchronized(lockFor(workbookId)) {
            requireReadableWorkbook(uploaded.file)
            val tempFile = File.createTempFile("${uploaded.file.nameWithoutExtension}-", ".tmp", uploaded.file.parentFile)
            try {
                WorkbookFactory.create(uploaded.file).use { workbook ->
                    val sheet = workbook.getSheetAt(0) ?: error("The first worksheet is empty.")
                    val headerRow = sheet.firstOrNull { row -> row.anyCellText().isNotEmpty() }
                        ?: error("No header row found in the first worksheet.")
                    val headers = headerRow.mapCells()
                    val ipColumnIndex = headers.indexOf("来自IP").takeIf { it >= 0 } ?: headers.size
                    if (ipColumnIndex == headers.size) {
                        headerRow.createCell(ipColumnIndex).setCellValue("来自IP")
                    }

                    val targetRowNumber = headerRow.rowNum + rowIndex
                    val row = sheet.getRow(targetRowNumber) ?: sheet.createRow(targetRowNumber)
                    val cell = row.getCell(ipColumnIndex, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)
                    cell.setCellValue(ip)

                    tempFile.outputStream().use { output ->
                        workbook.write(output)
                    }
                }
                require(tempFile.length() > 0L) { "Temporary Excel output is empty; original workbook was not replaced." }
                replaceFile(tempFile, uploaded.file)
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }
    }

    fun autoGenerateMappings(workbookId: String): List<FieldMapping> {
        val workbook = uploads[workbookId] ?: error("Workbook not found or server was restarted.")
        val parsed = parseRows(workbook.file)
        val questionColumns = parsed.headers.filter { header -> header.questionNumberPrefix() != null }
        val grouped = questionColumns.groupBy { header -> header.questionNumberPrefix() }

        return questionColumns.mapNotNull { columnName ->
            val questionNumber = columnName.questionNumberPrefix() ?: return@mapNotNull null
            val groupedColumns = grouped[questionNumber].orEmpty()
            if (groupedColumns.firstOrNull() != columnName) {
                return@mapNotNull null
            }

            FieldMapping(
                excelColumn = columnName,
                excelColumns = groupedColumns,
                questionTitle = columnName,
                questionType = QuestionType.TEXT,
                valueMode = ValueMode.ORDINAL,
                required = false,
                offset = questionNumber
            )
        }
    }

    private fun String.questionNumberPrefix(): Int? {
        return Regex("^\\s*(\\d+)[.、．]\\s*").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.isBlankOrBinary(): Boolean {
        val normalized = trim().removeSuffix(".0").lowercase(Locale.ROOT)
        return normalized.isBlank() || normalized == "0" || normalized == "1" ||
            normalized == "false" || normalized == "true" ||
            normalized == "no" || normalized == "yes" ||
            normalized == "n" || normalized == "y" ||
            normalized == "否" || normalized == "是"
    }

    private fun parseRows(file: File): ParsedWorkbook {
        requireReadableWorkbook(file)
        WorkbookFactory.create(file).use { workbook ->
            val sheet = workbook.getSheetAt(0) ?: error("The first worksheet is empty.")
            val headerRow = sheet.firstOrNull { row -> row.anyCellText().isNotEmpty() }
                ?: error("No header row found in the first worksheet.")
            val headers = headerRow.mapCells().mapIndexed { index, value ->
                value.ifBlank { "Column ${index + 1}" }
            }

            val rows = mutableListOf<Map<String, String>>()
            for (rowIndex in (headerRow.rowNum + 1)..sheet.lastRowNum) {
                val row = sheet.getRow(rowIndex) ?: continue
                val values = headers.indices.associate { columnIndex ->
                    headers[columnIndex] to row.cellText(columnIndex)
                }
                if (values.values.any { it.isNotBlank() }) {
                    rows += values
                }
            }

            return ParsedWorkbook(headers, rows)
        }
    }

    private fun Row.mapCells(): List<String> {
        val last = lastCellNum.toInt().coerceAtLeast(0)
        return (0 until last).map { index -> cellText(index) }
    }

    private fun Row.anyCellText(): String = mapCells().joinToString("").trim()

    private fun Row.cellText(index: Int): String {
        val cell: Cell = getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL) ?: return ""
        return formatter.formatCellValue(cell).trim()
    }

    private fun lockFor(workbookId: String): Any = workbookLocks.computeIfAbsent(workbookId) { Any() }

    private fun requireReadableWorkbook(file: File) {
        require(file.exists()) { "Excel file does not exist: ${file.absolutePath}" }
        require(file.length() > 0L) { "Excel file is empty: ${file.absolutePath}" }
    }

    private fun replaceFile(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private data class ParsedWorkbook(
    val headers: List<String>,
    val rows: List<Map<String, String>>
)
