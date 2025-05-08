package constants;

/**
 * Centralized collection of informational messages used throughout the application.
 * This approach promotes consistency and makes message maintenance easier.
 */
public class InfoMessages {
    // Command usage info
    public static final String COMMAND_USAGE = "Usage: %s <filepath> or %s \"<filepath with spaces>\"";

    // Server information
    public static final String SERVER_STARTING = "Starting server on port %d with %d core threads, %d max threads";
    public static final String SERVER_STARTED = "Server started successfully";
    public static final String SERVER_SHUTDOWN = "Server shutting down...";
    public static final String SERVER_SHUTDOWN_COMPLETE = "Server shutdown complete";
    public static final String NEW_CONNECTION = "New connection accepted from: %s (Active connections: %d)";
    public static final String CONNECTION_CLOSED = "Connection closed. Active connections: %d";

    // Client information
    public static final String CLIENT_STARTING = "Starting client...";
    public static final String CLIENT_CONNECTED = "Connected to server at %s:%d";
    public static final String PROCESSING_COMMAND = "Processing %s command for: %s";

    // File operations
    public static final String PREPARING_UPLOAD = "Preparing to upload: %s (%d bytes)";
    public static final String OPERATION_PROGRESS = "%s progress: %d%%";
    public static final String OPERATION_COMPLETED = "%s completed";
    public static final String RECEIVING_FILE = "Receiving file: %s (%d bytes)";
    public static final String DOWNLOAD_SAVED = "Downloaded %s to folder: %s";
    public static final String FILE_PATH = "File saved to: %s";

    // Server requests
    public static final String REQUEST_LIST = "Requesting file list from server...";
    public static final String REQUEST_LOGS = "Requesting %d recent logs from server...";

    // Database operations
    public static final String DB_CONNECTING = "Connecting to database: %s";
    public static final String DB_INITIALIZED = "Database initialized with %d connections";
    public static final String DB_TABLES_CREATED = "Database tables created successfully";

    // Thread operations
    public static final String THREAD_STARTED = "Thread started: %s";
    public static final String THREAD_COMPLETED = "Thread completed: %s";
}