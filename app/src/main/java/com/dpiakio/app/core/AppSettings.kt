package com.dpiakio.app.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Everything the user configures. Persisted as JSON in SharedPreferences. */
data class AppConfig(
    val options: DpiOptions = Presets.byId("any_country")!!.build(),
    val presetId: String = "any_country",      // "" = custom
    val modeset: Int = 5,                       // 0 = custom flags
    val ipv6: Boolean = true,
    val fallbackFakeTtl: Int = 0,               // used for --wrong-* emulation when TTL is unknown
    val startOnBoot: Boolean = false,
    val verboseLog: Boolean = false,
) {
    fun toJson() = JSONObject().apply {
        put("options", options.toJson()); put("preset", presetId); put("mode", modeset)
        put("ipv6", ipv6); put("fbttl", fallbackFakeTtl); put("boot", startOnBoot); put("verbose", verboseLog)
    }

    companion object {
        fun fromJson(j: JSONObject) = AppConfig(
            options = j.optJSONObject("options")?.let { DpiOptions.fromJson(it) } ?: AppConfig().options,
            presetId = j.optString("preset", ""), modeset = j.optInt("mode", 0),
            ipv6 = j.optBoolean("ipv6", true), fallbackFakeTtl = j.optInt("fbttl", 0),
            startOnBoot = j.optBoolean("boot", false), verboseLog = j.optBoolean("verbose", false),
        )
    }
}

object AppSettings {
    private const val PREFS = "dpiakio"
    private const val KEY = "config"
    private val _config = MutableStateFlow(AppConfig())
    val config: StateFlow<AppConfig> = _config
    @Volatile private var loaded = false

    fun load(ctx: Context): AppConfig {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                _config.value = s?.let { runCatching { AppConfig.fromJson(JSONObject(it)) }.getOrNull() } ?: AppConfig()
                loaded = true
            }
        }
        return _config.value
    }

    fun update(ctx: Context, f: (AppConfig) -> AppConfig) {
        val n = f(load(ctx))
        _config.value = n
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, n.toJson().toString()).apply()
    }
}
