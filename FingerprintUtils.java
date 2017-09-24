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

    private static final long[] FP_ERROR_VIBRATE_PATTERN = new long[] {0, 30, 100, 30};//will start the device to vibrate without any delay, vibrates for 30ms and sleeps for 100ms and again starts vibrating for 30ms
    private static final long[] FP_SUCCESS_VIBRATE_PATTERN = new long[] {0, 30};//will cause the device to vibrate for 30ms without any delay

    private static final Object sInstanceLock = new Object(); //lock created for all classes so that only one thread can execute at a time
    private static FingerprintUtils sInstance; //declaration of instance for FingerprintUtils class 

    @GuardedBy("this")
    private final SparseArray<FingerprintsUserState> mUsers = new SparseArray<>(); // A sparse array is created to map to different users

    public static FingerprintUtils getInstance() { //getInstance() ensures only one instance to be created
        synchronized (sInstanceLock) {//this makes sure only one thread is executed at once
            if (sInstance == null) {//this creates a new object instance
                sInstance = new FingerprintUtils(); //instantiation of the class instance
            }
        }
        return sInstance;// returns the reference to the object instance
    }

    private FingerprintUtils() { //Empty constructor
    }

    //this function returns the obtained initial fingerprints of the user
    public List<Fingerprint> getFingerprintsForUser(Context ctx, int userId) { 
        return getStateForUser(ctx, userId).getFingerprints(); //returns a copy of the fingerprints which has name,userid, group id and device id
    }

    //creates a new instance of FingerPrint class and adds the details of user fingerprint by executing the runnables in the background
    public void addFingerprintForUser(Context ctx, int fingerId, int userId) {
        getStateForUser(ctx, userId).addFingerprint(fingerId, userId);
    }

    public void removeFingerprintIdForUser(Context ctx, int fingerId, int userId) {
        getStateForUser(ctx, userId).removeFingerprint(fingerId);
    }

    public void renameFingerprintForUser(Context ctx, int fingerId, int userId, CharSequence name) {
        if (TextUtils.isEmpty(name)) {
            // Don't do the rename if it's empty
            return;
        }
        getStateForUser(ctx, userId).renameFingerprint(fingerId, name);
    }

    public static void vibrateFingerprintError(Context context) {
        Vibrator vibrator = context.getSystemService(Vibrator.class);
        if (vibrator != null) {
            vibrator.vibrate(FP_ERROR_VIBRATE_PATTERN, -1);
        }
    }

    public static void vibrateFingerprintSuccess(Context context) {
        Vibrator vibrator = context.getSystemService(Vibrator.class);
        if (vibrator != null) {
            vibrator.vibrate(FP_SUCCESS_VIBRATE_PATTERN, -1);
        }
    }

    private FingerprintsUserState getStateForUser(Context ctx, int userId) {
        synchronized (this) {
            FingerprintsUserState state = mUsers.get(userId); //the object is mapped taking userId as the key
            if (state == null) { 
                state = new FingerprintsUserState(ctx, userId);//Instantiates the class by sending context of fingerprint service and stores userId in a file
                mUsers.put(userId, state); maps the userId to the state created
            }
            return state; //return the fingerprint state of a particular userId
        }
    }
}

