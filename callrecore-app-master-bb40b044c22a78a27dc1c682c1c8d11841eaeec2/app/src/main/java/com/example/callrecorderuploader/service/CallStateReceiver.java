package com.example.callrecorderuploader.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.Log;

public class CallStateReceiver extends BroadcastReceiver {
    private static final String TAG = "CallStateReceiver";
    private static int lastState = TelephonyManager.CALL_STATE_IDLE;
    private static boolean isIncoming;
    private static String savedNumber;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() == null) {
            return;
        }
        Log.d(TAG, "Action received: " + intent.getAction());

        if (intent.getAction().equals(Intent.ACTION_NEW_OUTGOING_CALL)) {
            savedNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
            Log.d(TAG, "Outgoing call to: " + savedNumber);
            isIncoming = false;
        } else if (intent.getAction().equals(TelephonyManager.ACTION_PHONE_STATE_CHANGED)) {
            String stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
            String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
            int state = TelephonyManager.CALL_STATE_IDLE;

            if (stateStr != null) {
                if (stateStr.equals(TelephonyManager.EXTRA_STATE_OFFHOOK)) {
                    state = TelephonyManager.CALL_STATE_OFFHOOK;
                } else if (stateStr.equals(TelephonyManager.EXTRA_STATE_RINGING)) {
                    state = TelephonyManager.CALL_STATE_RINGING;
                } else if (stateStr.equals(TelephonyManager.EXTRA_STATE_IDLE)) {
                    state = TelephonyManager.CALL_STATE_IDLE;
                }
            }
            Log.d(TAG, "Phone state changed: " + stateStr + ", Incoming number: " + number);
            onCallStateChanged(context, state, number);
        }
    }

    public void onCallStateChanged(Context context, int state, String number) {
        if (lastState == state) {
            Log.d(TAG, "State unchanged: " + state);
            return;
        }
        Intent serviceIntent = new Intent(context, RecordingService.class);

        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                isIncoming = true;
                savedNumber = number;
                Log.d(TAG, "Incoming call ringing from: " + savedNumber);
                serviceIntent.putExtra(RecordingService.EXTRA_PHONE_NUMBER, savedNumber);
                serviceIntent.putExtra(RecordingService.EXTRA_CALL_STATE, "RINGING");
                break;
            case TelephonyManager.CALL_STATE_OFFHOOK:
                if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                    Log.d(TAG, "Incoming call answered from: " + savedNumber);
                } else {
                    Log.d(TAG, "Outgoing call connected to: " + savedNumber);
                     if (savedNumber == null && number != null) {
                        savedNumber = number;
                    }
                }
                serviceIntent.putExtra(RecordingService.EXTRA_PHONE_NUMBER, savedNumber);
                serviceIntent.putExtra(RecordingService.EXTRA_CALL_STATE, "OFFHOOK");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                break;
            case TelephonyManager.CALL_STATE_IDLE:
                if (lastState == TelephonyManager.CALL_STATE_RINGING || lastState == TelephonyManager.CALL_STATE_OFFHOOK) {
                    Log.d(TAG, "Call ended. Number was: " + savedNumber);
                    serviceIntent.putExtra(RecordingService.EXTRA_PHONE_NUMBER, savedNumber);
                    serviceIntent.putExtra(RecordingService.EXTRA_CALL_STATE, "IDLE");
                     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                }
                savedNumber = null;
                break;
        }
        lastState = state;
    }
}
