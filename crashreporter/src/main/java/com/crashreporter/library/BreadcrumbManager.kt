package com.crashreporter.library

import java.util.concurrent.ConcurrentLinkedQueue

object BreadcrumbManager {
    private const val MAX_BREADCRUMBS = 100
    private val breadcrumbs = ConcurrentLinkedQueue<Breadcrumb>()

    fun addBreadcrumb(
        category: String,
        message: String,
        level: String = "info",
        data: Map<String, String> = emptyMap()
    ) {
        val breadcrumb = Breadcrumb(
            timestamp = System.currentTimeMillis(),
            category = category,
            message = message,
            level = level,
            data = data
        )

        breadcrumbs.add(breadcrumb)

        // Keep only last MAX_BREADCRUMBS
        while (breadcrumbs.size > MAX_BREADCRUMBS) {
            breadcrumbs.poll()
        }
    }

    fun getBreadcrumbs(): List<Breadcrumb> {
        return breadcrumbs.toList()
    }

    fun clear() {
        breadcrumbs.clear()
    }
}