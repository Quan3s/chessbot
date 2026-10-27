package com.chessbot.assist;

import java.util.Arrays;

/**
 * Recognises the <b>type</b> and <b>colour</b> of the piece on each square.
 *
 * <p>Piece sets are not known in advance, so the recognizer does not ship with
 * images. It learns one shape template per piece from the standard starting
 * position the first time the bot starts: every square already has a known
 * piece there, so 32 labelled samples are available for free. From then on it
 * extracts a background-independent shape mask from each occupied square and
 * compares it with the learned templates.</p>
 *
 * <p>The mask is built from the per-square background colour estimated by
 * {@link BoardReader}, then contrast-normalised per square. That makes it work
 * on light and dark squares, and on coloured last-move highlights, without
 * knowing the board theme. Classification compares each of the six piece types
 * against both the white and the black template, so the shape decides the type
 * and the existing luminance test decides the colour independently.</p>
 *
 * <p>Plain Java, no Android imports, so it can be unit tested on a desktop JVM.
 * This class only proposes information; {@code GameController} keeps the final
 * say and falls back to the old occupancy matching whenever confidence is low.</p>
 */
public final class PieceRecognizer {

    /** Side of the square mask used for matching. */
    public static final int GRID = 20;

    public static final int TYPE_PAWN = 0;
    public static final int TYPE_KNIGHT = 1;
    public static final int TYPE_BISHOP = 2;
    public static final int TYPE_ROOK = 3;
    public static final int TYPE_QUEEN = 4;
    public static final int TYPE_KING = 5;
    public static final int TYPE_EMPTY = 6;
    public static final int TYPE_UNKNOWN = 7;

    public static final int WHITE = BoardReader.WHITE;
    public static final int BLACK = BoardReader.BLACK;

    public static final int CLASS_COUNT = 12;

    /** Below this normalised correlation the type is treated as unknown. */
    public static float MIN_SCORE = 0.44f;
    /** Best type must beat the second best by at least this much. */
    public static float MIN_MARGIN = 0.025f;
    /** Minimum average mask value for an occupied square to be classified. */
    public static float MIN_FILL = 0.045f;

    private static final float INNER_IN = 0.08f;
    private static final float INNER_OUT = 0.92f;

    private static final String TYPE_CHARS = "PNBRQK";

    private final float[][] templates = new float[CLASS_COUNT][];
    private final float[] templateFill = new float[CLASS_COUNT];
    private final float[] templateColorContrast = new float[CLASS_COUNT];
    private final int[] classSamples = new int[CLASS_COUNT];
    private int learnedClasses;
    private int learnedSamples;

    public static final class LearnResult {
        public int classes;
        public int samples;
        public int occupied;
        public String detail = "";
    }

    /** Recognition result for a full board, in board order (index 0 = a1). */
    public static final class Recognized {
        public final int[] type = new int[64];
        public final int[] color = new int[64];
        public final float[] confidence = new float[64];
        public int occupied;
        public int knownTypes;
        public int knownColors;

        Recognized() {
            Arrays.fill(type, TYPE_EMPTY);
            Arrays.fill(color, BoardReader.EMPTY);
        }
    }

    public boolean isReady() {
        return learnedClasses == CLASS_COUNT;
    }

    public int learnedClasses() {
        return learnedClasses;
    }

    public int learnedSamples() {
        return learnedSamples;
    }

    /**
     * Learn one averaged mask for each of the 12 piece classes.
     *
     * @param classByScreenCell 64 entries in screen order; -1 = no sample,
     *                          0..11 = chesslib piece ordinal (white P..K, black P..K)
     */
    public LearnResult learn(int[] px, int w, int h, BoardReader.Result res,
                             int[] classByScreenCell) {
        LearnResult out = new LearnResult();
        if (px == null || res == null || classByScreenCell == null) {
            return out;
        }
        float[][] sum = new float[CLASS_COUNT][GRID * GRID];
        float[] colorContrastSum = new float[CLASS_COUNT];
        int[] n = new int[CLASS_COUNT];
        StringBuilder detail = new StringBuilder();

        for (int idx = 0; idx < 64; idx++) {
            int cls = classByScreenCell[idx];
            if (cls < 0 || cls >= CLASS_COUNT) {
                continue;
            }
            BoardReader.Cell cell = res.cells[idx];
            if (!cell.occupied) {
                continue;
            }
            float[] mask = extract(px, w, h, res, idx);
            float fill = mean(mask);
            if (fill < MIN_FILL || fill > 0.90f) {
                continue;
            }
            float[] dst = sum[cls];
            for (int i = 0; i < mask.length; i++) {
                dst[i] += mask[i];
            }
            colorContrastSum[cls] += cell.pieceLum - cell.bgLum;
            n[cls]++;
            out.samples++;
        }

        Arrays.fill(templates, null);
        Arrays.fill(templateFill, 0f);
        Arrays.fill(templateColorContrast, 0f);
        Arrays.fill(classSamples, 0);
        learnedClasses = 0;
        learnedSamples = out.samples;

        for (int cls = 0; cls < CLASS_COUNT; cls++) {
            classSamples[cls] = n[cls];
            if (n[cls] == 0) {
                continue;
            }
            float[] t = sum[cls];
            for (int i = 0; i < t.length; i++) {
                t[i] /= n[cls];
            }
            templates[cls] = t;
            templateFill[cls] = mean(t);
            templateColorContrast[cls] = colorContrastSum[cls] / n[cls];
            learnedClasses++;
            if (detail.length() > 0) {
                detail.append(' ');
            }
            detail.append(TYPE_CHARS.charAt(cls % 6));
            if (cls >= 6) {
                detail.append('_');
            }
            detail.append(n[cls]);
        }
        out.classes = learnedClasses;
        out.occupied = out.samples;
        out.detail = detail.toString();
        return out;
    }

    /**
     * Classify every square of the board.
     *
     * @return types and colours in board order (a1, b1, ... h8)
     */
    public Recognized classifyBoard(int[] px, int w, int h, BoardReader.Result res,
                                    boolean whiteAtBottom) {
        Recognized out = new Recognized();
        if (px == null || res == null || !isReady()) {
            return out;
        }
        for (int rank = 0; rank < 8; rank++) {
            for (int file = 0; file < 8; file++) {
                int sr = whiteAtBottom ? 7 - rank : rank;
                int sc = whiteAtBottom ? file : 7 - file;
                int screen = sr * 8 + sc;
                int board = rank * 8 + file;
                BoardReader.Cell cell = res.cells[screen];
                if (!cell.occupied) {
                    out.type[board] = TYPE_EMPTY;
                    out.color[board] = BoardReader.EMPTY;
                    continue;
                }
                out.occupied++;
                float[] mask = extract(px, w, h, res, screen);
                float fill = mean(mask);
                if (fill < MIN_FILL) {
                    out.type[board] = TYPE_UNKNOWN;
                    out.color[board] = cell.color;
                    continue;
                }
                int[] typed = classifyMask(mask, cell.color,
                        cell.pieceLum - cell.bgLum, out.confidence, board);
                out.type[board] = typed[0];
                out.color[board] = typed[1];
                if (typed[0] != TYPE_UNKNOWN) {
                    out.knownTypes++;
                }
                if (typed[1] == BoardReader.WHITE || typed[1] == BoardReader.BLACK) {
                    out.knownColors++;
                }
            }
        }
        return out;
    }

    /**
     * Classify a single mask.
     *
     * @return {type, colour}; colour can be BoardReader.UNKNOWN if luminance is ambiguous
     */
    private int[] classifyMask(float[] mask, int cellColor, float colorContrast,
                               float[] confidence, int boardIndex) {
        float best = -2f;
        float second = -2f;
        int bestType = TYPE_UNKNOWN;

        for (int type = 0; type < 6; type++) {
            float typeScore = -2f;
            for (int variant = 0; variant < 2; variant++) {
                int cls = type + variant * 6;
                if (templates[cls] == null) {
                    continue;
                }
                float s = bestNcc(mask, templates[cls]);
                float templateContrast = templateColorContrast[cls];
                if (Math.abs(colorContrast) >= 14f && Math.abs(templateContrast) >= 14f) {
                    boolean sameColor = Math.signum(colorContrast)
                            == Math.signum(templateContrast);
                    s = s * 0.85f + (sameColor ? 0.15f : -0.15f);
                }
                if (s > typeScore) {
                    typeScore = s;
                }
            }
            if (typeScore > best) {
                second = best;
                best = typeScore;
                bestType = type;
            } else if (typeScore > second) {
                second = typeScore;
            }
        }

        if (best < MIN_SCORE || best - second < MIN_MARGIN) {
            bestType = TYPE_UNKNOWN;
        }
        if (confidence != null && boardIndex >= 0 && boardIndex < confidence.length) {
            confidence[boardIndex] = best;
        }

        int color = cellColor;
        if (color != BoardReader.WHITE && color != BoardReader.BLACK) {
            color = colorContrast > 14f ? BoardReader.WHITE
                    : colorContrast < -14f ? BoardReader.BLACK : BoardReader.UNKNOWN;
        }
        return new int[]{bestType, color};
    }

    /** Human-readable board, always from White's side (rank 8 at the top). */
    public String describe(Recognized rec) {
        if (rec == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            for (int file = 0; file < 8; file++) {
                int i = rank * 8 + file;
                int t = rec.type[i];
                if (t == TYPE_EMPTY) {
                    sb.append('.');
                } else if (t == TYPE_UNKNOWN || t < 0 || t > 5) {
                    sb.append('?');
                } else {
                    char c = TYPE_CHARS.charAt(t);
                    sb.append(rec.color[i] == BoardReader.BLACK
                            ? Character.toLowerCase(c) : c);
                }
                if (file != 7) {
                    sb.append(' ');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Average confidence over occupied, classified squares (0 when none). */
    public static float averageConfidence(Recognized rec) {
        if (rec == null || rec.occupied == 0) {
            return 0f;
        }
        float sum = 0f;
        int n = 0;
        for (int i = 0; i < 64; i++) {
            if (rec.type[i] != TYPE_EMPTY && rec.type[i] != TYPE_UNKNOWN) {
                sum += rec.confidence[i];
                n++;
            }
        }
        return n == 0 ? 0f : sum / n;
    }

    /**
     * Extract a contrast-normalised shape mask for one screen square.
     * The per-square background colour makes this independent of board theme;
     * the percentile stretch makes it independent of piece colour and of a
     * last-move highlight behind the piece.
     */
    public static float[] extract(int[] px, int w, int h, BoardReader.Result res, int screenIdx) {
        float[] mask = new float[GRID * GRID];
        if (px == null || res == null || screenIdx < 0 || screenIdx >= 64) {
            return mask;
        }
        int row = screenIdx / 8;
        int col = screenIdx % 8;
        float cw = (res.right - res.left) / 8f;
        float ch = (res.bottom - res.top) / 8f;
        int x0 = res.left + Math.round(col * cw);
        int x1 = res.left + Math.round((col + 1) * cw);
        int y0 = res.top + Math.round(row * ch);
        int y1 = res.top + Math.round((row + 1) * ch);
        int sw = x1 - x0;
        int sh = y1 - y0;
        if (sw < 4 || sh < 4) {
            return mask;
        }
        BoardReader.Cell cell = res.cells[screenIdx];
        int bgR = cell.bgR;
        int bgG = cell.bgG;
        int bgB = cell.bgB;

        int[] hist = new int[256];
        int total = GRID * GRID;
        for (int gy = 0; gy < GRID; gy++) {
            for (int gx = 0; gx < GRID; gx++) {
                float u = INNER_IN + (gx + 0.5f) / GRID * (INNER_OUT - INNER_IN);
                float v = INNER_IN + (gy + 0.5f) / GRID * (INNER_OUT - INNER_IN);
                int x = x0 + (int) (u * sw);
                int y = y0 + (int) (v * sh);
                int sumR = 0, sumG = 0, sumB = 0, n = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int xx = x + dx;
                        int yy = y + dy;
                        if (xx >= x0 && xx < x1 && yy >= y0 && yy < y1
                                && xx >= 0 && xx < w && yy >= 0 && yy < h) {
                            int c = px[yy * w + xx];
                            sumR += (c >> 16) & 255;
                            sumG += (c >> 8) & 255;
                            sumB += c & 255;
                            n++;
                        }
                    }
                }
                int r = n == 0 ? bgR : sumR / n;
                int g = n == 0 ? bgG : sumG / n;
                int b = n == 0 ? bgB : sumB / n;
                int d = Math.abs(r - bgR);
                int d2 = Math.abs(g - bgG);
                int d3 = Math.abs(b - bgB);
                if (d2 > d) d = d2;
                if (d3 > d) d = d3;
                if (d > 255) d = 255;
                mask[gy * GRID + gx] = d;
                hist[d]++;
            }
        }

        // Robust contrast stretch: the lower 20% is board background, the
        // upper 8% is the piece. This keeps white-on-light pieces visible and
        // stops a highlighted square from washing the mask out.
        int low = percentile(hist, total, 0.20f);
        int high = percentile(hist, total, 0.92f);
        float range = Math.max(12f, high - low);
        for (int i = 0; i < mask.length; i++) {
            float v = (mask[i] - low) / range;
            mask[i] = v < 0f ? 0f : (v > 1f ? 1f : v);
        }
        return mask;
    }

    private static float bestNcc(float[] a, float[] b) {
        float best = -2f;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                float v = ncc(a, b, dx, dy);
                if (v > best) {
                    best = v;
                }
            }
        }
        return best;
    }

    /** Normalised cross-correlation of a (shifted) and b. */
    private static float ncc(float[] a, float[] b, int dx, int dy) {
        double sa = 0, sb = 0;
        int n = 0;
        for (int y = 0; y < GRID; y++) {
            int ay = y + dy;
            if (ay < 0 || ay >= GRID) {
                continue;
            }
            for (int x = 0; x < GRID; x++) {
                int ax = x + dx;
                if (ax < 0 || ax >= GRID) {
                    continue;
                }
                sa += a[ay * GRID + ax];
                sb += b[y * GRID + x];
                n++;
            }
        }
        if (n < 20) {
            return -2f;
        }
        double ma = sa / n;
        double mb = sb / n;
        double va = 0, vb = 0, cov = 0;
        for (int y = 0; y < GRID; y++) {
            int ay = y + dy;
            if (ay < 0 || ay >= GRID) {
                continue;
            }
            for (int x = 0; x < GRID; x++) {
                int ax = x + dx;
                if (ax < 0 || ax >= GRID) {
                    continue;
                }
                double da = a[ay * GRID + ax] - ma;
                double db = b[y * GRID + x] - mb;
                va += da * da;
                vb += db * db;
                cov += da * db;
            }
        }
        if (va < 1e-6 || vb < 1e-6) {
            return -2f;
        }
        return (float) (cov / Math.sqrt(va * vb));
    }

    private static float mean(float[] v) {
        double s = 0;
        for (float x : v) {
            s += x;
        }
        return v.length == 0 ? 0f : (float) (s / v.length);
    }

    private static int percentile(int[] hist, int total, float p) {
        int target = Math.max(1, (int) (total * p));
        int acc = 0;
        for (int i = 0; i < hist.length; i++) {
            acc += hist[i];
            if (acc >= target) {
                return i;
            }
        }
        return 255;
    }
}
