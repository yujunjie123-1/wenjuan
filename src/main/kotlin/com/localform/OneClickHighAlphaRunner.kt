package com.localform

import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val url = parseArg(args, "--url")
        ?: args.getOrNull(0)
        ?: "https://v.wjx.cn/vm/Y0EQA2i.aspx#"
    val count = parseArg(args, "--count")?.toIntOrNull()
        ?: args.getOrNull(1)?.toIntOrNull()
        ?: 300
    val mode = parseArg(args, "--mode") ?: "high-alpha"
    require(mode == "high-alpha") { "Only --mode high-alpha is supported." }

    val taskStore = TaskStore()
    val filler = OneClickHighAlphaFiller(taskStore)
    val request = HighAlphaTaskRequest(
        questionnaireUrl = url,
        count = count,
        submitEnabled = true
    )

    val task = taskStore.create(count)
    filler.start(task.id, request)
    runBlocking {
        while (true) {
            val snapshot = taskStore.get(task.id) ?: break
            if (snapshot.status == TaskStatus.COMPLETED || snapshot.status == TaskStatus.FAILED || snapshot.status == TaskStatus.CANCELLED) {
                break
            }
            kotlinx.coroutines.delay(500L)
        }
    }
    println("High-alpha batch completed. Estimated alpha >= ${"%.2f".format(filler.simulateExpectedAlpha())}, generated $count rows.")
}

private fun parseArg(args: Array<String>, flag: String): String? {
    val index = args.indexOf(flag)
    if (index == -1 || index == args.lastIndex) {
        return null
    }
    return args[index + 1]
}
