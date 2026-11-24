package com.qcaller.announcer;

import android.content.Context;
import android.content.SharedPreferences;

/** 全局设置 */
public class Cfg {

    // 角色
    public String role = "";              // "" | server | caller
    public String broadcast = "server";   // server | caller | both

    // 蓝牙组网
    public String peerMac = "";
    public String peerName = "";

    // 打印机
    public boolean printerEnabled = true;
    public String printerMac = "";
    public String printerName = "";
    public boolean printerUseKey = true;   // 喵喵机 CRC 密钥握手
    public int printerDensity = 90;
    public int feedLines = 120;
    public boolean autoPrint = true;
    /** 每张号单之间的间隔（毫秒）。取号太快时打印机来不及走完纸，间隔太短会打印失败 */
    public int printGapMs = 5000;

    // 播报
    public int start = 1;
    public int digits = 3;
    public int repeat = 1;
    public String template = "请{num}号办理业务";
    public String extra = "";
    public String readMode = "digit";
    public float rate = 0.95f;
    public float volume = 1f;
    public boolean chime = true;
    public boolean awake = true;

    // 单机模式计数器（叫号端离线时）
    public int offlineCurrent = 0;

    private static final String SP = "queue_cfg_v2";

    public static Cfg load(Context c) {
        SharedPreferences sp = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
        Cfg f = new Cfg();
        f.role = sp.getString("role", "");
        f.broadcast = sp.getString("broadcast", "server");
        f.peerMac = sp.getString("peerMac", "");
        f.peerName = sp.getString("peerName", "");
        f.printerEnabled = sp.getBoolean("printerEnabled", true);
        f.printerMac = sp.getString("printerMac", "");
        f.printerName = sp.getString("printerName", "");
        f.printerUseKey = sp.getBoolean("printerUseKey", true);
        f.printerDensity = sp.getInt("printerDensity", 90);
        f.feedLines = sp.getInt("feedLines", 120);
        f.autoPrint = sp.getBoolean("autoPrint", true);
        f.printGapMs = sp.getInt("printGapMs", 5000);
        f.start = sp.getInt("start", 1);
        f.digits = sp.getInt("digits", 3);
        f.repeat = sp.getInt("repeat", 1);
        f.template = sp.getString("template", "请{num}号办理业务");
        f.extra = sp.getString("extra", "");
        f.readMode = sp.getString("readMode", "digit");
        f.rate = sp.getFloat("rate", 0.95f);
        f.volume = sp.getFloat("volume", 1f);
        f.chime = sp.getBoolean("chime", true);
        f.awake = sp.getBoolean("awake", true);
        f.offlineCurrent = sp.getInt("offlineCurrent", 0);
        return f;
    }

    public void save(Context c) {
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putString("role", role)
                .putString("broadcast", broadcast)
                .putString("peerMac", peerMac)
                .putString("peerName", peerName)
                .putBoolean("printerEnabled", printerEnabled)
                .putString("printerMac", printerMac)
                .putString("printerName", printerName)
                .putBoolean("printerUseKey", printerUseKey)
                .putInt("printerDensity", printerDensity)
                .putInt("feedLines", feedLines)
                .putBoolean("autoPrint", autoPrint)
                .putInt("printGapMs", printGapMs)
                .putInt("start", start)
                .putInt("digits", digits)
                .putInt("repeat", repeat)
                .putString("template", template)
                .putString("extra", extra)
                .putString("readMode", readMode)
                .putFloat("rate", rate)
                .putFloat("volume", volume)
                .putBoolean("chime", chime)
                .putBoolean("awake", awake)
                .putInt("offlineCurrent", offlineCurrent)
                .apply();
    }

    public String fmt(int n) {
        String s = String.valueOf(n);
        while (s.length() < digits) s = "0" + s;
        return s;
    }

    public String spoken(int n) {
        String s = fmt(n);
        if (!"digit".equals(readMode)) {
            try { return String.valueOf(Integer.parseInt(s)); } catch (Exception e) { return s; }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') sb.append("零一二三四五六七八九".charAt(ch - '0'));
            else sb.append(ch);
        }
        return sb.toString();
    }

    public String buildText(int n) {
        String t = (template == null || template.length() == 0) ? "请{num}号办理业务" : template;
        t = t.replace("{num}", spoken(n));
        String ex = extra == null ? "" : extra.trim();
        if (ex.length() > 0) {
            while (t.endsWith("，") || t.endsWith(",") || t.endsWith("。")) t = t.substring(0, t.length() - 1);
            t = t + "，" + ex;
        }
        return t;
    }

    public void resetToDefaults() {
        broadcast = "server";
        printerEnabled = true;
        printerUseKey = true;
        printerDensity = 90;
        feedLines = 120;
        autoPrint = true;
        printGapMs = 5000;
        start = 1; digits = 3; repeat = 1;
        template = "请{num}号办理业务";
        extra = "";
        readMode = "digit";
        rate = 0.95f; volume = 1f;
        chime = true; awake = true;
    }
}
