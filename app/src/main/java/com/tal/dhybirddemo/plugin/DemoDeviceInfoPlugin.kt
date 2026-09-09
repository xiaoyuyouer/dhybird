package com.tal.dhybirddemo.plugin

import android.os.Build
import com.dahai.dhybird.bridge.BridgePlugin
import com.dahai.dhybird.bridge.BridgeRequest
import com.dahai.dhybird.bridge.BridgeResponder
import org.json.JSONObject

/** Example of a plugin registered by the host application. */
class DemoDeviceInfoPlugin : BridgePlugin {
    override fun name(): String = "demo.getDeviceInfo"

    override fun executionThread(): BridgePlugin.ExecutionThread =
        BridgePlugin.ExecutionThread.CALLER

    override fun execute(request: BridgeRequest, responder: BridgeResponder) {
        val result = JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("received", request.data)
        responder.success(result)
    }
}