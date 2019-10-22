package com.moust.cordova.videoplayer;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MediaSourceEventListener;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.VideoListener;
import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;

import static com.google.android.exoplayer2.C.VIDEO_SCALING_MODE_SCALE_TO_FIT;
import static com.google.android.exoplayer2.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING;

public class VideoPlayerDialog extends Dialog {

    private static final String TAG = "VideoPlayerActivity";

    private static final DefaultBandwidthMeter BANDWIDTH_METER = new DefaultBandwidthMeter();

    public static final String EXTRA_VOLUME = "volume";
    public static final String EXTRA_SCALING_MODE = "scalingMode";
    public static final String EXTRA_RESULT_RECEIVER = "resultReceiver";
    public static final String EXTRA_SHOW_IMAGE = "showImage";
    public static final String EXTRA_SHOW_IMAGE_DURATION = "showImageDuration";

    public static final int RESULT_PLAYBACK_ENDED = 1;
    public static final int RESULT_FINISHING = 2;
    public static final int RESULT_ERROR = 1;

    private PlayerView playerView;
    private SimpleExoPlayer player;
    private DataSource.Factory mediaDataSourceFactory;

    private ResultReceiver resultReceiver;

    private ImageView imageView;
    private long hideImageTime = SystemClock.elapsedRealtime();
    private boolean showImage = false;
    private boolean skipPlaceholder = false;

    private final Runnable onPlaybackEnd = new Runnable() {
        @Override
        public void run() {
            if (player != null) {
                player.stop(false);
            }

            if (resultReceiver != null) {
                resultReceiver.send(RESULT_PLAYBACK_ENDED, Bundle.EMPTY);
            } else {
                cancel();
            }
        }
    };

    private Intent params;


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

        playerView = new PlayerView(getContext());
        playerView.setId(android.R.id.custom);
        playerView.setUseController(false);
        playerView.setUseArtwork(false);
        playerView.setShutterBackgroundColor(Color.TRANSPARENT);
        content.addView(playerView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null && savedInstanceState.containsKey("hideImageTime")) {
            hideImageTime = savedInstanceState.getLong("hideImageTime", SystemClock.elapsedRealtime());
        }

        TrackSelection.Factory adaptiveTrackSelectionFactory = new AdaptiveTrackSelection.Factory(BANDWIDTH_METER);
        player = ExoPlayerFactory.newSimpleInstance(getContext(), new DefaultTrackSelector(adaptiveTrackSelectionFactory));
        player.setPlayWhenReady(true);
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        playerView.setPlayer(player);

        mediaDataSourceFactory = buildDataSourceFactory(true);

        player.addListener(new Player.EventListener() {
            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                if (playWhenReady && playbackState == Player.STATE_READY) {
                    // Active playback.
                } else if (playWhenReady) {
                    // Not playing because playback ended, the player is buffering, stopped or
                    // failed. Check playbackState and player.getPlaybackError for details.
                    if (playbackState == Player.STATE_ENDED && !showImage) {
                        onPlaybackEnd.run();
                    }
                } else {
                    // Paused by app.
                }
            }

            @Override
            public void onPlayerError(ExoPlaybackException error) {
                Log.e(TAG, "Playback error", error);

                if (!showImage) {
                    if (resultReceiver != null) {
                        Bundle resultData = new Bundle(1);
                        resultData.putString("error", error.getLocalizedMessage());
                        resultReceiver.send(RESULT_ERROR, resultData);
                        resultReceiver = null;
                    }

                    cancel();
                }
            }

            @Override
            public void onTimelineChanged(Timeline timeline, Object manifest, int reason) {

            }

            @Override
            public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {

            }

            @Override
            public void onLoadingChanged(boolean isLoading) {

            }

            @Override
            public void onRepeatModeChanged(int repeatMode) {

            }

            @Override
            public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {

            }

            @Override
            public void onPositionDiscontinuity(int reason) {

            }

            @Override
            public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {

            }

            @Override
            public void onSeekProcessed() {

            }
        });

        player.addVideoListener(new VideoListener() {
            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                AspectRatioFrameLayout contentFrame = (AspectRatioFrameLayout) playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_content_frame);
                if (contentFrame.getResizeMode() == AspectRatioFrameLayout.RESIZE_MODE_FILL) {
                    float videoAspectRatio =
                            (height == 0 || width == 0) ? 1 : (width * pixelWidthHeightRatio) / height;
                    int viewHeight = playerView.getHeight();
                    int viewWidth = playerView.getWidth();
                    float viewAspectRatio = (float) width / height;
                    float aspectDeformation = videoAspectRatio / viewAspectRatio - 1;
                    if (aspectDeformation > 0) {
                        viewWidth = (int) (viewHeight * videoAspectRatio);
                    } else {
                        viewHeight = (int) (viewWidth / videoAspectRatio);
                    }
                    ViewGroup.LayoutParams lp = contentFrame.getLayoutParams();
                    lp.height = viewHeight;
                    lp.width = viewWidth;
                    contentFrame.setLayoutParams(lp);
                } else {
                    playerView.requestLayout();
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                if (!showImage) {
                    imageView.postOnAnimationDelayed(new Runnable() {
                        @Override
                        public void run() {
                            imageView.setVisibility(View.GONE);
                        }
                    }, 100);
                }
            }
        });

        onNewIntent(params);
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

        if (intent.getIntExtra(EXTRA_SCALING_MODE, MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT) == MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING) {
            player.setVideoScalingMode(VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            player.setVideoScalingMode(VIDEO_SCALING_MODE_SCALE_TO_FIT);
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
            imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        }

        if (showImage) {
            if (skipPlaceholder) {
                imageView.setVisibility(View.VISIBLE);
                playerView.setVisibility(View.GONE);
                player.stop(false);
                Picasso.get()
                        .load(intent.getData())
                        .noPlaceholder()
                        .noFade()
                        .into(imageView);
            } else {
                playerView.bringToFront();
                imageView.setVisibility(View.VISIBLE);
                Picasso.get()
                        .load(intent.getData())
                        .noFade()
                        .noPlaceholder()
                        .into(imageView, new Callback() {
                            @Override
                            public void onSuccess() {
                                imageView.postOnAnimationDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        playerView.setVisibility(View.GONE);
                                        player.stop(false);
                                    }
                                }, 100);
                            }

                            @Override
                            public void onError(Exception e) {
                                imageView.postOnAnimationDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        playerView.setVisibility(View.GONE);
                                        player.stop(false);
                                    }
                                }, 100);
                            }
                        });
            }
            imageView.postDelayed(onPlaybackEnd, Math.max(0, hideImageTime - SystemClock.elapsedRealtime()));
        } else {
            imageView.bringToFront();
            playerView.setVisibility(View.VISIBLE);
            // Will be hidden on first rendered frame.
//            imageView.setVisibility(View.GONE);
            imageView.removeCallbacks(onPlaybackEnd);

            float volume = 1F;
            if (intent.hasExtra(EXTRA_VOLUME)) {
                String volumeStr = intent.getStringExtra(EXTRA_VOLUME);
                try {
                    volume = Float.parseFloat(volumeStr);
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Invalid volume level: " + volumeStr);
                }
            }
            player.setVolume(volume);
            player.prepare(buildMediaSource(intent.getData(), null, null));
        }
    }

    private MediaSource buildMediaSource(
            Uri uri,
            @Nullable Handler handler,
            @Nullable MediaSourceEventListener listener) {
        @C.ContentType int type = Util.inferContentType(uri);
        switch (type) {
            case C.TYPE_DASH:
                return new DashMediaSource.Factory(
                        new DefaultDashChunkSource.Factory(mediaDataSourceFactory),
                        buildDataSourceFactory(false))
                        .createMediaSource(uri, handler, listener);
            case C.TYPE_SS:
                return new SsMediaSource.Factory(
                        new DefaultSsChunkSource.Factory(mediaDataSourceFactory),
                        buildDataSourceFactory(false))
                        .createMediaSource(uri, handler, listener);
            case C.TYPE_HLS:
                return new HlsMediaSource.Factory(mediaDataSourceFactory)
                        .createMediaSource(uri, handler, listener);
            case C.TYPE_OTHER:
                return new ExtractorMediaSource.Factory(mediaDataSourceFactory)
                        .createMediaSource(uri, handler, listener);
            default: {
                throw new IllegalStateException("Unsupported type: " + type);
            }
        }
    }

    /**
     * Returns a new DataSource factory.
     *
     * @param useBandwidthMeter Whether to set {@link #BANDWIDTH_METER} as a listener to the new
     *                          DataSource factory.
     * @return A new DataSource factory.
     */
    private DataSource.Factory buildDataSourceFactory(boolean useBandwidthMeter) {
        TransferListener<? super DataSource> listener = useBandwidthMeter ? BANDWIDTH_METER : null;
        return new DefaultDataSourceFactory(getContext().getApplicationContext(), listener,
                new DefaultHttpDataSourceFactory(Util.getUserAgent(getContext(), "me.vandalko.videoplayer"), listener));
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            player.setPlayWhenReady(false);
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
