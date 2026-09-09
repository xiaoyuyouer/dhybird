package com.dahai.dhybird.bridge

/** Native 插件的最小执行契约。插件名称就是 H5 请求中的 plugin 字段。 */
interface BridgePlugin {
    /** 声明插件执行线程，避免 UI 操作和耗时任务跑错线程。 */
    enum class ExecutionThread {
        MAIN,
        BACKGROUND
    }

    /** 返回稳定的插件名，例如 common.showToast。 */
    fun name(): String

    /** 未覆写时默认使用后台线程，只有 UI 插件才需要显式声明 MAIN。 */
    fun executionThread(): ExecutionThread = ExecutionThread.BACKGROUND

    /** 处理一次 H5 请求，并通过 responder 返回成功或失败结果。 */
    fun execute(request: BridgeRequest, responder: BridgeResponder)
}
