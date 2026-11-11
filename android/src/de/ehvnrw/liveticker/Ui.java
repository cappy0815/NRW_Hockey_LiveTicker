package de.ehvnrw.liveticker;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

/**
 * Gemeinsame Bausteine aller Fenster: Farben, dp, Texte, Abschnitte, Schalter, Farbstreifen,
 * „weiter scrollen“-Hinweis und die „Pillen“-Knöpfe in den EHV-Farben.
 * v2.95: vorher in 7–9 Dateien einzeln kopiert.
 */
final class Ui {
    static final int RED = Color.rgb(200, 16, 46);     // Rot aus dem EHV-Logo
    static final int GREEN = Color.rgb(0, 140, 69);    // Gruen aus dem EHV-Logo
    static final int DARK = Color.rgb(52, 58, 66);
    static final int TEXT = Color.rgb(34, 38, 43);
    static final int MUTED = Color.rgb(110, 116, 124);
    static final int LINE = Color.rgb(225, 228, 232);
    static final int FAV = Color.rgb(255, 246, 214);    // gelbe Hervorhebung

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static int dp(Button b, float v) {
        return dp(b.getContext(), v);
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static View colorBlock(Context c, int color) {
        View v = new View(c);
        v.setBackgroundColor(color);
        return v;
    }

    /** Streifen Grün · Grau · Rot unter Fenstertiteln (Hilfe, Optionen, Aufstellung) */
    static void stripe(Context c, LinearLayout box, int top) {
        LinearLayout st = new LinearLayout(c);
        st.addView(colorBlock(c, GREEN), new LinearLayout.LayoutParams(0, -1, 1f));
        st.addView(colorBlock(c, LINE), new LinearLayout.LayoutParams(0, -1, 1f));
        st.addView(colorBlock(c, RED), new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(c, 3));
        lp.topMargin = dp(c, top);
        lp.bottomMargin = dp(c, 4);
        box.addView(st, lp);
    }

    /** rote Abschnitts-Überschrift in Großbuchstaben */
    static void section(Context c, LinearLayout box, String title) {
        TextView h = text(c, title.toUpperCase(Locale.GERMANY), 11, RED, true);
        h.setLetterSpacing(0.08f);
        h.setPadding(0, dp(c, 14), 0, dp(c, 4));
        box.addView(h);
    }

    /** Zeile „Symbol | Text“ (Hilfe, Changelog) */
    static void item(Context c, LinearLayout box, String symbol, String body) {
        LinearLayout row = new LinearLayout(c);
        row.setPadding(0, dp(c, 3), 0, dp(c, 3));
        TextView sym = text(c, symbol, 12, TEXT, true);
        sym.setGravity(Gravity.CENTER_HORIZONTAL);
        row.addView(sym, new LinearLayout.LayoutParams(dp(c, 30), -2));
        row.addView(text(c, body, 13, TEXT, false), new LinearLayout.LayoutParams(0, -2, 1f));
        box.addView(row);
    }

    static Switch toggle(Context c, String label, boolean on) {
        Switch sw = new Switch(c);
        sw.setText(label);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sw.setTextColor(TEXT);
        sw.setChecked(on);
        sw.setPadding(0, dp(c, 6), 0, dp(c, 6));
        return sw;
    }

    /** ScrollView mit „▼ weiter scrollen“-Hinweis unten, solange es weiter nach unten geht */
    static FrameLayout withScrollHint(final ScrollView sv) {
        Context c = sv.getContext();
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(dp(c, 48));
        sv.setScrollbarFadingEnabled(false);
        FrameLayout frame = new FrameLayout(c);
        frame.addView(sv, new FrameLayout.LayoutParams(-1, -2));
        final TextView more = text(c, "\u25BC  weiter scrollen", 11, Color.WHITE, true);
        more.setPadding(dp(c, 12), dp(c, 4), dp(c, 12), dp(c, 4));
        GradientDrawable moreBg = new GradientDrawable();
        moreBg.setColor(Color.argb(215, 34, 38, 43));
        moreBg.setCornerRadius(dp(c, 12));
        more.setBackground(moreBg);
        FrameLayout.LayoutParams moreLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        moreLp.bottomMargin = dp(c, 6);
        frame.addView(more, moreLp);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sv.smoothScrollBy(0, sv.getHeight() * 2 / 3);
            }
        });
        final Runnable updateMore = new Runnable() {
            @Override
            public void run() {
                more.setVisibility(sv.canScrollVertically(1) ? View.VISIBLE : View.GONE);
            }
        };
        sv.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int x, int y, int ox, int oy) {
                updateMore.run();
            }
        });
        sv.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                updateMore.run();
            }
        });
        return frame;
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
        for (Button b : new Button[]{p, n, u}) if (b != null) b.setSingleLine(true); // v2.82: nicht umbrechen
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
