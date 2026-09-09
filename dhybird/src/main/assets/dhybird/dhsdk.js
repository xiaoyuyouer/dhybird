(function (global) {
  'use strict';

  /**
   * dhsdk v2：只保留 Promise API 的 Android Bridge SDK。
   *
   * 这份文件故意保持为一个可以直接放进 assets 的单文件运行时，内部按职责拆成：
   *
   *   Transport    负责把消息发给 Android WebMessageListener
   *   EventBus     负责 Native -> H5 的业务事件
   *   RequestStore 负责请求 Promise、连续响应和超时
   *   BridgeClient 负责 ready、排队、发送和销毁
   *   API          负责把业务方法映射到 Native 插件
   *
   * v2 不再提供旧版 callback、_dhbridge、DHBridgeReady 或 JavascriptInterface 回退。
   * 页面只需要使用 dhsdk.ready()、dhsdk.invoke()、dhsdk.on() 和内置 Promise API。
   */

  var SDK_VERSION = '2.0.0';
  var DEFAULT_TIMEOUT_MS = 15000;
  var MAX_PENDING_REQUESTS = 100;

  var State = {
    CREATED: 'CREATED',
    WAITING_TRANSPORT: 'WAITING_TRANSPORT',
    READY: 'READY',
    FAILED: 'FAILED',
    DESTROYED: 'DESTROYED'
  };

  /** 统一生成结构化错误，业务可以直接读取 errorCode/errorMessage。 */
  function createError(code, message) {
    return {
      status: 0,
      complete: 1,
      errorCode: code,
      errorMessage: message || code
    };
  }

  /** Native 返回字符串时解析 JSON；已经是对象时直接复用。 */
  function parseJson(message) {
    if (typeof message !== 'string') {
      return message;
    }
    try {
      return JSON.parse(message);
    } catch (error) {
      return null;
    }
  }

  /**
   * 校验 Native 回包的路由字段。
   * callbackId 是协议字段名，表示一次 Promise 请求的唯一 ID，不是旧版回调 API。
   */
  function normalizeResponse(message) {
    var response = parseJson(message);
    if (!response || typeof response !== 'object') {
      return null;
    }
    if (typeof response.callbackId !== 'string') {
      return null;
    }
    return response;
  }

  /**
   * v2 唯一的 Android 传输通道。
   * Native 会在页面加载前注册 WebMessageListener，因此这里不需要旧版注入回退。
   */
  function createTransport() {
    if (global.android && typeof global.android.postMessage === 'function') {
      return {
        name: 'android-web-message',
        send: function (payload) {
          global.android.postMessage(payload);
        }
      };
    }
    return null;
  }

  /** 监听器出错时异步抛出，避免阻断同一事件的其他监听器。 */
  function reportListenerError(error) {
    global.setTimeout(function () {
      throw error;
    }, 0);
  }

  /** Bridge 未 ready 时暂存请求，并限制队列长度。 */
  function PendingQueue(maxSize) {
    this.maxSize = maxSize;
    this.items = [];
  }

  PendingQueue.prototype.push = function (item) {
    var dropped = null;
    if (this.items.length >= this.maxSize) {
      dropped = this.items.shift();
    }
    this.items.push(item);
    return dropped;
  };

  PendingQueue.prototype.shift = function () {
    return this.items.shift();
  };

  PendingQueue.prototype.isEmpty = function () {
    return this.items.length === 0;
  };

  PendingQueue.prototype.clear = function () {
    this.items.length = 0;
  };

  /** Native -> H5 事件总线，例如 refreshToken。 */
  function EventBus() {
    this.listeners = Object.create(null);
  }

  /** 注册事件监听，返回取消监听函数。 */
  EventBus.prototype.on = function (eventName, listener) {
    if (!eventName || typeof listener !== 'function') {
      throw new TypeError('eventName and listener are required');
    }
    if (!this.listeners[eventName]) {
      this.listeners[eventName] = [];
    }
    this.listeners[eventName].push(listener);
    var listeners = this.listeners;
    return function () {
      listeners[eventName] = (listeners[eventName] || []).filter(function (item) {
        return item !== listener;
      });
    };
  };

  /** 向同名事件的所有监听器广播数据。 */
  EventBus.prototype.emit = function (eventName, value) {
    var listeners = (this.listeners[eventName] || []).slice();
    listeners.forEach(function (listener) {
      try {
        listener(value);
      } catch (error) {
        reportListenerError(error);
      }
    });
  };

  EventBus.prototype.clear = function () {
    this.listeners = Object.create(null);
  };

  /**
   * 保存一次请求的 Promise、超时和连续响应状态。
   *
   * status=1 且 complete=0 表示“成功但还没结束”，Promise 会继续等待；
   * complete=1 时才 resolve。失败响应统一作为终态 reject。
   */
  function RequestStore(timeoutMs) {
    this.timeoutMs = timeoutMs;
    this.records = Object.create(null);
  }

  RequestStore.prototype.create = function (requestId) {
    var resolvePromise;
    var rejectPromise;
    var promise = new Promise(function (resolve, reject) {
      resolvePromise = resolve;
      rejectPromise = reject;
    });
    var record = {
      timer: null,
      resolve: resolvePromise,
      reject: rejectPromise
    };
    this.records[requestId] = record;
    this.armTimeout(requestId, record);
    return promise;
  };

  RequestStore.prototype.armTimeout = function (requestId, record) {
    var self = this;
    if (record.timer) {
      global.clearTimeout(record.timer);
    }
    record.timer = global.setTimeout(function () {
      self.fail(requestId, createError('TIMEOUT', 'Native bridge response timed out'));
    }, this.timeoutMs);
  };

  /** 处理 Native 回包，并在最终响应到达后结束 Promise。 */
  RequestStore.prototype.handle = function (response) {
    var record = this.records[response.callbackId];
    if (!record) {
      return;
    }

    this.armTimeout(response.callbackId, record);
    if (response.status !== 1) {
      record.reject(response);
      this.remove(response.callbackId);
      return;
    }

    if (response.complete !== 0) {
      record.resolve(response);
      this.remove(response.callbackId);
    }
  };

  /** 主动结束请求，例如超时、队列溢出、传输失败或页面销毁。 */
  RequestStore.prototype.fail = function (requestId, error) {
    var record = this.records[requestId];
    if (!record) {
      return;
    }
    record.reject(error);
    this.remove(requestId);
  };

  RequestStore.prototype.remove = function (requestId) {
    var record = this.records[requestId];
    if (!record) {
      return;
    }
    if (record.timer) {
      global.clearTimeout(record.timer);
    }
    delete this.records[requestId];
  };

  RequestStore.prototype.clear = function (error) {
    var self = this;
    Object.keys(this.records).forEach(function (requestId) {
      self.fail(requestId, error);
    });
  };

  /**
   * Bridge 生命周期和请求调度器。
   *
   * CREATED -> WAITING_TRANSPORT -> READY
   * READY -> FAILED / DESTROYED
   *
   * 页面脚本可以在 React mounted、DOMContentLoaded 甚至 head 阶段调用；
   * transport 尚未 ready 时，请求会按 FIFO 顺序保存在 pending 队列中。
   */
  function BridgeClient(options) {
    this.global = options.global;
    this.state = State.CREATED;
    this.sequence = 1;
    this.transport = null;
    this.pending = new PendingQueue(options.maxPendingRequests);
    this.events = new EventBus();
    this.requests = new RequestStore(options.timeoutMs);
    this.readyWaiters = [];
    this.readyTimer = null;
    this.flushing = false;
  }

  BridgeClient.prototype.nextRequestId = function () {
    return 'req_' + (this.sequence++) + '_' + Date.now();
  };

  /** 在当前页面文档中建立 transport，并 flush 首屏排队请求。 */
  BridgeClient.prototype.activate = function () {
    if (this.state === State.DESTROYED || this.state === State.FAILED) {
      return false;
    }

    this.transport = createTransport();
    if (!this.transport) {
      this.state = State.WAITING_TRANSPORT;
      this.startReadyTimeout();
      return false;
    }

    if (this.state !== State.READY) {
      this.state = State.READY;
      if (this.readyTimer) {
        this.global.clearTimeout(this.readyTimer);
        this.readyTimer = null;
      }
      var waiters = this.readyWaiters.splice(0);
      waiters.forEach(function (waiter) {
        waiter.resolve();
      });
    }
    this.flush();
    return true;
  };

  /** transport 不可用时，让 ready 和排队请求在固定时间后明确失败。 */
  BridgeClient.prototype.startReadyTimeout = function () {
    var self = this;
    if (this.readyTimer) {
      return;
    }
    this.readyTimer = this.global.setTimeout(function () {
      if (self.state === State.READY || self.state === State.DESTROYED) {
        return;
      }
      var error = createError('TRANSPORT_UNAVAILABLE', 'Android WebMessageListener is unavailable');
      self.state = State.FAILED;
      self.readyTimer = null;
      self.readyWaiters.splice(0).forEach(function (waiter) {
        waiter.reject(error);
      });
      self.requests.clear(error);
      self.pending.clear();
    }, this.requests.timeoutMs);
  };

  /** 等待当前文档的 Android Bridge ready。 */
  BridgeClient.prototype.ready = function () {
    var self = this;
    if (this.state === State.READY) {
      return Promise.resolve();
    }
    if (this.state === State.FAILED) {
      return Promise.reject(createError('TRANSPORT_UNAVAILABLE', 'Android WebMessageListener is unavailable'));
    }
    if (this.state === State.DESTROYED) {
      return Promise.reject(createError('BRIDGE_DESTROYED', 'Native bridge has been destroyed'));
    }
    var promise = new Promise(function (resolve, reject) {
      self.readyWaiters.push({ resolve: resolve, reject: reject });
    });
    this.startReadyTimeout();
    return promise;
  };

  /** 创建请求；如果 transport 尚未 ready，就先进入 FIFO 队列。 */
  BridgeClient.prototype.invoke = function (plugin, data) {
    if (!plugin || typeof plugin !== 'string') {
      return Promise.reject(createError('INVALID_REQUEST', 'plugin is required'));
    }
    if (this.state === State.FAILED) {
      return Promise.reject(createError('TRANSPORT_UNAVAILABLE', 'Android WebMessageListener is unavailable'));
    }
    if (this.state === State.DESTROYED) {
      return Promise.reject(createError('BRIDGE_DESTROYED', 'Native bridge has been destroyed'));
    }

    var request = {
      callbackId: this.nextRequestId(),
      plugin: plugin,
      data: data || {},
      sdkVersion: SDK_VERSION
    };
    var promise = this.requests.create(request.callbackId);
    var dropped = this.pending.push({
      id: request.callbackId,
      message: request
    });
    if (dropped) {
      this.requests.fail(dropped.id, createError('QUEUE_OVERFLOW', 'Bridge queue is full'));
    }
    this.activate();
    this.flush();
    return promise.then(function (response) {
      return response.data;
    });
  };

  /** Bridge ready 后按 FIFO 顺序发送请求。 */
  BridgeClient.prototype.flush = function () {
    var self = this;
    if (this.state !== State.READY || !this.transport || this.flushing) {
      return;
    }
    this.flushing = true;
    try {
      while (!this.pending.isEmpty()) {
        var item = this.pending.shift();
        try {
          this.transport.send(JSON.stringify(item.message));
        } catch (error) {
          self.requests.fail(item.id, createError('TRANSPORT_UNAVAILABLE', error.message));
        }
      }
    } finally {
      this.flushing = false;
    }
  };

  /** Native 通过 evaluateJavascript 回传普通请求结果。 */
  BridgeClient.prototype.handleResponse = function (message) {
    var response = normalizeResponse(message);
    if (response) {
      this.requests.handle(response);
    }
  };

  /** Native 通过 evaluateJavascript 回传业务事件。 */
  BridgeClient.prototype.handleEvent = function (message) {
    var response = normalizeResponse(message);
    if (response) {
      this.events.emit(response.callbackId, response.data);
    }
  };

  /** 页面销毁时拒绝所有未完成 Promise，并清理监听器。 */
  BridgeClient.prototype.destroy = function () {
    if (this.state === State.DESTROYED) {
      return;
    }
    this.state = State.DESTROYED;
    if (this.readyTimer) {
      this.global.clearTimeout(this.readyTimer);
      this.readyTimer = null;
    }
    var error = createError('BRIDGE_DESTROYED', 'Native bridge has been destroyed');
    this.readyWaiters.splice(0).forEach(function (waiter) {
      waiter.reject(error);
    });
    this.requests.clear(error);
    this.pending.clear();
    this.events.clear();
  };

  var client = new BridgeClient({
    global: global,
    timeoutMs: DEFAULT_TIMEOUT_MS,
    maxPendingRequests: MAX_PENDING_REQUESTS
  });

  /** 注册 Native 需要调用的内部函数，并尽量设为不可枚举。 */
  function exposeInternal(name, value) {
    try {
      Object.defineProperty(global, name, {
        configurable: true,
        value: value,
        writable: false
      });
    } catch (error) {
      global[name] = value;
    }
  }

  exposeInternal('__dhybirdHandleResponse', function (message) {
    client.handleResponse(message);
  });
  exposeInternal('__dhybirdHandleEvent', function (message) {
    client.handleEvent(message);
  });

  /**
   * 公开 SDK 只提供通用 Bridge 能力，不内置任何业务插件名称。
   *
   * 插件由宿主 App 注册，H5 通过 invoke 使用：
   *
   * 推荐用法：
   *
   *   await dhsdk.ready();
   *   const data = await dhsdk.invoke('common.showToast', { message: 'hello' });
   *   const device = await dhsdk.invoke('demo.getDeviceInfo', {});
   *   const unsubscribe = dhsdk.on('refreshToken', handler);
   */
  global.dhsdk = {
    ready: function () {
      return client.ready();
    },
    on: function (eventName, listener) {
      return client.events.on(eventName, listener);
    },
    invoke: function (plugin, data) {
      return client.invoke(plugin, data);
    }
  };

  // SDK 脚本在 head 阶段执行时，首个 tick 就会尝试连接 Native transport。
  // 即使业务立刻调用 API，也会先入队，等 transport 建立后按顺序发送。
  global.setTimeout(function () {
    client.activate();
  }, 0);
})(window);
