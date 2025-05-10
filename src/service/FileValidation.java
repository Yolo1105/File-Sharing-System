package service;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class FileValidation {
    private static final Set<String> BLOCKED_EXTENSIONS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                "exe", "bat", "sh", "cmd", "com", "vbs", "js", "ps1", "msi", "dll"
            ))
    );

    public static boolean checkBlockedFile(String inputFile) {
        if (inputFile == null || inputFile.isEmpty()) {
            return false;
        }

        String extension = "";
        int fileExtension = inputFile.lastIndexOf(".");
        if (fileExtension > 0) {
            extension = inputFile.substring(fileExtension + 1).toLowerCase();
        }
        return BLOCKED_EXTENSIONS.contains(extension);
    }

    public static String sanitizeFile(String filename) {
        if (filename == null) {
            return "";
        }
        return filename.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}