package com.qcaller.announcer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * 打印任务串行队列。
 *
 * 取号是纯本地操作，必须立即完成并返回号码；号单只是「顺带」打印。
 * 所有打印任务都丢进这个队列，由单独一个线程一张一张串行打印，
 * 这样打印机慢、连不上、蓝牙被占用都不会影响继续取号，也不会因为连点时
 * 同时开好几个 RFCOMM 连接而把蓝牙栈卡死。
 */
public class PrintQueue {

    public interface Status {
        /** 结果回调，保证在主线程执行 */
        void onStatus(boolean ok, String msg, int pending);
    }

    private static class Job {
        Cfg cfg;
        int number;
        int ahead;
        Status status;
    }

    private static final LinkedBlockingQueue<Job> QUEUE = new LinkedBlockingQueue<Job>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context appCtx;
    private static Thread worker;
    /** 打印失败后的冷却截止时间，避免打印机断电时每张号单都卡 20 秒连接超时 */
    private static volatile long nextTryAt = 0;

    /** 入队一张号单，立刻返回，不阻塞界面 */
    public static void enqueue(Context c, Cfg cfg, int number, int ahead, final Status st) {
        if (c != null) appCtx = c.getApplicationContext();
        if (System.currentTimeMillis() < nextTryAt) {
            if (st != null) {
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        st.onStatus(false, "打印机暂时连不上，本张号单未打印", 0);
                    }
                });
            }
            return;
        }
        Job j = new Job();
        j.cfg = cfg;
        j.number = number;
        j.ahead = ahead;
        j.status = st;
        QUEUE.offer(j);
        startWorker();
    }

    /** 队列里还有多少张未打印 */
    public static int pending() {
        return QUEUE.size();
    }

    /** 清空尚未打印的任务（重置时用） */
    public static void clear() {
        QUEUE.clear();
    }

    private static synchronized void startWorker() {
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(new Runnable() {
            @Override public void run() {
                int failStreak = 0;
                while (true) {
                    Job job;
                    try {
                        job = QUEUE.take();
                    } catch (InterruptedException e) {
                        return;
                    }
                    final Job fjob = job;
                    if (fjob.status != null) {
                        MAIN.post(new Runnable() {
                            @Override public void run() {
                                fjob.status.onStatus(true, "打印中 " + fjob.cfg.fmt(fjob.number), QUEUE.size());
                            }
                        });
                    }

                    final boolean[] ok = new boolean[]{false};
                    final String[] msg = new String[]{""};
                    try {
                        Printer.printTicketSync(appCtx, fjob.cfg, fjob.number, fjob.ahead,
                                new Printer.Callback() {
                                    @Override public void onProgress(String s) { }
                                    @Override public void onResult(boolean success, String s) {
                                        ok[0] = success;
                                        msg[0] = s;
                                    }
                                });
                    } catch (Throwable t) {
                        ok[0] = false;
                        String m = t.getMessage();
                        msg[0] = "打印失败：" + (m == null ? t.getClass().getSimpleName() : m);
                    }

                    final boolean fok = ok[0];
                    final String fmsg = msg[0];
                    if (fok) {
                        failStreak = 0;
                        nextTryAt = 0;
                    } else {
                        failStreak++;
                        // 失败后冷却 30 秒，期间取号只发号、不再反复连打印机
                        nextTryAt = System.currentTimeMillis() + 30000;
                    }

                    // 打印机没开机/没配对时，一张要卡十几秒。连续失败两次就放弃
                    // 后面排队的号单，避免用户取号后界面一直挂着「正在连接打印机」。
                    int dropped = 0;
                    if (!fok && failStreak >= 2 && !QUEUE.isEmpty()) {
                        dropped = QUEUE.size();
                        QUEUE.clear();
                        failStreak = 0;
                    }
                    final int fdrop = dropped;
                    if (fjob.status != null) {
                        MAIN.post(new Runnable() {
                            @Override public void run() {
                                if (fdrop > 0) {
                                    fjob.status.onStatus(false, "打印机无响应，已跳过 " + fdrop + " 张号单", 0);
                                } else {
                                    fjob.status.onStatus(fok, fmsg, QUEUE.size());
                                }
                            }
                        });
                    }

                    // 关键：两张号单之间留出间隔。喵喵机走纸慢，上一张还没吐完就
                    // 发起下一张会直接打印失败（白纸/乱码/半张）。
                    if (!QUEUE.isEmpty()) {
                        int gap = fjob.cfg == null ? 5000 : fjob.cfg.printGapMs;
                        if (gap > 0) {
                            try {
                                Thread.sleep(gap);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                    }
                }
            }
        }, "print-queue");
        worker.start();
    }
}
