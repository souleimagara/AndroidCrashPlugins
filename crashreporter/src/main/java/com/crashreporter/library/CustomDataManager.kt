package com.crashreporter.library

object CustomDataManager {
    private val customData = mutableMapOf<String, String>()
    private var environment: String = "production"

    fun setUserContext(userId: String?, email: String? = null, username: String? = null) {
        userId?.let { customData["userId"] = it }
        email?.let { customData["email"] = it }
        username?.let { customData["username"] = it }
    }

    fun setTag(key: String, value: String) {
        customData[key] = value
    }

    fun removeTag(key: String) {
        customData.remove(key)
    }

    fun setEnvironment(env: String) {
        environment = env
    }

    fun getEnvironment(): String = environment

    fun getCustomData(): Map<String, String> {
        return customData.toMap()
    }

    fun clear() {
        customData.clear()
    }
}