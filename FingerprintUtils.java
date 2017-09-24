/**
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

package com.android.server.fingerprint;

import android.content.Context;
import android.hardware.fingerprint.Fingerprint;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.util.List;

/**
 * Utility class for dealing with fingerprints and fingerprint settings.
 */
public class FingerprintUtils {
    //will start the device to vibrate without any delay, vibrates for 30ms and sleeps for 100ms and again starts vibrating for 30ms
    private static final long[] FP_ERROR_VIBRATE_PATTERN = new long[] {0, 30, 100, 30};

    //will cause the device to vibrate for 30ms without any delay
    private static final long[] FP_SUCCESS_VIBRATE_PATTERN = new long[] {0, 30};

    //lock created for all classes so that only one thread can execute at a time
    private static final Object sInstanceLock = new Object(); 

    //declaration of instance for FingerprintUtils class
    private static FingerprintUtils sInstance;  

    @GuardedBy("this")
    // A sparse array is created to map to different users
    private final SparseArray<FingerprintsUserState> mUsers = new SparseArray<>(); 



    //getInstance() ensures only one instance to be created
    public static FingerprintUtils getInstance() { 
        synchronized (sInstanceLock) {//this makes sure only one thread is executed at once
            if (sInstance == null) {//this creates a new object instance
                sInstance = new FingerprintUtils(); //instantiation of the class instance
            }
        }
        return sInstance;// returns the reference to the object instance
    }


    //Default constructor
    private FingerprintUtils() { 
    }



    /**function returns the obtained initial fingerprints of the user
    * @param ctx
    * @param userId
    */
    public List<Fingerprint> getFingerprintsForUser(Context ctx, int userId) { 
        //returns a copy of the fingerprints which has name,userid, group id and device id
        return getStateForUser(ctx, userId).getFingerprints(); 
    }



    /**creates a new instance of FingerPrint class and adds the details of user fingerprint by executing the runnables in the background
    * @param ctx
    * @param fingerId
    * @param userId
    */
    public void addFingerprintForUser(Context ctx, int fingerId, int userId) {
        getStateForUser(ctx, userId).addFingerprint(fingerId, userId);
    }



    /**removes the fingerprint from the list
    * @param ctx
    * @param fingerId
    * @param userId 
    */
    public void removeFingerprintIdForUser(Context ctx, int fingerId, int userId) {
        getStateForUser(ctx, userId).removeFingerprint(fingerId);
    }



    /**renames the fingerprint by indexing the list using the fingerId and creating a new object by transfering the same details and a new name
    * @param ctx
    * @param fingerId
    * @param userId
    * @param name
    */
    public void renameFingerprintForUser(Context ctx, int fingerId, int userId, CharSequence name) {
        //checks if the name is empty, if it is, then do not rename it
        if (TextUtils.isEmpty(name)) { 
            // Don't do the rename if it's empty
            return;
        }
        getStateForUser(ctx, userId).renameFingerprint(fingerId, name);
    }



    /**function to give out a pattern of vibrations if there is an error in fingerPrint
    * @param context
    */
    public static void vibrateFingerprintError(Context context) {
        //gets the instance of the class that operates the vibrator on the device
        Vibrator vibrator = context.getSystemService(Vibrator.class); 
        if (vibrator != null) {
            //this makes the vibration without any delay for 30ms and sleeps for 100ms and again vibrate for 30ms. -1 indicates that the vibration is not repeated again
            vibrator.vibrate(FP_ERROR_VIBRATE_PATTERN, -1);  
        }
    }



    /**function to give out a pattern of vibrations if fingerPrint process is successful 
    * @param context
    */
    public static void vibrateFingerprintSuccess(Context context) {
        //gets the instance of the class that operates the vibrator on the device
        Vibrator vibrator = context.getSystemService(Vibrator.class);
        if (vibrator != null) {
            //this makes the vibration without any delay for 30ms.-1 indicates that the vibration is not repeated again
            vibrator.vibrate(FP_SUCCESS_VIBRATE_PATTERN, -1);
        }
    }



    /**function to map the state and userid of a particular user 
    * @param ctx
    * @param userId
    */
    private FingerprintsUserState getStateForUser(Context ctx, int userId) {
        synchronized (this) {
            FingerprintsUserState state = mUsers.get(userId); //the object is mapped taking userId as the key
            if (state == null) { 
                //Instantiates the class by sending context of fingerprint service and stores userId in a file
                state = new FingerprintsUserState(ctx, userId);
                mUsers.put(userId, state); //maps the userId to the state created
            }
            return state; //return the fingerprint state of a particular userId
        }
    }
}

