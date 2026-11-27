package com.myassistant.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeCleanTest {
    private val raw = """PLAN (nothing was changed yet):
exit code: 0
> plan · deepseek-v4.1-flash-free

✗ python3 --version; python --version 2>&1 failed
Error: The user has specified a rule which prevents you from using this specific tool call. [{"permission":"*"}]

${'$'} python3 --version
Python 3.14.10

إصدار بايثون: Python 3.14.10"""

    @Test fun keepsOnlyTheAnswer() {
        assertEquals("إصدار بايثون: Python 3.14.10", OpenCode.clean(raw))
        assertFalse(OpenCode.proposesChanges(OpenCode.clean(raw)))
    }

    @Test fun spotsAPlan() {
        assertTrue(OpenCode.proposesChanges("الخطة:\n1. هعمل ملف date.py\n2. هشغّله"))
    }
}
