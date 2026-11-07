package de.ehvnrw.liveticker;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/** v2.9: Umriss-Stern mit Zahl in der Mitte (Topscorer-Rang in der Aufstellung) */
class StarDrawable extends Drawable {
    private final String label;
    private final int color, size;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);

    StarDrawable(String label, int color, int sizePx) {
        this.label = label;
        this.color = color;
        this.size = sizePx;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeWidth(Math.max(2f, sizePx * 0.07f));
        line.setColor(color);
        txt.setColor(color);
        txt.setTypeface(Typeface.DEFAULT_BOLD);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTextSize(sizePx * 0.40f); // v2.91: Zahl größer
        setBounds(0, 0, sizePx, sizePx);
    }

    @Override
    public void draw(Canvas c) {
        float w = getBounds().width(), h = getBounds().height();
        float cx = getBounds().left + w / 2f, cy = getBounds().top + h * 0.54f;
        float ro = Math.min(w, h) * 0.47f, ri = ro * 0.6f; // v2.91: dickerer Stern, mehr Platz für die Zahl
        Path p = new Path();
        for (int i = 0; i < 10; i++) {
            double ang = Math.PI / 2 + i * Math.PI / 5; // Spitze nach oben
            float r = (i % 2 == 0) ? ro : ri;
            float x = cx + (float) (r * Math.cos(ang)), y = cy - (float) (r * Math.sin(ang));
            if (i == 0) p.moveTo(x, y);
            else p.lineTo(x, y);
        }
        p.close();
        c.drawPath(p, line);
        Paint.FontMetrics fm = txt.getFontMetrics();
        c.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f + h * 0.03f, txt);
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    @Override
    public void setAlpha(int a) {
        line.setAlpha(a);
        txt.setAlpha(a);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        line.setColorFilter(cf);
        txt.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
