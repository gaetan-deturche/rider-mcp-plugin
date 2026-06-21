package dev.ridermcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger

/**
 * Application-level owner of the MCP HTTP/SSE server. Started by
 * [McpServerStartup] on solution open (or manually via the "Start MCP Server"
 * action) and torn down when this service is disposed (IDE shutdown).
 *
 * Failures are logged loudly (LOG.error) so they surface in idea.log rather
 * than disappearing inside a startup coroutine.
 */
@Service(Service.Level.APP)
class McpServerService : Disposable {

    private val log = logger<McpServerService>()

    // Server type is referenced lazily (only inside start) so a class-load
    // failure of the Ktor/MCP stack can't break this service's instantiation.
    private var server: dev.ridermcp.server.McpHttpServer? = null

    val isRunning: Boolean
        @Synchronized get() = server != null

    /** Starts the server if not already running. Returns true if running afterward. */
    @Synchronized
    fun startIfNeeded(port: Int): Boolean {
        if (server != null) {
            log.info("Rider MCP server already running on port $port")
            return true
        }
        return try {
            log.warn("Rider MCP: starting server on 127.0.0.1:$port …")
            server = dev.ridermcp.server.McpHttpServer(port).also { it.start() }
            log.warn("Rider MCP: server started, SSE at http://127.0.0.1:$port/sse")
            true
        } catch (t: Throwable) {
            log.error("Rider MCP: FAILED to start server on port $port", t)
            server = null
            false
        }
    }

    @Synchronized
    override fun dispose() {
        runCatching { server?.stop() }.onFailure { log.warn("Rider MCP: error stopping server", it) }
        server = null
    }
}
