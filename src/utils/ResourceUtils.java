package utils;

import java.io.*;
import java.util.function.Consumer;
import logs.Logger;

public class ResourceUtils {
    private static final Logger logger = Logger.getInstance();

    /**
     * Safely closes a resource, logging any exceptions that occur during closing.
     *
     * @param resource     The resource to close
     * @param resourceName Description of the resource for logging
     * @param logger       Logger to use (if null, will use System.err)
     */
    public static void safeClose(AutoCloseable resource, String resourceName, Logger logger) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception e) {
                if (logger != null) {
                    logger.log(Logger.Level.ERROR, "ResourceUtils",
                            String.format("Error closing %s: %s", resourceName, e.getMessage()), e);
                } else {
                    System.err.println("Error closing " + resourceName + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Safely closes a resource without logging detailed information.
     *
     * @param resource The resource to close
     */
    public static void safeClose(AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception e) {
                // Use the default logger instance
                logger.log(Logger.Level.ERROR, "ResourceUtils",
                        String.format("Error closing resource: %s", e.getMessage()));
            }
        }
    }

    /**
     * Performs an action with a resource, ensuring the resource is properly closed.
     *
     * @param resourceSupplier Function that supplies the resource
     * @param action           Action to perform with the resource
     * @param logger           Logger to use
     * @param resourceName     Description of the resource for logging
     * @param <T>              Resource type that implements AutoCloseable
     * @param <R>              Return type of the action
     * @return Result of the action
     * @throws Exception If an error occurs
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
                        String.format("Error using %s: %s", resourceName, e.getMessage()), e);
            }
            throw e;
        } finally {
            safeClose(resource, resourceName, logger);
        }
    }

    /**
     * Performs an action with a resource, ensuring the resource is properly closed.
     *
     * @param resourceSupplier Function that supplies the resource
     * @param action           Action to perform with the resource
     * @param logger           Logger to use
     * @param resourceName     Description of the resource for logging
     * @param <T>              Resource type that implements AutoCloseable
     * @throws Exception If an error occurs
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
                        String.format("Error using %s: %s", resourceName, e.getMessage()), e);
            }
            throw e;
        } finally {
            safeClose(resource, resourceName, logger);
        }
    }

    /**
     * Safely closes multiple resources in reverse order.
     *
     * @param logger    Logger to use
     * @param resources Resources to close
     */
    public static void safeCloseAll(Logger logger, AutoCloseable... resources) {
        if (resources != null) {
            for (int i = resources.length - 1; i >= 0; i--) {
                safeClose(resources[i], "resource-" + i, logger);
            }
        }
    }

    /**
     * Safely deletes a file, logging any errors.
     *
     * @param file   File to delete
     * @param logger Logger to use
     * @return True if deletion was successful, false otherwise
     */
    public static boolean safeDelete(File file, Logger logger) {
        if (file != null && file.exists()) {
            try {
                boolean deleted = file.delete();
                if (!deleted && logger != null) {
                    logger.log(Logger.Level.WARNING, "ResourceUtils",
                            String.format("Failed to delete file: %s", file.getAbsolutePath()));
                }
                return deleted;
            } catch (Exception e) {
                if (logger != null) {
                    logger.log(Logger.Level.ERROR, "ResourceUtils",
                            String.format("Error deleting file: %s", file.getAbsolutePath()), e);
                }
                return false;
            }
        }
        return false;
    }

    /**
     * Safely creates directories for a file, logging any errors.
     *
     * @param file   File whose directories need to be created
     * @param logger Logger to use
     * @return True if directories were created or already exist, false otherwise
     */
    public static boolean safeCreateDirectories(File file, Logger logger) {
        if (file != null) {
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                try {
                    boolean created = parentDir.mkdirs();
                    if (!created && !parentDir.exists() && logger != null) {
                        logger.log(Logger.Level.WARNING, "ResourceUtils",
                                String.format("Failed to create directories for: %s", file.getAbsolutePath()));
                    }
                    return created || parentDir.exists();
                } catch (Exception e) {
                    if (logger != null) {
                        logger.log(Logger.Level.ERROR, "ResourceUtils",
                                String.format("Error creating directories for: %s", file.getAbsolutePath()), e);
                    }
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    @FunctionalInterface
    public interface ResourceSupplier<T extends AutoCloseable> {
        T get() throws Exception;
    }

    @FunctionalInterface
    public interface ResourceAction<T extends AutoCloseable, R> {
        R perform(T resource) throws Exception;
    }
}