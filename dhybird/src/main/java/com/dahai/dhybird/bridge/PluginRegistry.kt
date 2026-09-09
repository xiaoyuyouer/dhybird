package com.dahai.dhybird.bridge

import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 显式插件注册表。
 *
 * 通过插件名查找实例，不使用 Class.forName、JSON 配置或运行时反射。
 */
class PluginRegistry {
    private val plugins = ConcurrentHashMap<String, BridgePlugin>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor: ExecutorService = Executors.newCachedThreadPool()

    /** 注册或替换同名插件。 */
    fun register(plugin: BridgePlugin?) {
        requireNotNull(plugin) { "plugin is required" }
        val name = plugin.name().trim()
        require(name.isNotEmpty()) { "plugin name is required" }
        plugins[name] = plugin
    }

    /** 移除指定插件；不存在时不报错。 */
    fun unregister(name: String?) {
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { plugins.remove(it) }
    }

    fun contains(name: String?): Boolean =
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { plugins.containsKey(it) } == true

    fun names(): Set<String> = Collections.unmodifiableSet(plugins.keys.toSet())

    /** 根据请求中的插件名查找并按插件声明的线程执行。 */
    fun dispatch(request: BridgeRequest, responder: BridgeResponder) {
        val plugin = plugins[request.plugin]
        if (plugin == null) {
            responder.failure("PLUGIN_NOT_FOUND", "No plugin registered for ${request.plugin}")
            return
        }

        val task = Runnable {
            try {
                plugin.execute(request, responder)
            } catch (error: Exception) {
                responder.failure("PLUGIN_EXECUTION_FAILED", safeMessage(error))
            }
        }

        val executionThread = try {
            plugin.executionThread()
        } catch (error: Exception) {
            responder.failure("PLUGIN_CONFIGURATION_FAILED", safeMessage(error))
            return
        }

        try {
            when (executionThread) {
                BridgePlugin.ExecutionThread.BACKGROUND -> backgroundExecutor.execute(task)
                BridgePlugin.ExecutionThread.MAIN -> {
                    if (Looper.myLooper() == Looper.getMainLooper()) task.run()
                    else mainHandler.post(task)
                }
            }
        } catch (error: Exception) {
            responder.failure("PLUGIN_DISPATCH_FAILED", safeMessage(error))
        }
    }

    /** 停止后台线程池并清空插件，Controller 销毁时调用。 */
    fun shutdown() {
        backgroundExecutor.shutdownNow()
        plugins.clear()
    }

    private fun safeMessage(error: Exception): String =
        error.message?.takeIf { it.isNotEmpty() } ?: error.javaClass.simpleName
}
