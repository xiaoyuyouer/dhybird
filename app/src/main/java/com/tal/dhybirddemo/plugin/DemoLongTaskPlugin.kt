package com.tal.dhybirddemo.plugin

import com.dahai.dhybird.bridge.BridgePlugin
import com.dahai.dhybird.bridge.BridgeRequest
import com.dahai.dhybird.bridge.BridgeResponder
import org.json.JSONObject

/**
 * 演示连续响应：complete=false 的中间进度不会结束 H5 Promise，最后 complete=true 才会 resolve。
 *
 * 这个插件使用默认的 BACKGROUND 线程，模拟真实项目中的耗时任务；页面 reload 或容器销毁后，
 * isCancelled 会变成 true，任务应尽快停止，不再继续产生结果。
 */
class DemoLongTaskPlugin : BridgePlugin {
    override fun name(): String = "demo.longTask"

    override fun execute(request: BridgeRequest, responder: BridgeResponder) {
        val stages = listOf(
            20 to "prepare",
            50 to "processing",
            80 to "processing"
        )

        for ((progress, stage) in stages) {
            if (responder.isCancelled) return
            responder.success(
                JSONObject()
                    .put("stage", stage)
                    .put("progress", progress),
                false
            )
            if (!sleepBetweenStages()) return
        }

        if (responder.isCancelled) return
        responder.success(
            JSONObject()
                .put("stage", "complete")
                .put("progress", 100),
            true
        )
    }

    private fun sleepBetweenStages(): Boolean = try {
        Thread.sleep(STAGE_DELAY_MS)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private companion object {
        const val STAGE_DELAY_MS = 180L
    }
}
