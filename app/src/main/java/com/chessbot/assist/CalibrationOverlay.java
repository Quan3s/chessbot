package com.chessbot.assist;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full screen transparent overlay used to frame the 8x8 board. */
public class CalibrationOverlay extends FrameLayout {

    public interface Callback {
        void onSave(float l, float t, float r, float b, boolean userWhite, boolean startNow);

        void onCancel();
    }

    private final SelectionView selection;

    public CalibrationOverlay(Context context, int screenW, int screenH,
                              float[] initial, boolean defaultUserWhite, Callback callback) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);

        selection = new SelectionView(context, screenW, screenH, initial);
        addView(selection, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        TextView title = new TextView(context);
        title.setText("Hiệu chỉnh bàn cờ\nKéo 4 góc cho khung khớp với mép ngoài bàn cờ 8×8");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(14f);
        title.setLineSpacing(dp(3), 1f);
        title.setPadding(dp(18), dp(30), dp(18), dp(12));
        title.setBackgroundColor(0x99000000);
        title.setClickable(true);
        LayoutParams tlp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP);
        addView(title, tlp);

        // Step 1: frame the board. The colour question comes in step 2, after
        // the corners are set, so it never blocks dragging the bottom corners.
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(12));
        panel.setBackgroundColor(0xCC0E1015);
        panel.setClickable(true);

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);

        Button cancel = new Button(context);
        cancel.setText("Hủy");
        cancel.setTextSize(14f);
        cancel.setAllCaps(false);

        Button save = new Button(context);
        save.setText("Lưu vùng");
        save.setTextSize(14f);
        save.setAllCaps(false);

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp1.setMargins(0, 0, dp(6), 0);
        bar.addView(cancel, lp1);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp2.setMargins(dp(6), 0, 0, 0);
        bar.addView(save, lp2);
        panel.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LayoutParams panelLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        panelLp.bottomMargin = dp(22);
        addView(panel, panelLp);

        // Step 2: the colour question, shown right after "Lưu vùng".
        LinearLayout chooser = new LinearLayout(context);
        chooser.setOrientation(LinearLayout.VERTICAL);
        chooser.setPadding(dp(14), dp(12), dp(14), dp(12));
        chooser.setBackgroundColor(0xEE0E1015);
        chooser.setClickable(true);
        chooser.setVisibility(GONE);

        TextView ask = new TextView(context);
        ask.setText("Bạn cầm quân nào?\nChạm màu quân của bạn — bot bắt đầu luôn.");
        ask.setTextColor(0xFFEDEFF5);
        ask.setTextSize(14f);
        ask.setGravity(Gravity.CENTER);
        ask.setLineSpacing(dp(2), 1f);
        chooser.addView(ask, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout colors = new LinearLayout(context);
        colors.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams colorsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        colorsLp.topMargin = dp(10);
        chooser.addView(colors, colorsLp);

        Button white = colorButton(context, "\u2654  Trắng", 0xFFF2F2F2, 0xFF14161B);
        Button black = colorButton(context, "\u265A  Đen", 0xFF232833, 0xFFEDEFF5);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        wlp.setMargins(0, 0, dp(5), 0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        blp.setMargins(dp(5), 0, 0, 0);
        colors.addView(white, wlp);
        colors.addView(black, blp);

        LinearLayout chooserBar = new LinearLayout(context);
        chooserBar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cbLp.topMargin = dp(8);
        chooser.addView(chooserBar, cbLp);

        Button back = new Button(context);
        back.setText("\u2190  Sửa khung");
        back.setTextSize(13f);
        back.setAllCaps(false);
        Button saveOnly = new Button(context);
        saveOnly.setText("Chỉ lưu vùng");
        saveOnly.setTextSize(13f);
        saveOnly.setAllCaps(false);
        LinearLayout.LayoutParams blp1 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        blp1.setMargins(0, 0, dp(6), 0);
        chooserBar.addView(back, blp1);
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        blp2.setMargins(dp(6), 0, 0, 0);
        chooserBar.addView(saveOnly, blp2);

        LayoutParams chooserLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        chooserLp.bottomMargin = dp(22);
        addView(chooser, chooserLp);

        cancel.setOnClickListener(v -> callback.onCancel());
        save.setOnClickListener(v -> {
            panel.setVisibility(GONE);
            chooser.setVisibility(VISIBLE);
        });
        back.setOnClickListener(v -> {
            chooser.setVisibility(GONE);
            panel.setVisibility(VISIBLE);
        });
        white.setOnClickListener(v -> {
            float[] n = selection.normalized();
            callback.onSave(n[0], n[1], n[2], n[3], true, true);
        });
        black.setOnClickListener(v -> {
            float[] n = selection.normalized();
            callback.onSave(n[0], n[1], n[2], n[3], false, true);
        });
        saveOnly.setOnClickListener(v -> {
            float[] n = selection.normalized();
            callback.onSave(n[0], n[1], n[2], n[3], defaultUserWhite, false);
        });
    }

    private Button colorButton(Context context, String text, int background, int foreground) {
        Button b = new Button(context);
        b.setText(text);
        b.setTextSize(16f);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setPadding(0, 0, 0, 0);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(background));
        b.setTextColor(foreground);
        return b;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static final class SelectionView extends View {

        private static final int NONE = 0;
        private static final int MOVE = 1;
        private static final int TL = 2;
        private static final int TR = 3;
        private static final int BL = 4;
        private static final int BR = 5;
        private static final int NEW = 6;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int screenW;
        private final int screenH;
        private final RectF rect = new RectF();
        private final float minSize;

        private int mode = NONE;
        private float downX, downY;
        private float anchorX, anchorY;
        private RectF startRect = new RectF();

        SelectionView(Context context, int screenW, int screenH, float[] initial) {
            super(context);
            this.screenW = screenW;
            this.screenH = screenH;
            this.minSize = dp(100);
            if (initial != null) {
                rect.set(initial[0] * screenW, initial[1] * screenH,
                        initial[2] * screenW, initial[3] * screenH);
            } else {
                float side = Math.min(screenW * 0.92f, screenH * 0.62f);
                float left = (screenW - side) / 2f;
                float top = (screenH - side) / 2f;
                rect.set(left, top, left + side, top + side);
            }
            clampRect();
        }

        float[] normalized() {
            return new float[]{
                    rect.left / screenW,
                    rect.top / screenH,
                    rect.right / screenW,
                    rect.bottom / screenH
            };
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            // dim everything outside the selection
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xA6000000);
            canvas.drawRect(0, 0, screenW, rect.top, paint);
            canvas.drawRect(0, rect.bottom, screenW, screenH, paint);
            canvas.drawRect(0, rect.top, rect.left, rect.bottom, paint);
            canvas.drawRect(rect.right, rect.top, screenW, rect.bottom, paint);

            // 8x8 grid
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0x66FFFFFF);
            float cw = rect.width() / 8f;
            float ch = rect.height() / 8f;
            for (int i = 1; i < 8; i++) {
                canvas.drawLine(rect.left + i * cw, rect.top, rect.left + i * cw, rect.bottom, paint);
                canvas.drawLine(rect.left, rect.top + i * ch, rect.right, rect.top + i * ch, paint);
            }

            // border
            paint.setStrokeWidth(dp(2));
            paint.setColor(0xFF6FCF6F);
            canvas.drawRect(rect, paint);

            // corner handles
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFFFFF);
            float h = dp(9);
            canvas.drawCircle(rect.left, rect.top, h, paint);
            canvas.drawCircle(rect.right, rect.top, h, paint);
            canvas.drawCircle(rect.left, rect.bottom, h, paint);
            canvas.drawCircle(rect.right, rect.bottom, h, paint);

            // size label
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFFFFF);
            paint.setTextSize(dp(12));
            paint.setShadowLayer(dp(3), 0, dp(1), 0xFF000000);
            canvas.drawText("8 × 8", rect.left + dp(6), rect.top - dp(8), paint);
            paint.clearShadowLayer();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    startRect.set(rect);
                    mode = hitTest(downX, downY);
                    if (mode == NEW) {
                        anchorX = downX;
                        anchorY = downY;
                        rect.set(downX, downY, downX + 1, downY + 1);
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (mode == MOVE) {
                        float dx = e.getX() - downX;
                        float dy = e.getY() - downY;
                        rect.set(startRect.left + dx, startRect.top + dy,
                                startRect.right + dx, startRect.bottom + dy);
                    } else if (mode == NEW) {
                        rect.set(Math.min(anchorX, e.getX()), Math.min(anchorY, e.getY()),
                                Math.max(anchorX, e.getX()), Math.max(anchorY, e.getY()));
                    } else if (mode != NONE) {
                        float x = e.getX();
                        float y = e.getY();
                        if (mode == TL) {
                            rect.set(Math.min(x, rect.right - minSize), Math.min(y, rect.bottom - minSize),
                                    rect.right, rect.bottom);
                        } else if (mode == TR) {
                            rect.set(rect.left, Math.min(y, rect.bottom - minSize),
                                    Math.max(x, rect.left + minSize), rect.bottom);
                        } else if (mode == BL) {
                            rect.set(Math.min(x, rect.right - minSize), rect.top,
                                    rect.right, Math.max(y, rect.top + minSize));
                        } else if (mode == BR) {
                            rect.set(rect.left, rect.top,
                                    Math.max(x, rect.left + minSize), Math.max(y, rect.top + minSize));
                        }
                    }
                    clampRect();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mode = NONE;
                    if (rect.width() < minSize || rect.height() < minSize) {
                        rect.set(startRect);
                    }
                    clampRect();
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        private int hitTest(float x, float y) {
            float t = dp(48);
            if (dist(x, y, rect.left, rect.top) < t) return TL;
            if (dist(x, y, rect.right, rect.top) < t) return TR;
            if (dist(x, y, rect.left, rect.bottom) < t) return BL;
            if (dist(x, y, rect.right, rect.bottom) < t) return BR;
            if (rect.contains(x, y)) return MOVE;
            return NEW;
        }

        private void clampRect() {
            float w = Math.max(minSize, Math.min(rect.width(), screenW));
            float h = Math.max(minSize, Math.min(rect.height(), screenH));
            if (rect.left < 0) {
                rect.left = 0;
                rect.right = w;
            }
            if (rect.top < 0) {
                rect.top = 0;
                rect.bottom = h;
            }
            if (rect.right > screenW) {
                rect.right = screenW;
                rect.left = screenW - w;
            }
            if (rect.bottom > screenH) {
                rect.bottom = screenH;
                rect.top = screenH - h;
            }
        }

        private static float dist(float x1, float y1, float x2, float y2) {
            float dx = x1 - x2;
            float dy = y1 - y2;
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }
    }
}
