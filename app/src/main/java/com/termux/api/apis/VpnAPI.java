package com.termux.api.apis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.JsonWriter;

import com.termux.api.R;
import com.termux.api.util.ResultReturner;
import com.termux.shared.logger.Logger;

import java.io.IOException;

public class VpnAPI {

    private static final String LOG_TAG = "VpnAPI";

    public static void onReceive(final Context context, final Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        Intent vpnServiceIntent = new Intent(context, TermuxVpnService.class);
        vpnServiceIntent.setAction(intent.getAction());
        if (intent.getExtras() != null) {
            vpnServiceIntent.putExtras(intent.getExtras());
        }

        String action = intent.getAction();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && "start".equals(action)) {
            context.startForegroundService(vpnServiceIntent);
        } else {
            context.startService(vpnServiceIntent);
        }
    }

    public static class TermuxVpnService extends VpnService {

        private static final String LOG_TAG = "TermuxVpnService";
        private static final int NOTIFICATION_ID = 4832;
        private static final String NOTIFICATION_CHANNEL_ID = "termux-api-vpn";
        private static final String NOTIFICATION_CHANNEL_NAME = "Termux API VPN";

        private static final Object VPN_LOCK = new Object();
        private static ParcelFileDescriptor tunnelInterface;
        private static long startedAtMs;

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            Logger.logDebug(LOG_TAG, "onStartCommand");

            if (intent == null) {
                return Service.START_NOT_STICKY;
            }

            String action = intent.getAction();
            if (action == null) {
                action = intent.getStringExtra("action");
            }

            final String command = action;
            ResultReturner.returnData(this, intent, new ResultReturner.ResultJsonWriter() {
                @Override
                public void writeJson(JsonWriter out) throws Exception {
                    out.beginObject();
                    out.name("api_method").value("Vpn");
                    out.name("command").value(command == null ? "" : command);

                    if (command == null) {
                        out.name("ok").value(false);
                        out.name("error").value("Missing action; expected one of prepare/start/stop/status");
                        out.endObject();
                        return;
                    }

                    switch (command) {
                        case "prepare":
                            handlePrepare(out);
                            break;
                        case "start":
                            handleStart(out);
                            break;
                        case "stop":
                            handleStop(out);
                            break;
                        case "status":
                            handleStatus(out);
                            break;
                        default:
                            out.name("ok").value(false);
                            out.name("error").value("Unknown action: " + command);
                            out.name("supported_actions").beginArray();
                            out.value("prepare");
                            out.value("start");
                            out.value("stop");
                            out.value("status");
                            out.endArray();
                    }

                    out.endObject();
                }
            });

            return Service.START_NOT_STICKY;
        }

        @Override
        public void onRevoke() {
            Logger.logInfo(LOG_TAG, "onRevoke called; cleaning up VPN state");
            synchronized (VPN_LOCK) {
                cleanupTunnelLocked();
            }
            stopForeground(true);
            stopSelf();
            super.onRevoke();
        }

        @Override
        public void onDestroy() {
            Logger.logDebug(LOG_TAG, "onDestroy");
            synchronized (VPN_LOCK) {
                cleanupTunnelLocked();
            }
            stopForeground(true);
            super.onDestroy();
        }

        @Override
        public IBinder onBind(Intent intent) {
            return super.onBind(intent);
        }

        private void handlePrepare(JsonWriter out) throws IOException {
            Intent prepareIntent = VpnService.prepare(this);
            boolean prepared = (prepareIntent == null);

            out.name("ok").value(true);
            out.name("prepared").value(prepared);

            if (!prepared) {
                prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(prepareIntent);
                out.name("consent_required").value(true);
                out.name("consent_launched").value(true);
            } else {
                out.name("consent_required").value(false);
                out.name("consent_launched").value(false);
            }
        }

        private void handleStart(JsonWriter out) throws IOException {
            synchronized (VPN_LOCK) {
                if (tunnelInterface != null) {
                    out.name("ok").value(true);
                    out.name("running").value(true);
                    out.name("already_running").value(true);
                    out.name("prepared").value(VpnService.prepare(this) == null);
                    return;
                }

                if (VpnService.prepare(this) != null) {
                    out.name("ok").value(false);
                    out.name("running").value(false);
                    out.name("prepared").value(false);
                    out.name("error").value("VPN permission not granted; run prepare first");
                    return;
                }

                ParcelFileDescriptor established = null;
                try {
                    Builder builder = new Builder();
                    builder.setSession("Termux API VPN");
                    builder.addAddress("10.8.0.2", 32);
                    builder.addRoute("0.0.0.0", 0);
                    established = builder.establish();
                    if (established == null) {
                        out.name("ok").value(false);
                        out.name("running").value(false);
                        out.name("prepared").value(true);
                        out.name("error").value("Failed to establish VPN interface");
                        return;
                    }

                    tunnelInterface = established;
                    startedAtMs = System.currentTimeMillis();
                    startForeground(NOTIFICATION_ID, buildForegroundNotification());

                    out.name("ok").value(true);
                    out.name("running").value(true);
                    out.name("prepared").value(true);
                    out.name("session").value("Termux API VPN");
                } catch (Exception e) {
                    if (established != null) {
                        try {
                            established.close();
                        } catch (IOException ignored) {
                        }
                    }
                    out.name("ok").value(false);
                    out.name("running").value(false);
                    out.name("prepared").value(VpnService.prepare(this) == null);
                    out.name("error").value("Failed to start VPN: " + e.getMessage());
                }
            }
        }

        private void handleStop(JsonWriter out) throws IOException {
            boolean wasRunning;
            synchronized (VPN_LOCK) {
                wasRunning = tunnelInterface != null;
                cleanupTunnelLocked();
                startedAtMs = 0L;
            }
            stopForeground(true);
            stopSelf();

            out.name("ok").value(true);
            out.name("stopped").value(true);
            out.name("was_running").value(wasRunning);
            out.name("running").value(false);
            out.name("prepared").value(VpnService.prepare(this) == null);
        }

        private void handleStatus(JsonWriter out) throws IOException {
            ParcelFileDescriptor current;
            synchronized (VPN_LOCK) {
                current = tunnelInterface;
            }

            boolean running = current != null;
            out.name("ok").value(true);
            out.name("prepared").value(VpnService.prepare(this) == null);
            out.name("running").value(running);
            out.name("session").value("Termux API VPN");
            out.name("tun_fd").value(running ? current.getFd() : -1);
            out.name("started_at_ms").value(startedAtMs);
        }

        private void cleanupTunnelLocked() {
            if (tunnelInterface == null) {
                return;
            }

            try {
                tunnelInterface.close();
            } catch (IOException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to close VPN tunnel interface", e);
            } finally {
                tunnelInterface = null;
            }
        }

        private Notification buildForegroundNotification() {
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Termux API VPN session status");
                if (notificationManager != null) {
                    notificationManager.createNotificationChannel(channel);
                }

                return new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("Termux API VPN")
                    .setContentText("Experimental VPN session is active")
                    .setSmallIcon(R.drawable.ic_event_note_black_24dp)
                    .setOngoing(true)
                    .build();
            }

            return new Notification.Builder(this)
                .setContentTitle("Termux API VPN")
                .setContentText("Experimental VPN session is active")
                .setSmallIcon(R.drawable.ic_event_note_black_24dp)
                .setOngoing(true)
                .build();
        }
    }
}
