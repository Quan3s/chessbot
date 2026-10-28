package com.chessbot.assist;

import java.util.Arrays;

/**
 * Recognises which squares of a chess board are empty / hold a white piece /
 * hold a black piece. It is piece-set agnostic: for every square it estimates
 * the square's own background colour (from a ring near the edges) and then
 * measures how much of the inner area differs from that colour.
 *
 * The class is plain Java (no Android imports) so it can be unit tested on a
 * normal JVM with rendered boards.
 */
public final class BoardReader {

    public static final int EMPTY = 0;
    public static final int WHITE = 1;
    public static final int BLACK = 2;
    public static final int UNKNOWN = 3;

    /** Max channel difference from the background to count as "piece". */
    public static int DEV_THRESHOLD = 26;
    /** Fraction of the inner square that must differ to call it occupied. */
    public static float FILL_THRESHOLD = 0.10f;
    private static final int[] START_DEV_THRESHOLDS = {26, 26, 36, 42, 56, 56, 68, 72, 82};
    private static final float[] START_FILL_THRESHOLDS = {
            0.10f, 0.20f, 0.14f, 0.10f, 0.10f, 0.20f, 0.14f, 0.24f, 0.30f
    };
    /** Ring used to sample the square background (fractions of the square). */
    private static final float RING_IN = 0.06f;
    private static final float RING_OUT = 0.16f;
    /** Inner area used for occupancy. */
    private static final float INNER_IN = 0.18f;
    private static final float INNER_OUT = 0.82f;

    private BoardReader() {
    }

    public static final class Cell {
        public boolean occupied;
        public int color = EMPTY;
        public float fill;
        public float pieceLum;   // median luminance of piece pixels
        public float pieceHigh;  // 85th percentile luminance of piece pixels
        public float brightRatio; // fraction of piece pixels that are bright
        public float bgLum;
        // Background colour of this square, used by PieceRecognizer to build
        // a shape mask that is independent of the board theme.
        public int bgR = 128;
        public int bgG = 128;
        public int bgB = 128;
    }

    public static final class Result {
        public final Cell[] cells = new Cell[64]; // screen order, 0 = top-left
        public int left;
        public int top;
        public int right;
        public int bottom;

        public Result() {
            for (int i = 0; i < 64; i++) {
                cells[i] = new Cell();
            }
        }

        /** 1 = occupied, 0 = empty, screen order. */
        public int[] screenOccupancy() {
            int[] g = new int[64];
            for (int i = 0; i < 64; i++) {
                g[i] = cells[i].occupied ? 1 : 0;
            }
            return g;
        }

        /** 1 = occupied, 0 = empty, board order (index 0 = a1, 63 = h8). */
        public int[] boardOccupancy(boolean whiteAtBottom) {
            int[] g = new int[64];
            for (int rank = 0; rank < 8; rank++) {
                for (int file = 0; file < 8; file++) {
                    int sr = whiteAtBottom ? 7 - rank : rank;
                    int sc = whiteAtBottom ? file : 7 - file;
                    g[rank * 8 + file] = cells[sr * 8 + sc].occupied ? 1 : 0;
                }
            }
            return g;
        }

        /** EMPTY / WHITE / BLACK (UNKNOWN is reported as WHITE) in board order. */
        public int[] boardColors(boolean whiteAtBottom) {
            int[] g = new int[64];
            for (int rank = 0; rank < 8; rank++) {
                for (int file = 0; file < 8; file++) {
                    int sr = whiteAtBottom ? 7 - rank : rank;
                    int sc = whiteAtBottom ? file : 7 - file;
                    Cell c = cells[sr * 8 + sc];
                    g[rank * 8 + file] = c.occupied ? (c.color == BLACK ? BLACK : WHITE) : EMPTY;
                }
            }
            return g;
        }

        public int occupiedInRows(int rowFrom, int rowTo) {
            int n = 0;
            for (int r = rowFrom; r <= rowTo; r++) {
                for (int c = 0; c < 8; c++) {
                    if (cells[r * 8 + c].occupied) {
                        n++;
                    }
                }
            }
            return n;
        }

        public float avgPieceHigh(int rowFrom, int rowTo) {
            double sum = 0;
            int n = 0;
            for (int r = rowFrom; r <= rowTo; r++) {
                for (int c = 0; c < 8; c++) {
                    Cell cell = cells[r * 8 + c];
                    if (cell.occupied && cell.pieceHigh > 0) {
                        sum += cell.pieceHigh;
                        n++;
                    }
                }
            }
            return n == 0 ? 0f : (float) (sum / n);
        }

        public float avgPieceContrast(int rowFrom, int rowTo) {
            double sum = 0;
            int n = 0;
            for (int r = rowFrom; r <= rowTo; r++) {
                for (int c = 0; c < 8; c++) {
                    Cell cell = cells[r * 8 + c];
                    if (cell.occupied) {
                        sum += cell.pieceLum - cell.bgLum;
                        n++;
                    }
                }
            }
            return n == 0 ? 0f : (float) (sum / n);
        }

        public String debugRows() {
            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) {
                    Cell cell = cells[r * 8 + c];
                    if (!cell.occupied) {
                        sb.append('.');
                    } else if (cell.color == BLACK) {
                        sb.append('b');
                    } else if (cell.color == WHITE) {
                        sb.append('w');
                    } else {
                        sb.append('?');
                    }
                    if (c != 7) {
                        sb.append(' ');
                    }
                }
                sb.append('\n');
            }
            return sb.toString();
        }
    }

    /** Best read of the standard starting position across conservative thresholds. */
    public static final class StartRead {
        public final Result result;
        public final int devThreshold;
        public final float fillThreshold;
        public final int occupied;
        public final int mismatch;
        public final int falsePositives;
        public final int missedPieces;

        private StartRead(Result result, int devThreshold, float fillThreshold,
                          int occupied, int mismatch, int falsePositives, int missedPieces) {
            this.result = result;
            this.devThreshold = devThreshold;
            this.fillThreshold = fillThreshold;
            this.occupied = occupied;
            this.mismatch = mismatch;
            this.falsePositives = falsePositives;
            this.missedPieces = missedPieces;
        }

        public boolean isValid() {
            return occupied >= 28 && mismatch <= 4;
        }
    }

    /**
     * @param px     ARGB pixels
     * @param w      frame width
     * @param h      frame height
     * @param l,t,r,b board rectangle as fractions of the frame (0..1)
     */
    public static Result read(int[] px, int w, int h,
                              float l, float t, float r, float b) {
        return read(px, w, h, l, t, r, b, DEV_THRESHOLD, FILL_THRESHOLD);
    }

    /**
     * Read a starting board using several contrast settings. Starting-position
     * occupancy is known, so it provides a safe signal for adapting to piece
     * themes and low-resolution captures without guessing at arbitrary boards.
     */
    public static StartRead readStartingPosition(int[] px, int w, int h,
                                                 float l, float t, float r, float b) {
        StartRead best = null;
        for (int i = 0; i < START_DEV_THRESHOLDS.length; i++) {
            Result result = read(px, w, h, l, t, r, b,
                    START_DEV_THRESHOLDS[i], START_FILL_THRESHOLDS[i]);
            int occupied = 0;
            int mismatch = 0;
            int falsePositives = 0;
            int missedPieces = 0;
            for (int row = 0; row < 8; row++) {
                for (int col = 0; col < 8; col++) {
                    boolean expected = row < 2 || row >= 6;
                    boolean detected = result.cells[row * 8 + col].occupied;
                    if (detected) {
                        occupied++;
                    }
                    if (detected && !expected) {
                        falsePositives++;
                    } else if (!detected && expected) {
                        missedPieces++;
                    }
                    if (detected != expected) {
                        mismatch++;
                    }
                }
            }
            if (best == null || mismatch < best.mismatch) {
                best = new StartRead(result, START_DEV_THRESHOLDS[i],
                        START_FILL_THRESHOLDS[i], occupied, mismatch,
                        falsePositives, missedPieces);
            }
            if (mismatch == 0) {
                break;
            }
        }
        return best;
    }

    public static Result read(int[] px, int w, int h,
                              float l, float t, float r, float b,
                              int devThreshold, float fillThreshold) {
        Result res = new Result();
        int left = clamp(Math.round(l * w), 0, w - 1);
        int top = clamp(Math.round(t * h), 0, h - 1);
        int right = clamp(Math.round(r * w), left + 8, w);
        int bottom = clamp(Math.round(b * h), top + 8, h);
        res.left = left;
        res.top = top;
        res.right = right;
        res.bottom = bottom;

        float cw = (right - left) / 8f;
        float ch = (bottom - top) / 8f;

        int[] hist = new int[4096];
        int[] lumHist = new int[256];

        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                int x0 = left + Math.round(col * cw);
                int x1 = left + Math.round((col + 1) * cw);
                int y0 = top + Math.round(row * ch);
                int y1 = top + Math.round((row + 1) * ch);
                readCell(px, w, h, x0, y0, x1, y1, hist, lumHist,
                        res.cells[row * 8 + col], devThreshold, fillThreshold);
            }
        }
        return res;
    }

    private static void readCell(int[] px, int w, int h,
                                 int x0, int y0, int x1, int y1,
                                 int[] hist, int[] lumHist, Cell out,
                                 int devThreshold, float fillThreshold) {
        int sw = x1 - x0;
        int sh = y1 - y0;
        if (sw < 4 || sh < 4) {
            out.occupied = false;
            out.color = EMPTY;
            return;
        }

        int rx0 = x0 + Math.round(sw * RING_IN);
        int rx1 = x1 - Math.round(sw * RING_IN);
        int ry0 = y0 + Math.round(sh * RING_IN);
        int ry1 = y1 - Math.round(sh * RING_IN);
        int rx0b = x0 + Math.round(sw * RING_OUT);
        int rx1b = x1 - Math.round(sw * RING_OUT);
        int ry0b = y0 + Math.round(sh * RING_OUT);
        int ry1b = y1 - Math.round(sh * RING_OUT);

        Arrays.fill(hist, 0);
        int ringTotal = 0;
        // top + bottom bands
        for (int y = clamp(ry0, 0, h - 1); y <= clamp(ry0b, 0, h - 1); y++) {
            for (int x = clamp(rx0, 0, w - 1); x <= clamp(rx1, 0, w - 1); x++) {
                hist[bin(px[y * w + x])]++;
                ringTotal++;
            }
        }
        for (int y = clamp(ry1b, 0, h - 1); y <= clamp(ry1, 0, h - 1); y++) {
            for (int x = clamp(rx0, 0, w - 1); x <= clamp(rx1, 0, w - 1); x++) {
                hist[bin(px[y * w + x])]++;
                ringTotal++;
            }
        }
        // left + right bands (avoid double counting corners)
        for (int y = clamp(ry0b + 1, 0, h - 1); y <= clamp(ry1b - 1, 0, h - 1); y++) {
            for (int x = clamp(rx0, 0, w - 1); x <= clamp(rx0b, 0, w - 1); x++) {
                hist[bin(px[y * w + x])]++;
                ringTotal++;
            }
            for (int x = clamp(rx1b, 0, w - 1); x <= clamp(rx1, 0, w - 1); x++) {
                hist[bin(px[y * w + x])]++;
                ringTotal++;
            }
        }

        int bestBin = 0;
        int bestCount = -1;
        for (int i = 0; i < 4096; i++) {
            if (hist[i] > bestCount) {
                bestCount = hist[i];
                bestBin = i;
            }
        }

        long sr = 0, sg = 0, sb = 0;
        int bn = 0;
        // second pass over the same ring to average the modal colour
        for (int y = clamp(ry0, 0, h - 1); y <= clamp(ry0b, 0, h - 1); y++) {
            for (int x = clamp(rx0, 0, w - 1); x <= clamp(rx1, 0, w - 1); x++) {
                int c = px[y * w + x];
                if (bin(c) == bestBin) {
                    sr += (c >> 16) & 255;
                    sg += (c >> 8) & 255;
                    sb += c & 255;
                    bn++;
                }
            }
        }
        for (int y = clamp(ry1b, 0, h - 1); y <= clamp(ry1, 0, h - 1); y++) {
            for (int x = clamp(rx0, 0, w - 1); x <= clamp(rx1, 0, w - 1); x++) {
                int c = px[y * w + x];
                if (bin(c) == bestBin) {
                    sr += (c >> 16) & 255;
                    sg += (c >> 8) & 255;
                    sb += c & 255;
                    bn++;
                }
            }
        }
        for (int y = clamp(ry0b + 1, 0, h - 1); y <= clamp(ry1b - 1, 0, h - 1); y++) {
            for (int x = clamp(rx0, 0, w - 1); x <= clamp(rx0b, 0, w - 1); x++) {
                int c = px[y * w + x];
                if (bin(c) == bestBin) {
                    sr += (c >> 16) & 255;
                    sg += (c >> 8) & 255;
                    sb += c & 255;
                    bn++;
                }
            }
            for (int x = clamp(rx1b, 0, w - 1); x <= clamp(rx1, 0, w - 1); x++) {
                int c = px[y * w + x];
                if (bin(c) == bestBin) {
                    sr += (c >> 16) & 255;
                    sg += (c >> 8) & 255;
                    sb += c & 255;
                    bn++;
                }
            }
        }

        int bgR, bgG, bgB;
        if (bn > 0) {
            bgR = (int) (sr / bn);
            bgG = (int) (sg / bn);
            bgB = (int) (sb / bn);
        } else {
            bgR = 128;
            bgG = 128;
            bgB = 128;
        }
        out.bgR = bgR;
        out.bgG = bgG;
        out.bgB = bgB;
        out.bgLum = lum(bgR, bgG, bgB);

        int ix0 = x0 + Math.round(sw * INNER_IN);
        int ix1 = x1 - Math.round(sw * INNER_IN);
        int iy0 = y0 + Math.round(sh * INNER_IN);
        int iy1 = y1 - Math.round(sh * INNER_IN);
        Arrays.fill(lumHist, 0);
        int total = 0;
        int dev = 0;
        int bright = 0;
        for (int y = clamp(iy0, 0, h - 1); y < clamp(iy1, 0, h); y++) {
            int rowOff = y * w;
            for (int x = clamp(ix0, 0, w - 1); x < clamp(ix1, 0, w); x++) {
                int c = px[rowOff + x];
                int rr = (c >> 16) & 255;
                int gg = (c >> 8) & 255;
                int bb = c & 255;
                int d = Math.abs(rr - bgR);
                int d2 = Math.abs(gg - bgG);
                int d3 = Math.abs(bb - bgB);
                if (d2 > d) d = d2;
                if (d3 > d) d = d3;
                total++;
                if (d > devThreshold) {
                    dev++;
                    int l = lum(rr, gg, bb);
                    lumHist[l]++;
                    if (l >= 170) {
                        bright++;
                    }
                }
            }
        }

        out.fill = total == 0 ? 0f : dev / (float) total;
        if (out.fill < fillThreshold || dev == 0) {
            out.occupied = false;
            out.color = EMPTY;
            out.pieceLum = 0;
            out.pieceHigh = 0;
            return;
        }

        out.occupied = true;
        out.pieceLum = percentile(lumHist, dev, 0.50f);
        out.pieceHigh = percentile(lumHist, dev, 0.85f);
        out.brightRatio = bright / (float) dev;

        float diff = out.pieceLum - out.bgLum;
        if (diff > 14) {
            out.color = WHITE;
        } else if (diff < -14) {
            out.color = BLACK;
        } else {
            out.color = UNKNOWN;
        }
    }

    private static int percentile(int[] hist, int total, float p) {
        int target = Math.max(1, (int) (total * p));
        int acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= target) {
                return i;
            }
        }
        return 255;
    }

    private static int bin(int c) {
        return (((c >> 16) & 255) >> 4) << 8
                | (((c >> 8) & 255) >> 4) << 4
                | ((c & 255) >> 4);
    }

    private static int lum(int r, int g, int b) {
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
