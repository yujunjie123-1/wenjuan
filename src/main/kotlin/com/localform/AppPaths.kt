package com.localform

import java.io.File

class AppPaths(private val projectRoot: File) {
    val storageDir: File = File(projectRoot, ".survey-local-demo").absoluteFile
    val uploadDir: File = File(storageDir, "uploads").absoluteFile
    val screenshotDir: File = File(storageDir, "screenshots").absoluteFile

    fun ensure() {
        uploadDir.mkdirs()
        screenshotDir.mkdirs()
    }
}
