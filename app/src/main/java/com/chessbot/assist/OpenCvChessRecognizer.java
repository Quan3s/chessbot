package com.chessbot.assist;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** OpenCV template matching trained from a single standard starting-position image. */
public final class OpenCvChessRecognizer {
    public static final int TYPE_EMPTY = -1;
    public static final int TYPE_UNKNOWN = -2;
    public static final int CLASS_COUNT = 12;

    private static final int BOARD_SIZE = 320;
    private static final int CELL_SIZE = BOARD_SIZE / 8;
    private static final int SAMPLE_SIZE = 32;
    private static final int MAGIC = 0x4F435654;
    private static final int VERSION = 1;
    private static final String PIECES = "PNBRQK";
    private static volatile boolean loaded;

    private OpenCvChessRecognizer() {
    }

    public static final class Model {
        private final byte[][][] templates;
        private final float[][] contrasts;
        private final float occupancyThreshold;
        private final boolean occupancyReliable;

        private Model(byte[][][] templates, float[][] contrasts, float threshold,
                      boolean reliable) {
            this.templates = templates;
            this.contrasts = contrasts;
            this.occupancyThreshold = threshold;
            this.occupancyReliable = reliable;
        }

        public boolean occupancyReliable() {
            return occupancyReliable;
        }

        public byte[] toBytes() {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeFloat(occupancyThreshold);
                out.writeBoolean(occupancyReliable);
                for (int cls = 0; cls < CLASS_COUNT; cls++) {
                    out.writeByte(templates[cls].length);
                    for (byte[] sample : templates[cls]) {
                        out.write(sample);
                    }
                    for (float contrast : contrasts[cls]) {
                        out.writeFloat(contrast);
                    }
                }
                out.flush();
                return bytes.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException("Could not serialize OpenCV templates", e);
            }
        }

        public static Model fromBytes(byte[] data) {
            if (data == null) {
                return null;
            }
            try {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
                if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                    return null;
                }
                float threshold = in.readFloat();
                boolean reliable = in.readBoolean();
                if (!Float.isFinite(threshold) || threshold < 0f || threshold > 255f) {
                    return null;
                }
                byte[][][] templates = new byte[CLASS_COUNT][][];
                float[][] contrasts = new float[CLASS_COUNT][];
                for (int cls = 0; cls < CLASS_COUNT; cls++) {
                    int count = in.readUnsignedByte();
                    if (count < 1 || count > 8) {
                        return null;
                    }
                    templates[cls] = new byte[count][SAMPLE_SIZE * SAMPLE_SIZE];
                    contrasts[cls] = new float[count];
                    for (byte[] sample : templates[cls]) {
                        in.readFully(sample);
                    }
                    for (int i = 0; i < count; i++) {
                        contrasts[cls][i] = in.readFloat();
                        if (!Float.isFinite(contrasts[cls][i])) {
                            return null;
                        }
                    }
                }
                return in.available() == 0
                        ? new Model(templates, contrasts, threshold, reliable) : null;
            } catch (IOException e) {
                return null;
            }
        }
    }

    public static final class Result {
        public final int[] type = new int[64];
        public final int[] color = new int[64];
        public final int[] classIndex = new int[64];
        public final float[] confidence = new float[64];
        public final float[] deviation = new float[64];
        public int occupied;
        public int unknown;
        public int knownTypes;
        public int knownColors;

        private Result() {
            Arrays.fill(type, TYPE_EMPTY);
            Arrays.fill(color, BoardReader.EMPTY);
            Arrays.fill(classIndex, TYPE_EMPTY);
        }

        public String describe() {
            StringBuilder rows = new StringBuilder();
            for (int rank = 7; rank >= 0; rank--) {
                for (int file = 0; file < 8; file++) {
                    int cls = classIndex[rank * 8 + file];
                    char symbol = cls == TYPE_EMPTY ? '.'
                            : cls == TYPE_UNKNOWN ? '?'
                            : PIECES.charAt(cls % 6);
                    rows.append(cls >= 6 && cls < 12
                            ? Character.toLowerCase(symbol) : symbol);
                    if (file != 7) {
                        rows.append(' ');
                    }
                }
                rows.append('\n');
            }
            return rows.toString();
        }

        public String toFen(boolean whiteToMove) {
            return fenFromTypes(classIndex, whiteToMove);
        }

        public static float averageConfidence(Result result) {
            if (result == null || result.occupied == 0) {
                return 0f;
            }
            float sum = 0f;
            int count = 0;
            for (int i = 0; i < result.classIndex.length; i++) {
                if (result.classIndex[i] >= 0) {
                    sum += result.confidence[i];
                    count++;
                }
            }
            return count == 0 ? 0f : sum / count;
        }
    }

    public static String fenFromTypes(int[] types, boolean whiteToMove) {
        if (types == null || types.length != 64) {
            throw new IllegalArgumentException("Expected 64 board squares");
        }
        StringBuilder fen = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                int cls = types[rank * 8 + file];
                if (cls < 0 || cls >= CLASS_COUNT) {
                    empty++;
                } else {
                    if (empty > 0) {
                        fen.append(empty);
                        empty = 0;
                    }
                    char piece = PIECES.charAt(cls % 6);
                    fen.append(cls >= 6 ? Character.toLowerCase(piece) : piece);
                }
            }
            if (empty > 0) {
                fen.append(empty);
            }
            if (rank != 0) {
                fen.append('/');
            }
        }
        return fen.append(whiteToMove ? " w " : " b ")
                .append("KQkq - 0 1").toString();
    }

    public static void loadNative() {
        if (!loaded) {
            synchronized (OpenCvChessRecognizer.class) {
                if (!loaded) {
                    System.loadLibrary("opencv_java4");
                    loaded = true;
                }
            }
        }
    }

    public static Model train(int[] pixels, int width, int height, float[] crop,
                              int orientation) {
        loadNative();
        Mat board = makeBoard(pixels, width, height, crop);
        List<List<byte[]>> samples = new ArrayList<>(CLASS_COUNT);
        List<List<Float>> contrasts = new ArrayList<>(CLASS_COUNT);
        for (int i = 0; i < CLASS_COUNT; i++) {
            samples.add(new ArrayList<>());
            contrasts.add(new ArrayList<>());
        }
        List<Double> emptyDeviation = new ArrayList<>();
        List<Double> pieceDeviation = new ArrayList<>();
        try {
            for (int rank = 0; rank < 8; rank++) {
                for (int file = 0; file < 8; file++) {
                    int row = BoardOrientation.screenRowForSquare(rank, file, orientation);
                    int col = BoardOrientation.screenColumnForSquare(rank, file, orientation);
                    Mat cell = board.submat(new Rect(col * CELL_SIZE, row * CELL_SIZE,
                            CELL_SIZE, CELL_SIZE));
                    try {
                        CellFeatures features = extract(cell);
                        try {
                            int cls = openingClass(rank, file);
                            if (cls < 0) {
                                emptyDeviation.add(features.deviation);
                            } else {
                                samples.get(cls).add(features.pixels);
                                contrasts.get(cls).add((float) features.contrast);
                                pieceDeviation.add(features.deviation);
                            }
                        } finally {
                            features.mat.release();
                        }
                    } finally {
                        cell.release();
                    }
                }
            }
        } finally {
            board.release();
        }

        byte[][][] templates = new byte[CLASS_COUNT][][];
        float[][] templateContrasts = new float[CLASS_COUNT][];
        for (int cls = 0; cls < CLASS_COUNT; cls++) {
            if (samples.get(cls).isEmpty()) {
                throw new IllegalStateException("Missing opening-position samples for class "
                        + cls);
            }
            templates[cls] = samples.get(cls).toArray(new byte[0][]);
            templateContrasts[cls] = new float[contrasts.get(cls).size()];
            for (int i = 0; i < templateContrasts[cls].length; i++) {
                templateContrasts[cls][i] = contrasts.get(cls).get(i);
            }
        }
        float threshold = occupancyThreshold(emptyDeviation, pieceDeviation);
        return new Model(templates, templateContrasts, threshold,
                separates(emptyDeviation, pieceDeviation, threshold));
    }

    public static Result recognize(int[] pixels, int width, int height, float[] crop,
                                   int orientation, Model model) {
        return recognizeSelected(pixels, width, height, crop, orientation, model, null);
    }

    public static Result recognizeSelected(int[] pixels, int width, int height, float[] crop,
                                           int orientation, Model model, boolean[] selected) {
        if (model == null) {
            throw new IllegalArgumentException("No OpenCV template model selected");
        }
        if (selected != null && selected.length != 64) {
            throw new IllegalArgumentException("Expected 64 selected-square flags");
        }
        loadNative();
        Mat board = makeBoard(pixels, width, height, crop);
        Result result = new Result();
        Mat template = new Mat(SAMPLE_SIZE, SAMPLE_SIZE, CvType.CV_8UC1);
        Mat scores = new Mat();
        try {
            for (int rank = 0; rank < 8; rank++) {
                for (int file = 0; file < 8; file++) {
                    int square = rank * 8 + file;
                    if (selected != null && !selected[square]) {
                        continue;
                    }
                    int row = BoardOrientation.screenRowForSquare(rank, file, orientation);
                    int col = BoardOrientation.screenColumnForSquare(rank, file, orientation);
                    Mat cell = board.submat(new Rect(col * CELL_SIZE, row * CELL_SIZE,
                            CELL_SIZE, CELL_SIZE));
                    try {
                        CellFeatures features = extract(cell);
                        result.deviation[square] = (float) features.deviation;
                        if (features.deviation <= model.occupancyThreshold) {
                            features.mat.release();
                            continue;
                        }
                        result.occupied++;
                        double[] classScores = new double[CLASS_COUNT];
                        Arrays.fill(classScores, -2d);
                        for (int cls = 0; cls < CLASS_COUNT; cls++) {
                            for (int i = 0; i < model.templates[cls].length; i++) {
                                template.put(0, 0, model.templates[cls][i]);
                                Imgproc.matchTemplate(features.mat, template, scores,
                                        Imgproc.TM_CCOEFF_NORMED);
                                double score = Core.minMaxLoc(scores).maxVal;
                                double colorDifference = Math.min(1d,
                                        Math.abs(features.contrast
                                                - model.contrasts[cls][i]) / 96d);
                                score = score * 0.85d + (1d - colorDifference) * 0.15d;
                                if (Double.isFinite(score) && score > classScores[cls]) {
                                    classScores[cls] = score;
                                }
                            }
                        }
                        int bestClass = TYPE_UNKNOWN;
                        double best = -2d;
                        double second = -2d;
                        for (int cls = 0; cls < CLASS_COUNT; cls++) {
                            double score = classScores[cls];
                            if (score > best) {
                                second = best;
                                best = score;
                                bestClass = cls;
                            } else if (score > second) {
                                second = score;
                            }
                        }
                        result.confidence[square] = (float) best;
                        if (best < 0.20d || best - second < 0.015d) {
                            result.type[square] = TYPE_UNKNOWN;
                            result.color[square] = BoardReader.UNKNOWN;
                            result.classIndex[square] = TYPE_UNKNOWN;
                            result.unknown++;
                        } else {
                            result.type[square] = bestClass % 6;
                            result.color[square] = bestClass < 6
                                    ? BoardReader.WHITE : BoardReader.BLACK;
                            result.classIndex[square] = bestClass;
                            result.knownTypes++;
                            result.knownColors++;
                        }
                        features.mat.release();
                    } finally {
                        cell.release();
                    }
                }
            }
        } finally {
            board.release();
            template.release();
            scores.release();
        }
        return result;
    }

    public static int[] recognizeOccupancy(int[] pixels, int width, int height, float[] crop,
                                           int orientation, Model model) {
        if (model == null) {
            throw new IllegalArgumentException("No OpenCV template model selected");
        }
        loadNative();
        Mat board = makeBoard(pixels, width, height, crop);
        byte[] gray = new byte[BOARD_SIZE * BOARD_SIZE];
        try {
            board.get(0, 0, gray);
        } finally {
            board.release();
        }
        int[] occupancy = new int[64];
        for (int rank = 0; rank < 8; rank++) {
            for (int file = 0; file < 8; file++) {
                int row = BoardOrientation.screenRowForSquare(rank, file, orientation);
                int col = BoardOrientation.screenColumnForSquare(rank, file, orientation);
                double deviation = cellDeviation(gray, col * CELL_SIZE, row * CELL_SIZE);
                occupancy[rank * 8 + file] =
                        deviation > model.occupancyThreshold ? 1 : 0;
            }
        }
        return occupancy;
    }

    private static Mat makeBoard(int[] pixels, int width, int height, float[] crop) {
        if (pixels == null || width <= 0 || height <= 0 || pixels.length < width * height
                || crop == null || crop.length < 4) {
            throw new IllegalArgumentException("Invalid screenshot or board crop");
        }
        int left = clamp(Math.round(crop[0] * width), 0, width - 1);
        int top = clamp(Math.round(crop[1] * height), 0, height - 1);
        int right = clamp(Math.round(crop[2] * width), left + 1, width);
        int bottom = clamp(Math.round(crop[3] * height), top + 1, height);
        if (right - left < 8 || bottom - top < 8) {
            throw new IllegalArgumentException("Board crop is too small");
        }
        int cropWidth = right - left;
        int cropHeight = bottom - top;
        byte[] grayPixels = new byte[cropWidth * cropHeight];
        for (int y = 0; y < cropHeight; y++) {
            int source = (top + y) * width + left;
            int target = y * cropWidth;
            for (int x = 0; x < cropWidth; x++) {
                int pixel = pixels[source + x];
                int r = (pixel >> 16) & 255;
                int g = (pixel >> 8) & 255;
                int b = pixel & 255;
                grayPixels[target + x] = (byte) ((77 * r + 150 * g + 29 * b) >> 8);
            }
        }
        Mat gray = new Mat(cropHeight, cropWidth, CvType.CV_8UC1);
        Mat board = new Mat();
        try {
            gray.put(0, 0, grayPixels);
            Imgproc.resize(gray, board, new Size(BOARD_SIZE, BOARD_SIZE),
                    0, 0, Imgproc.INTER_AREA);
        } finally {
            gray.release();
        }
        return board;
    }

    private static double cellDeviation(byte[] board, int left, int top) {
        int margin = 4;
        double background = 0d;
        int backgroundCount = 0;
        for (int y = 0; y < CELL_SIZE; y++) {
            for (int x = 0; x < CELL_SIZE; x++) {
                if (x >= margin && x < CELL_SIZE - margin
                        && y >= margin && y < CELL_SIZE - margin) {
                    continue;
                }
                background += board[(top + y) * BOARD_SIZE + left + x] & 255;
                backgroundCount++;
            }
        }
        background /= Math.max(1, backgroundCount);

        double sum = 0d;
        for (int y = margin; y < CELL_SIZE - margin; y++) {
            for (int x = margin; x < CELL_SIZE - margin; x++) {
                int value = board[(top + y) * BOARD_SIZE + left + x] & 255;
                int difference = (int) Math.round(Math.abs(value - background));
                if (difference < 7) {
                    difference = 0;
                }
                sum += difference;
            }
        }
        double mean = sum / (SAMPLE_SIZE * SAMPLE_SIZE);
        double variance = 0d;
        for (int y = margin; y < CELL_SIZE - margin; y++) {
            for (int x = margin; x < CELL_SIZE - margin; x++) {
                int value = board[(top + y) * BOARD_SIZE + left + x] & 255;
                int difference = (int) Math.round(Math.abs(value - background));
                if (difference < 7) {
                    difference = 0;
                }
                double delta = difference - mean;
                variance += delta * delta;
            }
        }
        return Math.sqrt(variance / (SAMPLE_SIZE * SAMPLE_SIZE));
    }

    private static CellFeatures extract(Mat cell) {
        int margin = 4;
        byte[] bytes = new byte[CELL_SIZE * CELL_SIZE];
        cell.get(0, 0, bytes);
        double background = 0d;
        int backgroundCount = 0;
        for (int y = 0; y < CELL_SIZE; y++) {
            for (int x = 0; x < CELL_SIZE; x++) {
                if (x >= margin && x < CELL_SIZE - margin
                        && y >= margin && y < CELL_SIZE - margin) {
                    continue;
                }
                background += bytes[y * CELL_SIZE + x] & 255;
                backgroundCount++;
            }
        }
        background /= Math.max(1, backgroundCount);

        byte[] diff = new byte[SAMPLE_SIZE * SAMPLE_SIZE];
        double sum = 0d;
        double contrast = 0d;
        for (int y = 0; y < SAMPLE_SIZE; y++) {
            for (int x = 0; x < SAMPLE_SIZE; x++) {
                int value = bytes[(y + margin) * CELL_SIZE + x + margin] & 255;
                int raw = (int) Math.round(Math.abs(value - background));
                if (raw < 7) {
                    raw = 0;
                }
                diff[y * SAMPLE_SIZE + x] = (byte) raw;
                sum += raw;
                contrast += value - background;
            }
        }
        double mean = sum / diff.length;
        double variance = 0d;
        for (byte value : diff) {
            double delta = (value & 255) - mean;
            variance += delta * delta;
        }
        double deviation = Math.sqrt(variance / diff.length);
        int[] sorted = new int[diff.length];
        for (int i = 0; i < diff.length; i++) {
            sorted[i] = diff[i] & 255;
        }
        Arrays.sort(sorted);
        int scale = Math.max(12, sorted[(int) (sorted.length * 0.90)]);
        byte[] normalized = new byte[diff.length];
        for (int i = 0; i < diff.length; i++) {
            int y = i / SAMPLE_SIZE + margin;
            int x = i % SAMPLE_SIZE + margin;
            int value = bytes[y * CELL_SIZE + x] & 255;
            int signed = (int) Math.round((value - background) * 112d / scale);
            normalized[i] = (byte) Math.max(0, Math.min(255, 128 + signed));
        }
        Mat mask = new Mat(SAMPLE_SIZE, SAMPLE_SIZE, CvType.CV_8UC1);
        mask.put(0, 0, normalized);
        return new CellFeatures(mask, normalized, deviation, contrast / diff.length);
    }

    private static int openingClass(int rank, int file) {
        if (rank == 1) return 0;
        if (rank == 6) return 6;
        if (rank != 0 && rank != 7) return -1;
        int type;
        switch (file) {
            case 0:
            case 7:
                type = 3;
                break;
            case 1:
            case 6:
                type = 1;
                break;
            case 2:
            case 5:
                type = 2;
                break;
            case 3:
                type = 4;
                break;
            case 4:
                type = 5;
                break;
            default:
                throw new IllegalArgumentException("Invalid file");
        }
        return type + (rank == 7 ? 6 : 0);
    }

    private static float occupancyThreshold(List<Double> empty, List<Double> occupied) {
        if (empty.isEmpty() || occupied.isEmpty()) return 8f;
        double maxEmpty = 0d;
        double minPiece = Double.MAX_VALUE;
        for (double value : empty) maxEmpty = Math.max(maxEmpty, value);
        for (double value : occupied) minPiece = Math.min(minPiece, value);
        return (float) Math.max(4d, Math.min(32d,
                minPiece > maxEmpty ? (minPiece + maxEmpty) / 2d : maxEmpty * 1.15d));
    }

    private static boolean separates(List<Double> empty, List<Double> occupied,
                                     float threshold) {
        if (empty.isEmpty() || occupied.isEmpty()) return false;
        for (double value : empty) if (value > threshold) return false;
        for (double value : occupied) if (value <= threshold) return false;
        return true;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class CellFeatures {
        final Mat mat;
        final byte[] pixels;
        final double deviation;
        final double contrast;

        CellFeatures(Mat mat, byte[] pixels, double deviation, double contrast) {
            this.mat = mat;
            this.pixels = pixels;
            this.deviation = deviation;
            this.contrast = contrast;
        }
    }
}
