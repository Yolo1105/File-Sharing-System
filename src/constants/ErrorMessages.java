package constants;

public class ErrorMessages {
    // General errors
    public static final String COMMAND_FAILED = "Command execution failed: %s";
    public static final String UNKNOWN_COMMAND = "Unknown command. Available commands: UPLOAD <filename>, DOWNLOAD <filename>, LIST, LOGS [count]";
    public static final String INVALID_COMMAND = "Invalid command. Available commands: UPLOAD <filename>, DOWNLOAD <filename>, LIST, LOGS [count]";
    public static final String MISSING_FILENAME = "Missing filename for %s";

    // File validation errors
    public static final String BLOCKED_FILE_TYPE = "This file type is not allowed for security reasons";
    public static final String FILE_TOO_LARGE = "File exceeds maximum size limit";
    public static final String FILE_NOT_FOUND = "File not found: %s";
    public static final String INVALID_FILE_SIZE = "Invalid file size";

    // File transfer errors
    public static final String UPLOAD_FAILED = "Upload failed: %s";
    public static final String DOWNLOAD_FAILED = "Failed to send file: %s";
    public static final String FILE_TRANSFER_FAILED = "File transfer failed";
    public static final String ENCODE_FILENAME = "Failed to encode filename: %s";
    public static final String DECODE_FILENAME = "Failed to decode filename";
    public static final String EOF_DOWNLOAD = "Unexpected end of file during download";
    public static final String CORRUPTED_FILE = "Downloaded file is corrupted. Checksum verification failed.";
    public static final String SAVE_FAILED = "Failed to save downloaded file";

    // Checksum errors
    public static final String INVALID_CHECKSUM = "Invalid checksum length: %s";
    public static final String CHECKSUM_VERIFICATION = "Checksum verification failed";

    // Connection errors
    public static final String TIMEOUT = "Connection timed out: %s";
    public static final String CONNECTION_LOST = "Connection lost: %s";
    public static final String CONNECTION_ERROR = "Connection error: %s";
    public static final String SERVER_TIMEOUT = "Server response timeout. Try again or check server connection.";

    // Server errors
    public static final String START_SERVER = "Could not start server on port";
    public static final String ACCEPT_CONNECTION = "Error accepting client connection";
    public static final String CLOSE_SOCKET = "Error closing server socket";
    public static final String THREAD_POOL = "Thread pool did not terminate";

    // Database errors
    public static final String DB_INIT_FAILED = "Failed to initialize database logger";
    public static final String DB_LOG_FAILED = "Failed to log action";
    public static final String DB_LOGS_FAILED = "Failed to retrieve logs";
    public static final String DB_RESET_FAILED = "Failed to reset database";
    public static final String DB_OPERATION_FAILED = "Database operation failed";
}