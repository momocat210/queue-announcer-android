package com.qcaller.announcer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;

/** 叫号端：手持遥控，通过蓝牙指挥服务器端；离线时单机模式 */
public class CallerActivity extends Activity implements BtLink.Listener, Speaker.Listener {

    private Cfg cfg;
    private Speaker speaker;
    private BtLink link;

    private TextView tvNum, tvLabel, tvSub, tvNextSub, tvReplay, badgeLink, tvQueueTitle, tvQueueNext;
    private View dot;
    private LinearLayout historyRow, btnPrev, queueRow;

    private int linkState = BtLink.ST_DISCONNECTED;
    private long connectStart = 0;
    private int serverCur = 0;
    private int serverWait = 0;
    private int serverIssued = 0;
    private boolean speaking = false;
    private final ArrayList<Integer> history = new ArrayList<Integer>();
    /** 服务器端的排队顺序（队首 = 下一位要叫的号） */
    private final ArrayList<Integer> serverQueue = new ArrayList<Integer>();

    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (cfg.peerMac != null && cfg.peerMac.length() > 0) {
                if (linkState == BtLink.ST_DISCONNECTED) {
                    link.connect(cfg.peerMac);
                } else if (linkState == BtLink.ST_CONNECTING
                        && System.currentTimeMillis() - connectStart > 15000) {
                    link.stop();
                    linkState = BtLink.ST_DISCONNECTED;
                    render();
                }
            }
            h.postDelayed(this, 6000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_caller);

        cfg = Cfg.load(this);

        tvNum = (TextView) findViewById(R.id.tvNum);
        tvLabel = (TextView) findViewById(R.id.tvLabel);
        tvSub = (TextView) findViewById(R.id.tvSub);
        tvNextSub = (TextView) findViewById(R.id.tvNextSub);
        tvReplay = (TextView) findViewById(R.id.tvReplay);
        badgeLink = (TextView) findViewById(R.id.badgeLink);
        dot = findViewById(R.id.dot);
        historyRow = (LinearLayout) findViewById(R.id.historyRow);
        btnPrev = (LinearLayout) findViewById(R.id.btnPrev);
        queueRow = (LinearLayout) findViewById(R.id.queueRow);
        tvQueueTitle = (TextView) findViewById(R.id.tvQueueTitle);
        tvQueueNext = (TextView) findViewById(R.id.tvQueueNext);

        findViewById(R.id.btnNext).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doNext(); }
        });
        findViewById(R.id.btnReplay).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doReplay(); }
        });
        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doPrev(); }
        });
        findViewById(R.id.btnManual).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doManual(); }
        });
        findViewById(R.id.btnSet).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(CallerActivity.this, SettingsActivity.class));
            }
        });

        speaker = new Speaker(this, this);
        link = new BtLink(this);

        applyAwake();
        render();
        h.postDelayed(tick, 300);
    }

    @Override
    protected void onResume() {
        super.onResume();
        cfg = Cfg.load(this);
        applyAwake();
        render();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (link != null) link.stop();
        if (speaker != null) speaker.shutdown();
        super.onDestroy();
    }

    private void applyAwake() {
        if (cfg.awake) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private boolean connected() {
        return linkState == BtLink.ST_CONNECTED && link != null && link.isConnected();
    }

    private boolean hasPeer() {
        return cfg.peerMac != null && cfg.peerMac.length() > 0;
    }

    /* ================= 操作 ================= */

    private void doNext() {
        if (connected()) {
            if (serverQueue.isEmpty() && serverWait <= 0) {
                // 本地先给个提示，但仍然发一次，避免刚取号时状态还没同步到而被误拦
                toast(serverIssued == 0 ? "还没有人取号，请先在取号台取号" : "队列已叫完，等待新号码");
            }
            link.send("{\"t\":\"next\"}");
            haptic();
            return;
        }
        int n = (cfg.offlineCurrent <= 0 ? cfg.start : cfg.offlineCurrent + 1);
        cfg.offlineCurrent = n;
        cfg.save(this);
        pushHistory(n);
        haptic();
        announceLocal(n);
        render();
    }

    private void doReplay() {
        if (connected()) {
            link.send("{\"t\":\"replay\"}");
            haptic();
            return;
        }
        int n = cfg.offlineCurrent > 0 ? cfg.offlineCurrent : cfg.start;
        haptic();
        announceLocal(n);
    }

    private void doPrev() {
        if (connected()) {
            link.send("{\"t\":\"prev\"}");
            haptic();
            return;
        }
        if (cfg.offlineCurrent <= cfg.start) { toast("已经是第一个号码了"); return; }
        cfg.offlineCurrent--;
        cfg.save(this);
        haptic();
        render();
        toast("已回退到 " + cfg.fmt(cfg.offlineCurrent));
    }

    private void doManual() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("输入要呼叫的号码");
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF6B7593);
        int pad = dp(18);
        et.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("手动呼号")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("呼叫", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        int n;
                        try { n = Integer.parseInt(et.getText().toString().trim()); }
                        catch (Exception e) { toast("号码无效"); return; }
                        if (n <= 0) { toast("号码无效"); return; }
                        if (connected()) {
                            try {
                                JSONObject o = new JSONObject();
                                o.put("t", "call");
                                o.put("n", n);
                                link.send(o.toString());
                            } catch (Exception ignore) { }
                        } else {
                            cfg.offlineCurrent = n;
                            cfg.save(CallerActivity.this);
                            pushHistory(n);
                            announceLocal(n);
                            render();
                        }
                    }
                }).show();
    }

    private void announceLocal(int n) {
        if (speaker == null) return;
        speaker.speak(cfg.buildText(n), cfg.repeat, cfg.rate, cfg.volume, cfg.chime);
    }

    private void pushHistory(int n) {
        if (!history.isEmpty() && history.get(0) == n) return;
        history.add(0, n);
        while (history.size() > 12) history.remove(history.size() - 1);
    }

    private void haptic() {
        try { tvNum.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); }
        catch (Exception ignore) { }
    }

    /* ================= 界面 ================= */

    private void render() {
        try {
            renderAll();
        } catch (Throwable t) {
            try { badgeLink.setText("界面异常"); } catch (Throwable ignore) { }
        }
    }

    private void renderAll() {
        boolean conn = connected();
        int showing;
        if (conn) {
            showing = serverCur > 0 ? serverCur
                    : (history.isEmpty() ? Math.max(serverIssued + 1, cfg.start) : history.get(0));
        } else {
            showing = cfg.offlineCurrent > 0 ? cfg.offlineCurrent : cfg.start;
        }

        tvNum.setText(cfg.fmt(showing));
        tvLabel.setText(conn
                ? (serverCur > 0 ? "当 前 叫 号" : "等 待 叫 号")
                : (cfg.offlineCurrent > 0 ? "单 机 叫 号" : "待 叫 号 码"));

        if (conn) {
            tvSub.setText("等待 " + serverWait + " 人 · 已取号 " + serverIssued);
            badgeLink.setText("已连接");
            badgeLink.setBackgroundResource(R.drawable.badge_ok);
            badgeLink.setTextColor(getResources().getColor(R.color.teal));
        } else if (hasPeer()) {
            tvSub.setText(linkState == BtLink.ST_CONNECTING ? "正在连接服务器…" : "服务器离线 · 单机模式");
            badgeLink.setText(linkState == BtLink.ST_CONNECTING ? "连接中" : "单机模式");
            badgeLink.setBackgroundResource(R.drawable.badge_warn);
            badgeLink.setTextColor(getResources().getColor(R.color.amber));
        } else {
            tvSub.setText("未配置服务器 · 单机模式");
            badgeLink.setText("未配对");
            badgeLink.setBackgroundResource(R.drawable.badge_warn);
            badgeLink.setTextColor(getResources().getColor(R.color.amber));
        }
        tvSub.setTextColor(getResources().getColor(speaking ? R.color.teal : R.color.txtDim));

        tvNextSub.setText(conn
                ? (serverQueue.isEmpty() ? "等待取号" : "呼叫 " + cfg.fmt(serverQueue.get(0)))
                : "本机播报");
        tvReplay.setText("重播 " + cfg.fmt(showing));
        btnPrev.setAlpha(1f);

        dot.setBackgroundResource(conn ? R.drawable.dot_green : R.drawable.dot_gray);
        renderQueue();
        renderHistory();
    }

    /** 排队列表：按服务器给的顺序排列，队首 = 下一个要叫的号 */
    private void renderQueue() {
        if (queueRow == null) return;
        queueRow.removeAllViews();

        boolean conn = connected();
        int count = conn ? serverQueue.size() : 0;
        tvQueueTitle.setText(conn ? "排队等待 " + count + " 人" : "排队等待 · 未连接");
        tvQueueNext.setText(conn && count > 0 ? "下一位 " + cfg.fmt(serverQueue.get(0)) : "");

        if (!conn) {
            TextView t = new TextView(this);
            t.setText(hasPeer() ? "连接服务器后显示排队列表" : "单机模式：手动呼号");
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f);
            t.setTextColor(getResources().getColor(R.color.txtDim2));
            queueRow.addView(t);
            return;
        }
        if (count == 0) {
            TextView t = new TextView(this);
            t.setText("暂无排队，等待取号台发号");
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f);
            t.setTextColor(getResources().getColor(R.color.txtDim2));
            queueRow.addView(t);
            return;
        }

        for (int i = 0; i < count && i < 20; i++) {
            final int n = serverQueue.get(i);
            TextView chip = new TextView(this);
            chip.setText(i == 0 ? ("▶ " + cfg.fmt(n)) : cfg.fmt(n));
            chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
            chip.setTypeface(Typeface.MONOSPACE);
            chip.setTextColor(i == 0 ? getResources().getColor(R.color.amber) : 0xFFC9D2EA);
            chip.setBackgroundResource(i == 0 ? R.drawable.chip_now : R.drawable.chip);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            chip.setClickable(true);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { callSpecific(n); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(7);
            queueRow.addView(chip, lp);
        }
        if (count > 20) {
            TextView more = new TextView(this);
            more.setText("+" + (count - 20));
            more.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f);
            more.setTextColor(getResources().getColor(R.color.txtDim2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(7);
            queueRow.addView(more, lp);
        }
    }

    /** 点队列里的某个号直接呼叫（跳过前面的号，应急用） */
    private void callSpecific(int n) {
        haptic();
        if (connected()) {
            try {
                JSONObject o = new JSONObject();
                o.put("t", "call");
                o.put("n", n);
                link.send(o.toString());
            } catch (Exception ignore) { }
            return;
        }
        cfg.offlineCurrent = n;
        cfg.save(this);
        pushHistory(n);
        announceLocal(n);
        render();
    }

    private void renderHistory() {
        historyRow.removeAllViews();
        TextView label = new TextView(this);
        label.setText("最近");
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        label.setTextColor(getResources().getColor(R.color.txtDim));
        historyRow.addView(label);

        ArrayList<Integer> list = new ArrayList<Integer>();
        if (connected() && serverCur > 0) list.add(serverCur);
        for (Integer i : history) if (!list.contains(i)) list.add(i);

        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无播报记录");
            empty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f);
            empty.setTextColor(getResources().getColor(R.color.txtDim2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(8);
            historyRow.addView(empty, lp);
            return;
        }

        for (int i = 0; i < list.size() && i < 10; i++) {
            TextView chip = new TextView(this);
            chip.setText(cfg.fmt(list.get(i)));
            chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f);
            chip.setTypeface(Typeface.MONOSPACE);
            chip.setTextColor(i == 0 ? getResources().getColor(R.color.amber) : 0xFFC9D2EA);
            chip.setBackgroundResource(i == 0 ? R.drawable.chip_now : R.drawable.chip);
            chip.setPadding(dp(10), dp(5), dp(10), dp(5));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(8);
            historyRow.addView(chip, lp);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        if (curToast != null) curToast.cancel();
        curToast = Toast.makeText(this, s, Toast.LENGTH_SHORT);
        curToast.show();
    }

    private Toast curToast;

    /* ================= 回调 ================= */

    @Override
    public void onState(int state, String peer) {
        linkState = state;
        if (state == BtLink.ST_CONNECTING) connectStart = System.currentTimeMillis();
        if (state == BtLink.ST_CONNECTED) {
            link.send("{\"t\":\"hello\"}");
            toast("已连接服务器");
        }
        render();
    }

    @Override
    public void onMessage(String line) {
        try {
            JSONObject o = new JSONObject(line);
            String t = o.optString("t");
            if ("state".equals(t)) {
                int prevCur = serverCur;
                serverCur = o.optInt("cur", 0);
                serverWait = o.optInt("wait", 0);
                serverIssued = o.optInt("issued", 0);
                org.json.JSONArray arr = o.optJSONArray("q");
                serverQueue.clear();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) serverQueue.add(arr.optInt(i));
                }
                boolean ann = o.optInt("ann", 0) == 1;
                if (ann && serverCur > 0) {
                    pushHistory(serverCur);
                    announceLocal(serverCur);
                } else if (serverCur != prevCur && serverCur > 0) {
                    if (history.isEmpty() || history.get(0) != serverCur) pushHistory(serverCur);
                }
                render();
            } else if ("msg".equals(t)) {
                toast(o.optString("m"));
            }
        } catch (Exception ignore) { }
    }

    @Override
    public void onTtsReady(boolean ready, boolean chineseOk) { }

    @Override
    public void onSpeakingChanged(boolean s) {
        speaking = s;
        tvSub.setTextColor(getResources().getColor(s ? R.color.teal : R.color.txtDim));
    }
}
