/*
 * Copyright (C) 2014 Peter Gregus (C3C076@xda)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ceco.wakegestures;

import java.lang.reflect.Constructor;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.PowerManager;
import android.os.UserHandle;
import android.os.PowerManager.WakeLock;

import com.ceco.wakegestures.WakeGestureProcessor.WakeGesture;
import com.ceco.wakegestures.preference.AppPickerPreference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.Unhook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class WakeGestureHandler implements WakeGestureProcessor.WakeGestureListener {

    private static final String CLASS_SCREEN_ON_LISTENER = 
            "android.view.WindowManagerPolicy.ScreenOnListener";

    private Context mContext;
    private Context mWgContext;
    private XSharedPreferences mPrefs;
    private WakeGestureProcessor mWgp;
    private Map<WakeGesture, Intent> mWakeGestures;
    private PowerManager mPm;
    private Object mPhoneWindowManager;
    private boolean mDismissKeyguardOnNextScreenOn;
    private Unhook mScreenOnUnhook;

    public WakeGestureHandler(Object phoneWindowManager) {
        mPhoneWindowManager = phoneWindowManager;
        mContext = (Context) XposedHelpers.getObjectField(mPhoneWindowManager, "mContext");
        mPrefs = new XSharedPreferences(ModWakeGestures.PACKAGE_NAME);
        mPrefs.makeWorldReadable();
        mPm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);

        try {
            mWgContext = mContext.createPackageContext(ModWakeGestures.PACKAGE_NAME, Context.CONTEXT_IGNORE_SECURITY);
        } catch (NameNotFoundException e) {
            ModWakeGestures.log("Error creating WG context: " + e.getMessage());
        }

        try {
            mScreenOnUnhook = XposedHelpers.findAndHookMethod(mPhoneWindowManager.getClass(),
                    "finishScreenTurningOn", CLASS_SCREEN_ON_LISTENER, mScreenOnHook);
        } catch (Throwable t) {
            ModWakeGestures.log("Error hooking finishScreenTurningOn: " + t.getMessage());
        }

        initWakeGestureProcessor();
        initWakeGestures();
    }

    private void initWakeGestureProcessor() {
        mWgp = WakeGestureProcessor.getInstance();
        mWgp.registerWakeGestureListener(this);
        mWgp.startProcessing();
    }

    private void initWakeGestures() {
        mWakeGestures = new HashMap<WakeGesture, Intent>(5);
        mWakeGestures.put(WakeGesture.SWEEP_RIGHT, intentFromUri(mPrefs.getString(
                WakeGestureSettings.PREF_KEY_WG_SWEEP_RIGHT, null)));
        mWakeGestures.put(WakeGesture.SWEEP_LEFT, intentFromUri(mPrefs.getString(
                WakeGestureSettings.PREF_KEY_WG_SWEEP_LEFT, null)));
        mWakeGestures.put(WakeGesture.SWEEP_UP, intentFromUri(mPrefs.getString(
                WakeGestureSettings.PREF_KEY_WG_SWEEP_UP, null)));
        mWakeGestures.put(WakeGesture.SWEEP_DOWN, intentFromUri(mPrefs.getString(
                WakeGestureSettings.PREF_KEY_WG_SWEEP_DOWN, null)));
        mWakeGestures.put(WakeGesture.DOUBLETAP, intentFromUri(mPrefs.getString(
                WakeGestureSettings.PREF_KEY_WG_DOUBLETAP, null)));

        if (ModWakeGestures.DEBUG) {
            for (Entry<WakeGesture, Intent> item : mWakeGestures.entrySet()) {
                ModWakeGestures.log(item.getKey().toString() + ": " + item.getValue());
            }
        }

        IntentFilter intentFilter = new IntentFilter(WakeGestureSettings.ACTION_WAKE_GESTURE_CHANGED);
        mContext.registerReceiver(mBroadcastReceiver, intentFilter);
    }

    private Intent intentFromUri(String uri) {
        if (uri == null) return null;

        try {
            Intent intent = Intent.parseUri(uri, 0);
            return intent;
        } catch (URISyntaxException e) {
            ModWakeGestures.log("Error parsing uri: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void onWakeGesture(WakeGesture gesture) {
        if (ModWakeGestures.DEBUG) {
            ModWakeGestures.log("onWakeGesture: " + gesture);
        }

        handleIntent(mWakeGestures.get(gesture));
    }

    @Override
    public void onProcessingException(Exception e) {
        ModWakeGestures.log("onProcessingException: " + e.getMessage());
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !intent.hasExtra("mode")) return;

        @SuppressWarnings("deprecation")
        WakeLock wake = mPm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
            PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                ModWakeGestures.TAG);
        wake.acquire();
        wake.release();

        int mode = intent.getIntExtra("mode", AppPickerPreference.MODE_APP);
        if (mode == AppPickerPreference.MODE_APP || mode == AppPickerPreference.MODE_SHORTCUT) {
            startActivity(intent);
        } else if (mode == AppPickerPreference.MODE_ACTION) {
            executeAction(intent);
        }
    }

    private void startActivity(Intent intent) {
        try {
            Class<?> amnCls = XposedHelpers.findClass("android.app.ActivityManagerNative",
                    mContext.getClassLoader());
            Object amn = XposedHelpers.callStaticMethod(amnCls, "getDefault");
            XposedHelpers.callMethod(amn, "dismissKeyguardOnNextActivity");
        } catch (Throwable t) { }

        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Constructor<?> uhConst = XposedHelpers.findConstructorExact(UserHandle.class, int.class);
            UserHandle uh = (UserHandle) uhConst.newInstance(-2);
            XposedHelpers.callMethod(mContext, "startActivityAsUser", intent, uh);
        } catch (Throwable t) {
            ModWakeGestures.log("Error starting activity: " + t.getMessage());
        }
    }

    private void executeAction(Intent intent) {
        String action = intent.getAction();
        if (action.equals(AppPickerPreference.ACTION_DISMISS_KEYGUARD)) {
            if (mScreenOnUnhook != null) {
                mDismissKeyguardOnNextScreenOn = true;
            } else {
                dismissKeyguard();
            }
        } else if (action.equals(AppPickerPreference.ACTION_TOGGLE_TORCH)) {
            toggleTorch();
        }
    }

    private void dismissKeyguard() {
        try {
            XposedHelpers.callMethod(mPhoneWindowManager, "dismissKeyguardLw");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private void toggleTorch() {
        try {
            Intent intent = new Intent(mWgContext, TorchService.class);
            intent.setAction(TorchService.ACTION_TOGGLE_TORCH);
            Constructor<?> uhConst = XposedHelpers.findConstructorExact(UserHandle.class, int.class);
            UserHandle uh = (UserHandle) uhConst.newInstance(-2);
            XposedHelpers.callMethod(mContext, "startServiceAsUser", intent, uh);
        } catch (Throwable t) {
            ModWakeGestures.log("Error toggling Torch: " + t.getMessage());
        }
    }

    private XC_MethodHook mScreenOnHook = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            if (mDismissKeyguardOnNextScreenOn) {
                mDismissKeyguardOnNextScreenOn = false;
                dismissKeyguard();
            }
        }
    };

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(WakeGestureSettings.ACTION_WAKE_GESTURE_CHANGED) &&
                    intent.hasExtra(WakeGestureSettings.EXTRA_WAKE_GESTURE)) {
                try {
                    WakeGesture wg = WakeGesture.valueOf(intent.getStringExtra(
                            WakeGestureSettings.EXTRA_WAKE_GESTURE));
                    if (wg != null) {
                        String uri = intent.getStringExtra(WakeGestureSettings.EXTRA_INTENT_URI);
                        mWakeGestures.put(wg, intentFromUri(uri));
                        if (ModWakeGestures.DEBUG) {
                            ModWakeGestures.log(wg.toString() + ": " + uri);
                        }
                    }
                } catch (Exception e) { 
                    ModWakeGestures.log("ACTION_WAKE_GESTURE_CHANGED error: " + e.getMessage());
                }
            }
        }
    };
}
