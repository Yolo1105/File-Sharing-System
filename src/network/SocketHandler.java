package network;

import config.Config;
import java.net.Socket;
import java.net.SocketException;

public class SocketHandler {
    public static void Socket(Socket socket, int timeout) throws SocketException {
        if (socket == null) {
            throw new IllegalArgumentException("Socket cannot be null");
        }
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(timeout);
    }

    public static void SocketSetup(Socket socket) throws SocketException {
        Socket(socket, Config.getSocketTimeout());
    }

    public static void FileTransferSocket(Socket socket) throws SocketException {
        Socket(socket, Config.getFileTransferTimeout());
    }
}