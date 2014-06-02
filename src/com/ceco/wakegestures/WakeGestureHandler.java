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
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.PowerManager.WakeLock;
import android.view.KeyEvent;

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
    private Map<WakeGesture, Intent> mDoubleWakeGestures;
    private PowerManager mPm;
    private Object mPhoneWindowManager;
    private boolean mDismissKeyguardOnNextScreenOn;
    private Unhook mScreenOnUnhook;
    private WakeGesture mPendingGesture;
    private Handler mHandler;
    private WakeLock mWakeLock;

    public WakeGestureHandler(Object phoneWindowManager) {
        mPhoneWindowManager = phoneWindowManager;
        mContext = (Context) XposedHelpers.getObjectField(mPhoneWindowManager, "mContext");
        mPrefs = new XSharedPreferences(ModWakeGestures.PACKAGE_NAME);
        mPrefs.makeWorldReadable();
        mPm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mHandler = new Handler();

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

        mDoubleWakeGestures = new HashMap<WakeGesture, Intent>(5);
        mDoubleWakeGestures.put(WakeGesture.SWEEP_RIGHT, intentFromUri(mPrefs.getString(
                WakeGestureSettings.PREF_KEY_WG_SWEEP_RIGHT_DBL, null)));
        mDoubleWakeGestures.put(WakeGesture.SWEEP_LEFT, intentFromUri(mPrefs.getString(
                WakeGestureSettings.PREF_KEY_WG_SWEEP_LEFT_DBL, null)));
        mDoubleWakeGestures.put(WakeGesture.SWEEP_UP, intentFromUri(mPrefs.getString(
                WakeGestureSettings.PREF_KEY_WG_SWEEP_UP_DBL, null)));
        mDoubleWakeGestures.put(WakeGesture.SWEEP_DOWN, intentFromUri(mPrefs.getString(
                WakeGestureSettings.PREF_KEY_WG_SWEEP_DOWN_DBL, null)));
        mDoubleWakeGestures.put(WakeGesture.DOUBLETAP, intentFromUri(mPrefs.getString(
                WakeGestureSettings.PREF_KEY_WG_DOUBLETAP_DBL, null)));

        if (ModWakeGestures.DEBUG) {
            for (Entry<WakeGesture, Intent> item : mWakeGestures.entrySet()) {
                ModWakeGestures.log(item.getKey().toString() + ": " + item.getValue());
            }
        }

        IntentFilter intentFilter = new IntentFilter(WakeGestureSettings.ACTION_WAKE_GESTURE_CHANGED);
        intentFilter.addAction(WakeGestureSettings.ACTION_DOUBLE_WAKE_GESTURE_CHANGED);
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

        mHandler.removeCallbacks(mPendingGestureRunnable);
        final WakeGesture prevGesture = mPendingGesture;
        mPendingGesture = null;
        releasePartialWakeLock();

        if (gesture == prevGesture) {
            handleIntent(mDoubleWakeGestures.get(gesture));
        } else {
            if (mDoubleWakeGestures.get(gesture) != null) {
                mPendingGesture = gesture;
                mWakeLock = mPm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, ModWakeGestures.TAG);
                mWakeLock.acquire();
                mHandler.postDelayed(mPendingGestureRunnable, 1000);
            } else {
                handleIntent(mWakeGestures.get(gesture));
            }
        }
    }

    @Override
    public void onProcessingException(Exception e) {
        ModWakeGestures.log("onProcessingException: " + e.getMessage());
    }

    private Runnable mPendingGestureRunnable = new Runnable() {
        @Override
        public void run() {
            releasePartialWakeLock();
            if (mPendingGesture != null) {
                handleIntent(mWakeGestures.get(mPendingGesture));
                mPendingGesture = null;
            }
        }
    };

    private void releasePartialWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
            if (ModWakeGestures.DEBUG) ModWakeGestures.log("Partial wakelock released");
        }
    }

    @SuppressWarnings("deprecation")
    private void handleIntent(Intent intent) {
        if (intent == null || !intent.hasExtra("mode")) return;

        boolean keepScreenOff = intent.getBooleanExtra(AppPickerPreference.EXTRA_KEEP_SCREEN_OFF, false);
        mWakeLock = mPm.newWakeLock(keepScreenOff ? PowerManager.PARTIAL_WAKE_LOCK : 
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
                PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                ModWakeGestures.TAG);
        mWakeLock.acquire();

        int mode = intent.getIntExtra("mode", AppPickerPreference.MODE_APP);
        if (mode == AppPickerPreference.MODE_APP || mode == AppPickerPreference.MODE_SHORTCUT) {
            startActivity(intent);
        } else if (mode == AppPickerPreference.MODE_ACTION) {
            executeAction(intent);
        }

        mWakeLock.release();
        mWakeLock = null;
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
        } else if (action.equals(AppPickerPreference.ACTION_MEDIA_CONTROL)) {
            sendMediaButtonEvent(intent.getIntExtra(AppPickerPreference.EXTRA_MC_KEYCODE, 0));
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

    private void sendMediaButtonEvent(int code) {
        long eventtime = SystemClock.uptimeMillis();
        Intent keyIntent = new Intent(Intent.ACTION_MEDIA_BUTTON, null);
        KeyEvent keyEvent = new KeyEvent(eventtime, eventtime, KeyEvent.ACTION_DOWN, code, 0);
        keyIntent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
        dispatchMediaButtonEvent(keyEvent);

        keyEvent = KeyEvent.changeAction(keyEvent, KeyEvent.ACTION_UP);
        keyIntent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
        dispatchMediaButtonEvent(keyEvent);
    }

    private void dispatchMediaButtonEvent(KeyEvent keyEvent) {
        try {
            IBinder iBinder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getDeclaredMethod("checkService", String.class)
                    .invoke(null, Context.AUDIO_SERVICE);

            // get audioService from IAudioService.Stub.asInterface(IBinder)
            Object audioService  = Class.forName("android.media.IAudioService$Stub")
                    .getDeclaredMethod("asInterface",IBinder.class)
                    .invoke(null,iBinder);

            // Dispatch keyEvent using IAudioService.dispatchMediaKeyEvent(KeyEvent)
            Class.forName("android.media.IAudioService")
                    .getDeclaredMethod("dispatchMediaKeyEvent",KeyEvent.class)
                    .invoke(audioService, keyEvent);
        } catch (Throwable t) {
            XposedBridge.log(t);
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
            String action = intent.getAction();
            if ((action.equals(WakeGestureSettings.ACTION_WAKE_GESTURE_CHANGED) ||
                    action.equals(WakeGestureSettings.ACTION_DOUBLE_WAKE_GESTURE_CHANGED)) &&
                    intent.hasExtra(WakeGestureSettings.EXTRA_WAKE_GESTURE)) {
                try {
                    WakeGesture wg = WakeGesture.valueOf(intent.getStringExtra(
                            WakeGestureSettings.EXTRA_WAKE_GESTURE));
                    if (wg != null) {
                        String uri = intent.getStringExtra(WakeGestureSettings.EXTRA_INTENT_URI);
                        if (action.equals(WakeGestureSettings.ACTION_WAKE_GESTURE_CHANGED)) {
                            mWakeGestures.put(wg, intentFromUri(uri));
                        } else {
                            mDoubleWakeGestures.put(wg, intentFromUri(uri));
                        }
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
