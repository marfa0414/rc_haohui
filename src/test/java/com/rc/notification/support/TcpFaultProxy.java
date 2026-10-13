package com.rc.notification.support;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test-only real TCP proxy: cut established JDBC sockets without stopping another test's database. */
public final class TcpFaultProxy implements AutoCloseable {
    private final ServerSocket listener;
    private final InetSocketAddress destination;
    private final AtomicBoolean available = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Semaphore permits = new Semaphore(8);
    private final ExecutorService connections = Executors.newFixedThreadPool(8);
    private final ExecutorService reverse = Executors.newFixedThreadPool(8);
    private final Thread acceptor;
    private final URI original;

    public TcpFaultProxy(String jdbcUrl) throws IOException {
        original = URI.create(jdbcUrl.substring("jdbc:".length()));
        destination = new InetSocketAddress(original.getHost(), original.getPort() < 0 ? 5432 : original.getPort());
        listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        acceptor = new Thread(this::accept, "test-pg-proxy-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public String jdbcUrl() {
        String query = original.getRawQuery() == null ? "" : "?" + original.getRawQuery();
        return "jdbc:postgresql://127.0.0.1:" + listener.getLocalPort() + original.getRawPath() + query;
    }

    public void cut() {
        available.set(false);
        sockets.forEach(TcpFaultProxy::closeSocket);
    }

    public void restore() { available.set(true); }

    private void accept() {
        while (!closed.get()) {
            try {
                Socket downstream = listener.accept();
                if (!available.get() || !permits.tryAcquire()) {
                    downstream.close();
                    continue;
                }
                sockets.add(downstream);
                connections.execute(() -> bridge(downstream));
            } catch (SocketException stopped) {
                if (!closed.get()) throw new IllegalStateException(stopped);
            } catch (IOException failure) {
                if (!closed.get()) throw new IllegalStateException(failure);
            }
        }
    }

    private void bridge(Socket downstream) {
        Socket upstream = new Socket();
        sockets.add(upstream);
        try (downstream; upstream) {
            if (!available.get()) return;
            upstream.connect(destination, 1000);
            if (!available.get()) return;
            reverse.execute(() -> {
                try {
                    upstream.getInputStream().transferTo(downstream.getOutputStream());
                } catch (IOException expectedOnCut) {
                    // Closing either side is the injected fault or normal client shutdown.
                } finally {
                    closeSocket(downstream);
                    closeSocket(upstream);
                }
            });
            downstream.getInputStream().transferTo(upstream.getOutputStream());
        } catch (IOException expectedOnCut) {
            // Fault injection deliberately terminates live traffic.
        } finally {
            sockets.remove(downstream);
            sockets.remove(upstream);
            permits.release();
        }
    }

    private static void closeSocket(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }

    @Override public void close() throws IOException {
        closed.set(true);
        cut();
        listener.close();
        connections.shutdownNow();
        reverse.shutdownNow();
        acceptor.interrupt();
    }
}
