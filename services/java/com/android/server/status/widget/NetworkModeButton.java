package com.android.server.status.widget;

import com.android.internal.R;
import com.android.server.status.widget.PowerButton;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings.SettingNotFoundException;
import android.widget.Toast;
import android.provider.Settings;

import com.android.internal.telephony.Phone;

public class NetworkModeButton extends PowerButton{

    Context mContext;

    public static final String NETWORK_MODE_CHANGED = "com.android.internal.telephony.NETWORK_MODE_CHANGED";
    public static final String REQUEST_NETWORK_MODE = "com.android.internal.telephony.REQUEST_NETWORK_MODE";
    public static final String MODIFY_NETWORK_MODE = "com.android.internal.telephony.MODIFY_NETWORK_MODE";

    public static final String NETWORK_MODE = "networkMode";

    private static final int NO_NETWORK_MODE_YET = -99;
    private static final int NETWORK_MODE_UNKNOWN = -100;

    private static final int MODE_3G2G = 0;
    private static final int MODE_3GONLY = 1;
    private static final int MODE_BOTH = 2;

    private static final int DEFAULT_SETTING = 0;

    static NetworkModeButton ownButton = null;

    private static int networkMode = NO_NETWORK_MODE_YET;
    private static int intendedNetworkMode = NO_NETWORK_MODE_YET;
    private static int currentInternalState = PowerButton.STATE_INTERMEDIATE;
    private int currentMode;


    private int networkModeToState(Context context) {
        if (currentInternalState == PowerButton.STATE_TURNING_ON ||
                currentInternalState == PowerButton.STATE_TURNING_OFF)
            return PowerButton.STATE_INTERMEDIATE;

        switch (networkMode) {
            case Phone.NT_MODE_WCDMA_PREF:
            case Phone.NT_MODE_WCDMA_ONLY:
                return PowerButton.STATE_ENABLED;
            case Phone.NT_MODE_GSM_ONLY:
                return PowerButton.STATE_DISABLED;
        }
        return PowerButton.STATE_INTERMEDIATE;
    }


    /**
     * Gets the state of 2G3g // NOT working
     *
     * @param context
     * @return true if enabled.
     */

    private int get2G3G(Context context) {
        int state = 99;
        try {
            state = android.provider.Settings.Secure.getInt(context
                    .getContentResolver(),
                    android.provider.Settings.Secure.PREFERRED_NETWORK_MODE);
        } catch (SettingNotFoundException e) {
        }
        return state;
    }


    public static NetworkModeButton getInstance() {
        if (ownButton == null) ownButton = new NetworkModeButton();
        return ownButton;
    }

    @Override
    void initButton(int position) {
    }

    @Override
    public void toggleState(Context context) {
        toggleState(context, false);
    }

    public void toggleState(Context context, int newState) {
        if (currentState != PowerButton.STATE_INTERMEDIATE && currentState != newState) {
            toggleState(context,true);
        } else if (currentState == PowerButton.STATE_INTERMEDIATE){
            Toast toast = Toast.makeText(context, "Network mode state unknown. Please change it manually!",Toast.LENGTH_SHORT);
            toast.show();
        }
    }

    public boolean launchActivity(Context context) {
        Intent intentWireless = new Intent();
        intentWireless.setAction(android.provider.Settings.ACTION_WIRELESS_SETTINGS);
        intentWireless.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intentWireless);
        return true;
    }

    public void toggleState(Context context, boolean switchModes) {
        Intent intent = new Intent(MODIFY_NETWORK_MODE);
        switch (networkMode) {
        case Phone.NT_MODE_WCDMA_PREF:
            intent.putExtra(NETWORK_MODE, Phone.NT_MODE_GSM_ONLY);
            currentInternalState = PowerButton.STATE_TURNING_OFF;
            intendedNetworkMode=Phone.NT_MODE_GSM_ONLY;
            break;
        case Phone.NT_MODE_WCDMA_ONLY:
            if (currentMode == MODE_3GONLY || switchModes) {
                intent.putExtra(NETWORK_MODE, Phone.NT_MODE_GSM_ONLY);
                currentInternalState = PowerButton.STATE_TURNING_OFF;
                intendedNetworkMode=Phone.NT_MODE_GSM_ONLY;
            } else {
                intent.putExtra(NETWORK_MODE, Phone.NT_MODE_WCDMA_PREF);
                currentInternalState = PowerButton.STATE_TURNING_ON;
                intendedNetworkMode = Phone.NT_MODE_WCDMA_PREF;
            }
            break;
        case Phone.NT_MODE_GSM_ONLY:
            if (currentMode == MODE_3GONLY || currentMode == MODE_BOTH ) {
                intent.putExtra(NETWORK_MODE, Phone.NT_MODE_WCDMA_ONLY);
                currentInternalState = PowerButton.STATE_TURNING_ON;
                intendedNetworkMode = Phone.NT_MODE_WCDMA_ONLY;
            } else {
                intent.putExtra(NETWORK_MODE, Phone.NT_MODE_WCDMA_PREF);
                currentInternalState = PowerButton.STATE_TURNING_ON;
                intendedNetworkMode = Phone.NT_MODE_WCDMA_PREF;
            }
            break;
        }

        networkMode = NETWORK_MODE_UNKNOWN;
        context.sendBroadcast(intent);
    }




    @Override
    public void updateState(Context context) {
        mContext = context;
        boolean useCustomExp = Settings.System.getInt(mContext.getContentResolver(),
        Settings.System.NOTIF_EXPANDED_BAR_CUSTOM, 0) == 1;

        currentMode = Settings.System.getInt(context.getContentResolver(),
                Settings.System.EXPANDED_NETWORK_MODE, DEFAULT_SETTING);
        networkMode = get2G3G(context);
        currentState = networkModeToState(context);

        switch (currentState) {
        case PowerButton.STATE_DISABLED:
            if (useCustomExp) {
                currentIcon = R.drawable.stat_2g3g_off_cust;
            } else {
                currentIcon = R.drawable.stat_2g3g_off;
            }
            break;
        case PowerButton.STATE_ENABLED:
            if (networkMode == Phone.NT_MODE_WCDMA_ONLY) {
                if (useCustomExp) {
                    currentIcon = R.drawable.stat_3g_on_cust;
                } else {
                    currentIcon = R.drawable.stat_3g_on;
                }
            } else {
                if (useCustomExp) {
                    currentIcon = R.drawable.stat_2g3g_on_cust;
                } else {
                    currentIcon = R.drawable.stat_2g3g_on;
                }
            }
            break;
        case PowerButton.STATE_INTERMEDIATE:
            // In the transitional state, the bottom green bar
            // shows the tri-state (on, off, transitioning), but
            // the top dark-gray-or-bright-white logo shows the
            // user's intent. This is much easier to see in
            // sunlight.
            if (currentInternalState == PowerButton.STATE_TURNING_ON) {
                if (intendedNetworkMode == Phone.NT_MODE_WCDMA_ONLY) {
                    if (useCustomExp) {
                        currentIcon = R.drawable.stat_3g_on_cust;
                    } else {
                        currentIcon = R.drawable.stat_3g_on;
                    }
                } else {
                    if (useCustomExp) {
                        currentIcon = R.drawable.stat_2g3g_on_cust;
                    } else {
                        currentIcon = R.drawable.stat_2g3g_on;
                    }
                }
            } else {
                if (useCustomExp) {
                    currentIcon = R.drawable.stat_2g3g_off_cust;
                } else {
                    currentIcon = R.drawable.stat_2g3g_off;
                }
            }
            break;
        }
    }


    public void onReceive(Context context, Intent intent) {
        if (intent.getExtras() != null) {
            networkMode = intent.getExtras().getInt(NETWORK_MODE);
            //Update to actual state
            intendedNetworkMode=networkMode;
        }

        //need to clear intermediate states
        currentInternalState=PowerButton.STATE_ENABLED;

        int widgetState = networkModeToState(context);
        currentInternalState = widgetState;
        if (widgetState == PowerButton.STATE_ENABLED) {
            MobileDataButton.getInstance().networkModeChanged(context, networkMode);
        }
    }

    public boolean isDisabled(Context context) {
        return networkModeToState(context)==PowerButton.STATE_DISABLED;
    }

}
