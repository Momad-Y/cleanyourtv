package com.majnioui.tv.prayer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local change. The next prayer for the Clean Your TV launcher's game_day panel under the clock ("Asr in 2h 18m",
 * docs/modules.md): one row, from the times this app keeps for today. The launcher only reads; when the kept times
 * are not today's, this fetches them once in the background (at most every 5 minutes) and notifies the launcher.
 * After Isha the row is tomorrow's Fajr, taken from today's time, which is within a minute or two.
 */
public final class NextPrayerProvider extends ContentProvider {
    static final String AUTHORITY = "com.majnioui.tv.prayer.turns";
    static final Uri TURNS = Uri.parse("content://" + AUTHORITY + "/turns");
    private static final String[] COLUMNS = {"top_line", "word", "start_ms", "end_ms", "colour", "calendar", "turn_seconds"};
    private static final long RETRY_MS = 5 * 60_000L;
    private static final AtomicLong LAST_FETCH = new AtomicLong();

    @Override
    public boolean onCreate() {
        return true;
    }

    /** The kept times changed: tell the launcher to read again. */
    static void changed(Context ctx) {
        ctx.getContentResolver().notifyChange(TURNS, null);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor out = new MatrixCursor(COLUMNS);
        Context ctx = getContext();
        if (ctx == null) return out;
        out.setNotificationUri(ctx.getContentResolver(), TURNS);
        SharedPreferences prefs = ctx.getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        if (!today.equals(prefs.getString("cached_date", ""))) fetch(ctx);
        if (!prefs.contains("min_Fajr")) return out;   // nothing kept yet; the fetch will notify

        Calendar now = Calendar.getInstance();
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int index = -1;
        for (int i = 0; i < PrayerScheduler.NAMES.length; i++) {
            if (i == 1) continue;   // sunrise is not a prayer
            if (prefs.getInt("min_" + PrayerScheduler.NAMES[i], -1) > nowMin) {
                index = i;
                break;
            }
        }
        boolean tomorrow = index < 0;
        if (tomorrow) index = 0;
        String name = PrayerScheduler.NAMES[index];
        int minute = prefs.getInt("min_" + name, -1);
        if (minute < 0) return out;

        Calendar at = Calendar.getInstance();
        at.set(Calendar.HOUR_OF_DAY, minute / 60);
        at.set(Calendar.MINUTE, minute % 60);
        at.set(Calendar.SECOND, 0);
        at.set(Calendar.MILLISECOND, 0);
        if (tomorrow) at.add(Calendar.DAY_OF_MONTH, 1);
        long start = at.getTimeInMillis();
        String time = prefs.getString("time_" + name, "");
        // Isolated (FSI ... PDI) so the Arabic name does not swap places with the time in a left-to-right line.
        String top = ctx.getString(R.string.next_prayer_line, "\u2068" + PrayerScheduler.ARABIC[index] + "\u2069", time);
        int colour = ctx.getColor(R.color.accent);
        out.addRow(new Object[] {top, ctx.getString(PrayerScheduler.NAME_RES[index]), start, start + 1, colour, "", 30});
        return out;
    }

    private static void fetch(Context ctx) {
        long now = System.currentTimeMillis();
        long last = LAST_FETCH.get();
        if (now - last < RETRY_MS || !LAST_FETCH.compareAndSet(last, now)) return;
        Context app = ctx.getApplicationContext();
        PrayerScheduler.fetchTimings(app, () -> changed(app));
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }
}
