package com.sudo.manet.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeIdentityTest {

    @Test
    fun testSha256GenerationLength() {
        val id = NodeIdentity.localNodeId
        // SHA-256 is 256 bits = 32 bytes = 64 hex characters
        assertEquals(64, id.length)
    }

    @Test
    fun testSha256Format() {
        val id = NodeIdentity.localNodeId
        val hexRegex = Regex("^[a-f0-9]{64}$")
        assert(hexRegex.matches(id)) { "ID $id is not a valid 64-char hex string" }
    }
}
