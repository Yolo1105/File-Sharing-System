package network; // Add package declaration

import java.net.Socket;
import java.net.SocketException;
import config.Config; // Add import

/**
 * Utility class for socket configuration operations
 */
public class SocketHandler {
    /**
     * Configures a socket with standard parameters
     * @param socket The socket to configure
     * @param timeout The timeout value in milliseconds
     */
    public static void configureSocket(Socket socket, int timeout) throws SocketException {
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(timeout);
    }

    /**
     * Configures a socket for file transfer operations with longer timeout
     * @param socket The socket to configure
     */
    public static void configureFileTransferSocket(Socket socket) throws SocketException {
        configureSocket(socket, Config.getFileTransferTimeout());
    }

    /**
     * Configures a socket for standard operations
     * @param socket The socket to configure
     */
    public static void configureStandardSocket(Socket socket) throws SocketException {
        configureSocket(socket, Config.getSocketTimeout());
    }
}