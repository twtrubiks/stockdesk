package com.twtrubiks.stockdesk

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

enum class ThemeMode(val label: String) {
    SYSTEM("跟系統"),
    LIGHT("淺色"),
    DARK("深色"),
}

/** 使用者的設定，存在 SharedPreferences `settings`（存名字，不存序號）。[value] 是 Compose 的狀態，畫面直接讀。 */
class EnumSetting<E : Enum<E>>(private val prefs: SharedPreferences, private val key: String, entries: List<E>, default: E) {
    var value by mutableStateOf(prefs.getString(key, null)?.let { saved -> entries.firstOrNull { it.name == saved } } ?: default)
        private set

    /** 改了之後要跟著做的事（例如把深色模式套到瀏覽器引擎）。 */
    var onChange: (E) -> Unit = {}

    fun set(value: E) {
        this.value = value
        prefs.edit { putString(key, value.name) }
        onChange(value)
    }
}

/** 一組字串（例如 goodinfo 分頁收起來的代號），存成 StringSet。[value] 是 Compose 的狀態。 */
class StringSetSetting(private val prefs: SharedPreferences, private val key: String) {
    // getStringSet 回的那份不能改，複製一份
    var value by mutableStateOf(prefs.getStringSet(key, null)?.toSet() ?: emptySet())
        private set

    fun set(value: Set<String>) {
        this.value = value
        prefs.edit { putStringSet(key, value) }
    }
}

class IntSetting(private val prefs: SharedPreferences, private val key: String, default: Int) {
    var value by mutableIntStateOf(prefs.getInt(key, default))
        private set

    fun set(value: Int) {
        this.value = value
        prefs.edit { putInt(key, value) }
    }
}
