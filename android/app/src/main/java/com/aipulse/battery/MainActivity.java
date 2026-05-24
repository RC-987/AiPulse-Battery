package com.aipulse.battery;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import com.aipulse.battery.battery.BatteryMonitorPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(BatteryMonitorPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
