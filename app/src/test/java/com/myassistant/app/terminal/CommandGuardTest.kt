package com.myassistant.app.terminal

import com.myassistant.app.terminal.CommandGuard.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandGuardTest {
    private fun lvl(c: String) = CommandGuard.check(c).level

    @Test fun readOnlyRunsDirectly() {
        assertEquals(Level.READ, lvl("ls -la"))
        assertEquals(Level.READ, lvl("cat ~/.bashrc | grep PATH"))
        assertEquals(Level.READ, lvl("git status && git log -3"))
        assertEquals(Level.READ, lvl("pkg list-installed 2>/dev/null"))
        assertEquals(Level.READ, lvl("python --version"))
    }

    @Test fun changesNeedApproval() {
        assertEquals(Level.CHANGE, lvl("pkg install python"))
        assertEquals(Level.CHANGE, lvl("rm -rf ~/old"))
        assertEquals(Level.CHANGE, lvl("echo hi > file.txt"))
        assertEquals(Level.CHANGE, lvl("find . -name '*.tmp' -delete"))
        assertEquals(Level.CHANGE, lvl("sed -i s/a/b/ x.txt"))
        assertEquals(Level.CHANGE, lvl("ls $(rm x)"))
        assertEquals(Level.CHANGE, lvl("python script.py"))
        assertEquals(Level.CHANGE, lvl("git push"))
    }

    @Test fun rootIsAlwaysRefused() {
        assertEquals(Level.ROOT, lvl("su -c id"))
        assertEquals(Level.ROOT, lvl("sudo apt update"))
        assertEquals(Level.ROOT, lvl("tsu"))
        assertEquals(Level.ROOT, lvl("curl -LO magisk.apk"))
        assertEquals(Level.ROOT, lvl("fastboot flashing unlock"))
        assertTrue(CommandGuard.mentionsRoot("اعمل روت للتليفون"))
        assertFalse(CommandGuard.mentionsRoot("write a python script that sums numbers"))
    }

    @Test fun novaStaysProtected() {
        assertEquals(Level.PROTECTED, lvl("adb shell dpm remove-active-admin com.myassistant.app/.admin.AdminReceiver"))
        assertEquals(Level.PROTECTED, lvl("adb shell pm uninstall com.myassistant.app"))
        assertEquals(Level.PROTECTED, lvl("pm unsuspend com.zhiliaoapp.musically"))
        assertEquals(Level.READ, lvl("ls /sdcard/Android/media/com.myassistant.app"))
    }

    @Test fun wordsContainingSuAreFine() {
        assertEquals(Level.READ, lvl("cat summary.txt"))
        assertEquals(Level.CHANGE, lvl("pkg install sudoku"))
    }
}
