package logs;
import utils.ResourceUtils;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A thread-safe, asynchronous logging system with support for multiple log levels,
 * file output, and console mirroring.
 */
public class Logger {
    /**
     * Log levels in ascending order of importance.
     */
    public enum Level {
        DEBUG, INFO, WARNING, ERROR, FATAL;

        /**
         * Checks if this level is at least as important as another level.
         *
         * @param other The level to compare against
         * @return true if this level is at least as important as other
         */
        public boolean isAtLeastAsImportantAs(Level other) {
            return this.ordinal() >= other.ordinal();
        }
    }

    // Singleton instance
    private static final Logger instance = new Logger();

    // File configuration
    private static final String LOG_DIRECTORY = "logs/";
    private static final String LOG_FILE = LOG_DIRECTORY + "server.log";
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Writer and queue for async logging
    private PrintWriter logWriter;
    private final BlockingQueue<String> logQueue = new LinkedBlockingQueue<>();

    // Logging thread and control flags
    private Thread loggingThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    // Configuration
    private Level logLevelThreshold = Level.INFO;
    private boolean debugMode = false;
    private boolean consoleOutput = true;

    // Batch processing settings
    private static final int FLUSH_THRESHOLD = 10;
    private int entriesSinceFlush = 0;

    /**
     * Private constructor for singleton pattern.
     * Initializes the logging system.
     */
    private Logger() {
        try {
            initializeLogDirectory();
            initializeLogWriter();
            startLoggingThread();

            // Mark as initialized
            initialized.set(true);

            // Queue first log message
            String initialMessage = formatLogEntry(Level.INFO, "Logger", "Logging system initialized");
            logQueue.add(initialMessage);
            System.out.println(initialMessage);

            // Add shutdown hook to ensure clean shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        } catch (IOException e) {
            System.err.println("Failed to initialize logger: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Initializes the log directory.
     */
    private void initializeLogDirectory() {
        java.io.File logDir = new java.io.File(LOG_DIRECTORY);
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
    }

    /**
     * Initializes the log writer.
     *
     * @throws IOException If the log file can't be opened for writing
     */
    private void initializeLogWriter() throws IOException {
        logWriter = new PrintWriter(new FileWriter(LOG_FILE, true), true);
    }

    /**
     * Gets the singleton instance of the logger.
     *
     * @return The logger instance
     */
    public static Logger getInstance() {
        return instance;
    }

    /**
     * Starts the asynchronous logging thread.
     */
    private void startLoggingThread() {
        loggingThread = new Thread(() -> {
            try {
                System.out.println("[INFO] [Logger] Logging thread started");

                while (running.get() || !logQueue.isEmpty()) {
                    try {
                        // Process one message or wait if queue is empty
                        String logEntry = logQueue.poll(500, TimeUnit.MILLISECONDS);
                        if (logEntry != null) {
                            // Write to file with batched flushing
                            synchronized (logWriter) {
                                logWriter.println(logEntry);
                                entriesSinceFlush++;

                                // Only flush periodically or when the queue is empty
                                if (entriesSinceFlush >= FLUSH_THRESHOLD || logQueue.isEmpty()) {
                                    logWriter.flush();
                                    entriesSinceFlush = 0;
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        // Thread was interrupted, check if we should exit
                        Thread.currentThread().interrupt();
                        if (!running.get()) {
                            break;
                        }
                    } catch (Exception e) {
                        // Log to console if there's an error writing to the log file
                        System.err.println("Error in logging thread: " + e.getMessage());
                    }
                }

                // Final flush and cleanup
                synchronized (logWriter) {
                    logWriter.flush();
                }

                System.out.println("[INFO] [Logger] Logging thread terminated");
            } catch (Exception e) {
                System.err.println("[FATAL] Fatal error in logging thread: " + e.getMessage());
                e.printStackTrace();
            }
        });

        // Mark as daemon so it doesn't prevent JVM exit
        loggingThread.setDaemon(true);
        loggingThread.setName("LoggingThread");
        loggingThread.start();
    }

    /**
     * Sets the minimum log level threshold.
     *
     * @param level The minimum level to log
     */
    public void setLogLevel(Level level) {
        this.logLevelThreshold = level;
        log(Level.INFO, "Logger", String.format("Log level set to: %s", level));
    }

    /**
     * Sets debug mode which enables DEBUG level logs.
     *
     * @param debug true to enable debug mode, false otherwise
     */
    public void setDebugMode(boolean debug) {
        this.debugMode = debug;
        log(Level.INFO, "Logger", String.format("Debug mode set to: %s", debug));
    }

    /**
     * Sets whether logs should also be output to console.
     *
     * @param enabled true to enable console output, false otherwise
     */
    public void setConsoleOutput(boolean enabled) {
        this.consoleOutput = enabled;
        log(Level.INFO, "Logger", String.format("Console output set to: %s", enabled));
    }

    /**
     * Formats a log entry.
     *
     * @param level The log level
     * @param source The source of the log
     * @param message The log message
     * @return The formatted log entry
     */
    private String formatLogEntry(Level level, String source, String message) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        return String.format("[%s] [%s] [%s] %s", timestamp, level, source, message);
    }

    /**
     * Logs a message.
     *
     * @param level The log level
     * @param source The source of the log
     * @param message The log message
     */
    public void log(Level level, String source, String message) {
        // Skip logging if not initialized or if level is below threshold
        if (!initialized.get() || !level.isAtLeastAsImportantAs(logLevelThreshold)) {
            // Special case: still show FATAL and ERROR on console even if logging is not initialized
            if (level == Level.FATAL || level == Level.ERROR) {
                System.err.println(formatLogEntry(level, source, message));
            }
            return;
        }

        // Skip DEBUG logs if debug mode is disabled
        if (level == Level.DEBUG && !debugMode) {
            return;
        }

        String logEntry = formatLogEntry(level, source, message);

        // Write to console if enabled or for important logs
        if (consoleOutput || level == Level.ERROR || level == Level.FATAL) {
            if (level == Level.ERROR || level == Level.FATAL) {
                System.err.println(logEntry);
            } else {
                System.out.println(logEntry);
            }
        }

        // Queue for asynchronous file logging
        logQueue.add(logEntry);
    }

    /**
     * Logs a message with an associated throwable.
     *
     * @param level The log level
     * @param source The source of the log
     * @param message The log message
     * @param throwable The throwable to log
     */
    public void log(Level level, String source, String message, Throwable throwable) {
        log(level, source, String.format("%s: %s", message, throwable.getMessage()));

        // Only log stack traces for warnings, errors and fatal issues
        if (level.isAtLeastAsImportantAs(Level.WARNING)) {
            // Use StringWriter for stack trace capture
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);

            // Queue the stack trace for logging
            logQueue.add(sw.toString());

            // Print stack trace to console for severe errors
            if (level == Level.ERROR || level == Level.FATAL) {
                throwable.printStackTrace(System.err);
            }
        }
    }

    // Static convenience methods

    /**
     * Logs an info message.
     *
     * @param source The source of the log
     * @param message The log message
     */
    public static void logInfo(String source, String message) {
        getInstance().log(Level.INFO, source, message);
    }

    /**
     * Logs a warning message.
     *
     * @param source The source of the log
     * @param message The log message
     */
    public static void logWarning(String source, String message) {
        getInstance().log(Level.WARNING, source, message);
    }

    /**
     * Logs an error message.
     *
     * @param source The source of the log
     * @param message The log message
     */
    public static void logError(String source, String message) {
        getInstance().log(Level.ERROR, source, message);
    }

    /**
     * Logs an error message with an associated throwable.
     *
     * @param source The source of the log
     * @param message The log message
     * @param throwable The throwable to log
     */
    public static void logError(String source, String message, Throwable throwable) {
        getInstance().log(Level.ERROR, source, message, throwable);
    }

    /**
     * Logs a debug message.
     *
     * @param source The source of the log
     * @param message The log message
     */
    public static void logDebug(String source, String message) {
        getInstance().log(Level.DEBUG, source, message);
    }

    /**
     * Logs a fatal message with an associated throwable.
     *
     * @param source The source of the log
     * @param message The log message
     * @param throwable The throwable to log
     */
    public static void logFatal(String source, String message, Throwable throwable) {
        getInstance().log(Level.FATAL, source, message, throwable);
    }

    /**
     * Shuts down the logging system.
     */
    public void shutdown() {
        if (!initialized.get()) {
            return;
        }

        try {
            // Log the shutdown
            log(Level.INFO, "Logger", "Logging system shutting down");

            // Signal the logging thread to stop after processing remaining entries
            running.set(false);

            // Wait for the logging thread to finish (with timeout)
            if (loggingThread != null && loggingThread.isAlive()) {
                try {
                    loggingThread.join(5000); // 5 second timeout
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // Final sync flush of any remaining entries
            synchronized (logWriter) {
                logWriter.flush();
                ResourceUtils.safeClose(logWriter);
            }
        } catch (Exception e) {
            System.err.println("Error closing logger: " + e.getMessage());
        } finally {
            initialized.set(false);
        }
    }
}