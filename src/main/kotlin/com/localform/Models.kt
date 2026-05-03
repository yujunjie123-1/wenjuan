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
    val excelColumn: String? = null,
    val excelColumns: List<String> = emptyList(),
    val questionTitle: String,
    val questionType: QuestionType,
    val valueMode: ValueMode = ValueMode.TEXT,
    val required: Boolean = true,
    val offset: Int = 0
)

@Serializable
enum class QuestionType {
    TEXT,
    SINGLE_CHOICE,
    MULTIPLE_CHOICE,
    SCALE,
    SELECT
}

@Serializable
enum class ValueMode {
    TEXT,
    ORDINAL,
    MULTI_BINARY_COLUMNS
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
    val startRow: Int? = 1,
    val endRow: Int? = null,
    val speedLevel: Int? = 3,
    val sourceRatioMobile: Int = 33,
    val sourceRatioLink: Int = 33,
    val sourceRatioWechat: Int = 34,
    val changeIp: Boolean = false,
    val submitEnabled: Boolean = false,
    val automationProfileId: String? = null,
    val proxyProfileId: String? = null,
    val fingerprintProfileId: String? = null,
    val behaviorProfileId: String? = null,
    val captchaProfileId: String? = null
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
