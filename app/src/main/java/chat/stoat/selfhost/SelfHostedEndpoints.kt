package chat.stoat.selfhost

import chat.stoat.core.model.data.EndpointConfig
import chat.stoat.persistence.KVStorage

/**
 * Fork addition (self-hosted support): persists the configured instance endpoints
 * in [KVStorage] and mirrors them into [EndpointConfig] at runtime.
 *
 * [hydrate] must run before any network traffic — it is called from
 * `StoatApplication.onCreate`.
 */
object SelfHostedEndpoints {
    private const val KEY_API_BASE = "selfHostedApiBase"
    private const val KEY_WEBSOCKET = "selfHostedWebsocket"
    private const val KEY_FILES = "selfHostedFiles"
    private const val KEY_PROXY = "selfHostedProxy"
    private const val KEY_WEB_APP = "selfHostedWebApp"

    suspend fun hydrate(kvStorage: KVStorage) {
        val apiBase = kvStorage.get(KEY_API_BASE) ?: return
        EndpointConfig.apiBase = apiBase
        kvStorage.get(KEY_WEBSOCKET)?.let { EndpointConfig.websocket = it }
        kvStorage.get(KEY_FILES)?.let { EndpointConfig.files = it }
        kvStorage.get(KEY_PROXY)?.let { EndpointConfig.proxy = it }
        kvStorage.get(KEY_WEB_APP)?.let { EndpointConfig.webApp = it }
    }

    suspend fun save(
        kvStorage: KVStorage,
        apiBase: String,
        websocket: String,
        files: String,
        proxy: String,
        webApp: String
    ) {
        kvStorage.set(KEY_API_BASE, apiBase)
        kvStorage.set(KEY_WEBSOCKET, websocket)
        kvStorage.set(KEY_FILES, files)
        kvStorage.set(KEY_PROXY, proxy)
        kvStorage.set(KEY_WEB_APP, webApp)

        EndpointConfig.apiBase = apiBase
        EndpointConfig.websocket = websocket
        EndpointConfig.files = files
        EndpointConfig.proxy = proxy
        EndpointConfig.webApp = webApp
    }

    suspend fun clear(kvStorage: KVStorage) {
        kvStorage.remove(KEY_API_BASE)
        kvStorage.remove(KEY_WEBSOCKET)
        kvStorage.remove(KEY_FILES)
        kvStorage.remove(KEY_PROXY)
        kvStorage.remove(KEY_WEB_APP)

        EndpointConfig.reset()
    }
}
