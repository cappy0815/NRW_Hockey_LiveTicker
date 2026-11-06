package de.ehvnrw.liveticker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** Team-Logo; solange (oder falls) kein Logo da ist, wird das Teamkuerzel angezeigt. */
public class TeamBadge extends FrameLayout {
    private final ImageView img;
    private final TextView txt;

    public TeamBadge(Context ctx, int sizePx, boolean dark) {
        super(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(dark ? Color.argb(255, 245, 245, 245) : Color.argb(255, 236, 239, 243));
        setBackground(bg);

        img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = Math.round(sizePx * 0.08f);
        img.setPadding(pad, pad, pad, pad);
        addView(img, new LayoutParams(sizePx, sizePx));

        txt = new TextView(ctx);
        txt.setGravity(Gravity.CENTER);
        txt.setTextColor(Color.rgb(40, 40, 40));
        txt.setTypeface(Typeface.DEFAULT_BOLD);
        txt.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx * 0.26f);
        txt.setMaxLines(1);
        addView(txt, new LayoutParams(sizePx, sizePx));
    }

    public void setTeam(String shortName, Bitmap logo) {
        if (logo != null) {
            img.setImageBitmap(logo);
            img.setVisibility(VISIBLE);
            txt.setVisibility(GONE);
        } else {
            img.setImageDrawable(null);
            img.setVisibility(GONE);
            txt.setText(shortName == null ? "" : shortName);
            txt.setVisibility(VISIBLE);
        }
    }
}
