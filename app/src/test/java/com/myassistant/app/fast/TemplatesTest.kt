package com.myassistant.app.fast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TemplatesTest {
    @Test fun swapsTheOneChangedName() {
        assertEquals("""{"name":"محمد"}""", Templates.adapt("كلم أحمد", """{"name":"أحمد"}""", "كلم محمد", requireChange = true))
    }

    @Test fun handlesPrefixedNames() {
        assertEquals("""{"to":"محمد","message":"صباح الخير"}""",
            Templates.adapt("ابعت لأحمد صباح الخير", """{"to":"أحمد","message":"صباح الخير"}""", "ابعت لمحمد صباح الخير", requireChange = true))
    }

    @Test fun meaningMatchKeepsArgsOnlyWhenTheNameIsThere() {
        val args = """{"name":"أحمد"}"""
        assertEquals(args, Templates.adapt("اتصل بأحمد", args, "كلم احمد لو سمحت", requireChange = false))
        assertNull(Templates.adapt("اتصل بأحمد", args, "كلم محمد", requireChange = false))
    }

    @Test fun refusesWhenTwoWordsChange() {
        assertNull(Templates.adapt("كلم أحمد", """{"name":"أحمد"}""", "ابعت لمحمد", requireChange = true))
    }

    @Test fun refusesWhenTheChangedWordIsNotASlot() {
        assertNull(Templates.adapt("كلم أحمد", """{"name":"أحمد"}""", "اتصل أحمد", requireChange = true))
    }

    @Test fun adaptsTheReply() {
        assertEquals("كلمت محمد", Templates.adaptReply("كلمت أحمد", "كلم أحمد", "كلم محمد"))
    }
}
