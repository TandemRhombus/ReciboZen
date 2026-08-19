package app.recibozen.android

import app.recibozen.android.core.model.EntitlementTier
import app.recibozen.android.data.repository.SessionJsonCodec
import app.recibozen.android.demo.repository.ReciboZenSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionEntitlementSecurityTest {
    @Test fun ignoresPremiumTierInjectedIntoMutableSessionJson() {
        val decoded = SessionJsonCodec.decode("""{"entitlementTier":"FAMILY"}""")

        assertEquals(EntitlementTier.FREE, decoded.entitlementTier)
    }

    @Test fun neverSerializesPremiumTierIntoMutableSessionJson() {
        val encoded = SessionJsonCodec.encode(
            ReciboZenSessionState(entitlementTier = EntitlementTier.FAMILY),
        )

        assertFalse(encoded.contains("entitlementTier"))
        assertFalse(encoded.contains("FAMILY"))
    }
}
