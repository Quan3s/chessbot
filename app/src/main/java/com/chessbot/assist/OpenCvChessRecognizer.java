package com.chessbot.assist;

import android.content.Context;
import android.graphics.Bitmap;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Classifies all 64 calibrated board squares with OpenCV DNN and the ONNX CNN. */
public final class OpenCvChessRecognizer {

    private static final int INPUT_SIZE = 48;
    private static final int CLASS_COUNT = 13;
    private static final float CONFIDENCE_FLOOR = 0.5f;
    private static final int[] FEN_CLASSES = {
            0, 'P', 'N', 'B', 'R', 'Q', 'K', 'p', 'n', 'b', 'r', 'q', 'k'
    };
    private static final Object MODEL_LOCK = new Object();
    private static Net sharedNet;

    public static final class Result {
        public final String placement;
        public final String placementOtherOrientation;
        public final boolean whiteAtBottom;
        public final boolean reliable;
        public final boolean plausible;
        public final float minConfidence;
        public final float meanConfidence;

        private Result(String placement, String placementOtherOrientation,
                       boolean whiteAtBottom, boolean reliable, boolean plausible,
                       float minConfidence, float meanConfidence) {
            this.placement = placement;
            this.placementOtherOrientation = placementOtherOrientation;
            this.whiteAtBottom = whiteAtBottom;
            this.reliable = reliable;
            this.plausible = plausible;
            this.minConfidence = minConfidence;
            this.meanConfidence = meanConfidence;
        }

        public String placementFor(boolean whiteBottom) {
            return whiteBottom == whiteAtBottom ? placement : placementOtherOrientation;
        }

        public int[] boardOccupancy(boolean whiteBottom) {
            int[] occupancy = new int[64];
            String[] ranks = placementFor(whiteBottom).split("/");
            if (ranks.length != 8) {
                throw new IllegalStateException("OpenCV returned invalid placement");
            }
            for (int row = 0; row < 8; row++) {
                int file = 0;
                for (int i = 0; i < ranks[row].length(); i++) {
                    char c = ranks[row].charAt(i);
                    if (c >= '1' && c <= '8') {
                        file += c - '0';
                    } else if ("KQRBNPkqrbnp".indexOf(c) >= 0 && file < 8) {
                        occupancy[(7 - row) * 8 + file++] = 1;
                    } else {
                        throw new IllegalStateException("OpenCV returned invalid rank");
                    }
                }
                if (file != 8) {
                    throw new IllegalStateException("OpenCV returned incomplete rank");
                }
            }
            return occupancy;
        }
    }

    private final Context context;
    private volatile boolean closed;

    public OpenCvChessRecognizer(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result recognize(ScreenCapture.Frame frame, float[] boardRect) throws IOException {
        if (closed) {
            throw new IllegalStateException("OpenCV recognizer is closed");
        }
        if (frame == null || frame.pixels == null || frame.width < 8 || frame.height < 8
                || boardRect == null || boardRect.length < 4) {
            throw new IOException("Invalid screenshot or board bounds");
        }

        int left = clamp(Math.round(boardRect[0] * frame.width), 0, frame.width - 8);
        int top = clamp(Math.round(boardRect[1] * frame.height), 0, frame.height - 8);
        int right = clamp(Math.round(boardRect[2] * frame.width), left + 8, frame.width);
        int bottom = clamp(Math.round(boardRect[3] * frame.height), top + 8, frame.height);
        if (right <= left || bottom <= top) {
            throw new IOException("Calibrated board bounds are empty");
        }

        Net net;
        synchronized (MODEL_LOCK) {
            net = loadNet();
        }

        Bitmap bitmap = Bitmap.createBitmap(frame.pixels, frame.width, frame.height,
                Bitmap.Config.ARGB_8888);
        Mat rgba = new Mat();
        Mat bgr = new Mat();
        Mat board = null;
        Mat blob = null;
        Mat logits = null;
        List<Mat> squares = new ArrayList<>(64);
        try {
            Utils.bitmapToMat(bitmap, rgba);
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR);
            board = bgr.submat(new Rect(left, top, right - left, bottom - top));

            for (int row = 0; row < 8; row++) {
                int y0 = row * board.rows() / 8;
                int y1 = (row + 1) * board.rows() / 8;
                for (int col = 0; col < 8; col++) {
                    int x0 = col * board.cols() / 8;
                    int x1 = (col + 1) * board.cols() / 8;
                    Mat cell = board.submat(new Rect(x0, y0, x1 - x0, y1 - y0));
                    Mat resized = new Mat();
                    boolean retained = false;
                    try {
                        Imgproc.resize(cell, resized, new Size(INPUT_SIZE, INPUT_SIZE),
                                0, 0, Imgproc.INTER_AREA);
                        squares.add(resized);
                        retained = true;
                    } finally {
                        cell.release();
                        if (!retained) resized.release();
                    }
                }
            }

            blob = Dnn.blobFromImages(squares, 1.0 / 255.0,
                    new Size(INPUT_SIZE, INPUT_SIZE), new Scalar(0, 0, 0),
                    false, false, CvType.CV_32F);
            float[] values;
            synchronized (MODEL_LOCK) {
                net.setInput(blob);
                logits = net.forward();
                if (logits.total() * logits.channels() != 64L * CLASS_COUNT) {
                    throw new IOException("Unexpected OpenCV classifier output shape");
                }
                values = new float[64 * CLASS_COUNT];
                Mat rows = logits.reshape(1, 64);
                try {
                    if (rows.cols() != CLASS_COUNT || rows.type() != CvType.CV_32F) {
                        throw new IOException("Unexpected reshaped OpenCV classifier output");
                    }
                    float[] rowValues = new float[CLASS_COUNT];
                    for (int row = 0; row < 64; row++) {
                        int read = rows.get(row, 0, rowValues);
                        int expectedBytes = rowValues.length * Float.BYTES;
                        if (read != expectedBytes) {
                            throw new IOException("Could not read OpenCV classifier output row "
                                    + row + ": read " + read + " bytes; expected "
                                    + expectedBytes);
                        }
                        System.arraycopy(rowValues, 0, values, row * CLASS_COUNT, CLASS_COUNT);
                    }
                } finally {
                    rows.release();
                }
            }
            return decode(values);
        } finally {
            bitmap.recycle();
            rgba.release();
            bgr.release();
            if (board != null) board.release();
            if (blob != null) blob.release();
            if (logits != null) logits.release();
            for (Mat square : squares) square.release();
        }
    }

    public void close() {
        closed = true;
    }

    private Net loadNet() throws IOException {
        if (sharedNet != null) {
            return sharedNet;
        }
        File nativeLibrary = new File(context.getApplicationInfo().nativeLibraryDir,
                "libopencv_java4.so");
        if (!nativeLibrary.isFile()) {
            throw new IOException("OpenCV native library not found: " + nativeLibrary);
        }
        try {
            System.load(nativeLibrary.getAbsolutePath());
            Core.getBuildInformation();
            Mat probe = new Mat();
            probe.release();
        } catch (UnsatisfiedLinkError | UnsupportedOperationException e) {
            throw new IOException("OpenCV native library failed JNI initialization: "
                    + nativeLibrary, e);
        }

        byte[] model;
        try (InputStream input = context.getAssets().open("opencv/piece_classifier.onnx");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            model = output.toByteArray();
        }

        MatOfByte modelBytes = new MatOfByte(model);
        try {
            Net net = Dnn.readNetFromONNX(modelBytes);
            net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
            net.setPreferableTarget(Dnn.DNN_TARGET_CPU);
            sharedNet = net;
            EventLog.add("OpenCV DNN và model ONNX đã sẵn sàng");
            return net;
        } finally {
            modelBytes.release();
        }
    }

    private static Result decode(float[] logits) throws IOException {
        int[][] grid = new int[8][8];
        float minConfidence = 1;
        float confidenceSum = 0;
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                int square = row * 8 + col;
                float max = Float.NEGATIVE_INFINITY;
                int best = 0;
                for (int pieceClass = 0; pieceClass < CLASS_COUNT; pieceClass++) {
                    float score = logits[square * CLASS_COUNT + pieceClass];
                    if (!Float.isFinite(score)) {
                        throw new IOException("OpenCV classifier returned a non-finite score");
                    }
                    if (score > max) {
                        max = score;
                        best = pieceClass;
                    }
                }
                double sum = 0;
                for (int pieceClass = 0; pieceClass < CLASS_COUNT; pieceClass++) {
                    sum += Math.exp(logits[square * CLASS_COUNT + pieceClass] - max);
                }
                float confidence = (float) (1 / sum);
                minConfidence = Math.min(minConfidence, confidence);
                confidenceSum += confidence;
                grid[row][col] = best;
            }
        }

        boolean whiteAtBottom = inferWhiteAtBottom(grid);
        boolean plausible = isPlausible(grid);
        return new Result(placementFor(grid, whiteAtBottom),
                placementFor(grid, !whiteAtBottom), whiteAtBottom,
                minConfidence >= CONFIDENCE_FLOOR, plausible,
                minConfidence, confidenceSum / 64);
    }

    private static boolean inferWhiteAtBottom(int[][] grid) {
        int score = 0;
        for (int row = 0; row < 8; row++) {
            for (int piece : grid[row]) {
                if (piece == 0) continue;
                boolean bottom = row >= 4;
                boolean white = piece <= 6;
                score += white == bottom ? 1 : -1;
            }
        }
        return score >= 0;
    }

    private static boolean isPlausible(int[][] grid) {
        int whiteKings = 0;
        int blackKings = 0;
        for (int row = 0; row < 8; row++) {
            for (int piece : grid[row]) {
                if (piece == 6) whiteKings++;
                if (piece == 12) blackKings++;
                if ((row == 0 || row == 7) && (piece == 1 || piece == 7)) {
                    return false;
                }
            }
        }
        return whiteKings == 1 && blackKings == 1;
    }

    private static String placementFor(int[][] grid, boolean whiteAtBottom) {
        StringBuilder placement = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            if (rank < 7) placement.append('/');
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                int row = whiteAtBottom ? 7 - rank : rank;
                int col = whiteAtBottom ? file : 7 - file;
                int piece = grid[row][col];
                if (piece == 0) {
                    empty++;
                } else {
                    if (empty > 0) {
                        placement.append(empty);
                        empty = 0;
                    }
                    placement.append((char) FEN_CLASSES[piece]);
                }
            }
            if (empty > 0) placement.append(empty);
        }
        return placement.toString();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
