/**
 * Utility class for resource management operations
 */
public class ResourceUtils {
    /**
     * Safely closes a resource and logs any errors
     * @param resource The resource to close
     * @param resourceName Name of the resource for logging
     * @param logger Logger instance
     */
    public static void safeClose(AutoCloseable resource, String resourceName, Logger logger) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception e) {
                logger.log(Logger.Level.ERROR, "ResourceUtils", "Error closing " + resourceName, e);
            }
        }
    }

    /**
     * Safely closes a resource without logging
     * @param resource The resource to close
     */
    public static void safeClose(AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception e) {
                // Silently ignore closure errors when no logger is provided
                System.err.println("Error closing resource: " + e.getMessage());
            }
        }
    }
}