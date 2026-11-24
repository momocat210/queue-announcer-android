package com.qcaller.announcer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** 服务器端：取号、打印、队列、播报，并接受叫号端指令 */
public class ServerActivity extends Activity implements BtLink.Listener, Speaker.Listener {

    private Cfg cfg;
    private QueueStore q;
    private Speaker speaker;
    private BtLink link;

    private TextView tvNum, tvLabel, tvSub, tvNextSub, tvTakeSub, tvPrinter, tvQueueTitle, badgeLink;
    private View dot;
    private LinearLayout queueList;
    private android.widget.ScrollView queueScroll;

    private boolean ttsOk = false;
    private int linkState = BtLink.ST_DISCONNECTED;
    private boolean destroyed = false;

    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());

    /** 守护：蓝牙晚开后、监听被意外关闭后能自动恢复 */
    private final Runnable guard = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (link != null && !link.isListening() && !link.isConnected()) {
                link.startServer();
            }
            h.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_server);

        cfg = Cfg.load(this);
        q = QueueStore.load(this);

        tvNum = (TextView) findViewById(R.id.tvNum);
        tvLabel = (TextView) findViewById(R.id.tvLabel);
        tvSub = (TextView) findViewById(R.id.tvSub);
        tvNextSub = (TextView) findViewById(R.id.tvNextSub);
        tvTakeSub = (TextView) findViewById(R.id.tvTakeSub);
        tvPrinter = (TextView) findViewById(R.id.tvPrinter);
        tvQueueTitle = (TextView) findViewById(R.id.tvQueueTitle);
        badgeLink = (TextView) findViewById(R.id.badgeLink);
        dot = findViewById(R.id.dot);
        queueList = (LinearLayout) findViewById(R.id.queueList);
        queueScroll = (android.widget.ScrollView) findViewById(R.id.queueScroll);

        findViewById(R.id.btnTake).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doTake(); }
        });
        findViewById(R.id.btnNext).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doNext(); }
        });
        findViewById(R.id.btnReplay).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doReplay(); }
        });
        findViewById(R.id.btnDone).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doDone(); }
        });
        findViewById(R.id.btnSkip).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doSkip(); }
        });
        findViewById(R.id.btnSet).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(ServerActivity.this, SettingsActivity.class));
            }
        });

        speaker = new Speaker(this, this);
        link = new BtLink(this);
        link.startServer();

        applyAwake();
        refresh();
        h.postDelayed(guard, 5000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        cfg = Cfg.load(this);
        // 设置页可能重置了队列，这里必须重新读取，否则界面还是内存里的旧数据
        q = QueueStore.load(this);
        applyAwake();
        refresh();
        broadcastState(false);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        h.removeCallbacksAndMessages(null);
        if (link != null) link.stop();
        if (speaker != null) speaker.shutdown();
        super.onDestroy();
    }

    private void applyAwake() {
        if (cfg.awake) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /* ================= 业务 ================= */

    /**
     * 取号：纯本地操作，立即发号、立即入队、立即刷新界面。
     * 打印只是顺带，丢进 PrintQueue 串行处理，绝不会挡住下一次取号。
     */
    private void doTake() {
        final int n = q.take(cfg.start);
        q.save(this);
        refresh();
        pulseTake();
        int ahead = Math.max(0, q.waitCount() - 1);
        toast("已取号 " + cfg.fmt(n) + (ahead > 0 ? "，前面 " + ahead + " 位" : ""));

        boolean canPrint = cfg.printerEnabled && cfg.autoPrint
                && cfg.printerMac != null && cfg.printerMac.length() > 0;
        if (canPrint) {
            PrintQueue.enqueue(this, cfg, n, ahead, new PrintQueue.Status() {
                @Override public void onStatus(boolean ok, String msg, int pending) {
                    tvPrinter.setText(ok ? ("已打印 " + cfg.fmt(n)
                            + (pending > 0 ? " · 待打 " + pending + " 张" : "")) : msg);
                }
            });
        }
        broadcastState(false);
    }

    /** 取号按钮点击反馈，让连续点击也能看清每次是否生效 */
    private void pulseTake() {
        final View b = findViewById(R.id.btnTake);
        if (b == null) return;
        b.animate().cancel();
        b.setScaleX(0.95f);
        b.setScaleY(0.95f);
        b.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
    }

    private void doNext() {
        int n = q.callNext();
        if (n < 0) {
            String m = q.issued == 0 ? "还没有人取号，请先取号" : "队列已叫完，等待新号码";
            toast(m);
            try {
                link.send(new JSONObject().put("t", "msg").put("m", m).toString());
            } catch (Exception ignore) { }
            return;
        }
        q.save(this);
        refresh();
        callOut(n);
    }

    private void doReplay() {
        if (q.current <= 0) { toast("还没有叫号"); return; }
        callOut(q.current);
    }

    private void doDone() {
        if (q.current <= 0) { toast("当前没有办理中的号码"); return; }
        q.complete();
        q.save(this);
        refresh();
        broadcastState(false);
        toast("已办结");
    }

    private void doSkip() {
        if (q.current <= 0) { toast("当前没有办理中的号码"); return; }
        q.backToQueue();
        q.save(this);
        refresh();
        broadcastState(false);
        toast("已过号，号码回到队首");
    }

    private void callNum(int n) {
        q.callNumber(n);
        q.save(this);
        refresh();
        callOut(n);
    }

    private void doPrev() {
        // 回退到上一个「真正叫过」的号码，而不是简单地把数字 -1
        int target = 0;
        if (q.current > 0) {
            for (int i = 0; i < q.called.size(); i++) {
                int v = q.called.get(i);
                if (v != q.current && v > 0) { target = v; break; }
            }
        } else if (!q.called.isEmpty()) {
            target = q.called.get(0);
        }
        if (target <= 0) { toast("没有上一个号码"); return; }
        callNum(target);
    }

    /** 喊号：按设置决定由本机播报还是让叫号端播报 */
    private void callOut(int n) {
        boolean byServer = "server".equals(cfg.broadcast) || "both".equals(cfg.broadcast);
        if (byServer && speaker != null) {
            speaker.speak(cfg.buildText(n), cfg.repeat, cfg.rate, cfg.volume, cfg.chime);
        }
        broadcastState(true);
    }

    private void broadcastState(boolean justCalled) {
        try {
            boolean callerAnnounces = "caller".equals(cfg.broadcast) || "both".equals(cfg.broadcast);
            JSONObject o = new JSONObject();
            o.put("t", "state");
            o.put("cur", q.current);
            o.put("wait", q.waitCount());
            o.put("issued", q.issued);
            o.put("done", q.doneCount);
            o.put("ann", (justCalled && callerAnnounces) ? 1 : 0);
            // 把完整排队顺序带给叫号端，叫号端按这个顺序逐个叫
            JSONArray arr = new JSONArray();
            for (int i = 0; i < q.waiting.size() && i < 50; i++) {
                arr.put(q.waiting.get(i).intValue());
            }
            o.put("q", arr);
            link.send(o.toString());
        } catch (Exception ignore) { }
    }

    private void printTicket(final int n) {
        if (cfg.printerMac == null || cfg.printerMac.length() == 0) {
            toast("未设置打印机，跳过打印");
            return;
        }
        PrintQueue.enqueue(this, cfg, n, Math.max(0, q.waitCount() - 1), new PrintQueue.Status() {
            @Override public void onStatus(boolean ok, String msg, int pending) {
                tvPrinter.setText(ok ? ("已打印 " + cfg.fmt(n)) : msg);
            }
        });
    }

    /* ================= 界面 ================= */

    private void refresh() {
        // 界面刷新出错绝不能让 App 退出（之前就是这里抛异常导致「取号后闪退」）
        try {
            renderAll();
        } catch (Throwable t) {
            try {
                tvPrinter.setText("界面刷新异常：" + t.getClass().getSimpleName());
            } catch (Throwable ignore) { }
        }
    }

    private void renderAll() {
        boolean has = q.current > 0;
        tvNum.setText(has ? cfg.fmt(q.current) : "---");
        tvLabel.setText(has ? "当 前 叫 号" : "等 待 叫 号");
        tvSub.setText("等待 " + q.waitCount() + " 人 · 已取号 " + q.issued + " · 已办结 " + q.doneCount);
        tvNextSub.setText("播报 " + cfg.fmt(q.waiting.isEmpty() ? Math.max(q.issued + 1, cfg.start) : q.waiting.get(0)));
        tvTakeSub.setText("下一个 " + cfg.fmt(q.issued + 1));

        int pendingPrint = PrintQueue.pending();
        if (pendingPrint > 0) {
            tvPrinter.setText("待打印 " + pendingPrint + " 张…");
        } else {
            tvPrinter.setText(cfg.printerName != null && cfg.printerName.length() > 0
                    ? "打印机：" + cfg.printerName : "打印机未设置");
        }

        tvQueueTitle.setText("等待队列 " + q.waitCount() + " 人");
        dot.setBackgroundResource(linkState == BtLink.ST_CONNECTED ? R.drawable.dot_green : R.drawable.dot_gray);

        if (linkState == BtLink.ST_CONNECTED) {
            int n = link == null ? 0 : link.peerCount();
            badgeLink.setText(n > 1 ? "叫号端 " + n + " 台在线" : "叫号端已连接");
            badgeLink.setBackgroundResource(R.drawable.badge_ok);
            badgeLink.setTextColor(getResources().getColor(R.color.teal));
        } else {
            badgeLink.setText("叫号端未连接");
            badgeLink.setBackgroundResource(R.drawable.badge_off);
            badgeLink.setTextColor(getResources().getColor(R.color.txtDim));
        }

        renderQueue();
    }

    private void renderQueue() {
        queueList.removeAllViews();
        if (q.waiting.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("队列为空，点「取号」发号");
            empty.setTextSize(12);
            empty.setTextColor(getResources().getColor(R.color.txtDim2));
            empty.setPadding(dp(4), dp(8), dp(4), dp(8));
            queueList.addView(empty);
            return;
        }
        int total = q.waiting.size();
        int limit = Math.min(30, total);
        for (int i = 0; i < limit; i++) {
            final int n = q.waiting.get(i);
            TextView row = new TextView(this);
            String tag = i == 0 ? "  下一位" : "";
            row.setText((i + 1) + ".  " + cfg.fmt(n) + tag + "        点击呼叫");
            row.setTextSize(14);
            row.setTypeface(android.graphics.Typeface.MONOSPACE);
            row.setTextColor(i == 0
                    ? getResources().getColor(R.color.amber)
                    : 0xFFC9D2EA);
            row.setBackgroundResource(i == 0 ? R.drawable.item_queue_now : R.drawable.item_queue);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(13), dp(8), dp(13), dp(8));
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { callNum(n); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            queueList.addView(row, lp);
        }
        if (total > limit) {
            TextView more = new TextView(this);
            more.setText("… 还有 " + (total - limit) + " 人");
            more.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f);
            more.setTextColor(getResources().getColor(R.color.txtDim2));
            more.setPadding(dp(4), dp(2), dp(4), dp(6));
            queueList.addView(more);
        }
        // 新号追加在队尾，自动滚到底部让取号的人看到自己的号
        if (queueScroll != null) {
            queueScroll.post(new Runnable() {
                @Override public void run() {
                    if (queueScroll != null) queueScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        // 连续取号时 Toast 会排队堆叠，看上去像卡住；只保留最新一条
        if (curToast != null) curToast.cancel();
        curToast = Toast.makeText(this, s, Toast.LENGTH_SHORT);
        curToast.show();
    }

    private Toast curToast;

    /* ================= 回调 ================= */

    @Override
    public void onState(int state, String peer) {
        linkState = state;
        refresh();
        if (state == BtLink.ST_CONNECTED) {
            toast("叫号端已连接：" + peer);
            broadcastState(false);
        }
    }

    @Override
    public void onMessage(String line) {
        try {
            JSONObject o = new JSONObject(line);
            String t = o.optString("t");
            if ("next".equals(t)) doNext();
            else if ("replay".equals(t)) doReplay();
            else if ("prev".equals(t)) doPrev();
            else if ("done".equals(t)) doDone();
            else if ("call".equals(t)) {
                int n = o.optInt("n", 0);
                if (n > 0) callNum(n);
            } else if ("hello".equals(t)) {
                broadcastState(false);
            } else if ("ping".equals(t)) {
                link.send("{\"t\":\"pong\"}");
            }
        } catch (Exception ignore) { }
    }

    @Override
    public void onTtsReady(boolean ready, boolean chineseOk) {
        ttsOk = ready;
        if (!ready) dot.setBackgroundResource(R.drawable.dot_gray);
    }

    @Override
    public void onSpeakingChanged(boolean speaking) {
        tvSub.setTextColor(getResources().getColor(speaking ? R.color.teal : R.color.txtDim));
    }
}
