package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Local change: an internet speed test for the Wi-Fi dropdown, against Cloudflare's public speed endpoints.
 *
 * Built to hold up on a slow or flaky link: each direction runs {@link #STREAMS} connections in parallel (one TCP
 * stream rarely fills a line), every stream keeps re-requesting and retries after an error until the phase ends,
 * the first {@link #WARMUP_MS} are left out (TLS setup and TCP slow start), and the running speed is reported live.
 * A direction only fails when no data moved at all. This is the only code in the launcher that uses the INTERNET
 * permission, and it runs only when the user presses the row.
 */
final class SpeedTest {

    interface Listener {
        /** Live reading while a direction runs: down true for download, mbps the speed so far (0 before data). */
        void onProgress(boolean down, double mbps);

        /** Final Mbps for each direction, or a negative value when that direction moved no data. */
        void onDone(double downMbps, double upMbps);
    }

    private static final String TAG = "TvHome";
    private static final String DOWN_URL = "https://speed.cloudflare.com/__down?bytes=25000000";
    private static final String UP_URL = "https://speed.cloudflare.com/__up";
    private static final int STREAMS = 4;
    private static final long PHASE_MS = 8000;
    private static final long WARMUP_MS = 1500;
    private static final long TICK_MS = 500;
    private static final long RETRY_PAUSE_MS = 300;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int CHUNK = 64 * 1024;
    private static final int UPLOAD_BODY = 4 * 1024 * 1024;

    private static volatile boolean running;

    private SpeedTest() {
    }

    /** Starts a test unless one is already running; false when one is. cancelled is polled from the workers. */
    static boolean start(Listener listener, BooleanSupplier cancelled) {
        if (running) return false;
        running = true;
        Handler main = new Handler(Looper.getMainLooper());
        Thread worker = new Thread(() -> {
            try {
                double down = phase(true, listener, main, cancelled);
                double up = cancelled.getAsBoolean() ? -1 : phase(false, listener, main, cancelled);
                main.post(() -> listener.onDone(down, up));
            } finally {
                running = false;
            }
        }, "speed-test");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    /** One direction: STREAMS workers move data until PHASE_MS; the Mbps after warm-up, or -1 if nothing moved. */
    private static double phase(boolean down, Listener listener, Handler main, BooleanSupplier cancelled) {
        AtomicLong bytes = new AtomicLong();
        long start = System.nanoTime();
        long end = start + PHASE_MS * 1_000_000;
        BooleanSupplier stop = () -> System.nanoTime() >= end || cancelled.getAsBoolean();
        Thread[] streams = new Thread[STREAMS];
        for (int i = 0; i < STREAMS; i++) {
            streams[i] = new Thread(() -> stream(down, bytes, stop), "speed-test-" + (down ? "down" : "up") + i);
            streams[i].setDaemon(true);
            streams[i].start();
        }
        long warmBytes = -1, warmAt = 0;
        while (!stop.getAsBoolean()) {
            sleep(TICK_MS);
            long now = System.nanoTime();
            if (warmBytes < 0 && now - start >= WARMUP_MS * 1_000_000) {
                warmBytes = bytes.get();
                warmAt = now;
            }
            double live = warmBytes >= 0 ? mbps(bytes.get() - warmBytes, now - warmAt) : mbps(bytes.get(), now - start);
            main.post(() -> listener.onProgress(down, live));
        }
        for (Thread t : streams) t.interrupt();
        long total = bytes.get();
        long now = System.nanoTime();
        if (total == 0) return -1;
        // A link so slow that nothing arrived before the warm-up ended: fall back to the whole phase.
        if (warmBytes <= 0 || total == warmBytes) return mbps(total, now - start);
        return mbps(total - warmBytes, now - warmAt);
    }

    /** One connection's loop: request, move data, repeat; an error pauses briefly and retries until stop. */
    private static void stream(boolean down, AtomicLong bytes, BooleanSupplier stop) {
        while (!stop.getAsBoolean()) {
            try {
                if (down) download(bytes, stop);
                else upload(bytes, stop);
            } catch (IOException e) {
                if (stop.getAsBoolean()) return;
                Log.w(TAG, "speed test " + (down ? "download" : "upload") + " stream retrying: " + e);
                sleep(RETRY_PAUSE_MS);
            }
        }
    }

    private static void download(AtomicLong bytes, BooleanSupplier stop) throws IOException {
        HttpURLConnection conn = open(DOWN_URL);
        try (InputStream in = conn.getInputStream()) {
            byte[] buffer = new byte[CHUNK];
            int n;
            while (!stop.getAsBoolean() && (n = in.read(buffer)) > 0) bytes.addAndGet(n);
        } finally {
            conn.disconnect();
        }
    }

    private static void upload(AtomicLong bytes, BooleanSupplier stop) throws IOException {
        HttpURLConnection conn = open(UP_URL);
        try {
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setFixedLengthStreamingMode(UPLOAD_BODY);
            byte[] chunk = new byte[CHUNK];
            try (OutputStream out = conn.getOutputStream()) {
                for (int sent = 0; sent < UPLOAD_BODY; sent += CHUNK) {
                    // Stopping mid-body aborts the request: the bytes already sent still count.
                    if (stop.getAsBoolean()) return;
                    out.write(chunk);
                    bytes.addAndGet(CHUNK);
                }
            }
            int code = conn.getResponseCode();
            if (code / 100 != 2) throw new IOException("upload answered HTTP " + code);
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setUseCaches(false);
        conn.setRequestProperty("Accept-Encoding", "identity");
        return conn;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static double mbps(long bytes, long nanos) {
        double seconds = Math.max(0.001, nanos / 1e9);
        return bytes * 8 / seconds / 1e6;
    }
}
