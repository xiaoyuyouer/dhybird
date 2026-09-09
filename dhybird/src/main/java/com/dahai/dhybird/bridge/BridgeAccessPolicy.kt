package com.dahai.dhybird.bridge

/** 控制哪些 origin 可以接收 document-start Bridge 脚本和消息对象。 */
class BridgeAccessPolicy private constructor(
    val mode: Mode,
    allowedOriginRules: Set<String>
) {
    val allowedOriginRules: Set<String> = allowedOriginRules.toSet()

    enum class Mode {
        ALL_ORIGINS,
        ALLOWLIST
    }

    companion object {
        @JvmStatic
        fun allOrigins(): BridgeAccessPolicy = BridgeAccessPolicy(Mode.ALL_ORIGINS, setOf("*"))

        @JvmStatic
        fun allowlist(originRules: Collection<String>?): BridgeAccessPolicy {
            require(!originRules.isNullOrEmpty()) { "originRules must not be empty" }
            val rules = originRules.filter { it.isNotEmpty() }.toSet()
            require(rules.isNotEmpty()) { "originRules must contain a valid rule" }
            return BridgeAccessPolicy(Mode.ALLOWLIST, rules)
        }
    }
}
