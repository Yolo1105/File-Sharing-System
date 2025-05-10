package constants;

public final class Constants {
    private Constants() {
        throw new AssertionError("Constants class should not be instantiated");
    }

    public static final class ErrorMessages {
        // Common errors
        public static final String ERR_MISSING_FILENAME = "Missing filename for %s";
        public static final String ERR_FILE_NOT_FOUND = "File not found: %s";
        public static final String ERR_BLOCKED_FILE_TYPE = "This file type is not allowed for security reasons";
        public static final String ERR_FILE_TOO_LARGE = "File exceeds maximum size limit";
        public static final String ERR_DECODE_FILENAME = "Failed to decode filename";
        public static final String ERR_ENCODE_FILENAME = "Failed to encode filename: %s";

        // Connection errors
        public static final String ERR_CONNECTION_LOST = "Connection lost: %s";
        public static final String ERR_TIMEOUT = "Connection timed out: %s";
        public static final String ERR_SERVER_TIMEOUT = "Server response timeout. Try again or check server connection";

        // Database errors
        public static final String ERR_INIT_FAILED = "Failed to initialize database logger";
        public static final String ERR_LOG_FAILED = "Failed to log action";
        public static final String ERR_LOGS_FAILED = "Failed to retrieve logs";

        // Command errors
        public static final String ERR_UNKNOWN_COMMAND = "Unknown command. Available commands: UPLOAD <filename>, DOWNLOAD <filename>, DELETE <filename>, LIST, LOGS [count]";
        public static final String ERR_COMMAND_FAILED = "Command execution failed: %s";

        // File operation errors
        public static final String ERR_UPLOAD_FAILED = "Upload failed: %s";
        public static final String ERR_DOWNLOAD_FAILED = "Download failed: %s";
        public static final String ERR_DELETE_FAILED = "Delete failed: %s";
        public static final String ERR_INVALID_FILESIZE = "Invalid file size reported: %s";
        public static final String ERR_INVALID_CHECKSUM = "Invalid checksum length: %s";
        public static final String ERR_CORRUPTED = "Downloaded file is corrupted. Checksum verification failed";
        public static final String ERR_FILE_TRANSFER = "File transfer failed";
        public static final String ERR_FILE_NOT_FOUND_DB = "File not found in database";
        public static final String ERR_CHECKSUM_VERIFICATION = "Checksum verification failed";
        public static final String ERR_SQL = "Failed to executing SQL: %s";
        public static final String ERR_RETRIEVE_FAILED = "Failed to retrieve file: %s";

        // Server errors
        public static final String ERR_START_SERVER = "Could not start server on port";
        public static final String ERR_ACCEPT_CONNECTION = "Failed to accepting client connection";
        public static final String ERR_THREAD_POOL = "Thread pool did not terminate";
        public static final String ERR_DATABASE_SCHEMA = "Failed to initialize database schema";
        public static final String ERR_THREAD_INTERRUPTED = "Thread interrupted while waiting to queue task";
    }

    public static final class SuccessMessages {
        public static final String SUCCESS_UPLOAD = "Upload successful to database";
        public static final String SUCCESS_DOWNLOAD = "Download completed successfully";
        public static final String SUCCESS_DELETE = "File '%s' was successfully deleted from the server";
        public static final String SUCCESS_FILE_SAVED = "File saved to database";
        public static final String SUCCESS_FILE_RECEIVED = "File received successfully";
        public static final String SUCCESS_FILE_DELETED = "File deleted successfully";
    }

    public static final class InfoMessages {
        public static final String INFO_CONNECTED = "Connected to server at %s:%d";
        public static final String INFO_PROCESSING = "Processing %s command for: %s";
        public static final String INFO_PREPARING = "Preparing to upload: %s (%d bytes)";
        public static final String INFO_PROGRESS = "%s progress: %d%%";
        public static final String INFO_COMPLETED = "%s completed";
        public static final String INFO_COMMAND_USAGE = "Usage: %s <filepath> or %s \"<filepath with spaces>\"";

        // File manager info messages
        public static final String INFO_FILE_RECEPTION_START = "Starting file reception for: %s";
        public static final String INFO_BLOCKED_FILE_REJECTED = "Rejected blocked file type: %s";
        public static final String INFO_BLOCKED_DOWNLOAD_REJECTED = "Rejected blocked file type download: %s";
        public static final String INFO_FILEMANAGER_INIT = "FileManager initialized";
        public static final String INFO_RECEIVING_FILE = "Receiving file: %s";
        public static final String INFO_FILE_SIZE = "File size reported: %d bytes";
        public static final String INFO_CHECKSUM_LENGTH = "Checksum length: %d";
        public static final String INFO_CHECKSUM_RECEIVED = "Checksum received";
        public static final String INFO_CHECKSUM_VERIFIED = "Checksum verification successful";
        public static final String INFO_START_FILE_TRANSFER = "Starting file data transfer";
        public static final String INFO_FILE_TRANSFER_COMPLETE = "File data transfer complete";
        public static final String INFO_RECEIVE_PROGRESS = "Receive progress: %d%%";
        public static final String INFO_SENDING_FILE = "Sending file: %s";
        public static final String INFO_SENDING_FILE_SIZE = "Sending file size: %d bytes";
        public static final String INFO_SENDING_CHECKSUM = "Sending checksum of length: %d bytes";
        public static final String INFO_FILE_SENT = "File sent successfully: %s";
        public static final String INFO_FILE_LIST_HEADER = "Available files:";
        public static final String INFO_FILE_LIST_ENTRY = " - %s (%d bytes)";
        public static final String INFO_NO_FILES = "No files available.";
        public static final String INFO_FILE_LIST_ERROR = "Failed to listing files. Please try again later.";

        // Server info messages
        public static final String INFO_SERVER_STARTING = "Starting server on port %d with %d core threads, %d max threads";
        public static final String INFO_SERVER_STARTED = "Server started successfully";
        public static final String INFO_SHUTDOWN = "Server shutting down...";
        public static final String INFO_SHUTDOWN_COMPLETE = "Server shutdown complete";
        public static final String INFO_NEW_CONNECTION = "New connection accepted from: %s (Active connections: %d)";
        public static final String INFO_CONNECTION_CLOSED = "Connection closed. Active connections: %d";
    }
}