package com.moust.cordova.videoplayer;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.VideoView;

import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.RequestCreator;

import java.io.IOException;

public class VideoPlayerDialog extends Dialog {

    private static final String TAG = "VideoPlayerActivity";

    private static final String ASSETS = "/android_asset/";

    public static final String EXTRA_VOLUME = "volume";
    public static final String EXTRA_SCALING_MODE = "scalingMode";
    public static final String EXTRA_RESULT_RECEIVER = "resultReceiver";
    public static final String EXTRA_SHOW_IMAGE = "showImage";
    public static final String EXTRA_SHOW_IMAGE_DURATION = "showImageDuration";

    public static final int RESULT_PLAYBACK_ENDED = 1;
    public static final int RESULT_FINISHING = 2;
    public static final int RESULT_ERROR = 1;

    private VideoView playerView;
    private MediaPlayer player;

    private ResultReceiver resultReceiver;

    private ImageView imageView;
    private long hideImageTime = SystemClock.elapsedRealtime();
    private boolean showImage = false;
    private boolean skipPlaceholder = false;

    private final Runnable onPlaybackEnd = new Runnable() {
        @Override
        public void run() {
            if (player != null) {
                try {
                    if (player.isPlaying()) {
                        player.stop();
                    }
                } catch (IllegalStateException e) {
                    // ignore
                }
            }

            if (resultReceiver != null) {
                resultReceiver.send(RESULT_PLAYBACK_ENDED, Bundle.EMPTY);
            } else {
                cancel();
            }
        }
    };

    private Intent params;

    /**
     * Removes the "file://" prefix from the given URI string, if applicable.
     * If the given URI string doesn't have a "file://" prefix, it is returned unchanged.
     *
     * @param uriString the URI string to operate on
     * @return a path without the "file://" prefix
     */
    public static String stripFileProtocol(String uriString) {
        if (uriString.startsWith("file://")) {
            return Uri.parse(uriString).getPath();
        }
        return uriString;
    }


    public VideoPlayerDialog(@NonNull Activity activity, @NonNull Intent params) {
        super(activity, android.R.style.Theme_NoTitleBar);
        setOwnerActivity(activity);
        setCancelable(true);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(getWindow().getAttributes());
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        lp.windowAnimations = 0;
        lp.dimAmount = 0;
        getWindow().setAttributes(lp);

        this.params = params;
        determineMode(params);

        FrameLayout content = new FrameLayout(getContext());
        content.setId(android.R.id.content);
        content.setMeasureAllChildren(true);
        setContentView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        imageView = new ImageView(getContext());
        imageView.setId(android.R.id.icon1);
        content.addView(imageView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        playerView = new VideoView(getContext());
        playerView.setId(android.R.id.custom);
        content.addView(playerView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null && savedInstanceState.containsKey("hideImageTime")) {
            hideImageTime = savedInstanceState.getLong("hideImageTime", SystemClock.elapsedRealtime());
        }

        player = new MediaPlayer();
        player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                if (!showImage) {
                    imageView.postOnAnimationDelayed(new Runnable() {
                        @Override
                        public void run() {
                            imageView.setVisibility(View.INVISIBLE);
                        }
                    }, 100);
                    mp.start();
                }
            }
        });
        player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                if (!showImage) {
                    onPlaybackEnd.run();
                }
            }
        });
        player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                String error = "MediaPlayer.onError(" + what + ", " + extra + ")";
                Log.e(TAG, error);

                if (!showImage) {
                    if (resultReceiver != null) {
                        Bundle resultData = new Bundle(1);
                        resultData.putString("error", error);
                        resultReceiver.send(RESULT_ERROR, resultData);
                        resultReceiver = null;
                    }

                    cancel();
                }
                return false;
            }
        });

        onNewIntent(params);
    }

    private void preparePlayer() {
        try {
            player.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Failed to prepare player", e);
            if (!showImage) {
                if (resultReceiver != null) {
                    Bundle resultData = new Bundle(1);
                    resultData.putString("error", e.getLocalizedMessage());
                    resultReceiver.send(RESULT_ERROR, resultData);
                    resultReceiver = null;
                }

                cancel();
            }
        }
    }

    @NonNull
    @Override
    public Bundle onSaveInstanceState() {
        Bundle outState = super.onSaveInstanceState();
        outState.putLong("hideImageTime", hideImageTime);
        return outState;
    }

    public void onNewIntent(Intent intent) {
        this.params = intent;
        if (determineMode(intent)) {
            handleIntent(intent);
        }
    }

    private boolean determineMode(Intent intent) {
        if (intent.getData() == null) {
            resultReceiver = null;
            cancel();
            return false;
        }

        resultReceiver = intent.getParcelableExtra(EXTRA_RESULT_RECEIVER);
        hideImageTime = SystemClock.elapsedRealtime() + intent.getLongExtra(EXTRA_SHOW_IMAGE_DURATION, -1) + 100;
        skipPlaceholder = showImage;
        showImage = intent.getBooleanExtra(EXTRA_SHOW_IMAGE, false);
        return true;
    }

    private void handleIntent(Intent intent) {
        if (intent.getData() == null) {
            // Nothing to process...
            return;
        }

        if (showImage) {
            RequestCreator requestCreator = Picasso.get()
                    .load(intent.getData())
                    .noPlaceholder()
                    .noFade();

            if (intent.getIntExtra(EXTRA_SCALING_MODE, MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT) == MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING) {
                imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            } else {
                imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            }

            if (skipPlaceholder) {
                imageView.setVisibility(View.VISIBLE);
                playerView.setVisibility(View.INVISIBLE);
                if (player != null) {
                    try {
                        if (player.isPlaying()) {
                            player.stop();
                        }
                    } catch (IllegalStateException e) {
                        // ignore
                    }
                }
                requestCreator
                        .into(imageView);
            } else {
                playerView.bringToFront();
                imageView.setVisibility(View.VISIBLE);
                requestCreator
                        .into(imageView, new Callback() {
                            @Override
                            public void onSuccess() {
                                imageView.postOnAnimationDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        playerView.setVisibility(View.INVISIBLE);
                                        if (player != null) {
                                            try {
                                                if (player.isPlaying()) {
                                                    player.stop();
                                                }
                                            } catch (IllegalStateException e) {
                                                // ignore
                                            }
                                        }
                                    }
                                }, 100);
                            }

                            @Override
                            public void onError(Exception e) {
                                imageView.postOnAnimationDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        playerView.setVisibility(View.INVISIBLE);
                                        if (player != null) {
                                            try {
                                                if (player.isPlaying()) {
                                                    player.stop();
                                                }
                                            } catch (IllegalStateException e) {
                                                // ignore
                                            }
                                        }
                                    }
                                }, 100);
                            }
                        });
            }
            imageView.postDelayed(onPlaybackEnd, Math.max(0, hideImageTime - SystemClock.elapsedRealtime()));
        } else {
            if (player != null) {
                try {
                    if (player.isPlaying()) {
                        player.stop();
                    }
                } catch (IllegalStateException e) {
                    // ignore
                }
            }

            if (player != null) {
                try {
                    player.reset();
                } catch (IllegalStateException e) {
                    // ignore
                }
            }

            imageView.bringToFront();
            playerView.setVisibility(View.VISIBLE);
            // Will be hidden on first rendered frame.
//            imageView.setVisibility(View.INVISIBLE);
            imageView.removeCallbacks(onPlaybackEnd);

            final String path = stripFileProtocol(intent.getData().toString());
            if (path.startsWith(ASSETS)) {
                String f = path.substring(15);
                AssetFileDescriptor fd = null;
                try {
                    fd = getContext().getAssets().openFd(f);
                    player.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                } catch (Exception ee) {
                    Log.e(TAG, "Failed to prepare player", ee);
                    if (resultReceiver != null) {
                        Bundle resultData = new Bundle(1);
                        resultData.putString("error", ee.getLocalizedMessage());
                        resultReceiver.send(RESULT_ERROR, resultData);
                        resultReceiver = null;
                    }

                    cancel();
                    return;
                }
            } else {
                try {
                    player.setDataSource(path);
                } catch (Exception eee) {
                    Log.e(TAG, "Failed to prepare player", eee);
                    if (resultReceiver != null) {
                        Bundle resultData = new Bundle(1);
                        resultData.putString("error", eee.getLocalizedMessage());
                        resultReceiver.send(RESULT_ERROR, resultData);
                        resultReceiver = null;
                    }

                    cancel();
                    return;
                }
            }

            if (intent.getIntExtra(EXTRA_SCALING_MODE, MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT) == MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING) {
                player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            } else {
                player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            }

            float volume = 1F;
            if (intent.hasExtra(EXTRA_VOLUME)) {
                String volumeStr = intent.getStringExtra(EXTRA_VOLUME);
                try {
                    volume = Float.parseFloat(volumeStr);
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Invalid volume level: " + volumeStr);
                }
            }
            player.setVolume(volume, volume);

            final SurfaceHolder mHolder = playerView.getHolder();
            mHolder.setKeepScreenOn(true);
            if (mHolder.getSurface() == null || !mHolder.getSurface().isValid()) {
                mHolder.addCallback(new SurfaceHolder.Callback() {
                    @Override
                    public void surfaceCreated(SurfaceHolder holder) {
                        Log.d(TAG, "Surface created");
                        player.setDisplay(holder);
                        preparePlayer();
                        mHolder.removeCallback(this);
                    }

                    @Override
                    public void surfaceDestroyed(SurfaceHolder holder) {
                        Log.d(TAG, "Surface destroyed");
                        mHolder.removeCallback(this);
                    }

                    @Override
                    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    }
                });
            } else {
                Log.d(TAG, "Surface ready");
                player.setDisplay(mHolder);
                preparePlayer();
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            try {
                if (player.isPlaying()) {
                    player.stop();
                }
            } catch (IllegalStateException e) {
                // ignore
            }
            player.release();
        }
        if (imageView != null) {
            imageView.removeCallbacks(onPlaybackEnd);
        }
        if (resultReceiver != null) {
            resultReceiver.send(RESULT_FINISHING, Bundle.EMPTY);
        }
    }
}
