package com.rc2.etx.radio;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;

/** 模型的持久化：SharedPreferences + 可导出到外部文件。 */
public final class Store {

    private static final String TAG = "ETX-Store";
    private static final String PREFS = "etx";
    private static final String KEY = "model";

    private Store() {
    }

    /** 机内摇杆开关：默认开（用户 2026-10-31 反复被重置困扰，改为开机自启）。 */
    public static boolean loadDumlOn(Context c) {
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("dumlOn", true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 启动即进全屏原生（默认开）：用户要的是"打开就是遥控器界面"。 */
    public static boolean loadFullNative(Context c) {
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("fullNative", true);
        } catch (Throwable t) { return true; }
    }

    public static void saveFullNative(Context c, boolean on) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("fullNative", on).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 遥控器型号：0=自动识别 1=RC2 2=RC Pro。自动模式下用系统型号 + protocol 服务探测。 */
    public static int loadModel(Context c) {
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("radioModel", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void saveModel(Context c, int m) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("radioModel", m).apply();
        } catch (Throwable ignore) {
        }
    }

    public static String modelName(int m) {
        return m == 1 ? "RC2" : (m == 2 ? "RC Pro" : "自动识别");
    }

    public static boolean loadHidOn(Context c) {
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("hidOn", false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void saveHidOn(Context c, boolean on) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("hidOn", on).apply();
        } catch (Throwable ignore) {
        }
    }

    public static void saveDumlOn(Context c, boolean on) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("dumlOn", on).apply();
        } catch (Throwable ignore) {
        }
    }

    public static String loadCalib(Context c) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return sp.getString("calib", "");
        } catch (Throwable t) {
            return "";
        }
    }

    public static void saveCalib(Context c, String json) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit().putString("calib", json).apply();
        } catch (Throwable t) {
            Log.w(TAG, "saveCalib failed: " + t);
        }
    }

    public static Model load(Context c) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String s = sp.getString(KEY, null);
            if (s == null || s.length() == 0) return Model.defaults();
            return fromJson(s);
        } catch (Throwable t) {
            Log.w(TAG, "load failed: " + t);
            return Model.defaults();
        }
    }

    public static void save(Context c, Model m) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit().putString(KEY, toJson(m)).apply();
        } catch (Throwable t) {
            Log.w(TAG, "save failed: " + t);
        }
    }

    public static String toJson(Model m) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", m.name);
            o.put("ver", 1);
            JSONArray a = new JSONArray();
            for (int i = 0; i < Mixer.CH_COUNT; i++) {
                ChannelConfig c = m.ch[i];
                JSONObject j = new JSONObject();
                j.put("src", c.src);
                j.put("expo", (double) c.expo);
                j.put("rate", (double) c.rate);
                j.put("off", (double) c.offset);
                j.put("rev", c.reverse);
                j.put("nm", c.name);
                a.put(j);
            }
            o.put("ch", a);
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    public static Model fromJson(String s) throws Exception {
        Model m = new Model();
        JSONObject o = new JSONObject(s);
        m.name = o.optString("name", "模型");
        JSONArray a = o.optJSONArray("ch");
        ChannelConfig[] cfg = new ChannelConfig[Mixer.CH_COUNT];
        for (int i = 0; i < Mixer.CH_COUNT; i++) {
            ChannelConfig c = new ChannelConfig();
            if (a != null && i < a.length()) {
                JSONObject j = a.getJSONObject(i);
                c.src = j.optInt("src", ChannelConfig.SRC_NONE);
                c.expo = (float) j.optDouble("expo", 0);
                c.rate = (float) j.optDouble("rate", 1);
                c.offset = (float) j.optDouble("off", 0);
                c.reverse = j.optBoolean("rev", false);
                c.name = j.optString("nm", "CH" + (i + 1));
            } else {
                c.src = ChannelConfig.SRC_NONE;
                c.name = "CH" + (i + 1);
            }
            cfg[i] = c;
        }
        m.ch = cfg;
        return m;
    }

    public static File file(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        return new File(dir, "model.json");
    }

    public static boolean exportFile(Context c, Model m) {
        try {
            File f = file(c);
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(toJson(m).getBytes("UTF-8"));
            fo.close();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "export failed: " + t);
            return false;
        }
    }

    public static Model importFile(Context c) {
        try {
            File f = file(c);
            if (!f.exists()) return null;
            FileInputStream fi = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fi.read(buf)) > 0) bo.write(buf, 0, n);
            fi.close();
            return fromJson(new String(bo.toByteArray(), "UTF-8"));
        } catch (Throwable t) {
            Log.w(TAG, "import failed: " + t);
            return null;
        }
    }
}
