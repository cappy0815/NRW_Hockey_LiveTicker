package de.ehvnrw.liveticker;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.widget.Button;

/** Einheitliche "Pillen"-Knoepfe in den EHV-Farben statt der grauen Standard-Knoepfe. */
final class Ui {
    static final int RED = Color.rgb(200, 16, 46);     // Rot aus dem EHV-Logo
    static final int GREEN = Color.rgb(0, 140, 69);    // Gruen aus dem EHV-Logo
    static final int DARK = Color.rgb(52, 58, 66);

    private Ui() {
    }

    private static int dp(Button b, float v) {
        return Math.round(v * b.getResources().getDisplayMetrics().density);
    }

    private static int darker(int c) {
        return Color.rgb(Color.red(c) * 3 / 4, Color.green(c) * 3 / 4, Color.blue(c) * 3 / 4);
    }

    /** gefuellte Pille mit leichtem Verlauf, weisser fetter Schrift und Ripple */
    static void pill(Button b, int color) {
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{color, darker(color)});
        shape.setCornerRadius(dp(b, 24));
        style(b, shape, Color.WHITE);
        b.setElevation(dp(b, 3));
    }

    /** umrandete Pille (fuer Nebenaktionen wie "Abbrechen") */
    static void outline(Button b, int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.TRANSPARENT);
        shape.setStroke(dp(b, 1.5f), color);
        shape.setCornerRadius(dp(b, 24));
        style(b, shape, color);
        b.setElevation(0);
    }

    private static void style(Button b, GradientDrawable shape, int textColor) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(b, 24));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), shape, mask));
        b.setTextColor(textColor);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(b, 18), dp(b, 9), dp(b, 18), dp(b, 9));
    }

    /** Knoepfe eines bereits angezeigten Dialogs: Hauptaktion gefuellt, Rest umrandet */
    static void dialog(AlertDialog d) {
        Button p = d.getButton(AlertDialog.BUTTON_POSITIVE);
        Button n = d.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button u = d.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (p != null) pill(p, GREEN);
        if (n != null) outline(n, DARK);
        if (u != null) outline(u, DARK);
        // Knopfleiste mittig: Abstandhalter ausblenden, Inhalt zentrieren
        Button any = p != null ? p : (n != null ? n : u);
        if (any != null && any.getParent() instanceof android.widget.LinearLayout) {
            android.widget.LinearLayout bar = (android.widget.LinearLayout) any.getParent();
            bar.setGravity(android.view.Gravity.CENTER);
            for (int i = 0; i < bar.getChildCount(); i++) {
                android.view.View c = bar.getChildAt(i);
                if (!(c instanceof Button)) c.setVisibility(android.view.View.GONE);
            }
        }
        for (Button b : new Button[]{p, n, u}) {
            if (b == null || !(b.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams)) continue;
            android.view.ViewGroup.MarginLayoutParams lp = (android.view.ViewGroup.MarginLayoutParams) b.getLayoutParams();
            lp.leftMargin = dp(b, 4);
            lp.rightMargin = dp(b, 4);
            b.setLayoutParams(lp);
        }
    }
}
