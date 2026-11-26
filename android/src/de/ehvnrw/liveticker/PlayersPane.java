package de.ehvnrw.liveticker;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v2.97: Reiter „Spieler“ – Spielersuche nach Nach- und/oder Vorname, Filter Altersklasse.
 * Daten: LeaderFieldPlayers + LeaderGoalkeepers je Liga (dieselben Abfragen wie die Statistik-Seiten der Tabelle,
 * 30 Min. im Speicher). Darin steht jeder Spieler, der in der Liga schon eingesetzt wurde – auch ohne Punkte.
 * Alle 28 Ligen zusammen ≈ 3,4 MB (gemessen 25.11.2026), deshalb erst beim Suchen und nur für die gewählte Altersklasse.
 */
class PlayersPane extends LinearLayout {
    private static final int MAX_RESULTS = 100;

    /** Liga aus dem Saisonplan */
    private static final class Div {
        String id, group, title;
        int rank;
    }

    /** ein Eintrag eines Spielers in einer Liga */
    private static final class Entry {
        Div div;
        JSONObject row;
        boolean goalie;
    }

    private static final class Player {
        String key, last, first, sortKey;
        int score;                 // 0 = Nachname passt genau, 1 = sonst
        boolean fav;
        final List<Entry> entries = new ArrayList<>();
    }

    private final Activity a;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final EditText input;
    private final Spinner spClass;
    private final TextView status;
    private final LinearLayout results;
    private final List<Div> divs = new ArrayList<>();
    private final List<String> classes = new ArrayList<>();       // "" = alle
    private final Map<String, String> teamNames = new HashMap<>(); // divisionId|Kürzel -> Name
    private List<Game> season = new ArrayList<>();
    private boolean loaded, loading, searching;
    private int searchSeq;
    private final Runnable debounced = new Runnable() {
        @Override
        public void run() {
            search();
        }
    };

    PlayersPane(Activity act) {
        super(act);
        a = act;
        setOrientation(VERTICAL);
        setPadding(dp(10), dp(8), dp(10), 0);

        input = new EditText(a);
        input.setHint("Name, z. B. Müller oder Müller Tim");
        input.setSingleLine(true);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                hideKeyboard();
                ui.removeCallbacks(debounced);
                search();
                return true;
            }
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                ui.removeCallbacks(debounced);
                ui.postDelayed(debounced, 500);
            }
        });
        addView(input, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.text(a, "Altersklasse", 13, Ui.TEXT, false), new LinearLayout.LayoutParams(-2, -2));
        spClass = new Spinner(a);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, -2, 1f);
        sl.leftMargin = dp(8);
        row.addView(spClass, sl);
        addView(row);

        status = Ui.text(a, "", 12, Ui.MUTED, false);
        status.setPadding(dp(4), dp(4), dp(4), dp(6));
        addView(status);

        results = new LinearLayout(a);
        results.setOrientation(VERTICAL);
        ScrollView sv = new ScrollView(a);
        sv.addView(results);
        addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    void shown() {
        if (!loaded) loadSeason();
    }

    private void loadSeason() {
        if (loading) return;
        loading = true;
        status.setText("Lade Ligen …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Game> s = Api.season(false);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        season = s;
                        loaded = !s.isEmpty();
                        buildDivs();
                    }
                });
            }
        }).start();
    }

    private void buildDivs() {
        divs.clear();
        teamNames.clear();
        Map<String, Div> map = new LinkedHashMap<>();
        for (Game g : season) {
            if (g.divisionId.isEmpty()) continue;
            teamNames.put(g.divisionId + "|" + g.homeShort, g.homeName);
            teamNames.put(g.divisionId + "|" + g.awayShort, g.awayName);
            if (map.containsKey(g.divisionId)) continue;
            Div d = new Div();
            d.id = g.divisionId;
            d.group = TablesPane.group(g);
            d.title = TablesPane.title(g, d.group);
            d.rank = TablesPane.groupRank(d.group) * 10 + TablesPane.levelRank(d.title);
            map.put(d.id, d);
        }
        divs.addAll(map.values());
        Collections.sort(divs, new Comparator<Div>() {
            @Override
            public int compare(Div x, Div y) {
                return x.rank != y.rank ? (x.rank < y.rank ? -1 : 1) : x.title.compareToIgnoreCase(y.title);
            }
        });
        classes.clear();
        classes.add("");
        for (Div d : divs) if (!classes.contains(d.group)) classes.add(d.group);
        List<String> names = new ArrayList<>();
        for (String c : classes) names.add(c.isEmpty() ? "Alle (" + divs.size() + " Ligen)" : c + " (" + count(c) + ")");
        ArrayAdapter<String> ad = new ArrayAdapter<>(a, android.R.layout.simple_spinner_item, names);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spClass.setAdapter(ad);
        int sel = Math.max(0, classes.indexOf(Prefs.sp(a).getString("playerClass", "")));
        spClass.setSelection(sel);
        spClass.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                Prefs.sp(a).edit().putString("playerClass", classes.get(pos)).apply();
                search();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        if (divs.isEmpty()) status.setText("Keine Ligen – Verbindung prüfen und den Reiter neu öffnen.");
        else hint();
    }

    private int count(String group) {
        int n = 0;
        for (Div d : divs) if (d.group.equals(group)) n++;
        return n;
    }

    private void hint() {
        status.setText("Mindestens 2 Buchstaben. Gesucht wird in allen Spielern, die in der Saison schon eingesetzt wurden. "
                + "Beim ersten Suchen werden die Ligen geladen (alle zusammen ca. 3,5 MB), danach 30 Min. gespeichert.");
        results.removeAllViews();
    }

    private List<Div> selectedDivs() {
        int pos = spClass.getSelectedItemPosition();
        String c = pos >= 0 && pos < classes.size() ? classes.get(pos) : "";
        List<Div> out = new ArrayList<>();
        for (Div d : divs) if (c.isEmpty() || d.group.equals(c)) out.add(d);
        return out;
    }

    private void search() {
        if (!loaded) {
            loadSeason();
            return;
        }
        final String q = input.getText().toString().trim();
        final List<String> tokens = tokens(q);
        if (tokens.isEmpty() || q.replaceAll("[^\\p{L}]", "").length() < 2) {
            hint();
            return;
        }
        final List<Div> sel = selectedDivs();
        final int seq = ++searchSeq;
        searching = true;
        status.setText("Suche …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final AtomicInteger done = new AtomicInteger(), failed = new AtomicInteger();
                final Map<String, List<JSONObject>[]> data = Collections.synchronizedMap(new HashMap<String, List<JSONObject>[]>());
                ExecutorService ex = Executors.newFixedThreadPool(4);
                for (final Div d : sel) {
                    ex.execute(new Runnable() {
                        @Override
                        @SuppressWarnings("unchecked")
                        public void run() {
                            try {
                                List<JSONObject> f = Api.leaders(d.id, "FieldPlayers");
                                List<JSONObject> g = Api.leaders(d.id, "Goalkeepers");
                                data.put(d.id, new List[]{f, g});
                            } catch (Exception e) {
                                failed.incrementAndGet();
                            }
                            final int n = done.incrementAndGet();
                            if (seq == searchSeq) ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (seq == searchSeq && n < sel.size())
                                        status.setText("Lade Ligen " + n + "/" + sel.size() + " …");
                                }
                            });
                        }
                    });
                }
                ex.shutdown();
                try {
                    ex.awaitTermination(2, TimeUnit.MINUTES);
                } catch (InterruptedException ignored) {
                }
                final List<Player> found = match(tokens, sel, data);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (seq != searchSeq) return;
                        searching = false;
                        show(found, sel.size(), failed.get());
                    }
                });
            }
        }).start();
    }

    /** jedes Suchwort muss ein Namensteil (Nach- oder Vorname) einleiten; Umlaute: „Müller“ = „Mueller“ = „Muller“ */
    private List<Player> match(List<String> tokens, List<Div> sel, Map<String, List<JSONObject>[]> data) {
        Map<String, Player> map = new LinkedHashMap<>();
        // Die API vergibt manchen Spielern je Liga eine andere ID (am 25.11.2026: 42 Fälle, z. B. Torhüter
        // „SCHWARTE, Linus“ RIA in zwei Ligen) → zusätzlich über gleichen Namen + gleiches Teamkürzel zusammenführen.
        Map<String, Player> byName = new HashMap<>();
        for (Div d : sel) {
            List<JSONObject>[] lists = data.get(d.id);
            if (lists == null) continue;
            for (int t = 0; t < 2; t++) {
                for (JSONObject r : lists[t]) {
                    String last = r.optString("playerLastname", ""), first = r.optString("playerFirstname", "");
                    if (r.isNull("playerFirstname")) first = "";
                    List<String> words = new ArrayList<>();
                    for (String w : (last + " " + first).split("[\\s\\-]+")) if (!w.isEmpty()) words.addAll(forms(w));
                    boolean all = true;
                    for (String tok : tokens) {
                        boolean hit = false;
                        for (String w : words) if (w.startsWith(tok)) { hit = true; break; }
                        if (!hit) { all = false; break; }
                    }
                    if (!all) continue;
                    String key = r.optString("id", last + "|" + first);
                    String nameKey = forms(last).get(0) + "|" + forms(first).get(0) + "|" + r.optString("teamShortname", "");
                    Player p = map.get(key);
                    if (p == null) p = byName.get(nameKey);
                    if (p == null) {
                        p = new Player();
                        p.key = key;
                        p.last = nice(last);
                        p.first = nice(first);
                        p.sortKey = (forms(last).get(0) + " " + forms(first).get(0));
                        p.score = forms(last).contains(tokens.get(0)) ? 0 : 1;
                        map.put(key, p);
                    }
                    byName.put(nameKey, p);
                    Entry e = new Entry();
                    e.div = d;
                    e.row = r;
                    e.goalie = t == 1;
                    p.entries.add(e);
                    if (Prefs.isFavTeam(a, d.group, teamName(d, r.optString("teamShortname", "")))) p.fav = true;
                }
            }
        }
        List<Player> out = new ArrayList<>(map.values());
        Collections.sort(out, new Comparator<Player>() {
            @Override
            public int compare(Player x, Player y) {
                if (x.score != y.score) return x.score - y.score;
                return x.sortKey.compareTo(y.sortKey);
            }
        });
        return out;
    }

    private void show(List<Player> found, int leagues, int failed) {
        results.removeAllViews();
        String s = found.isEmpty() ? "Kein Spieler gefunden" : found.size() == 1 ? "1 Spieler" : found.size() + " Spieler";
        if (found.size() > MAX_RESULTS) s += " – die ersten " + MAX_RESULTS + " angezeigt, Suche genauer fassen";
        s += " · " + leagues + (leagues == 1 ? " Liga" : " Ligen") + " durchsucht";
        if (failed > 0) s += " · " + failed + " nicht erreichbar";
        status.setText(s);
        status.setTextColor(failed > 0 ? Ui.RED : Ui.MUTED);
        int n = 0;
        for (Player p : found) {
            if (++n > MAX_RESULTS) break;
            results.addView(card(p));
            results.addView(Ui.colorBlock(a, Ui.LINE), new LinearLayout.LayoutParams(-1, 1));
        }
        if (found.isEmpty()) {
            TextView t = Ui.text(a, "Gesucht wird nur unter Spielern, die in dieser Saison schon eingesetzt wurden. "
                    + "Andere Altersklasse wählen oder weniger Buchstaben eingeben.", 13, Ui.MUTED, false);
            t.setPadding(dp(4), dp(16), dp(4), dp(16));
            results.addView(t);
        }
    }

    private View card(Player p) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(VERTICAL);
        box.setPadding(dp(6), dp(8), dp(6), dp(8));
        if (p.fav) box.setBackgroundColor(Ui.FAV);
        box.addView(Ui.text(a, p.first.isEmpty() ? p.last : p.last + ", " + p.first, 15, Ui.TEXT, true));
        for (final Entry e : p.entries) {
            JSONObject r = e.row;
            String sh = r.optString("teamShortname", "");
            String team = teamName(e.div, sh);
            String nr = r.isNull("playerJerseyNr") ? "" : "#" + r.optString("playerJerseyNr");
            String pos = position(r.optString("position", ""));
            StringBuilder head = new StringBuilder(e.div.group + " " + e.div.title);
            head.append(" · ").append(team.isEmpty() ? sh : team);
            if (!nr.isEmpty() || !pos.isEmpty()) head.append(" · ").append((nr + " " + pos).trim());
            LinearLayout line = new LinearLayout(a);
            line.setOrientation(VERTICAL);
            line.setPadding(0, dp(4), 0, 0);
            line.addView(Ui.text(a, head.toString(), 12, Ui.MUTED, false));
            line.addView(Ui.text(a, stats(r, e.goalie), 13, Ui.TEXT, false));
            line.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    GameSheet.standings(a, e.div.id, e.div.group + " · " + e.div.title);
                }
            });
            box.addView(line);
        }
        return box;
    }

    /** Feldspieler: Spiele · Tore · Vorlagen · Punkte · Strafminuten. Torhüter: Spiele · Gegentore/Spiel · Fangquote */
    static String stats(JSONObject r, boolean goalie) {
        int gp = r.optInt("gamesPlayed");
        if (goalie) {
            String s = gp + (gp == 1 ? " Spiel" : " Spiele") + " · " + r.optInt("goalsAgainst") + " GT · "
                    + String.format(Locale.GERMANY, "%.2f", r.optDouble("goalsAgainstAverage", 0)) + " GT/Spiel";
            double sv = r.optDouble("savePercentage", 0);
            if (sv > 0) s += " · " + String.format(Locale.GERMANY, "%.1f", sv) + " % Fangquote";
            int so = r.optInt("shutOuts");
            if (so > 0) s += " · " + so + " zu null";
            return s;
        }
        return gp + (gp == 1 ? " Spiel" : " Spiele") + " · " + r.optInt("goals") + " T · " + r.optInt("assists") + " A · "
                + r.optInt("points") + " P · " + r.optInt("penaltyMinutes") + " SM";
    }

    private static String position(String p) {
        if ("F".equals(p)) return "Stürmer";
        if ("D".equals(p)) return "Verteidiger";
        if ("G".equals(p)) return "Torhüter";
        return "";
    }

    private String teamName(Div d, String shortName) {
        String n = teamNames.get(d.id + "|" + shortName);
        return n == null ? "" : n;
    }

    /** Suchwörter: klein, ohne Satzzeichen; Umlaute als ae/oe/ue */
    static List<String> tokens(String q) {
        List<String> out = new ArrayList<>();
        for (String t : q.split("[\\s,;\\-]+")) {
            if (t.isEmpty()) continue;
            String n = forms(t).get(0);
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    /** zwei Schreibweisen eines Namens: „mueller“ (ä→ae) und „muller“ (Akzente weg) */
    static List<String> forms(String w) {
        String l = w.toLowerCase(Locale.GERMANY).replaceAll("[^\\p{L}]", "");
        String a1 = l.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        a1 = Normalizer.normalize(a1, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String a2 = Normalizer.normalize(l.replace("ß", "ss"), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        List<String> out = new ArrayList<>();
        out.add(a1);
        if (!a2.equals(a1)) out.add(a2);
        return out;
    }

    /** „JAAKOLA“ → „Jaakola“, „MÜLLER-SCHMIDT“ → „Müller-Schmidt“; gemischte Schreibweise bleibt */
    static String nice(String s) {
        if (s == null) return "";
        s = s.trim();
        if (!s.equals(s.toUpperCase(Locale.GERMANY)) || s.length() < 2) return s;
        StringBuilder b = new StringBuilder();
        boolean start = true;
        for (char c : s.toLowerCase(Locale.GERMANY).toCharArray()) {
            b.append(start ? Character.toUpperCase(c) : c);
            start = c == ' ' || c == '-' || c == '\'';
        }
        return b.toString();
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) a.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private int dp(int v) {
        return Ui.dp(a, v);
    }
}
