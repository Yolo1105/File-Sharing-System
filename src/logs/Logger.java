package logs;
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
import utils.IOProcessor;

public class Logger {
    public enum Level {
        DEBUG, INFO, WARNING, ERROR, FATAL;
        public boolean isAtLeastAsImportantAs(Level other) {
            return this.ordinal() >= other.ordinal();
        }
    }

    private static final Logger instance = new Logger();
    private static final String LOG_DIRECTORY = "logs/";
    private static final String LOG_FILE = LOG_DIRECTORY + "server.log";
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private PrintWriter logWriter;
    private final BlockingQueue<String> logQueue = new LinkedBlockingQueue<>();

    private Thread loggingThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    private Level logLevelThreshold = Level.INFO;
    private boolean consoleOutput = true;

    private static final int FLUSH_THRESHOLD = 10;
    private int entriesSinceFlush = 0;

    private Logger() {
        try {
            initializeLogDirectory();
            initializeLogWriter();
            startLoggingThread();
            initialized.set(true);

            String initialMessage = logEntry(Level.INFO, "Logger", "Logging system initialized");
            logQueue.add(initialMessage);
            System.out.println(initialMessage);

            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        } catch (IOException e) {
            System.err.println("Failed to initialize logger: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void initializeLogDirectory() {
        java.io.File logDir = new java.io.File(LOG_DIRECTORY);
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
    }

    private void initializeLogWriter() throws IOException {
        logWriter = new PrintWriter(new FileWriter(LOG_FILE, true), true);
    }

    public static Logger getInstance() {
        return instance;
    }

    private void startLoggingThread() {
        loggingThread = new Thread(() -> {
            try {
                System.out.println("[INFO] [Logger] Logging thread started");

                while (running.get() || !logQueue.isEmpty()) {
                    try {
                        String logEntry = logQueue.poll(500, TimeUnit.MILLISECONDS);
                        if (logEntry != null) {
                            
                            synchronized (logWriter) {
                                logWriter.println(logEntry);
                                entriesSinceFlush++;

                                
                                if (entriesSinceFlush >= FLUSH_THRESHOLD || logQueue.isEmpty()) {
                                    logWriter.flush();
                                    entriesSinceFlush = 0;
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        if (!running.get()) {
                            break;
                        }
                    } catch (Exception e) {
                        System.err.println("Failed to in logging thread: " + e.getMessage());
                    }
                }

                synchronized (logWriter) {
                    logWriter.flush();
                }

                System.out.println("[INFO] [Logger] Logging thread terminated");
            } catch (Exception e) {
                System.err.println("[FATAL] Fatal error in logging thread: " + e.getMessage());
                e.printStackTrace();
            }
        });

        loggingThread.setDaemon(true);
        loggingThread.setName("LoggingThread");
        loggingThread.start();
    }

    private String logEntry(Level level, String source, String message) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        return String.format("[%s] [%s] [%s] %s", timestamp, level, source, message);
    }

    public void log(Level level, String source, String message) {
        if (!initialized.get() || !level.isAtLeastAsImportantAs(logLevelThreshold)) {
            if (level == Level.FATAL || level == Level.ERROR) {
                System.err.println(logEntry(level, source, message));
            }
            return;
        }

        String logEntry = logEntry(level, source, message);
        if (consoleOutput || level == Level.ERROR || level == Level.FATAL) {
            if (level == Level.ERROR || level == Level.FATAL) {
                System.err.println(logEntry);
            } else {
                System.out.println(logEntry);
            }
        }

        logQueue.add(logEntry);
    }

    public void log(Level level, String source, String message, Throwable throwable) {
        log(level, source, String.format("%s: %s", message, throwable.getMessage()));
        if (level.isAtLeastAsImportantAs(Level.WARNING)) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            logQueue.add(sw.toString());

            if (level == Level.ERROR || level == Level.FATAL) {
                throwable.printStackTrace(System.err);
            }
        }
    }

    public static void logInfo(String source, String message) {
        getInstance().log(Level.INFO, source, message);
    }
    public static void logWarning(String source, String message) {
        getInstance().log(Level.WARNING, source, message);
    }
    public static void logError(String source, String message) {
        getInstance().log(Level.ERROR, source, message);
    }
    public static void logDebug(String source, String message) {
        getInstance().log(Level.DEBUG, source, message);
    }

    public void shutdown() {
        if (!initialized.get()) {
            return;
        }

        try {
            log(Level.INFO, "Logger", "Logging system shutting down");
            running.set(false);
            if (loggingThread != null && loggingThread.isAlive()) {
                try {
                    loggingThread.join(5000); 
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            synchronized (logWriter) {
                logWriter.flush();
                IOProcessor.closeCheck(logWriter);
            }
        } catch (Exception e) {
            System.err.println("Failed to closing logger: " + e.getMessage());
        } finally {
            initialized.set(false);
        }
    }
}