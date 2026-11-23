package com.example.classpulse.online;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONObject;

/** Durable per-file journal. Committing the file metadata always precedes scheduling its upload. */
public final class LectureRecordingStore {
    private LectureRecordingStore() {}
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("lecture_recording_queue", Context.MODE_PRIVATE);
    }
    private static String key(String uid, String sid, String id) { return uid + "/" + sid + "/" + id; }

    public static synchronized boolean save(Context context, String uid, String sid, JSONObject value) {
        return prefs(context).edit().putString(key(uid, sid, value.optString("id")), value.toString()).commit();
    }
    public static synchronized JSONObject get(Context context, String uid, String sid, String id) {
        return OnlineJson.object(prefs(context).getString(key(uid, sid, id), "{}"));
    }
    public static synchronized List<JSONObject> list(Context context, String uid, String sid) {
        String prefix = uid + "/" + sid + "/";
        List<JSONObject> values = new ArrayList<>();
        for (java.util.Map.Entry<String, ?> item : prefs(context).getAll().entrySet()) {
            if (item.getKey().startsWith(prefix) && item.getValue() instanceof String) {
                JSONObject value = OnlineJson.object((String) item.getValue());
                if (!value.optString("id").isEmpty()) values.add(value);
            }
        }
        values.sort(Comparator.comparingLong((JSONObject v) -> v.optLong("startedAt"))
                .thenComparing(v -> v.optString("id")));
        return values;
    }
    public static synchronized void state(Context context, String uid, String sid, String id, String state, String error) {
        JSONObject value = get(context, uid, sid, id);
        if (value.optString("id").isEmpty()) return;
        try { value.put("state", state); value.put("error", error); } catch (Exception ignored) { return; }
        save(context, uid, sid, value);
    }
    public static void resume(Context context, String uid, String sid) {
        for (JSONObject value : list(context, uid, sid)) {
            String state = value.optString("state");
            if (state.equals("SAVED") || state.equals("QUEUED") || state.equals("UPLOADING") || state.equals("SUBMITTING")) {
                LectureUploadWorker.enqueue(context, uid, sid, value.optString("id"));
            }
        }
    }
    public static void resumeAccount(Context context, String uid) {
        java.util.Set<String> sessions = new java.util.HashSet<>();
        for (String item : prefs(context).getAll().keySet()) {
            String[] path = item.split("/");
            if (path.length == 3 && path[0].equals(uid)) sessions.add(path[1]);
        }
        for (String sid : sessions) resume(context, uid, sid);
    }
}
