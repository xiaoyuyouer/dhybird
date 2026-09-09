package com.tal.dhybirddemo.plugin

import com.dahai.dhybird.bridge.BridgePlugin
import com.dahai.dhybird.bridge.BridgeRequest
import com.dahai.dhybird.bridge.BridgeResponder
import com.dahai.dhybird.bridge.PluginRegistry
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Demo App 自己提供的能力查询插件，不属于通用 dhybird SDK。 */
class DemoCheckAvailablePlugin(
    private val pluginRegistry: PluginRegistry
) : BridgePlugin {
    override fun name(): String = "common.checkAvailable"

    override fun execute(request: BridgeRequest, responder: BridgeResponder) {
        val data = request.data
        val result = JSONObject()
        try {
            val available = data.optJSONArray("available")
            if (available == null) {
                responder.failure("INVALID_ARGUMENT", "available must be an array")
                return
            }
            val checkList = JSONArray()
            for (index in 0 until available.length()) {
                checkList.put(
                    pluginRegistry.contains(available.optString(index, "")).toString()
                )
            }
            result.put("available", checkList)
        } catch (error: JSONException) {
            responder.failure("INVALID_ARGUMENT", error.message)
            return
        }
        responder.success(result)
    }
}
