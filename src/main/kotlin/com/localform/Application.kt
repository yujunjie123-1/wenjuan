package com.localform

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.default
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.io.File

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, host = "127.0.0.1", port = port) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    val projectRoot = File(System.getProperty("user.dir")).absoluteFile
    val paths = AppPaths(projectRoot)
    paths.ensure()

    val excelService = ExcelService(paths)
    val taskStore = TaskStore()
    val runner = QuestionnaireRunner(paths, excelService, taskStore)

    install(CallLogging)
    install(CORS) {
        anyHost()
    }
    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
        )
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Request failed."))
        }
    }

    routing {
        staticResources("/", "static") {
            default("index.html")
        }

        get("/api/health") {
            call.respond(HealthResponse("ok", paths.storageDir.absolutePath))
        }

        post("/api/workbooks") {
            val multipart = call.receiveMultipart()
            var fileName = "workbook.xlsx"
            var bytes: ByteArray? = null

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        fileName = part.originalFileName ?: fileName
                        bytes = part.streamProvider().use { input -> input.readBytes() }
                    }
                    else -> Unit
                }
                part.dispose()
            }

            val content = bytes ?: error("No Excel file was uploaded.")
            call.respond(excelService.saveAndPreview(fileName, content))
        }

        post("/api/tasks") {
            val request = call.receive<StartTaskRequest>()
            validateStartRequest(request)
            val rows = excelService.loadRows(request.workbookId)
            val totalRows = (request.maxRows ?: 20).coerceIn(1, 50).coerceAtMost(rows.size)
            val task = taskStore.create(totalRows)
            runner.start(task.id, request.copy(maxRows = totalRows))
            call.respond(task)
        }

        get("/api/tasks/{id}") {
            val id = call.parameters["id"] ?: error("Task id is required.")
            val task = taskStore.get(id) ?: error("Task not found.")
            call.respond(task)
        }

        post("/api/tasks/{id}/cancel") {
            val id = call.parameters["id"] ?: error("Task id is required.")
            val task = taskStore.cancel(id) ?: error("Task not found.")
            call.respond(task)
        }

        get("/api/artifacts/{taskId}/{fileName}") {
            val taskId = call.parameters["taskId"] ?: error("Task id is required.")
            val fileName = call.parameters["fileName"] ?: error("File name is required.")
            val file = File(File(paths.screenshotDir, taskId).absoluteFile, fileName).absoluteFile
            require(file.parentFile.absolutePath.startsWith(paths.screenshotDir.absolutePath)) {
                "Invalid artifact path."
            }
            require(file.exists()) { "Artifact not found." }
            call.respondBytes(file.readBytes(), ContentType.Image.PNG)
        }

        get("/api") {
            call.respondText("survey-local-demo")
        }
    }
}
