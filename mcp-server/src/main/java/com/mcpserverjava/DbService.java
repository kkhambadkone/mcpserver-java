package com.mcpserverjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Fully generic JDBC service — no table or column names are hardcoded.
 *
 * Safe identifier pattern:  only letters, digits, underscores, and dots
 * (schema.table notation).  Anything else is rejected before it reaches JDBC,
 * protecting against identifier-injection in table/column name arguments.
 *
 * All methods return a JSON string ready for McpSchema.TextContent.
 */
@Service
public class DbService {

    private static final Logger log = LoggerFactory.getLogger(DbService.class);

    /** Allowed characters in a table or column identifier. */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[\\w.]+");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DbService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc   = jdbc;
        this.mapper = mapper;
    }

    // ── Schema discovery ─────────────────────────────────────────────────────

    /**
     * List all user tables visible to the connected account.
     */
    public String listTables() {
        // Exclude MySQL built-in system schemas — only user-created tables are returned.
        return queryToJson("""
                SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE
                FROM   INFORMATION_SCHEMA.TABLES
                WHERE  TABLE_TYPE = 'BASE TABLE'
                  AND  TABLE_SCHEMA NOT IN (
                         'information_schema',
                         'performance_schema',
                         'sys',
                         'mysql'
                       )
                ORDER  BY TABLE_SCHEMA, TABLE_NAME
                """);
    }

    /**
     * Return column metadata for any table.
     *
     * @param tableName  unqualified or schema-qualified name (e.g. "orders" or "dbo.orders")
     */
    public String describeTable(String tableName) {
        validateIdentifier(tableName);
        // Split optional schema prefix so we can bind safely
        String[] parts = tableName.split("\\.", 2);
        if (parts.length == 2) {
            return queryToJson("""
                    SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH,
                           IS_NULLABLE, COLUMN_DEFAULT
                    FROM   INFORMATION_SCHEMA.COLUMNS
                    WHERE  TABLE_SCHEMA = ?
                      AND  TABLE_NAME   = ?
                    ORDER  BY ORDINAL_POSITION
                    """, parts[0], parts[1]);
        }
        return queryToJson("""
                SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH,
                       IS_NULLABLE, COLUMN_DEFAULT
                FROM   INFORMATION_SCHEMA.COLUMNS
                WHERE  TABLE_NAME = ?
                ORDER  BY ORDINAL_POSITION
                """, tableName);
    }

    // ── Generic query ────────────────────────────────────────────────────────

    /**
     * Execute any caller-supplied SELECT statement (DDL/DML guard applied upstream).
     */
    public String executeQuery(String sql, int maxRows) {
        jdbc.setMaxRows(maxRows);
        try {
            return queryToJson(sql);
        } finally {
            jdbc.setMaxRows(0);
        }
    }

    // ── Generic table operations ─────────────────────────────────────────────

    /**
     * Paginated scan of any table ordered by a caller-supplied column.
     *
     * @param tableName  target table
     * @param orderBy    column to sort by (validated against safe-identifier pattern)
     * @param limit      page size
     * @param offset     row offset
     */
    public String listRows(String tableName, String orderBy, int limit, int offset) {
        validateIdentifier(tableName);
        validateIdentifier(orderBy);
        // Table and column names cannot be bound as parameters — we've validated them above.
        // MySQL pagination syntax: LIMIT count OFFSET start
        String sql = "SELECT * FROM " + tableName
                   + " ORDER BY " + orderBy
                   + " LIMIT ? OFFSET ?";
        return queryToJson(sql, limit, offset);
    }

    /**
     * Fetch rows where a specific column equals a given value.
     *
     * @param tableName   target table
     * @param columnName  column to filter on (e.g. "id", "email")
     * @param value       exact value to match (bound as JDBC parameter — safe)
     */
    public String getRowsWhere(String tableName, String columnName, String value) {
        validateIdentifier(tableName);
        validateIdentifier(columnName);
        String sql = "SELECT * FROM " + tableName + " WHERE " + columnName + " = ?";
        return queryToJson(sql, value);
    }

    /**
     * Case-insensitive substring search on a single column of any table.
     *
     * @param tableName   target table
     * @param columnName  column to search (e.g. "name", "description")
     * @param term        search substring (bound as JDBC parameter — safe)
     */
    public String searchColumn(String tableName, String columnName, String term) {
        validateIdentifier(tableName);
        validateIdentifier(columnName);
        String sql = "SELECT * FROM " + tableName
                   + " WHERE LOWER(" + columnName + ") LIKE LOWER(?)";
        return queryToJson(sql, "%" + term + "%");
    }

    // ── Write operations ─────────────────────────────────────────────────────

    /**
     * Update a single column on all rows matching a WHERE condition.
     *
     * Example: updateColumn("customers", "phone", "Jimmy Stewart", "name", "609-098-0220")
     *
     * Executes:  UPDATE <table> SET <setColumn> = ? WHERE <whereColumn> = ?
     *
     * All identifier names are validated; both values are bound as JDBC parameters.
     *
     * @param tableName    target table
     * @param whereColumn  column used in the WHERE clause (e.g. "name", "id")
     * @param whereValue   value to match in whereColumn (bound as parameter)
     * @param setColumn    column to update (e.g. "phone", "email")
     * @param newValue     new value to set (bound as parameter)
     * @return JSON with rowsAffected count
     */
    public String updateColumn(String tableName,
                               String whereColumn, String whereValue,
                               String setColumn,   String newValue) {
        validateIdentifier(tableName);
        validateIdentifier(whereColumn);
        validateIdentifier(setColumn);

        String sql = "UPDATE " + tableName
                   + " SET "   + setColumn   + " = ?"
                   + " WHERE " + whereColumn + " = ?";
        try {
            int affected = jdbc.update(sql, newValue, whereValue);
            log.info("updateColumn table={} set {}='{}' where {}='{}' → {} rows",
                     tableName, setColumn, newValue, whereColumn, whereValue, affected);
            return String.format("{\"rowsAffected\":%d}", affected);
        } catch (Exception ex) {
            log.error("Update failed: {}", sql, ex);
            return String.format("{\"error\":\"%s\"}", ex.getMessage().replace("\"", "'"));
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Rejects identifiers containing characters outside [A-Za-z0-9_.].
     * Protects the non-parameterisable parts of dynamically built SQL.
     */
    private static void validateIdentifier(String name) {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("Identifier must not be blank");
        if (!SAFE_IDENTIFIER.matcher(name).matches())
            throw new IllegalArgumentException(
                    "Unsafe identifier (only letters, digits, _ and . are allowed): " + name);
    }

    private String queryToJson(String sql, Object... args) {
        try {
            List<Map<String, Object>> rows = (args.length == 0)
                    ? jdbc.queryForList(sql)
                    : jdbc.queryForList(sql, args);

            ArrayNode array = mapper.createArrayNode();
            for (Map<String, Object> row : rows) {
                ObjectNode node = mapper.createObjectNode();
                row.forEach((k, v) -> {
                    if (v == null) node.putNull(k);
                    else           node.putPOJO(k, v);
                });
                array.add(node);
            }

            ObjectNode result = mapper.createObjectNode();
            result.put("rowCount", rows.size());
            result.set("rows", array);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);

        } catch (Exception ex) {
            log.error("Query failed: {}", sql, ex);
            return String.format("{\"error\":\"%s\"}", ex.getMessage().replace("\"", "'"));
        }
    }
}
