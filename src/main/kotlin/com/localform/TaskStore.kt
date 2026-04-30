package com.localform

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class TaskStore {
    private val tasks = ConcurrentHashMap<String, MutableTask>()

    fun create(totalRows: Int): TaskSnapshot {
        val now = Instant.now().toString()
        val task = MutableTask(
            id = UUID.randomUUID().toString(),
            status = TaskStatus.QUEUED,
            createdAt = now,
            updatedAt = now,
            totalRows = totalRows
        )
        tasks[task.id] = task
        return task.snapshot()
    }

    fun get(id: String): TaskSnapshot? = tasks[id]?.snapshot()

    fun markRunning(id: String) {
        tasks[id]?.updateStatus(TaskStatus.RUNNING)
    }

    fun markCompleted(id: String) {
        tasks[id]?.updateStatus(TaskStatus.COMPLETED)
    }

    fun markFailed(id: String, message: String) {
        append(id, "error", message)
        tasks[id]?.updateStatus(TaskStatus.FAILED)
    }

    fun cancel(id: String): TaskSnapshot? {
        val task = tasks[id] ?: return null
        task.cancelled.set(true)
        task.updateStatus(TaskStatus.CANCELLED)
        append(id, "warn", "Task cancellation requested.")
        return task.snapshot()
    }

    fun isCancelled(id: String): Boolean = tasks[id]?.cancelled?.get() == true

    fun append(
        id: String,
        level: String,
        message: String,
        rowNumber: Int? = null,
        screenshotPath: String? = null,
        screenshotUrl: String? = null,
        rowData: Map<String, String>? = null
    ) {
        tasks[id]?.appendLog(
            TaskLogEntry(
                timestamp = Instant.now().toString(),
                level = level,
                message = message,
                rowNumber = rowNumber,
                screenshotPath = screenshotPath,
                screenshotUrl = screenshotUrl,
                rowData = rowData
            )
        )
    }

    fun recordSuccess(id: String) {
        tasks[id]?.recordSuccess()
    }

    fun recordFailure(id: String) {
        tasks[id]?.recordFailure()
    }
}

private class MutableTask(
    val id: String,
    private var status: TaskStatus,
    private val createdAt: String,
    private var updatedAt: String,
    private val totalRows: Int
) {
    val cancelled = AtomicBoolean(false)
    private val logs = mutableListOf<TaskLogEntry>()
    private var completedRows = 0
    private var failedRows = 0

    @Synchronized
    fun updateStatus(next: TaskStatus) {
        status = next
        updatedAt = Instant.now().toString()
    }

    @Synchronized
    fun appendLog(entry: TaskLogEntry) {
        logs += entry
        updatedAt = entry.timestamp
    }

    @Synchronized
    fun recordSuccess() {
        completedRows += 1
        updatedAt = Instant.now().toString()
    }

    @Synchronized
    fun recordFailure() {
        failedRows += 1
        updatedAt = Instant.now().toString()
    }

    @Synchronized
    fun snapshot(): TaskSnapshot = TaskSnapshot(
        id = id,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
        logs = logs.toList(),
        totalRows = totalRows,
        completedRows = completedRows,
        failedRows = failedRows
    )
}
