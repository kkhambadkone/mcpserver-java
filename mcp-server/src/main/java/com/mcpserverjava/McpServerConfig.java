package com.mcpserverjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registers six fully generic MCP tools — no table or column names are hardcoded.
 * The LLM discovers schema via list_tables / describe_table, then operates on
 * whatever tables exist in the connected database.
 *
 *   list_tables     — enumerate all tables
 *   describe_table  — column metadata for any table
 *   execute_sql     — safe read-only SELECT
 *   list_rows       — paginated scan of any table
 *   get_rows_where  — equality filter on any column of any table
 *   search_column   — substring search on any column of any table
 */
@Configuration
public class McpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfig.class);

    private final DbService db;
    private final ObjectMapper objectMapper;

    public McpServerConfig(DbService db, ObjectMapper objectMapper) {
        this.db           = db;
        this.objectMapper = objectMapper;
    }

    // ── Transport ─────────────────────────────────────────────────────────────

    @Bean
    public HttpServletSseServerTransportProvider sseTransportProvider() {
        return new HttpServletSseServerTransportProvider(objectMapper, "/mcp/message");
    }

    @Bean
    public ServletRegistrationBean<HttpServletSseServerTransportProvider> mcpServlet(
            HttpServletSseServerTransportProvider tp) {
        ServletRegistrationBean<HttpServletSseServerTransportProvider> reg =
                new ServletRegistrationBean<>(tp, "/sse", "/mcp/message");
        reg.setName("mcp-sse-transport");
        reg.setLoadOnStartup(1);
        return reg;
    }

    // ── Server ────────────────────────────────────────────────────────────────

    @Bean
    public McpSyncServer mcpServer(HttpServletSseServerTransportProvider tp) {
        McpSyncServer server = McpServer.sync(tp)
                .serverInfo("db-mcp-server", "1.0.0")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .build();

        server.addTool(listTablesTool());
        server.addTool(describeTableTool());
        server.addTool(executeSqlTool());
        server.addTool(listRowsTool());
        server.addTool(getRowsWhereTool());
        server.addTool(searchColumnTool());
        server.addTool(updateRowTool());

        log.info("MCP server ready — 7 generic tools registered");
        return server;
    }

    // =========================================================================
    // Tool definitions — fully generic, no hardcoded table/column names
    // =========================================================================

    private McpServerFeatures.SyncToolSpecification listTablesTool() {
        return new McpServerFeatures.SyncToolSpecification(
                new Tool("list_tables",
                        "List all user tables in the connected database. "
                      + "Call this first to discover what tables are available.",
                        noParams()),
                (exchange, args) -> {
                    log.info("[tool] list_tables");
                    return ok(db.listTables());
                });
    }

    private McpServerFeatures.SyncToolSpecification describeTableTool() {
        return new McpServerFeatures.SyncToolSpecification(
                new Tool("describe_table",
                        "Return column names, data types, nullability and defaults for any table. "
                      + "Use after list_tables to understand the table structure before querying.",
                        """
                        {"type":"object",
                         "properties":{
                           "table_name":{"type":"string",
                             "description":"Table name, optionally schema-qualified (e.g. 'orders' or 'dbo.orders')"}
                         },
                         "required":["table_name"]}"""),
                (exchange, args) -> {
                    String table = str(args, "table_name");
                    log.info("[tool] describe_table table={}", table);
                    return ok(db.describeTable(table));
                });
    }

    private McpServerFeatures.SyncToolSpecification executeSqlTool() {
        return new McpServerFeatures.SyncToolSpecification(
                new Tool("execute_sql",
                        "Execute a read-only SELECT statement. DDL and DML are blocked. "
                      + "Use describe_table first so you know the exact column names.",
                        """
                        {"type":"object",
                         "properties":{
                           "query":{"type":"string","description":"A SELECT or WITH…SELECT statement"},
                           "max_rows":{"type":"integer","description":"Row cap — default 100, max 500","default":100}
                         },
                         "required":["query"]}"""),
                (exchange, args) -> {
                    String query = str(args, "query");
                    String upper = query.stripLeading().toUpperCase();
                    if (!upper.startsWith("SELECT") && !upper.startsWith("WITH"))
                        return err("Only SELECT (or CTE WITH … SELECT) statements are allowed.");
                    for (String kw : Set.of("DROP","DELETE","INSERT","UPDATE",
                                            "TRUNCATE","ALTER","EXEC","EXECUTE","XP_"))
                        if (upper.contains(kw)) return err("Blocked keyword: " + kw);
                    int maxRows = args.containsKey("max_rows")
                            ? Math.min(((Number) args.get("max_rows")).intValue(), 500) : 100;
                    log.info("[tool] execute_sql max_rows={}", maxRows);
                    return ok(db.executeQuery(query, maxRows));
                });
    }

    private McpServerFeatures.SyncToolSpecification listRowsTool() {
        return new McpServerFeatures.SyncToolSpecification(
                new Tool("list_rows",
                        "Return a paginated list of rows from any table ordered by a chosen column.",
                        """
                        {"type":"object",
                         "properties":{
                           "table_name":{"type":"string","description":"Target table"},
                           "order_by":{"type":"string","description":"Column to sort by (e.g. 'id')"},
                           "limit":{"type":"integer","description":"Page size — default 20, max 200","default":20},
                           "offset":{"type":"integer","description":"Row offset for pagination — default 0","default":0}
                         },
                         "required":["table_name","order_by"]}"""),
                (exchange, args) -> {
                    String table   = str(args, "table_name");
                    String orderBy = str(args, "order_by");
                    int limit  = args.containsKey("limit")
                            ? Math.min(((Number) args.get("limit")).intValue(), 200) : 20;
                    int offset = args.containsKey("offset")
                            ? ((Number) args.get("offset")).intValue() : 0;
                    log.info("[tool] list_rows table={} orderBy={} limit={} offset={}",
                             table, orderBy, limit, offset);
                    return ok(db.listRows(table, orderBy, limit, offset));
                });
    }

    private McpServerFeatures.SyncToolSpecification getRowsWhereTool() {
        return new McpServerFeatures.SyncToolSpecification(
                new Tool("get_rows_where",
                        "Fetch rows from any table where a specific column equals a given value. "
                      + "Good for primary-key lookups and exact-match filtering.",
                        """
                        {"type":"object",
                         "properties":{
                           "table_name":{"type":"string","description":"Target table"},
                           "column_name":{"type":"string","description":"Column to filter on (e.g. 'id', 'status')"},
                           "value":{"type":"string","description":"Exact value to match"}
                         },
                         "required":["table_name","column_name","value"]}"""),
                (exchange, args) -> {
                    String table  = str(args, "table_name");
                    String column = str(args, "column_name");
                    String value  = str(args, "value");
                    log.info("[tool] get_rows_where table={} column={}", table, column);
                    return ok(db.getRowsWhere(table, column, value));
                });
    }

    private McpServerFeatures.SyncToolSpecification searchColumnTool() {
        return new McpServerFeatures.SyncToolSpecification(
                new Tool("search_column",
                        "Case-insensitive substring search on any text column of any table. "
                      + "Use describe_table to identify which columns are text types.",
                        """
                        {"type":"object",
                         "properties":{
                           "table_name":{"type":"string","description":"Target table"},
                           "column_name":{"type":"string","description":"Text column to search"},
                           "term":{"type":"string","description":"Substring to search for"}
                         },
                         "required":["table_name","column_name","term"]}"""),
                (exchange, args) -> {
                    String table  = str(args, "table_name");
                    String column = str(args, "column_name");
                    String term   = str(args, "term");
                    log.info("[tool] search_column table={} column={} term={}", table, column, term);
                    return ok(db.searchColumn(table, column, term));
                });
    }


    private McpServerFeatures.SyncToolSpecification updateRowTool() {
        return new McpServerFeatures.SyncToolSpecification(
                new Tool("update_row",
                        "Update a single column value on rows matching a WHERE condition. "
                      + "Use get_rows_where or search_column first to confirm which row to target, "
                      + "then call this with the exact match values.",
                        """
                        {"type":"object",
                         "properties":{
                           "table_name":   {"type":"string","description":"Target table"},
                           "where_column": {"type":"string","description":"Column to identify the row (e.g. 'name', 'id')"},
                           "where_value":  {"type":"string","description":"Exact value that identifies the row"},
                           "set_column":   {"type":"string","description":"Column whose value should be changed"},
                           "new_value":    {"type":"string","description":"The new value to set"}
                         },
                         "required":["table_name","where_column","where_value","set_column","new_value"]}"""),
                (exchange, args) -> {
                    String table       = str(args, "table_name");
                    String whereColumn = str(args, "where_column");
                    String whereValue  = str(args, "where_value");
                    String setColumn   = str(args, "set_column");
                    String newValue    = str(args, "new_value");
                    log.info("[tool] update_row table={} set {}='{}' where {}='{}'",
                             table, setColumn, newValue, whereColumn, whereValue);
                    return ok(db.updateColumn(table, whereColumn, whereValue, setColumn, newValue));
                });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String noParams() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    private static CallToolResult ok(String json) {
        return new CallToolResult(List.of(new TextContent(json)), false);
    }

    private static CallToolResult err(String message) {
        return new CallToolResult(
                List.of(new TextContent("{\"error\":\"" + message + "\"}")), true);
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v == null) throw new IllegalArgumentException("Missing required argument: " + key);
        return v.toString();
    }
}
