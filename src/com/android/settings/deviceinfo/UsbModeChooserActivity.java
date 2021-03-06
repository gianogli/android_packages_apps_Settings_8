/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.settings.deviceinfo;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.support.annotation.VisibleForTesting;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Checkable;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.settings.R;
import com.android.settingslib.RestrictedLockUtils;

import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

/**
 * UI for the USB chooser dialog.
 *
 */
public class UsbModeChooserActivity extends Activity {

    public static final int[] DEFAULT_MODES = {
        UsbBackend.MODE_POWER_SINK | UsbBackend.MODE_DATA_NONE,
        UsbBackend.MODE_POWER_SOURCE | UsbBackend.MODE_DATA_NONE,
        UsbBackend.MODE_POWER_SINK | UsbBackend.MODE_DATA_MTP,
        UsbBackend.MODE_POWER_SINK | UsbBackend.MODE_DATA_PTP,
        UsbBackend.MODE_POWER_SINK | UsbBackend.MODE_DATA_MIDI,
        UsbBackend.MODE_POWER_SINK | UsbBackend.MODE_DATA_UMS
    };

    private UsbBackend mBackend;
    private AlertDialog mDialog;
    private LayoutInflater mLayoutInflater;
    private EnforcedAdmin mEnforcedAdmin;

    private BroadcastReceiver mDisconnectedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_STATE.equals(action)) {
                boolean connected = intent.getBooleanExtra(UsbManager.USB_CONNECTED, false);
                boolean hostConnected =
                        intent.getBooleanExtra(UsbManager.USB_HOST_CONNECTED, false);
                if (!connected && !hostConnected) {
                    mDialog.dismiss();
                }
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        mLayoutInflater = LayoutInflater.from(this);

        //Since the Settings App may be killed during the boot process,
        //the memory of UsbModeChooserReceiver will be released.
        //While reboot MUT with connecting USB cable, we need make sure
        //the value of mSoftSwitch is true before the USB Mode Dialog show.
        UsbModeChooserReceiver.mSoftSwitch = true;

        mDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.usb_use)
                .setView(R.layout.usb_dialog_container)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        finish();
                    }
                })
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                }).create();
        mDialog.show();

        LinearLayout container = (LinearLayout) mDialog.findViewById(R.id.container);

        mEnforcedAdmin = RestrictedLockUtils.checkIfRestrictionEnforced(this,
                UserManager.DISALLOW_USB_FILE_TRANSFER, UserHandle.myUserId());
        mBackend = new UsbBackend(this);
        int current = mBackend.getCurrentMode();
        for (int i = 0; i < DEFAULT_MODES.length; i++) {
            if (mBackend.isModeSupported(DEFAULT_MODES[i])
                    && !mBackend.isModeDisallowedBySystem(DEFAULT_MODES[i])) {
                inflateOption(DEFAULT_MODES[i], current == DEFAULT_MODES[i], container,
                        mBackend.isModeDisallowed(DEFAULT_MODES[i]));
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_STATE);
        registerReceiver(mDisconnectedReceiver, filter);
    }

    @Override
    protected void onStop() {
        unregisterReceiver(mDisconnectedReceiver);
        super.onStop();
    }

    private void inflateOption(final int mode, boolean selected, LinearLayout container,
            final boolean disallowedByAdmin) {
        View v = mLayoutInflater.inflate(R.layout.restricted_radio_with_summary, container, false);

        TextView titleView = (TextView) v.findViewById(android.R.id.title);
        titleView.setText(getTitle(mode));
        TextView summaryView = (TextView) v.findViewById(android.R.id.summary);
        updateSummary(summaryView, mode);

        if (disallowedByAdmin) {
            if (mEnforcedAdmin != null) {
                setDisabledByAdmin(v, titleView, summaryView);
            } else {
                return;
            }
        }

        v.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (disallowedByAdmin && mEnforcedAdmin != null) {
                    RestrictedLockUtils.sendShowAdminSupportDetailsIntent(
                            UsbModeChooserActivity.this, mEnforcedAdmin);
                    return;
                }
                if (!ActivityManager.isUserAMonkey()) {
                    mBackend.setMode(mode);
                }
                mDialog.dismiss();
                finish();
            }
        });
        ((Checkable) v).setChecked(selected);
        container.addView(v);
    }

    private void setDisabledByAdmin(View rootView, TextView titleView, TextView summaryView) {
        if (mEnforcedAdmin != null) {
            titleView.setEnabled(false);
            summaryView.setEnabled(false);
            rootView.findViewById(R.id.restricted_icon).setVisibility(View.VISIBLE);
            Drawable[] compoundDrawables = titleView.getCompoundDrawablesRelative();
            compoundDrawables[0 /* start */].mutate().setColorFilter(
                    getColor(R.color.disabled_text_color), PorterDuff.Mode.MULTIPLY);
        }
    }

    @VisibleForTesting
    static void updateSummary(TextView summaryView, int mode) {
        if (mode == (UsbBackend.MODE_POWER_SOURCE | UsbBackend.MODE_DATA_NONE)) {
            summaryView.setText(R.string.usb_use_power_only_desc);
        }
    }

    @VisibleForTesting
    static int getTitle(int mode) {
        switch (mode) {
            case UsbBackend.MODE_POWER_SINK | UsbBackend.MODE_DATA_NONE:
                return R.string.usb_use_charging_only;
            case UsbBackend.MODE_POWER_SOURCE | UsbBackend.MODE_DATA_NONE:
                return R.string.usb_use_power_only;
            case UsbBackend.MODE_POWER_SINK | UsbBackend.MODE_DATA_MTP:
                return R.string.usb_use_file_transfers;
            case UsbBackend.MODE_POWER_SINK | UsbBackend.MODE_DATA_PTP:
                return R.string.usb_use_photo_transfers;
            case UsbBackend.MODE_POWER_SINK | UsbBackend.MODE_DATA_MIDI:
                return R.string.usb_use_MIDI;
            case UsbBackend.MODE_POWER_SINK | UsbBackend.MODE_DATA_UMS:
                return R.string.usb_use_UMS;
        }
        return 0;
    }
}
