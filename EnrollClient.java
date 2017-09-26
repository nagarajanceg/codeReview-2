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
import android.util.Slog;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import java.util.Arrays;

/**
 * A class to keep track of the enrollment state for a given client.
 */
public abstract class EnrollClient extends ClientMonitor {
    private static final long MS_PER_SEC = 1000;//number of milli seconds for a second
    private static final int ENROLLMENT_TIMEOUT_MS = 60 * 1000; // 1 minute. the time in which enrollment time will be expired
    private byte[] mCryptoToken; //the fingerprint public key 

    public EnrollClient(Context context, long halDeviceId, IBinder token,
            IFingerprintServiceReceiver receiver, int userId, int groupId, byte [] cryptoToken,
            boolean restricted, String owner) {
        //calls the constructor in ClientMonitor and initializes the device id, user id, group id for set of fingerprints and sets the name of the owner of the device
        super(context, halDeviceId, token, receiver, userId, groupId, restricted, owner);
        mCryptoToken = Arrays.copyOf(cryptoToken, cryptoToken.length); //the fingerprint public key is added along with the copy of it with the new length
    }

    @Override
    public boolean onEnrollResult(int fingerId, int groupId, int remaining) {

        //checks if the given groupId is not same as the group id of the fingerprint set
        if (groupId != getGroupId()) {  

            //if not the same, then it is logged in log file
            Slog.w(TAG, "groupId != getGroupId(), groupId: " + groupId +
                    " getGroupId():" + getGroupId()); 

        }
        if (remaining == 0) {

            //creates a new instance of FingerPrint and adds the details of user fingerprint by executing the runnables in the background
            FingerprintUtils.getInstance().addFingerprintForUser(getContext(), fingerId,
                    getTargetUserId());
        }

        return sendEnrollResult(fingerId, groupId, remaining); //returns true if enrollment is completed
    }



    /*
     * @return true if we're done.
     */
    private boolean sendEnrollResult(int fpId, int groupId, int remaining) {

        //obtains the receiver for fingerprint service from the device
        IFingerprintServiceReceiver receiver = getReceiver(); 

        if (receiver == null)
            return true; // client not listening

        //the device vibrates for 30ms if the enrollment is sucessful
        FingerprintUtils.vibrateFingerprintSuccess(getContext()); 

        //the context of the fingerprint service and the enrollment status is logged
        MetricsLogger.action(getContext(), MetricsEvent.ACTION_FINGERPRINT_ENROLL); 

        try {

            //updates the group's authenticator id after the enrollment is done
            receiver.onEnrollResult(getHalDeviceId(), fpId, groupId, remaining); 
            return remaining == 0; //sends the result of the enrollment

        } catch (RemoteException e) {

            //if their is a failure in notifying, it is logged in the log file
            Slog.w(TAG, "Failed to notify EnrollResult:", e); 
            return true;

        }
    }


    //override the generic Start method available in the parent class ClientMonitor
    @Override
    public int start() {

        //gets the interface for fingerprint service
        IBiometricsFingerprint daemon = getFingerprintDaemon(); 

        //indicates that the fingerprint is not available
        if (daemon == null) { 

            Slog.w(TAG, "enroll: no fingerprint HAL!");
            //Likely fingerprint HAL is dead.
            return ERROR_ESRCH; //returns an error specifying that no process with that specific daemon is found

        }

        final int timeout = (int) (ENROLLMENT_TIMEOUT_MS / MS_PER_SEC); //sets timeout time to 60ms

        try {

            //daemon is enrolled by sending the key, id and timeout time
            final int result = daemon.enroll(mCryptoToken, getGroupId(), timeout); 

            if (result != 0) {

                Slog.w(TAG, "startEnroll failed, result=" + result); //failure is entered in the log file
                MetricsLogger.histogram(getContext(), "fingerprintd_enroll_start_error", result);//the histogram is sampled with the fingerprint data
                onError(FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */); //exception is notified
                return result;

            }
        } catch (RemoteException e) {
            Slog.e(TAG, "startEnroll failed", e); //logged if there is an exception
        }
        return 0; // success
    }


    /*
        override the generic stop method available in the parent class ClientMonitor
        stop the authentication process along with boolean initiatedByclient to verify whether 
        user trigger the event
    */
    @Override
    public int stop(boolean initiatedByClient) {

        //executes if the enrollment is cancelled
        if (mAlreadyCancelled) { 
            Slog.w(TAG, "stopEnroll: already cancelled!"); //logged into log file
            return 0;

        }

        //get fingerprint service provider
        IBiometricsFingerprint daemon = getFingerprintDaemon(); 

        //indicates that the fingerprint is not available
        if (daemon == null) { 

            Slog.w(TAG, "stopEnrollment: no fingerprint HAL!"); //logged into log file
            //Likely fingerprint HAL is dead.
            return ERROR_ESRCH;//returns an error specifying that no process with that specific daemon is found

        }
        try {

            //fingerprint service is cancelled
            final int result = daemon.cancel(); 

            if (result != 0) {
                Slog.w(TAG, "startEnrollCancel failed, result = " + result);
                return result;

            }
        } catch (RemoteException e) {
            //failed enrollment stopping state is logged in file
            Slog.e(TAG, "stopEnrollment failed", e);
            
        }
        if (initiatedByClient) {
            //exception is notified
            onError(FingerprintManager.FINGERPRINT_ERROR_CANCELED, 0 /* vendorCode */);
        }
        mAlreadyCancelled = true; 
        return 0;
    }
    /*Remaining - contains number of valid attempts available for the fingerprint verfication*/
    @Override
    public boolean onRemoved(int fingerId, int groupId, int remaining) {
        if (DEBUG) Slog.w(TAG, "onRemoved() called for enroll!");
        return true; // Invalid for EnrollClient
    }

    /*Remaining - contains number of valid attempts available for the fingerprint verfication*/
    @Override
    public boolean onEnumerationResult(int fingerId, int groupId, int remaining) {
        if (DEBUG) Slog.w(TAG, "onEnumerationResult() called for enroll!");
        return true; // Invalid for EnrollClient
    }

    /*Remaining - contains number of valid attempts available for the fingerprint verfication*/
    @Override
    public boolean onAuthenticated(int fingerId, int groupId) {
        if (DEBUG) Slog.w(TAG, "onAuthenticated() called for enroll!");
        return true; // Invalid for EnrollClient
    }

}
