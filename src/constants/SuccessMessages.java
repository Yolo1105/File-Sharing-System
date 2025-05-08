package constants;

/**
 * Centralized collection of success messages used throughout the application.
 * This approach promotes consistency and makes message maintenance easier.
 */
public class SuccessMessages {
    // File operations success messages
    public static final String FILE_SAVED = "File saved to database";
    public static final String FILE_RECEIVED = "File received successfully";
    public static final String UPLOAD_SUCCESSFUL = "Upload successful to database";
    public static final String DOWNLOAD_COMPLETED = "Download completed successfully";
    public static final String FILE_UPLOADED = "File '%s' was successfully uploaded to the server database";

    // Server status messages
    public static final String SERVER_STARTED = "Server started successfully";
    public static final String SERVER_SHUTDOWN_COMPLETE = "Server shutdown complete";

    // Database operations
    public static final String DB_INITIALIZED = "Database initialized successfully";
    public static final String DB_CONNECTED = "Database connection established";
    public static final String DB_TABLES_VERIFIED = "Database tables verified";

    // Client operations
    public static final String CLIENT_CONNECTED = "Connected to server successfully";
    public static final String CLIENT_AUTHENTICATED = "Client authenticated successfully";
}