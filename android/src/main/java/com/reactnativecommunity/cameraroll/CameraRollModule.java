/**
 * Copyright (c) Facebook, Inc. and its affiliates.
 * <p>
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.reactnativecommunity.cameraroll;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.MediaStore.Images;
import android.text.TextUtils;

import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.GuardedAsyncTask;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.module.annotations.ReactModule;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.text.SimpleDateFormat;
import java.text.ParseException;
import java.util.Date;

import javax.annotation.Nullable;

/**
 * {@link NativeModule} that allows JS to interact with the photos and videos on the device (i.e.
 * {@link MediaStore.Images}).
 */
@ReactModule(name = CameraRollModule.NAME)
public class CameraRollModule extends ReactContextBaseJavaModule {

    public static final String NAME = "RNCCameraRoll";

    private static final String ERROR_UNABLE_TO_LOAD = "E_UNABLE_TO_LOAD";
    private static final String ERROR_UNABLE_TO_LOAD_PERMISSION = "E_UNABLE_TO_LOAD_PERMISSION";
    private static final String ERROR_UNABLE_TO_SAVE = "E_UNABLE_TO_SAVE";
    private static final String ERROR_UNABLE_TO_FILTER = "E_UNABLE_TO_FILTER";

    private static final String ASSET_TYPE_PHOTOS = "Photos";
    private static final String ASSET_TYPE_VIDEOS = "Videos";
    private static final String ASSET_TYPE_ALL = "All";

    private static final String[] PROJECTION = {
            Images.Media._ID,
            Images.Media.MIME_TYPE,
            Images.Media.BUCKET_DISPLAY_NAME,
            Images.Media.DATE_TAKEN,
            Images.Media.DATE_ADDED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATA
    };

    private static final String SELECTION_BUCKET = Images.Media.BUCKET_DISPLAY_NAME + " = ?";
    private static final String SELECTION_DATE_TAKEN = Images.Media.DATE_TAKEN + " < ?";
    // NOTE: this may lead to duplicate results on subsequent queries.
    // However, should only be an issue in case a user makes multiple pictures in a second
    // AND has more than 100 pictures (current set limit in app).
    private static final String SELECTION_DATE_ADDED = Images.Media.DATE_ADDED + " <= ?";

    public CameraRollModule(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @Override
    public String getName() {
        return NAME;
    }

    /**
     * Save an image to the gallery (i.e. {@link MediaStore.Images}). This copies the original file
     * from wherever it may be to the external storage pictures directory, so that it can be scanned
     * by the MediaScanner.
     *
     * @param uri the file:// URI of the image to save
     * @param promise to be resolved or rejected
     */
    @ReactMethod
    public void saveToCameraRoll(String uri, ReadableMap options, Promise promise) {
        new SaveToCameraRoll(getReactApplicationContext(), Uri.parse(uri), options, promise)
                .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static class SaveToCameraRoll extends GuardedAsyncTask<Void, Void> {

        private final Context mContext;
        private final Uri mUri;
        private final Promise mPromise;
        private final ReadableMap mOptions;

        public SaveToCameraRoll(ReactContext context, Uri uri, ReadableMap options, Promise promise) {
            super(context);
            mContext = context;
            mUri = uri;
            mPromise = promise;
            mOptions = options;
        }

        @Override
        protected void doInBackgroundGuarded(Void... params) {
            File source = new File(mUri.getPath());
            FileChannel input = null, output = null;
            try {
                File environment = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DCIM);
                File exportDir;
                if (!"".equals(mOptions.getString("album"))) {
                    exportDir = new File(environment, mOptions.getString("album"));
                    if (!exportDir.exists() && !exportDir.mkdirs()) {
                        mPromise.reject(ERROR_UNABLE_TO_LOAD, "Album Directory not created. Did you request WRITE_EXTERNAL_STORAGE?");
                        return;
                    }
                } else {
                    exportDir = environment;
                }

                if (!exportDir.isDirectory()) {
                    mPromise.reject(ERROR_UNABLE_TO_LOAD, "External media storage directory not available");
                    return;
                }
                File dest = new File(exportDir, source.getName());
                int n = 0;
                String fullSourceName = source.getName();
                String sourceName, sourceExt;
                if (fullSourceName.indexOf('.') >= 0) {
                    sourceName = fullSourceName.substring(0, fullSourceName.lastIndexOf('.'));
                    sourceExt = fullSourceName.substring(fullSourceName.lastIndexOf('.'));
                } else {
                    sourceName = fullSourceName;
                    sourceExt = "";
                }
                while (!dest.createNewFile()) {
                    dest = new File(exportDir, sourceName + "_" + (n++) + sourceExt);
                }
                input = new FileInputStream(source).getChannel();
                output = new FileOutputStream(dest).getChannel();
                output.transferFrom(input, 0, input.size());
                input.close();
                output.close();

                MediaScannerConnection.scanFile(
                        mContext,
                        new String[]{dest.getAbsolutePath()},
                        null,
                        new MediaScannerConnection.OnScanCompletedListener() {
                            @Override
                            public void onScanCompleted(String path, Uri uri) {
                                if (uri != null) {
                                    mPromise.resolve(uri.toString());
                                } else {
                                    mPromise.reject(ERROR_UNABLE_TO_SAVE, "Could not add image to gallery");
                                }
                            }
                        });
            } catch (IOException e) {
                mPromise.reject(e);
            } finally {
                if (input != null && input.isOpen()) {
                    try {
                        input.close();
                    } catch (IOException e) {
                        FLog.e(ReactConstants.TAG, "Could not close input channel", e);
                    }
                }
                if (output != null && output.isOpen()) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        FLog.e(ReactConstants.TAG, "Could not close output channel", e);
                    }
                }
            }
        }
    }

    /**
     * Get photos from {@link MediaStore.Images}, most recent first.
     *
     * @param params a map containing the following keys:
     *        <ul>
     *          <li>first (mandatory): a number representing the number of photos to fetch</li>
     *          <li>
     *            after (optional): a cursor that matches page_info[end_cursor] returned by a
     *            previous call to {@link #getPhotos}
     *          </li>
     *          <li>groupName (optional): an album name</li>
     *          <li>
     *            mimeType (optional): restrict returned images to a specific mimetype (e.g.
     *            image/jpeg)
     *          </li>
     *          <li>
     *            assetType (optional): chooses between either photos or videos from the camera roll.
     *            Valid values are "Photos" or "Videos". Defaults to photos.
     *          </li>
     *          <li>
     *            useDateAddedQuery (optional): allows for taking the 'date_added' property of images
     *            into account. In Android 10+ the default 'date_taken' property has been replaced by
     *            'date_added', resulting in possible 0 timestamps. This allows to counteract the
     *            issue.
     *          </li>
     *        </ul>
     * @param promise the Promise to be resolved when the photos are loaded; for a format of the
     *        parameters passed to this callback, see {@code getPhotosReturnChecker} in CameraRoll.js
     */
    @ReactMethod
    public void getPhotos(final ReadableMap params, final Promise promise) {
        int first = params.getInt("first");
        String after = params.hasKey("after") ? params.getString("after") : null;
        String groupName = params.hasKey("groupName") ? params.getString("groupName") : null;
        String assetType = params.hasKey("assetType") ? params.getString("assetType") : ASSET_TYPE_PHOTOS;
        ReadableArray mimeTypes = params.hasKey("mimeTypes")
                ? params.getArray("mimeTypes")
                : null;
        boolean useDateDateAddedQuery = params.hasKey("useDateAddedQuery")
                ? params.getBoolean("useDateAddedQuery")
                : false;
        boolean useExifDateTimeOriginal = params.hasKey("useExifDateTimeOriginal") && params.getBoolean("useExifDateTimeOriginal");

        new GetMediaTask(
                getReactApplicationContext(),
                first,
                after,
                groupName,
                mimeTypes,
                assetType,
                useDateDateAddedQuery,
                useExifDateTimeOriginal,
                promise)
                .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static class GetMediaTask extends GuardedAsyncTask<Void, Void> {
        private final Context mContext;
        private final int mFirst;
        private final @Nullable
        String mAfter;
        private final @Nullable
        String mGroupName;
        private final @Nullable
        ReadableArray mMimeTypes;
        private final String mAssetType;
        private final boolean mUseDateAddedQuery;
        private final boolean mUseExifDateTimeOriginal;
        private final Promise mPromise;

        private GetMediaTask(
                ReactContext context,
                int first,
                @Nullable String after,
                @Nullable String groupName,
                @Nullable ReadableArray mimeTypes,
                String assetType,
                boolean useDateAddedQuery,
                boolean useExifDateTimeOriginal,
                Promise promise) {
            super(context);
            mContext = context;
            mFirst = first;
            mAfter = after;
            mGroupName = groupName;
            mMimeTypes = mimeTypes;
            mAssetType = assetType;
            mUseDateAddedQuery = useDateAddedQuery;
            mUseExifDateTimeOriginal = useExifDateTimeOriginal;
            mPromise = promise;
        }

        @Override
        protected void doInBackgroundGuarded(Void... params) {
            StringBuilder selection = new StringBuilder("1");
            List<String> selectionArgs = new ArrayList<>();
            if (!TextUtils.isEmpty(mAfter)) {
                if (mUseDateAddedQuery) {
                    selection.append(" AND (CASE WHEN " + Images.Media.DATE_TAKEN + " <= 0 THEN "
                            + SELECTION_DATE_ADDED);
                    String mAfterInSeconds = String.valueOf(Long.valueOf(mAfter) / 1000l);
                    selectionArgs.add(mAfterInSeconds);
                    selection.append(" ELSE " + SELECTION_DATE_TAKEN + " END)");
                    selectionArgs.add(mAfter);
                } else {
                    selection.append(" AND " + SELECTION_DATE_TAKEN);
                    selectionArgs.add(mAfter);
                }
            }

            if (!TextUtils.isEmpty(mGroupName)) {
                selection.append(" AND " + SELECTION_BUCKET);
                selectionArgs.add(mGroupName);
            }

            switch (mAssetType) {
                case ASSET_TYPE_PHOTOS:
                    selection.append(" AND " + MediaStore.Files.FileColumns.MEDIA_TYPE + " = "
                            + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE);
                    break;
                case ASSET_TYPE_VIDEOS:
                    selection.append(" AND " + MediaStore.Files.FileColumns.MEDIA_TYPE + " = "
                            + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO);
                    break;
                case ASSET_TYPE_ALL:
                    selection.append(" AND " + MediaStore.Files.FileColumns.MEDIA_TYPE + " IN ("
                            + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ","
                            + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE + ")");
                    break;
                default:
                    mPromise.reject(
                            ERROR_UNABLE_TO_FILTER,
                            "Invalid filter option: '" + mAssetType + "'. Expected one of '" + ASSET_TYPE_PHOTOS
                                    + "', '" + ASSET_TYPE_VIDEOS + "' or '" + ASSET_TYPE_ALL + "'."
                    );
                    return;
            }

            if (mMimeTypes != null && mMimeTypes.size() > 0) {
                selection.append(" AND " + Images.Media.MIME_TYPE + " IN (");
                for (int i = 0; i < mMimeTypes.size(); i++) {
                    selection.append("?,");
                    selectionArgs.add(mMimeTypes.getString(i));
                }
                selection.replace(selection.length() - 1, selection.length(), ")");
            }
            WritableMap response = new WritableNativeMap();
            ContentResolver resolver = mContext.getContentResolver();

            String sortQueryDateTaken = Images.Media.DATE_TAKEN + " DESC, " + Images.Media.DATE_MODIFIED + " DESC";
            // We sort on date_added when date_taken appears to be invalid else on date_taken
            // and finally on date_moddified. `date_added` is multiplied with 1000 in that case
            // since it is measured in second as opposed to `date_taken`.
            String sortQueryDateAdded = "(CASE WHEN " + Images.Media.DATE_TAKEN + "<=0 THEN " + Images.Media.DATE_ADDED
                    + "*1000 ELSE " + Images.Media.DATE_TAKEN + " END) DESC, "
                    + Images.Media.DATE_MODIFIED + " DESC";
            String sortQuery = mUseDateAddedQuery ? sortQueryDateAdded : sortQueryDateTaken;
            String[] selectionArgsStringArray = selectionArgs.toArray(new String[selectionArgs.size()]);

            Cursor media = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bundle selectionBundle = new Bundle();
                selectionBundle.putInt(ContentResolver.QUERY_ARG_LIMIT, mFirst + 1);
                selectionBundle.putInt(ContentResolver.QUERY_ARG_OFFSET, 0);
                selectionBundle.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortQuery);
                selectionBundle.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection.toString());
                selectionBundle.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgsStringArray);

                media = resolver.query(
                    MediaStore.Files.getContentUri("external"),
                    PROJECTION,
                    selectionBundle,
                    null
                );
            } else {
                media = resolver.query(
                        MediaStore.Files.getContentUri("external"),
                        PROJECTION,
                        selection.toString(),
                        selectionArgsStringArray,
                        sortQuery + " LIMIT " + (mFirst + 1)
                );
            }

            try {
                if (media == null) {
                    mPromise.reject(ERROR_UNABLE_TO_LOAD, "Could not get media");
                } else {
                    try {
                        putEdges(resolver, media, response, mFirst, mUseDateAddedQuery, mUseExifDateTimeOriginal);
                        putPageInfo(media, response, mFirst, mUseDateAddedQuery);
                    } finally {
                        media.close();
                        mPromise.resolve(response);
                    }
                }
            } catch (SecurityException e) {
                mPromise.reject(
                        ERROR_UNABLE_TO_LOAD_PERMISSION,
                        "Could not get media: need READ_EXTERNAL_STORAGE permission",
                        e);
            }
        }
    }

    private static void putPageInfo(Cursor media, WritableMap response, int limit, boolean useDateAdded) {
        WritableMap pageInfo = new WritableNativeMap();
        pageInfo.putBoolean("has_next_page", limit < media.getCount());
        if (limit < media.getCount()) {
            media.moveToPosition(limit - 1);
            int dateTakenIndex = media.getColumnIndex(Images.Media.DATE_TAKEN);
            long dateTaken = media.getLong(dateTakenIndex);
            long timestamp = dateTaken;
            // If we want to use data_added and the date_taken timestamp equals 0.
            if (useDateAdded && dateTaken <= 0) {
                // Use date_taken multiplied by 1000 as we return time in ms.
                int dateAddedIndex = media.getColumnIndex(Images.Media.DATE_ADDED);
                timestamp = media.getLong(dateAddedIndex) * 1000l;
            }
            pageInfo.putString(
                    "end_cursor",
                    Long.toString(timestamp));
        }
        response.putMap("page_info", pageInfo);
    }

    private static void putEdges(
            ContentResolver resolver,
            Cursor media,
            WritableMap response,
            int limit,
            boolean useDateAdded,
            boolean useExifDateTimeOriginal) {
        WritableArray edges = new WritableNativeArray();
        media.moveToFirst();
        int mimeTypeIndex = media.getColumnIndex(Images.Media.MIME_TYPE);
        int groupNameIndex = media.getColumnIndex(Images.Media.BUCKET_DISPLAY_NAME);
        int dateTakenIndex = media.getColumnIndex(Images.Media.DATE_TAKEN);
        int dateAddedIndex = media.getColumnIndex(Images.Media.DATE_ADDED);
        int widthIndex = media.getColumnIndex(MediaStore.MediaColumns.WIDTH);
        int heightIndex = media.getColumnIndex(MediaStore.MediaColumns.HEIGHT);
        int dataIndex = media.getColumnIndex(MediaStore.MediaColumns.DATA);

        for (int i = 0; i < limit && !media.isAfterLast(); i++) {
            WritableMap edge = new WritableNativeMap();
            WritableMap node = new WritableNativeMap();
            ExifInterface exif = getExifInterface(media, dataIndex);
            // `DATE_TAKEN` returns time in milliseconds.
            double timestamp = media.getLong(dateTakenIndex) / 1000d;
            // If we want to use data_added and the date_taken timestamp equals 0.
            if (useDateAdded && media.getLong(dateTakenIndex) <= 0) {
                // Use date_added. `DATE_ADDED` uses time in seconds.
                timestamp = (double) media.getLong(dateAddedIndex);
            }
            boolean imageInfoSuccess = exif != null &&
                    putImageInfo(resolver, media, node, widthIndex, heightIndex, dataIndex, mimeTypeIndex, exif, useExifDateTimeOriginal);
            if (imageInfoSuccess) {
                putBasicNodeInfo(media, node, mimeTypeIndex, groupNameIndex, timestamp);
                putLocationInfo(node, exif);

                edge.putMap("node", node);
                edges.pushMap(edge);
            } else {
                // we skipped an image because we couldn't get its details (e.g. width/height), so we
                // decrement i in order to correctly reach the limit, if the cursor has enough rows
                i--;
            }
            media.moveToNext();
        }
        response.putArray("edges", edges);
    }

    private static ExifInterface getExifInterface(Cursor media, int dataIndex) {
        Uri photoUri = Uri.parse("file://" + media.getString(dataIndex));
        File file = new File(media.getString(dataIndex));
        try {
            return new ExifInterface(file.getPath());
        } catch (IOException e) {
            FLog.e(ReactConstants.TAG, "Could not get exifTimestamp for " + photoUri.toString(), e);
            return null;
        }
    }

    private static void putBasicNodeInfo(
            Cursor media,
            WritableMap node,
            int mimeTypeIndex,
            int groupNameIndex,
            double timestamp) {
        node.putString("type", media.getString(mimeTypeIndex));
        node.putString("group_name", media.getString(groupNameIndex));
        node.putDouble("timestamp", timestamp);
    }

    private static boolean putImageInfo(
            ContentResolver resolver,
            Cursor media,
            WritableMap node,
            int widthIndex,
            int heightIndex,
            int dataIndex,
            int mimeTypeIndex,
            ExifInterface exif,
            boolean useExifDateTimeOriginal) {
        WritableMap image = new WritableNativeMap();
        Uri photoUri = Uri.parse("file://" + media.getString(dataIndex));
        File file = new File(media.getString(dataIndex));
        String strFileName = file.getName();
        image.putString("uri", photoUri.toString());
        image.putString("filename", strFileName);
        float width = media.getInt(widthIndex);
        float height = media.getInt(heightIndex);

        String mimeType = media.getString(mimeTypeIndex);

        if (mimeType != null
                && mimeType.startsWith("video")) {
            try {
                AssetFileDescriptor photoDescriptor = resolver.openAssetFileDescriptor(photoUri, "r");
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(photoDescriptor.getFileDescriptor());

                try {
                    if (width <= 0 || height <= 0) {
                        width =
                                Integer.parseInt(
                                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                        height =
                                Integer.parseInt(
                                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                    }
                    int timeInMillisec =
                            Integer.parseInt(
                                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                    int playableDuration = timeInMillisec / 1000;
                    image.putInt("playableDuration", playableDuration);
                } catch (NumberFormatException e) {
                    FLog.e(
                            ReactConstants.TAG,
                            "Number format exception occurred while trying to fetch video metadata for "
                                    + photoUri.toString(),
                            e);
                    return false;
                } finally {
                    retriever.release();
                    photoDescriptor.close();
                }
            } catch (Exception e) {
                FLog.e(ReactConstants.TAG, "Could not get video metadata for " + photoUri.toString(), e);
                return false;
            }
        }

        if (width <= 0 || height <= 0) {
            try {
                AssetFileDescriptor photoDescriptor = resolver.openAssetFileDescriptor(photoUri, "r");
                BitmapFactory.Options options = new BitmapFactory.Options();
                // Set inJustDecodeBounds to true so we don't actually load the Bitmap, but only get its
                // dimensions instead.
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFileDescriptor(photoDescriptor.getFileDescriptor(), null, options);
                width = options.outWidth;
                height = options.outHeight;
                photoDescriptor.close();
            } catch (IOException e) {
                FLog.e(ReactConstants.TAG, "Could not get width/height for " + photoUri.toString(), e);
                return false;
            }
        }
        image.putDouble("width", width);
        image.putDouble("height", height);
        node.putMap("image", image);
        try {
            String exifTimestampString = null;

            if (useExifDateTimeOriginal) {
                String exifDateTimeOriginal = exif.getAttribute("DateTimeOriginal");
                if (exifDateTimeOriginal != null) {
                    exifTimestampString = exifDateTimeOriginal;
                }
            }

            if (exifTimestampString == null) {
                exifTimestampString = exif.getAttribute("DateTime");
            }

            if (exifTimestampString != null) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
                Date d = sdf.parse(exifTimestampString);
                node.putDouble("exif_timestamp", d.getTime());
            }
        } catch (ParseException e) {
            FLog.e(ReactConstants.TAG, "Could not parse exifTimestamp for " + photoUri.toString(), e);
            return false;
        }
        return true;
    }

    private static void putLocationInfo(
            WritableMap node,
            ExifInterface exif) {
        // location details are no longer indexed for privacy reasons using string Media.LATITUDE, Media.LONGITUDE
        // we manually obtain location metadata using ExifInterface#getLatLong(float[]).
        // ExifInterface is added in API level 5
        float[] imageCoordinates = new float[2];
        boolean hasCoordinates = exif.getLatLong(imageCoordinates);
        if (hasCoordinates) {
            double longitude = imageCoordinates[1];
            double latitude = imageCoordinates[0];
            WritableMap location = new WritableNativeMap();
            location.putDouble("longitude", longitude);
            location.putDouble("latitude", latitude);
            node.putMap("location", location);
        }
    }
}
