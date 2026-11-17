package com.myassistant.app.admin

import com.myassistant.app.tools.Schema
import com.myassistant.app.tools.Tool
import com.myassistant.app.tools.ToolContext
import com.myassistant.app.tools.str
import org.json.JSONObject

class BlockAppTool : Tool {
    override val name = "block_app"
    override val title = "منع برنامج"
    override val description =
        "Block an app from being installed on this phone (removes it now if it's installed, closes its store page, removes it " +
            "again if it gets installed). Give the app name as the user said it, and its Android package if you know it " +
            "(e.g. TikTok = com.zhiliaoapp.musically)."
    override val parameters = Schema.obj(
        "app" to Schema.string("App name, e.g. TikTok"),
        "package" to Schema.string("Optional Android package name if known"),
    )
    override suspend fun execute(args: JSONObject, ctx: ToolContext) =
        DeviceOwner.block(ctx.app, args.str("app"), args.str("package").ifBlank { null })
}

class UnblockAppTool : Tool {
    override val name = "unblock_app"
    override val title = "إلغاء منع برنامج"
    override val description = "Remove an app from the block list. Needs the user's guard PIN if one is set — ask the user for it, never guess."
    override val parameters = Schema.obj(
        "app" to Schema.string("App name or package"),
        "pin" to Schema.string("The guard PIN the user typed (empty if none set)"),
    )
    override suspend fun execute(args: JSONObject, ctx: ToolContext) =
        DeviceOwner.unblock(ctx.app, args.str("app"), args.str("pin"))
}

class ListBlockedTool : Tool {
    override val name = "list_blocked_apps"
    override val title = "البرامج الممنوعة"
    override val description = "List blocked apps and whether full device-owner control is on."
    override val parameters = Schema.obj()
    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val l = DeviceOwner.blocked(ctx.app)
        val mode = if (DeviceOwner.isOwner(ctx.app)) "Device owner: ON (full control)" else "Device owner: OFF (blocking via screen service only)"
        return mode + "\n" + (if (l.isEmpty()) "No blocked apps" else l.joinToString("\n") { "- ${it.name}" + (it.pkg?.let { p -> " ($p)" } ?: "") })
    }
}

class SuspendAppTool : Tool {
    override val name = "suspend_app"
    override val title = "إيقاف برنامج"
    override val description =
        "Pause an installed app so it can't be opened (stays installed), or resume it. Needs device-owner control."
    override val parameters = Schema.obj(
        "app" to Schema.string("App name or package"),
        "on" to Schema.string("'true' to pause, 'false' to resume"),
    )
    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        if (!DeviceOwner.isOwner(ctx.app)) return "Device owner is OFF — tell the user to enable it from Settings → 🛡️ التحكم الكامل."
        val app = DeviceOwner.findInstalled(ctx.app, args.str("app")) ?: return "App not found"
        val on = args.str("on") != "false"
        return if (DeviceOwner.suspend(ctx.app, app.second, on)) (if (on) "⏸️ ${app.first} متوقف" else "▶️ ${app.first} رجع يشتغل") else "Failed"
    }
}

class LockPhoneTool : Tool {
    override val name = "lock_phone"
    override val title = "قفل الشاشة"
    override val description = "Lock the screen now. Needs device-owner control."
    override val parameters = Schema.obj()
    override suspend fun execute(args: JSONObject, ctx: ToolContext) =
        if (DeviceOwner.lockNow(ctx.app)) "🔒 Locked" else "Can't lock — device owner is OFF"
}
