/**
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.fingerprint;

import android.content.Context;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;
import com.android.internal.logging.MetricsLogger;

/**
 * A class to keep track of the remove state for a given client.
 */
public abstract class RemovalClient extends ClientMonitor {
    private int mFingerId; //fingerprint id


    //constructor for class RemovalClient 
    public RemovalClient(Context context, long halDeviceId, IBinder token,
            IFingerprintServiceReceiver receiver, int fingerId, int groupId, int userId,
            boolean restricted, String owner) {
        //calls the constructor in ClientMonitor and initializes the device id, user id, group id for set of fingerprints and sets the name of the owner of the device
        super(context, halDeviceId, token, receiver, userId, groupId, restricted, owner);
        mFingerId = fingerId;
    }


    /**
    * function removes the client fingerprint
    */
    @Override
    public int start() {

        //gets the interface for fingerprint service
        IBiometricsFingerprint daemon = getFingerprintDaemon();
        // The fingerprint template ids will be removed when we get confirmation from the HAL
        try {
            //checks for the permissions and removes any fingerprints based on the groupId and fingerId
            final int result = daemon.remove(getGroupId(), mFingerId);
            if (result != 0) {

                //file is logged if there is a failure in the fingerprint removal
                Slog.w(TAG, "startRemove with id = " + mFingerId + " failed, result=" + result);

                //the histogram is sampled with the fingerprint data
                MetricsLogger.histogram(getContext(), "fingerprintd_remove_start_error", result);

                //exception is notified
                onError(FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
                return result;
            }
        } catch (RemoteException e) {
            //logs the error if the removal process is a failure
            Slog.e(TAG, "startRemove failed", e);
        }
        return 0;
    }

    @Override
    public int stop(boolean initiatedByClient) {
        if (mAlreadyCancelled) {
            //logs it if the client stops the fingerprint removal process
            Slog.w(TAG, "stopRemove: already cancelled!");
            return 0;
        }

        //gets the interface for fingerprint service
        IBiometricsFingerprint daemon = getFingerprintDaemon();
        if (daemon == null) {
            //logs it if there is no fingerprint found
            Slog.w(TAG, "stopRemoval: no fingerprint HAL!");
            return ERROR_ESRCH; //returns an error specifying that no process with that specific daemon is found
        }
        try {
            final int result = daemon.cancel(); //the daemon is force stopped
            if (result != 0) {
                //logs it if the force stop of the daemon is unsuccessful
                Slog.w(TAG, "stopRemoval failed, result=" + result);
                return result;
            }
            if (DEBUG) Slog.w(TAG, "client " + getOwnerString() + " is no longer removing");
        } catch (RemoteException e) {
            //catches the exception and logs it in TAG
            Slog.e(TAG, "stopRemoval failed", e);
            return ERROR_ESRCH;//returns an error specifying that no process with that specific daemon is found
        }
        mAlreadyCancelled = true;
        return 0; // success
    }

    /*
     * @return true if we're done.
     */
    private boolean sendRemoved(int fingerId, int groupId, int remaining) {

        //obtains the receiver for fingerprint service from the device
        IFingerprintServiceReceiver receiver = getReceiver();
        try {
            if (receiver != null) {
                //notifies once the fingerprint is removed
                receiver.onRemoved(getHalDeviceId(), fingerId, groupId, remaining);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to notify Removed:", e);
        }
        return remaining == 0;
    }


    /**
    * return status of the fingerprint removal process
    */
    @Override
    public boolean onRemoved(int fingerId, int groupId, int remaining) {
        if (fingerId != 0) {
            //creates a new instance of FingerPrint and removes the fingerprint by indexing the userId and fingerId
            FingerprintUtils.getInstance().removeFingerprintIdForUser(getContext(), fingerId,
                    getTargetUserId());
        }
        return sendRemoved(fingerId, getGroupId(), remaining);
    }

    @Override
    public boolean onEnrollResult(int fingerId, int groupId, int rem) {
        if (DEBUG) Slog.w(TAG, "onEnrollResult() called for remove!");
        return true; // Invalid for Remove
    }

    @Override
    public boolean onAuthenticated(int fingerId, int groupId) {
        if (DEBUG) Slog.w(TAG, "onAuthenticated() called for remove!");
        return true; // Invalid for Remove.
    }

    @Override
    public boolean onEnumerationResult(int fingerId, int groupId, int remaining) {
        if (DEBUG) Slog.w(TAG, "onEnumerationResult() called for remove!");
        return true; // Invalid for Remove.
    }


}
