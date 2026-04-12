package com.gigiaj.paradise;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

class MainActivity : BridgeActivity() {
    public override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(ShadowDevicePlugin::class.java);
        super.onCreate(savedInstanceState);
    }
}
