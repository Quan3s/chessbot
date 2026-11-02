package com.chessbot.assist;

import android.content.Context;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;

/** Crops the calibrated board and runs its 64 square crops through ONNX Runtime. */
public final class OnnxChessRecognizer {

    private static final int INPUT_SIZE = 224;
    private static final int BATCH_SIZE = 8;
    private static final int CLASS_COUNT = 13;
    private static final float CONFIDENCE_FLOOR = 0.35f;
    private static final String MODEL_ASSET = "onnx/MobileNetV2_0p5_all.onnx";
    private static final int[] FEN_CLASSES = {
            'B', 'K', 'N', 'P', 'Q', 'R', 0, 'b', 'k', 'n', 'p', 'q', 'r'
    };
    private static final Object MODEL_LOCK = new Object();
    private static OrtEnvironment sharedEnvironment;
    private static OrtSession sharedSession;
    private static String sharedInputName;
    private static String sharedOutputName;
    private static final int INPUT_FLOAT_COUNT =
            BATCH_SIZE * INPUT_SIZE * INPUT_SIZE * 3;

    public static final class Result {
        public final String placement;
        public final int a1Position;
        public final boolean reliable;
        public final boolean plausible;
        public final float minConfidence;
        public final float meanConfidence;
        public final String screenRows;
        private final float[] probabilities;
        private final int[][] grid;

        private Result(String placement, int a1Position, boolean reliable, boolean plausible,
                       float minConfidence, float meanConfidence, String screenRows,
                       float[] probabilities, int[][] grid) {
            this.placement = placement;
            this.a1Position = a1Position;
            this.reliable = reliable;
            this.plausible = plausible;
            this.minConfidence = minConfidence;
            this.meanConfidence = meanConfidence;
            this.screenRows = screenRows;
            this.probabilities = probabilities;
            this.grid = grid;
        }

        public String placementFor(int orientation) {
            return OnnxChessRecognizer.placementFor(grid, orientation);
        }

        public int[] boardOccupancy(int orientation) {
            int[] occupancy = new int[64];
            String[] ranks = placementFor(orientation).split("/");
            if (ranks.length != 8) {
                throw new IllegalStateException("ONNX returned invalid placement");
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
                        throw new IllegalStateException("ONNX returned invalid rank");
                    }
                }
                if (file != 8) {
                    throw new IllegalStateException("ONNX returned incomplete rank");
                }
            }
            return occupancy;
        }

        public float logLikelihoodForPlacement(String placement, int orientation)
                throws IOException {
            String[] ranks = placement.split("/");
            if (ranks.length != 8) {
                throw new IOException("Invalid chess placement for ONNX scoring");
            }
            float likelihood = 0;
            for (int fenRow = 0; fenRow < 8; fenRow++) {
                int file = 0;
                for (int i = 0; i < ranks[fenRow].length(); i++) {
                    char piece = ranks[fenRow].charAt(i);
                    if (piece >= '1' && piece <= '8') {
                        int emptyCount = piece - '0';
                        for (int empty = 0; empty < emptyCount; empty++) {
                            likelihood += logProbability(fenRow, file++, orientation, 6);
                        }
                        continue;
                    }
                    int pieceClass = classForPiece(piece);
                    if (pieceClass < 0 || file >= 8) {
                        throw new IOException("Invalid piece in ONNX scoring placement");
                    }
                    likelihood += logProbability(fenRow, file, orientation, pieceClass);
                    file++;
                }
                if (file != 8) {
                    throw new IOException("Incomplete rank in ONNX scoring placement");
                }
            }
            return likelihood;
        }

        private float logProbability(int fenRow, int file, int orientation,
                                     int pieceClass) {
            int screenRow = BoardOrientation.screenRowForFenRow(fenRow, file, orientation);
            int screenCol = BoardOrientation.screenColumnForFenRow(fenRow, file, orientation);
            float probability =
                    probabilities[(screenRow * 8 + screenCol) * CLASS_COUNT + pieceClass];
            return (float) Math.log(Math.max(probability, 1e-8f));
        }
    }

    private final Context context;
    private final FloatBuffer inputBuffer = ByteBuffer.allocateDirect(
            INPUT_FLOAT_COUNT * Float.BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();
    private volatile boolean closed;
    private boolean cropInfoLogged;

    public OnnxChessRecognizer(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result recognize(ScreenCapture.Frame frame, float[] boardRect) throws IOException {
        if (closed) {
            throw new IllegalStateException("ONNX recognizer is closed");
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

        if (!cropInfoLogged) {
            EventLog.add("ONNX vùng cắt thủ công: x=" + left + " y=" + top + " · "
                    + (right - left) + "×" + (bottom - top) + " px từ ảnh "
                    + frame.width + "×" + frame.height
                    + " · chia 8×8 ô, RGB [8,224,224,3], MobileNetV2 x/127.5-1");
            cropInfoLogged = true;
        }
        OrtSession session;
        OrtEnvironment environment;
        String inputName;
        String outputName;
        synchronized (MODEL_LOCK) {
            session = loadSession();
            environment = sharedEnvironment;
            inputName = sharedInputName;
            outputName = sharedOutputName;
        }
        float[] values = new float[64 * CLASS_COUNT];
        try {
            for (int firstSquare = 0; firstSquare < 64; firstSquare += BATCH_SIZE) {
                FloatBuffer inputData = inputBuffer;
                inputData.clear();
                for (int batch = 0; batch < BATCH_SIZE; batch++) {
                    int square = firstSquare + batch;
                    int row = square / 8;
                    int col = square % 8;
                    int y0 = top + row * (bottom - top) / 8;
                    int y1 = top + (row + 1) * (bottom - top) / 8;
                    int x0 = left + col * (right - left) / 8;
                    int x1 = left + (col + 1) * (right - left) / 8;
                    appendSquare(frame.pixels, frame.width, x0, y0, x1, y1, inputData);
                }
                inputData.flip();
                long[] inputShape = {BATCH_SIZE, INPUT_SIZE, INPUT_SIZE, 3};
                try (OnnxTensor input = OnnxTensor.createTensor(
                             environment, inputData, inputShape);
                     OrtSession.Result output = session.run(
                             Collections.singletonMap(inputName, input),
                             Collections.singleton(outputName))) {
                    OnnxValue value = output.get(outputName)
                            .orElseThrow(() -> new IOException(
                                    "ONNX output is missing: " + outputName));
                    if (!(value instanceof OnnxTensor)) {
                        throw new IOException("ONNX output is not a tensor");
                    }
                    OnnxTensor tensor = (OnnxTensor) value;
                    if (!(tensor.getInfo() instanceof TensorInfo)) {
                        throw new IOException("ONNX output has no tensor metadata");
                    }
                    TensorInfo info = (TensorInfo) tensor.getInfo();
                    long[] shape = info.getShape();
                    if (shape.length != 2 || shape[0] != BATCH_SIZE
                            || shape[1] != CLASS_COUNT) {
                        throw new IOException("Unexpected ONNX output shape: " + info);
                    }
                    FloatBuffer outputData = tensor.getFloatBuffer();
                    int expectedValues = BATCH_SIZE * CLASS_COUNT;
                    if (outputData.remaining() != expectedValues) {
                        throw new IOException("ONNX output has " + outputData.remaining()
                                + " values; expected " + expectedValues);
                    }
                    outputData.get(values, firstSquare * CLASS_COUNT, expectedValues);
                }
            }
            return decode(values);
        } catch (OrtException e) {
            throw new IOException("ONNX Runtime inference failed: " + e.getMessage(), e);
        }
    }

    public void close() {
        closed = true;
    }

    private OrtSession loadSession() throws IOException {
        if (sharedSession != null) {
            return sharedSession;
        }
        try {
            byte[] model;
            try (InputStream input = context.getAssets().open(MODEL_ASSET);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                model = output.toByteArray();
            }
            OrtEnvironment environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            try {
                options.setIntraOpNumThreads(Math.max(1,
                        Math.min(4, Runtime.getRuntime().availableProcessors())));
                OrtSession session = environment.createSession(model, options);
                Map<String, NodeInfo> inputs = session.getInputInfo();
                Map<String, NodeInfo> outputs = session.getOutputInfo();
                if (inputs.size() != 1 || outputs.size() != 1) {
                    session.close();
                    throw new IOException("ONNX model must have one input and one output");
                }
                String inputName = inputs.keySet().iterator().next();
                String outputName = outputs.keySet().iterator().next();
                validateInput(inputs.get(inputName));
                validateOutput(outputs.get(outputName));
                sharedEnvironment = environment;
                sharedInputName = inputName;
                sharedOutputName = outputName;
                sharedSession = session;
                EventLog.add("ONNX Runtime " + environment.getVersion()
                        + " và model đã sẵn sàng · chạy CPU");
                return session;
            } finally {
                options.close();
            }
        } catch (OrtException | RuntimeException e) {
            throw new IOException("Could not initialize ONNX Runtime: " + e.getMessage(), e);
        }
    }

    private static void validateInput(NodeInfo input) throws IOException {
        if (!(input.getInfo() instanceof TensorInfo)) {
            throw new IOException("ONNX model input is not a tensor");
        }
        TensorInfo info = (TensorInfo) input.getInfo();
        long[] shape = info.getShape();
        if (info.type != ai.onnxruntime.OnnxJavaType.FLOAT || shape.length != 4
                || (shape[0] != -1 && shape[0] != 64)
                || shape[1] != INPUT_SIZE || shape[2] != INPUT_SIZE || shape[3] != 3) {
            throw new IOException("Unexpected ONNX input shape: " + info);
        }
    }

    private static void validateOutput(NodeInfo output) throws IOException {
        if (!(output.getInfo() instanceof TensorInfo)) {
            throw new IOException("ONNX model output is not a tensor");
        }
        TensorInfo info = (TensorInfo) output.getInfo();
        long[] shape = info.getShape();
        if (info.type != ai.onnxruntime.OnnxJavaType.FLOAT || shape.length != 2
                || (shape[0] != -1 && shape[0] != 64) || shape[1] != CLASS_COUNT) {
            throw new IOException("Unexpected ONNX output shape: " + info);
        }
    }

    private static void appendSquare(int[] pixels, int frameWidth,
                                     int left, int top, int right, int bottom,
                                     FloatBuffer input) {
        int sourceWidth = right - left;
        int sourceHeight = bottom - top;
        for (int outY = 0; outY < INPUT_SIZE; outY++) {
            int y = top + Math.min(sourceHeight - 1,
                    (int) ((outY + 0.5f) * sourceHeight / INPUT_SIZE));
            for (int outX = 0; outX < INPUT_SIZE; outX++) {
                int x = left + Math.min(sourceWidth - 1,
                        (int) ((outX + 0.5f) * sourceWidth / INPUT_SIZE));
                int pixel = pixels[y * frameWidth + x];
                input.put((((pixel >> 16) & 255) - 127.5f) / 127.5f);
                input.put((((pixel >> 8) & 255) - 127.5f) / 127.5f);
                input.put(((pixel & 255) - 127.5f) / 127.5f);
            }
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
                double probabilitySum = 0;
                for (int pieceClass = 0; pieceClass < CLASS_COUNT; pieceClass++) {
                    float score = logits[square * CLASS_COUNT + pieceClass];
                    if (!Float.isFinite(score) || score < 0 || score > 1.001f) {
                        throw new IOException("ONNX classifier returned an invalid probability");
                    }
                    probabilitySum += score;
                    if (score > max) {
                        max = score;
                        best = pieceClass;
                    }
                }
                if (Math.abs(probabilitySum - 1) > 0.02) {
                    throw new IOException("ONNX classifier output is not normalized");
                }
                float confidence = max;
                minConfidence = Math.min(minConfidence, confidence);
                confidenceSum += confidence;
                grid[row][col] = best;
            }
        }

        int a1Position = inferA1Position(grid);
        boolean plausible = isPlausible(grid, a1Position);
        return new Result(placementFor(grid, a1Position), a1Position,
            minConfidence >= CONFIDENCE_FLOOR, plausible, minConfidence,
            confidenceSum / 64, screenRowsFor(grid), logits, grid);
    }

    private static int classForPiece(char piece) {
        for (int i = 0; i < FEN_CLASSES.length; i++) {
            if (FEN_CLASSES[i] == piece) {
                return i;
            }
        }
        return -1;
    }

    private static String screenRowsFor(int[][] grid) {
        StringBuilder rows = new StringBuilder(8 * 9);
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                rows.append(grid[row][col] == 6 ? '.' : (char) FEN_CLASSES[grid[row][col]]);
                if (col != 7) {
                    rows.append(' ');
                }
            }
            rows.append('\n');
        }
        return rows.toString();
    }

    private static int inferA1Position(int[][] grid) {
        int[] colors = new int[64];
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                int piece = grid[row][col];
                colors[row * 8 + col] = piece == 6 ? BoardReader.EMPTY
                        : piece <= 5 ? BoardReader.WHITE : BoardReader.BLACK;
            }
        }
        return BoardOrientation.inferFromScreenColors(colors);
    }

    private static boolean isPlausible(int[][] grid, int orientation) {
        int whiteKings = 0;
        int blackKings = 0;
        for (int rank = 0; rank < 8; rank++) {
            for (int file = 0; file < 8; file++) {
                int row = BoardOrientation.screenRowForSquare(rank, file, orientation);
                int col = BoardOrientation.screenColumnForSquare(rank, file, orientation);
                int piece = grid[row][col];
                if (piece == 1) whiteKings++;
                if (piece == 8) blackKings++;
                if ((rank == 0 || rank == 7) && (piece == 3 || piece == 10)) {
                    return false;
                }
            }
        }
        return whiteKings == 1 && blackKings == 1;
    }

    private static String placementFor(int[][] grid, int orientation) {
        StringBuilder placement = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            if (rank < 7) placement.append('/');
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                int row = BoardOrientation.screenRowForSquare(rank, file, orientation);
                int col = BoardOrientation.screenColumnForSquare(rank, file, orientation);
                int piece = grid[row][col];
                if (piece == 6) {
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
