package com.ceco.wakegestures;

import com.ceco.wakegestures.WakeGestureProcessor.WakeGesture;
import com.ceco.wakegestures.preference.AppPickerPreference;

import android.app.Activity;
import android.app.Fragment;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

public class WakeGestureSettings extends Activity {
    public static final String PREF_CAT_KEY_GESTURES = "pref_cat_gestures";
    public static final String PREF_KEY_ABOUT = "pref_about";
    public static final String PREF_KEY_ABOUT_DONATE = "pref_about_donate";
    public static final String PREF_KEY_ABOUT_ELEMENTALX = "pref_about_elementalx";
    public static final String PREF_KEY_WG_SWEEP_RIGHT = "pref_wg_sweep_right";
    public static final String PREF_KEY_WG_SWEEP_RIGHT_DBL = "pref_wg_sweep_right_dbl";
    public static final String PREF_KEY_WG_SWEEP_LEFT = "pref_wg_sweep_left";
    public static final String PREF_KEY_WG_SWEEP_LEFT_DBL = "pref_wg_sweep_left_dbl";
    public static final String PREF_KEY_WG_SWEEP_UP = "pref_wg_sweep_up";
    public static final String PREF_KEY_WG_SWEEP_UP_DBL = "pref_wg_sweep_up_dbl";
    public static final String PREF_KEY_WG_SWEEP_DOWN = "pref_wg_sweep_down";
    public static final String PREF_KEY_WG_SWEEP_DOWN_DBL = "pref_wg_sweep_down_dbl";
    public static final String PREF_KEY_WG_DOUBLETAP = "pref_wg_doubletap";
    public static final String PREF_KEY_WG_DOUBLETAP_DBL = "pref_wg_doubletap_dbl";
    public static final String PREF_KEY_POCKET_MODE = "pref_pocket_mode";
    public static final String PREF_KEY_ACTIVE_MUSIC = "pref_active_music";

    public static final String ACTION_WAKE_GESTURE_CHANGED = "wakegestures.intent.action.WAKE_GESTURE_CHANGED";
    public static final String ACTION_DOUBLE_WAKE_GESTURE_CHANGED = "wakegestures.intent.action.DOUBLE_WAKE_GESTURE_CHANGED";
    public static final String EXTRA_WAKE_GESTURE = "wakeGesture";
    public static final String EXTRA_INTENT_URI = "intentUri";

    public static final String ACTION_SETTINGS_CHANGED = "wakegestures.intent.action.SETTINGS_CHANGED";
    public static final String EXTRA_POCKET_MODE = "pocketMode";
    public static final String EXTRA_ACTIVE_MUSIC = "activeMusic";

    private static final int REQ_OBTAIN_SHORTCUT = 1028;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wake_gesture_settings);

        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .add(R.id.container, new PlaceholderFragment()).commit();
        }
    }

    public static class PlaceholderFragment extends Fragment {

        private TextView mInfoTextView;
        private SettingsFragment mSettingsFragment;

        public PlaceholderFragment() { }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View rootView = inflater.inflate(R.layout.fragment_wake_gesture_settings, container, false);

            mInfoTextView = (TextView) rootView.findViewById(R.id.infoText);
            mSettingsFragment = (SettingsFragment) getFragmentManager().findFragmentById(R.id.settingsFragment);

            return rootView;
        }

        @Override
        public void onResume() {
            super.onResume();

            if (!WakeGestureProcessor.supportsWakeGestures()) {
                mInfoTextView.setText(R.string.wake_gestures_unsupported);
                mInfoTextView.setVisibility(View.VISIBLE);
            }
            else if (!isModuleActive()) {
                mInfoTextView.setText(R.string.module_not_active);
                mInfoTextView.setVisibility(View.VISIBLE);
            } else {
                mInfoTextView.setVisibility(View.GONE);
            }

            mSettingsFragment.setGesturePrefsEnabled(mInfoTextView.getVisibility() == View.GONE);
        }

        private boolean isModuleActive() {
            return false;
        }
    }

    public static class SettingsFragment extends PreferenceFragment implements OnSharedPreferenceChangeListener {
        private SharedPreferences mPrefs;
        private PreferenceCategory mPrefCatGestures;
        private Preference mPrefAbout;
        private Preference mPrefAboutDonate;
        private Preference mPrefAboutElementalX;

        @SuppressWarnings("deprecation")
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            getPreferenceManager().setSharedPreferencesMode(Context.MODE_WORLD_READABLE);
            addPreferencesFromResource(R.xml.settings);

            mPrefs = getPreferenceScreen().getSharedPreferences();
            AppPickerPreference.sPrefsFragment = this;

            mPrefCatGestures = (PreferenceCategory) findPreference(PREF_CAT_KEY_GESTURES);

            mPrefAbout = findPreference(PREF_KEY_ABOUT);
            String version = "";
            try {
                PackageInfo pInfo = getActivity().getPackageManager().getPackageInfo(getActivity().getPackageName(), 0);
                version = " v" + pInfo.versionName;
            } catch (NameNotFoundException e) {
                e.printStackTrace();
            } finally {
                mPrefAbout.setTitle(getActivity().getTitle() + version);
            }

            mPrefAboutDonate = findPreference(PREF_KEY_ABOUT_DONATE);
            mPrefAboutElementalX = findPreference(PREF_KEY_ABOUT_ELEMENTALX);
        }

        protected void setGesturePrefsEnabled(boolean enabled) {
            mPrefCatGestures.setEnabled(enabled);
        }

        @Override
        public void onResume() {
            super.onResume();
            mPrefs.registerOnSharedPreferenceChangeListener(this);

            
            findPreference(PREF_KEY_WG_SWEEP_RIGHT).setEnabled(WakeGesture.SWEEP_RIGHT.isEnabled());
            findPreference(PREF_KEY_WG_SWEEP_RIGHT_DBL).setEnabled(
                    findPreference(PREF_KEY_WG_SWEEP_RIGHT).isEnabled());

            findPreference(PREF_KEY_WG_SWEEP_LEFT).setEnabled(WakeGesture.SWEEP_LEFT.isEnabled());
            findPreference(PREF_KEY_WG_SWEEP_LEFT_DBL).setEnabled(
                    findPreference(PREF_KEY_WG_SWEEP_LEFT).isEnabled());

            findPreference(PREF_KEY_WG_SWEEP_UP).setEnabled(WakeGesture.SWEEP_UP.isEnabled());
            findPreference(PREF_KEY_WG_SWEEP_UP_DBL).setEnabled(
                    findPreference(PREF_KEY_WG_SWEEP_UP).isEnabled());

            findPreference(PREF_KEY_WG_SWEEP_DOWN).setEnabled(WakeGesture.SWEEP_DOWN.isEnabled());
            findPreference(PREF_KEY_WG_SWEEP_DOWN_DBL).setEnabled(
                    findPreference(PREF_KEY_WG_SWEEP_DOWN).isEnabled());

            findPreference(PREF_KEY_WG_DOUBLETAP).setEnabled(WakeGesture.DOUBLETAP.isEnabled());
            findPreference(PREF_KEY_WG_DOUBLETAP_DBL).setEnabled(
                    findPreference(PREF_KEY_WG_DOUBLETAP).isEnabled());
        }

        @Override
        public void onPause() {
            mPrefs.unregisterOnSharedPreferenceChangeListener(this);
            super.onPause();
        }

        @Override
        public boolean onPreferenceTreeClick(PreferenceScreen prefScreen, Preference pref) {
            Intent intent = null;

            if (pref == mPrefAbout) {
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.url_wakegestures)));
            } else if (pref == mPrefAboutDonate) {
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.url_donate)));
            } else if (pref == mPrefAboutElementalX) {
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.url_elementalx)));
            }

            if (intent != null) {
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    e.printStackTrace();
                }
                return true;
            }

            return super.onPreferenceTreeClick(prefScreen, pref);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            Intent intent = new Intent(ACTION_WAKE_GESTURE_CHANGED);

            if (key.equals(PREF_KEY_WG_SWEEP_RIGHT)) {
                intent.putExtra(EXTRA_WAKE_GESTURE, "SWEEP_RIGHT");
            } else if (key.equals(PREF_KEY_WG_SWEEP_RIGHT_DBL)) {
                intent.setAction(ACTION_DOUBLE_WAKE_GESTURE_CHANGED);
                intent.putExtra(EXTRA_WAKE_GESTURE, "SWEEP_RIGHT");
            } else if (key.equals(PREF_KEY_WG_SWEEP_LEFT)) {
                intent.putExtra(EXTRA_WAKE_GESTURE, "SWEEP_LEFT");
            } else if (key.equals(PREF_KEY_WG_SWEEP_LEFT_DBL)) {
                intent.setAction(ACTION_DOUBLE_WAKE_GESTURE_CHANGED);
                intent.putExtra(EXTRA_WAKE_GESTURE, "SWEEP_LEFT");
            } else if (key.equals(PREF_KEY_WG_SWEEP_UP)) {
                intent.putExtra(EXTRA_WAKE_GESTURE, "SWEEP_UP");
            } else if (key.equals(PREF_KEY_WG_SWEEP_UP_DBL)) {
                intent.setAction(ACTION_DOUBLE_WAKE_GESTURE_CHANGED);
                intent.putExtra(EXTRA_WAKE_GESTURE, "SWEEP_UP");
            } else if (key.equals(PREF_KEY_WG_SWEEP_DOWN)) {
                intent.putExtra(EXTRA_WAKE_GESTURE, "SWEEP_DOWN");
            } else if (key.equals(PREF_KEY_WG_SWEEP_DOWN_DBL)) {
                intent.setAction(ACTION_DOUBLE_WAKE_GESTURE_CHANGED);
                intent.putExtra(EXTRA_WAKE_GESTURE, "SWEEP_DOWN");
            } else if (key.equals(PREF_KEY_WG_DOUBLETAP)) {
                intent.putExtra(EXTRA_WAKE_GESTURE, "DOUBLETAP");
            } else if (key.equals(PREF_KEY_WG_DOUBLETAP_DBL)) {
                intent.setAction(ACTION_DOUBLE_WAKE_GESTURE_CHANGED);
                intent.putExtra(EXTRA_WAKE_GESTURE, "DOUBLETAP");
            } else if (key.equals(PREF_KEY_POCKET_MODE)) {
                intent.setAction(ACTION_SETTINGS_CHANGED);
                intent.putExtra(EXTRA_POCKET_MODE, prefs.getBoolean(key, false));
            } else if (key.equals(PREF_KEY_ACTIVE_MUSIC)) {
                intent.setAction(ACTION_SETTINGS_CHANGED);
                intent.putExtra(EXTRA_ACTIVE_MUSIC, prefs.getBoolean(key, false));
            }

            if (intent.hasExtra(EXTRA_WAKE_GESTURE) ||
                    ACTION_SETTINGS_CHANGED.equals(intent.getAction())) {
                prefs.edit().commit();
                if (intent.hasExtra(EXTRA_WAKE_GESTURE)) {
                    intent.putExtra(EXTRA_INTENT_URI, prefs.getString(key, null));
                }
                getActivity().sendBroadcast(intent);
            }
        }

        public interface ShortcutHandler {
            Intent getCreateShortcutIntent();
            void onHandleShortcut(Intent intent, String name, Bitmap icon);
            void onShortcutCancelled();
        }

        private ShortcutHandler mShortcutHandler;
        public void obtainShortcut(ShortcutHandler handler) {
            if (handler == null) return;

            mShortcutHandler = handler;
            startActivityForResult(mShortcutHandler.getCreateShortcutIntent(), REQ_OBTAIN_SHORTCUT);
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            if (requestCode == REQ_OBTAIN_SHORTCUT && mShortcutHandler != null) {
                if (resultCode == Activity.RESULT_OK) {
                    Bitmap b = null;
                    Intent.ShortcutIconResource siRes = data.getParcelableExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE);
                    if (siRes != null) {
                        try {
                            final Context extContext = getActivity().createPackageContext(
                                    siRes.packageName, Context.CONTEXT_IGNORE_SECURITY);
                            final Resources extRes = extContext.getResources();
                            final int drawableResId = extRes.getIdentifier(siRes.resourceName, "drawable", siRes.packageName);
                            b = BitmapFactory.decodeResource(extRes, drawableResId);
                        } catch (NameNotFoundException e) {
                            //
                        }
                    }
                    if (b == null) {
                        b = (Bitmap)data.getParcelableExtra(Intent.EXTRA_SHORTCUT_ICON);
                    }

                    mShortcutHandler.onHandleShortcut(
                            (Intent)data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT),
                            data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME), b);
                } else {
                    mShortcutHandler.onShortcutCancelled();
                }
            }
        }
    }
}
