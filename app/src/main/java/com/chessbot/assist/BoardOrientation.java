package com.chessbot.assist;

/** Maps chess squares to the calibrated screen grid for each board rotation. */
public final class BoardOrientation {

    public static final int A1_BOTTOM_LEFT = 0;
    public static final int A1_BOTTOM_RIGHT = 1;
    public static final int A1_TOP_LEFT = 2;
    public static final int A1_TOP_RIGHT = 3;

    private BoardOrientation() {
    }

    public static int fromPreference(int preference) {
        switch (preference) {
            case Prefs.ORIENT_WHITE_BOTTOM:
                return A1_BOTTOM_LEFT;
            case Prefs.ORIENT_WHITE_TOP:
                return A1_TOP_RIGHT;
            case Prefs.ORIENT_WHITE_RIGHT:
                return A1_BOTTOM_RIGHT;
            case Prefs.ORIENT_WHITE_LEFT:
                return A1_TOP_LEFT;
            default:
                return A1_BOTTOM_LEFT;
        }
    }

    public static int screenRowForSquare(int rank, int file, int orientation) {
        switch (orientation) {
            case A1_BOTTOM_RIGHT:
                return 7 - file;
            case A1_TOP_LEFT:
                return file;
            case A1_TOP_RIGHT:
                return rank;
            default:
                return 7 - rank;
        }
    }

    public static int screenColumnForSquare(int rank, int file, int orientation) {
        switch (orientation) {
            case A1_BOTTOM_RIGHT:
                return 7 - rank;
            case A1_TOP_LEFT:
                return rank;
            case A1_TOP_RIGHT:
                return 7 - file;
            default:
                return file;
        }
    }

    public static int screenRowForFenRow(int fenRow, int file, int orientation) {
        return screenRowForSquare(7 - fenRow, file, orientation);
    }

    public static int screenColumnForFenRow(int fenRow, int file, int orientation) {
        return screenColumnForSquare(7 - fenRow, file, orientation);
    }

    public static int inferFromScreenColors(int[] colors) {
        if (colors == null || colors.length != 64) {
            return A1_BOTTOM_LEFT;
        }
        int whiteTop = 0;
        int whiteBottom = 0;
        int blackTop = 0;
        int blackBottom = 0;
        int whiteLeft = 0;
        int whiteRight = 0;
        int blackLeft = 0;
        int blackRight = 0;
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                int color = colors[row * 8 + col];
                if (color == BoardReader.WHITE) {
                    if (row < 4) whiteTop++;
                    else whiteBottom++;
                    if (col < 4) whiteLeft++;
                    else whiteRight++;
                } else if (color == BoardReader.BLACK) {
                    if (row < 4) blackTop++;
                    else blackBottom++;
                    if (col < 4) blackLeft++;
                    else blackRight++;
                }
            }
        }
        int vertical = whiteBottom + blackTop - whiteTop - blackBottom;
        int horizontal = whiteLeft + blackRight - whiteRight - blackLeft;
        if (Math.abs(vertical) >= Math.abs(horizontal)) {
            return vertical >= 0 ? A1_BOTTOM_LEFT : A1_TOP_RIGHT;
        }
        return horizontal >= 0 ? A1_TOP_LEFT : A1_BOTTOM_RIGHT;
    }

    public static String label(int orientation) {
        switch (orientation) {
            case A1_BOTTOM_RIGHT:
                return "dưới phải";
            case A1_TOP_LEFT:
                return "trên trái";
            case A1_TOP_RIGHT:
                return "trên phải";
            default:
                return "dưới trái";
        }
    }
}
