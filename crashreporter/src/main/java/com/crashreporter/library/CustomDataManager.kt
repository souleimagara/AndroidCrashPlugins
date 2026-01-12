package com.crashreporter.library

object CustomDataManager {
    private val customData = mutableMapOf<String, String>()
    private var environment: String = "production"

    @JvmStatic
    @JvmOverloads
    fun setUserContext(userId: String?, email: String? = null, username: String? = null) {
        userId?.let { customData["userId"] = it }
        email?.let { customData["email"] = it }
        username?.let { customData["username"] = it }
    }

    @JvmStatic
    fun setTag(key: String, value: String) {
        customData[key] = value
    }

    @JvmStatic
    fun removeTag(key: String) {
        customData.remove(key)
    }

    @JvmStatic
    fun setEnvironment(env: String) {
        environment = env
    }

    @JvmStatic
    fun getEnvironment(): String = environment

    @JvmStatic
    fun getCustomData(): Map<String, String> {
        return customData.toMap()
    }

    @JvmStatic
    fun clear() {
        customData.clear()
    }
}