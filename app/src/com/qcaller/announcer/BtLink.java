package com.qcaller.announcer;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 手机之间的蓝牙数据通道（RFCOMM / SPP），按行传输 JSON。
 *
 * 服务器端可以同时挂多台叫号端（默认最多 4 台）：每台一个 socket、一个读线程，
 * 服务器发出的每条状态会广播给所有在线终端，任意终端发出的指令都由服务器统一处理。
 */
public class BtLink {

    public static final UUID LINK_UUID = UUID.fromString("8f6a1b20-7c4d-4e93-9e51-2b7a5c9d1e33");
    public static final String SERVICE_NAME = "QueueCallerLink";

    public static final int ST_DISCONNECTED = 0;
    public static final int ST_CONNECTING = 1;
    public static final int ST_CONNECTED = 2;
    public static final int ST_LISTENING = 3;

    /** 最多同时接入的叫号端数量 */
    public static final int MAX_PEERS = 4;

    public interface Listener {
        void onState(int state, String peer);
        void onMessage(String line);
    }

    /** 一台已接入的对端 */
    private static class Peer {
        BluetoothSocket socket;
        OutputStream out;
        String name = "";
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private BluetoothServerSocket serverSocket;
    private volatile boolean running = false;

    private final List<Peer> peers = new CopyOnWriteArrayList<Peer>();

    // 发送队列：蓝牙写操作可能因为信号差/对端不读而阻塞，
    // 绝不能在主线程直接 write，否则整个界面会「点了没反应」。
    private final LinkedBlockingQueue<String> TX = new LinkedBlockingQueue<String>();
    private Thread txThread;

    public BtLink(Listener l) { listener = l; }

    public void setListener(Listener l) { listener = l; }

    public boolean isConnected() { return !peers.isEmpty(); }

    public int peerCount() { return peers.size(); }

    /** 服务器端是否正在等待接入 */
    public boolean isListening() {
        return running && serverSocket != null;
    }

    /* ---------------- 服务器端 ---------------- */

    public void startServer() {
        stop();
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) { postState(ST_DISCONNECTED, ""); return; }
        running = true;
        try {
            serverSocket = ad.listenUsingRfcommWithServiceRecord(SERVICE_NAME, LINK_UUID);
        } catch (Exception e) {
            postState(ST_DISCONNECTED, "");
            return;
        }
        postState(ST_LISTENING, "");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    while (running) {
                        BluetoothSocket s = serverSocket.accept();
                        if (s == null) continue;
                        if (peers.size() >= MAX_PEERS) {
                            closeQuietly(s);
                            continue;
                        }
                        attach(s);
                        // 不退出循环：任意终端断开后都还能被重新连上
                    }
                } catch (Exception ignore) {
                } finally {
                    closeQuietly(serverSocket);
                    serverSocket = null;
                }
            }
        }, "bt-accept").start();
    }

    /* ---------------- 叫号端 ---------------- */

    public void connect(final String mac) {
        stop();
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null || mac == null || mac.length() == 0) {
            postState(ST_DISCONNECTED, "");
            return;
        }
        running = true;
        postState(ST_CONNECTING, "");
        new Thread(new Runnable() {
            @Override public void run() {
                BluetoothSocket s = null;
                try {
                    ad.cancelDiscovery();
                    BluetoothDevice dev = ad.getRemoteDevice(mac);
                    if (dev.getBondState() != BluetoothDevice.BOND_BONDED) {
                        try { dev.createBond(); } catch (Exception ignore) { }
                        for (int i = 0; i < 40 && dev.getBondState() == BluetoothDevice.BOND_BONDING; i++) {
                            Thread.sleep(500);
                        }
                    }
                    try {
                        s = dev.createRfcommSocketToServiceRecord(LINK_UUID);
                        s.connect();
                    } catch (Exception e) {
                        closeQuietly(s);
                        s = null;
                    }
                    if (s == null) {
                        Method m = dev.getClass().getMethod("createRfcommSocket", int.class);
                        s = (BluetoothSocket) m.invoke(dev, 1);
                        s.connect();
                    }
                    attach(s);
                } catch (Exception e) {
                    closeQuietly(s);
                    postState(ST_DISCONNECTED, "");
                }
            }
        }, "bt-connect").start();
    }

    /* ---------------- 公共 ---------------- */

    private void attach(final BluetoothSocket s) {
        final Peer p = new Peer();
        p.socket = s;
        try {
            p.out = s.getOutputStream();
            p.name = safeName(s.getRemoteDevice());
        } catch (Exception e) {
            closeQuietly(s);
            if (peers.isEmpty()) postState(ST_DISCONNECTED, "");
            return;
        }
        peers.add(p);
        postState(ST_CONNECTED, label());

        new Thread(new Runnable() {
            @Override public void run() {
                BufferedReader r = null;
                try {
                    r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.length() > 0) postMessage(line);
                    }
                } catch (Exception ignore) {
                } finally {
                    if (r != null) { try { r.close(); } catch (Exception ignore) { } }
                    closeQuietly(s);
                    peers.remove(p);
                    if (running) {
                        postState(peers.isEmpty() ? ST_DISCONNECTED : ST_CONNECTED, label());
                    }
                }
            }
        }, "bt-read-" + p.name).start();
    }

    private String label() {
        int n = peers.size();
        if (n == 0) return "";
        if (n == 1) return peers.get(0).name;
        return n + " 台终端";
    }

    /** 广播给所有已连接的终端 */
    public boolean send(String json) {
        if (peers.isEmpty()) return false;
        TX.offer(json);
        startTx();
        return true;
    }

    private synchronized void startTx() {
        if (txThread != null && txThread.isAlive()) return;
        txThread = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    String s;
                    try {
                        s = TX.take();
                    } catch (InterruptedException e) {
                        return;
                    }
                    byte[] data = (s + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    for (Peer p : peers) {
                        try {
                            OutputStream o = p.out;
                            if (o == null) continue;
                            o.write(data);
                            o.flush();
                        } catch (Exception e) {
                            // 这台终端掉了，移除，其他终端不受影响
                            closeQuietly(p.socket);
                            peers.remove(p);
                        }
                    }
                }
            }
        }, "bt-tx");
        txThread.start();
    }

    public void stop() {
        running = false;
        TX.clear();
        closeQuietly(serverSocket);
        serverSocket = null;
        for (Peer p : peers) closeQuietly(p.socket);
        peers.clear();
    }

    private void closeQuietly(BluetoothServerSocket s) {
        if (s != null) { try { s.close(); } catch (Exception ignore) { } }
    }

    private void closeQuietly(BluetoothSocket s) {
        if (s != null) { try { s.close(); } catch (Exception ignore) { } }
    }

    private String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? d.getAddress() : n;
        } catch (Exception e) {
            return "";
        }
    }

    private void postState(final int st, final String peer) {
        main.post(new Runnable() {
            @Override public void run() { if (listener != null) listener.onState(st, peer); }
        });
    }

    private void postMessage(final String line) {
        main.post(new Runnable() {
            @Override public void run() { if (listener != null) listener.onMessage(line); }
        });
    }
}
