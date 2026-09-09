package com.dahai.dhybird.bridge

import org.json.JSONObject

/** 插件向 H5 返回结果的统一出口。 */
interface BridgeResponder {
    /** 页面 reload 或容器销毁后为 true，长任务应尽快停止并释放资源。 */
    val isCancelled: Boolean

    fun success(data: JSONObject?)

    fun success(data: JSONObject?, complete: Boolean)

    fun failure(errorCode: String, errorMessage: String?)

    fun failure(errorCode: String, errorMessage: String?, complete: Boolean)
}
