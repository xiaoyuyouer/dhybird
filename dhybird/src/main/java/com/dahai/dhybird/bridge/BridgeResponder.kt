package com.dahai.dhybird.bridge

import org.json.JSONObject

/** 插件向 H5 返回结果的统一出口。 */
interface BridgeResponder {
    fun success(data: JSONObject?)

    fun success(data: JSONObject?, complete: Boolean)

    fun failure(errorCode: String, errorMessage: String?)

    fun failure(errorCode: String, errorMessage: String?, complete: Boolean)
}
