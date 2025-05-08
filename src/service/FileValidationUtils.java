package service;

import java.util.Arrays;

public class FileValidationUtils {
    private static final String[] BLOCKED_EXTENSIONS = {
            "exe", "bat", "sh", "cmd", "com", "vbs", "js", "ps1", "msi", "dll"
    };

    public static boolean isBlockedFileType(String fileName) {
        String extension = "";
        int lastDot = fileName.lastIndexOf(".");
        if (lastDot > 0) {
            extension = fileName.substring(lastDot + 1).toLowerCase();
        }

        return Arrays.asList(BLOCKED_EXTENSIONS).contains(extension);
    }
}