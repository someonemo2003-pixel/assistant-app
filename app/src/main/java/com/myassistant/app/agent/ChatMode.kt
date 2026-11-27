package com.myassistant.app.agent

/** Who handles the next message — the user picks it above the input box. */
enum class ChatMode(val label: String, val hint: String) {
    AUTO("تلقائي", "Nova يختار"),
    TALK("كلام", "رد بس، من غير ما يعمل حاجة"),
    PHONE("الموبايل", "يتحكم في الموبايل والبرامج"),
    CODE("OpenCode", "برمجة وتيرمنال"),
    ;

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: AUTO
    }
}
