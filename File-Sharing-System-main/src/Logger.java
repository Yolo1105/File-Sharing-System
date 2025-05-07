import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Logger {
    public enum Level {
        INFO, WARNING, ERROR, FATAL
    }

    private static final String LOG_FILE = "server.log";
    private static final Logger instance = new Logger();

    private PrintWriter logWriter;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private Logger() {
        try {
            logWriter = new PrintWriter(new FileWriter(LOG_FILE, true), true);
            log(Level.INFO, "Logger", "Logging system initialized");
        } catch (IOException e) {
            System.err.println("Failed to initialize logger: " + e.getMessage());
            e.printStackTrace();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    }

    public static Logger getInstance() {
        return instance;
    }

    public void log(Level level, String source, String message) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        String logEntry = String.format("[%s] [%s] [%s] %s", timestamp, level, source, message);

        // Write to console
        if (level == Level.ERROR || level == Level.FATAL) {
            System.err.println(logEntry);
        } else {
            System.out.println(logEntry);
        }

        // Write to file
        synchronized (logWriter) {
            logWriter.println(logEntry);
        }
    }

    public void log(Level level, String source, String message, Throwable throwable) {
        log(level, source, message + ": " + throwable.getMessage());

        // Log stack trace to file
        synchronized (logWriter) {
            throwable.printStackTrace(logWriter);
            logWriter.flush();
        }

        // Print stack trace to console for severe errors
        if (level == Level.ERROR || level == Level.FATAL) {
            throwable.printStackTrace(System.err);
        }
    }

    private void close() {
        synchronized (logWriter) {
            log(Level.INFO, "Logger", "Logging system shutting down");
            logWriter.close();
        }
    }
}