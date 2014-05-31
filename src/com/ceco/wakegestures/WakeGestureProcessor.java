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

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import android.os.Handler;
import android.os.Message;
import android.util.Log;

/**
 * Wake Gesture Processor for ElementalX kernel wake gestures
 * 
 * 1) create/get existing instance using getInstance() static method.
 * Processor must be instantiated within process that has permission to access input device file.
 * E.g. PhoneWindowManager init() hooked in zygote init can be used.
 * 
 * 2) register WakeGestureListener that will receive Wake Gesture events as well as error messages
 * 
 * 3) call startProcessing() to initiate wake gesture processing
 * 
 * @author C3C076@XDA
 */
public class WakeGestureProcessor {
    private static final String TAG = "WakeGestureProcessor";
    private static final boolean DEBUG = false;

    private static final String CONFIG_PATH_WG = "/sys/android_touch/wake_gestures";
    private static final String CONFIG_PATH_SWEEP = "/sys/android_touch/sweep2wake";
    private static final String CONFIG_PATH_DT = "/sys/android_touch/doubletap2wake";
    private static final String CONFIG_WG_DEVICE_NAME = "wake_gesture";
    private static final int EV_TYPE = 2; // EV_REL event type
    private static final int EV_CODE = 11; // Wake gesture event

    private static final int MSG_EVENT_RECEIVED = 1;
    private static final int MSG_PROCESSING_ERROR = 2;

    private static Object sLock = new Object();
    private static WakeGestureProcessor sInstance;

    /**
     * Enum representing all available wake gestures
     */
    public enum WakeGesture {
        SWEEP_RIGHT(1),
        SWEEP_LEFT(2),
        SWEEP_UP(4),
        SWEEP_DOWN(8),
        DOUBLETAP(16),
        UNKNOWN(0);

        private int mBitValue;
        WakeGesture(int bitValue) {
           mBitValue = bitValue;
        }

        public static WakeGesture createFromId(int id) {
            switch (id) {
                case 1: return SWEEP_RIGHT;
                case 2: return SWEEP_LEFT;
                case 3: return SWEEP_UP;
                case 4: return SWEEP_DOWN;
                case 5: return DOUBLETAP;
                default: return UNKNOWN;
            }
        }

        public boolean isEnabled() {
            if (mBitValue == 0) return false;

            try {
                File f = new File(mBitValue == 16 ? CONFIG_PATH_DT : CONFIG_PATH_SWEEP);
                if (!f.exists()) return false;
                int value = readFileSingleLineAsInt(f);
                return (mBitValue == 16 ? value != 0 : 
                    (value & mBitValue) == mBitValue);
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }
    };

    /**
     * Interface for Wake Gesture event and error handling
     */
    public interface WakeGestureListener {
        /**
         * Fires when wake gesture event is triggered
         * @param gesture WakeGesture that was triggered
         */
        void onWakeGesture(WakeGesture gesture);

        /**
         * Fires when error occurres during event processing
         * @param e Exception containing error message and stack trace
         */
        void onProcessingException(Exception e);
    }

    private InputEventThread mInputEventThread;
    private List<WakeGestureListener> mListeners;

    /**
     * Creates or gets existing instance of WakeGestureProcessor
     * @return WakeGestureProcessor instance
     */
    public static WakeGestureProcessor getInstance() {
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new WakeGestureProcessor();
            }
        }
        return sInstance;
    }

    private WakeGestureProcessor() {
        mInputEventThread = new InputEventThread();
        mListeners = new ArrayList<WakeGestureListener>();
    }

    /**
     * Checks if device supports wake gestures
     * @return true if device supports wake gestures
     */
    public static boolean supportsWakeGestures() {
        try {
            File f = new File(CONFIG_PATH_WG);
            if (!f.exists()) return false;
            int value = readFileSingleLineAsInt(f);
            return (value == 1);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Starts processing of wake gestures
     * @throws UnsupportedOperationException in case device doesn't support wake gestures
     * @throws IllegalStateException in case processing could not be started because it is already running
     */
    public synchronized void startProcessing() {
        if (!supportsWakeGestures())
            throw new UnsupportedOperationException("Device does not support wake gestures");

        if (!mInputEventThread.isAlive()) {
            try {
                mInputEventThread.start();
            } catch (IllegalThreadStateException e) {
                throw new IllegalStateException("Error in startProcessing", e);
            }
        }
    }

    /**
     * Registers listener that will receive Wake Gestures and error messages
     * @param listener that implements WakeGestureListener interface
     */
    public void registerWakeGestureListener(WakeGestureListener listener) {
        if (listener == null)
            throw new IllegalArgumentException("WakeGestureListener cannot be null");

        synchronized (mListeners) {
            if (!mListeners.contains(listener)) {
                mListeners.add(listener);
            }
        }
    }

    /**
     * Unregisters existing Wake Gesture listener
     * @param listener that was previously registered via registerWakeGestureListener
     */
    public void unregisterWakeGestureListener(WakeGestureListener listener) {
        if (listener == null)
            throw new IllegalArgumentException("WakeGestureListener cannot be null");

        synchronized (mListeners) {
            if (mListeners.contains(listener)) {
                mListeners.remove(listener);
            }
        }
    }

    private void notifyWakeGestureListeners(WakeGesture gesture) {
        synchronized (mListeners) {
            for (WakeGestureListener l : mListeners) {
                l.onWakeGesture(gesture);
            }
        }
    }

    private void notifyWakeGestureListeners(Exception e) {
        synchronized (mListeners) {
            for (WakeGestureListener l : mListeners) {
                l.onProcessingException(e);
            }
        }
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_EVENT_RECEIVED:
                    EventData data = (EventData) msg.obj;
                    if (DEBUG) {
                        Log.d(TAG, "MSG_EVENT_RECEIVED: sec=" + data.timeSec +
                            "; usec=" + data.timeUsec + "; type=" + data.type +
                            "; code=" + data.code + "; value=" + data.value);
                    }
                    if (data.type == EV_TYPE && data.code == EV_CODE) {
                        notifyWakeGestureListeners(WakeGesture.createFromId(data.value));
                    }
                    break;
                case MSG_PROCESSING_ERROR:
                    notifyWakeGestureListeners((Exception) msg.obj);
                    break;
            }
        }
    };

    private class InputEventThread extends Thread {
        @Override
        public void run() {
            if (DEBUG) Log.d(TAG, "Thread starting");

            BufferedInputStream inputStream = null;
            final byte[] event = new byte[16];

            try {
                File f = new File(getInputDevicePath());
                inputStream = new BufferedInputStream(new FileInputStream(f));

                while (!isInterrupted()) {
                    if (inputStream.read(event) > 0) {
                        sendEventMessage(event);
                    }
                }

                if (DEBUG) Log.d(TAG, "Thread finishing");
            } catch (Exception e) {
                sendExceptionMessage(e);
            } finally {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (IOException e) { }
                    inputStream = null;
                }
            }
        }

        private String getInputDevicePath() {
            File[] inputDirs = new File("/sys/devices/virtual/input").listFiles();
            if (inputDirs == null)
                throw new UnsupportedOperationException("Unable to determine input device path");

            String eventName = null;
            for (File inputDir : inputDirs) {
                if (!inputDir.isDirectory()) continue;
                File[] inputFiles = inputDir.listFiles();
                if (inputFiles == null) continue;
                boolean isWakeGesture = false;
                eventName = null;
                for (File inputFile : inputFiles) {
                    if (inputFile.getName().startsWith("event")) {
                        eventName = inputFile.getName();
                    }
                    if (inputFile.getName().equals("name")) {
                        try {
                            String line = readFileSingleLine(inputFile);
                            if (CONFIG_WG_DEVICE_NAME.equals(line)) {
                                isWakeGesture = true;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error reading device name: " + e.getMessage());
                        }
                    }
                }
                if (isWakeGesture) break;
            }

            if (eventName == null)
                throw new UnsupportedOperationException("Unable to determine input device path");

            String devicePath = String.format("/dev/input/%s", eventName);
            if (DEBUG) Log.d(TAG, "Found wake gesture input device as: " + devicePath);
            return devicePath;
        }

        private void sendEventMessage(byte[] event) {
            EventData data = new EventData(event);
            Message msg = Message.obtain(mHandler, MSG_EVENT_RECEIVED, 0, 0, data);
            mHandler.sendMessage(msg);
        }

        private void sendExceptionMessage(Exception e) {
            Exception newEx = new Exception("InputEventThread exception", e);
            Message msg = Message.obtain(mHandler, MSG_PROCESSING_ERROR, 0, 0, newEx);
            mHandler.sendMessage(msg);
        }
    }

    private static int readFileSingleLineAsInt(File f) throws IOException, NumberFormatException {
        return Integer.valueOf(readFileSingleLine(f));
    }

    private static String readFileSingleLine(File f) throws IOException {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(f));
            String line = reader.readLine();
            reader.close();
            return line;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e1) { }
            }
        }
    }

    private class EventData {
        final int timeSec;
        final int timeUsec;
        final short type;
        final short code;
        final int value;

        public EventData(byte[] data) {
            byte[] tmp;

            tmp = new byte[4];
            System.arraycopy(data, 0, tmp, 0, tmp.length);
            timeSec = toBuffer(tmp).getInt();

            System.arraycopy(data, 4, tmp, 0, tmp.length);
            timeUsec = getInt(tmp);

            tmp = new byte[2];
            System.arraycopy(data, 8, tmp, 0, tmp.length);
            type = getShort(tmp);

            System.arraycopy(data, 10, tmp, 0, tmp.length);
            code = getShort(tmp);

            tmp = new byte[4];
            System.arraycopy(data, 12, tmp, 0, tmp.length);
            value = getInt(tmp);
        }

        private int getInt(byte[] array) {
            return toBuffer(array).getInt();
        }

        private short getShort(byte[] array) {
            return toBuffer(array).getShort();
        }

        private ByteBuffer toBuffer(byte[] array) {
            ByteBuffer buf = ByteBuffer.wrap(array);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            return buf;
        }
    }
}
