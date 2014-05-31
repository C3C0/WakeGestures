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

import android.content.Context;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class ModWakeGestures implements IXposedHookZygoteInit, IXposedHookLoadPackage {
    public static final String TAG = "WakeGestures";
    public static final String PACKAGE_NAME = ModWakeGestures.class.getPackage().getName();
    public static final boolean DEBUG = true;

    @SuppressWarnings("unused")
    private static WakeGestureHandler sWgh;

    public static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PACKAGE_NAME)) {
            try {
                if (DEBUG) log("Hooking isModuleActive method");
                XposedHelpers.findAndHookMethod(WakeGestureSettings.PlaceholderFragment.class.getName(), 
                        lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
    }

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        if (!WakeGestureProcessor.supportsWakeGestures()) {
            log("Device does not support wake gestures");
            return;
        }

        final String CLASS_PHONE_WINDOW_MANAGER = "com.android.internal.policy.impl.PhoneWindowManager";
        final String CLASS_IWINDOW_MANAGER = "android.view.IWindowManager";
        final String CLASS_WINDOW_MANAGER_FUNCS = "android.view.WindowManagerPolicy.WindowManagerFuncs";

        try { 
            XposedHelpers.findAndHookMethod(CLASS_PHONE_WINDOW_MANAGER,  null, "init",
                    Context.class, CLASS_IWINDOW_MANAGER, CLASS_WINDOW_MANAGER_FUNCS, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    sWgh = new WakeGestureHandler(param.thisObject);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }
}
