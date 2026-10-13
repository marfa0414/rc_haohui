package com.rc.notification.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Raw wire fault peer with bounded threads, explicit socket ownership and no generated HTTP headers. */
public final class RawHttpPeer implements AutoCloseable {
    @FunctionalInterface public interface Handler { void handle(Socket socket) throws Exception; }
    private final ServerSocket listener;
    private final Thread acceptor;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), runnable -> { var thread = new Thread(runnable, "cp08-raw-peer"); thread.setDaemon(true); return thread; });
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final List<Throwable> unexpected = new CopyOnWriteArrayList<>();
    public final AtomicInteger accepted = new AtomicInteger();
    private volatile boolean closed;
    public RawHttpPeer(Handler handler) throws IOException {
        listener = new ServerSocket(0, 16, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        acceptor = new Thread(() -> {
            while (!closed) {
                try {
                    Socket socket = listener.accept(); socket.setSoTimeout(5000); sockets.add(socket); accepted.incrementAndGet();
                    executor.execute(() -> {
                        try (socket) { handler.handle(socket); }
                        catch (IOException expectedConnectionClose) { }
                        catch (Throwable failure) { unexpected.add(failure); }
                        finally { sockets.remove(socket); }
                    });
                } catch (IOException stopping) { if (!closed) unexpected.add(stopping); }
            }
        }, "cp08-raw-acceptor");
        acceptor.setDaemon(true); acceptor.start();
    }
    public URI uri(String scheme, String path) { return URI.create(scheme + "://127.0.0.1:" + listener.getLocalPort() + path); }
    public List<Throwable> unexpectedFailures() { return List.copyOf(unexpected); }
    public int activeConnections() { return sockets.size(); }
    public static String readRequest(Socket socket) throws IOException {
        InputStream input = socket.getInputStream(); var head = new ByteArrayOutputStream();
        int tail = 0;
        while (head.size() <= 65536) {
            int next = input.read(); if (next < 0) throw new IOException("Peer closed before sending request headers");
            head.write(next); tail = (tail << 8) | next;
            if (tail == 0x0d0a0d0a) break;
        }
        if (head.size() > 65536) throw new IOException("Test request exceeds expected size");
        String headers = head.toString(StandardCharsets.ISO_8859_1);
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                int length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                if (length > 262144 || input.readNBytes(length).length != length) throw new IOException("Incomplete test request Body");
            }
        }
        return headers;
    }
    public static void write(Socket socket, String value) throws IOException {
        socket.getOutputStream().write(value.getBytes(StandardCharsets.ISO_8859_1)); socket.getOutputStream().flush();
    }
    /** A timeout is not accepted as evidence that the client closed the physical connection. */
    public static boolean observePeerClose(Socket socket, Duration deadline) throws IOException {
        socket.setSoTimeout(Math.toIntExact(deadline.toMillis()));
        try {
            while (socket.getInputStream().read() >= 0) { }
            return true;
        } catch (SocketTimeoutException missingCancellation) { return false; }
        catch (SocketException reset) { return true; }
    }
    @Override public void close() throws Exception {
        closed = true; listener.close();
        for (Socket socket : sockets) socket.close();
        executor.shutdownNow(); acceptor.join(2000);
        if (!executor.awaitTermination(3, TimeUnit.SECONDS)) throw new IllegalStateException("Raw HTTP fixture leaked handler threads");
    }
}
