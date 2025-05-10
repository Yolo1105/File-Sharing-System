package utils;

import java.io.*;
import logs.Logger;

public class IOProcessor {
    private static final Logger logger = Logger.getInstance();

    public static void closeCheck(AutoCloseable resource, String resourceName, Logger logger) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception e) {
                if (logger != null) {
                    logger.log(Logger.Level.ERROR, "IOProcessor",
                    String.format("Failed to closing %s: %s", resourceName, e.getMessage()), e);
                } else{
                    System.err.println("Failed to closing " + resourceName + ": " + e.getMessage());
                }
            }
        }
    }

    public static void closeCheck(AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception e) {
                logger.log(Logger.Level.ERROR, "IOProcessor",
                String.format("Failed to closing resource: %s", e.getMessage()));
            }
        }
    }

    public static void closeCheckAll(Logger logger, AutoCloseable... resources) {
        if (resources != null) {
            for (int i = resources.length - 1; i >= 0; i--) {
                closeCheck(resources[i], "resource" + i, logger);
            }
        }
    }

    public static boolean deleteCheck(File file, Logger logger) {
        if (file != null && file.exists()) {
            try {
                boolean deleted = file.delete();
                if (!deleted && logger != null) {
                    logger.log(Logger.Level.WARNING, "IOProcessor",
                            String.format("Failed to delete file: %s", file.getAbsolutePath()));
                }
                return deleted;
            } catch (Exception e) {
                if (logger != null) {
                    logger.log(Logger.Level.ERROR, "IOProcessor",
                            String.format("Failed to deleting file: %s", file.getAbsolutePath()), e);
                }
                return false;
            }
        }
        return false;
    }
}