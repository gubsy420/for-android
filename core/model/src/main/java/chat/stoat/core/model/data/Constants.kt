package chat.stoat.core.model.data

val STOAT_BASE: String get() = EndpointConfig.apiBase
const val STOAT_SUPPORT = "https://support.stoat.chat"
const val STOAT_MARKETING = "https://stoat.chat"
// Fork change: these delegate to EndpointConfig so the app follows the configured
// (self-hosted or official) instance. STOAT_BETA_WEB_APP is upstream's official
// beta host, used only for web-link host recognition, so it stays a constant.
val STOAT_FILES: String get() = EndpointConfig.files
val STOAT_PROXY: String get() = EndpointConfig.proxy
val STOAT_WEB_APP: String get() = EndpointConfig.webApp
const val STOAT_BETA_WEB_APP = "https://beta.stoat.chat"
val STOAT_INVITES: String get() = EndpointConfig.inviteBase
val STOAT_WEBSOCKET: String get() = EndpointConfig.websocket
const val STOAT_CHANGELOG = "https://changelog.stoat.chat"
