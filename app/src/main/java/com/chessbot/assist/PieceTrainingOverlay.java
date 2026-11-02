package com.chessbot.assist;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.bhlangonijr.chesslib.Square;

/** Compact training panel kept outside the calibrated board area. */
public final class PieceTrainingOverlay extends LinearLayout {

    public interface Callback {
        void onBegin(String themeName);
        void onCapture(int step);
        void onCancel();
    }

    private static final String[] PIECE_NAMES = {
            "Tốt", "Mã", "Tượng", "Xe", "Hậu", "Vua"
    };
    private static final char[] PIECE_SYMBOLS = {'P', 'N', 'B', 'R', 'Q', 'K'};

    private final Callback callback;
    private final EditText themeName;
    private final TextView instruction;
    private final TextView status;
    private final Button begin;
    private final Button capture;
    private int step = -1;

    public PieceTrainingOverlay(Context context, Callback callback) {
        super(context);
        this.callback = callback;
        setOrientation(VERTICAL);
        setPadding(dp(12), dp(8), dp(12), dp(8));
        setBackgroundColor(0xEE10141C);

        TextView heading = new TextView(context);
        heading.setText("Train theme quân cờ");
        heading.setTextColor(0xFFEDEFF5);
        heading.setTextSize(15);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addView(heading, matchWrap());

        themeName = new EditText(context);
        themeName.setSingleLine(true);
        themeName.setHint("Tên theme, ví dụ: Chess.com 2D");
        themeName.setTextSize(14);
        addView(themeName, matchWrap());

        instruction = new TextView(context);
        instruction.setText("Mở bàn cờ phân tích/chỉnh thế cờ. Lấy 24 mẫu: 12 nhóm quân "
                + "× ô tối và ô sáng. Đặt quân được yêu cầu vào đúng ô rồi chụp.");
        instruction.setTextColor(0xFFEDEFF5);
        instruction.setTextSize(13);
        instruction.setLineSpacing(dp(2), 1f);
        instruction.setPadding(0, dp(4), 0, dp(4));
        addView(instruction, matchWrap());

        status = new TextView(context);
        status.setTextColor(0xFF9AA1B2);
        status.setTextSize(12);
        addView(status, matchWrap());

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams buttonsLp = matchWrap();
        buttonsLp.topMargin = dp(4);
        addView(buttons, buttonsLp);

        begin = new Button(context);
        begin.setText("Bắt đầu train");
        begin.setAllCaps(false);
        capture = new Button(context);
        capture.setText("Chụp mẫu");
        capture.setAllCaps(false);
        capture.setVisibility(GONE);
        Button cancel = new Button(context);
        cancel.setText("Hủy");
        cancel.setAllCaps(false);

        LayoutParams actionLp = new LayoutParams(0, dp(44), 1f);
        actionLp.setMargins(0, 0, dp(5), 0);
        buttons.addView(begin, actionLp);
        LayoutParams captureLp = new LayoutParams(0, dp(44), 1f);
        captureLp.setMargins(0, 0, dp(5), 0);
        buttons.addView(capture, captureLp);
        buttons.addView(cancel, new LayoutParams(0, dp(44), 0.7f));

        begin.setOnClickListener(v -> {
            String name = themeName.getText().toString().trim();
            if (name.isEmpty()) {
                themeName.setError("Nhập tên theme");
                return;
            }
            if (name.length() > 40) {
                themeName.setError("Tên tối đa 40 ký tự");
                return;
            }
            callback.onBegin(name);
        });
        capture.setOnClickListener(v -> {
            if (step >= 0 && step < PieceRecognizer.CLASS_COUNT * 2) {
                capture.setEnabled(false);
                callback.onCapture(step);
            }
        });
        cancel.setOnClickListener(v -> callback.onCancel());
    }

    public static int classForStep(int step) {
        return step / 2;
    }

    public static boolean darkSquareForStep(int step) {
        return step % 2 == 0;
    }

    public static int targetSquareForStep(int step) {
        return darkSquareForStep(step) ? Square.D4.ordinal() : Square.E4.ordinal();
    }

    public static String instructionForStep(int step) {
        int cls = classForStep(step);
        boolean black = cls >= 6;
        int type = cls % 6;
        String square = darkSquareForStep(step) ? "d4 (ô tối)" : "e4 (ô sáng)";
        return "Mẫu " + (step + 1) + "/24 · quân " + (black ? "Đen" : "Trắng")
                + " " + PIECE_NAMES[type] + " (" + PIECE_SYMBOLS[type] + ")"
                + " · đặt đúng vào ô " + square + ", rồi chụp.";
    }

    public void startSteps() {
        step = 0;
        themeName.setVisibility(GONE);
        begin.setVisibility(GONE);
        capture.setVisibility(VISIBLE);
        updateStep();
    }

    public void setBusy(boolean busy) {
        capture.setEnabled(!busy && step >= 0 && step < PieceRecognizer.CLASS_COUNT * 2);
        status.setText(busy ? "Đang lấy ảnh mẫu…" : "");
    }

    public void sampleCaptured() {
        step++;
        if (step >= PieceRecognizer.CLASS_COUNT * 2) {
            capture.setVisibility(GONE);
            instruction.setText("Đã lấy đủ 24 mẫu. Theme đã được lưu và chọn.");
            status.setText("");
            return;
        }
        updateStep();
    }

    public void showError(String message) {
        status.setText(message);
        status.setTextColor(0xFFFF8A80);
        capture.setEnabled(true);
    }

    public String themeName() {
        return themeName.getText().toString().trim();
    }

    public boolean isComplete() {
        return step >= PieceRecognizer.CLASS_COUNT * 2;
    }

    private void updateStep() {
        instruction.setText(instructionForStep(step));
        status.setText("");
        capture.setEnabled(true);
    }

    private LayoutParams matchWrap() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
