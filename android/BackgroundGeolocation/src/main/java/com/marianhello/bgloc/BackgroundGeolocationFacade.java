package com.marianhello.bgloc;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;

import com.marianhello.bgloc.data.BackgroundLocation;
import com.marianhello.bgloc.data.ConfigurationDAO;
import com.marianhello.bgloc.data.DAOFactory;
import com.marianhello.bgloc.data.LocationDAO;
import com.marianhello.logging.DBLogReader;
import com.marianhello.logging.LogEntry;
import com.marianhello.logging.LogReader;
import com.marianhello.logging.LoggerManager;

import org.json.JSONException;

import java.util.Collection;

public class BackgroundGeolocationFacade implements ActivityCompat.OnRequestPermissionsResultCallback {

    public static final int BACKGROUND_MODE = 0;
    public static final int FOREGROUND_MODE = 1;
    public static final String[] PERMISSIONS = { Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION };

    private static final int PERMISSIONS_REQUEST = 1;
    private static final int MESSENGER_CLIENT_ID = 666;
    private static final int AUTHORIZATION_AUTHORIZED = 1;
    private static final int AUTHORIZATION_DENIED = 0;

    /** Messenger for communicating with the service. */
    private Messenger mService = null;
    /** Flag indicating whether we have called bind on the service. */
    private Boolean mIsBound = false;
    private Boolean locationModeChangeReceiverRegistered = false;
    private Config mConfig = null;
    private PluginDelegate mDelegate;

    private BackgroundLocation mStationaryLocation;

    private org.slf4j.Logger logger;

    Messenger mMessenger;

    public BackgroundGeolocationFacade(PluginDelegate delegate) {
        mDelegate = delegate;

        logger = LoggerManager.getLogger(BackgroundGeolocationFacade.class);
        LoggerManager.enableDBLogging();
        logger.info("initializing plugin");

        // TODO: investigate if we can enable background sync conditionally
//        final ResourceResolver res = ResourceResolver.newInstance(getApplication());
//        final String authority = res.getStringResource(Config.CONTENT_AUTHORITY_RESOURCE);
    }

    public void onAppDestroy() {
        logger.info("Destroying plugin");

        unregisterLocationModeChangeReceiver();
        // Unbind from the service
        doUnbindService();
        if (mConfig == null || mConfig.getStopOnTerminate()) {
            stopBackgroundService();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case PERMISSIONS_REQUEST: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length == 0) {
                    // permission denied
                    logger.info("User denied requested permissions");
                    mDelegate.onAuthorizationChanged(AUTHORIZATION_DENIED);
                    return;
                }
                for (int grant : grantResults) {
                    if (grant != PackageManager.PERMISSION_GRANTED) {
                        // permission denied
                        logger.info("User denied requested permissions");
                        mDelegate.onAuthorizationChanged(AUTHORIZATION_DENIED);
                        return;
                    }
                }

                // permission was granted
                // start service
                logger.info("User granted requested permissions");
                startAndBindBackgroundService();
                // watch location mode changes
                registerLocationModeChangeReceiver();

                return;
            }
        }
    }

    /**
     * Handler of incoming messages from service.
     */
    private class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            Bundle bundle;
            BackgroundLocation location;
            switch (msg.what) {
                case LocationService.MSG_LOCATION_UPDATE:
                    logger.debug("Sending location to webview");
                    bundle = msg.getData();
                    bundle.setClassLoader(LocationService.class.getClassLoader());
                    location = (BackgroundLocation) bundle.getParcelable(BackgroundLocation.BUNDLE_KEY);
                    mDelegate.onLocationChanged(location);

                    break;
                case LocationService.MSG_ON_STATIONARY:
                    logger.debug("Sending stationary location to webview");
                    bundle = msg.getData();
                    bundle.setClassLoader(LocationService.class.getClassLoader());
                    location = (BackgroundLocation) bundle.getParcelable(BackgroundLocation.BUNDLE_KEY);
                    mDelegate.onStationaryChanged(location);

                    break;
                case LocationService.MSG_ERROR:
                    logger.warn("Sending error to webview");
                    bundle = msg.getData();
                    bundle.setClassLoader(LocationService.class.getClassLoader());
                    Integer errorCode = bundle.getInt("code");
                    String errorMessage = bundle.getString("message");
                    mDelegate.onError(new PluginError(errorCode, errorMessage));

                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    /**
     * Class for interacting with the main interface of the service.
     */
    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            // This is called when the connection with the service has been
            // established, giving us the object we can use to
            // interact with the service.  We are communicating with the
            // service using a Messenger, so here we get a client-side
            // representation of that from the raw IBinder object.
            mService = new Messenger(service);
            mIsBound = true;

            // We want to monitor the service for as long as we are
            // connected to it.
            try {
                Message msg = Message.obtain(null,
                        LocationService.MSG_REGISTER_CLIENT);
                msg.replyTo = mMessenger;
                msg.arg1 = MESSENGER_CLIENT_ID;
                mService.send(msg);
            } catch (RemoteException e) {
                // In this case the service has crashed before we could even
                // do anything with it; we can count on soon being
                // disconnected (and then reconnected if it can be restarted)
                // so there is no need to do anything here.
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            mService = null;
            mIsBound = false;
        }
    };

    private BroadcastReceiver locationModeChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            logger.debug("Received AUTHORIZATION_EVENT");
            try {
                mDelegate.onAuthorizationChanged(getAuthorizationStatus());
            } catch (SettingNotFoundException e) {
                mDelegate.onError(new PluginError(PluginError.SETTINGS_ERROR, "Error occured while determining location mode"));
            }
        }
    };

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void registerLocationModeChangeReceiver () {
        if (locationModeChangeReceiverRegistered) return;

        getContext().registerReceiver(locationModeChangeReceiver, new IntentFilter(LocationManager.MODE_CHANGED_ACTION));
        locationModeChangeReceiverRegistered = true;
    }

    private void unregisterLocationModeChangeReceiver () {
        if (locationModeChangeReceiverRegistered == false) return;

        Context context = getContext();
        if (context != null) {
            context.unregisterReceiver(locationModeChangeReceiver);
        }
        locationModeChangeReceiverRegistered = false;
    }

    public void start() throws JSONException {
        if (hasPermissions(getContext(), PERMISSIONS)) {
            logger.debug("Permissions granted");
            startAndBindBackgroundService();
            // watch location mode changes
            registerLocationModeChangeReceiver();
        } else {
            logger.debug("Permissions not granted");
            requestPermissions(PERMISSIONS);
        }
    }

    public void stop() {
        unregisterLocationModeChangeReceiver();
        doUnbindService();
        stopBackgroundService();
    }

    public Collection<BackgroundLocation> getLocations() {
        LocationDAO dao = DAOFactory.createLocationDAO(getContext());
        return dao.getAllLocations();
    }

    public Collection<BackgroundLocation> getValidLocations() {
        LocationDAO dao = DAOFactory.createLocationDAO(getContext());
        return dao.getValidLocations();
    }

    public BackgroundLocation getStationaryLocation() {
        return mStationaryLocation;
    }

    public void deleteLocation(Long locationId) {
        logger.info("Deleting location locationId={}", locationId);
        LocationDAO dao = DAOFactory.createLocationDAO(getContext());
        dao.deleteLocation(locationId.longValue());
    }

    public void deleteAllLocations() {
        logger.info("Deleting all locations");
        LocationDAO dao = DAOFactory.createLocationDAO(getContext());
        dao.deleteAllLocations();
    }

    public void switchMode(int mode) {
        if (mode == BACKGROUND_MODE) {


        } else if (mode == FOREGROUND_MODE) {
            if (LocationService.isRunning()) {
                if (!mIsBound) {
                    doBindService();
                }
                if (!locationModeChangeReceiverRegistered) {
                    registerLocationModeChangeReceiver();
                }
            }
        }
        Message msg = Message.obtain(null,
                LocationService.MSG_SWITCH_MODE);
        msg.replyTo = mMessenger;
        msg.arg1 = mode;
        serviceSend(msg);
    }

    public void configure(Config config) {
        try {
            persistConfiguration(config);
            logger.debug("Service configured with: {}", config.toString());
            mConfig = config;

            Message msg = Message.obtain(null,
                    LocationService.MSG_CONFIGURE);
            msg.setData(config.toBundle());

            serviceSend(msg);
        } catch (NullPointerException e) {
            logger.error("Configuration error: {}", e.getMessage());
            mDelegate.onError(new PluginError(PluginError.CONFIGURE_ERROR, e.getMessage()));
        }
    }

    public Config getConfig() throws JSONException {
        Config config = mConfig;
        try {
            if (config == null) {
                config = getStoredOrDefaultConfig();
            }
            return config;
        } catch (JSONException e) {
            logger.error("Error getting config: {}", e.getMessage());
            throw e;
        }
    }

    public Collection<LogEntry> getLogEntries(int limit) {
        LogReader logReader = new DBLogReader();
        return logReader.getEntries(limit);
    }

    public boolean hasPermissions() {
        return hasPermissions(getContext(), PERMISSIONS);
    }

    public int getAuthorizationStatus() throws SettingNotFoundException {
        boolean enabled = isLocationEnabled(getContext());
        return enabled ? AUTHORIZATION_AUTHORIZED : AUTHORIZATION_DENIED;
    }

    private Config getStoredOrDefaultConfig() throws JSONException {
        Config config = getStoredConfig();
        if (config == null) {
            config = new Config();
        }
        return config;
    }

    private Config getStoredConfig() throws JSONException {
        ConfigurationDAO dao = DAOFactory.createConfigurationDAO(getContext());
        return dao.retrieveConfiguration();
    }

    private void requestPermissions(String[] permissions) {
        logger.debug("Requesting permissions");
        ActivityCompat.requestPermissions(mDelegate.getActivity(), permissions, PERMISSIONS_REQUEST);
    }

    private void startAndBindBackgroundService() {
        try {
            startBackgroundService();
            doBindService();
        } catch (JSONException e) {
            logger.error("Error starting service: {}", e.getMessage());
            mDelegate.onError(new PluginError(PluginError.SERVICE_ERROR, e.getMessage()));
        }
    }

    private void startBackgroundService() throws JSONException {
        if (LocationService.isRunning()) return;

        logger.info("Starting bg service");
        Context context = getContext();
        Intent locationServiceIntent = new Intent(context, LocationService.class);

        if (mConfig == null) {
            logger.warn("Attempt to start unconfigured service. Will use stored or default.");
            mConfig = getStoredOrDefaultConfig();
        }

        locationServiceIntent.putExtra("config", mConfig);
        locationServiceIntent.addFlags(Intent.FLAG_FROM_BACKGROUND);
        // start service to keep service running even if no clients are bound to it
        context.startService(locationServiceIntent);
        mDelegate.onLocationResume();
    }

    private void stopBackgroundService() {
        if (!LocationService.isRunning()) return;

        logger.info("Stopping bg service");
        Context context = getContext();
        context.stopService(new Intent(context, LocationService.class));
        mDelegate.onLocationPause();
    }

    private void doBindService() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                bindService();
            }
        });
    }

    private void doUnbindService() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                unbindService();
            }
        });
    }

    private void bindService() {
        // Establish a connection with the service.  We use an explicit
        // class name because there is no reason to be able to let other
        // applications replace our component.
        if (mIsBound) return;

        logger.debug("Binding to service");
        mMessenger = new Messenger(new IncomingHandler());

        final Context context = getContext();
        Intent locationServiceIntent = new Intent(context, LocationService.class);
//        locationServiceIntent.putExtra("config", mConfig);
        context.bindService(locationServiceIntent, mConnection, Context.BIND_IMPORTANT);
    }

    private void unbindService () {
        if (mIsBound == false) return;

        logger.debug("Unbinding from service");
        // If we have received the service, and hence registered with
        // it, then now is the time to unregister.
        if (mService != null) {
            try {
                Message msg = Message.obtain(null,
                        LocationService.MSG_UNREGISTER_CLIENT);
                msg.replyTo = mMessenger;
                msg.arg1 = MESSENGER_CLIENT_ID;
                mService.send(msg);
            } catch (RemoteException e) {
                // There is nothing special we need to do if the service
                // has crashed.
            }

            // Detach our existing connection.
            final Context context = getContext();

            if (context != null) { //workaround for issue RN #9791
                // not unbinding from service will cause ServiceConnectionLeaked
                // but there is not much we can do about it now
                context.unbindService(mConnection);
            }

            mIsBound = false;
        }
    }

    private void runOnUiThread(Runnable runnable) {
        getActivity().runOnUiThread(runnable);
    }

    private void serviceSend(Message message) {
        if (mService != null) {
            try {
                mService.send(message);
            } catch (RemoteException e) {
                logger.error("Service send exception: {}", e.getMessage());
            }
        }
    }

    private void persistConfiguration(Config config) throws NullPointerException {
        ConfigurationDAO dao = DAOFactory.createConfigurationDAO(getContext());
        dao.persistConfiguration(config);
    }

    private Context getContext() {
        return mDelegate.getContext();
    }

    private Activity getActivity() {
        return mDelegate.getActivity();
    }

    public static void showAppSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        context.startActivity(intent);
    }

    public static void showLocationSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        context.startActivity(intent);
    }

    public static boolean isLocationEnabled(Context context) throws SettingNotFoundException {
        int locationMode = 0;
        String locationProviders;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            locationMode = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.LOCATION_MODE);
            return locationMode != Settings.Secure.LOCATION_MODE_OFF;

        } else {
            locationProviders = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.LOCATION_PROVIDERS_ALLOWED);
            return !TextUtils.isEmpty(locationProviders);
        }
    }

    public static boolean hasPermissions(Context context, String[] permissions) {
        for (String perm: permissions) {
            if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
}
