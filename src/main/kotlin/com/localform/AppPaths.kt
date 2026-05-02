package com.localform

import java.io.File

class AppPaths(val projectRoot: File) {
    val storageDir: File = File(projectRoot, ".survey-local-demo").absoluteFile
    val uploadDir: File = File(storageDir, "uploads").absoluteFile
    val screenshotDir: File = File(storageDir, "screenshots").absoluteFile
    val configDir: File = File(projectRoot, "config").absoluteFile
    val automationProfilesFile: File = File(configDir, "automation-profiles.json").absoluteFile

    fun ensure() {
        configDir.mkdirs()
        uploadDir.mkdirs()
        screenshotDir.mkdirs()
    }
}
