package com.qcaller.announcer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

public class SettingsActivity extends Activity implements Speaker.Listener {

    private Cfg cfg;
    private Speaker speaker;
    private boolean binding = false;

    private EditText etTemplate, etExtra, etStart;
    private RadioGroup rgRole, rgBroadcast, rgDigits, rgRead;
    private SeekBar sbRate, sbVolume;
    private TextView tvRateLabel, tvVolLabel, tvEngine, tvLinkState, tvPrinter;
    private Switch swChime, swAwake, swAutoPrint, swPrinterCompat;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ArrayList<BluetoothDevice> found = new ArrayList<BluetoothDevice>();
    private boolean pickingPrinter = false;
    private LinearLayout deviceListBox;
    private AlertDialog deviceDialog;
    private boolean receiverOn = false;

    private final BroadcastReceiver discovery = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(a)) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null && !found.contains(d)) {
                    found.add(d);
                    fillDeviceList();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        cfg = Cfg.load(this);

        etTemplate = (EditText) findViewById(R.id.etTemplate);
        etExtra = (EditText) findViewById(R.id.etExtra);
        etStart = (EditText) findViewById(R.id.etStart);
        rgRole = (RadioGroup) findViewById(R.id.rgRole);
        rgBroadcast = (RadioGroup) findViewById(R.id.rgBroadcast);
        rgDigits = (RadioGroup) findViewById(R.id.rgDigits);
        rgRead = (RadioGroup) findViewById(R.id.rgRead);
        sbRate = (SeekBar) findViewById(R.id.sbRate);
        sbVolume = (SeekBar) findViewById(R.id.sbVolume);
        tvRateLabel = (TextView) findViewById(R.id.tvRateLabel);
        tvVolLabel = (TextView) findViewById(R.id.tvVolLabel);
        tvEngine = (TextView) findViewById(R.id.tvEngine);
        tvLinkState = (TextView) findViewById(R.id.tvLinkState);
        tvPrinter = (TextView) findViewById(R.id.tvPrinter);
        swChime = (Switch) findViewById(R.id.swChime);
        swAwake = (Switch) findViewById(R.id.swAwake);
        swAutoPrint = (Switch) findViewById(R.id.swAutoPrint);
        swPrinterCompat = (Switch) findViewById(R.id.swPrinterCompat);

        speaker = new Speaker(this, this);
        bindForm();
        wire();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStateText();
    }

    @Override
    protected void onDestroy() {
        if (receiverOn) {
            try { unregisterReceiver(discovery); } catch (Exception ignore) { }
            receiverOn = false;
        }
        if (speaker != null) speaker.shutdown();
        super.onDestroy();
    }

    /* ================= 表单 ================= */

    private void bindForm() {
        binding = true;
        etTemplate.setText(cfg.template);
        etExtra.setText(cfg.extra);
        etStart.setText(String.valueOf(cfg.start));

        rgRole.check("server".equals(cfg.role) ? R.id.rbServer : R.id.rbCaller);
        rgBroadcast.check("caller".equals(cfg.broadcast) ? R.id.rbBsCaller
                : ("both".equals(cfg.broadcast) ? R.id.rbBsBoth : R.id.rbBsServer));
        rgDigits.check(cfg.digits == 2 ? R.id.rbD2 : (cfg.digits == 4 ? R.id.rbD4 : R.id.rbD3));
        rgRead.check("value".equals(cfg.readMode) ? R.id.rbReadValue : R.id.rbReadDigit);

        sbRate.setMax(80);
        sbRate.setProgress(Math.round((cfg.rate - 0.6f) * 100f));
        sbVolume.setProgress(Math.round(cfg.volume * 100f));
        tvRateLabel.setText(String.format(Locale.US, "语速 %.2f", cfg.rate));
        tvVolLabel.setText("音量 " + Math.round(cfg.volume * 100) + "%");

        swChime.setChecked(cfg.chime);
        swAwake.setChecked(cfg.awake);
        swAutoPrint.setChecked(cfg.autoPrint);
        swPrinterCompat.setChecked(!cfg.printerUseKey);
        binding = false;

        refreshStateText();
    }

    private void refreshStateText() {
        if (cfg.peerMac != null && cfg.peerMac.length() > 0) {
            tvLinkState.setText("已选择：" + cfg.peerName + "\n" + cfg.peerMac);
        } else {
            tvLinkState.setText("未选择对方手机。两机需先在系统蓝牙中配对。");
        }
        if (cfg.printerMac != null && cfg.printerMac.length() > 0) {
            tvPrinter.setText("已选择：" + cfg.printerName + "\n" + cfg.printerMac);
        } else {
            tvPrinter.setText("未选择打印机");
        }
    }

    private void wire() {
        etTemplate.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                if (binding) return;
                String v = s.toString().trim();
                cfg.template = v.length() == 0 ? "请{num}号办理业务" : v;
                cfg.save(SettingsActivity.this);
            }
        });
        etExtra.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                if (binding) return;
                cfg.extra = s.toString().trim();
                cfg.save(SettingsActivity.this);
            }
        });
        etStart.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                if (binding) return;
                String v = s.toString().trim();
                if (v.length() == 0) return;
                try {
                    cfg.start = Math.max(0, Integer.parseInt(v));
                    cfg.save(SettingsActivity.this);
                } catch (Exception ignore) { }
            }
        });

        rgRole.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup g, int id) {
                if (binding) return;
                String r = id == R.id.rbServer ? "server" : "caller";
                if (r.equals(cfg.role)) return;
                cfg.role = r;
                cfg.save(SettingsActivity.this);
                Intent i = new Intent(SettingsActivity.this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                finish();
            }
        });

        rgBroadcast.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup g, int id) {
                if (binding) return;
                cfg.broadcast = id == R.id.rbBsCaller ? "caller" : (id == R.id.rbBsBoth ? "both" : "server");
                cfg.save(SettingsActivity.this);
            }
        });

        rgDigits.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup g, int id) {
                if (binding) return;
                cfg.digits = id == R.id.rbD2 ? 2 : (id == R.id.rbD4 ? 4 : 3);
                cfg.save(SettingsActivity.this);
            }
        });

        rgRead.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup g, int id) {
                if (binding) return;
                cfg.readMode = id == R.id.rbReadValue ? "value" : "digit";
                cfg.save(SettingsActivity.this);
            }
        });

        sbRate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                cfg.rate = 0.6f + p / 100f;
                tvRateLabel.setText(String.format(Locale.US, "语速 %.2f", cfg.rate));
                if (!binding) cfg.save(SettingsActivity.this);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        sbVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                cfg.volume = Math.max(0.2f, p / 100f);
                tvVolLabel.setText("音量 " + p + "%");
                if (!binding) cfg.save(SettingsActivity.this);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        swChime.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton v, boolean c) {
                if (binding) return;
                cfg.chime = c;
                cfg.save(SettingsActivity.this);
            }
        });
        swAwake.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton v, boolean c) {
                if (binding) return;
                cfg.awake = c;
                cfg.save(SettingsActivity.this);
            }
        });
        swAutoPrint.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton v, boolean c) {
                if (binding) return;
                cfg.autoPrint = c;
                cfg.save(SettingsActivity.this);
            }
        });
        swPrinterCompat.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton v, boolean c) {
                if (binding) return;
                cfg.printerUseKey = !c;
                cfg.save(SettingsActivity.this);
            }
        });

        findViewById(R.id.btnPickPeer).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showDevicePicker(false); }
        });
        findViewById(R.id.btnPickPrinter).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showDevicePicker(true); }
        });
        findViewById(R.id.btnTestPrint).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { testPrint(); }
        });
        findViewById(R.id.btnResetQueue).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { resetQueue(); }
        });
        findViewById(R.id.btnPreview).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!speaker.isReady()) { toast("语音引擎初始化中…"); return; }
                speaker.speak(cfg.buildText(cfg.start), cfg.repeat, cfg.rate, cfg.volume, cfg.chime);
            }
        });
        findViewById(R.id.btnDefaults).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cfg.resetToDefaults();
                bindForm();
                cfg.save(SettingsActivity.this);
                toast("已恢复默认设置");
            }
        });
    }

    /* ================= 设备选择 ================= */

    private void showDevicePicker(boolean printer) {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) { toast("本机不支持蓝牙"); return; }
        if (!ad.isEnabled()) { toast("请先打开蓝牙"); return; }
        if (!ensurePerm()) return;

        pickingPrinter = printer;
        found.clear();

        deviceListBox = new LinearLayout(this);
        deviceListBox.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(6);
        deviceListBox.setPadding(pad, pad, pad, pad);

        ScrollView sv = new ScrollView(this);
        sv.addView(deviceListBox);

        deviceDialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(printer ? "选择打印机（喵喵机）" : "选择对方手机")
                .setView(sv)
                .setNegativeButton("关闭", null)
                .create();
        deviceDialog.show();

        fillDeviceList();

        if (!receiverOn) {
            IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            registerReceiver(discovery, f);
            receiverOn = true;
        }
        try { ad.startDiscovery(); } catch (Exception ignore) { }
    }

    private void fillDeviceList() {
        if (deviceListBox == null) return;
        deviceListBox.removeAllViews();

        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) return;

        ArrayList<BluetoothDevice> all = new ArrayList<BluetoothDevice>();
        try {
            Set<BluetoothDevice> bonded = ad.getBondedDevices();
            if (bonded != null) all.addAll(bonded);
        } catch (Exception ignore) { }
        for (BluetoothDevice d : found) if (!all.contains(d)) all.add(d);

        if (all.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("没有找到设备，请先在系统蓝牙里配对，或等待搜索…");
            t.setTextColor(0xFF8B95B2);
            t.setTextSize(13);
            t.setPadding(dp(10), dp(10), dp(10), dp(10));
            deviceListBox.addView(t);
            return;
        }

        for (final BluetoothDevice d : all) {
            TextView row = new TextView(this);
            String name;
            try { name = d.getName(); } catch (Exception e) { name = null; }
            if (name == null || name.length() == 0) name = "未知设备";
            boolean bonded;
            String addr;
            try {
                bonded = d.getBondState() == BluetoothDevice.BOND_BONDED;
                addr = d.getAddress();
            } catch (Exception e) {
                bonded = false;
                addr = "";
            }
            row.setText(name + (bonded ? "  ·  已配对" : "  ·  未配对") + "\n" + addr);
            row.setTextSize(14);
            row.setLineSpacing(dp(2), 1f);
            row.setTextColor(bonded ? 0xFFEEF2FF : 0xFF9AA4C0);
            row.setPadding(dp(12), dp(12), dp(12), dp(12));
            row.setBackgroundResource(R.drawable.item_queue);
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { chooseDevice(d); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            deviceListBox.addView(row, lp);
        }
    }

    private void chooseDevice(BluetoothDevice d) {
        String name;
        String addr;
        try {
            name = d.getName();
            addr = d.getAddress();
        } catch (Exception e) {
            name = null;
            addr = "";
        }
        if (name == null || name.length() == 0) name = addr;

        if (pickingPrinter) {
            cfg.printerMac = addr;
            cfg.printerName = name;
        } else {
            cfg.peerMac = addr;
            cfg.peerName = name;
        }
        cfg.save(this);
        refreshStateText();

        try {
            if (d.getBondState() != BluetoothDevice.BOND_BONDED) d.createBond();
        } catch (Exception ignore) { }

        if (deviceDialog != null && deviceDialog.isShowing()) deviceDialog.dismiss();
        toast(pickingPrinter ? "已选择打印机：" + name : "已选择对方手机：" + name);
    }

    private boolean ensurePerm() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT}, 200);
                toast("请授予蓝牙权限后重试");
                return false;
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 201);
                toast("搜索附近设备需要定位权限");
                return false;
            }
        }
        return true;
    }

    /* ================= 其它 ================= */

    private void testPrint() {
        if (cfg.printerMac == null || cfg.printerMac.length() == 0) {
            toast("请先选择打印机");
            return;
        }
        toast("正在发送测试页…");
        Printer.selfTest(cfg.printerMac, new Printer.Callback() {
            @Override public void onProgress(String msg) { }
            @Override public void onResult(final boolean ok, final String msg) {
                // 打印线程回调，切回主线程再弹提示，否则会崩
                runOnUiThread(new Runnable() {
                    @Override public void run() { toast(msg); }
                });
            }
        });
    }

    /** 重置队列：直接执行，不再弹二次确认（点了就重置） */
    private void resetQueue() {
        QueueStore q = QueueStore.load(SettingsActivity.this);
        q.reset(cfg.start);
        q.save(SettingsActivity.this);
        PrintQueue.clear();
        toast("已重置：号码从 " + cfg.fmt(cfg.start) + " 重新开始");
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onTtsReady(boolean ready, boolean chineseOk) {
        if (tvEngine == null) return;
        if (!ready) tvEngine.setText("语音引擎未就绪，请在系统设置中检查语音合成");
        else if (!chineseOk) tvEngine.setText("当前引擎 " + speaker.engineName() + " 不支持中文，请下载中文语音包");
        else tvEngine.setText("语音引擎 " + speaker.engineName() + " · 中文可用");
    }

    @Override
    public void onSpeakingChanged(boolean speaking) { }
}
