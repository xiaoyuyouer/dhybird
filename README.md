# dhybird Hybrid Bridge

一个基于 Android WebView 的全新 Hybrid 容器和异步 JavaScript Bridge 框架。

核心原则：

- SDK 只负责 WebView、Bridge 传输、生命周期和插件调度；
- SDK 不内置业务 API，也不内置 Toast、登录、分享等业务插件；
- 宿主 App 显式注册自己拥有的 Native 能力；
- H5 通过 Promise API 调用插件，不直接依赖 Native 实现细节；
- Native 通信使用 WebMessageListener；
- 宿主 App 通过显式注册提供业务能力。

## 目录

- [项目结构](#项目结构)
- [架构设计](#架构设计)
- [Bridge 通信时序](#bridge-通信时序)
- [快速开始](#快速开始)
- [H5 API](#h5-api)
- [开发 Native 插件](#开发-native-插件)
- [配置说明](#配置说明)
- [生命周期和销毁](#生命周期和销毁)
- [安全策略](#安全策略)
- [Demo 工程](#demo-工程)
- [构建与测试](#构建与测试)
- [已知限制](#已知限制)

## 项目结构

~~~text
dhybird/
├── dhybird/                                  # Android Library，框架本体
│   └── src/main/
│       ├── assets/dhybird/dhsdk.js            # 通用 H5 Bridge Runtime
│       └── java/com/dahai/dhybird/
│           ├── HybridConfig.kt                # 容器配置
│           ├── HybridController.kt            # 容器生命周期和 WebView 管理
│           ├── HybridCallbacks.kt             # 宿主页面回调
│           ├── bridge/
│           │   ├── BridgeAccessPolicy.kt      # Origin 访问策略
│           │   ├── BridgePlugin.kt            # 插件接口
│           │   ├── BridgeRequest.kt            # 请求解析
│           │   ├── BridgeResponder.kt          # 结果返回
│           │   ├── BridgeRuntime.kt             # Bridge 状态和 Native 回包
│           │   └── PluginRegistry.kt            # 显式插件注册表
│           └── core/system/                    # WebView、下载、全屏等系统适配
│
└── app/                                      # Demo 宿主 App
    └── src/main/
        ├── assets/dhybird/
        │   ├── react-demo.html                # React 示例页面
        │   └── vue-demo.html                  # Vue 示例页面
        └── java/com/tal/dhybirddemo/
            ├── MainActivity.kt                 # Demo 容器初始化
            └── plugin/                         # Demo 自己提供的插件
                ├── DemoToastPlugin.kt
                ├── DemoCheckAvailablePlugin.kt
                └── DemoLongTaskPlugin.kt
~~~

dhsdk.js 位于 Library 的 assets 中，会随 dhybird AAR 一起打包。宿主 App 不需要复制 SDK 文件，只需要提供自己的 H5 页面和插件实现。

## 架构设计

### 分层职责

~~~text
H5 页面
  │
  │  dhsdk.ready / dhsdk.invoke / dhsdk.on
  ▼
dhsdk.js
  ├── Promise 请求管理
  ├── 首屏调用队列
  ├── 超时和错误处理
  └── android.postMessage
  │
  ▼
Android WebMessageListener
  │
  ▼
BridgeRuntime
  ├── 后台串行解析 BridgeRequest
  ├── 管理当前文档状态
  ├── 取消 reload/destroy 后的未完成请求
  └── 通过 evaluateJavascript 返回结果/事件
  │
  ▼
PluginRegistry
  │
  ▼
宿主 App 注册的 BridgePlugin
~~~

### SDK 和宿主 App 的边界

dhybird SDK 只提供机制：

- 创建和配置 Android WebView；
- 在页面加载前注册 WebMessageListener；
- 接收、解析和分发 H5 请求；
- 管理插件执行线程；
- 将结果异步返回给 H5；
- 处理页面导航、下载、视频全屏和销毁；
- 提供 dhsdk.js 通用运行时。

宿主 App 负责能力：

- 登录、支付、分享、扫码、相册、定位等业务实现；
- 业务权限申请；
- Activity、Fragment、ViewModel 或业务服务的协调；
- 根据业务环境注册允许 H5 使用的插件。

SDK 不会自动注册任何具体业务插件。当前 Demo 里的 Toast、能力检查和设备信息，全部由 app 工程显式注册。

### 关键类职责

| 类 | 职责 |
| --- | --- |
| HybridConfig | 保存 URL、容器、Cookie、UA、调试、Origin 策略等不可变配置 |
| HybridController | 对外提供 start、navigate、生命周期和插件注册 API |
| BridgeRuntime | 管理 Native 请求入口、页面状态、响应队列和 JS 回包 |
| PluginRegistry | 根据插件名查找实例并调度执行线程 |
| BridgePlugin | 宿主 App 实现 Native 能力的接口 |
| BridgeRequest | 校验并解析 H5 请求 JSON |
| BridgeResponder | 统一返回成功、失败和连续结果 |
| HybridWebViewClient | 页面开始/结束、错误和外链处理 |
| DNativeWebChromeClient | 标题、文件选择和视频全屏处理 |
| dhsdk.js | H5 Promise、请求队列、事件订阅和 Android transport |

## Bridge 通信时序

### Native 初始化

HybridController.start() 的顺序如下：

1. 在主线程创建 WebView；
2. 应用 WebView 配置；
3. 创建 BridgeRuntime；
4. 安装 WebViewClient 和 WebChromeClient；
5. 注册 WebMessageListener；
6. 将 WebView 添加到宿主容器；
7. 加载目标 URL。

Bridge transport 在 loadUrl() 之前安装，因此页面脚本不需要等待 onPageFinished() 才能开始调用。

### H5 首屏调用

~~~text
HTML head 加载 dhsdk.js
        │
        ├── transport 已可用：直接发送
        │
        └── transport 暂未可用：请求进入 FIFO 队列
                         │
                         ▼
                   Bridge ready
                         │
                         ▼
                 按顺序 flush 请求
~~~

H5 可以在以下阶段调用：

- HTML head 脚本；
- DOMContentLoaded；
- Vue mounted；
- React useEffect；
- 其他框架的首屏初始化阶段。

### BridgeRuntime 状态

~~~text
CREATED
   │ onPageStarted
   ▼
DOCUMENT_LOADING
   │ onPageFinished
   ▼
BRIDGE_READY
   │ destroy
   ▼
DESTROYED
~~~

Native 响应在文档尚未完成时会暂存，页面进入 BRIDGE_READY 后再发送。页面销毁时会丢弃响应队列，并拒绝所有未完成的 H5 Promise。

## 快速开始

### 1. 创建配置

~~~kotlin
val config = HybridConfig.Builder(this)
    .container(containerView)
    .url("https://example.com/hybrid")
    .cookies(mapOf("token" to token))
    .debug(BuildConfig.DEBUG)
    .bridgeAccessPolicy(
        BridgeAccessPolicy.allowlist(
            setOf("https://example.com")
        )
    )
    .build()
~~~

### 2. 创建 Controller 并注册插件

~~~kotlin
val controller = HybridController(config, callbacks)
controller.registerPlugin(DeviceInfoPlugin())
controller.start()
~~~

所有插件都必须在 start() 之前注册。注册表使用宿主 App 显式提供的插件实例和名称。

### 3. 转发生命周期

~~~kotlin
override fun onResume() {
    super.onResume()
    controller.onResume()
}

override fun onPause() {
    controller.onPause()
    super.onPause()
}

override fun onDestroy() {
    controller.destroy()
    super.onDestroy()
}
~~~

### 4. H5 页面加载 SDK

如果 H5 页面和 SDK 位于同一个 Android assets 路径，可以直接引用：

~~~html
<script src="./dhsdk.js"></script>
~~~

在宿主 App 中，dhsdk.js 由 Library AAR 自动合并到 assets/dhybird/dhsdk.js。

## H5 API

dhsdk.js 对外只提供三个通用入口：

~~~javascript
dhsdk.ready()
dhsdk.on(eventName, listener)
dhsdk.invoke(pluginName, data)
~~~

### dhsdk.ready()

等待当前页面的 Native Bridge 可用，返回 Promise：

~~~javascript
try {
  await dhsdk.ready();
  console.log('Bridge Ready');
} catch (error) {
  console.error(error.errorCode, error.errorMessage);
}
~~~

ready() 返回一个 Promise<void>，只表示当前页面的 Bridge 已经可以使用。

### dhsdk.invoke(pluginName, data)

调用宿主 App 注册的插件：

~~~javascript
const result = await dhsdk.invoke('demo.getDeviceInfo', {
  from: 'react-page'
});

console.log(result);
~~~

调用失败时 Promise 会 reject：

~~~javascript
try {
  const data = await dhsdk.invoke('camera.scan', { mode: 'qr' });
} catch (error) {
  // error.errorCode，例如 PLUGIN_NOT_FOUND、PLUGIN_EXECUTION_FAILED、TIMEOUT
  console.error(error.errorCode, error.errorMessage);
}
~~~

如果 Native 需要分阶段返回结果，可以在 Promise 上注册 `onProgress`。中间响应不会结束 Promise，只有 `complete=true` 的最终响应会 resolve：

~~~javascript
const task = dhsdk.invoke('demo.longTask', { from: 'react-page' });

task.onProgress(progress => {
  console.log('任务进度', progress.stage, progress.progress);
});

const finalResult = await task;
console.log('任务完成', finalResult);
~~~

`onProgress` 只接收 Native 使用 `responder.success(data, false)` 返回的数据；普通一次性调用不需要注册进度监听。

SDK 不提供 dhsdk.showToast()、dhsdk.login() 等业务快捷方法。即使某个宿主 App 注册了 common.showToast，H5 也应该显式调用：

~~~javascript
await dhsdk.invoke('common.showToast', {
  message: 'hello'
});
~~~

例如，Demo 的 `common.checkAvailable` 会返回带插件名的结果，避免只能依赖数组下标判断：

~~~javascript
const result = await dhsdk.invoke('common.checkAvailable', {
  available: ['common.showToast', 'common.checkAvailable', 'user.getToken']
});

// {
//   plugins: [
//     { name: 'common.showToast', available: true },
//     { name: 'common.checkAvailable', available: true },
//     { name: 'user.getToken', available: false }
//   ]
// }
~~~

### dhsdk.on(eventName, listener)

订阅 Native 主动推送的事件，并得到取消订阅函数：

~~~javascript
const unsubscribe = dhsdk.on('refreshToken', data => {
  console.log('token refreshed', data);
});

// 页面卸载或组件销毁时调用
unsubscribe();
~~~

React 示例：

~~~javascript
useEffect(() => {
  const unsubscribe = dhsdk.on('refreshToken', data => {
    setToken(data.token);
  });

  return unsubscribe;
}, []);
~~~

## 开发 Native 插件

### 插件接口

~~~kotlin
interface BridgePlugin {
    fun name(): String

    fun executionThread(): ExecutionThread = ExecutionThread.BACKGROUND

    fun execute(
        request: BridgeRequest,
        responder: BridgeResponder
    )
}
~~~

### 完整示例

~~~kotlin
class DeviceInfoPlugin : BridgePlugin {
    override fun name() = "demo.getDeviceInfo"

    override fun execute(
        request: BridgeRequest,
        responder: BridgeResponder
    ) {
        val result = JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("received", request.data)

        responder.success(result)
    }
}
~~~

插件执行完成后，通过 `BridgeResponder` 返回结果。页面 reload 或容器销毁时，`responder.isCancelled` 会变成 `true`；长任务应在循环或阶段边界检查它：

~~~kotlin
override fun execute(request: BridgeRequest, responder: BridgeResponder) {
    for (step in loadSteps()) {
        if (responder.isCancelled) return
        responder.success(JSONObject().put("step", step), complete = false)
    }

    if (!responder.isCancelled) {
        responder.success(JSONObject().put("finished", true))
    }
}
~~~

### 注册插件

~~~kotlin
val controller = HybridController(config, callbacks)
controller.registerPlugin(DeviceInfoPlugin())
controller.start()
~~~

插件名必须稳定、唯一，建议使用命名空间：

~~~text
account.getProfile
camera.scan
payment.start
demo.getDeviceInfo
~~~

同名插件重复注册时，后注册的实例会覆盖之前的实例。

### 执行线程

~~~kotlin
enum class ExecutionThread {
    MAIN,
    BACKGROUND
}
~~~

| 线程 | 适用场景 |
| --- | --- |
| MAIN | Toast、Activity、View、权限请求和其他 UI 操作 |
| BACKGROUND | 网络、文件、数据库和耗时计算 |

默认线程是 BACKGROUND。只有需要操作 Toast、Activity、View 或发起权限请求的插件才声明 MAIN。WebMessageListener 的入口只负责接收消息，BridgeRuntime 会在后台串行队列中完成 JSON 解析和插件分发；插件仍然必须自己保证线程安全。

### 返回成功和失败

~~~kotlin
responder.success(JSONObject().put("id", "123"))
~~~

~~~kotlin
responder.failure(
    "INVALID_ARGUMENT",
    "id is required"
)
~~~

H5 收到的成功结果是 Promise 的返回值；失败结果包含：

~~~javascript
{
  status: 0,
  complete: 1,
  errorCode: 'INVALID_ARGUMENT',
  errorMessage: 'id is required'
}
~~~

如果插件抛出未处理异常，注册表会统一转换为 PLUGIN_EXECUTION_FAILED。

### 连续响应

需要分阶段返回结果时，可以使用 complete = false：

~~~kotlin
responder.success(progressJson, false)
responder.success(finalJson, true)
~~~

H5 Promise 在最终 complete = true 的响应到达后 resolve。普通插件只需要调用不带 complete 参数的 success() 或 failure()。

Demo 中的 `demo.longTask` 是一个完整示例：它在后台线程发送多次 `complete=false` 进度，最后发送 `complete=true` 的终态结果。React 和 Vue 页面都提供了“连续响应任务”按钮。

## 配置说明

HybridConfig.Builder 支持以下配置：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| container | 必填 | WebView 的父容器 |
| url | 必填 | 首次加载的 URL |
| cookies | 空 | 加载 URL 前写入 CookieManager |
| debug | false | 只有 Debug 构建且显式开启时才打开 WebView 调试 |
| userAgentSuffix | 空 | 追加 User-Agent 后缀 |
| bridgeAccessPolicy | allOrigins() | WebMessageListener 的 Origin 规则 |
| allowFileAccess | false | 是否允许 WebView 文件访问 |
| allowMixedContent | false | 是否允许 HTTPS 页面加载 HTTP 内容 |
| cacheMode | LOAD_DEFAULT | WebView 缓存策略 |

建议生产环境至少显式设置：

~~~kotlin
val config = HybridConfig.Builder(this)
    .container(containerView)
    .url("https://example.com/hybrid")
    .bridgeAccessPolicy(
        BridgeAccessPolicy.allowlist(
            setOf("https://example.com")
        )
    )
    .build()
~~~

## 生命周期和销毁

### 页面导航

~~~kotlin
controller.navigate("https://example.com/next")

if (controller.canGoBack()) {
    controller.goBack()
}
~~~

页面 reload、redirect 或前进后退时，Native 会重新维护当前文档的 Bridge 状态。H5 页面每次都应该重新加载 dhsdk.js，不要把旧页面的 Promise 或事件订阅带到新文档。

### Native 主动发事件

~~~kotlin
controller.sendEventMessageToJS(
    "refreshToken",
    JSONObject().put("token", token)
)

// 无数据事件可以省略第二个参数：
// controller.sendEventMessageToJS("logout")
~~~

事件使用独立的消息结构，不复用请求响应的 callbackId：

~~~json
{
  "type": "event",
  "eventName": "refreshToken",
  "data": {
    "token": "..."
  }
}
~~~

### 销毁流程

controller.destroy() 会执行：

1. 停止 WebView 加载；
2. 销毁 Bridge Runtime；
3. 拒绝未完成 H5 Promise；
4. 清理事件监听和请求队列；
5. 移除 WebMessageListener；
6. 从父容器移除 WebView；
7. 清理 WebView Client；
8. 销毁 WebView。

宿主 Activity 必须在 onDestroy() 中调用 destroy()。

## 安全策略

### WebMessageListener

Bridge 使用 AndroidX WebViewCompat.addWebMessageListener()：

- transport 在 loadUrl() 前注册；
- 不使用 addJavascriptInterface；
- 不在 Bridge 入口直接操作 UI；
- 插件执行线程由 ExecutionThread 明确声明；
- Origin 规则由 BridgeAccessPolicy 控制。

### Origin 白名单

默认配置是 BridgeAccessPolicy.allOrigins()，方便本地 Demo 使用。这个配置会让所有被 WebView 加载的页面都可能接触 Bridge，生产环境不建议使用。

生产环境使用：

~~~kotlin
BridgeAccessPolicy.allowlist(
    setOf(
        "https://example.com",
        "https://m.example.com"
    )
)
~~~

白名单只解决 Origin 范围问题，不能替代登录态校验、业务权限校验和插件参数校验。每个敏感插件仍然需要在 Native 内部验证用户身份和请求参数。

### WebView 配置建议

- 默认关闭混合内容；
- 默认关闭不必要的文件访问；
- 生产包不要开启 WebView 调试；
- 不要向不可信页面注册敏感插件；
- 不要把支付、登录凭证、设备标识等敏感数据无条件返回给 H5；
- 插件失败信息不要暴露内部堆栈和密钥。

## Demo 工程

Demo 宿主首页提供三个按钮：

- `React Demo`：加载 `react-demo.html`；
- `Vue Demo`：加载 `vue-demo.html`；
- `事件`：向当前页面发送一次 `refreshToken` Native 事件。

React 和 Vue Demo 覆盖相同的 Bridge 能力：

- 框架组件挂载阶段调用 Bridge，验证首屏调用队列；
- `dhsdk.ready()` 生命周期检查；
- `dhsdk.invoke()` Promise 插件调用；
- Toast、插件能力查询、设备信息和连续响应插件；
- `onProgress()` 连续响应进度监听；
- 未知插件和非法参数的失败场景；
- `dhsdk.on()` Native 事件订阅、取消订阅和事件次数统计；
- 请求日志和插件调用结果。

Demo 插件注册位置：

~~~text
app/src/main/java/com/tal/dhybirddemo/MainActivity.kt
~~~

Demo H5 页面位置：

~~~text
app/src/main/assets/dhybird/react-demo.html
app/src/main/assets/dhybird/vue-demo.html
~~~

React 页面通过 CDN 加载 React 18 UMD 文件，Vue 页面通过 CDN 加载 Vue 3 UMD 文件，因此模拟器或真机需要访问 unpkg.com。如果需要完全离线运行，可以将对应的 UMD 文件放入 Demo assets 并改成本地引用。

## 构建与测试

使用仓库内 Gradle Wrapper：

~~~bash
./gradlew assembleDebug
~~~

运行 JVM 单元测试：

~~~bash
./gradlew test
~~~

运行 Android Lint：

~~~bash
./gradlew lint
~~~

连接模拟器或真机后运行 Instrumentation Test：

~~~bash
./gradlew connectedDebugAndroidTest
~~~

一次执行全部检查：

~~~bash
./gradlew assembleDebug test lint connectedDebugAndroidTest
~~~

Debug APK 输出路径：

~~~text
app/build/outputs/apk/debug/app-debug.apk
~~~

Debug AAR 输出路径：

~~~text
dhybird/build/outputs/aar/dhybird-debug.aar
~~~

当前工程已经验证：

- Android 和 Library 全量使用 Kotlin；
- minSdkVersion 为 26；
- SDK AAR 包含 assets/dhybird/dhsdk.js；
- Bridge 首屏队列、Promise、插件注册、生命周期和错误路径可构建验证。

## 已知限制

- 运行环境必须支持 Android WebView 的 WebMessageListener 能力；
- SDK 不负责业务权限、登录态和敏感数据授权；
- 默认 allOrigins() 只适合本地 Demo，生产环境必须配置 Origin 白名单；
- React Demo 的 React 运行时依赖网络 CDN；
- H5 与 Native 必须共同约定插件名、参数结构和返回结构，SDK 不会自动生成业务契约；
- BridgeResponder 支持连续响应，但普通业务应优先使用一次成功或失败的终态响应；长任务必须检查 `isCancelled`。
