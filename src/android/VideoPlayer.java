package com.moust.cordova.videoplayer;

import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.text.TextUtils;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaResourceApi;
import org.apache.cordova.PluginResult;
import org.json.JSONException;
import org.json.JSONObject;

public class VideoPlayer extends VideoPlayerOld {

    private static final String LOG_TAG = "VideoPlayer";

    private static boolean USE_OLD_PLUGIN = false;

    private final PlaybackResultReceiver resultReceiver = new PlaybackResultReceiver();

    private CallbackContext callbackContext = null;

    private VideoPlayerDialog dialog = null;

    /**
     * Executes the request and returns PluginResult.
     *
     * @param action The action to execute.
     * @param args   JSONArray of arguments for the plugin.
     * @return A PluginResult object with a status and message.
     */
    public boolean execute(final String action, final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
        if (USE_OLD_PLUGIN) {
            return super.execute(action, args, callbackContext);
        }

        if ("play".equals(action) || "display".equals(action)) {
            this.callbackContext = callbackContext;

            CordovaResourceApi resourceApi = webView.getResourceApi();
            String target = args.getString(0);
            final JSONObject options = args.getJSONObject(1);

            String fileUriStr;
            try {
                Uri targetUri = resourceApi.remapUri(Uri.parse(target));
                fileUriStr = targetUri.toString();
            } catch (IllegalArgumentException e) {
                fileUriStr = target;
            }

            Log.v(LOG_TAG, fileUriStr);

            final Uri targetUri = Uri.parse(fileUriStr);
            cordova.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (dialog != null && (dialog.getOwnerActivity() != cordova.getActivity() || !dialog.isShowing())) {
                        dialog.cancel();
                        dialog = null;
                    }
                    Intent launchIntent = new Intent()
                            .setData(targetUri)
                            .putExtra(VideoPlayerDialog.EXTRA_VOLUME, options.optString("volume", "1"))
                            .putExtra(VideoPlayerDialog.EXTRA_SCALING_MODE, options.optInt("scalingMode", MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT))
                            .putExtra(VideoPlayerDialog.EXTRA_RESULT_RECEIVER, resultReceiver)
                            .putExtra(VideoPlayerDialog.EXTRA_SHOW_IMAGE, "display".equals(action))
                            .putExtra(VideoPlayerDialog.EXTRA_SHOW_IMAGE_DURATION, options.optLong("showImageDuration", -1))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    if (dialog == null) {
                        dialog = new VideoPlayerDialog(cordova.getActivity(), launchIntent);
                        dialog.show();
                    } else {
                        dialog.onNewIntent(launchIntent);
                    }
                }
            });

            // Don't return any result now
            if (callbackContext != null) {
                PluginResult pluginResult = new PluginResult(PluginResult.Status.NO_RESULT);
                pluginResult.setKeepCallback(true);
                callbackContext.sendPluginResult(pluginResult);
            }

            return true;
        } else if ("close".equals(action)) {
            this.callbackContext = null;

            cordova.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (dialog != null && dialog.isShowing()) {
                        dialog.onNewIntent(new Intent());
                    }
                    dialog = null;
                }
            });

            if (callbackContext != null) {
                PluginResult result = new PluginResult(PluginResult.Status.OK);
                result.setKeepCallback(false); // release status callback in JS side
                callbackContext.sendPluginResult(result);
            }

            return true;
        }
        return false;
    }


    private class PlaybackResultReceiver extends ResultReceiver {

        public PlaybackResultReceiver() {
            super(null);
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            Log.e(LOG_TAG, "Got result[" + resultCode + "] error=" + (resultData == null ? null : resultData.getString("error")));
            CallbackContext callback = callbackContext;
            if (callback != null) {
                if (resultCode == VideoPlayerDialog.RESULT_PLAYBACK_ENDED) {
                    PluginResult result = new PluginResult(PluginResult.Status.OK);
                    result.setKeepCallback(true);
                    callback.sendPluginResult(result);
                } else {
                    PluginResult result;
                    if (resultCode == VideoPlayerDialog.RESULT_FINISHING) {
                        result = new PluginResult(PluginResult.Status.OK);
                    } else {
                        String error = resultData == null ? null : resultData.getString("error");
                        if (TextUtils.isEmpty(error)) {
                            error = "Unknown error";
                        }
                        result = new PluginResult(PluginResult.Status.ERROR, error);
                    }
                    result.setKeepCallback(false); // release status callback in JS side
                    callback.sendPluginResult(result);
                    callbackContext = null;
                    dialog = null;
                }
            }
        }
    }
}
