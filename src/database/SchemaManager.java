package database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import constants.ErrorMessages;
import logs.Logger;

/**
 * Utility class for standardized database operations.
 * Provides consistent patterns for database access and resource management.
 */
public class SchemaManager {
    private static final Logger logger = Logger.getInstance();

    /**
     * Functional interface for database operations that don't return a result
     */
    @FunctionalInterface
    public interface DatabaseAction {
        void execute(Connection connection) throws SQLException;
    }

    /**
     * Functional interface for database operations that return a result
     * @param <T> The type of result returned
     */
    @FunctionalInterface
    public interface DatabaseFunction<T> {
        T execute(Connection connection) throws SQLException;
    }

    /**
     * Executes a database operation that doesn't return a result
     * @param action The database operation to execute
     * @param logSource The source for logging purposes
     * @throws SQLException If a database error occurs
     */
    public static void withConnection(DatabaseAction action, String logSource) throws SQLException {
        ConnectionManager pool = ConnectionManager.getInstance();
        Connection conn = null;

        try {
            conn = pool.getConnection();
            action.execute(conn);
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, logSource, ErrorMessages.DB_OPERATION_FAILED, e);
            throw e;
        } finally {
            pool.releaseConnection(conn);
        }
    }

    /**
     * Executes a database operation that returns a result
     * @param <T> The type of result
     * @param function The database operation to execute
     * @param defaultValue The default value to return if an error occurs
     * @param logSource The source for logging purposes
     * @return The result of the database operation, or the defaultValue if an error occurs
     */
    public static <T> T withConnection(DatabaseFunction<T> function, T defaultValue, String logSource) {
        ConnectionManager pool = ConnectionManager.getInstance();
        Connection conn = null;

        try {
            conn = pool.getConnection();
            return function.execute(conn);
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, logSource, ErrorMessages.DB_OPERATION_FAILED, e);
            return defaultValue;
        } finally {
            pool.releaseConnection(conn);
        }
    }

    /**
     * Executes a query and processes each row through a consumer
     * @param query The SQL query to execute
     * @param rowProcessor Consumer to process each row
     * @param logSource The source for logging purposes
     * @throws SQLException If a database error occurs
     */
    public static void queryEach(String query, Consumer<ResultSet> rowProcessor, String logSource) throws SQLException {
        withConnection(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    rowProcessor.accept(rs);
                }
            }
        }, logSource);
    }

    /**
     * Executes a query with parameters and processes each row through a consumer
     * @param query The parameterized SQL query
     * @param paramSetter Consumer that sets parameters on the prepared statement
     * @param rowProcessor Consumer that processes each row in the result set
     * @param logSource The source for logging purposes
     * @throws SQLException If a database error occurs
     */
    public static void queryEachWithParams(String query, Consumer<PreparedStatement> paramSetter,
                                           Consumer<ResultSet> rowProcessor, String logSource) throws SQLException {
        withConnection(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                paramSetter.accept(pstmt);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        rowProcessor.accept(rs);
                    }
                }
            }
        }, logSource);
    }

    /**
     * Collects all results of a query into a List
     * @param <T> The type of objects in the result list
     * @param query The SQL query
     * @param rowMapper Function that maps a row to an object of type T
     * @param logSource The source for logging purposes
     * @return A list of objects created from the query results
     */
    public static <T> List<T> queryList(String query, Function<ResultSet, T> rowMapper, String logSource) {
        return withConnection(conn -> {
            List<T> results = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    results.add(rowMapper.apply(rs));
                }
            }
            return results;
        }, new ArrayList<>(), logSource);
    }

    /**
     * Executes an update statement and returns the number of affected rows
     * @param query The SQL update query
     * @param logSource The source for logging purposes
     * @return The number of rows affected
     * @throws SQLException If a database error occurs
     */
    public static int executeUpdate(String query, String logSource) throws SQLException {
        return withConnection(conn -> {
            try (Statement stmt = conn.createStatement()) {
                return stmt.executeUpdate(query);
            }
        }, 0, logSource);
    }

    /**
     * Executes a parameterized update statement and returns the number of affected rows
     * @param query The parameterized SQL update query
     * @param paramSetter Consumer that sets parameters on the prepared statement
     * @param logSource The source for logging purposes
     * @return The number of rows affected
     * @throws SQLException If a database error occurs
     */
    public static int executeUpdateWithParams(String query, Consumer<PreparedStatement> paramSetter, String logSource) throws SQLException {
        return withConnection(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                paramSetter.accept(pstmt);
                return pstmt.executeUpdate();
            }
        }, 0, logSource);
    }

    /**
     * Checks if a table exists in the database
     * @param tableName The name of the table to check
     * @param logSource The source for logging purposes
     * @return true if the table exists, false otherwise
     */
    public static boolean tableExists(String tableName, String logSource) {
        return withConnection(conn -> {
            try (ResultSet rs = conn.getMetaData().getTables(null, null, tableName, null)) {
                return rs.next();
            }
        }, false, logSource);
    }

    /**
     * Executes a transaction with multiple operations
     * @param transaction The transaction to execute (multiple SQL operations)
     * @param logSource The source for logging purposes
     * @throws SQLException If a database error occurs
     */
    public static void executeTransaction(DatabaseAction transaction, String logSource) throws SQLException {
        withConnection(conn -> {
            boolean originalAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                transaction.execute(conn);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }
        }, logSource);
    }
}