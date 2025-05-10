package utils;

import java.io.*;
import logs.Logger;

public class IOUtils {
    private static final Logger logger = Logger.getInstance();

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
}