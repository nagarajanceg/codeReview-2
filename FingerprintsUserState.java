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
 * limitations under the License
 */

// trying to change

package com.android.server.fingerprint;

import android.content.Context;
import android.hardware.fingerprint.Fingerprint;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;

import libcore.io.IoUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Class managing the set of fingerprint per user across device reboots.
 */
class FingerprintsUserState {

    private static final String TAG = "FingerprintState";
    private static final String FINGERPRINT_FILE = "settings_fingerprint.xml";

    private static final String TAG_FINGERPRINTS = "fingerprints";
    private static final String TAG_FINGERPRINT = "fingerprint";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_GROUP_ID = "groupId";
    private static final String ATTR_FINGER_ID = "fingerId";
    private static final String ATTR_DEVICE_ID = "deviceId";

    private final File mFile;

    @GuardedBy("this")
    private final ArrayList<Fingerprint> mFingerprints = new ArrayList<Fingerprint>();
    private final Context mCtx;

    /**
     * Constructor for the given class with Context and UserID
     * @param ctx
     * @param userId
     */
    public FingerprintsUserState(Context ctx, int userId) {
        // get the mfile for the given user ID
        mFile = getFileForUser(userId);
        mCtx = ctx;
        // in a synchronized manner , with this instance being the lock ,
        // read state
        synchronized (this) {
            readStateSyncLocked();
        }
    }

    /**
     * Method to add a new Finger Print
     * @param fingerId
     * @param groupId
     */
    public void addFingerprint(int fingerId, int groupId) {
        synchronized (this) {
            // add new finger print to our member variable mFingerprints (array list)
            mFingerprints.add(new Fingerprint(getUniqueName(), groupId, fingerId, 0));
            // write the change / current state
            scheduleWriteStateLocked();
        }
    }

    /**
     * Remove the given finger print from our list
     * @param fingerId
     */
    public void removeFingerprint(int fingerId) {

        synchronized (this) {
            // for i from 0 to size of mfingerprints
            for (int i = 0; i < mFingerprints.size(); i++) {
                // if the fingerprint id matches
                if (mFingerprints.get(i).getFingerId() == fingerId) {
                    // then remove the finger print from our list
                    mFingerprints.remove(i);
                    // persist the changes
                    scheduleWriteStateLocked();
                    // once changed break away from the loop
                    break;
                }
            }
        }
    }

    /**
     * Method to rename the given fingerprint to given name
     * @param fingerId
     * @param name
     */
    public void renameFingerprint(int fingerId, CharSequence name) {
        synchronized (this) {
            // for i from 0 to size of mfingerprint list
            for (int i = 0; i < mFingerprints.size(); i++) {
                // if the mfingerprint matches
                if (mFingerprints.get(i).getFingerId() == fingerId) {
                    // get the fingerprint object at the given index
                    Fingerprint old = mFingerprints.get(i);
                    // crete a new fingerprint object with old groupid , fingerid and device id at the given index
                    mFingerprints.set(i, new Fingerprint(name, old.getGroupId(), old.getFingerId(),
                            old.getDeviceId()));
                    // persist the changes
                    scheduleWriteStateLocked();
                    break;
                }
            }
        }
    }

    /**
     * Get all fingerprints
     * @return deep copy of mfingerprints list
     */
    public List<Fingerprint> getFingerprints() {

        synchronized (this) {
            // return a deep copy of the mfingerprint list
            return getCopy(mFingerprints);
        }
    }

    /**
     * Finds a unique name for the given fingerprint
     * @return unique name
     */
    private String getUniqueName() {
        int guess = 1;
        while (true) {
            // Not the most efficient algorithm in the world, but there shouldn't be more than 10
            String name = mCtx.getString(com.android.internal.R.string.fingerprint_name_template,
                    guess);
            // if the name is unique return
            if (isUnique(name)) {
                return name;
            }
            // else increment no of guess and iterate
            guess++;
        }
    }

    /**
     * method to check if the given name doesn't exist
     * in the existing fingerprints
     * @param name
     * @return
     */
    private boolean isUnique(String name) {
        // for each fingerprint in mfingerprints
        for (Fingerprint fp : mFingerprints) {
            // if the name matches then return false , coz the name is already taken
            if (fp.getName().equals(name)) {
                return false;
            }
        }
        // else it's unqiue
        return true;
    }

    private static File getFileForUser(int userId) {
        return new File(Environment.getUserSystemDirectory(userId), FINGERPRINT_FILE);
    }

    // creating mwriteStateRunnable , (Classes have to extend runnable in order to be thread and
    // be used for multi threaded environment)
    private final Runnable mWriteStateRunnable = new Runnable() {
        @Override
        // this method has to be over ridden
        // the method the worker method is dowritestate
        public void run() {
            doWriteState();
        }
    };


    private void scheduleWriteStateLocked() {
        //NOTE this is done Asynchronosly and not locked with the instance
        AsyncTask.execute(mWriteStateRunnable);
    }

    /**
     * Method to create a deep copy of the fingerprint list
     * @param array
     * @return list of fingerprint objects
     */
    private ArrayList<Fingerprint> getCopy(ArrayList<Fingerprint> array) {
        // create a new list of given array size
        ArrayList<Fingerprint> result = new ArrayList<Fingerprint>(array.size());
        for (int i = 0; i < array.size(); i++) {
            // for each i get the finger print in the array
            Fingerprint fp = array.get(i);
            // create a new finger print with the existing name , id , group id and decise id
            // and add to the result
            result.add(new Fingerprint(fp.getName(), fp.getGroupId(), fp.getFingerId(),
                    fp.getDeviceId()));
        }
        // return result
        return result;
    }

    /**
     * This is the multi threaded method that is run simultaneously by various threads.
     * To wrtie change to the mfile that is used for storing the fingerprint details in Xml Format
     */
    private void doWriteState() {
        // AtomicFile is a class that helps performing atomic operations on file
        // and it also creates a back up so that if an operation fails we can restore it.
        AtomicFile destination = new AtomicFile(mFile);

        ArrayList<Fingerprint> fingerprints;

        synchronized (this) {
            // get copy of the fingerprint in our class instance
            // note that it alwasy contains the upto date version and all changes are made to it
            // so to persist we first need the latest version
            fingerprints = getCopy(mFingerprints);
        }

        FileOutputStream out = null;
        try {
            // we start writing (start wrtie is an operation provided by AtomicFile)
            out = destination.startWrite();

            // we create a new XmlSerializer and set the output format , features and start tag
            XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(out, "utf-8");
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_FINGERPRINTS);

            final int count = fingerprints.size();
            for (int i = 0; i < count; i++) {
                // for each fingerprint print in xml format
                Fingerprint fp = fingerprints.get(i);
                serializer.startTag(null, TAG_FINGERPRINT);
                serializer.attribute(null, ATTR_FINGER_ID, Integer.toString(fp.getFingerId()));
                serializer.attribute(null, ATTR_NAME, fp.getName().toString());
                serializer.attribute(null, ATTR_GROUP_ID, Integer.toString(fp.getGroupId()));
                serializer.attribute(null, ATTR_DEVICE_ID, Long.toString(fp.getDeviceId()));
                serializer.endTag(null, TAG_FINGERPRINT);
            }
            // add end tag
            serializer.endTag(null, TAG_FINGERPRINTS);
            // end document write
            serializer.endDocument();
            // finish write command
            destination.finishWrite(out);
            // Any error while writing is fatal.
        } catch (Throwable t) {
            Slog.wtf(TAG, "Failed to write settings, restoring backup", t);
            // fail wrtie will replace the file or any opetion with the backup file thus we don't lose data
            destination.failWrite(out);
            throw new IllegalStateException("Failed to write fingerprints", t);
        } finally {
            // close finally
            IoUtils.closeQuietly(out);
        }
    }

    /**
     * Method used to read the mfile , which contains the fingerprint data
     * It is one of the multi threaded worker method and access the file in proper fashion
     */
    private void readStateSyncLocked() {

        FileInputStream in;
        // check if the mfile exist
        if (!mFile.exists()) {
            return;
        }
        try {
            // create a new input stream from the mfile
            in = new FileInputStream(mFile);
        } catch (FileNotFoundException fnfe) {
            Slog.i(TAG, "No fingerprint state");
            return;
        }
        try {
            // now the XmlPullParser is used to extract data from the XmlFile
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, null);
            parseStateLocked(parser);

        } catch (XmlPullParserException | IOException e) {
            // if any exception in parsing , IO Exception throw exception
            throw new IllegalStateException("Failed parsing settings file: "
                    + mFile , e);
        } finally {
            // finally close the input stream
            IoUtils.closeQuietly(in);
        }
    }

    private void parseStateLocked(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(TAG_FINGERPRINTS)) {
                parseFingerprintsLocked(parser);
            }
        }
    }

    private void parseFingerprintsLocked(XmlPullParser parser)
            throws IOException, XmlPullParserException {

        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(TAG_FINGERPRINT)) {
                String name = parser.getAttributeValue(null, ATTR_NAME);
                String groupId = parser.getAttributeValue(null, ATTR_GROUP_ID);
                String fingerId = parser.getAttributeValue(null, ATTR_FINGER_ID);
                String deviceId = parser.getAttributeValue(null, ATTR_DEVICE_ID);
                mFingerprints.add(new Fingerprint(name, Integer.parseInt(groupId),
                        Integer.parseInt(fingerId), Integer.parseInt(deviceId)));
            }
        }
    }

}
