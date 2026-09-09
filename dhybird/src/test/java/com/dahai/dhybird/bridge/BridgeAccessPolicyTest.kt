package com.dahai.dhybird.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeAccessPolicyTest {
    @Test
    fun allOriginsUsesWildcardRule() {
        val policy = BridgeAccessPolicy.allOrigins()

        assertEquals(BridgeAccessPolicy.Mode.ALL_ORIGINS, policy.mode)
        assertTrue(policy.allowedOriginRules.contains("*"))
    }

    @Test
    fun allowlistCopiesConfiguredRules() {
        val policy = BridgeAccessPolicy.allowlist(setOf("https://example.com"))

        assertEquals(BridgeAccessPolicy.Mode.ALLOWLIST, policy.mode)
        assertTrue(policy.allowedOriginRules.contains("https://example.com"))
    }
}
