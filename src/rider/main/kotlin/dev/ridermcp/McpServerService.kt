package dev.ridermcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import dev.ridermcp.server.McpHttpServer

/**
 * Application-level owner of the MCP HTTP/SSE server. The server is started by
 * [McpServerStartup] and torn down when this service is disposed (IDE shutdown).
 */
@Service(Service.Level.APP)
class McpServerService : Disposable {

    private val log = logger<McpServerService>()
    private var server: McpHttpServer? = null

    @Synchronized
    fun startIfNeeded(port: Int) {
        if (server != null) return
        log.info("Starting Rider MCP server on port $port")
        server = McpHttpServer(port).also { it.start() }
    }

    @Synchronized
    override fun dispose() {
        log.info("Stopping Rider MCP server")
        server?.stop()
        server = null
    }
}
