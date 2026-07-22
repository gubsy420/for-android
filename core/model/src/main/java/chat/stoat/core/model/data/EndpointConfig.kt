package chat.stoat.core.model.data

// Fork addition (self-hosted support): the official first-party endpoints, kept as
// compile-time constants so they can be referenced anywhere, including as defaults.
const val STOAT_OFFICIAL_BASE = "https://api.stoat.chat/0.8"
const val STOAT_OFFICIAL_WEBSOCKET = "wss://events.stoat.chat"
const val STOAT_OFFICIAL_FILES = "https://cdn.stoatusercontent.com"
const val STOAT_OFFICIAL_PROXY = "https://proxy.stoatusercontent.com"
const val STOAT_OFFICIAL_WEB_APP = "https://stoat.chat"

/**
 * Fork addition (self-hosted support): runtime-mutable endpoint configuration.
 *
 * The top-level endpoint vals in [Constants.kt] delegate to this object, so the
 * whole app follows whatever instance is configured here. Defaults are the
 * official first-party Stoat endpoints. Persistence is handled in the app module
 * (`chat.stoat.selfhost.SelfHostedEndpoints`); this object is process state only.
 */
object EndpointConfig {
    var apiBase: String = STOAT_OFFICIAL_BASE
    var websocket: String = STOAT_OFFICIAL_WEBSOCKET
    var files: String = STOAT_OFFICIAL_FILES
    var proxy: String = STOAT_OFFICIAL_PROXY
    var webApp: String = STOAT_OFFICIAL_WEB_APP

    /** True when the app is pointed at a non-official (self-hosted) instance. */
    val isCustom: Boolean
        get() = apiBase != STOAT_OFFICIAL_BASE

    fun reset() {
        apiBase = STOAT_OFFICIAL_BASE
        websocket = STOAT_OFFICIAL_WEBSOCKET
        files = STOAT_OFFICIAL_FILES
        proxy = STOAT_OFFICIAL_PROXY
        webApp = STOAT_OFFICIAL_WEB_APP
    }
}
