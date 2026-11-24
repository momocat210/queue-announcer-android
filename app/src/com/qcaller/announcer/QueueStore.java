package com.qcaller.announcer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** 队列数据：已发号、等待队列、当前叫号、已办结 */
public class QueueStore {

    public int issued = 0;       // 已发出的最大号码
    public int current = 0;      // 当前正在办理（0 = 无人）
    public int doneCount = 0;
    public ArrayList<Integer> waiting = new ArrayList<Integer>();   // 已取号未叫
    public ArrayList<Integer> called = new ArrayList<Integer>();    // 最近叫过的（最新在前）

    private static final String SP = "queue_data";
    private static final String KEY = "data";

    public static QueueStore load(Context c) {
        QueueStore q = new QueueStore();
        try {
            SharedPreferences sp = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY, "");
            if (raw == null || raw.length() == 0) return q;
            JSONObject o = new JSONObject(raw);
            q.issued = o.optInt("issued", 0);
            q.current = o.optInt("current", 0);
            q.doneCount = o.optInt("done", 0);
            JSONArray w = o.optJSONArray("waiting");
            if (w != null) for (int i = 0; i < w.length(); i++) q.waiting.add(w.optInt(i));
            JSONArray cl = o.optJSONArray("called");
            if (cl != null) for (int i = 0; i < cl.length(); i++) q.called.add(cl.optInt(i));
        } catch (Exception ignore) { }
        return q;
    }

    public void save(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("issued", issued);
            o.put("current", current);
            o.put("done", doneCount);
            JSONArray w = new JSONArray();
            for (Integer i : waiting) w.put(i.intValue());
            o.put("waiting", w);
            JSONArray cl = new JSONArray();
            for (Integer i : called) cl.put(i.intValue());
            o.put("called", cl);
            c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply();
        } catch (Exception ignore) { }
    }

    /** 取号：返回新号码，加入等待队列 */
    public int take(int startAt) {
        if (issued < startAt) issued = startAt - 1;
        issued++;
        waiting.add(issued);
        return issued;
    }

    /** 叫下一位：从等待队列取第一个；队列空返回 -1 */
    public int callNext() {
        if (waiting.isEmpty()) return -1;
        int n = waiting.remove(0);
        setCurrent(n);
        return n;
    }

    public boolean callNumber(int n) {
        int idx = waiting.indexOf(n);
        if (idx >= 0) waiting.remove(idx);
        setCurrent(n);
        return true;
    }

    private void setCurrent(int n) {
        if (current > 0 && current != n) doneCount++;
        current = n;
        called.add(0, n);
        while (called.size() > 30) called.remove(called.size() - 1);
    }

    /** 办结：当前号完成，清空当前 */
    public void complete() {
        if (current > 0) {
            doneCount++;
            current = 0;
        }
    }

    /** 过号：放回队首，等待重新呼叫 */
    public void backToQueue() {
        if (current > 0) {
            waiting.add(0, current);
            current = 0;
        }
    }

    public void reset(int startAt) {
        issued = startAt - 1;
        current = 0;
        doneCount = 0;
        waiting.clear();
        called.clear();
    }

    public int waitCount() { return waiting.size(); }
}
