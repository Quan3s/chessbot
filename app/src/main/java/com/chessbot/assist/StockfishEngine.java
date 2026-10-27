package com.chessbot.assist;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Minimal UCI wrapper around a bundled Stockfish executable. */
public final class StockfishEngine {

    private final String path;
    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private Thread readerThread;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private volatile String name = "Stockfish";

    public StockfishEngine(String path) {
        this.path = path;
    }

    public synchronized void start() throws IOException {
        if (process != null) {
            return;
        }
        File file = new File(path);
        if (!file.isFile()) {
            throw new IOException("Không tìm thấy Stockfish: " + path);
        }
        if (!file.canExecute() && !file.setExecutable(true)) {
            throw new IOException("Không thể chạy Stockfish (quyền thực thi): " + path);
        }
        ProcessBuilder pb = new ProcessBuilder(path);
        pb.redirectErrorStream(true);
        pb.directory(file.getParentFile());
        process = pb.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        readerThread = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("id name ")) {
                        name = line.substring("id name ".length()).trim();
                    }
                    lines.offer(line);
                }
            } catch (IOException ignored) {
            }
        }, "stockfish-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        send("uci");
        waitForPrefix("uciok", 20000);
    }

    public String getName() {
        return name;
    }

    public synchronized void configure(int skill, int threads, int hashMb) throws IOException {
        int level = Math.max(0, Math.min(20, skill));
        send("setoption name Threads value " + Math.max(1, threads));
        send("setoption name Hash value " + Math.max(8, hashMb));
        // Stockfish only applies Skill Level when strength limiting is on.
        // At level 20 we turn limiting off so the engine is at full strength.
        send("setoption name UCI_LimitStrength value " + (level < 20));
        send("setoption name Skill Level value " + level);
        send("setoption name UCI_Chess960 value false");
        send("ucinewgame");
        send("isready");
        waitForPrefix("readyok", 15000);
    }

    /**
     * @return UCI move such as "e2e4" / "e7e8q", or null if the engine has no move.
     */
    public synchronized String bestMove(String fen, int movetimeMs) throws IOException {
        if (process == null) {
            throw new IOException("Stockfish chưa chạy");
        }
        send("position fen " + fen);
        send("go movetime " + Math.max(50, movetimeMs));
        long deadline = System.currentTimeMillis() + movetimeMs + 12000L;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw new IOException("Stockfish quá thời gian");
            }
            String line;
            try {
                line = lines.poll(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Đã dừng");
            }
            if (line == null) {
                throw new IOException("Stockfish quá thời gian");
            }
            if (line.startsWith("bestmove")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2 || "(none)".equals(parts[1])) {
                    return null;
                }
                return parts[1];
            }
        }
    }

    public synchronized void quit() {
        if (process == null) {
            return;
        }
        try {
            send("quit");
        } catch (Throwable ignored) {
        }
        try {
            process.waitFor(800, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            process.destroy();
        } catch (Throwable ignored) {
        }
        process = null;
        writer = null;
        reader = null;
    }

    private void send(String cmd) throws IOException {
        if (writer == null) {
            throw new IOException("Stockfish chưa chạy");
        }
        writer.write(cmd);
        writer.write('\n');
        writer.flush();
    }

    private void waitForPrefix(String prefix, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw new IOException("Stockfish không phản hồi (" + prefix + ")");
            }
            String line;
            try {
                line = lines.poll(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Đã dừng");
            }
            if (line == null) {
                throw new IOException("Stockfish không phản hồi (" + prefix + ")");
            }
            if (line.startsWith(prefix)) {
                return;
            }
        }
    }
}
