package com.qcaller.announcer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

/** 入口：首次选择角色，之后直接进入对应界面 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestBtPermissions();

        Cfg cfg = Cfg.load(this);
        if ("server".equals(cfg.role)) { go(ServerActivity.class); return; }
        if ("caller".equals(cfg.role)) { go(CallerActivity.class); return; }

        setContentView(R.layout.activity_main);
        findViewById(R.id.cardServer).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pick("server"); }
        });
        findViewById(R.id.cardCaller).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pick("caller"); }
        });
    }

    private void pick(String role) {
        Cfg cfg = Cfg.load(this);
        cfg.role = role;
        cfg.save(this);
        if ("server".equals(role)) go(ServerActivity.class);
        else go(CallerActivity.class);
    }

    private void go(Class<?> target) {
        startActivity(new Intent(this, target));
        finish();
    }

    private void requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            String[] ps = {Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN};
            boolean need = false;
            for (String p : ps) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need = true;
            }
            if (need) requestPermissions(ps, 100);
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 101);
            }
        }
    }
}
