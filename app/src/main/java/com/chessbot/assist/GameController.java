package com.chessbot.assist;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The bot brain.
 *
 * The app starts from the initial position and keeps a chesslib board in sync
 * by matching every visible board change against the legal moves of the side
 * to move. This works without knowing the piece set or board theme.
 *
 * <p>Since 1.4 there is a second, optional layer: {@link PieceRecognizer}
 * learns the 12 piece shapes from the standard starting position and then
 * classifies the pieces on later frames. That extra evidence is only trusted
 * when it is unambiguous. It resolves the one case occupancy alone cannot —
 * which piece a promotion turned into — and can break ties between two
 * identical-looking captures. If recognition is unsure, the controller uses
 * the old occupancy matching exactly as before.</p>
 */
public final class GameController {

    public interface Listener {
        void onStatus(String text);

        void onFinished(String reason, boolean error);
    }

    private static final String START_FEN =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private final Context ctx;
    private final ScreenCapture capture;
    private final Listener listener;
    private final Board board = new Board();
    private final StockfishEngine engine;
    private final float[] rect;
    private final int screenW;
    private final int screenH;
    private final int movetime;
    private final int skill;
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final PieceRecognizer recognizer = new PieceRecognizer();

    private volatile boolean running;
    private Thread thread;
    private boolean whiteAtBottom = true;
    private Side botSide = Side.WHITE;
    private int tapMode;
    /** True only when the shape templates were learned and self-checked. */
    private boolean typedReady;

    public GameController(Context ctx, ScreenCapture capture, int screenW, int screenH,
                          Listener listener) {
        this.ctx = ctx;
        this.capture = capture;
        this.screenW = screenW;
        this.screenH = screenH;
        this.listener = listener;
        float[] r = Prefs.getRect(ctx);
        this.rect = r != null ? r : new float[]{0.04f, 0.20f, 0.96f, 0.70f};
        this.movetime = Prefs.getMovetime(ctx);
        this.skill = Prefs.getSkill(ctx);
        this.tapMode = Prefs.getTapMode(ctx);
        this.engine = new StockfishEngine(StockfishCatalog.resolveEnginePath(ctx));
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "chessbot-loop");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    private void loop() {
        try {
            EventLog.add("Khởi động Stockfish…");
            engine.start();
            int cores = Runtime.getRuntime().availableProcessors();
            int maxThreads = Math.max(1, Math.min(16, cores));
            int configuredThreads = Prefs.getThreads(ctx);
            int threads = configuredThreads > 0
                    ? Math.min(configuredThreads, maxThreads) : maxThreads;
            int configuredHashMb = Prefs.getHashMb(ctx);
            int hashMb = configuredHashMb > 0 ? configuredHashMb : chooseHashMb();
            engine.configure(skill, threads, hashMb);
            EventLog.add("Engine: " + engine.getName() + " · " + threads + "/" + cores
                    + " nhân · " + hashMb + " MB hash · Skill " + skill
                    + " · " + movetime + " ms/nước"
                    + (skill >= 20 ? " (tối đa, không giới hạn)" : ""));

            String initError = initialize();
            if (initError != null) {
                finish(initError, true);
                return;
            }

            String sideText = botSide == Side.WHITE ? "Trắng" : "Đen";
            listener.onStatus("Bot cầm " + sideText);
            EventLog.add("Sẵn sàng — bot cầm " + sideText + ", "
                    + (whiteAtBottom ? "Trắng ở dưới" : "Trắng ở trên"));

            int[] lastGrid = null;
            BoardReader.Result baseline = null;
            long lastChange = 0;
            int stable = 0;
            int unknown = 0;
            Move previousObservedMove = null;
            int observedMoveRepeats = 0;
            long observedMoveSince = 0;
            int retried = 0;
            boolean tapPending = false;
            long tapAt = 0;
            Move pending = null;

            while (running) {
                ScreenCapture.Frame frame = capture.grab();
                if (frame == null) {
                    sleep(120);
                    continue;
                }
                BoardReader.Result res = BoardReader.read(frame.pixels, frame.width, frame.height,
                        rect[0], rect[1], rect[2], rect[3],
                        Prefs.getBoardDevThreshold(ctx), Prefs.getBoardFillThreshold(ctx));
                int[] grid = res.boardOccupancy(whiteAtBottom);

                if (lastGrid != null && Arrays.equals(grid, lastGrid)) {
                    stable++;
                } else {
                    lastGrid = grid;
                    stable = 0;
                    lastChange = SystemClock.elapsedRealtime();
                }
                if (stable < 2 || SystemClock.elapsedRealtime() - lastChange < 300) {
                    sleep(130);
                    continue;
                }

                int[] internal = boardOccupancy(board);
                boolean synced = Arrays.equals(grid, internal);
                if (synced) {
                    baseline = res;
                    previousObservedMove = null;
                    observedMoveRepeats = 0;
                }

                if (!synced) {
                    if (tapPending && SystemClock.elapsedRealtime() - tapAt < 650) {
                        sleep(110);
                        continue;
                    }
                    // After our own tap we know exactly which move we asked
                    // for, so prefer that move. This resolves the otherwise
                    // ambiguous case of two identical-looking captures
                    // (e.g. a knight can take either of two identical pawns).
                    Move move = null;
                    if (tapPending && pending != null && matchesMove(grid, pending)) {
                        move = pending;
                    } else {
                        Move occupancyMove = findMoveWithThresholdFallback(
                                frame, grid, res, baseline);
                        if (occupancyMove != null) {
                            if (occupancyMove.equals(previousObservedMove)) {
                                observedMoveRepeats++;
                            } else {
                                previousObservedMove = occupancyMove;
                                observedMoveRepeats = 1;
                                observedMoveSince = SystemClock.elapsedRealtime();
                            }
                            long candidateDuration =
                                    SystemClock.elapsedRealtime() - observedMoveSince;
                            if (observedMoveRepeats >= 2 && candidateDuration >= 180) {
                                move = occupancyMove;
                            } else {
                                unknown++;
                                if (unknown == 1) {
                                    EventLog.add("Bàn cờ đã đổi — đang xác nhận nước đi…");
                                }
                                if (unknown > 45) {
                                    finish("Không nhận ra nước đi. Hãy kiểm tra vùng bàn cờ / mở ván mới.", true);
                                    return;
                                }
                                sleep(230);
                                continue;
                            }
                        } else {
                            previousObservedMove = null;
                            observedMoveRepeats = 0;
                            unknown++;
                            if (unknown == 1) {
                                EventLog.add("Bàn cờ đã đổi nhưng chưa khớp nước hợp lệ — đang chờ…");
                                EventLog.add("Bàn cờ đọc được:\n" + res.debugRows());
                            }
                            if (unknown > 45) {
                                finish("Không nhận ra nước đi. Hãy kiểm tra vùng bàn cờ / mở ván mới.", true);
                                return;
                            }
                            sleep(230);
                            continue;
                        }
                        if (typedReady) {
                            // Piece types are extra evidence, never a
                            // replacement for the occupancy match. They are
                            // accepted only when unique and clearly better.
                            PieceRecognizer.Recognized rec = recognizer.classifyBoard(
                                    frame.pixels, frame.width, frame.height, res, whiteAtBottom);
                            Move typedMove = chooseTypedMove(grid, rec);
                            if (typedMove != null && !typedMove.equals(move)) {
                                EventLog.add("Nhận diện quân xác nhận: " + uci(typedMove)
                                        + " · tin cậy "
                                        + Math.round(PieceRecognizer.averageConfidence(rec) * 100)
                                        + "%");
                                move = typedMove;
                            }
                        }
                    }
                    Side mover = board.getSideToMove();
                    board.doMove(move);
                    tapPending = false;
                    pending = null;
                    unknown = 0;
                    previousObservedMove = null;
                    observedMoveRepeats = 0;
                    retried = 0;
                    EventLog.add((mover == botSide ? "Bot" : "Đối thủ") + ": " + uci(move));
                    listener.onStatus((mover == botSide ? "Bot" : "Đối thủ") + " đi " + uci(move));
                    if (isGameOver()) {
                        finish("Ván đã kết thúc", false);
                        return;
                    }
                    sleep(160);
                    continue;
                }

                unknown = 0;

                if (tapPending) {
                    long dt = SystemClock.elapsedRealtime() - tapAt;
                    if (isPromotion(pending) && dt < 15000) {
                        sleep(200);
                        continue;
                    }
                    if (dt < 4200) {
                        sleep(140);
                        continue;
                    }
                    if (!isPromotion(pending) && retried < 1) {
                        retried++;
                        tapMode = tapMode == Prefs.TAP_DRAG ? Prefs.TAP_TAP : Prefs.TAP_DRAG;
                        EventLog.add("Nước đi chưa được áp dụng — thử lại bằng "
                                + (tapMode == Prefs.TAP_DRAG ? "kéo" : "chạm 2 lần"));
                        tapPending = false;
                        continue;
                    }
                    finish(isPromotion(pending)
                            ? "Chưa chọn quân phong cấp."
                            : "Không đi được quân. Thử đổi 'Cách đi quân' trong cài đặt.", true);
                    return;
                }

                if (board.getSideToMove() != botSide) {
                    sleep(200);
                    continue;
                }

                if (isGameOver()) {
                    finish("Ván đã kết thúc", false);
                    return;
                }

                String best = engine.bestMove(board.getFen(), movetime);
                if (best == null) {
                    finish("Stockfish không có nước đi.", false);
                    return;
                }
                Move move = findByUci(best);
                if (move == null) {
                    finish("Stockfish trả về nước không hợp lệ: " + best, true);
                    return;
                }

                EventLog.add("Bot tính: " + best);
                listener.onStatus("Đang đi " + best);
                boolean ok = tapMove(move);
                if (!ok) {
                    EventLog.add("Không gửi được thao tác chạm (trợ năng chưa bật?)");
                }
                pending = move;
                tapPending = true;
                tapAt = SystemClock.elapsedRealtime();
                sleep(120);
            }
        } catch (Throwable t) {
            if (running) {
                EventLog.add("Lỗi: " + t);
                finish("Lỗi: " + t.getMessage(), true);
            }
        } finally {
            engine.quit();
        }
    }

    /**
     * Give the engine as much hash as the device can comfortably spare.
     * Stockfish is strongest with a large transposition table, so this scales
     * with the phone's RAM. Skill 20 / UCI_LimitStrength off still means the
     * engine is at full strength; this only tunes resources.
     */
    private int chooseHashMb() {
        try {
            ActivityManager am =
                    (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                long totalMb = mi.totalMem / (1024L * 1024L);
                if (totalMb >= 7000) {
                    return 1024;
                }
                if (totalMb >= 3500) {
                    return 512;
                }
                if (totalMb >= 2500) {
                    return 256;
                }
            }
        } catch (Throwable ignored) {
        }
        return 128;
    }

    private String initialize() {
        StartFrame startFrame = captureStartingPosition();
        if (startFrame == null) {
            return "Không chụp được màn hình.";
        }
        ScreenCapture.Frame frame = startFrame.frame;
        BoardReader.StartRead startRead = startFrame.read;
        BoardReader.Result res = startRead.result;
        EventLog.add("Nhận diện sơ bộ:\n" + res.debugRows());
        EventLog.add("Ngưỡng nhận diện: chênh lệch " + startRead.devThreshold
                + " · diện tích " + Math.round(startRead.fillThreshold * 100) + "%");

        board.clear();
        board.loadFromFen(START_FEN);
        int mismatch = startRead.mismatch;
        int occ = startRead.occupied;
        EventLog.add("Kiểm tra vị trí đầu: " + occ + "/32 quân, khớp "
                + (64 - mismatch) + "/64 ô");
        if (!startRead.isValid()) {
            return "Không nhận diện rõ bàn cờ (" + occ + "/32 quân, "
                    + (64 - mismatch) + "/64 ô khớp). Hãy mở ván mới, căn khung sát mép "
                    + "bàn cờ và bảo đảm nút nổi không che quân.";
        }

        int orient = Prefs.getOrientation(ctx);
        if (orient == Prefs.ORIENT_WHITE_BOTTOM) {
            whiteAtBottom = true;
        } else if (orient == Prefs.ORIENT_WHITE_TOP) {
            whiteAtBottom = false;
        } else {
            int topN = res.occupiedInRows(0, 1);
            int botN = res.occupiedInRows(6, 7);
            float topL = res.avgPieceHigh(0, 1);
            float botL = res.avgPieceHigh(6, 7);
            float topContrast = res.avgPieceContrast(0, 1);
            float botContrast = res.avgPieceContrast(6, 7);
            int topWhite = countPieceColor(res, 0, 1, BoardReader.WHITE);
            int topBlack = countPieceColor(res, 0, 1, BoardReader.BLACK);
            int bottomWhite = countPieceColor(res, 6, 7, BoardReader.WHITE);
            int bottomBlack = countPieceColor(res, 6, 7, BoardReader.BLACK);
            EventLog.add("Hướng bàn cờ: trên " + topN + " quân (độ tương phản "
                    + Math.round(topContrast) + "), dưới " + botN + " quân (độ tương phản "
                    + Math.round(botContrast) + ")");
            int colorOrientationScore =
                    topBlack + bottomWhite - topWhite - bottomBlack;
            if (Math.abs(colorOrientationScore) >= 4) {
                whiteAtBottom = colorOrientationScore > 0;
            } else if (Math.abs(botContrast - topContrast) >= 8) {
                whiteAtBottom = botContrast > topContrast;
            } else if (Math.abs(topL - botL) >= 8) {
                whiteAtBottom = botL > topL;
            } else {
                whiteAtBottom = Prefs.isBotWhite(ctx);
                EventLog.add("Không tự phân biệt được hướng bàn cờ; tạm dùng hướng theo "
                        + "màu bot đã chọn. Nếu bàn cờ bị lật, chọn hướng thủ công trong Cài đặt.");
            }
        }

        Prefs.setBoardThresholds(ctx, startRead.devThreshold, startRead.fillThreshold);
        botSide = Prefs.isBotWhite(ctx) ? Side.WHITE : Side.BLACK;
        learnPieces(frame, res, occ == 32 && mismatch == 0);
        return null;
    }

    private int countPieceColor(BoardReader.Result result, int rowFrom, int rowTo, int color) {
        int count = 0;
        for (int row = rowFrom; row <= rowTo; row++) {
            for (int col = 0; col < 8; col++) {
                BoardReader.Cell cell = result.cells[row * 8 + col];
                if (cell.occupied && cell.color == color) {
                    count++;
                }
            }
        }
        return count;
    }

    private StartFrame captureStartingPosition() {
        StartFrame best = null;
        int[] previousExact = null;
        int previousThreshold = -1;
        for (int attempt = 0; attempt < 8 && running; attempt++) {
            ScreenCapture.Frame frame = capture.grab();
            if (frame != null) {
                BoardReader.StartRead read = BoardReader.readStartingPosition(
                        frame.pixels, frame.width, frame.height,
                        rect[0], rect[1], rect[2], rect[3]);
                if (best == null || read.mismatch < best.read.mismatch) {
                    ScreenCapture.Frame saved = new ScreenCapture.Frame();
                    saved.width = frame.width;
                    saved.height = frame.height;
                    saved.pixels = frame.pixels.clone();
                    best = new StartFrame(saved, read);
                }
                if (read.mismatch == 0) {
                    int[] current = read.result.screenOccupancy();
                    if (previousThreshold == read.devThreshold
                            && Arrays.equals(previousExact, current)) {
                        ScreenCapture.Frame saved = new ScreenCapture.Frame();
                        saved.width = frame.width;
                        saved.height = frame.height;
                        saved.pixels = frame.pixels.clone();
                        best = new StartFrame(saved, read);
                        break;
                    }
                    previousExact = current;
                    previousThreshold = read.devThreshold;
                } else {
                    previousExact = null;
                    previousThreshold = -1;
                }
            }
            sleep(150);
        }
        return best;
    }

    private static final class StartFrame {
        final ScreenCapture.Frame frame;
        final BoardReader.StartRead read;

        StartFrame(ScreenCapture.Frame frame, BoardReader.StartRead read) {
            this.frame = frame;
            this.read = read;
        }
    }

    /**
     * Learn the 12 piece shapes from the standard starting position, where
     * every piece is already labelled by the known FEN. The learned templates
     * are only used if a self-check on the same frame classifies almost all
     * pieces correctly; otherwise the controller stays on the old occupancy
     * matching.
     */
    private void learnPieces(ScreenCapture.Frame frame, BoardReader.Result res,
                             boolean exactStartingPosition) {
        typedReady = false;
        if (!exactStartingPosition) {
            EventLog.add("Vị trí đầu chưa khớp đủ 64 ô để học bộ quân — dùng cách cũ.");
            return;
        }
        int[] classByScreen = new int[64];
        Arrays.fill(classByScreen, -1);
        int total = 0;
        int observed = 0;
        for (Square s : Square.values()) {
            if (s == Square.NONE) {
                continue;
            }
            Piece p = board.getPiece(s);
            if (p == null || p == Piece.NONE) {
                continue;
            }
            total++;
            int idx = screenIndex(s);
            if (!res.cells[idx].occupied) {
                continue;
            }
            classByScreen[idx] = p.ordinal();
            observed++;
        }
        if (total != 32 || observed != 32) {
            EventLog.add("Không đủ 32 quân đúng vị trí đầu để học hình (" + observed
                    + "/32) — dùng cách cũ.");
            return;
        }
        PieceRecognizer.LearnResult lr = recognizer.learn(
                frame.pixels, frame.width, frame.height, res, classByScreen);
        if (!recognizer.isReady()) {
            EventLog.add("Không học đủ bộ quân (" + lr.classes
                    + "/12 loại) — dùng cách cũ.");
            return;
        }

        PieceRecognizer.Recognized rec = recognizer.classifyBoard(
                frame.pixels, frame.width, frame.height, res, whiteAtBottom);
        int typeOk = 0;
        int colorOk = 0;
        for (Square s : Square.values()) {
            if (s == Square.NONE) {
                continue;
            }
            Piece p = board.getPiece(s);
            if (p == null || p == Piece.NONE) {
                continue;
            }
            int i = s.ordinal();
            if (rec.type[i] == p.ordinal() % 6) {
                typeOk++;
            }
            int expColor = p.ordinal() < 6 ? BoardReader.WHITE : BoardReader.BLACK;
            if (rec.color[i] == expColor) {
                colorOk++;
            }
        }
        int minTypes = Math.max(27, total - 3);
        typedReady = lr.classes == PieceRecognizer.CLASS_COUNT
                && lr.samples == 32
                && observed == 32
                && typeOk >= minTypes
                && colorOk >= minTypes;

        EventLog.add("Học bộ quân: " + lr.classes + "/12 loại · " + lr.samples
                + " mẫu · tự kiểm tra loại " + typeOk + "/" + total
                + ", màu " + colorOk + "/" + total
                + (typedReady ? " → bật nhận diện" : " → tắt (dùng cách cũ)"));
        if (lr.detail != null && lr.detail.length() > 0) {
            EventLog.add("Mẫu/loại: " + lr.detail);
        }
        EventLog.add("Bàn cờ nhận diện:\n" + recognizer.describe(rec));
        if (typedReady) {
            EventLog.add("Nhận diện quân: "
                    + Math.round(PieceRecognizer.averageConfidence(rec) * 100)
                    + "% tin cậy trung bình — chỉ dùng khi rõ ràng.");
        }
    }

    private int[] boardOccupancy(Board b) {
        int[] g = new int[64];
        for (Square s : Square.values()) {
            if (s == Square.NONE) {
                continue;
            }
            Piece p = b.getPiece(s);
            g[s.ordinal()] = (p == null || p == Piece.NONE) ? 0 : 1;
        }
        return g;
    }

    private Move findMove(int[] observed, BoardReader.Result now, BoardReader.Result before) {
        List<Move> legal = board.legalMoves();
        int best = Integer.MAX_VALUE;
        List<Move> bestMoves = new ArrayList<>();
        for (Move m : legal) {
            Board copy = board.clone();
            copy.doMove(m);
            int score = 0;
            int[] expected = boardOccupancy(copy);
            for (int i = 0; i < 64; i++) {
                if (observed[i] != expected[i]) {
                    score += 3;
                }
            }
            score += appearancePenalty(m, now, before);
            if (score < best) {
                best = score;
                bestMoves.clear();
                bestMoves.add(m);
            } else if (score == best) {
                bestMoves.add(m);
            }
        }
        if (best > 3 || bestMoves.isEmpty()) {
            return null;
        }
        if (bestMoves.size() == 1) {
            return bestMoves.get(0);
        }
        // Promotion: all four choices produce the same picture. Prefer a queen.
        for (Move m : bestMoves) {
            Piece p = m.getPromotion();
            if (p == Piece.WHITE_QUEEN || p == Piece.BLACK_QUEEN) {
                return m;
            }
        }
        return null;
    }

    private Move findMoveWithThresholdFallback(ScreenCapture.Frame frame, int[] observed,
                                               BoardReader.Result current,
                                               BoardReader.Result before) {
        Move direct = findMove(observed, current, before);
        if (direct != null) {
            return direct;
        }

        int selectedDev = Prefs.getBoardDevThreshold(ctx);
        float selectedFill = Prefs.getBoardFillThreshold(ctx);
        int[] devThresholds = {
                Math.min(42, selectedDev + 8),
                Math.min(42, selectedDev + 4),
                Math.max(12, selectedDev - 4),
                Math.max(10, selectedDev - 8)
        };
        float[] fillThresholds = {
                Math.min(0.20f, selectedFill * 1.4f),
                Math.min(0.18f, selectedFill * 1.2f),
                Math.max(0.035f, selectedFill * 0.8f),
                Math.max(0.03f, selectedFill * 0.6f)
        };
        Move consensus = null;
        int votes = 0;
        for (int i = 0; i < devThresholds.length; i++) {
            BoardReader.Result candidateRead = BoardReader.read(
                    frame.pixels, frame.width, frame.height,
                    rect[0], rect[1], rect[2], rect[3],
                    devThresholds[i], fillThresholds[i]);
            int[] candidateGrid = candidateRead.boardOccupancy(whiteAtBottom);
            Move candidate = findMove(candidateGrid, candidateRead, before);
            if (candidate == null) {
                continue;
            }
            if (consensus == null) {
                consensus = candidate;
                votes = 1;
            } else if (consensus.equals(candidate)) {
                votes++;
            } else {
                return null;
            }
        }
        return votes >= 2 ? consensus : null;
    }

    /**
     * Use a complete, confident piece classification to distinguish legal
     * moves that have the same occupancy image (including underpromotion).
     * Any uncertain square leaves the existing occupancy-only path in charge.
     */
    private Move chooseTypedMove(int[] observed, PieceRecognizer.Recognized rec) {
        if (rec == null || rec.occupied == 0
                || rec.knownTypes != rec.occupied
                || rec.knownColors != rec.occupied) {
            return null;
        }

        List<Move> matches = new ArrayList<>();
        for (Move move : board.legalMoves()) {
            Board candidate = board.clone();
            candidate.doMove(move);
            if (!Arrays.equals(observed, boardOccupancy(candidate))) {
                continue;
            }

            boolean matchesPieces = true;
            for (Square square : Square.values()) {
                if (square == Square.NONE) {
                    continue;
                }
                int index = square.ordinal();
                Piece piece = candidate.getPiece(square);
                if (piece == null || piece == Piece.NONE) {
                    if (rec.type[index] != PieceRecognizer.TYPE_EMPTY) {
                        matchesPieces = false;
                        break;
                    }
                } else {
                    int type = piece.ordinal() % 6;
                    int color = piece.ordinal() < 6
                            ? BoardReader.WHITE : BoardReader.BLACK;
                    if (rec.type[index] != type || rec.color[index] != color) {
                        matchesPieces = false;
                        break;
                    }
                }
            }
            if (matchesPieces) {
                matches.add(move);
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    /**
     * A capture replaces an enemy piece with the moving piece, which does not
     * change the occupancy grid. Compare the brightness of the piece that
     * arrived with the brightness of the piece that left: a wrong capture
     * destination still shows the enemy piece and scores badly.
     */
    private int appearancePenalty(Move m, BoardReader.Result now, BoardReader.Result before) {
        if (now == null || before == null) {
            return 0;
        }
        int penalty = samePiece(m.getFrom(), m.getTo(), now, before);
        Square from = m.getFrom();
        Square to = m.getTo();
        if (from == Square.E1 && to == Square.G1) {
            penalty += samePiece(Square.H1, Square.F1, now, before);
        } else if (from == Square.E1 && to == Square.C1) {
            penalty += samePiece(Square.A1, Square.D1, now, before);
        } else if (from == Square.E8 && to == Square.G8) {
            penalty += samePiece(Square.H8, Square.F8, now, before);
        } else if (from == Square.E8 && to == Square.C8) {
            penalty += samePiece(Square.A8, Square.D8, now, before);
        }
        return penalty;
    }

    private int samePiece(Square from, Square to, BoardReader.Result now, BoardReader.Result before) {
        BoardReader.Cell a = before.cells[screenIndex(from)];
        BoardReader.Cell b = now.cells[screenIndex(to)];
        if (!a.occupied || !b.occupied) {
            return 0;
        }
        // The same piece keeps its silhouette (fill) and its light/dark mix
        // (brightRatio) when it moves to another square. A different piece,
        // or an enemy piece that is still sitting on a wrong destination,
        // does not.
        if (Math.abs(a.fill - b.fill) > 0.10f || Math.abs(a.brightRatio - b.brightRatio) > 0.15f) {
            return 2;
        }
        return 0;
    }

    private int screenIndex(Square s) {
        int rank = s.ordinal() / 8;
        int file = s.ordinal() % 8;
        int sr = whiteAtBottom ? 7 - rank : rank;
        int sc = whiteAtBottom ? file : 7 - file;
        return sr * 8 + sc;
    }

    private boolean matchesMove(int[] observed, Move move) {
        Board copy = board.clone();
        copy.doMove(move);
        return Arrays.equals(observed, boardOccupancy(copy));
    }

    private Move findByUci(String uci) {
        for (Move m : board.legalMoves()) {
            if (m.toString().equals(uci)) {
                return m;
            }
        }
        return null;
    }

    private static int diff(int[] a, int[] b) {
        int d = 0;
        for (int i = 0; i < 64; i++) {
            if (a[i] != b[i]) {
                d++;
            }
        }
        return d;
    }

    private boolean tapMove(Move move) {
        BotAccessibilityService acc = BotAccessibilityService.getInstance();
        if (acc == null) {
            return false;
        }
        int[] from = squareCenter(move.getFrom());
        int[] to = squareCenter(move.getTo());
        boolean ok;
        if (tapMode == Prefs.TAP_DRAG) {
            ok = acc.drag(from[0], from[1], to[0], to[1]);
        } else {
            ok = acc.tap(from[0], from[1]);
            if (ok) {
                sleep(150);
                ok = acc.tap(to[0], to[1]);
            }
        }
        if (ok && isPromotion(move)) {
            EventLog.add("Phong cấp: nếu app hỏi, hãy chọn Hậu.");
            listener.onStatus("Phong cấp — chọn Hậu nếu được hỏi");
        }
        return ok;
    }

    private int[] squareCenter(Square square) {
        int rank = square.ordinal() / 8;
        int file = square.ordinal() % 8;
        int sr = whiteAtBottom ? 7 - rank : rank;
        int sc = whiteAtBottom ? file : 7 - file;
        float x = (rect[0] + (sc + 0.5f) / 8f * (rect[2] - rect[0])) * screenW;
        float y = (rect[1] + (sr + 0.5f) / 8f * (rect[3] - rect[1])) * screenH;
        return new int[]{Math.round(x), Math.round(y)};
    }

    private boolean isPromotion(Move m) {
        return m != null && m.getPromotion() != null && m.getPromotion() != Piece.NONE;
    }

    private boolean isGameOver() {
        if (board.legalMoves().isEmpty()) {
            return true;
        }
        try {
            return board.isMated() || board.isDraw();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String uci(Move m) {
        return m.toString();
    }

    private void finish(String reason, boolean error) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        running = false;
        EventLog.add((error ? "Dừng (lỗi): " : "Dừng: ") + reason);
        listener.onFinished(reason, error);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
