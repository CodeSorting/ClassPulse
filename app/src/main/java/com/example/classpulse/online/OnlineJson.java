package com.example.classpulse.online;

import com.google.firebase.Timestamp;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Wire/cache conversion only. Fragments receive copies, not mutable Room entities. */
public final class OnlineJson {
    private OnlineJson() {}

    public static JSONObject object(String value) {
        try {
            return new JSONObject(value);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static JSONObject json(Object value) {
        try {
            return (JSONObject) normalize(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid server document", e);
        }
    }

    private static Object normalize(Object value) throws Exception {
        if (value == null) return JSONObject.NULL;
        if (value instanceof Timestamp)
            return ((Timestamp) value).getSeconds() * 1000
                    + ((Timestamp) value).getNanoseconds() / 1000000;
        if (value instanceof Map) {
            JSONObject o = new JSONObject();
            for (Object item : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) item;
                o.put(String.valueOf(e.getKey()), normalize(e.getValue()));
            }
            return o;
        }
        if (value instanceof Collection) {
            JSONArray a = new JSONArray();
            for (Object v : (Collection<?>) value) a.put(normalize(v));
            return a;
        }
        return value;
    }

    public static Map<String, Object> map(JSONObject o) {
        Map<String, Object> m = new LinkedHashMap<>();
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = o.opt(k);
            m.put(k, toWire(v));
        }
        return m;
    }

    private static Object toWire(Object v) {
        if (v == JSONObject.NULL) return null;
        if (v instanceof JSONObject) return map((JSONObject) v);
        if (v instanceof JSONArray) {
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < ((JSONArray) v).length(); i++)
                list.add(toWire(((JSONArray) v).opt(i)));
            return list;
        }
        return v;
    }

    public static JSONObject values(Object... pairs) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2)
                o.put(
                        String.valueOf(pairs[i]),
                        pairs[i + 1] == null ? JSONObject.NULL : pairs[i + 1]);
            return o;
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static String id() {
        return UUID.randomUUID().toString();
    }

    public static boolean contains(JSONArray a, String id) {
        if (a == null) return false;
        for (int i = 0; i < a.length(); i++) if (id.equals(a.optString(i))) return true;
        return false;
    }
}
