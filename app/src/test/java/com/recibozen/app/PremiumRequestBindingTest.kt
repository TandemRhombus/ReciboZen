package com.recibozen.app

import com.recibozen.app.feature.premium.requestHash
import com.recibozen.app.feature.premium.subjectHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumRequestBindingTest {
    @Test fun requestHashBindsSubjectProductAndPurchaseToken() {
        val base = requestHash("install-a", "recibozen_plus_monthly", "purchase-token")
        assertNotEquals(base, requestHash("install-b", "recibozen_plus_monthly", "purchase-token"))
        assertNotEquals(base, requestHash("install-a", "recibozen_family_monthly", "purchase-token"))
        assertNotEquals(base, requestHash("install-a", "recibozen_plus_monthly", "other-token"))
    }

    @Test fun obfuscatedAccountIdIsStableAndWithinPlayLimit() {
        val hash = subjectHash("a-stable-install-subject")
        assertEquals(hash, subjectHash("a-stable-install-subject"))
        assertTrue(hash.length <= 64)
    }
}
