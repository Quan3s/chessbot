package com.chessbot.assist;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Actions opened by holding the floating button. */
public final class ToolMenuOverlay extends FrameLayout {

    public interface Callback {
        void onCalibrate();
        void onTrain();
        void onSelectTheme(String themeId);
        void onClose();
    }

    public ToolMenuOverlay(Context context, List<PieceThemeStore.Theme> themes,
                           String selectedId, Callback callback) {
        super(context);
        setBackgroundColor(0x99000000);
        setClickable(true);
        setOnClickListener(v -> callback.onClose());

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(14));
        card.setBackground(roundBackground(0xFF191C24, 0x33FFFFFF));
        card.setClickable(true);

        TextView title = new TextView(context);
        title.setText("ChessBot");
        title.setTextColor(0xFFEDEFF5);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        card.addView(title, matchWrap());

        Button calibrate = makeButton(context, "Căn chỉnh bàn cờ");
        Button train = makeButton(context, "Train bộ template");
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        buttonLp.topMargin = dp(10);
        card.addView(calibrate, buttonLp);
        LinearLayout.LayoutParams trainLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        trainLp.topMargin = dp(7);
        card.addView(train, trainLp);

        TextView listTitle = new TextView(context);
        listTitle.setText("Bộ template đã lưu");
        listTitle.setTextColor(0xFFEDEFF5);
        listTitle.setTextSize(14);
        listTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams listTitleLp = matchWrap();
        listTitleLp.topMargin = dp(12);
        card.addView(listTitle, listTitleLp);

        ScrollView scroll = new ScrollView(context);
        LinearLayout themeList = new LinearLayout(context);
        themeList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(themeList, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180));
        card.addView(scroll, listLp);
        if (themes == null || themes.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("Chưa có bộ template. Bấm Train để tạo.");
            empty.setTextColor(0xFF9AA1B2);
            empty.setTextSize(13);
            empty.setPadding(dp(4), dp(10), dp(4), dp(10));
            themeList.addView(empty, matchWrap());
        } else {
            for (PieceThemeStore.Theme theme : themes) {
                Button item = makeButton(context,
                        (theme.id.equals(selectedId) ? "✓  " : "") + theme.name);
                item.setTextSize(13);
                LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
                itemLp.topMargin = dp(3);
                themeList.addView(item, itemLp);
                item.setOnClickListener(v -> callback.onSelectTheme(theme.id));
            }
        }

        TextView close = new TextView(context);
        close.setText("Đóng");
        close.setTextColor(0xFF9AA1B2);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, dp(12), 0, dp(2));
        card.addView(close, matchWrap());

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels - dp(40), dp(340)),
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        addView(card, cardLp);
        calibrate.setOnClickListener(v -> callback.onCalibrate());
        train.setOnClickListener(v -> callback.onTrain());
        close.setOnClickListener(v -> callback.onClose());
    }

    private Button makeButton(Context context, String text) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(Color.WHITE);
        return button;
    }

    private GradientDrawable roundBackground(int color, int stroke) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), stroke);
        return bg;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
