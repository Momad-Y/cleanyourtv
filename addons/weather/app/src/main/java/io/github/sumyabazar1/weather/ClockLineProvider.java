package io.github.sumyabazar1.weather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local change. Today's weather as one short line ("27° Sunny · H 31° L 24°") for the Clean Your TV launcher's
 * clock_line key, which shows it under the date. The launcher only reads this provider; the fetching happens here,
 * and only when the line is read and the last reading is more than 30 minutes old (5 minutes after a failure).
 * A reading older than 6 hours is not shown. Nothing is fetched until a city has been picked in the app.
 */
public final class ClockLineProvider extends ContentProvider {
    private static final String TAG = "WeatherLine";
    private static final String CACHE = "clock_line";
    private static final long STALE_MS = 6 * 60 * 60_000L;
    private static final int TIMEOUT_MS = 8_000;
    private static final String[] COLUMNS = {"text"};
    private static final AtomicBoolean FETCHING = new AtomicBoolean();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor out = new MatrixCursor(COLUMNS);
        Context ctx = getContext();
        if (ctx == null) return out;
        SharedPreferences place = ctx.getSharedPreferences("place", Context.MODE_PRIVATE);
        if (!place.contains("lat")) return out;   // no city picked yet
        String placeKey = place.getLong("lat", 0) + "," + place.getLong("lon", 0);

        SharedPreferences cache = ctx.getSharedPreferences(CACHE, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        boolean samePlace = placeKey.equals(cache.getString("place", ""));
        long readAt = samePlace ? cache.getLong("at", 0) : 0;
        long triedAt = samePlace ? cache.getLong("tried", 0) : 0;
        boolean due = now - readAt > Forecast.REFRESH_MS && now - triedAt > Forecast.RETRY_MS;
        if (due) refresh(ctx, uri, placeKey,
                Double.longBitsToDouble(place.getLong("lat", 0)), Double.longBitsToDouble(place.getLong("lon", 0)));

        String text = cache.getString("text", null);
        if (samePlace && text != null && now - readAt < STALE_MS) out.addRow(new Object[] {text});
        out.setNotificationUri(ctx.getContentResolver(), uri);
        return out;
    }

    /** One fetch at a time, off the binder thread; notifies the launcher when the line changes. */
    private static void refresh(Context ctx, Uri uri, String placeKey, double lat, double lon) {
        if (!FETCHING.compareAndSet(false, true)) return;
        Context app = ctx.getApplicationContext();
        SharedPreferences cache = app.getSharedPreferences(CACHE, Context.MODE_PRIVATE);
        cache.edit().putString("place", placeKey).putLong("tried", System.currentTimeMillis()).apply();
        new Thread(() -> {
            try {
                String line = todayLine(app, lat, lon);
                cache.edit().putString("place", placeKey).putString("text", line)
                        .putLong("at", System.currentTimeMillis()).apply();
                app.getContentResolver().notifyChange(uri, null);
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w(TAG, "today's weather unavailable", e);
            } finally {
                FETCHING.set(false);
            }
        }, "weather-line").start();
    }

    /** "27° Sunny · H 31° L 24°", in the unit chosen in the app's settings. */
    static String todayLine(Context ctx, double lat, double lon) throws IOException, JSONException {
        String where = String.format(Locale.ROOT, "latitude=%.4f&longitude=%.4f", lat, lon);
        JSONObject reply = get("https://api.open-meteo.com/v1/forecast?" + where + "&models=icon_seamless"
                + "&current=temperature_2m,relative_humidity_2m,weather_code,is_day"
                + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1");
        JSONObject current = reply.getJSONObject("current");
        JSONObject daily = reply.getJSONObject("daily");
        double temp = current.getDouble("temperature_2m");
        int code = Forecast.snowIfCold(current.getInt("weather_code"), temp, current.getDouble("relative_humidity_2m"));
        boolean day = current.getInt("is_day") == 1;
        boolean f = ctx.getSharedPreferences("options", Context.MODE_PRIVATE).getBoolean("fahrenheit",
                "f".equalsIgnoreCase(ctx.getString(R.string.temperature_unit).trim()));
        double high = daily.getJSONArray("temperature_2m_max").getDouble(0);
        double low = daily.getJSONArray("temperature_2m_min").getDouble(0);
        return Forecast.degrees(temp, f) + "° " + ctx.getString(Forecast.sky(code, day))
                + " · H " + Forecast.degrees(high, f) + "° L " + Forecast.degrees(low, f) + "°";
    }

    private static JSONObject get(String url) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
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
