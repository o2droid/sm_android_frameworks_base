/*
 * Copyright (C) 2007 The Android Open Source Project
 *
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

package com.android.server.status;

import com.android.internal.R;
import com.android.internal.util.CharSequences;

import android.app.ActivityManagerNative;
import android.app.Dialog;
import android.app.IStatusBar;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.StateListDrawable;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff.Mode;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.Power;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.provider.Telephony;
import android.util.Log;
import android.util.Slog;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import android.widget.FrameLayout;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Set;
import java.lang.reflect.Field;

import com.android.server.status.widget.PowerButton;
import com.android.server.status.widget.GPSButton;
import com.android.server.status.widget.WifiButton;
import com.android.server.status.widget.BluetoothButton;
import com.android.server.status.widget.BrightnessButton;
import com.android.server.status.widget.SoundButton;
import com.android.server.status.widget.SyncButton;
import com.android.server.status.widget.WifiApButton;
import com.android.server.status.widget.ScreenTimeoutButton;
import com.android.server.status.widget.MobileDataButton;
import com.android.server.status.widget.NetworkModeButton;
import com.android.server.status.widget.LockScreenButton;
import com.android.server.status.widget.AutoRotateButton;
import com.android.server.status.widget.AirplaneButton;
import com.android.server.status.widget.SleepButton;

/**
 * The public (ok, semi-public) service for the status bar.
 * <p>
 * This interesting thing to note about this class is that most of the methods that
 * are called from other classes just post a message, and everything else is batched
 * and coalesced into a series of calls to methods that all start with "perform."
 * There are two reasons for this.  The first is that some of the methods (activate/deactivate)
 * are on IStatusBar, so they're called from the thread pool and they need to make their
 * way onto the UI thread.  The second is that the message queue is stopped while animations
 * are happening in order to make for smoother transitions.
 * <p>
 * Each icon is either an icon or an icon and a notification.  They're treated mostly
 * separately throughout the code, although they both use the same key, which is assigned
 * when they are created.
 */
public class StatusBarService extends IStatusBar.Stub
{
    static final String TAG = "StatusBar";
    static final boolean SPEW = false;

    private boolean mShowPlmnSb;
    private boolean mShowSpnSb;

    static final int EXPANDED_LEAVE_ALONE = -10000;
    static final int EXPANDED_FULL_OPEN = -10001;

    private static final int MSG_ANIMATE = 1000;
    private static final int MSG_ANIMATE_REVEAL = 1001;

    private static final int OP_ADD_ICON = 1;
    private static final int OP_UPDATE_ICON = 2;
    private static final int OP_REMOVE_ICON = 3;
    private static final int OP_SET_VISIBLE = 4;
    private static final int OP_EXPAND = 5;
    private static final int OP_TOGGLE = 6;
    private static final int OP_DISABLE = 7;

    private static PowerManager PowerMan;
    private static PowerManager.WakeLock powerWake = null;

    private class PendingOp {
        IBinder key;
        int code;
        IconData iconData;
        NotificationData notificationData;
        boolean visible;
        int integer;
    }

    private class DisableRecord implements IBinder.DeathRecipient {
        String pkg;
        int what;
        IBinder token;

        public void binderDied() {
            Slog.i(TAG, "binder died for pkg=" + pkg);
            disable(0, token, pkg);
            token.unlinkToDeath(this, 0);
        }
    }

    public interface NotificationCallbacks {
        void onSetDisabled(int status);
        void onClearAll();
        void onNotificationClick(String pkg, String tag, int id);
        void onNotificationClear(String pkg, String tag, int id);
        void onPanelRevealed();
    }

    private class ExpandedDialog extends Dialog {
        ExpandedDialog(Context context) {
            super(context, com.android.internal.R.style.Theme_Light_NoTitleBar);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
                if (!down) {
                    StatusBarService.this.deactivate();
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
    }
    
    final Context mContext;
    final Display mDisplay;
    StatusBarView mStatusBarView;
    int mPixelFormat;
    H mHandler;
    Object mQueueLock = new Object();
    ArrayList<PendingOp> mQueue = new ArrayList<PendingOp>();
    NotificationCallbacks mNotificationCallbacks;
    
    // All accesses to mIconMap and mNotificationData are syncronized on those objects,
    // but this is only so dump() can work correctly.  Modifying these outside of the UI
    // thread will not work, there are places in the code that unlock and reaquire between
    // reads and require them to not be modified.

    // icons
    HashMap<IBinder,StatusBarIcon> mIconMap = new HashMap<IBinder,StatusBarIcon>();
    ArrayList<StatusBarIcon> mIconList = new ArrayList<StatusBarIcon>();
    String[] mRightIconSlots;
    StatusBarIcon[] mRightIcons;
    LinearLayout mIcons;
    IconMerger mNotificationIcons;
    LinearLayout mStatusIcons;
    StatusBarIcon mMoreIcon;
    private UninstallReceiver mUninstallReceiver;

    // expanded notifications
    NotificationViewList mNotificationData = new NotificationViewList();
    Dialog mExpandedDialog;
    ExpandedView mExpandedView;
    WindowManager.LayoutParams mExpandedParams;
    ScrollView mScrollView;
    View mNotificationLinearLayout;
    TextView mOngoingTitle;
    LinearLayout mOngoingItems;
    TextView mLatestTitle;
    LinearLayout mLatestItems;
    TextView mNoNotificationsTitle;
    TextView mSpnLabel;
    TextView mPlmnLabel;
    TextView mClearButton;
    View mExpandedContents;
    CloseDragHandle mCloseView;
    int[] mPositionTmp = new int[2];
    boolean mExpanded;
    boolean mExpandedVisible;

    // the date view
    DateView mDateView;

    // the tracker view
    TrackingView mTrackingView;
    WindowManager.LayoutParams mTrackingParams;
    int mTrackingPosition; // the position of the top of the tracking view.

    // ticker
    private Ticker mTicker;
    private View mTickerView;
    private boolean mTicking;
    private TickerView tickerView;
    
    // Tracking finger for opening/closing.
    int mEdgeBorder; // corresponds to R.dimen.status_bar_edge_ignore
    boolean mTracking;
    VelocityTracker mVelocityTracker;
    
    static final int ANIM_FRAME_DURATION = (1000/60);
    
    boolean mAnimating;
    long mCurAnimationTime;
    float mDisplayHeight;
    float mAnimY;
    float mAnimVel;
    float mAnimAccel;
    long mAnimLastTime;
    boolean mAnimatingReveal = false;
    int mViewDelta;
    int[] mAbsPos = new int[2];
    private int whiteColor = 0xffffffff;

    // GB theme adaptation
    private int notifications_title_color = 0xff000000;
    private int notifications_text_color = 0xff686868;
    private int notifications_time_color = 0xff686868;
    private int date_color = 0xffffffff;
    private int no_notifications_color = 0xff949494;
    private int ongoing_notifications_color = 0xff949494;
    private int latest_notifications_color = 0xff949494;
    private int plmn_label_color = 0xffe0e0e0;
    private int spn_label_color = 0xffe0e0e0;
    private int clear_button_label_color = 0xff000000;
    private int new_notifications_ticker_color = 0xffffffff;

    boolean custNotBar = false;
    boolean custExpBar = false;
    int notifBarColorMask;
    int expBarColorMask;
    Mode notifPDMode = Mode.SCREEN;
    Mode expPDMode = Mode.SCREEN;
    Drawable closerDrawable;
    Drawable expBarBackDrawable;
    Drawable expBarHeadDrawable;
    Drawable expBarNotifTitleDrawable;
    
    // for disabling the status bar
    ArrayList<DisableRecord> mDisableRecords = new ArrayList<DisableRecord>();
    int mDisabled = 0;

    private boolean mHideOnPowerButtonChange = false;

    private HashMap<String,PowerButton> mUsedPowerButtons = new HashMap<String,PowerButton>();

    boolean mNotificationScreenLighter;
    int mNotificationScreenLighterTime;

    // statusbar music controls
    private AudioManager am;
    private boolean mIsMusicActive;
    private ImageButton mPlayIcon;
    private ImageButton mPauseIcon;
    private ImageButton mRewindIcon;
    private ImageButton mForwardIcon;
    private LinearLayout mStatusbarMusicControls;
    private LinearLayout mCarrierBox;
    private boolean mStatusMusicControls;
    private boolean mStatusAlwaysMusic;

    /**
     * Construct the service, add the status bar view to the window manager
     */
    public StatusBarService(Context context) {
        mContext = context;
        mHandler = new H();
        notifications_title_color = Settings.System.getInt(mContext.getContentResolver(), Settings.System.NOTIF_ITEM_TITLE_COLOR, notifications_title_color);
        notifications_text_color = Settings.System.getInt(mContext.getContentResolver(), Settings.System.NOTIF_ITEM_TEXT_COLOR, notifications_text_color);
        notifications_time_color = Settings.System.getInt(mContext.getContentResolver(), Settings.System.NOTIF_ITEM_TIME_COLOR, notifications_time_color);
        mDisplay = ((WindowManager)context.getSystemService(
                Context.WINDOW_SERVICE)).getDefaultDisplay();
        makeStatusBarView(context);
        updateColors();
        mUninstallReceiver = new UninstallReceiver();
        SettingsObserver observer = new SettingsObserver(mHandler);
        observer.observe();
    }

    public void setNotificationCallbacks(NotificationCallbacks listener) {
        mNotificationCallbacks = listener;
    }

    // ================================================================================
    // Constructing the view
    // ================================================================================
    private void makeStatusBarView(Context context) {
        Resources res = context.getResources();
        mRightIconSlots = res.getStringArray(com.android.internal.R.array.status_bar_icon_order);
        mRightIcons = new StatusBarIcon[mRightIconSlots.length];
        getNotBarConfig();        
        ExpandedView expanded = (ExpandedView)View.inflate(context,
                com.android.internal.R.layout.status_bar_expanded, null);
        expanded.mService = this;
        StatusBarView sb = (StatusBarView)View.inflate(context,
                com.android.internal.R.layout.status_bar, null);
        sb.mService = this;

        // figure out which pixel-format to use for the status bar.
        mPixelFormat = PixelFormat.TRANSLUCENT;
        Drawable bg = sb.getBackground();
        if (bg != null) {
            mPixelFormat = bg.getOpacity();
        }
        mStatusBarView = sb;
        mDateView = (DateView)sb.findViewById(R.id.date);
        if (custNotBar) {
            mStatusBarView.setBackgroundDrawable(res.getDrawable(com.android.internal.R.drawable.statusbar_background_sq,
            		notifBarColorMask, notifPDMode));
            mDateView.setBackgroundDrawable(res.getDrawable(com.android.internal.R.drawable.statusbar_background_sq,
            		notifBarColorMask, notifPDMode));
            mDateView.setPadding(6, 0, 6, 0);
        }
        mStatusIcons = (LinearLayout)sb.findViewById(R.id.statusIcons);
        mNotificationIcons = (IconMerger)sb.findViewById(R.id.notificationIcons);
        mNotificationIcons.service = this;
        mIcons = (LinearLayout)sb.findViewById(R.id.icons);
        mTickerView = sb.findViewById(R.id.ticker);
        
        mExpandedDialog = new ExpandedDialog(context);
        mExpandedView = expanded;
        mExpandedContents = expanded.findViewById(R.id.notificationLinearLayout);
        mOngoingTitle = (TextView)expanded.findViewById(R.id.ongoingTitle);
        mOngoingItems = (LinearLayout)expanded.findViewById(R.id.ongoingItems);
        mLatestTitle = (TextView)expanded.findViewById(R.id.latestTitle);
        mLatestItems = (LinearLayout)expanded.findViewById(R.id.latestItems);
        mNoNotificationsTitle = (TextView)expanded.findViewById(R.id.noNotificationsTitle);
        mClearButton = (TextView)expanded.findViewById(R.id.clear_all_button);
        mClearButton.setOnClickListener(mClearButtonListener);
        mCarrierBox = (LinearLayout)expanded.findViewById(R.id.carrierBox);
        mSpnLabel = (TextView)expanded.findViewById(R.id.spnLabel);
        mPlmnLabel = (TextView)expanded.findViewById(R.id.plmnLabel);
        mScrollView = (ScrollView)expanded.findViewById(R.id.scroll);
        mNotificationLinearLayout = expanded.findViewById(R.id.notificationLinearLayout);

        // music controls
        mPlayIcon = (ImageButton) expanded.findViewById(R.id.music_control_play);
        mPauseIcon = (ImageButton) expanded.findViewById(R.id.music_control_pause);
        mRewindIcon = (ImageButton) expanded.findViewById(R.id.music_control_previous);
        mForwardIcon = (ImageButton) expanded.findViewById(R.id.music_control_next);
        mStatusbarMusicControls = (LinearLayout) expanded.findViewById(R.id.exp_music_control);
        if (custExpBar) {
            mExpandedView.findViewById(R.id.exp_view_lin_layout).
            		setBackgroundDrawable(expBarHeadDrawable);
            mNoNotificationsTitle.setBackgroundDrawable(expBarNotifTitleDrawable);
            mOngoingTitle.setBackgroundDrawable(expBarNotifTitleDrawable);
            mLatestTitle.setBackgroundDrawable(expBarNotifTitleDrawable);
        }

        mExpandedView.setVisibility(View.GONE);
        mOngoingTitle.setVisibility(View.GONE);
        mLatestTitle.setVisibility(View.GONE);

        mTicker = new MyTicker(context, sb);

        tickerView = (TickerView)sb.findViewById(R.id.tickerText);
        tickerView.mTicker = mTicker;

        mTrackingView = (TrackingView)View.inflate(context,
                com.android.internal.R.layout.status_bar_tracking, null);
        mTrackingView.mService = this;
        mCloseView = (CloseDragHandle)mTrackingView.findViewById(R.id.close);
        if (custExpBar) {
            ImageView iv = (ImageView)mTrackingView.findViewById(R.id.close_image);
            mCloseView.removeAllViews();
            iv.setImageDrawable(closerDrawable);
            iv.setColorFilter(expBarColorMask, expPDMode);
            mCloseView.addView(iv);
        }
        mCloseView.mService = this;
        mEdgeBorder = res.getDimensionPixelSize(R.dimen.status_bar_edge_ignore);

        // add the more icon for the notifications
        IconData moreData = IconData.makeIcon(null, context.getPackageName(),
                R.drawable.stat_notify_more, 0, 42);
        mMoreIcon = new StatusBarIcon(context, moreData, mNotificationIcons);
        mMoreIcon.view.setId(R.drawable.stat_notify_more);
        mNotificationIcons.moreIcon = mMoreIcon;
        mNotificationIcons.addView(mMoreIcon.view);

        // set the inital view visibility
        setAreThereNotifications();
        mDateView.setVisibility(View.INVISIBLE);
        mCarrierBox.setVisibility(View.VISIBLE);

        // before we register for broadcasts
        mPlmnLabel.setText(R.string.lockscreen_carrier_default);
        mPlmnLabel.setVisibility(View.VISIBLE);
        mSpnLabel.setText("");
        mSpnLabel.setVisibility(View.GONE);

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Telephony.Intents.SPN_STRINGS_UPDATED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(Settings.SETTINGS_CHANGED);
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        filter.addAction(NetworkModeButton.NETWORK_MODE_CHANGED);
        context.registerReceiver(mBroadcastReceiver, filter);
    }

    public void systemReady() {
        final StatusBarView view = mStatusBarView;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                view.getContext().getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.status_bar_height),
                WindowManager.LayoutParams.TYPE_STATUS_BAR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
                WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING,
                mPixelFormat);
        lp.gravity = Gravity.TOP | Gravity.FILL_HORIZONTAL;
        lp.setTitle("StatusBar");
        lp.windowAnimations = R.style.Animation_StatusBar;

        //Check and see if power widget should be set on start.
        boolean powerWidget = Settings.System.getInt(mContext.getContentResolver(),
                            Settings.System.EXPANDED_VIEW_WIDGET, 1) == 1;
        setupPowerWidget();
        if(!powerWidget) {
            mExpandedView.findViewById(R.id.exp_power_stat).
                        setVisibility(View.GONE);
        }

        // music controls
        mStatusMusicControls = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.STATUSBAR_MUSIC_CONTROLS, 0) == 1;
        mStatusAlwaysMusic = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.STATUSBAR_ALWAYS_MUSIC_CONTROLS, 0) == 1;
        setupMusicControls();

        // settings for on notif screen on
        mNotificationScreenLighter = Settings.System.getInt(mContext.getContentResolver(),
                            Settings.System.NOTIFICATION_SCREEN_LIGHTER, 1) == 1;
        mNotificationScreenLighterTime = Settings.System.getInt(mContext.getContentResolver(),
                            Settings.System.NOTIFICATION_SCREEN_LIGHTER_TIME, 4000);

        WindowManagerImpl.getDefault().addView(view, lp);
    }

    // ================================================================================
    // From IStatusBar
    // ================================================================================
    public void activate() {
        enforceExpandStatusBar();
        addPendingOp(OP_EXPAND, null, true);
    }

    public void deactivate() {
        enforceExpandStatusBar();
        addPendingOp(OP_EXPAND, null, false);
    }

    public void toggle() {
        enforceExpandStatusBar();
        addPendingOp(OP_TOGGLE, null, false);
    }

    public void disable(int what, IBinder token, String pkg) {
        enforceStatusBar();
        synchronized (mNotificationCallbacks) {
            // This is a little gross, but I think it's safe as long as nobody else
            // synchronizes on mNotificationCallbacks.  It's important that the the callback
            // and the pending op get done in the correct order and not interleaved with
            // other calls, otherwise they'll get out of sync.
            int net;
            synchronized (mDisableRecords) {
                manageDisableListLocked(what, token, pkg);
                net = gatherDisableActionsLocked();
                mNotificationCallbacks.onSetDisabled(net);
            }
            addPendingOp(OP_DISABLE, net);
        }
    }

    public IBinder addIcon(String slot, String iconPackage, int iconId, int iconLevel) {
        enforceStatusBar();
        return addIcon(IconData.makeIcon(slot, iconPackage, iconId, iconLevel, 0), null);
    }

    public void updateIcon(IBinder key,
            String slot, String iconPackage, int iconId, int iconLevel) {
        enforceStatusBar();
        updateIcon(key, IconData.makeIcon(slot, iconPackage, iconId, iconLevel, 0), null);
    }

    public void removeIcon(IBinder key) {
        enforceStatusBar();
        addPendingOp(OP_REMOVE_ICON, key, null, null, -1);
    }

    private void enforceStatusBar() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.STATUS_BAR,
                "StatusBarService");
    }

    private void enforceExpandStatusBar() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.EXPAND_STATUS_BAR,
                "StatusBarService");
    }

    // ================================================================================
    // Can be called from any thread
    // ================================================================================
    public IBinder addIcon(IconData data, NotificationData n) {
        int slot;
        // assert early-on if they using a slot that doesn't exist.
        if (data != null && n == null) {
            slot = getRightIconIndex(data.slot);
            if (slot < 0) {
                throw new SecurityException("invalid status bar icon slot: "
                        + (data.slot != null ? "'" + data.slot + "'" : "null"));
            }
        } else {
            slot = -1;
        }
        IBinder key = new Binder();
        addPendingOp(OP_ADD_ICON, key, data, n, -1);
        // screen backlight
        if (mNotificationScreenLighter) {
            PowerManager PowerMan = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            boolean isScreenOn = PowerMan.isScreenOn();
            if (!isScreenOn) {
                powerWake = PowerMan.newWakeLock(PowerManager.FULL_WAKE_LOCK |
                        PowerManager.ACQUIRE_CAUSES_WAKEUP, "NotificationScreenLight");
                powerWake.acquire(mNotificationScreenLighterTime);
            }
        }
        return key;
    }

    public void updateIcon(IBinder key, IconData data, NotificationData n) {
        addPendingOp(OP_UPDATE_ICON, key, data, n, -1);
    }

    public void setIconVisibility(IBinder key, boolean visible) {
        addPendingOp(OP_SET_VISIBLE, key, visible);
    }

    private void addPendingOp(int code, IBinder key, IconData data, NotificationData n, int i) {
        synchronized (mQueueLock) {
            PendingOp op = new PendingOp();
            op.key = key;
            op.code = code;
            op.iconData = data == null ? null : data.clone();
            op.notificationData = n;
            op.integer = i;
            mQueue.add(op);
            if (mQueue.size() == 1) {
                mHandler.sendEmptyMessage(2);
            }
        }
    }

    private void addPendingOp(int code, IBinder key, boolean visible) {
        synchronized (mQueueLock) {
            PendingOp op = new PendingOp();
            op.key = key;
            op.code = code;
            op.visible = visible;
            mQueue.add(op);
            if (mQueue.size() == 1) {
                mHandler.sendEmptyMessage(1);
            }
        }
    }

    private void addPendingOp(int code, int integer) {
        synchronized (mQueueLock) {
            PendingOp op = new PendingOp();
            op.code = code;
            op.integer = integer;
            mQueue.add(op);
            if (mQueue.size() == 1) {
                mHandler.sendEmptyMessage(1);
            }
        }
    }

    // lock on mDisableRecords
    void manageDisableListLocked(int what, IBinder token, String pkg) {
        if (SPEW) {
            Slog.d(TAG, "manageDisableList what=0x" + Integer.toHexString(what)
                    + " pkg=" + pkg);
        }
        // update the list
        synchronized (mDisableRecords) {
            final int N = mDisableRecords.size();
            DisableRecord tok = null;
            int i;
            for (i=0; i<N; i++) {
                DisableRecord t = mDisableRecords.get(i);
                if (t.token == token) {
                    tok = t;
                    break;
                }
            }
            if (what == 0 || !token.isBinderAlive()) {
                if (tok != null) {
                    mDisableRecords.remove(i);
                    tok.token.unlinkToDeath(tok, 0);
                }
            } else {
                if (tok == null) {
                    tok = new DisableRecord();
                    try {
                        token.linkToDeath(tok, 0);
                    }
                    catch (RemoteException ex) {
                        return; // give up
                    }
                    mDisableRecords.add(tok);
                }
                tok.what = what;
                tok.token = token;
                tok.pkg = pkg;
            }
        }
    }

    // lock on mDisableRecords
    int gatherDisableActionsLocked() {
        final int N = mDisableRecords.size();
        // gather the new net flags
        int net = 0;
        for (int i=0; i<N; i++) {
            net |= mDisableRecords.get(i).what;
        }
        return net;
    }

    private int getRightIconIndex(String slot) {
        final int N = mRightIconSlots.length;
        for (int i=0; i<N; i++) {
            if (mRightIconSlots[i].equals(slot)) {
                return i;
            }
        }
        return -1;
    }

    // ================================================================================
    // Always called from UI thread
    // ================================================================================
    /**
     * All changes to the status bar and notifications funnel through here and are batched.
     */
    private class H extends Handler {
        public void handleMessage(Message m) {
            if (m.what == MSG_ANIMATE) {
                doAnimation();
                return;
            }
            if (m.what == MSG_ANIMATE_REVEAL) {
                doRevealAnimation();
                return;
            }

            ArrayList<PendingOp> queue;
            synchronized (mQueueLock) {
                queue = mQueue;
                mQueue = new ArrayList<PendingOp>();
            }

            boolean wasExpanded = mExpanded;

            // for each one in the queue, find all of the ones with the same key
            // and collapse that down into a final op and/or call to setVisibility, etc
            boolean expand = wasExpanded;
            boolean doExpand = false;
            boolean doDisable = false;
            int disableWhat = 0;
            int N = queue.size();
            while (N > 0) {
                PendingOp op = queue.get(0);
                boolean doOp = false;
                boolean visible = false;
                boolean doVisibility = false;
                if (op.code == OP_SET_VISIBLE) {
                    doVisibility = true;
                    visible = op.visible;
                }
                else if (op.code == OP_EXPAND) {
                    doExpand = true;
                    expand = op.visible;
                }
                else if (op.code == OP_TOGGLE) {
                    doExpand = true;
                    expand = !expand;
                }
                else {
                    doOp = true;
                }

                if (alwaysHandle(op.code)) {
                    // coalesce these
                    for (int i=1; i<N; i++) {
                        PendingOp o = queue.get(i);
                        if (!alwaysHandle(o.code) && o.key == op.key) {
                            if (o.code == OP_SET_VISIBLE) {
                                visible = o.visible;
                                doVisibility = true;
                            }
                            else if (o.code == OP_EXPAND) {
                                expand = o.visible;
                                doExpand = true;
                            }
                            else {
                                op.code = o.code;
                                op.iconData = o.iconData;
                                op.notificationData = o.notificationData;
                            }
                            queue.remove(i);
                            i--;
                            N--;
                        }
                    }
                }

                queue.remove(0);
                N--;

                if (doOp) {
                    switch (op.code) {
                        case OP_ADD_ICON:
                        case OP_UPDATE_ICON:
                            performAddUpdateIcon(op.key, op.iconData, op.notificationData);
                            break;
                        case OP_REMOVE_ICON:
                            performRemoveIcon(op.key);
                            break;
                        case OP_DISABLE:
                            doDisable = true;
                            disableWhat = op.integer;
                            break;
                    }
                }
                if (doVisibility && op.code != OP_REMOVE_ICON) {
                    performSetIconVisibility(op.key, visible);
                }
            }

            if (queue.size() != 0) {
                throw new RuntimeException("Assertion failed: queue.size=" + queue.size());
            }
            if (doExpand) {
                // this is last so that we capture all of the pending changes before doing it
                if (expand) {
                    animateExpand();
                } else {
                    animateCollapse();
                }
            }
            if (doDisable) {
                performDisableActions(disableWhat);
            }
        }
    }

    private boolean alwaysHandle(int code) {
        return code == OP_DISABLE;
    }

    /* private */ void performAddUpdateIcon(IBinder key, IconData data, NotificationData n)
                        throws StatusBarException {
        if (SPEW) {
            Slog.d(TAG, "performAddUpdateIcon icon=" + data + " notification=" + n + " key=" + key);
        }
        // notification
        if (n != null) {
            StatusBarNotification notification = getNotification(key);
            NotificationData oldData = null;
            if (notification == null) {
                // add
                notification = new StatusBarNotification();
                notification.key = key;
                notification.data = n;
                synchronized (mNotificationData) {
                    mNotificationData.add(notification);
                }
                addNotificationView(notification);
                setAreThereNotifications();
            } else {
                // update
                oldData = notification.data;
                notification.data = n;
                updateNotificationView(notification, oldData);
            }
            // Show the ticker if one is requested, and the text is different
            // than the currently displayed ticker.  Also don't do this
            // until status bar window is attached to the window manager,
            // because...  well, what's the point otherwise?  And trying to
            // run a ticker without being attached will crash!
            if (n.tickerText != null && mStatusBarView.getWindowToken() != null
                    && (oldData == null
                        || oldData.tickerText == null
                        || !CharSequences.equals(oldData.tickerText, n.tickerText))) {
                if (0 == (mDisabled & 
                    (StatusBarManager.DISABLE_NOTIFICATION_ICONS | StatusBarManager.DISABLE_NOTIFICATION_TICKER))) {
                    mTicker.addEntry(n, StatusBarIcon.getIcon(mContext, data), n.tickerText);
                }
            }
            updateExpandedViewPos(EXPANDED_LEAVE_ALONE);
        }

        // icon
        synchronized (mIconMap) {
            StatusBarIcon icon = mIconMap.get(key);
            if (icon == null) {
                // add
                LinearLayout v = n == null ? mStatusIcons : mNotificationIcons;

                icon = new StatusBarIcon(mContext, data, v);
                mIconMap.put(key, icon);
                mIconList.add(icon);

                if (n == null) {
                    int slotIndex = getRightIconIndex(data.slot);
                    StatusBarIcon[] rightIcons = mRightIcons;
                    if (rightIcons[slotIndex] == null) {
                        int pos = 0;
                        for (int i=mRightIcons.length-1; i>slotIndex; i--) {
                            StatusBarIcon ic = rightIcons[i];
                            if (ic != null) {
                                pos++;
                            }
                        }
                        rightIcons[slotIndex] = icon;
                        mStatusIcons.addView(icon.view, pos);
                    } else {
                        Slog.e(TAG, "duplicate icon in slot " + slotIndex + "/" + data.slot);
                        mIconMap.remove(key);
                        mIconList.remove(icon);
                        return ;
                    }
                } else {
                    int iconIndex = mNotificationData.getIconIndex(n);
                    mNotificationIcons.addView(icon.view, iconIndex);
                }
            } else {
                if (n == null) {
                    // right hand side icons -- these don't reorder
                    icon.update(mContext, data);
                } else {
                    // remove old
                    ViewGroup parent = (ViewGroup)icon.view.getParent();
                    parent.removeView(icon.view);
                    // add new
                    icon.update(mContext, data);
                    int iconIndex = mNotificationData.getIconIndex(n);
                    mNotificationIcons.addView(icon.view, iconIndex);
                }
            }
        }
    }

    /* private */ void performSetIconVisibility(IBinder key, boolean visible) {
        synchronized (mIconMap) {
            if (SPEW) {
                Slog.d(TAG, "performSetIconVisibility key=" + key + " visible=" + visible);
            }
            StatusBarIcon icon = mIconMap.get(key);
            icon.view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
    
    /* private */ void performRemoveIcon(IBinder key) {
        synchronized (this) {
            if (SPEW) {
                Slog.d(TAG, "performRemoveIcon key=" + key);
            }
            StatusBarIcon icon = mIconMap.remove(key);
            mIconList.remove(icon);
            if (icon != null) {
                ViewGroup parent = (ViewGroup)icon.view.getParent();
                parent.removeView(icon.view);
                int slotIndex = getRightIconIndex(icon.mData.slot);
                if (slotIndex >= 0) {
                    mRightIcons[slotIndex] = null;
                }
            }
            StatusBarNotification notification = getNotification(key);
            if (notification != null) {
                removeNotificationView(notification);
                synchronized (mNotificationData) {
                    mNotificationData.remove(notification);
                }
                setAreThereNotifications();
            }
        }
    }

    int getIconNumberForView(View v) {
        synchronized (mIconMap) {
            StatusBarIcon icon = null;
            final int N = mIconList.size();
            for (int i=0; i<N; i++) {
                StatusBarIcon ic = mIconList.get(i);
                if (ic.view == v) {
                    icon = ic;
                    break;
                }
            }
            if (icon != null) {
                return icon.getNumber();
            } else {
                return -1;
            }
        }
    }


    StatusBarNotification getNotification(IBinder key) {
        synchronized (mNotificationData) {
            return mNotificationData.get(key);
        }
    }

    View.OnFocusChangeListener mFocusChangeListener = new View.OnFocusChangeListener() {
        public void onFocusChange(View v, boolean hasFocus) {
            // Because 'v' is a ViewGroup, all its children will be (un)selected
            // too, which allows marqueeing to work.
            v.setSelected(hasFocus);
        }
    };
    
    View makeNotificationView(StatusBarNotification notification, ViewGroup parent) {
        Resources res = mContext.getResources();
        final NotificationData n = notification.data;
        RemoteViews remoteViews = n.contentView;
        if (remoteViews == null) {
            return null;
        }

        // create the row view
        LayoutInflater inflater = (LayoutInflater)mContext.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        LatestItemContainer row = (LatestItemContainer) inflater.inflate(com.android.internal.R.layout.status_bar_latest_event, parent, false);
        if (n.clearable) {
            row.setOnSwipeCallback(new Runnable() {
                public void run() {
                    mNotificationCallbacks.onNotificationClear(n.pkg, n.tag, n.id);
                }
            });
        }
        ViewGroup content = (ViewGroup)row.findViewById(com.android.internal.R.id.content);
        if (custExpBar) {
            StateListDrawable sld = new StateListDrawable();
            int stateFocused = android.R.attr.state_focused;
            int statePressed = android.R.attr.state_pressed;
            Drawable colornormal = res.getDrawable(com.android.internal.R.drawable.status_bar_item_background_normal_cust);
            Drawable colorfocused = res.getDrawable(com.android.internal.R.drawable.status_bar_item_background_focus_cust);
            Drawable colorpressed = res.getDrawable(com.android.internal.R.drawable.status_bar_item_background_pressed_cust);
            sld.addState(new int[] {stateFocused}, colorfocused);
            sld.addState(new int[] {statePressed}, colorpressed);
            sld.addState(new int[] {}, colornormal);
            sld.mutate();
            sld.setColorFilter(expBarColorMask, expPDMode);
            content.setBackgroundDrawable(sld);
            
        }
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        content.setOnFocusChangeListener(mFocusChangeListener);
        PendingIntent contentIntent = n.contentIntent;
        if (contentIntent != null) {
            content.setOnClickListener(new Launcher(contentIntent, n.pkg, n.tag, n.id));
        }

        View child = null;
        Exception exception = null;
        try {
            child = remoteViews.apply(mContext, content);
        }
        catch (RuntimeException e) {
            exception = e;
        }
        if (child == null) {
            Slog.e(TAG, "couldn't inflate view for package " + n.pkg, exception);
            return null;
        }

        // This will try to handle text color for all notifications from apps, applying the appropriate
        // color if ID is possible, otherwise setting it to notification text color
        startRecurse(child);

        content.addView(child);

        row.setDrawingCacheEnabled(true);

        notification.view = row;
        notification.contentView = child;

        return row;
    }

    void startRecurse(View v) {
        ViewGroup vg = (ViewGroup) v;
        int childcount = vg.getChildCount();
        if (childcount > 0) {
            int i;
            for (i = 0; i < childcount; i++) {
                try {
                setViewColors((TextView) vg.getChildAt(i));
                } catch (Exception e) { }
                try {
                    startRecurse((View) vg.getChildAt(i));
                } catch (Exception e) { }
            }
        }
    }
    
    void setViewColors(TextView tv) {
        int tvID = 0;
        try {
            tvID = tv.getId();
            switch (tvID) {
            case com.android.internal.R.id.title:
                tv.setTextColor(notifications_title_color);
                break;
            case com.android.internal.R.id.text:
                tv.setTextColor(notifications_text_color);
                break;
            case com.android.internal.R.id.time:
                tv.setTextColor(notifications_time_color);
                break;
            default:
                tv.setTextColor(notifications_text_color);
            }
        } catch (Exception e) { }
    }
    
    
    void addNotificationView(StatusBarNotification notification) {
        if (notification.view != null) {
            throw new RuntimeException("Assertion failed: notification.view="
                    + notification.view);
        }

        LinearLayout parent = notification.data.ongoingEvent ? mOngoingItems : mLatestItems;

        View child = makeNotificationView(notification, parent);
        if (child == null) {
            return ;
        }

        int index = mNotificationData.getExpandedIndex(notification);
        parent.addView(child, index);
    }

    /**
     * Remove the old one and put the new one in its place.
     * @param notification the notification
     */
    void updateNotificationView(StatusBarNotification notification, NotificationData oldData) {
        NotificationData n = notification.data;
        if (oldData != null && n != null
                && n.when == oldData.when
                && n.ongoingEvent == oldData.ongoingEvent
                && n.contentView != null && oldData.contentView != null
                && n.contentView.getPackage() != null
                && oldData.contentView.getPackage() != null
                && oldData.contentView.getPackage().equals(n.contentView.getPackage())
                && oldData.contentView.getLayoutId() == n.contentView.getLayoutId()
                && notification.view != null) {
            mNotificationData.update(notification);
            try {
                n.contentView.reapply(mContext, notification.contentView);

                // update the contentIntent
                ViewGroup content = (ViewGroup)notification.view.findViewById(
                        com.android.internal.R.id.content);
                PendingIntent contentIntent = n.contentIntent;
                if (contentIntent != null) {
                    content.setOnClickListener(new Launcher(contentIntent, n.pkg, n.tag, n.id));
                }
            }
            catch (RuntimeException e) {
                // It failed to add cleanly.  Log, and remove the view from the panel.
                Slog.w(TAG, "couldn't reapply views for package " + n.contentView.getPackage(), e);
                removeNotificationView(notification);
            }
        } else {
            mNotificationData.update(notification);
            removeNotificationView(notification);
            addNotificationView(notification);
        }
        setAreThereNotifications();
    }

    void removeNotificationView(StatusBarNotification notification) {
        View v = notification.view;
        if (v != null) {
            ViewGroup parent = (ViewGroup)v.getParent();
            parent.removeView(v);
            notification.view = null;
        }
    }

    private void setAreThereNotifications() {
        boolean ongoing = mOngoingItems.getChildCount() != 0;
        boolean latest = mLatestItems.getChildCount() != 0;

        if (mNotificationData.hasClearableItems()) {
            mClearButton.setVisibility(View.VISIBLE);
        } else {
            mClearButton.setVisibility(View.INVISIBLE);
        }

        mOngoingTitle.setVisibility(ongoing ? View.VISIBLE : View.GONE);
        mLatestTitle.setVisibility(latest ? View.VISIBLE : View.GONE);

        if (ongoing || latest) {
            mNoNotificationsTitle.setVisibility(View.GONE);
        } else {
            mNoNotificationsTitle.setVisibility(View.VISIBLE);
        }
    }

    private void makeExpandedVisible() {
        if (SPEW) Slog.d(TAG, "Make expanded visible: expanded visible=" + mExpandedVisible);
        if (mExpandedVisible) {
            return;
        }
        refreshMusicStatus();
        mExpandedVisible = true;
        panelSlightlyVisible(true);
        
        updateExpandedViewPos(EXPANDED_LEAVE_ALONE);
        mExpandedParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        mExpandedParams.flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        mExpandedDialog.getWindow().setAttributes(mExpandedParams);
        mExpandedView.requestFocus(View.FOCUS_FORWARD);
        mTrackingView.setVisibility(View.VISIBLE);
        mExpandedView.setVisibility(View.VISIBLE);
        
        if (!mTicking) {
            setDateViewVisibility(true, com.android.internal.R.anim.fade_in);
        }
    }
    
    void animateExpand() {
        if (SPEW) Slog.d(TAG, "Animate expand: expanded=" + mExpanded);
        if ((mDisabled & StatusBarManager.DISABLE_EXPAND) != 0) {
            return ;
        }
        if (mExpanded) {
            return;
        }

        prepareTracking(0, true);
        performFling(0, 2000.0f, true);
    }
    
    void animateCollapse() {
        if (SPEW) {
            Slog.d(TAG, "animateCollapse(): mExpanded=" + mExpanded
                    + " mExpandedVisible=" + mExpandedVisible
                    + " mExpanded=" + mExpanded
                    + " mAnimating=" + mAnimating
                    + " mAnimY=" + mAnimY
                    + " mAnimVel=" + mAnimVel);
        }
        
        if (!mExpandedVisible) {
            return;
        }

        int y;
        if (mAnimating) {
            y = (int)mAnimY;
        } else {
            y = mDisplay.getHeight()-1;
        }
        // Let the fling think that we're open so it goes in the right direction
        // and doesn't try to re-open the windowshade.
        mExpanded = true;
        prepareTracking(y, false);
        performFling(y, -2000.0f, true);
    }
    
    void performExpand() {
        if (SPEW) Slog.d(TAG, "performExpand: mExpanded=" + mExpanded);
        if ((mDisabled & StatusBarManager.DISABLE_EXPAND) != 0) {
            return ;
        }
        if (mExpanded) {
            return;
        }

        // It seems strange to sometimes not expand...
        if (false) {
            synchronized (mNotificationData) {
                if (mNotificationData.size() == 0) {
                    return;
                }
            }
        }
        
        mExpanded = true;
        makeExpandedVisible();
        updateExpandedViewPos(EXPANDED_FULL_OPEN);

        if (false) postStartTracing();
    }

    void performCollapse() {
        if (SPEW) Slog.d(TAG, "performCollapse: mExpanded=" + mExpanded
                + " mExpandedVisible=" + mExpandedVisible);
        
        if (!mExpandedVisible) {
            return;
        }
        mExpandedVisible = false;
        panelSlightlyVisible(false);
        mExpandedParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        mExpandedParams.flags &= ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        mExpandedDialog.getWindow().setAttributes(mExpandedParams);
        mTrackingView.setVisibility(View.GONE);
        mExpandedView.setVisibility(View.GONE);

        if ((mDisabled & StatusBarManager.DISABLE_NOTIFICATION_ICONS) == 0) {
            setNotificationIconVisibility(true, com.android.internal.R.anim.fade_in);
        }
        setDateViewVisibility(false, com.android.internal.R.anim.fade_out);
        
        if (!mExpanded) {
            return;
        }
        mExpanded = false;
    }

    void doAnimation() {
        if (mAnimating) {
            if (SPEW) Slog.d(TAG, "doAnimation");
            if (SPEW) Slog.d(TAG, "doAnimation before mAnimY=" + mAnimY);
            incrementAnim();
            if (SPEW) Slog.d(TAG, "doAnimation after  mAnimY=" + mAnimY);
            if (mAnimY >= mDisplay.getHeight()-1) {
                if (SPEW) Slog.d(TAG, "Animation completed to expanded state.");
                mAnimating = false;
                updateExpandedViewPos(EXPANDED_FULL_OPEN);
                performExpand();
            }
            else if (mAnimY < mStatusBarView.getHeight()) {
                if (SPEW) Slog.d(TAG, "Animation completed to collapsed state.");
                mAnimating = false;
                updateExpandedViewPos(0);
                performCollapse();
            }
            else {
                updateExpandedViewPos((int)mAnimY);
                mCurAnimationTime += ANIM_FRAME_DURATION;
                mHandler.sendMessageAtTime(mHandler.obtainMessage(MSG_ANIMATE), mCurAnimationTime);
            }
        }
    }

    void stopTracking() {
        mTracking = false;
        mVelocityTracker.recycle();
        mVelocityTracker = null;
    }

    void incrementAnim() {
        long now = SystemClock.uptimeMillis();
        float t = ((float)(now - mAnimLastTime)) / 1000;            // ms -> s
        final float y = mAnimY;
        final float v = mAnimVel;                                   // px/s
        final float a = mAnimAccel;                                 // px/s/s
        mAnimY = y + (v*t) + (0.5f*a*t*t);                          // px
        mAnimVel = v + (a*t);                                       // px/s
        mAnimLastTime = now;                                        // ms
        //Slog.d(TAG, "y=" + y + " v=" + v + " a=" + a + " t=" + t + " mAnimY=" + mAnimY
        //        + " mAnimAccel=" + mAnimAccel);
    }

    void doRevealAnimation() {
        final int h = mCloseView.getHeight() + mStatusBarView.getHeight();
        if (mAnimatingReveal && mAnimating && mAnimY < h) {
            incrementAnim();
            if (mAnimY >= h) {
                mAnimY = h;
                updateExpandedViewPos((int)mAnimY);
            } else {
                updateExpandedViewPos((int)mAnimY);
                mCurAnimationTime += ANIM_FRAME_DURATION;
                mHandler.sendMessageAtTime(mHandler.obtainMessage(MSG_ANIMATE_REVEAL),
                        mCurAnimationTime);
            }
        }
    }
    
    void prepareTracking(int y, boolean opening) {
        mTracking = true;
        mVelocityTracker = VelocityTracker.obtain();
        if (opening) {
            mAnimAccel = 2000.0f;
            mAnimVel = 200;
            mAnimY = mStatusBarView.getHeight();
            updateExpandedViewPos((int)mAnimY);
            mAnimating = true;
            mAnimatingReveal = true;
            mHandler.removeMessages(MSG_ANIMATE);
            mHandler.removeMessages(MSG_ANIMATE_REVEAL);
            long now = SystemClock.uptimeMillis();
            mAnimLastTime = now;
            mCurAnimationTime = now + ANIM_FRAME_DURATION;
            mAnimating = true;
            mHandler.sendMessageAtTime(mHandler.obtainMessage(MSG_ANIMATE_REVEAL),
                    mCurAnimationTime);
            makeExpandedVisible();
        } else {
            // it's open, close it?
            if (mAnimating) {
                mAnimating = false;
                mHandler.removeMessages(MSG_ANIMATE);
            }
            updateExpandedViewPos(y + mViewDelta);
        }
    }
    
    void performFling(int y, float vel, boolean always) {
        mAnimatingReveal = false;
        mDisplayHeight = mDisplay.getHeight();

        mAnimY = y;
        mAnimVel = vel;

        //Slog.d(TAG, "starting with mAnimY=" + mAnimY + " mAnimVel=" + mAnimVel);

        if (mExpanded) {
            if (!always && (
                    vel > 200.0f
                    || (y > (mDisplayHeight-25) && vel > -200.0f))) {
                // We are expanded, but they didn't move sufficiently to cause
                // us to retract.  Animate back to the expanded position.
                mAnimAccel = 2000.0f;
                if (vel < 0) {
		    mAnimVel *= -1;
                }
            }
            else {
                // We are expanded and are now going to animate away.
                mAnimAccel = -2000.0f;
                if (vel > 0) {
                    mAnimVel *= -1;
                }
            }
        } else {
            if (always || (
                    vel > 200.0f
                    || (y > (mDisplayHeight/2) && vel > -200.0f))) {
                // We are collapsed, and they moved enough to allow us to
                // expand.  Animate in the notifications.
                mAnimAccel = 2000.0f;
                if (vel < 0) {
                    mAnimVel *= -1;
                }
            }
            else {
                // We are collapsed, but they didn't move sufficiently to cause
                // us to retract.  Animate back to the collapsed position.
                mAnimAccel = -2000.0f;
                if (vel > 0) {
                    mAnimVel *= -1;
                }
            }
        }
        //Slog.d(TAG, "mAnimY=" + mAnimY + " mAnimVel=" + mAnimVel
        //        + " mAnimAccel=" + mAnimAccel);

        long now = SystemClock.uptimeMillis();
        mAnimLastTime = now;
        mCurAnimationTime = now + ANIM_FRAME_DURATION;
        mAnimating = true;
        mHandler.removeMessages(MSG_ANIMATE);
        mHandler.removeMessages(MSG_ANIMATE_REVEAL);
        mHandler.sendMessageAtTime(mHandler.obtainMessage(MSG_ANIMATE), mCurAnimationTime);
        stopTracking();
    }
    
    boolean interceptTouchEvent(MotionEvent event) {
        if (SPEW) {
            Slog.d(TAG, "Touch: rawY=" + event.getRawY() + " event=" + event + " mDisabled="
                + mDisabled);
        }

        if ((mDisabled & StatusBarManager.DISABLE_EXPAND) != 0) {
            return false;
        }
        
        final int statusBarSize = mStatusBarView.getHeight();
        final int hitSize = statusBarSize*2;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            final int y = (int)event.getRawY();

            if (!mExpanded) {
                mViewDelta = statusBarSize - y;
            } else {
                mTrackingView.getLocationOnScreen(mAbsPos);
                mViewDelta = mAbsPos[1] + mTrackingView.getHeight() - y;
            }
            if ((!mExpanded && y < hitSize) ||
                    (mExpanded && y > (mDisplay.getHeight()-hitSize))) {

                // We drop events at the edge of the screen to make the windowshade come
                // down by accident less, especially when pushing open a device with a keyboard
                // that rotates (like g1 and droid)
                int x = (int)event.getRawX();
                final int edgeBorder = mEdgeBorder;
                if (x >= edgeBorder && x < mDisplay.getWidth() - edgeBorder) {
                    prepareTracking(y, !mExpanded);// opening if we're not already fully visible
                    mVelocityTracker.addMovement(event);
                }
            }
        } else if (mTracking) {
            mVelocityTracker.addMovement(event);
            final int minY = statusBarSize + mCloseView.getHeight();
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                int y = (int)event.getRawY();
                if (mAnimatingReveal && y < minY) {
                    // nothing
                } else  {
                    mAnimatingReveal = false;
                    updateExpandedViewPos(y + mViewDelta);
                }
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                mVelocityTracker.computeCurrentVelocity(1000);

                float yVel = mVelocityTracker.getYVelocity();
                boolean negative = yVel < 0;

                float xVel = mVelocityTracker.getXVelocity();
                if (xVel < 0) {
                    xVel = -xVel;
                }
                if (xVel > 150.0f) {
                    xVel = 150.0f; // limit how much we care about the x axis
                }

                float vel = (float)Math.hypot(yVel, xVel);
                if (negative) {
                    vel = -vel;
                }
                
                performFling((int)event.getRawY(), vel, false);
            }
            
        }
        return false;
    }

    private class Launcher implements View.OnClickListener {
        private PendingIntent mIntent;
        private String mPkg;
        private String mTag;
        private int mId;

        Launcher(PendingIntent intent, String pkg, String tag, int id) {
            mIntent = intent;
            mPkg = pkg;
            mTag = tag;
            mId = id;
        }

        public void onClick(View v) {
            try {
                // The intent we are sending is for the application, which
                // won't have permission to immediately start an activity after
                // the user switches to home.  We know it is safe to do at this
                // point, so make sure new activity switches are now allowed.
                ActivityManagerNative.getDefault().resumeAppSwitches();
            } catch (RemoteException e) {
            }
            int[] pos = new int[2];
            v.getLocationOnScreen(pos);
            Intent overlay = new Intent();
            overlay.setSourceBounds(
                    new Rect(pos[0], pos[1], pos[0]+v.getWidth(), pos[1]+v.getHeight()));
            try {
                mIntent.send(mContext, 0, overlay);
                mNotificationCallbacks.onNotificationClick(mPkg, mTag, mId);
            } catch (PendingIntent.CanceledException e) {
                // the stack trace isn't very helpful here.  Just log the exception message.
                Slog.w(TAG, "Sending contentIntent failed: " + e);
            }
            deactivate();
        }
    }

    private class MyTicker extends Ticker {
        MyTicker(Context context, StatusBarView sb) {
            super(context, sb);
        }
        
        @Override
        void tickerStarting() {
            mTicking = true;
            mIcons.setVisibility(View.GONE);
            mTickerView.setVisibility(View.VISIBLE);
            mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.push_up_in, null));
            mIcons.startAnimation(loadAnim(com.android.internal.R.anim.push_up_out, null));
            if (mExpandedVisible) {
                setDateViewVisibility(false, com.android.internal.R.anim.push_up_out);
            }
        }

        @Override
        void tickerDone() {
            mIcons.setVisibility(View.VISIBLE);
            mTickerView.setVisibility(View.GONE);
            mIcons.startAnimation(loadAnim(com.android.internal.R.anim.push_down_in, null));
            mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.push_down_out,
                        mTickingDoneListener));
            if (mExpandedVisible) {
                setDateViewVisibility(true, com.android.internal.R.anim.push_down_in);
            }
        }

        void tickerHalting() {
            mIcons.setVisibility(View.VISIBLE);
            mTickerView.setVisibility(View.GONE);
            mIcons.startAnimation(loadAnim(com.android.internal.R.anim.fade_in, null));
            mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.fade_out,
                        mTickingDoneListener));
            if (mExpandedVisible) {
                setDateViewVisibility(true, com.android.internal.R.anim.fade_in);
            }
        }
    }

    Animation.AnimationListener mTickingDoneListener = new Animation.AnimationListener() {;
        public void onAnimationEnd(Animation animation) {
            mTicking = false;
        }
        public void onAnimationRepeat(Animation animation) {
        }
        public void onAnimationStart(Animation animation) {
        }
    };

    private Animation loadAnim(int id, Animation.AnimationListener listener) {
        Animation anim = AnimationUtils.loadAnimation(mContext, id);
        if (listener != null) {
            anim.setAnimationListener(listener);
        }
        return anim;
    }

    public String viewInfo(View v) {
        return "(" + v.getLeft() + "," + v.getTop() + ")(" + v.getRight() + "," + v.getBottom()
                + " " + v.getWidth() + "x" + v.getHeight() + ")";
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump StatusBar from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }
        
        synchronized (mQueueLock) {
            pw.println("Current Status Bar state:");
            pw.println("  mExpanded=" + mExpanded
                    + ", mExpandedVisible=" + mExpandedVisible);
            pw.println("  mTicking=" + mTicking);
            pw.println("  mTracking=" + mTracking);
            pw.println("  mAnimating=" + mAnimating
                    + ", mAnimY=" + mAnimY + ", mAnimVel=" + mAnimVel
                    + ", mAnimAccel=" + mAnimAccel);
            pw.println("  mCurAnimationTime=" + mCurAnimationTime
                    + " mAnimLastTime=" + mAnimLastTime);
            pw.println("  mDisplayHeight=" + mDisplayHeight
                    + " mAnimatingReveal=" + mAnimatingReveal
                    + " mViewDelta=" + mViewDelta);
            pw.println("  mDisplayHeight=" + mDisplayHeight);
            final int N = mQueue.size();
            pw.println("  mQueue.size=" + N);
            for (int i=0; i<N; i++) {
                PendingOp op = mQueue.get(i);
                pw.println("    [" + i + "] key=" + op.key + " code=" + op.code + " visible="
                        + op.visible);
                pw.println("           iconData=" + op.iconData);
                pw.println("           notificationData=" + op.notificationData);
            }
            pw.println("  mExpandedParams: " + mExpandedParams);
            pw.println("  mExpandedView: " + viewInfo(mExpandedView));
            pw.println("  mExpandedDialog: " + mExpandedDialog);
            pw.println("  mTrackingParams: " + mTrackingParams);
            pw.println("  mTrackingView: " + viewInfo(mTrackingView));
            pw.println("  mOngoingTitle: " + viewInfo(mOngoingTitle));
            pw.println("  mOngoingItems: " + viewInfo(mOngoingItems));
            pw.println("  mLatestTitle: " + viewInfo(mLatestTitle));
            pw.println("  mLatestItems: " + viewInfo(mLatestItems));
            pw.println("  mNoNotificationsTitle: " + viewInfo(mNoNotificationsTitle));
            pw.println("  mCloseView: " + viewInfo(mCloseView));
            pw.println("  mTickerView: " + viewInfo(mTickerView));
            pw.println("  mScrollView: " + viewInfo(mScrollView)
                    + " scroll " + mScrollView.getScrollX() + "," + mScrollView.getScrollY());
            pw.println("mNotificationLinearLayout: " + viewInfo(mNotificationLinearLayout));
        }
        synchronized (mIconMap) {
            final int N = mIconMap.size();
            pw.println("  mIconMap.size=" + N);
            Set<IBinder> keys = mIconMap.keySet();
            int i=0;
            for (IBinder key: keys) {
                StatusBarIcon icon = mIconMap.get(key);
                pw.println("    [" + i + "] key=" + key);
                pw.println("           data=" + icon.mData);
                i++;
            }
        }
        synchronized (mNotificationData) {
            int N = mNotificationData.ongoingCount();
            pw.println("  ongoingCount.size=" + N);
            for (int i=0; i<N; i++) {
                StatusBarNotification n = mNotificationData.getOngoing(i);
                pw.println("    [" + i + "] key=" + n.key + " view=" + n.view);
                pw.println("           data=" + n.data);
            }
            N = mNotificationData.latestCount();
            pw.println("  ongoingCount.size=" + N);
            for (int i=0; i<N; i++) {
                StatusBarNotification n = mNotificationData.getLatest(i);
                pw.println("    [" + i + "] key=" + n.key + " view=" + n.view);
                pw.println("           data=" + n.data);
            }
        }
        synchronized (mDisableRecords) {
            final int N = mDisableRecords.size();
            pw.println("  mDisableRecords.size=" + N
                    + " mDisabled=0x" + Integer.toHexString(mDisabled));
            for (int i=0; i<N; i++) {
                DisableRecord tok = mDisableRecords.get(i);
                pw.println("    [" + i + "] what=0x" + Integer.toHexString(tok.what)
                                + " pkg=" + tok.pkg + " token=" + tok.token);
            }
        }
        
        if (false) {
            pw.println("see the logcat for a dump of the views we have created.");
            // must happen on ui thread
            mHandler.post(new Runnable() {
                    public void run() {
                        mStatusBarView.getLocationOnScreen(mAbsPos);
                        Slog.d(TAG, "mStatusBarView: ----- (" + mAbsPos[0] + "," + mAbsPos[1]
                                + ") " + mStatusBarView.getWidth() + "x"
                                + mStatusBarView.getHeight());
                        mStatusBarView.debug();

                        mExpandedView.getLocationOnScreen(mAbsPos);
                        Slog.d(TAG, "mExpandedView: ----- (" + mAbsPos[0] + "," + mAbsPos[1]
                                + ") " + mExpandedView.getWidth() + "x"
                                + mExpandedView.getHeight());
                        mExpandedView.debug();

                        mTrackingView.getLocationOnScreen(mAbsPos);
                        Slog.d(TAG, "mTrackingView: ----- (" + mAbsPos[0] + "," + mAbsPos[1]
                                + ") " + mTrackingView.getWidth() + "x"
                                + mTrackingView.getHeight());
                        mTrackingView.debug();
                    }
                });
        }
    }

    void onBarViewAttached() {
        WindowManager.LayoutParams lp;
        int pixelFormat;
        Drawable bg;

        /// ---------- Tracking View --------------
        pixelFormat = PixelFormat.TRANSLUCENT;
        bg = mTrackingView.getBackground();
        if (bg != null) {
            pixelFormat = bg.getOpacity();
        }

        lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                pixelFormat);
//        lp.token = mStatusBarView.getWindowToken();
        lp.gravity = Gravity.TOP | Gravity.FILL_HORIZONTAL;
        lp.setTitle("TrackingView");
        lp.y = mTrackingPosition;
        mTrackingParams = lp;

        WindowManagerImpl.getDefault().addView(mTrackingView, lp);
    }

    void onTrackingViewAttached() {
        WindowManager.LayoutParams lp;
        int pixelFormat;
        Drawable bg;

        /// ---------- Expanded View --------------
        pixelFormat = PixelFormat.TRANSLUCENT;

        final int disph = mDisplay.getHeight();
        lp = mExpandedDialog.getWindow().getAttributes();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = getExpandedHeight();
        lp.x = 0;
        mTrackingPosition = lp.y = -disph; // sufficiently large negative
        lp.type = WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL;
        lp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_DITHER
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        lp.format = pixelFormat;
        lp.gravity = Gravity.TOP | Gravity.FILL_HORIZONTAL;
        lp.setTitle("StatusBarExpanded");
        mExpandedDialog.getWindow().setAttributes(lp);
        mExpandedDialog.getWindow().setFormat(pixelFormat);
        mExpandedParams = lp;

        mExpandedDialog.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        mExpandedDialog.setContentView(mExpandedView,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                           ViewGroup.LayoutParams.MATCH_PARENT));
        mExpandedDialog.getWindow().setBackgroundDrawable(null);
        mExpandedDialog.show();
        FrameLayout hack = (FrameLayout)mExpandedView.getParent();
    }

    void setDateViewVisibility(boolean visible, int anim) {
        mDateView.setUpdates(visible);
        mDateView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        mDateView.startAnimation(loadAnim(anim, null));
    }

    void setNotificationIconVisibility(boolean visible, int anim) {
        int old = mNotificationIcons.getVisibility();
        int v = visible ? View.VISIBLE : View.INVISIBLE;
        if (old != v) {
            mNotificationIcons.setVisibility(v);
            mNotificationIcons.startAnimation(loadAnim(anim, null));
        }
    }

    void updateExpandedViewPos(int expandedPosition) {
        if (SPEW) {
            Slog.d(TAG, "updateExpandedViewPos before expandedPosition=" + expandedPosition
                    + " mTrackingParams.y=" + mTrackingParams.y
                    + " mTrackingPosition=" + mTrackingPosition);
        }

        int h = mStatusBarView.getHeight();
        int disph = mDisplay.getHeight();

        // If the expanded view is not visible, make sure they're still off screen.
        // Maybe the view was resized.
        if (!mExpandedVisible) {
            if (mTrackingView != null) {
                mTrackingPosition = -disph;
                if (mTrackingParams != null) {
                    mTrackingParams.y = mTrackingPosition;
                    WindowManagerImpl.getDefault().updateViewLayout(mTrackingView, mTrackingParams);
                }
            }
            if (mExpandedParams != null) {
                mExpandedParams.y = -disph;
                mExpandedDialog.getWindow().setAttributes(mExpandedParams);
            }
            return;
        }

        // tracking view...
        int pos;
        if (expandedPosition == EXPANDED_FULL_OPEN) {
            pos = h;
        }
        else if (expandedPosition == EXPANDED_LEAVE_ALONE) {
            pos = mTrackingPosition;
        }
        else {
            if (expandedPosition <= disph) {
                pos = expandedPosition;
            } else {
                pos = disph;
            }
            pos -= disph-h;
        }
        mTrackingPosition = mTrackingParams.y = pos;
        mTrackingParams.height = disph-h;
        WindowManagerImpl.getDefault().updateViewLayout(mTrackingView, mTrackingParams);

        if (mExpandedParams != null) {
            mCloseView.getLocationInWindow(mPositionTmp);
            final int closePos = mPositionTmp[1];

            mExpandedContents.getLocationInWindow(mPositionTmp);
            final int contentsBottom = mPositionTmp[1] + mExpandedContents.getHeight();

            mExpandedParams.y = pos + mTrackingView.getHeight()
                    - (mTrackingParams.height-closePos) - contentsBottom;
            int max = h;
            if (mExpandedParams.y > max) {
                mExpandedParams.y = max;
            }
            int min = mTrackingPosition;
            if (mExpandedParams.y < min) {
                mExpandedParams.y = min;
            }

            boolean visible = (mTrackingPosition + mTrackingView.getHeight()) > h;
            if (!visible) {
                // if the contents aren't visible, move the expanded view way off screen
                // because the window itself extends below the content view.
                mExpandedParams.y = -disph;
            }
            panelSlightlyVisible(visible);
            mExpandedDialog.getWindow().setAttributes(mExpandedParams);
        }

        if (SPEW) {
            Slog.d(TAG, "updateExpandedViewPos after  expandedPosition=" + expandedPosition
                    + " mTrackingParams.y=" + mTrackingParams.y
                    + " mTrackingPosition=" + mTrackingPosition
                    + " mExpandedParams.y=" + mExpandedParams.y
                    + " mExpandedParams.height=" + mExpandedParams.height);
        }
    }

    int getExpandedHeight() {
        return mDisplay.getHeight() - mStatusBarView.getHeight() - mCloseView.getHeight();
    }

    void updateExpandedHeight() {
        if (mExpandedView != null) {
            mExpandedParams.height = getExpandedHeight();
            mExpandedDialog.getWindow().setAttributes(mExpandedParams);
        }
    }

    /**
     * The LEDs are turned o)ff when the notification panel is shown, even just a little bit.
     * This was added last-minute and is inconsistent with the way the rest of the notifications
     * are handled, because the notification isn't really cancelled.  The lights are just
     * turned off.  If any other notifications happen, the lights will turn back on.  Steve says
     * this is what he wants. (see bug 1131461)
     */
    private boolean mPanelSlightlyVisible;
    void panelSlightlyVisible(boolean visible) {
        if (mPanelSlightlyVisible != visible) {
            mPanelSlightlyVisible = visible;
            if (visible) {
                // tell the notification manager to turn off the lights.
                mNotificationCallbacks.onPanelRevealed();
            }
        }
    }

    void performDisableActions(int net) {
        int old = mDisabled;
        int diff = net ^ old;
        mDisabled = net;

        // act accordingly
        if ((diff & StatusBarManager.DISABLE_EXPAND) != 0) {
            if ((net & StatusBarManager.DISABLE_EXPAND) != 0) {
                Slog.d(TAG, "DISABLE_EXPAND: yes");
                animateCollapse();
            }
        }
        if ((diff & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
            if ((net & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
                Slog.d(TAG, "DISABLE_NOTIFICATION_ICONS: yes");
                if (mTicking) {
                    mNotificationIcons.setVisibility(View.INVISIBLE);
                    mTicker.halt();
                } else {
                    setNotificationIconVisibility(false, com.android.internal.R.anim.fade_out);
                }
            } else {
                Slog.d(TAG, "DISABLE_NOTIFICATION_ICONS: no");
                if (!mExpandedVisible) {
                    setNotificationIconVisibility(true, com.android.internal.R.anim.fade_in);
                }
            }
        } else if ((diff & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
            if (mTicking && (net & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
                mTicker.halt();
            }
        }
    }

    private void updateColors() {
        mDateView.setTextColor(Settings.System.getInt(mContext.getContentResolver(), Settings.System.DATE_COLOR, date_color));
        mNoNotificationsTitle.setTextColor(Settings.System.getInt(mContext.getContentResolver(), Settings.System.NO_NOTIF_COLOR, no_notifications_color));
        mLatestTitle.setTextColor(Settings.System.getInt(mContext.getContentResolver(), Settings.System.LATEST_NOTIF_COLOR, latest_notifications_color));
        mOngoingTitle.setTextColor(Settings.System.getInt(mContext.getContentResolver(), Settings.System.ONGOING_NOTIF_COLOR, ongoing_notifications_color));
        mSpnLabel.setTextColor(Settings.System.getInt(mContext.getContentResolver(), Settings.System.SPN_LABEL_COLOR, spn_label_color));
        mPlmnLabel.setTextColor(Settings.System.getInt(mContext.getContentResolver(), Settings.System.PLMN_LABEL_COLOR, plmn_label_color));
        mClearButton.setTextColor(Settings.System.getInt(mContext.getContentResolver(), Settings.System.CLEAR_BUTTON_LABEL_COLOR, clear_button_label_color));
        tickerView.updateColors(Settings.System.getInt(mContext.getContentResolver(), Settings.System.NEW_NOTIF_TICKER_COLOR, new_notifications_ticker_color));
    }

    private View.OnClickListener mClearButtonListener = new View.OnClickListener() {
        public void onClick(View v) {
            mNotificationCallbacks.onClearAll();
            addPendingOp(OP_EXPAND, null, false);
        }
    };

    /** Music Control **/
   private View.OnClickListener mPlayListener = new View.OnClickListener() {
        public void onClick(View v) {
            refreshMusicStatus();
            mPlayIcon.setVisibility(View.GONE);
            mPauseIcon.setVisibility(View.VISIBLE);
            sendMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
    };

   private View.OnClickListener mPauseListener = new View.OnClickListener() {
        public void onClick(View v) {
            refreshMusicStatus();
            mPauseIcon.setVisibility(View.GONE);
            mPlayIcon.setVisibility(View.VISIBLE);
            sendMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
    };

   private View.OnClickListener mRewindListener = new View.OnClickListener() {
        public void onClick(View v) {
            sendMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        }
    };

   private View.OnClickListener mForwardListener = new View.OnClickListener() {
        public void onClick(View v) {
            sendMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_NEXT);
        }
    };

    private void sendMediaButtonEvent(int code) {
        long eventtime = SystemClock.uptimeMillis();

        Intent downIntent = new Intent(Intent.ACTION_MEDIA_BUTTON, null);
        KeyEvent downEvent = new KeyEvent(eventtime, eventtime, KeyEvent.ACTION_DOWN, code, 0);
        downIntent.putExtra(Intent.EXTRA_KEY_EVENT, downEvent);
        mContext.sendOrderedBroadcast(downIntent, null);

        Intent upIntent = new Intent(Intent.ACTION_MEDIA_BUTTON, null);
        KeyEvent upEvent = new KeyEvent(eventtime, eventtime, KeyEvent.ACTION_UP, code, 0);
        upIntent.putExtra(Intent.EXTRA_KEY_EVENT, upEvent);
        mContext.sendOrderedBroadcast(upIntent, null);
    }

    private void setupMusicControls() {
        am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        refreshMusicStatus();
        mPlayIcon.setOnClickListener(mPlayListener);
        mPauseIcon.setOnClickListener(mPauseListener);
        mRewindIcon.setOnClickListener(mRewindListener);
        mForwardIcon.setOnClickListener(mForwardListener);
    }

    private void refreshMusicStatus() {
        mIsMusicActive = am.isMusicActive();
        if (mStatusMusicControls && (mIsMusicActive || mStatusAlwaysMusic)) {
            mCarrierBox.setVisibility(View.GONE);
            mStatusbarMusicControls.setVisibility(View.VISIBLE);
            if (mIsMusicActive) {
                mPauseIcon.setVisibility(View.VISIBLE);
                mPlayIcon.setVisibility(View.GONE);
            } else {
                mPlayIcon.setVisibility(View.VISIBLE);
                mPauseIcon.setVisibility(View.GONE);
            }
        } else {
            mStatusbarMusicControls.setVisibility(View.GONE);
            mCarrierBox.setVisibility(View.VISIBLE);
            mPlayIcon.setVisibility(View.VISIBLE);
            mPauseIcon.setVisibility(View.GONE);
        }
    }

    /** Power Widget **/

   private View.OnLongClickListener mPowerLongListener = new View.OnLongClickListener() {
        public boolean onLongClick(View v) {
            LinearLayout layout = (LinearLayout)v;
            String type = (String)layout.getTag();
            PowerButton btn = mUsedPowerButtons.get(type);
            if(btn.launchActivity(mContext)) {
                deactivate();
            }
            return true;
        }
    };

   private View.OnClickListener mPowerListener = new View.OnClickListener() {
        public void onClick(View v) {
            LinearLayout layout = (LinearLayout)v;
            String type = (String)layout.getTag();
            if(mHideOnPowerButtonChange) {
                 deactivate();
            }
            PowerButton btn = mUsedPowerButtons.get(type);
            btn.toggleState(mContext);
            updateWidget();
        }
    };

    private void setupPowerWidget() {
        LinearLayout layout;
        HorizontalScrollView scroller = (HorizontalScrollView)mExpandedView.findViewById(R.id.exp_power_scroller);
        String lists = Settings.System.getString(mContext.getContentResolver(),
                                Settings.System.WIDGET_BUTTONS);
        Log.i("setupPowerWidget", "List: "+lists);
        if(lists == null) {
            lists = "toggleWifi|toggleBluetooth|toggleGPS|toggleBrightness|toggleMobileData";
        }
        List<String> list = Arrays.asList(lists.split("\\|"));
        clearWidget();

        scroller.setHorizontalScrollBarEnabled(false);

        int posi;
        for(posi = 0; posi < list.size(); posi++) {
            layout = (LinearLayout)mExpandedView.findViewById(PowerButton.getLayoutID(posi + 1));
            String buttonType = list.get(posi);
            layout.setVisibility(View.VISIBLE);
            layout.setTag(list.get(posi));
            layout.setOnLongClickListener(mPowerLongListener);
            layout.setOnClickListener(mPowerListener);
            setupWidget(buttonType, posi + 1);
        }
        updateWidget();

        mHideOnPowerButtonChange = (Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.EXPANDED_HIDE_ONCHANGE, 0) == 1);
    }

    private void setupWidget(String buttonType, int position) {
        PowerButton btn = null;
        if(PowerButton.TOGGLE_WIFI.equals(buttonType)) {
            btn = WifiButton.getInstance();
        } else if(PowerButton.TOGGLE_GPS.equals(buttonType)) {
            btn = GPSButton.getInstance();
        } else if(PowerButton.TOGGLE_BLUETOOTH.equals(buttonType)) {
            btn = BluetoothButton.getInstance();
        } else if(PowerButton.TOGGLE_BRIGHTNESS.equals(buttonType)) {
            btn = BrightnessButton.getInstance();
        } else if(PowerButton.TOGGLE_SOUND.equals(buttonType)) {
            btn = SoundButton.getInstance();
        } else if(PowerButton.TOGGLE_SYNC.equals(buttonType)) {
            btn = SyncButton.getInstance();
        } else if(PowerButton.TOGGLE_WIFIAP.equals(buttonType)) {
            btn = WifiApButton.getInstance();
        } else if(PowerButton.TOGGLE_SCREENTIMEOUT.equals(buttonType)) {
            btn = ScreenTimeoutButton.getInstance();
        } else if(PowerButton.TOGGLE_MOBILEDATA.equals(buttonType)) {
            btn = MobileDataButton.getInstance();
        } else if(PowerButton.TOGGLE_LOCKSCREEN.equals(buttonType)) {
            btn = LockScreenButton.getInstance();
        } else if(PowerButton.TOGGLE_NETWORKMODE.equals(buttonType)) {
            btn = NetworkModeButton.getInstance();
        } else if(PowerButton.TOGGLE_AUTOROTATE.equals(buttonType)) {
            btn = AutoRotateButton.getInstance();
        } else if(PowerButton.TOGGLE_AIRPLANE.equals(buttonType)) {
            btn = AirplaneButton.getInstance();
        } else if(PowerButton.TOGGLE_SLEEPMODE.equals(buttonType)) {
            btn = SleepButton.getInstance();
        }
        if (btn != null) {
            synchronized(mUsedPowerButtons) {
                btn.setupButton(position);
                mUsedPowerButtons.put(buttonType, btn);
            }
        }
    }

    private void clearWidget() {
        for(int posi = 0; posi < 14; posi++) {
            LinearLayout layout = (LinearLayout)mExpandedView.findViewById(PowerButton.getLayoutID(posi + 1));
            layout.setVisibility(View.GONE);
            layout.setTag("");
        }
        synchronized(mUsedPowerButtons) {
            Set<String> keys = mUsedPowerButtons.keySet();
            for (String key: keys) {
                PowerButton btn = mUsedPowerButtons.get(key);
                btn.setupButton(0);
            }
            mUsedPowerButtons.clear();
        }
    }

    private void updateStates() {
        synchronized(mUsedPowerButtons) {
            Set<String> keys = mUsedPowerButtons.keySet();
            for (String key: keys) {
                PowerButton btn = mUsedPowerButtons.get(key);
                btn.updateState(mContext);
            }
        }
    }

    private void updateViews() {
        synchronized(mUsedPowerButtons) {
            Set<String> keys = mUsedPowerButtons.keySet();
            for (String key: keys) {
                PowerButton btn = mUsedPowerButtons.get(key);
                btn.updateView(mContext, mExpandedView);
            }
        }
    }

    private void updateWidget() {
        updateStates();
        updateViews();
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                    || Intent.ACTION_SCREEN_OFF.equals(action)) {
                deactivate();
            }
            else if (Telephony.Intents.SPN_STRINGS_UPDATED_ACTION.equals(action)) {
                updateNetworkName(intent.getBooleanExtra(Telephony.Intents.EXTRA_SHOW_SPN, false),
                        intent.getStringExtra(Telephony.Intents.EXTRA_SPN),
                        intent.getBooleanExtra(Telephony.Intents.EXTRA_SHOW_PLMN, false),
                        intent.getStringExtra(Telephony.Intents.EXTRA_PLMN));
            }
            else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                updateResources();
            }
            else if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                WifiButton btn = (WifiButton)
                    mUsedPowerButtons.get(PowerButton.TOGGLE_WIFI);
                if (btn != null) btn.onReceive(context, intent);
            } else if (WifiManager.WIFI_AP_STATE_CHANGED_ACTION.equals(action)) {
                WifiApButton btn = (WifiApButton)
                    mUsedPowerButtons.get(PowerButton.TOGGLE_WIFIAP);
                if (btn != null) btn.onReceive(context, intent);
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                BluetoothButton btn = (BluetoothButton)
                    mUsedPowerButtons.get(PowerButton.TOGGLE_BLUETOOTH);
                if (btn != null) btn.onReceive(context, intent);
            } else if (NetworkModeButton.NETWORK_MODE_CHANGED.equals(action)) {
                NetworkModeButton btn = (NetworkModeButton)
                    mUsedPowerButtons.get(PowerButton.TOGGLE_NETWORKMODE);
                if (btn != null) btn.onReceive(context, intent);
            }
            updateWidget();
        }
    };

    void updateNetworkName(boolean showSpn, String spn, boolean showPlmn, String plmn) {
        // Double carrier
        mShowPlmnSb = (Settings.System.getInt(mContext.getContentResolver(), Settings.System.SHOW_PLMN_SB, 1) == 1);
        mShowSpnSb = (Settings.System.getInt(mContext.getContentResolver(), Settings.System.SHOW_SPN_SB, 1) == 1);
        if (false) {
            Slog.d(TAG, "updateNetworkName showSpn=" + showSpn + " spn=" + spn
                    + " showPlmn=" + showPlmn + " plmn=" + plmn);
        }
        boolean something = false;
        // Double carrier - bcrook
        if (showPlmn && mShowPlmnSb) {
            mPlmnLabel.setVisibility(View.VISIBLE);
            if (plmn != null) {
                mPlmnLabel.setText(plmn);
            } else {
                mPlmnLabel.setText(R.string.lockscreen_carrier_default);
            }
        } else {
            mPlmnLabel.setText("");
            mPlmnLabel.setVisibility(View.GONE);
        }
        // Double carrier - bcrook, refinements from Wysie
        if (showSpn && spn != null && mShowSpnSb) {
            mSpnLabel.setText(spn);
            mSpnLabel.setVisibility(View.VISIBLE);
            something = true;
        } else {
            mSpnLabel.setText("");
            mSpnLabel.setVisibility(View.GONE);
        }
    }

    /**
     * Reload some of our resources when the configuration changes.
     *
     * We don't reload everything when the configuration changes -- we probably
     * should, but getting that smooth is tough.  Someday we'll fix that.  In the
     * meantime, just update the things that we know change.
     */
    void updateResources() {
        Resources res = mContext.getResources();

        mClearButton.setText(mContext.getText(R.string.status_bar_clear_all_button));
        mOngoingTitle.setText(mContext.getText(R.string.status_bar_ongoing_events_title));
        mLatestTitle.setText(mContext.getText(R.string.status_bar_latest_events_title));
        mNoNotificationsTitle.setText(mContext.getText(R.string.status_bar_no_notifications_title));

        mEdgeBorder = res.getDimensionPixelSize(R.dimen.status_bar_edge_ignore);

        if (false) Slog.v(TAG, "updateResources");
    }

    //
    // tracing
    //

    void postStartTracing() {
        mHandler.postDelayed(mStartTracing, 3000);
    }

    void vibrate() {
        android.os.Vibrator vib = (android.os.Vibrator)mContext.getSystemService(
                Context.VIBRATOR_SERVICE);
        vib.vibrate(250);
    }

    Runnable mStartTracing = new Runnable() {
        public void run() {
            vibrate();
            SystemClock.sleep(250);
            Slog.d(TAG, "startTracing");
            android.os.Debug.startMethodTracing("/data/statusbar-traces/trace");
            mHandler.postDelayed(mStopTracing, 10000);
        }
    };

    Runnable mStopTracing = new Runnable() {
        public void run() {
            android.os.Debug.stopMethodTracing();
            Slog.d(TAG, "stopTracing");
            vibrate();
        }
    };

    class UninstallReceiver extends BroadcastReceiver {
        public UninstallReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
            filter.addDataScheme("package");
            mContext.registerReceiver(this, filter);
            IntentFilter sdFilter = new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
            mContext.registerReceiver(this, sdFilter);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String pkgList[] = null;
            if (Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(intent.getAction())) {
                pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            } else {
                Uri data = intent.getData();
                if (data != null) {
                    String pkg = data.getSchemeSpecificPart();
                    if (pkg != null) {
                        pkgList = new String[]{pkg};
                    }
                }
            }
            ArrayList<StatusBarNotification> list = null;
            if (pkgList != null) {
                synchronized (StatusBarService.this) {
                    for (String pkg : pkgList) {
                        list = mNotificationData.notificationsForPackage(pkg);
                    }
                }
            }

            if (list != null) {
                final int N = list.size();
                for (int i=0; i<N; i++) {
                    removeIcon(list.get(i).key);
                }
            }
        }
    }

    private void getNotBarConfig() {
        Resources res = mContext.getResources();
        /*
         * Setup color and bar type for notification strip
         */
        boolean useCustom = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.NOTIF_BAR_CUSTOM, 0) == 1;
        notifBarColorMask = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.NOTIF_BAR_COLOR, whiteColor);
        if (useCustom) {
            custNotBar = true;
        } else {
            custNotBar = false;
        }
        /*
         * Setup colors for expanded notification drawables
        */
        boolean useCustomExp = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.NOTIF_EXPANDED_BAR_CUSTOM, 0) == 1;
        expBarColorMask = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.NOTIF_EXPANDED_BAR_COLOR, whiteColor);
        int noalpha = expBarColorMask | 0xFF000000;
        if (useCustomExp) {
            closerDrawable = res.getDrawable(com.android.internal.R.drawable.status_bar_close_on_cust);
            expBarHeadDrawable = res.getDrawable(com.android.internal.R.drawable.status_bar_header_background_cust,
                    expBarColorMask, expPDMode);
            expBarNotifTitleDrawable = res.getDrawable(com.android.internal.R.drawable.title_bar_portrait_cust,
                    noalpha, expPDMode); // always solid
            custExpBar = true;
            } else {
            custExpBar = false;
            }
    }

    public class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        public void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NOTIF_EXPANDED_BAR_CUSTOM),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NOTIF_EXPANDED_BAR_COLOR),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NOTIF_BAR_CUSTOM),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NOTIF_BAR_COLOR),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NOTIF_ITEM_TITLE_COLOR),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NOTIF_ITEM_TEXT_COLOR),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NOTIF_ITEM_TIME_COLOR),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.DATE_COLOR),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NO_NOTIF_COLOR),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.LATEST_NOTIF_COLOR),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.ONGOING_NOTIF_COLOR),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SPN_LABEL_COLOR),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.PLMN_LABEL_COLOR),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.CLEAR_BUTTON_LABEL_COLOR),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NEW_NOTIF_TICKER_COLOR),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.EXPANDED_VIEW_WIDGET),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.EXPANDED_HIDE_ONCHANGE),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.AIRPLANE_MODE_ON),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.WIDGET_BUTTONS),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.TORCH_STATE),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.STATUSBAR_MUSIC_CONTROLS),
                         false, this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.STATUSBAR_ALWAYS_MUSIC_CONTROLS),
                         false, this);
        }

        @Override
        public void onChangeUri(Uri uri, boolean selfChange) {
            update(uri);
        }

        public void update(Uri uri) {
            ContentResolver resolver = mContext.getContentResolver();
            Resources res = mContext.getResources();
            updateColors();
            if(uri.equals(Settings.System.getUriFor(Settings.System.NOTIF_EXPANDED_BAR_CUSTOM)) ||
                uri.equals(Settings.System.getUriFor(Settings.System.NOTIF_EXPANDED_BAR_COLOR)) ||
                uri.equals(Settings.System.getUriFor(Settings.System.NOTIF_BAR_CUSTOM)) ||
                uri.equals(Settings.System.getUriFor(Settings.System.NOTIF_BAR_COLOR))) {

                getNotBarConfig();
                if (custExpBar) {
                    mExpandedView.findViewById(R.id.exp_view_lin_layout).
                            setBackgroundDrawable(expBarHeadDrawable);
                   mNoNotificationsTitle.setBackgroundDrawable(expBarNotifTitleDrawable);
                   mOngoingTitle.setBackgroundDrawable(expBarNotifTitleDrawable);
                   mLatestTitle.setBackgroundDrawable(expBarNotifTitleDrawable);
                }
                if (custNotBar) {
                    mStatusBarView.setBackgroundDrawable(
                            res.getDrawable(com.android.internal.R.drawable.statusbar_background_sq,
                                notifBarColorMask, notifPDMode));
                    mDateView.setBackgroundDrawable(
                            res.getDrawable(com.android.internal.R.drawable.statusbar_background_sq,
                                notifBarColorMask, notifPDMode));
                    mDateView.setPadding(6, 0, 6, 0);
                }
            } else if (uri.equals(Settings.System.getUriFor(Settings.System.WIDGET_BUTTONS))) {
                setupPowerWidget();
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.EXPANDED_HIDE_ONCHANGE))) {
                setupPowerWidget();
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.EXPANDED_VIEW_WIDGET))) {
                boolean powerWidget = Settings.System.getInt(mContext.getContentResolver(),
                            Settings.System.EXPANDED_VIEW_WIDGET, 1) == 1;
                if(!powerWidget) {
                    mExpandedView.findViewById(R.id.exp_power_stat).
                        setVisibility(View.GONE);
                } else {
                    mExpandedView.findViewById(R.id.exp_power_stat).
                        setVisibility(View.VISIBLE);
                }
            } else if (uri.equals(Settings.System.getUriFor(Settings.System.STATUSBAR_MUSIC_CONTROLS)) ||
                       uri.equals(Settings.System.getUriFor(Settings.System.STATUSBAR_ALWAYS_MUSIC_CONTROLS))) {
                mStatusMusicControls = Settings.System.getInt(mContext.getContentResolver(),
                            Settings.System.STATUSBAR_MUSIC_CONTROLS, 0) == 1;
                mStatusAlwaysMusic = Settings.System.getInt(mContext.getContentResolver(),
                            Settings.System.STATUSBAR_ALWAYS_MUSIC_CONTROLS, 0) == 1;
                mIsMusicActive = am.isMusicActive();
                if(!mStatusMusicControls || (!mStatusAlwaysMusic && !mIsMusicActive)) {
                    mStatusbarMusicControls.setVisibility(View.GONE);
                    mCarrierBox.setVisibility(View.VISIBLE);
                } else if (mStatusMusicControls && mStatusAlwaysMusic) {
                    mStatusbarMusicControls.setVisibility(View.VISIBLE);
                    mCarrierBox.setVisibility(View.GONE);
                }
            }
            updateWidget();
        }
    }
}
