package com.micalendario.laboral;

import android.Manifest;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/** Sincroniza los turnos con el calendario del móvil (Samsung, Google…). Solo toca los eventos que creó esta app. */
@CapacitorPlugin(
    name = "CalSync",
    permissions = {
        @Permission(alias = "calendar", strings = { Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR })
    }
)
public class CalSyncPlugin extends Plugin {

    private static final String SCHEME = "micalendario://turno/";

    @PluginMethod
    public void ensure(PluginCall call) {
        if (getPermissionState("calendar") == PermissionState.GRANTED) {
            JSObject r = new JSObject();
            r.put("granted", true);
            call.resolve(r);
        } else {
            requestPermissionForAlias("calendar", call, "permCb");
        }
    }

    @PermissionCallback
    private void permCb(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", getPermissionState("calendar") == PermissionState.GRANTED);
        call.resolve(r);
    }

    private boolean ok(PluginCall call) {
        if (getPermissionState("calendar") != PermissionState.GRANTED) {
            call.reject("permiso");
            return false;
        }
        return true;
    }

    @PluginMethod
    public void calendars(PluginCall call) {
        if (!ok(call)) return;
        JSArray out = new JSArray();
        String[] proj = { Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.IS_PRIMARY };
        try (Cursor c = getContext().getContentResolver().query(Calendars.CONTENT_URI, proj, null, null, Calendars.ACCOUNT_NAME + " ASC")) {
            while (c != null && c.moveToNext()) {
                if (c.getInt(3) < Calendars.CAL_ACCESS_CONTRIBUTOR) continue;
                JSObject o = new JSObject();
                o.put("id", c.getLong(0));
                o.put("name", c.getString(1));
                o.put("account", c.getString(2));
                o.put("primary", c.getInt(4) == 1);
                out.put(o);
            }
        } catch (Exception e) {
            call.reject("No se pudo leer los calendarios");
            return;
        }
        JSObject r = new JSObject();
        r.put("calendars", out);
        call.resolve(r);
    }

    @PluginMethod
    public void sync(final PluginCall call) {
        if (!ok(call)) return;
        new Thread(() -> {
            try {
                call.resolve(doSync(call));
            } catch (Exception e) {
                call.reject("No se pudo sincronizar: " + e.getMessage());
            }
        }).start();
    }

    private JSObject doSync(PluginCall call) throws Exception {
        long calId = call.getData().getLong("calId");
        long from = call.getData().getLong("from");
        long to = call.getData().getLong("to");
        JSONArray evs = call.getData().getJSONArray("events");
        String pkg = getContext().getPackageName();
        android.content.ContentResolver cr = getContext().getContentResolver();

        // Eventos que ya creó la app en ese calendario
        Map<String, long[]> have = new HashMap<>(); // uid -> [id, dtstart, dtend, allDay]
        Map<String, String[]> haveTxt = new HashMap<>(); // uid -> [title, desc]
        String[] proj = { Events._ID, Events.CUSTOM_APP_URI, Events.DTSTART, Events.DTEND, Events.ALL_DAY, Events.TITLE, Events.DESCRIPTION };
        try (Cursor c = cr.query(Events.CONTENT_URI, proj,
                Events.CALENDAR_ID + "=? AND " + Events.CUSTOM_APP_PACKAGE + "=? AND " + Events.DELETED + "=0",
                new String[] { String.valueOf(calId), pkg }, null)) {
            while (c != null && c.moveToNext()) {
                String uri = c.getString(1);
                if (uri == null || !uri.startsWith(SCHEME)) continue;
                String uid = uri.substring(SCHEME.length());
                have.put(uid, new long[] { c.getLong(0), c.getLong(2), c.getLong(3), c.getInt(4) });
                haveTxt.put(uid, new String[] { c.getString(5), c.getString(6) });
            }
        }

        int added = 0, updated = 0, removed = 0;
        Set<String> seen = new HashSet<>();
        String tz = TimeZone.getDefault().getID();
        for (int i = 0; i < evs.length(); i++) {
            JSONObject e = evs.getJSONObject(i);
            String uid = e.getString("uid");
            seen.add(uid);
            boolean all = e.optBoolean("allDay", false);
            long s = e.getLong("s"), en = e.getLong("e");
            String title = e.optString("title", "");
            String desc = e.optString("desc", "");
            long[] h = have.get(uid);
            if (h != null) {
                String[] t = haveTxt.get(uid);
                boolean same = h[1] == s && h[2] == en && (h[3] == 1) == all
                    && title.equals(t[0] == null ? "" : t[0]) && desc.equals(t[1] == null ? "" : t[1]);
                if (same) continue;
            }
            ContentValues v = new ContentValues();
            v.put(Events.CALENDAR_ID, calId);
            v.put(Events.TITLE, title);
            v.put(Events.DESCRIPTION, desc);
            v.put(Events.DTSTART, s);
            v.put(Events.DTEND, en);
            v.put(Events.ALL_DAY, all ? 1 : 0);
            v.put(Events.EVENT_TIMEZONE, all ? "UTC" : tz);
            v.put(Events.AVAILABILITY, all ? Events.AVAILABILITY_FREE : Events.AVAILABILITY_BUSY);
            if (h != null) {
                cr.update(ContentUris.withAppendedId(Events.CONTENT_URI, h[0]), v, null, null);
                updated++;
            } else {
                v.put(Events.CUSTOM_APP_PACKAGE, pkg);
                v.put(Events.CUSTOM_APP_URI, SCHEME + uid);
                cr.insert(Events.CONTENT_URI, v);
                added++;
            }
        }
        // Quitar los que ya no existen en la app (solo dentro del periodo sincronizado)
        for (Map.Entry<String, long[]> en : have.entrySet()) {
            if (seen.contains(en.getKey())) continue;
            long ds = en.getValue()[1];
            if (ds < from || ds > to) continue;
            cr.delete(ContentUris.withAppendedId(Events.CONTENT_URI, en.getValue()[0]), null, null);
            removed++;
        }
        JSObject r = new JSObject();
        r.put("added", added);
        r.put("updated", updated);
        r.put("removed", removed);
        return r;
    }

    /** Borra del calendario todos los eventos que creó esta app. */
    @PluginMethod
    public void clear(PluginCall call) {
        if (!ok(call)) return;
        int n = getContext().getContentResolver().delete(Events.CONTENT_URI,
            Events.CUSTOM_APP_PACKAGE + "=?", new String[] { getContext().getPackageName() });
        JSObject r = new JSObject();
        r.put("removed", n);
        call.resolve(r);
    }
}
