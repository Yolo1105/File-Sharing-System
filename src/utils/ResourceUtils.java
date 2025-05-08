package utils;

import java.io.*;
import java.util.function.Consumer;
import logs.Logger;

public class ResourceUtils {
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

    public static void safeCloseAll(Logger logger, AutoCloseable... resources) {
        if (resources != null) {
            for (int i = resources.length - 1; i >= 0; i--) {
                safeClose(resources[i], "resource-" + i, logger);
            }
        }
    }

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

    @FunctionalInterface
    public interface ResourceSupplier<T extends AutoCloseable> {
        T get() throws Exception;
    }

    @FunctionalInterface
    public interface ResourceAction<T extends AutoCloseable, R> {
        R perform(T resource) throws Exception;
    }
}