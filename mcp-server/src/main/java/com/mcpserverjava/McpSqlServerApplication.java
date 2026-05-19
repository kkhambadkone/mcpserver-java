package com.mcpserverjava;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry-point for the MCP SQL Server gateway.
 *
 * The HttpServletSseServerTransportProvider registered in McpServerConfig
 * exposes two HTTP endpoints:
 *   GET  /sse           → SSE stream (client subscribes here)
 *   POST /mcp/message   → client sends JSON-RPC requests here
 */
@SpringBootApplication
public class McpSqlServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpSqlServerApplication.class, args);
    }
}
