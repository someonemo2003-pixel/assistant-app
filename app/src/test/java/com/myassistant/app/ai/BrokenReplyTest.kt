package com.myassistant.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokenReplyTest {
    private val router = ProviderRouter { emptyList() }

    @Test fun catchesGibberish() {
        assertTrue(router.looksBroken("لو عايز **ت)[[open_app open_app: \"Truecaller\"]** و[[do_steps do_steps]] 问题"))
        assertTrue(router.looksBroken("Truecaller مشasiبه وبيمنع التفاعل معorque الأدوات 个案 问题 越"))
    }

    @Test fun keepsNormalReplies() {
        assertFalse(router.looksBroken("تمام، لقيت ٣ جهات اتصال باسم أحمد."))
        assertFalse(router.looksBroken("Here is the list: [1] Ahmed, [2] Sara"))
    }
}
