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

import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import android.content.Context; //Application context include
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

/**
 * A class to keep track of the authentication state for a given client.
 */
public abstract class AuthenticationClient extends ClientMonitor {
    private long mOpId;
    //Abstract method it can be implemented at the time invoking
    public abstract int handleFailedAttempt();
    public abstract void resetFailedAttempts();
    /*Three states for a lock have been maintained LOCKOUT_NONE, LOCKOUT_TIMED, LOCKOUT_PERMANENT */
    /*Assign unchanged value 0 to LOCKOUT_NONE indicates there is no failed attempt for fingerprint verification*/
    
    public static final int LOCKOUT_NONE = 0; 
    /*Assign unchanged value 1 to LOCKOUT_TIMED indicates there has been few failed attempts caused time out for further attempts */
    // Max timed out = 300000ms and max number of failure attempts to the lockout timed - 5 times
    public static final int LOCKOUT_TIMED = 1; /*Unchanged timer value*/
    /*Assign unchanged value 2 to LOCKOUT_PERMANENT indicates the lockout untill the reset the failed attempts*/
    //Max no of attempts allowed for permanent lockout is 20
    public static final int LOCKOUT_PERMANENT = 2;
    /*Constructor for Abstract class which will be helpful in invoking this abstract class in  
        the implementation
    */ 
    /*
       Context - Application context of Fingerprint Service
       HalDeviceId - Hardware abstraction layer device Id of associated fingerprint hardware device
       Token - Unique token for the client 
       restricted - the client access permission stated by android.Manifest.permission.MANAGE_FINGERPRINT
       targetUserId - target user id for authentication
       owner - owner name of the client that owns this
       receiver -  authentication recipient of related events
       opId - operation identification number
       group id - fingerprint set grouped identification 
    */
    public AuthenticationClient(Context context, long halDeviceId, IBinder token,
            IFingerprintServiceReceiver receiver, int targetUserId, int groupId, long opId,
            boolean restricted, String owner) {
        /*
            super call should be the first statement invokes ClientMonitor constructor and assign values
            to the required variables. Because AuthenticationClient is abstract class , it's not possible 
            to create object and calling the super is the way to initalize the instance.
        */
        super(context, halDeviceId, token, receiver, targetUserId, groupId, restricted, owner);
        mOpId = opId;
    }

    /*This method override the extended class method*/
    @Override
    /*Return true irrespective of valid or invalid fingerID. This true means authentication process get completed 
    and move to process next client event*/
    public boolean onAuthenticated(int fingerId, int groupId) {
        boolean result = false; // intial value of authentication method compeletion status 
        boolean authenticated = fingerId != 0; // assign authenticated value only for non zero fingerId value
        // Receiver event listener already binded to the instance in the constructor and assign to the fingerprint service Interface 
        IFingerprintServiceReceiver receiver = getReceiver(); 
        // check for the any available receiver
        if (receiver != null) {
            try {
                // logging the authentication event 
                MetricsLogger.action(getContext(), MetricsEvent.ACTION_FINGERPRINT_AUTH,
                        authenticated);
                if (!authenticated) {
                    //on Auth failure notify the device using HAL id
                    receiver.onAuthenticationFailed(getHalDeviceId());
                } else {
                    //check for debugger flags. if its enbaled 
                    if (DEBUG) {
                        //display logs with device owner details include stacktrace
                        Slog.v(TAG, "onAuthenticated(owner=" + getOwnerString()
                                + ", id=" + fingerId + ", gp=" + groupId + ")");
                    }
                    /*
                        check for the restricted deviceid assigned in the constructor 
                        if it is not restricted create final Fingerprint object with groupId corresponding
                        finger print associated with this device id.
                    */
                    Fingerprint fp = !getIsRestricted() 
                            ? new Fingerprint("" /* TODO */, groupId, fingerId, getHalDeviceId())
                            : null;
                    //overrided method from fingerprintmanager
                    /*Called when a fingerprint is recognized  and bind the target user id and device id to the
                    finger print object*/                          
                    receiver.onAuthenticationSucceeded(getHalDeviceId(), fp, getTargetUserId());
                }
            } catch (RemoteException e) {
                // Catch all the communication related exception during finger print authentication process
                //Log the failure event 
                Slog.w(TAG, "Failed to notify Authenticated:", e);
                result = true; // client failed
            }
        } else {
            result = true; // client not listening
        }
        //check for non zero fingerprint value.
        if (!authenticated) {
            //check receiver event listener available
            if (receiver != null) {
                //call the vibrator system service to notify the error for not having finger print value
                FingerprintUtils.vibrateFingerprintError(getContext());
            }
            // allow system-defined limit of number of attempts before giving up
            // invoking class having generic functionality for the failedAttempt
            int lockoutMode =  handleFailedAttempt();
            /*check for any other lockout state apart from Lockout_none*/
            if (lockoutMode != LOCKOUT_NONE) {
                try {
                    Slog.w(TAG, "Forcing lockout (fp driver code should do this!), mode(" +
                            lockoutMode + ")");
                    stop(false);
                    //check Lockout_timer and if it available set error code as lock timed out otherwise
                    //set erroe code as permanent lock
                    int errorCode = lockoutMode == LOCKOUT_TIMED ?
                            FingerprintManager.FINGERPRINT_ERROR_LOCKOUT :
                            FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT;
                    //notify the error through receiver error callback        
                    receiver.onError(getHalDeviceId(), errorCode, 0 /* vendorCode */);
                } catch (RemoteException e) {
                    //record a log at the notification failure
                    Slog.w(TAG, "Failed to notify lockout:", e);
                }
            }
            //set the result value
            result |= lockoutMode != LOCKOUT_NONE; // in a lockout mode
        } else {
            //check receiver event listener available
            if (receiver != null) {
                // Notify the the fingerprint is matched and success to proceed
                FingerprintUtils.vibrateFingerprintSuccess(getContext());
            }
            result |= true; // we have a valid fingerprint, done
            // Failed attempts counted so far have to reset to zero after a single success
            resetFailedAttempts();
        }
        return result;
    }

    /**
     * Start authentication
     */
    @Override
    public int start() {
        //get fingerprint service provider
        IBiometricsFingerprint daemon = getFingerprintDaemon();
        //notify error msg(fingerprint HAL is dead) if no service available
        if (daemon == null) {
            Slog.w(TAG, "start authentication: no fingerprint HAL!");
            return ERROR_ESRCH; ////Likely fingerprint HAL is dead.
        }
        try {
            //getGroupId() - Gets the group id specified when the fingerprint was enrolled
            //authenticate with op id provided 
            final int result = daemon.authenticate(mOpId, getGroupId());
            // Log error on Invoked authentication function fails or finger print dies
            if (result != 0) {
                Slog.w(TAG, "startAuthentication failed, result=" + result);
                //Log the values in histogram basis and the increment the counter based on the no of errors
                MetricsLogger.histogram(getContext(), "fingeprintd_auth_start_error", result);
                // set the fingerprint manager as unavailable
                onError(FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
                return result;
            }
            //initiate the authenticating process and log along with owner details 
            if (DEBUG) Slog.w(TAG, "client " + getOwnerString() + " is authenticating...");
        } catch (RemoteException e) {
            //Log the auth failure
            Slog.e(TAG, "startAuthentication failed", e);
            return ERROR_ESRCH; //Likely fingerprint HAL is dead.
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
        //boolean variable mAlreadyCancelled shows the user is already authenticated
        // And then stopped further authentication
        if (mAlreadyCancelled) {
            Slog.w(TAG, "stopAuthentication: already cancelled!");
            return 0;
        }
        //fingerprint service provider
        IBiometricsFingerprint daemon = getFingerprintDaemon();
        //No service available then stop the authentication process and notify the error
        if (daemon == null) {
            Slog.w(TAG, "stopAuthentication: no fingerprint HAL!");
            return ERROR_ESRCH; //Likely fingerprint HAL is dead.
        }
        try {
            final int result = daemon.cancel();
            // check for any service cancellation, if it's cancelled stop the authentication and notify 
            if (result != 0) {
                Slog.w(TAG, "stopAuthentication failed, result=" + result);
                return result;
            }
            if (DEBUG) Slog.w(TAG, "client " + getOwnerString() + " is no longer authenticating");
        } catch (RemoteException e) {
            Slog.e(TAG, "stopAuthentication failed", e);
            return ERROR_ESRCH; //Likely fingerprint HAL is dead.
        }
        mAlreadyCancelled = true; // Make it as true is helpful to avoid further authentication
        return 0; // success
    }

    // remaining - number of remaining available valid attempts to authenticate fingerId before locking
    // fingerId - fingerprint provided by the user
    // groupId - fingerId belongs to the groupId
    // Enroll Result Arbitration
    @Override
    public boolean onEnrollResult(int fingerId, int groupId, int remaining) {
        //check for debug enabled
        if (DEBUG) Slog.w(TAG, "onEnrollResult() called for authenticate!");
        return true; // Invalid for Authenticate
    }
    //remove Arbitration
    // Generic method provided for further extended class can have their own remove logic on Result data available
    @Override
    public boolean onRemoved(int fingerId, int groupId, int remaining) {
        //check for debug enabled
        if (DEBUG) Slog.w(TAG, "onRemoved() called for authenticate!");
        return true; // Invalid for Authenticate
    }
    // enumerate result Arbitration
    // Generic method provided for further extended class can have their own iterate logic on Result
    @Override
    public boolean onEnumerationResult(int fingerId, int groupId, int remaining) {
        //check for debug enabled
        if (DEBUG) Slog.w(TAG, "onEnumerationResult() called for authenticate!");
        return true; // Invalid for Authenticate
    }
}
