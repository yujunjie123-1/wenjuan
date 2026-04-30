package com.localform

import kotlinx.serialization.Serializable

@Serializable
data class WorkbookPreview(
    val workbookId: String,
    val originalFileName: String,
    val headers: List<String>,
    val rows: List<Map<String, String>>,
    val rowCount: Int
)

@Serializable
data class FieldMapping(
    val excelColumn: String,
    val questionTitle: String,
    val questionType: QuestionType,
    val required: Boolean = true
)

@Serializable
enum class QuestionType {
    TEXT,
    SINGLE_CHOICE,
    MULTIPLE_CHOICE,
    SELECT
}

@Serializable
enum class ExecutionMode {
    DEMO,
    AUTO
}

@Serializable
data class StartTaskRequest(
    val workbookId: String,
    val questionnaireUrl: String,
    val mappings: List<FieldMapping>,
    val mode: ExecutionMode = ExecutionMode.DEMO,
    val intervalSeconds: Int = 3,
    val maxRows: Int? = null,
    val submitEnabled: Boolean = false
)

@Serializable
enum class TaskStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Serializable
data class TaskLogEntry(
    val timestamp: String,
    val level: String,
    val message: String,
    val rowNumber: Int? = null,
    val screenshotPath: String? = null,
    val screenshotUrl: String? = null,
    val rowData: Map<String, String>? = null
)

@Serializable
data class TaskSnapshot(
    val id: String,
    val status: TaskStatus,
    val createdAt: String,
    val updatedAt: String,
    val logs: List<TaskLogEntry>,
    val totalRows: Int,
    val completedRows: Int,
    val failedRows: Int
)

@Serializable
data class ErrorResponse(
    val error: String
)

@Serializable
data class HealthResponse(
    val status: String,
    val storageDir: String
)
