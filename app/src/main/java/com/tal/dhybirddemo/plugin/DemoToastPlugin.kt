package com.tal.dhybirddemo.plugin

import android.app.Activity
import android.widget.Toast
import com.dahai.dhybird.bridge.BridgePlugin
import com.dahai.dhybird.bridge.BridgeRequest
import com.dahai.dhybird.bridge.BridgeResponder
import org.json.JSONException
import org.json.JSONObject

/** Demo App 自己提供的 Toast 能力，不属于通用 dhybird SDK。 */
class DemoToastPlugin(private val activity: Activity) : BridgePlugin {
    override fun name(): String = "common.showToast"

    override fun executionThread(): BridgePlugin.ExecutionThread =
        BridgePlugin.ExecutionThread.MAIN

    override fun execute(request: BridgeRequest, responder: BridgeResponder) {
        val data = request.data
        val title = data.optString("message", data.optString("title"))
        Toast.makeText(activity, title, Toast.LENGTH_SHORT).show()
        val result = JSONObject()
        try {
            result.put("title", "弹出成功")
            result.put("message", "ojbk")
        } catch (error: JSONException) {
            responder.failure("RESULT_BUILD_FAILED", error.message)
            return
        }
        responder.success(result)
    }
}
