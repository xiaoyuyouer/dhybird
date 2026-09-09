package com.dahai.dhybird.bridge

import org.json.JSONException
import org.json.JSONObject

/** 已完成校验和解析的 H5 Bridge 请求。 */
class BridgeRequest private constructor(
    val callbackId: String,
    val plugin: String,
    val sdkVersion: String,
    val data: JSONObject
) {
    companion object {
        @JvmStatic
        @Throws(JSONException::class)
        /** 从 H5 发送的 JSON 字符串创建请求，缺少 plugin 时直接失败。 */
        fun parse(message: String?): BridgeRequest {
            if (message.isNullOrBlank()) {
                throw JSONException("bridge message is empty")
            }
            val json = JSONObject(message)
            val plugin = json.optString("plugin", "").trim()
            if (plugin.isEmpty()) {
                throw JSONException("plugin is required")
            }
            return BridgeRequest(
                callbackId = json.optString("callbackId", ""),
                plugin = plugin,
                sdkVersion = json.optString("sdkVersion", ""),
                data = json.optJSONObject("data") ?: JSONObject()
            )
        }
    }
}
