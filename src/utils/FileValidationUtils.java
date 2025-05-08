package utils;

import java.util.Arrays;

/**
 * Utility class for file validation operations
 */
public class FileValidationUtils {

    /**
     * List of blocked file extensions for security
     */
    private static final String[] BLOCKED_EXTENSIONS = {
            "exe", "bat", "sh", "cmd", "com", "vbs", "js", "ps1", "msi", "dll"
    };

    /**
     * Checks if the file type is in the blocked list
     * @param fileName The filename to check
     * @return true if the file type is blocked, false otherwise
     */
    public static boolean isBlockedFileType(String fileName) {
        String extension = "";
        int lastDot = fileName.lastIndexOf(".");
        if (lastDot > 0) {
            extension = fileName.substring(lastDot + 1).toLowerCase();
        }

        // Block potentially dangerous file types
        return Arrays.asList(BLOCKED_EXTENSIONS).contains(extension);
    }
}