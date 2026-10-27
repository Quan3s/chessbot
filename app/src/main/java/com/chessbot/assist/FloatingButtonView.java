package com.chessbot.assist;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** The draggable overlay button: triangle = start, two bars = stop. */
public class FloatingButtonView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path triangle = new Path();
    private final RectF bar = new RectF();
    private boolean running;

    public FloatingButtonView(Context context) {
        super(context);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setRunning(boolean running) {
        if (this.running != running) {
            this.running = running;
            invalidate();
        }
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) / 2f - dp(2f);

        // body
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xE614171D);
        canvas.drawCircle(cx, cy, r, paint);

        // ring
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.6f));
        paint.setColor(running ? 0xFF6FCF6F : 0x80FFFFFF);
        canvas.drawCircle(cx, cy, r - dp(1.2f), paint);

        // icon
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        if (!running) {
            float s = r * 0.44f;
            triangle.reset();
            triangle.moveTo(cx - s * 0.55f, cy - s);
            triangle.lineTo(cx + s * 0.85f, cy);
            triangle.lineTo(cx - s * 0.55f, cy + s);
            triangle.close();
            canvas.drawPath(triangle, paint);
        } else {
            float bw = r * 0.21f;
            float bh = r * 0.52f;
            float gap = r * 0.15f;
            bar.set(cx - gap / 2f - bw, cy - bh, cx - gap / 2f, cy + bh);
            canvas.drawRoundRect(bar, dp(2f), dp(2f), paint);
            bar.set(cx + gap / 2f, cy - bh, cx + gap / 2f + bw, cy + bh);
            canvas.drawRoundRect(bar, dp(2f), dp(2f), paint);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
