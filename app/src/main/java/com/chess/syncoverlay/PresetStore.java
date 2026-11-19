package com.chess.syncoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PresetStore {
    private static final String PREF_NAME = "chess_overlay_presets_v3";
    private static final String KEY_LIST = "presets_json";
    private static final String KEY_ACTIVE_ID = "active_preset_id";

    public static class Preset {
        public String id;
        public String name;
        public int x;
        public int y;
        public int size;
        public int boardAlpha;
        public int pieceAlpha;

        public Preset(String id, String name, int x, int y, int size, int boardAlpha, int pieceAlpha) {
            this.id = id;
            this.name = name;
            this.x = x;
            this.y = y;
            this.size = size;
            this.boardAlpha = boardAlpha;
            this.pieceAlpha = pieceAlpha;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("name", name);
                o.put("x", x);
                o.put("y", y);
                o.put("size", size);
                o.put("boardAlpha", boardAlpha);
                o.put("pieceAlpha", pieceAlpha);
            } catch (Exception ignored) {}
            return o;
        }

        public static Preset fromJson(JSONObject o) {
            return new Preset(
                    o.optString("id", UUID.randomUUID().toString()),
                    o.optString("name", "默认存档"),
                    o.optInt("x", 40),
                    o.optInt("y", 280),
                    o.optInt("size", 900),
                    o.optInt("boardAlpha", 42),
                    o.optInt("pieceAlpha", 92)
            );
        }
    }

    public static List<Preset> getAll(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_LIST, null);
        List<Preset> list = new ArrayList<>();
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) list.add(Preset.fromJson(o));
                }
            } catch (Exception ignored) {}
        }
        if (list.isEmpty()) {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int defSize = Math.round(dm.widthPixels * 0.92f);
            int defX = (dm.widthPixels - defSize) / 2;
            int defY = Math.round(dm.density * 190);
            Preset def = new Preset("default_1", "默认存档", defX, defY, defSize, 42, 92);
            list.add(def);
            saveList(ctx, list);
            setActiveId(ctx, def.id);
        }
        return list;
    }

    public static void saveList(Context ctx, List<Preset> list) {
        JSONArray arr = new JSONArray();
        for (Preset p : list) {
            arr.put(p.toJson());
        }
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LIST, arr.toString())
                .apply();
    }

    public static String getActiveId(Context ctx) {
        List<Preset> all = getAll(ctx);
        SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String id = sp.getString(KEY_ACTIVE_ID, null);
        for (Preset p : all) {
            if (p.id.equals(id)) return id;
        }
        String fallback = all.get(0).id;
        setActiveId(ctx, fallback);
        return fallback;
    }

    public static void setActiveId(Context ctx, String id) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ACTIVE_ID, id)
                .apply();
    }

    public static Preset getActive(Context ctx) {
        List<Preset> all = getAll(ctx);
        String activeId = getActiveId(ctx);
        for (Preset p : all) {
            if (p.id.equals(activeId)) return p;
        }
        return all.get(0);
    }

    public static void saveOrUpdate(Context ctx, Preset target) {
        List<Preset> all = getAll(ctx);
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(target.id)) {
                all.set(i, target);
                found = true;
                break;
            }
        }
        if (!found) {
            all.add(0, target);
        }
        saveList(ctx, all);
        setActiveId(ctx, target.id);
    }

    public static void delete(Context ctx, String id) {
        List<Preset> all = getAll(ctx);
        if (all.size() <= 1) return;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                all.remove(i);
                break;
            }
        }
        saveList(ctx, all);
        getActiveId(ctx);
    }
}
