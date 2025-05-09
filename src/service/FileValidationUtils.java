package service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class FileValidationUtils {
    private static final Set<String> BLOCKED_EXTENSIONS = new HashSet<>(Arrays.asList(
            "exe", "bat", "sh", "cmd", "com", "vbs", "js", "ps1", "msi", "dll"
    ));

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

    public static String sanitizeFileName(String filename) {
        if (filename == null) {
            return "";
        }
        // Replace any path-like characters with underscores
        return filename.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}