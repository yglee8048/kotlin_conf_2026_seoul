package com.example.demo.vo

enum class Channel {
    MOBILE,
    WEB,
    ATM,
    UNKNOWN,
    ;

    companion object {
        fun from(value: String?): Channel {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
        }
    }
}
