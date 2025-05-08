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

import logs.Logger;

public class SchemaManager {
    private static final Logger logger = Logger.getInstance();

    @FunctionalInterface
    public interface DatabaseAction {
        void execute(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface DatabaseFunction<T> {
        T execute(Connection connection) throws SQLException;
    }

    public static void withConnection(DatabaseAction action, String logSource) throws SQLException {
        ConnectionManager pool = ConnectionManager.getInstance();
        Connection conn = null;

        try {
            conn = pool.getConnection();
            action.execute(conn);
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, logSource, "Database operation failed: " + e.getMessage(), e);
            throw e;
        } finally {
            pool.releaseConnection(conn);
        }
    }

    public static <T> T withConnection(DatabaseFunction<T> function, T defaultValue, String logSource) {
        ConnectionManager pool = ConnectionManager.getInstance();
        Connection conn = null;

        try {
            conn = pool.getConnection();
            return function.execute(conn);
        } catch (SQLException e) {
            logger.log(Logger.Level.ERROR, logSource, "Connection operation failed: " + e.getMessage(), e);
            return defaultValue;
        } finally {
            pool.releaseConnection(conn);
        }
    }

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

    public static int executeUpdate(String query, String logSource) throws SQLException {
        return withConnection(conn -> {
            try (Statement stmt = conn.createStatement()) {
                return stmt.executeUpdate(query);
            }
        }, 0, logSource);
    }

    public static int executeUpdateWithParams(String query, Consumer<PreparedStatement> paramSetter, String logSource) throws SQLException {
        return withConnection(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                paramSetter.accept(pstmt);
                return pstmt.executeUpdate();
            }
        }, 0, logSource);
    }

    public static boolean tableExists(String tableName, String logSource) {
        return withConnection(conn -> {
            try (ResultSet rs = conn.getMetaData().getTables(null, null, tableName, null)) {
                return rs.next();
            }
        }, false, logSource);
    }

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