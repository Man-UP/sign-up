package com.appspot.manup.signup;

import android.app.Application;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import com.appspot.manup.signup.data.DataManager;
import com.appspot.manup.signup.data.DataManager.OnChangeListener;
import com.appspot.manup.signup.extrainfo.ExtraInfoService;
import com.appspot.manup.signup.swipeupclient.SwipeUpClientService;

public final class SignUpApplication extends Application implements OnChangeListener,
        OnSharedPreferenceChangeListener
{
    @SuppressWarnings("unused")
    private static final String TAG = SignUpApplication.class.getSimpleName();

    private final BroadcastReceiver mBtReciever = new BroadcastReceiver()
    {
        @Override
        public void onReceive(final Context context, final Intent intent)
        {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction()))
            {
                final int btState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.STATE_OFF);
                mHasBtConnection = btState == BluetoothAdapter.STATE_ON;
                controlSwipeUpClientService();
            } // if
        } // onReceiver(Context, Intent)
    };

    private final BroadcastReceiver mNetworkReciever = new BroadcastReceiver()
    {
        @Override
        public void onReceive(final Context context, final Intent intent)
        {
            final NetworkInfo info = (NetworkInfo) intent.getParcelableExtra(
                    ConnectivityManager.EXTRA_NETWORK_INFO);
            mHasNetworkConnection = info.isConnected();
            controlAllServices();
        } // onReceive(Context, Intent)
    };

    private volatile boolean mHasBtConnection = false;
    private volatile boolean mHasNetworkConnection = false;
    private volatile boolean mExtraLookupEnabled = false;
    private volatile boolean mShouldUploadSignatures = false;
    private volatile boolean mListenForSwipeUp = false;

    public SignUpApplication()
    {
        super();
    } // constructor()

    @Override
    public void onCreate()
    {
        new Thread()
        {
            public void run()
            {
                init();
            } // run()
        }.start();

        registerReceiver(mBtReciever,
                new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        registerReceiver(mNetworkReciever,
                new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

        DataManager.registerListener(this);
    } // onCreate()

    private void init()
    {
        final Preferences prefs = new Preferences(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        mExtraLookupEnabled = prefs.extraInfoLookupEnabled();
        mListenForSwipeUp = prefs.listenForSwipeUp();
        mShouldUploadSignatures = prefs.shouldUploadSignatures();
        controlAllServices();
    } // loadPreferences()

    @Override
    public void onChange()
    {
        controlExtraInfoService();
        controlUploadService();
    } // onChange()

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences,
            final String key)
    {
        final Preferences prefs = new Preferences(sharedPreferences);

        if (prefs.isLdapLookEnabledKey(key))
        {
            mExtraLookupEnabled = prefs.extraInfoLookupEnabled();
            controlExtraInfoService();
        } // if
        else if (prefs.isListenForSwipeUpKey(key))
        {
            mListenForSwipeUp = prefs.listenForSwipeUp();
            controlSwipeUpClientService();
        } // else if
        else if (prefs.isShouldUploadSignaturesKey(key))
        {
            mShouldUploadSignatures = prefs.shouldUploadSignatures();
            controlUploadService();
        } // else if
    } // onSharedPreferenceChanged(SharedPreferences, String)

    private void controlAllServices()
    {
        controlExtraInfoService();
        controlSwipeUpClientService();
        controlUploadService();
    } // controlAllServices()

    private void controlExtraInfoService()
    {
        controlService(ExtraInfoService.class, mExtraLookupEnabled, mHasNetworkConnection);
    } // controlExtraInfoService()

    private void controlSwipeUpClientService()
    {
        controlService(SwipeUpClientService.class, mListenForSwipeUp, mHasBtConnection);
    } // controlSwipeUpService()

    private void controlUploadService()
    {
        controlService(UploadService.class, mShouldUploadSignatures, mHasNetworkConnection);
    } // controlUploadService()

    private <S extends Service> void controlService(final Class<S> service,
            final boolean startService, final boolean hasConnectivity)
    {
        final Intent intent = new Intent(this, service);

        if (startService && hasConnectivity)
        {
            startService(intent);
        } // if
        else
        {
            stopService(intent);
        } // else
    } // controlService(Class, boolean)

} // class SignUpApplication
