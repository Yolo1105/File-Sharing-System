package network;

import java.net.Socket;
import java.net.SocketException;
import config.Config;

/**
 * Utility class for socket configuration to ensure consistent
 * socket settings across the application.
 */
public class SocketHandler {

    /**
     * Configures a socket with the specified timeout and standard optimizations.
     *
     * @param socket The socket to configure
     * @param timeout Timeout in milliseconds
     * @throws SocketException If an error occurs during configuration
     */
    public static void configureSocket(Socket socket, int timeout) throws SocketException {
        if (socket == null) {
            throw new IllegalArgumentException("Socket cannot be null");
        }

        // Enable TCP keep-alive to detect dead connections
        socket.setKeepAlive(true);

        // Disable Nagle's algorithm for better responsiveness
        socket.setTcpNoDelay(true);

        // Set the socket timeout
        socket.setSoTimeout(timeout);
    }

    /**
     * Configures a socket for file transfer operations with extended timeout.
     *
     * @param socket The socket to configure
     * @throws SocketException If an error occurs during configuration
     */
    public static void configureFileTransferSocket(Socket socket) throws SocketException {
        configureSocket(socket, Config.getFileTransferTimeout());
    }

    /**
     * Configures a socket for standard (non-file-transfer) operations.
     *
     * @param socket The socket to configure
     * @throws SocketException If an error occurs during configuration
     */
    public static void configureStandardSocket(Socket socket) throws SocketException {
        configureSocket(socket, Config.getSocketTimeout());
    }
}