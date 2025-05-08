import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class Logger {
    public enum Level {
        DEBUG, INFO, WARNING, ERROR, FATAL;

        public boolean isAtLeastAsImportantAs(Level other) {
            return this.ordinal() >= other.ordinal();
        }
    }

    // Singleton instance
    private static final Logger instance = new Logger();

    // File configuration
    private static final String LOG_FILE = "server.log";
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

    /**
     * Private constructor for singleton pattern
     */
    private Logger() {
        try {
            // Create the log file directory if it doesn't exist
            java.io.File logDir = new java.io.File("logs");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }

            // Initialize log file with default in logs directory
            String logFilePath = "logs/" + LOG_FILE;
            logWriter = new PrintWriter(new FileWriter(logFilePath, true), true);

            // Start the logging thread
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
     * Get the singleton instance
     */
    public static Logger getInstance() {
        return instance;
    }

    /**
     * Starts the logging thread to consume from the queue
     */
    private void startLoggingThread() {
        loggingThread = new Thread(() -> {
            try {
                System.out.println("[INFO] [Logger] Logging thread started");

                while (running.get() || !logQueue.isEmpty()) {
                    try {
                        // Process one message or wait if queue is empty
                        String logEntry = logQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (logEntry != null) {
                            // Write to file
                            synchronized (logWriter) {
                                logWriter.println(logEntry);
                                logWriter.flush();
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
     * Sets the log level threshold. Only logs at this level or higher are recorded.
     */
    public void setLogLevel(Level level) {
        this.logLevelThreshold = level;
        log(Level.INFO, "Logger", "Log level set to: " + level);
    }

    /**
     * Enables or disables debug mode
     */
    public void setDebugMode(boolean debug) {
        this.debugMode = debug;
        log(Level.INFO, "Logger", "Debug mode set to: " + debug);
    }

    /**
     * Enables or disables console output
     */
    public void setConsoleOutput(boolean enabled) {
        this.consoleOutput = enabled;
        log(Level.INFO, "Logger", "Console output set to: " + enabled);
    }

    /**
     * Formats a log entry with timestamp and level
     */
    private String formatLogEntry(Level level, String source, String message) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        return String.format("[%s] [%s] [%s] %s", timestamp, level, source, message);
    }

    /**
     * Logs a message if its level is at or above the current threshold
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
     * Logs an exception with stack trace
     */
    public void log(Level level, String source, String message, Throwable throwable) {
        log(level, source, message + ": " + throwable.getMessage());

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

    /**
     * Convenience method for static logging without requiring the instance directly
     */
    public static void logStatic(Level level, String source, String message) {
        getInstance().log(level, source, message);
    }

    /**
     * Convenience method for static exception logging
     */
    public static void logStatic(Level level, String source, String message, Throwable throwable) {
        getInstance().log(level, source, message, throwable);
    }

    /**
     * Utility method to log message to both file and console regardless of current settings
     */
    public static void console(Level level, String source, String message) {
        String formattedMsg = getInstance().formatLogEntry(level, source, message);
        if (level == Level.ERROR || level == Level.FATAL) {
            System.err.println(formattedMsg);
        } else {
            System.out.println(formattedMsg);
        }
        getInstance().logQueue.add(formattedMsg);
    }

    /**
     * Shutdown the logging system
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
                logWriter.close();
            }
        } catch (Exception e) {
            System.err.println("Error closing logger: " + e.getMessage());
        } finally {
            initialized.set(false);
        }
    }
}