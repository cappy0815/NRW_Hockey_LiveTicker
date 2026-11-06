package de.ehvnrw.liveticker;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Update-Pruefung wie bei Soundboard und Player:
 *  1. Versionsdatei auf der Projektseite  https://cappy0815.github.io/downloads/ehv-live.json
 *     {"version":"2.6","page":"…","download":"…apk","sha256":"…"}
 *  2. Rueckfall: neuestes GitHub-Release von cappy0815/NRW_Hockey_LiveTicker (Tag z. B. "WIN_v3.0.1_APK_v2.51")
 * Es wird nichts automatisch installiert – die App zeigt nur an und oeffnet den Download im Browser.
 * Gesendet wird nur eine normale Abfrage, keine Daten aus der App.
 */
final class Update {
    static final String VERSION_URL = "https://cappy0815.github.io/downloads/ehv-live.json";
    static final String RELEASE_API = "https://api.github.com/repos/cappy0815/NRW_Hockey_LiveTicker/releases/latest";
    static final String PROJECT_PAGE = "https://cappy0815.github.io/#liveticker";
    private static final long DAY_MS = 24L * 3600 * 1000;

    interface Done {
        void done(boolean ok);
    }

    private static volatile boolean busy = false;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private Update() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("update", Context.MODE_PRIVATE);
    }

    static boolean isBusy() {
        return busy;
    }

    static boolean auto(Context c) {
        return sp(c).getBoolean("auto", true);
    }

    static void setAuto(Context c, boolean on) {
        sp(c).edit().putBoolean("auto", on).apply();
    }

    static String latest(Context c) {
        return sp(c).getString("latest", "");
    }

    static String installed(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    static boolean available(Context c) {
        String l = latest(c);
        return !l.isEmpty() && newer(l, installed(c));
    }

    /** automatische Pruefung faellig: eingeschaltet und letzte Pruefung aelter als 24 h */
    static boolean due(Context c) {
        return auto(c) && System.currentTimeMillis() - sp(c).getLong("last", 0) > DAY_MS;
    }

    static String lastText(Context c) {
        long t = sp(c).getLong("last", 0);
        return t == 0 ? "noch nie" : new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(new Date(t));
    }

    static void check(Context ctx, final Done done) {
        if (busy) return;
        busy = true;
        final Context c = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String[] r = fromVersionFile();
                if (r == null) r = fromRelease();
                SharedPreferences.Editor e = sp(c).edit().putLong("last", System.currentTimeMillis());
                if (r != null) e.putString("latest", r[0]).putString("download", r[1]).putString("page", r[2]);
                e.apply();
                busy = false;
                final boolean ok = r != null;
                UI.post(new Runnable() {
                    @Override
                    public void run() {
                        if (done != null) done.done(ok);
                    }
                });
            }
        }).start();
    }

    private static String[] fromVersionFile() {
        try {
            JSONObject o = new JSONObject(get(VERSION_URL));
            String v = strip(o.optString("version"));
            if (v.isEmpty()) return null;
            return new String[]{v, o.optString("download", ""), o.optString("page", PROJECT_PAGE)};
        } catch (Exception e) {
            return null;
        }
    }

    private static final Pattern APK_TAG = Pattern.compile("APK_v?([0-9][0-9.]*)");

    private static String[] fromRelease() {
        try {
            JSONObject o = new JSONObject(get(RELEASE_API));
            String tag = o.optString("tag_name");
            Matcher m = APK_TAG.matcher(tag);
            String v = m.find() ? m.group(1) : strip(tag);
            if (v.isEmpty() || !Character.isDigit(v.charAt(0))) return null;
            String dl = "";
            JSONArray as = o.optJSONArray("assets");
            if (as != null) for (int i = 0; i < as.length(); i++) {
                JSONObject a = as.optJSONObject(i);
                if (a != null && a.optString("name").toLowerCase(Locale.ROOT).endsWith(".apk")) dl = a.optString("browser_download_url");
            }
            return new String[]{v, dl, o.optString("html_url", PROJECT_PAGE)};
        } catch (Exception e) {
            return null;
        }
    }

    private static String strip(String v) {
        v = v == null ? "" : v.trim();
        while (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        return v;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "EHV-Live-Update");
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && bo.size() < 1_000_000) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    /**
     * true, wenn a neuer ist als b. EHV Live zaehlt dezimal (2.1 < 2.11 < 2.2 < 2.5 < 2.51 < 2.6),
     * deshalb wird "Haupt.Neben" als Dezimalzahl verglichen, weitere Stellen ganzzahlig.
     */
    static boolean newer(String a, String b) {
        String[] pa = strip(a).split("\\."), pb = strip(b).split("\\.");
        java.math.BigDecimal da = dec(pa), db = dec(pb);
        int c = da.compareTo(db);
        if (c != 0) return c > 0;
        for (int i = 2; i < Math.max(pa.length, pb.length); i++) {
            int x = i < pa.length ? num(pa[i]) : 0, y = i < pb.length ? num(pb[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static java.math.BigDecimal dec(String[] p) {
        try {
            String s = num(p[0]) + (p.length > 1 ? "." + p[1].replaceAll("[^0-9]", "") : "");
            return new java.math.BigDecimal(s.endsWith(".") ? s + "0" : s);
        } catch (Exception e) {
            return java.math.BigDecimal.ZERO;
        }
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    static void open(Context c, String url) {
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    static void openDownload(Context c) {
        String d = sp(c).getString("download", "");
        open(c, d.isEmpty() ? sp(c).getString("page", PROJECT_PAGE) : d);
    }

    static void openPage(Context c) {
        open(c, sp(c).getString("page", PROJECT_PAGE));
    }
}
