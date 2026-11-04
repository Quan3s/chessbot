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
 * <p>Piece shapes are learned from the known starting position. Legal-move
 * matching remains the guard for synchronizing the visible board.</p>
 */
public final class GameController {

    public interface Listener {
        void onStatus(String text);

        void onFinished(String reason, boolean error);
    }

    private static final String START_FEN =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private static final long BOARD_SYNC_TIMEOUT_MS = 15000;

    private final Context ctx;
    private final ScreenCapture capture;
    private final Listener listener;
    private final Board board = new Board();
    private final ChessEngine engine;
    private final float[] rect;
    private final int screenW;
    private final int screenH;
    private final int movetime;
    private final int skill;
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final PieceRecognizer recognizer = new PieceRecognizer();

    private volatile boolean running;
    private Thread thread;
    private int a1Position = BoardOrientation.A1_BOTTOM_LEFT;
    private Side botSide = Side.WHITE;
    private int tapMode;
    /** True only when the learned piece templates pass their self-check. */
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
        int botMode = Prefs.getBotMode(ctx);
        this.engine = botMode == Prefs.BOT_STOCKFISH_LOCAL
                ? new StockfishEngine(StockfishCatalog.resolveEnginePath(ctx))
                : new CloudEngine(Prefs.getCloudUrl(ctx), Prefs.getCloudApiKey(ctx),
                        botMode, Prefs.getMaiaElo(ctx));
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
            EventLog.add("Khởi động " + engine.getName() + "…");
            engine.start();
            if (engine instanceof StockfishEngine) {
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
                        + (skill >= 20 ? " (UCI_LimitStrength tắt)" : " (giới hạn bật)"));
            } else {
                engine.configure(skill, 1, 128);
                EventLog.add("Engine: " + engine.getName() + " · " + movetime + " ms/nước");
            }

            String initError = initialize();
            if (initError != null) {
                finish(initError, true);
                return;
            }

            String sideText = botSide == Side.WHITE ? "Trắng" : "Đen";
            listener.onStatus("Bot cầm " + sideText);
            EventLog.add("Sẵn sàng — bot cầm " + sideText + ", "
                    + "a1 ở góc " + BoardOrientation.label(a1Position));

            int[] lastGrid = null;
            BoardReader.Result baseline = null;
            long lastChange = 0;
            int stable = 0;
            int unknown = 0;
            long unknownSince = 0;
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
                        rect[0], rect[1], rect[2], rect[3]);
                int[] grid = res.boardOccupancy(a1Position);
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
                    unknownSince = 0;
                }

                if (!synced) {
                    if (unknownSince == 0) {
                        unknownSince = SystemClock.elapsedRealtime();
                    }
                    if (SystemClock.elapsedRealtime() - unknownSince
                            >= BOARD_SYNC_TIMEOUT_MS) {
                        finish("Không đồng bộ được bàn cờ sau 15 giây. Kiểm tra vùng "
                                + "căn chỉnh rồi thử lại.", true);
                        return;
                    }
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
                        Move occupancyMove = findMove(grid, res, baseline);
                        if (typedReady) {
                            PieceRecognizer.Recognized recognized = recognizer.classifyBoard(
                                    frame.pixels, frame.width, frame.height, res, a1Position);
                            Move typedMove = chooseTypedMove(grid, recognized);
                            if (typedMove != null && !typedMove.equals(occupancyMove)) {
                                EventLog.add("Nhận diện quân xác nhận: " + uci(typedMove)
                                        + " · tin cậy "
                                        + Math.round(PieceRecognizer.averageConfidence(recognized) * 100)
                                        + "%");
                                occupancyMove = typedMove;
                            }
                        }
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
                            sleep(230);
                            continue;
                        }
                    }
                    Side mover = board.getSideToMove();
                    board.doMove(move);
                    tapPending = false;
                    pending = null;
                    unknown = 0;
                    unknownSince = 0;
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

                long searchStarted = SystemClock.elapsedRealtime();
                String best = engine.bestMove(board.getFen(), movetime);
                EventLog.add("Engine trả lời sau "
                        + (SystemClock.elapsedRealtime() - searchStarted) + " ms");
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
        board.clear();
        board.loadFromFen(START_FEN);
        int[] expected = boardOccupancy(board);
        int preference = Prefs.getOrientation(ctx);
        ScreenCapture.Frame frame = null;
        BoardReader.Result res = null;
        int mismatch = Integer.MAX_VALUE;
        int occupied = 0;
        int selectedOrientation = BoardOrientation.A1_BOTTOM_LEFT;
        for (int attempt = 0; attempt < 8 && running; attempt++) {
            ScreenCapture.Frame candidate = capture.grab();
            if (candidate == null) {
                sleep(150);
                continue;
            }
            BoardReader.Result read = BoardReader.read(candidate.pixels, candidate.width,
                    candidate.height, rect[0], rect[1], rect[2], rect[3]);
            int orientation = preference == Prefs.ORIENT_AUTO
                    ? BoardOrientation.inferFromScreenColors(read.screenColors())
                    : BoardOrientation.fromPreference(preference);
            int[] observed = read.boardOccupancy(orientation);
            int candidateMismatch = diff(expected, observed);
            if (res == null || candidateMismatch < mismatch) {
                frame = candidate;
                res = read;
                mismatch = candidateMismatch;
                selectedOrientation = orientation;
                occupied = countOccupied(observed);
            }
            if (candidateMismatch == 0) {
                break;
            }
            sleep(150);
        }
        if (frame == null || res == null) {
            return "Không chụp được màn hình.";
        }
        a1Position = selectedOrientation;
        EventLog.add("Nhận diện sơ bộ:\n" + res.debugRows());
        EventLog.add("Hướng bàn cờ: a1 ở góc " + BoardOrientation.label(a1Position)
                + " (BoardReader)");
        EventLog.add("Kiểm tra vị trí đầu: " + occupied + "/32 quân, khớp "
                + (64 - mismatch) + "/64 ô");
        if (occupied < 28 || mismatch > 4) {
            return "Không xác nhận được bàn cờ khai cuộc (" + (64 - mismatch)
                    + "/64 ô đúng). Căn chỉnh lại vùng bàn cờ và mở ván mới.";
        }
        botSide = Prefs.isBotWhite(ctx) ? Side.WHITE : Side.BLACK;
        learnPieces(frame, res, mismatch == 0);
        return null;
    }

    private void learnPieces(ScreenCapture.Frame frame, BoardReader.Result res,
                             boolean exactStartingPosition) {
        typedReady = false;
        if (!exactStartingPosition) {
            EventLog.add("Vị trí đầu chưa khớp đủ 64 ô để học bộ quân; dùng nhận diện luật nước đi.");
            return;
        }
        int[] classByScreen = new int[64];
        Arrays.fill(classByScreen, -1);
        int total = 0;
        int observed = 0;
        for (Square square : Square.values()) {
            if (square == Square.NONE) {
                continue;
            }
            Piece piece = board.getPiece(square);
            if (piece == null || piece == Piece.NONE) {
                continue;
            }
            total++;
            int index = screenIndex(square);
            if (res.cells[index].occupied) {
                classByScreen[index] = piece.ordinal();
                observed++;
            }
        }
        PieceRecognizer.LearnResult learned = recognizer.learn(
                frame.pixels, frame.width, frame.height, res, classByScreen);
        PieceRecognizer.Recognized recognized = recognizer.classifyBoard(
                frame.pixels, frame.width, frame.height, res, a1Position);
        int typeMatches = 0;
        int colorMatches = 0;
        for (Square square : Square.values()) {
            if (square == Square.NONE) {
                continue;
            }
            Piece piece = board.getPiece(square);
            if (piece == null || piece == Piece.NONE) {
                continue;
            }
            int index = square.ordinal();
            if (recognized.type[index] == piece.ordinal() % 6) {
                typeMatches++;
            }
            int color = piece.ordinal() < 6 ? BoardReader.WHITE : BoardReader.BLACK;
            if (recognized.color[index] == color) {
                colorMatches++;
            }
        }
        int minimumMatches = Math.max(27, total - 3);
        typedReady = learned.classes == PieceRecognizer.CLASS_COUNT
                && learned.samples == 32 && observed == 32
                && typeMatches >= minimumMatches && colorMatches >= minimumMatches;
        EventLog.add("Học bộ quân: " + learned.classes + "/12 loại · " + learned.samples
                + " mẫu · tự kiểm tra loại " + typeMatches + "/" + total
                + ", màu " + colorMatches + "/" + total
                + (typedReady ? " → bật nhận diện" : " → dùng luật nước đi"));
        EventLog.add("Bàn cờ nhận diện:\n" + recognizer.describe(recognized));
    }

    private static int countOccupied(int[] squares) {
        int count = 0;
        for (int square : squares) {
            if (square != 0) {
                count++;
            }
        }
        return count;
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
        List<Move> bestMoves = bestMoves(observed, now, before);
        if (bestMoves.size() == 1) {
            return bestMoves.get(0);
        }
        // Promotion: all four choices produce the same picture. Prefer a queen.
        for (Move move : bestMoves) {
            Piece piece = move.getPromotion();
            if (piece == Piece.WHITE_QUEEN || piece == Piece.BLACK_QUEEN) {
                return move;
            }
        }
        return null;
    }

    private List<Move> bestMoves(int[] observed, BoardReader.Result now,
                                 BoardReader.Result before) {
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
        if (best > 3) {
            bestMoves.clear();
        }
        return bestMoves;
    }

    private Move chooseTypedMove(int[] observed, PieceRecognizer.Recognized recognized) {
        if (recognized == null || recognized.occupied == 0
                || recognized.knownTypes != recognized.occupied
                || recognized.knownColors != recognized.occupied) {
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
                    if (recognized.type[index] != PieceRecognizer.TYPE_EMPTY) {
                        matchesPieces = false;
                        break;
                    }
                } else {
                    int type = piece.ordinal() % 6;
                    int color = piece.ordinal() < 6
                            ? BoardReader.WHITE : BoardReader.BLACK;
                    if (recognized.type[index] != type
                            || recognized.color[index] != color) {
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
        int sr = BoardOrientation.screenRowForSquare(rank, file, a1Position);
        int sc = BoardOrientation.screenColumnForSquare(rank, file, a1Position);
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
            EventLog.add("Không gửi được nước đi: dịch vụ Trợ năng chưa kết nối.");
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
                sleep(250);
                ok = acc.tap(to[0], to[1]);
            }
        }
        if (!ok) {
            EventLog.add("Gửi thao tác cho nước " + uci(move) + " thất bại.");
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
        int sr = BoardOrientation.screenRowForSquare(rank, file, a1Position);
        int sc = BoardOrientation.screenColumnForSquare(rank, file, a1Position);
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
