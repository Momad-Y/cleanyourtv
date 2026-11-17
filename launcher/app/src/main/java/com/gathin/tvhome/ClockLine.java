package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Local change. clock_line (config.xml or a theme): one short line under the date from an add-on, such as today's
 * weather. Same shape as GameDay: the owner names the add-on's package, the add-on fetches, and the launcher only
 * reads its provider (meta-data {@value #META}, query content://authority/line, column "text") while Home is on
 * screen, so it stays offline.
 */
final class ClockLine {
    static final String META = "com.gathin.tvhome.clockline";
    private static final String TAG = "TvHome";
    private static final String[] COLUMNS = {"text"};

    private final Context ctx;
    private final Uri uri;
    private final Handler main;
    private final Runnable onChange;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ContentObserver observer;
    private boolean watching;
    private String text = "";

    private ClockLine(Context ctx, Uri uri, Handler main, Runnable onChange) {
        this.ctx = ctx;
        this.uri = uri;
        this.main = main;
        this.onChange = onChange;
        observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                read();
            }
        };
    }

    /** Null when the key is empty, the package is missing, or it does not own the provider it names. */
    static ClockLine create(Context ctx, String pkg, Handler main, Runnable onChange) {
        if (pkg == null || pkg.trim().isEmpty()) return null;
        String name = pkg.trim();
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(name, PackageManager.GET_META_DATA);
            String authority = info.metaData == null ? null : info.metaData.getString(META);
            if (authority == null || authority.trim().isEmpty()) return null;
            ProviderInfo provider = pm.resolveContentProvider(authority.trim(), 0);
            if (provider == null || !name.equals(provider.packageName)) return null;
            Uri uri = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority.trim())
                    .appendPath("line").build();
            return new ClockLine(ctx, uri, main, onChange);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Home is on screen: read now and watch for changes. */
    void start() {
        if (!watching) {
            try {
                ctx.getContentResolver().registerContentObserver(uri, false, observer);
                watching = true;
            } catch (SecurityException e) {
                Log.w(TAG, "clock_line provider unavailable", e);
            }
        }
        read();
    }

    /** Home is off screen: nothing runs until it is back. */
    void stop() {
        if (watching) ctx.getContentResolver().unregisterContentObserver(observer);
        watching = false;
    }

    void shutdown() {
        stop();
        io.shutdownNow();
    }

    /** One query off the main thread, then onChange on the main thread. */
    void read() {
        try {
            io.execute(() -> {
                String line = "";
                try (Cursor c = ctx.getContentResolver().query(uri, COLUMNS, null, null, null)) {
                    if (c != null && c.moveToFirst() && !c.isNull(0)) line = c.getString(0).trim();
                } catch (RuntimeException gone) {
                    Log.w(TAG, "clock_line query failed", gone);
                    return;   // keep what is on screen
                }
                final String result = line;
                main.post(() -> {
                    text = result;
                    onChange.run();
                });
            });
        } catch (RejectedExecutionException shutDown) {
            // Home is being destroyed.
        }
    }

    /** The line to show, or "" for none. */
    String text() {
        return text;
    }
}
