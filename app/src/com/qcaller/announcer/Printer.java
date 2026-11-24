package com.qcaller.announcer;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * 喵喵机 / Paperang 打印机驱动（经典蓝牙 SPP）。
 * 协议：0x02 | cmd | 包序号 | 长度(LE16) | 数据 | CRC32(LE32) | 0x03
 * 打印内容为 384 点宽单色位图，每行 48 字节，MSB 在前，1 = 黑。
 */
public class Printer {

    public static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final int CMD_PRINT_DATA = 0;
    private static final int CMD_SET_CRC_KEY = 24;
    private static final int CMD_SET_DENSITY = 25;
    private static final int CMD_FEED_LINE = 26;
    private static final int CMD_PRINT_TEST = 27;
    private static final int CMD_SET_PAPER_TYPE = 44;

    private static final long STD_KEY = 0x35769521L;
    private static final long CUSTOM_KEY = 0x6968634L ^ 0x2E696DL;

    public interface Callback {
        void onProgress(String msg);
        void onResult(boolean ok, String msg);
    }

    /* ======================= 对外 ======================= */

    public static void selfTest(final String mac, final Callback cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                BluetoothSocket sock = null;
                try {
                    sock = open(mac, cb, 15000);
                    OutputStream os = sock.getOutputStream();
                    long key = handshake(os, true);
                    writePacket(os, CMD_PRINT_TEST, 0, new byte[]{0}, key);
                    Thread.sleep(800);
                    cb.onResult(true, "已发送自检指令");
                } catch (Exception e) {
                    cb.onResult(false, "失败：" + msg(e));
                } finally {
                    close(sock);
                }
            }
        }, "printer-test").start();
    }

    /** 打印取号单（异步，内部起线程） */
    public static void printTicket(final Context ctx, final Cfg cfg, final int number,
                                   final int ahead, final Callback cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                printTicketSync(ctx, cfg, number, ahead, cb);
            }
        }, "printer-print").start();
    }

    /** 打印取号单（同步执行，供 PrintQueue 串行调用） */
    public static void printTicketSync(Context ctx, Cfg cfg, int number, int ahead, Callback cb) {
        BluetoothSocket sock = null;
        try {
            Bitmap bmp = renderTicket(ctx, cfg, number, ahead);
            byte[] data = toMonoBytes(bmp);
            bmp.recycle();

            cb.onProgress("连接打印机…");
            sock = open(cfg.printerMac, cb);
            OutputStream os = sock.getOutputStream();
            long key = handshake(os, cfg.printerUseKey);

            cb.onProgress("发送打印数据…");
            writePacket(os, CMD_SET_PAPER_TYPE, 0, new byte[]{0}, key);
            writePacket(os, CMD_SET_DENSITY, 0, new byte[]{(byte) cfg.printerDensity}, key);

            int chunk = 2016;
            int idx = 0;
            for (int off = 0; off < data.length; off += chunk) {
                int len = Math.min(chunk, data.length - off);
                byte[] part = new byte[len];
                System.arraycopy(data, off, part, 0, len);
                writePacket(os, CMD_PRINT_DATA, idx++, part, key);
            }
            byte[] feed = new byte[]{(byte) (cfg.feedLines & 0xFF), (byte) ((cfg.feedLines >> 8) & 0xFF)};
            writePacket(os, CMD_FEED_LINE, 0, feed, key);
            os.flush();
            // 等打印机走完纸再断开，否则下一张连上来会把这张打断
            Thread.sleep(2500);
            cb.onResult(true, "号单已打印");
        } catch (Exception e) {
            cb.onResult(false, "打印失败：" + msg(e));
        } finally {
            close(sock);
        }
    }

    /* ======================= 连接 ======================= */

    private static BluetoothSocket open(String mac, Callback cb) throws Exception {
        return open(mac, cb, 0);
    }

    /**
     * @param waitBondMs 未配对时最多等待多久让系统配对框完成（自检场景用，取号场景传 0）
     */
    private static BluetoothSocket open(String mac, Callback cb, int waitBondMs) throws Exception {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) throw new Exception("本机不支持蓝牙");
        if (mac == null || mac.length() == 0) throw new Exception("未设置打印机");
        try { ad.cancelDiscovery(); } catch (Exception ignore) { }

        BluetoothDevice dev = ad.getRemoteDevice(mac);
        // 注意：不要在这里 createBond()。取号时弹系统配对框会把界面顶掉，
        // 用户连续取号就会一直被打断。配对只在设置页「选择打印机」时做。
        if (dev.getBondState() != BluetoothDevice.BOND_BONDED) {
            if (waitBondMs > 0) {
                cb.onProgress("等待系统配对完成…");
                long end = System.currentTimeMillis() + waitBondMs;
                while (System.currentTimeMillis() < end
                        && dev.getBondState() != BluetoothDevice.BOND_BONDED) {
                    Thread.sleep(300);
                }
            }
            if (dev.getBondState() != BluetoothDevice.BOND_BONDED) {
                throw new Exception("打印机未配对，请到设置页重新选择打印机完成配对");
            }
        }

        BluetoothSocket sock = null;
        try {
            sock = dev.createRfcommSocketToServiceRecord(SPP);
            sock.connect();
            return sock;
        } catch (Exception e) {
            close(sock);
        }
        // 常见兼容回退：直接走 RFCOMM 通道 1
        Method m = dev.getClass().getMethod("createRfcommSocket", int.class);
        sock = (BluetoothSocket) m.invoke(dev, 1);
        sock.connect();
        return sock;
    }

    private static long handshake(OutputStream os, boolean useKey) throws Exception {
        if (!useKey) return STD_KEY;
        byte[] p = le32(CUSTOM_KEY ^ STD_KEY);
        writePacket(os, CMD_SET_CRC_KEY, 0, p, STD_KEY);
        Thread.sleep(60);
        return CUSTOM_KEY;
    }

    private static void close(BluetoothSocket s) {
        if (s != null) {
            try { s.close(); } catch (Exception ignore) { }
        }
    }

    private static String msg(Exception e) {
        String m = e.getMessage();
        if (m == null || m.length() == 0) m = e.getClass().getSimpleName();
        return m;
    }

    /* ======================= 协议 ======================= */

    private static void writePacket(OutputStream os, int cmd, int index, byte[] payload, long key)
            throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream(payload.length + 10);
        b.write(0x02);
        b.write(cmd & 0xFF);
        b.write(index & 0xFF);
        b.write(payload.length & 0xFF);
        b.write((payload.length >> 8) & 0xFF);
        b.write(payload, 0, payload.length);
        long c = crc32(payload, key);
        b.write((int) (c & 0xFF));
        b.write((int) ((c >> 8) & 0xFF));
        b.write((int) ((c >> 16) & 0xFF));
        b.write((int) ((c >> 24) & 0xFF));
        b.write(0x03);
        os.write(b.toByteArray());
        os.flush();
        Thread.sleep(12);
    }

    private static long crc32(byte[] data, long init) {
        long crc = (init ^ 0xFFFFFFFFL) & 0xFFFFFFFFL;
        for (int i = 0; i < data.length; i++) {
            crc ^= (data[i] & 0xFFL);
            for (int k = 0; k < 8; k++) {
                if ((crc & 1L) != 0) crc = (crc >>> 1) ^ 0xEDB88320L;
                else crc >>>= 1;
            }
        }
        return (crc ^ 0xFFFFFFFFL) & 0xFFFFFFFFL;
    }

    private static byte[] le32(long v) {
        return new byte[]{(byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF),
                (byte) ((v >> 16) & 0xFF), (byte) ((v >> 24) & 0xFF)};
    }

    /** 位图 → 单色数据（384 宽，每行 48 字节） */
    public static byte[] toMonoBytes(Bitmap bmp) {
        int w = Math.min(384, bmp.getWidth());
        int h = bmp.getHeight();
        byte[] out = new byte[48 * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < 384; x++) {
                boolean black = false;
                if (x < w) {
                    int px = bmp.getPixel(x, y);
                    int lum = (int) (0.299 * Color.red(px) + 0.587 * Color.green(px) + 0.114 * Color.blue(px));
                    black = lum < 140;
                }
                if (black) out[y * 48 + (x >> 3)] |= (byte) (0x80 >> (x & 7));
            }
        }
        return out;
    }

    /* ======================= 排版 ======================= */

    public static Bitmap renderTicket(Context ctx, Cfg cfg, int number, int ahead) {
        int W = 384;
        int H = 400;
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        // 标题
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(26);
        c.drawText("叫 号 单", W / 2, 42, p);

        // 虚线
        Paint line = new Paint();
        line.setColor(Color.BLACK);
        line.setStrokeWidth(2);
        for (int x = 16; x < W - 16; x += 12) c.drawLine(x, 58, x + 6, 58, line);

        // 号码
        p.setTextSize(96);
        String num = cfg.fmt(number);
        c.drawText(num, W / 2, 168, p);

        for (int x = 16; x < W - 16; x += 12) c.drawLine(x, 190, x + 6, 190, line);

        // 信息
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(24);
        p.setTypeface(Typeface.DEFAULT);
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        c.drawText("取号时间", 24, 232, p);
        c.drawText(sdf.format(new Date()), 190, 232, p);

        c.drawText("前面等待", 24, 274, p);
        c.drawText(ahead + " 位", 190, 274, p);

        p.setTextSize(21);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("请留意语音叫号，过号请重新取号", W / 2, 322, p);

        // 底部留白
        return bmp;
    }
}
