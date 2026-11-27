package com.myassistant.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandsTest {
    @Test fun suggestsWhileTyping() {
        assertEquals(2, SlashCommands.matching("/").size)
        assertEquals("/blockapps", SlashCommands.matching("/blo").first().command)
        assertTrue(SlashCommands.matching("/xyz").isEmpty())
        assertTrue(SlashCommands.matching("ازيك").isEmpty())
        assertTrue(SlashCommands.matching("/blockapps tiktok").isEmpty())
    }
}
