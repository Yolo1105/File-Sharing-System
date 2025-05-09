package service;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Utility class for file validation operations, including security checks
 * and filename sanitization.
 */
public class FileValidationUtils {

    // Create an unmodifiable set of blocked file extensions for security
    private static final Set<String> BLOCKED_EXTENSIONS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "exe", "bat", "sh", "cmd", "com", "vbs", "js", "ps1", "msi", "dll"
            ))
    );

    /**
     * Checks if a file's extension is in the blocked list.
     *
     * @param fileName The file name to check
     * @return true if the file extension is blocked, false otherwise
     */
    public static boolean isBlockedFileType(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }

        String extension = "";
        int lastDot = fileName.lastIndexOf(".");
        if (lastDot > 0) {
            extension = fileName.substring(lastDot + 1).toLowerCase();
        }

        return BLOCKED_EXTENSIONS.contains(extension);
    }

    /**
     * Sanitizes a filename by replacing any characters that could be used
     * for path traversal or other file system attacks.
     *
     * @param filename The filename to sanitize
     * @return The sanitized filename
     */
    public static String sanitizeFileName(String filename) {
        if (filename == null) {
            return "";
        }
        // Replace any path-like characters with underscores
        return filename.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /**
     * Checks if a filename contains any path traversal sequences.
     *
     * @param filename The filename to check
     * @return true if the filename contains path traversal sequences, false otherwise
     */
    public static boolean containsPathTraversal(String filename) {
        if (filename == null) {
            return false;
        }

        // Check for common path traversal patterns
        return filename.contains("../") ||
                filename.contains("..\\") ||
                filename.contains("/..") ||
                filename.contains("\\..");
    }
}