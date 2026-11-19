package dev.phoneagent.access

import org.junit.Assert.assertEquals
import org.junit.Test

class RootIdentityTest {
    @Test
    fun verifiesOnlySuccessfulExactRootIdentity() {
        assertEquals(RootIdentity.Verified, parseRootIdentity(0, "0\n"))
        assertEquals(RootIdentity.Unverified(1, 0), parseRootIdentity(1, "0\n"))
        assertEquals(RootIdentity.Unverified(0, 2000), parseRootIdentity(0, "2000\n"))
        assertEquals(RootIdentity.Unverified(0, null), parseRootIdentity(0, "got uid 0\n"))
    }
}
