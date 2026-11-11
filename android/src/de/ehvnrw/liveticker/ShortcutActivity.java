package de.ehvnrw.liveticker;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/**
 * v2.94: unsichtbare Aktivität für die App-Verknüpfung „Alles deaktivieren“ (App-Symbol lange drücken).
 * Entfernt alle Floatings und Vormerkungen, ohne die App zu öffnen. v2.95: gemeinsam mit Optionen und
 * Benachrichtigung über OverlayService.deactivateAll.
 */
public class ShortcutActivity extends Activity {
    static final String SC_OFF = "de.ehvnrw.liveticker.SC_OFF";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (SC_OFF.equals(getIntent().getAction()))
            Toast.makeText(this, OverlayService.deactivatedText(OverlayService.deactivateAll(this)), Toast.LENGTH_SHORT).show();
        finish();
    }
}
