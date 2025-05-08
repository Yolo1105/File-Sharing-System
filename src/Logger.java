import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Logger {
    public enum Level {
        INFO, WARNING, ERROR, FATAL
    }

    private static final String LOG_FILE = "server.log";
    private static final Logger instance = new Logger();
    private PrintWriter logWriter;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // Queue for asynchronous logging
    private final ConcurrentLinkedQueue<String> logQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    // Log level threshold - only log messages at this level or higher
    private Level logLevelThreshold = Level.INFO;
    private boolean debugMode = false;

    private Logger() {
        try {
            logWriter = new PrintWriter(new FileWriter(LOG_FILE, true), true);
            // Start the log flushing task that runs every 1 second
            scheduler.scheduleAtFixedRate(this::flushLogQueue, 1, 1, TimeUnit.SECONDS);

            // Check for debug mode in Config
            String debugModeStr = Config.getProperty("debug.mode", "false");
            debugMode = Boolean.parseBoolean(debugModeStr);

            log(Level.INFO, "Logger", "Logging system initialized. Debug mode: " + debugMode);
        } catch (IOException e) {
            System.err.println("Failed to initialize logger: " + e.getMessage());
            e.printStackTrace();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    }

    public static Logger getInstance() {
        return instance;
    }

    /**
     * Sets the log level threshold
     * @param level The minimum level to log
     */
    public void setLogLevel(Level level) {
        this.logLevelThreshold = level;
        log(Level.INFO, "Logger", "Log level set to: " + level);
    }

    /**
     * Enables or disables debug mode
     * @param debug True to enable debug mode, false to disable
     */
    public void setDebugMode(boolean debug) {
        this.debugMode = debug;
        log(Level.INFO, "Logger", "Debug mode set to: " + debug);
    }

    /**
     * Logs a message if its level is at or above the current threshold
     */
    public void log(Level level, String source, String message) {
        // Skip logging if level is below threshold (e.g. skip INFO logs if threshold is ERROR)
        if (level.ordinal() < logLevelThreshold.ordinal()) {
            return;
        }

        // Skip DEBUG logs if debug mode is disabled
        if (message.startsWith("[DEBUG]") && !debugMode) {
            return;
        }

        String timestamp = LocalDateTime.now().format(timeFormatter);
        String logEntry = String.format("[%s] [%s] [%s] %s", timestamp, level, source, message);

        // Write to console for important logs
        if (level == Level.ERROR || level == Level.FATAL || level == Level.WARNING) {
            if (level == Level.ERROR || level == Level.FATAL) {
                System.err.println(logEntry);
            } else {
                System.out.println(logEntry);
            }
        }

        // Queue for asynchronous file logging
        logQueue.add(logEntry);
    }

    public void log(Level level, String source, String message, Throwable throwable) {
        log(level, source, message + ": " + throwable.getMessage());

        // Only log stack traces for errors
        if (level == Level.ERROR || level == Level.FATAL) {
            // Use StringWriter for stack trace capture
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);

            // Queue the stack trace for logging
            logQueue.add(sw.toString());

            // Print stack trace to console for severe errors
            throwable.printStackTrace(System.err);
        }
    }

    /**
     * Flushes queued log entries to file
     */
    private void flushLogQueue() {
        if (logQueue.isEmpty()) {
            return;
        }

        synchronized (logWriter) {
            String entry;
            while ((entry = logQueue.poll()) != null) {
                logWriter.println(entry);
            }
            logWriter.flush();
        }
    }

    private void close() {
        synchronized (logWriter) {
            log(Level.INFO, "Logger", "Logging system shutting down");

            // Shutdown scheduler
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }

            // Flush any remaining logs
            flushLogQueue();

            // Close writer
            logWriter.close();
        }
    }
}