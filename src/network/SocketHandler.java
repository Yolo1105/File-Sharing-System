package network;

import java.net.Socket;
import java.net.SocketException;
import config.Config;

public class SocketHandler {
    public static void configureSocket(Socket socket, int timeout) throws SocketException {
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(timeout);
    }

    public static void configureFileTransferSocket(Socket socket) throws SocketException {
        configureSocket(socket, Config.getFileTransferTimeout());
    }

    public static void configureStandardSocket(Socket socket) throws SocketException {
        configureSocket(socket, Config.getSocketTimeout());
    }
}