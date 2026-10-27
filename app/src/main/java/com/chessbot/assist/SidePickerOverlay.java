package com.chessbot.assist;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen overlay shown right before the bot starts, asking which colour
 * the user is playing. The choice is saved as the new default.
 */
public class SidePickerOverlay extends FrameLayout {

    public interface Callback {
        /** @param userWhite true when the human plays White. */
        void onPick(boolean userWhite);

        void onCancel();
    }

    public SidePickerOverlay(Context context, boolean defaultUserWhite, Callback callback) {
        super(context);
        setBackgroundColor(0xB8000000);
        setClickable(true);
        setOnClickListener(v -> callback.onCancel());

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(10));
        card.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF191C24);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), 0x33FFFFFF);
        card.setBackground(bg);

        TextView title = new TextView(context);
        title.setText("Bạn cầm quân nào?");
        title.setTextColor(0xFFEDEFF5);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        card.addView(title, matchWrap());

        TextView sub = new TextView(context);
        sub.setText("Chọn đúng màu quân của bạn trong app cờ vua. "
                + "Bot sẽ đi quân bên đó cho bạn. Chọn sai màu thì bot sẽ đi quân đối thủ.");
        sub.setTextColor(0xFF9AA1B2);
        sub.setTextSize(12.5f);
        sub.setGravity(Gravity.CENTER);
        sub.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams slp = matchWrap();
        slp.topMargin = dp(6);
        card.addView(sub, slp);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(16);
        card.addView(row, rlp);

        Button white = makeButton(context, "\u2654  Trắng", 0xFF6FCF6F, 0xFF08120A);
        Button black = makeButton(context, "\u265A  Đen", 0xFF2A2F3A, 0xFFEDEFF5);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        wlp.setMargins(0, 0, dp(5), 0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        blp.setMargins(dp(5), 0, 0, 0);
        row.addView(white, wlp);
        row.addView(black, blp);

        TextView hint = new TextView(context);
        hint.setText(defaultUserWhite ? "Lần trước bạn cầm: Trắng" : "Lần trước bạn cầm: Đen");
        hint.setTextColor(0xFF6F788A);
        hint.setTextSize(11);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = matchWrap();
        hlp.topMargin = dp(10);
        card.addView(hint, hlp);

        TextView cancel = new TextView(context);
        cancel.setText("Hủy");
        cancel.setTextColor(0xFF9AA1B2);
        cancel.setTextSize(14);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, dp(11), 0, dp(7));
        card.addView(cancel, matchWrap());

        white.setOnClickListener(v -> callback.onPick(true));
        black.setOnClickListener(v -> callback.onPick(false));
        cancel.setOnClickListener(v -> callback.onCancel());

        int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(40), dp(340));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                width, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        addView(card, lp);
    }

    private Button makeButton(Context context, String text, int background, int foreground) {
        Button b = new Button(context);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(foreground);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackgroundTintList(ColorStateList.valueOf(background));
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
