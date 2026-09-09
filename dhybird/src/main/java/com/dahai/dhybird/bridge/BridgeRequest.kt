package com.dahai.dhybird.bridge

import org.json.JSONException
import org.json.JSONObject

/** 已完成校验和解析的 H5 Bridge 请求。 */
class BridgeRequest private constructor(
    val callbackId: String,
    val plugin: String,
    val data: JSONObject
) {
    companion object {
        @JvmStatic
        @Throws(JSONException::class)
        /** 从 H5 发送的 JSON 字符串创建请求，并校验路由字段和参数类型。 */
        fun parse(message: String?): BridgeRequest {
            if (message.isNullOrBlank()) {
                throw JSONException("bridge message is empty")
            }
            val json = JSONObject(message)
            val plugin = requiredString(json, "plugin")
            val callbackId = requiredString(json, "callbackId")
            val data = when {
                !json.has("data") || json.isNull("data") -> JSONObject()
                else -> json.optJSONObject("data")
                    ?: throw JSONException("data must be an object")
            }
            return BridgeRequest(
                callbackId = callbackId,
                plugin = plugin,
                data = data
            )
        }

        private fun requiredString(json: JSONObject, key: String): String {
            val value = json.opt(key)
            if (value !is String || value.trim().isEmpty()) {
                throw JSONException("$key must be a non-empty string")
            }
            return value.trim()
        }

    }
}
