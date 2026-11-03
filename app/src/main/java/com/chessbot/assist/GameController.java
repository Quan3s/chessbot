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
 * <p>Saved OpenCV templates classify pieces on each frame. Legal-move matching
 * remains the guard for synchronizing the visible board.</p>
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
    private final OpenCvChessRecognizer.Model templateModel;
    private final String savedStartFen;

    private volatile boolean running;
    private Thread thread;
    private int a1Position = BoardOrientation.A1_BOTTOM_LEFT;
    private Side botSide = Side.WHITE;
    private int tapMode;
    /** True when a saved OpenCV theme is available. */
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
        String themeId = Prefs.getPieceThemeId(ctx);
        byte[] selectedTemplates = PieceThemeStore.getTemplates(ctx, themeId);
        templateModel = OpenCvChessRecognizer.Model.fromBytes(selectedTemplates);
        savedStartFen = PieceThemeStore.getStartFen(ctx, themeId);
        if (selectedTemplates != null) {
            typedReady = templateModel != null;
            if (!typedReady) {
                EventLog.add("Bộ template đã chọn không hợp lệ; hãy train lại.");
            } else {
                a1Position = PieceThemeStore.getOrientation(ctx, themeId);
                EventLog.add("Đã nạp bộ template \"" + selectedThemeName(ctx) + "\".");
            }
        }
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
                        rect[0], rect[1], rect[2], rect[3],
                        Prefs.getBoardDevThreshold(ctx), Prefs.getBoardFillThreshold(ctx));
                int[] readerGrid = res.boardOccupancy(a1Position);
                OpenCvChessRecognizer.Result typedRead = typedReady
                        ? OpenCvChessRecognizer.recognize(frame.pixels, frame.width,
                                frame.height, rect, a1Position, templateModel)
                        : null;
                int[] grid = typedRead == null ? readerGrid : occupancyFrom(typedRead);
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
                                + "căn chỉnh và bộ template, rồi thử lại.", true);
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
                        if (typedReady && typedRead != null) {
                            // Piece types are extra evidence, never a
                            // replacement for the occupancy match. They are
                            // accepted only when unique and clearly better.
                            Move typedMove = chooseTypedMove(grid, typedRead);
                            if (typedMove != null && !typedMove.equals(move)) {
                                EventLog.add("Nhận diện quân xác nhận: " + uci(typedMove)
                                        + " · tin cậy "
                                        + Math.round(OpenCvChessRecognizer.Result
                                                .averageConfidence(typedRead) * 100)
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
        StartFrame startFrame = captureStartingPosition();
        if (startFrame == null) {
            return "Không chụp được màn hình.";
        }
        ScreenCapture.Frame frame = startFrame.frame;
        BoardReader.StartRead startRead = startFrame.read;
        BoardReader.Result res = startRead.result;
        EventLog.add("Nhận diện sơ bộ:\n" + res.debugRows());
        EventLog.add("Ngưỡng BoardReader dự phòng: chênh lệch " + startRead.devThreshold
                + " · diện tích " + Math.round(startRead.fillThreshold * 100) + "%");

        int orient = Prefs.getOrientation(ctx);
        int selectedOrientation = BoardOrientation.fromPreference(orient);
        if (typedReady) {
            a1Position = PieceThemeStore.getOrientation(ctx, Prefs.getPieceThemeId(ctx));
            botSide = Prefs.isBotWhite(ctx) ? Side.WHITE : Side.BLACK;
            OpenCvChessRecognizer.Result recognized = OpenCvChessRecognizer.recognize(
                    frame.pixels, frame.width, frame.height, rect, a1Position, templateModel);
            int occupancyMatches = startingOccupancyMatches(occupancyFrom(recognized));
            EventLog.add("OpenCV nhận diện bàn cờ: occupancy khai cuộc "
                    + occupancyMatches + "/64 · " + recognized.unknown
                    + " ô chưa rõ · " + BoardOrientation.label(a1Position));
            EventLog.add("Bàn cờ nhận diện:\n" + recognized.describe());
            EventLog.add("FEN nhận diện: " + recognized.toFen(true));
            if (occupancyMatches != 64) {
                EventLog.add("Cảnh báo: occupancy khác khai cuộc; tiếp tục dùng bàn cờ "
                        + "đã nhận diện hoặc FEN đã sửa trong bộ template.");
            }
            try {
                board.clear();
                board.loadFromFen(savedStartFen);
            } catch (RuntimeException e) {
                EventLog.add("FEN đã sửa không hợp lệ; dùng vị trí khai cuộc: " + e.getMessage());
                board.clear();
                board.loadFromFen(START_FEN);
            }
            EventLog.add("FEN bắt đầu đã hiệu chỉnh: " + board.getFen());
            if (!startRead.isValid()) {
                EventLog.add("Cảnh báo: BoardReader không xác nhận được occupancy khai cuộc; "
                        + "OpenCV tiếp tục.");
            }
            return null;
        }

        board.clear();
        board.loadFromFen(START_FEN);
        int mismatch = startRead.mismatch;
        int occ = startRead.occupied;
        EventLog.add("Kiểm tra vị trí đầu: " + startRead.falsePositives
                + " ô trống nhận nhầm thành có quân, " + startRead.missedPieces
                + " quân bị bỏ sót; đúng " + (64 - mismatch) + "/64 ô");
        if (!startRead.isValid()) {
            return "Không xác nhận được bàn cờ khai cuộc ("
                    + (64 - mismatch) + "/64 ô đúng). Bot chưa chạy để tránh đi sai. "
                    + "Căn chỉnh lại vùng bàn cờ, mở ván mới hoặc train/chọn bộ template.";
        }

        a1Position = orient == Prefs.ORIENT_AUTO
                ? BoardOrientation.inferFromScreenColors(res.screenColors())
                : selectedOrientation;
        EventLog.add("Hướng bàn cờ: a1 ở góc " + BoardOrientation.label(a1Position)
                + " (BoardReader)");

        Prefs.setBoardThresholds(ctx, startRead.devThreshold, startRead.fillThreshold);
        botSide = Prefs.isBotWhite(ctx) ? Side.WHITE : Side.BLACK;
        return null;
    }

    private int startingOccupancyMatches(int[] occupancy) {
        if (occupancy == null || occupancy.length != 64) {
            return 0;
        }
        int matches = 0;
        for (int square = 0; square < 64; square++) {
            boolean expected = square / 8 < 2 || square / 8 >= 6;
            if ((occupancy[square] != 0) == expected) {
                matches++;
            }
        }
        return matches;
    }

    private int[] occupancyFrom(OpenCvChessRecognizer.Result recognized) {
        int[] occupancy = new int[64];
        for (int square = 0; square < occupancy.length; square++) {
            occupancy[square] = recognized.type[square] == OpenCvChessRecognizer.TYPE_EMPTY
                    ? 0 : 1;
        }
        return occupancy;
    }

    private String selectedThemeName(Context context) {
        String id = Prefs.getPieceThemeId(context);
        for (PieceThemeStore.Theme theme : PieceThemeStore.getThemes(context)) {
            if (theme.id.equals(id)) {
                return theme.name;
            }
        }
        return "không rõ";
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
            int[] candidateGrid = candidateRead.boardOccupancy(a1Position);
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
    private Move chooseTypedMove(int[] observed, OpenCvChessRecognizer.Result rec) {
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
                    if (rec.type[index] != OpenCvChessRecognizer.TYPE_EMPTY) {
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
