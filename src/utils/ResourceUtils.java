package utils;

import java.io.*;
import java.sql.*;
import java.net.Socket;
import java.util.function.Consumer;
import logs.Logger; // Add import

/**
 * Enhanced utility class for resource management operations
 * Provides standardized methods for safely handling resources
 */
public class ResourceUtils {
    /**
     * Safely closes a resource and logs any errors
     * @param resource The resource to close
     * @param resourceName Name of the resource for logging
     * @param logger Logger instance
     */
    public static void safeClose(AutoCloseable resource, String resourceName, Logger logger) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception e) {
                if (logger != null) {
                    logger.log(Logger.Level.ERROR, "ResourceUtils", "Error closing " + resourceName, e);
                } else {
                    System.err.println("Error closing " + resourceName + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Safely closes a resource without logging
     * @param resource The resource to close
     */
    public static void safeClose(AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception e) {
                // Silently ignore closure errors when no logger is provided
                System.err.println("Error closing resource: " + e.getMessage());
            }
        }
    }

    /**
     * Uses a resource and ensures it is closed afterward
     * Similar to try-with-resources but as a method
     *
     * @param <T> The type of resource
     * @param <R> The return type
     * @param resourceSupplier A function that creates the resource
     * @param action A function that uses the resource and returns a result
     * @param logger Logger instance for error reporting
     * @param resourceName Name of the resource for error reporting
     * @return The result of the action
     * @throws Exception If an error occurs during resource creation or use
     */
    public static <T extends AutoCloseable, R> R withResource(
            ResourceSupplier<T> resourceSupplier,
            ResourceAction<T, R> action,
            Logger logger,
            String resourceName) throws Exception {

        T resource = null;
        try {
            resource = resourceSupplier.get();
            return action.perform(resource);
        } catch (Exception e) {
            if (logger != null) {
                logger.log(Logger.Level.ERROR, "ResourceUtils",
                        "Error using " + resourceName, e);
            }
            throw e;
        } finally {
            safeClose(resource, resourceName, logger);
        }
    }

    /**
     * Uses a resource without a return value and ensures it is closed afterward
     *
     * @param <T> The type of resource
     * @param resourceSupplier A function that creates the resource
     * @param action A consumer that uses the resource
     * @param logger Logger instance for error reporting
     * @param resourceName Name of the resource for error reporting
     * @throws Exception If an error occurs during resource creation or use
     */
    public static <T extends AutoCloseable> void withResource(
            ResourceSupplier<T> resourceSupplier,
            Consumer<T> action,
            Logger logger,
            String resourceName) throws Exception {

        T resource = null;
        try {
            resource = resourceSupplier.get();
            action.accept(resource);
        } catch (Exception e) {
            if (logger != null) {
                logger.log(Logger.Level.ERROR, "ResourceUtils",
                        "Error using " + resourceName, e);
            }
            throw e;
        } finally {
            safeClose(resource, resourceName, logger);
        }
    }

    /**
     * Safely closes multiple resources
     * @param logger Logger instance for error reporting
     * @param resources Variable number of resources to close
     */
    public static void safeCloseAll(Logger logger, AutoCloseable... resources) {
        if (resources != null) {
            for (int i = resources.length - 1; i >= 0; i--) {
                safeClose(resources[i], "resource-" + i, logger);
            }
        }
    }

    /**
     * Safely delete a file
     * @param file The file to delete
     * @param logger Logger instance
     * @return true if the file was deleted successfully, false otherwise
     */
    public static boolean safeDelete(File file, Logger logger) {
        if (file != null && file.exists()) {
            try {
                boolean deleted = file.delete();
                if (!deleted && logger != null) {
                    logger.log(Logger.Level.WARNING, "ResourceUtils",
                            "Failed to delete file: " + file.getAbsolutePath());
                }
                return deleted;
            } catch (Exception e) {
                if (logger != null) {
                    logger.log(Logger.Level.ERROR, "ResourceUtils",
                            "Error deleting file: " + file.getAbsolutePath(), e);
                }
                return false;
            }
        }
        return false;
    }

    /**
     * Safely creates directories for a file path
     * @param file The file whose parent directories should be created
     * @param logger Logger instance
     * @return true if directories were created successfully or already exist, false otherwise
     */
    public static boolean safeCreateDirectories(File file, Logger logger) {
        if (file != null) {
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                try {
                    boolean created = parentDir.mkdirs();
                    if (!created && !parentDir.exists() && logger != null) {
                        logger.log(Logger.Level.WARNING, "ResourceUtils",
                                "Failed to create directories for: " + file.getAbsolutePath());
                    }
                    return created || parentDir.exists();
                } catch (Exception e) {
                    if (logger != null) {
                        logger.log(Logger.Level.ERROR, "ResourceUtils",
                                "Error creating directories for: " + file.getAbsolutePath(), e);
                    }
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Functional interface for creating a resource
     * @param <T> The type of resource
     */
    @FunctionalInterface
    public interface ResourceSupplier<T extends AutoCloseable> {
        T get() throws Exception;
    }

    /**
     * Functional interface for using a resource and returning a result
     * @param <T> The type of resource
     * @param <R> The return type
     */
    @FunctionalInterface
    public interface ResourceAction<T extends AutoCloseable, R> {
        R perform(T resource) throws Exception;
    }
}