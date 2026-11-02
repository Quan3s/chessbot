package com.chessbot.assist;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

/** Captures one opening-position image, then lets the user correct its board. */
public final class PieceTrainingOverlay extends LinearLayout {
    public interface Callback {
        void onCapture(String themeName, int orientation);
        void onSave(String themeName, int orientation, int[] board);
        void onCancel();
    }

    private static final String PIECES = "PNBRQK";
    private final Callback callback;
    private final EditText themeName;
    private final RadioGroup orientationGroup;
    private final TextView instruction;
    private final TextView status;
    private final Button capture;
    private final Button save;
    private final GridLayout boardGrid;
    private int[] board;
    private int orientation = BoardOrientation.A1_BOTTOM_LEFT;

    public PieceTrainingOverlay(Context context, Callback callback) {
        super(context);
        this.callback = callback;
        setOrientation(VERTICAL);
        setPadding(dp(12), dp(8), dp(12), dp(8));
        setBackgroundColor(0xEE10141C);

        TextView heading = new TextView(context);
        heading.setText("Train bộ template từ khai cuộc");
        heading.setTextColor(0xFFEDEFF5);
        heading.setTextSize(15);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addView(heading, matchWrap());

        themeName = new EditText(context);
        themeName.setSingleLine(true);
        themeName.setHint("Tên bộ template");
        themeName.setTextSize(14);
        addView(themeName, matchWrap());

        TextView orientationLabel = new TextView(context);
        orientationLabel.setText("Hướng bàn cờ");
        orientationLabel.setTextColor(0xFFEDEFF5);
        orientationLabel.setTextSize(13);
        addView(orientationLabel, matchWrap());

        orientationGroup = new RadioGroup(context);
        orientationGroup.setOrientation(HORIZONTAL);
        RadioButton whiteBottom = radio(context, "Trắng dưới");
        whiteBottom.setId(1);
        RadioButton whiteTop = radio(context, "Trắng trên");
        whiteTop.setId(2);
        orientationGroup.addView(whiteBottom);
        orientationGroup.addView(whiteTop);
        orientationGroup.check(whiteBottom.getId());
        addView(orientationGroup, matchWrap());

        instruction = new TextView(context);
        instruction.setText("Đưa bàn cờ về khai cuộc, căn chỉnh vùng bàn cờ trước, "
                + "rồi chụp một ảnh. OpenCV sẽ học từ 32 quân và các ô trống.");
        instruction.setTextColor(0xFFEDEFF5);
        instruction.setTextSize(13);
        instruction.setPadding(0, dp(4), 0, dp(4));
        addView(instruction, matchWrap());

        status = new TextView(context);
        status.setTextColor(0xFF9AA1B2);
        status.setTextSize(12);
        addView(status, matchWrap());

        boardGrid = new GridLayout(context);
        boardGrid.setColumnCount(8);
        boardGrid.setRowCount(8);
        boardGrid.setVisibility(GONE);
        boardGrid.setUseDefaultMargins(false);
        LayoutParams boardLp = new LayoutParams(dp(300), dp(300));
        boardLp.gravity = Gravity.CENTER_HORIZONTAL;
        addView(boardGrid, boardLp);

        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        addView(actions, matchWrap());
        capture = new Button(context);
        capture.setText("Chụp & nhận diện");
        capture.setAllCaps(false);
        save = new Button(context);
        save.setText("Lưu bộ template");
        save.setAllCaps(false);
        save.setVisibility(GONE);
        Button cancel = new Button(context);
        cancel.setText("Hủy");
        cancel.setAllCaps(false);
        LayoutParams actionLp = new LayoutParams(0, dp(44), 1f);
        actionLp.setMargins(0, 0, dp(5), 0);
        actions.addView(capture, actionLp);
        LayoutParams saveLp = new LayoutParams(0, dp(44), 1f);
        saveLp.setMargins(0, 0, dp(5), 0);
        actions.addView(save, saveLp);
        actions.addView(cancel, new LayoutParams(0, dp(44), 0.7f));

        orientationGroup.setOnCheckedChangeListener((group, checkedId) ->
                orientation = checkedId == 2 ? BoardOrientation.A1_TOP_RIGHT
                        : BoardOrientation.A1_BOTTOM_LEFT);
        capture.setOnClickListener(v -> {
            String name = checkedName();
            if (name != null) {
                capture.setEnabled(false);
                callback.onCapture(name, orientation);
            }
        });
        save.setOnClickListener(v -> {
            String name = checkedName();
            if (name != null && board != null) {
                callback.onSave(name, orientation, board.clone());
            }
        });
        cancel.setOnClickListener(v -> callback.onCancel());
    }

    public void setBusy(boolean busy) {
        capture.setEnabled(!busy);
        status.setText(busy ? "Đang resize 320×320 và chạy OpenCV…" : "");
    }

    public void showRecognition(OpenCvChessRecognizer.Result result, String warning) {
        board = result.classIndex.clone();
        instruction.setText("Kết quả 8×8 · chạm ô để đổi: trống → Trắng → Đen → trống. "
                + "Sửa các ô nhận sai rồi lưu.");
        capture.setVisibility(GONE);
        themeName.setVisibility(VISIBLE);
        orientationGroup.setVisibility(GONE);
        boardGrid.setVisibility(VISIBLE);
        save.setVisibility(VISIBLE);
        status.setText(warning == null ? "FEN: " + result.toFen(true)
                : warning + "\nFEN sơ bộ: " + result.toFen(true));
        renderBoard();
    }

    public void showError(String message) {
        status.setText(message);
        status.setTextColor(0xFFFF8A80);
        capture.setEnabled(true);
    }

    private String checkedName() {
        String name = themeName.getText().toString().trim();
        if (name.isEmpty()) {
            themeName.setError("Nhập tên bộ template");
            return null;
        }
        if (name.length() > 40) {
            themeName.setError("Tên tối đa 40 ký tự");
            return null;
        }
        return name;
    }

    private void renderBoard() {
        boardGrid.removeAllViews();
        for (int row = 0; row < 8; row++) {
            for (int file = 0; file < 8; file++) {
                int rank = 7 - row;
                int square = rank * 8 + file;
                Button cell = new Button(getContext());
                cell.setText(symbol(board[square]));
                cell.setTextSize(14);
                cell.setMinWidth(0);
                cell.setMinimumWidth(0);
                cell.setMinHeight(0);
                cell.setMinimumHeight(0);
                cell.setPadding(0, 0, 0, 0);
                cell.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        ((rank + file) & 1) != 0 ? 0xFFDDC9A3 : 0xFF71805A));
                colorCell(cell, board[square]);
                cell.setOnClickListener(v -> {
                    int current = board[square];
                    board[square] = current == OpenCvChessRecognizer.TYPE_UNKNOWN ? -1
                            : current < 0 ? 0
                            : current < 5 ? current + 1
                            : current == 5 ? 6
                            : current < 11 ? current + 1 : -1;
                    cell.setText(symbol(board[square]));
                    colorCell(cell, board[square]);
                    status.setText("FEN: "
                            + OpenCvChessRecognizer.fenFromTypes(board, true));
                });
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = 0;
                lp.height = 0;
                lp.columnSpec = GridLayout.spec(file, 1f);
                lp.rowSpec = GridLayout.spec(row, 1f);
                boardGrid.addView(cell, lp);
            }
        }
    }

    private void colorCell(Button cell, int type) {
        cell.setTextColor(type >= 6 && type < 12 ? 0xFF17191D : Color.WHITE);
    }

    private String symbol(int type) {
        if (type == OpenCvChessRecognizer.TYPE_UNKNOWN) return "?";
        if (type < 0 || type >= OpenCvChessRecognizer.CLASS_COUNT) return "·";
        char piece = PIECES.charAt(type % 6);
        return String.valueOf(type >= 6 ? Character.toLowerCase(piece) : piece);
    }

    private RadioButton radio(Context context, String label) {
        RadioButton button = new RadioButton(context);
        button.setText(label);
        button.setTextColor(0xFFEDEFF5);
        button.setTextSize(13);
        return button;
    }

    private LayoutParams matchWrap() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
