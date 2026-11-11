package de.ehvnrw.liveticker;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * v2.94: unsichtbare Aktivität für die App-Verknüpfung „Alles deaktivieren“ (App-Symbol lange drücken).
 * Entfernt alle Floatings und Vormerkungen – wie „Beenden“ in der Benachrichtigung –, ohne die App zu öffnen.
 */
public class ShortcutActivity extends Activity {
    static final String SC_OFF = "de.ehvnrw.liveticker.SC_OFF";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (SC_OFF.equals(getIntent().getAction())) {
            int n = OverlayService.liveCount() + OverlayService.pendingCount();
            if (n > 0) {
                startForegroundService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_STOP));
                Toast.makeText(this, n == 1 ? "1 Floating/Vormerkung deaktiviert" : n + " Floatings/Vormerkungen deaktiviert",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Keine Floatings oder Vormerkungen aktiv", Toast.LENGTH_SHORT).show();
            }
        }
        finish();
    }
}
