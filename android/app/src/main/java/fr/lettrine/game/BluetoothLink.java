package fr.lettrine.game;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;

import org.json.JSONObject;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

final class BluetoothLink implements Closeable {
    interface Listener {
        void connected(BluetoothLink link);
        void received(BluetoothLink link, JSONObject row);
        void lost(BluetoothLink link, String reason);
    }

    private static final UUID SERVICE = UUID.fromString("8b99652a-287e-48e2-904a-67d02b41ac99");
    private static final int MAX_FRAME = 1_048_576;
    private final Listener listener;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private volatile BluetoothServerSocket server;
    private volatile BluetoothSocket socket;
    private volatile DataOutputStream output;
    private volatile boolean closed;

    BluetoothLink(Listener listener) { this.listener = listener; }

    void start(BluetoothAdapter adapter, BluetoothDevice device) {
        new Thread(() -> {
            try {
                if (device == null) {
                    BluetoothServerSocket opened = adapter.listenUsingRfcommWithServiceRecord("Lettrine", SERVICE);
                    server = opened;
                    if (closed) {
                        opened.close();
                        return;
                    }
                    socket = opened.accept(90_000);
                    opened.close();
                } else {
                    socket = device.createRfcommSocketToServiceRecord(SERVICE);
                    if (closed) {
                        socket.close();
                        return;
                    }
                    socket.connect();
                }
                if (closed) {
                    socket.close();
                    return;
                }
                output = new DataOutputStream(socket.getOutputStream());
                DataInputStream input = new DataInputStream(socket.getInputStream());
                listener.connected(this);
                while (!closed) {
                    int size = input.readInt();
                    if (size <= 0 || size > MAX_FRAME) throw new IOException("Message Bluetooth invalide.");
                    byte[] bytes = new byte[size];
                    input.readFully(bytes);
                    listener.received(this, new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
                }
            } catch (SecurityException exception) {
                fail("L’autorisation Bluetooth n’est plus disponible.");
            } catch (Exception exception) {
                fail("La connexion Bluetooth a été interrompue.");
            }
        }, "Lettrine Bluetooth").start();
    }

    void send(JSONObject message) {
        if (closed || output == null) return;
        byte[] bytes = message.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FRAME) {
            fail("Le message Bluetooth est trop volumineux.");
            return;
        }
        try {
            writer.execute(() -> {
                try {
                    output.writeInt(bytes.length);
                    output.write(bytes);
                    output.flush();
                } catch (IOException exception) {
                    fail("La connexion Bluetooth a été interrompue.");
                }
            });
        } catch (RejectedExecutionException ignored) {
            // The link closed before the send could start.
        }
    }

    private synchronized void fail(String reason) {
        if (closed) return;
        close();
        listener.lost(this, reason);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        writer.shutdownNow();
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }
}
