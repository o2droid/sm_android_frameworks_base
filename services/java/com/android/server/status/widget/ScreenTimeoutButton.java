package com.android.server.status.widget;

import com.android.internal.R;
import com.android.server.status.widget.PowerButton;
import com.android.server.status.widget.StateTracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

public class ScreenTimeoutButton extends PowerButton {

    Context mContext;

    public static final int SCREEN_MINIMUM_TIMEOUT = 15000;
    public static final int SCREEN_LOW_TIMEOUT = 30000;
    public static final int SCREEN_NORMAL_TIMEOUT = 60000;
    public static final int SCREEN_HI_TIMEOUT = 120000;
    public static final int SCREEN_MAX_TIMEOUT = 300000;


    private static final int MODE_15_60_300 = 0;
    private static final int MODE_30_120_300 = 1;

    private static final int DEFAULT_SETTING = 0;

    static ScreenTimeoutButton ownButton = null;

    private int currentMode;

    public static int getScreenTtimeout(Context context) {
        return Settings.System.getInt(
                context.getContentResolver(),
                Settings.System.SCREEN_OFF_TIMEOUT, 0);
    }


    public void toggleState(Context context) {
        int screentimeout = getScreenTtimeout(context);
        if (screentimeout < SCREEN_MINIMUM_TIMEOUT) {
            if (currentMode == MODE_15_60_300) {
                screentimeout = SCREEN_MINIMUM_TIMEOUT;
            } else {
                screentimeout = SCREEN_LOW_TIMEOUT;
            }
        } else if (screentimeout < SCREEN_LOW_TIMEOUT) {
            if (currentMode == MODE_15_60_300) {
                screentimeout = SCREEN_NORMAL_TIMEOUT;
            } else {
                screentimeout = SCREEN_LOW_TIMEOUT;
            }
        } else if (screentimeout < SCREEN_NORMAL_TIMEOUT) {
            if (currentMode == MODE_15_60_300) {
                screentimeout = SCREEN_NORMAL_TIMEOUT;
            } else {
                screentimeout = SCREEN_HI_TIMEOUT;
            }
        } else if (screentimeout < SCREEN_HI_TIMEOUT) {
            if (currentMode == MODE_15_60_300) {
                screentimeout = SCREEN_MAX_TIMEOUT;
            } else {
                screentimeout=SCREEN_HI_TIMEOUT;
            }
        } else if (screentimeout < SCREEN_MAX_TIMEOUT) {
            screentimeout = SCREEN_MAX_TIMEOUT;
        } else if (currentMode == MODE_30_120_300) {
            screentimeout = SCREEN_LOW_TIMEOUT;
        } else  {
            screentimeout = SCREEN_MINIMUM_TIMEOUT;
        }
        Settings.System.putInt(
                context.getContentResolver(),
                Settings.System.SCREEN_OFF_TIMEOUT, screentimeout);
    }

    public boolean launchActivity(Context context) {
        return false;
    }

    public static ScreenTimeoutButton getInstance() {
        if (ownButton == null) ownButton = new ScreenTimeoutButton();
        return ownButton;
    }

    @Override
    void initButton(int position) {
    }

    @Override
    public void updateState(Context context) {

        mContext = context;
        boolean useCustomExp = Settings.System.getInt(mContext.getContentResolver(),
        Settings.System.NOTIF_EXPANDED_BAR_CUSTOM, 0) == 1;

        currentMode = Settings.System.getInt(context.getContentResolver(),
                Settings.System.EXPANDED_SCREENTIMEOUT_MODE, DEFAULT_SETTING);

        int timeout=getScreenTtimeout(context);
        //TODO: ADD support for the possible values
        if (timeout <= SCREEN_LOW_TIMEOUT) {
            if (useCustomExp) {
                currentIcon = R.drawable.stat_screen_timeout_off_cust;
            } else {
                currentIcon = R.drawable.stat_screen_timeout_off;
            }
            currentState = PowerButton.STATE_DISABLED;
        } else if (timeout <= SCREEN_HI_TIMEOUT) {
            if (useCustomExp) {
                currentIcon = R.drawable.stat_screen_timeout_off_cust;
            } else {
                currentIcon = R.drawable.stat_screen_timeout_off;
            }
            currentState = PowerButton.STATE_INTERMEDIATE;
        } else {
            if (useCustomExp) {
                currentIcon = R.drawable.stat_screen_timeout_on_cust;
            } else {
                currentIcon = R.drawable.stat_screen_timeout_on;
            }
            currentState = PowerButton.STATE_ENABLED;
        }
    }
}


