package edu.mit.mobile.android.locast.casts;

/*
 * Copyright (C) 2011-2012  MIT Mobile Experience Lab
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import android.annotation.TargetApi;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.LoaderManager;
import android.support.v4.app.LoaderManager.LoaderCallbacks;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import edu.mit.mobile.android.imagecache.ImageCache.OnImageLoadListener;
import edu.mit.mobile.android.locast.data.CastMedia;
import edu.mit.mobile.android.locast.data.MediaProvider;
import edu.mit.mobile.android.locast.memorytraces.R;

/**
 * A video player that can play CastsMedia items. This shows the title and description of the video
 * when in portrait orientation. To use, request to {@link Intent#ACTION_VIEW} a {@link CastMedia}
 * item URI.
 *
 */
public class VideoPlayer extends FragmentActivity implements LoaderCallbacks<Cursor>,
		OnPreparedListener, OnCompletionListener, OnErrorListener, OnImageLoadListener {
	private static final String TAG = VideoPlayer.class.getSimpleName();

	private static final long OSD_TIMEOUT = 5000;

	VideoView mVideoView;
	MediaPlayer mMediaPlayer;
	MediaController mMediaController;
	TextView mDescriptionView;
	TextView mTitleView;

	private final int LOADER_CASTMEDIA_DIR = 0, LOADER_CASTMEDIA_ITEM = 1;

	private static final String[] CASTMEDIA_PROJECTION = new String[] { CastMedia._ID,
			CastMedia._DESCRIPTION, CastMedia._TITLE, CastMedia._LOCAL_URI, CastMedia._MEDIA_URL,
            CastMedia._MIME_TYPE, CastMedia._LOW_BITRATE_MIME_TYPE, CastMedia._LOW_BITRATE_URL };
	//
	private static final int MSG_HIDE_TITLE = 0;

	private long mTitleShowStart;

	private final Handler mHandler = new Handler() {

		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case MSG_HIDE_TITLE:
					hideTitleNow();

					break;
			}
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {

		super.onCreate(savedInstanceState);

		setContentView(R.layout.videoplayer);

		mVideoView = (VideoView) findViewById(R.id.video);
		mMediaController = new MediaController(this);

		mMediaController.setMediaPlayer(mVideoView);
		mMediaController.setAnchorView(mVideoView);
		mVideoView.setOnPreparedListener(this);
		mVideoView.setOnErrorListener(this);
		mVideoView.setOnCompletionListener(this);

		mVideoView.setMediaController(mMediaController);

		mDescriptionView = (TextView) findViewById(R.id.description);
		mTitleView = (TextView) findViewById(R.id.title);

		final Intent intent = getIntent();

		final String action = intent.getAction();

		if (!Intent.ACTION_VIEW.equals(action)) {
			Toast.makeText(this, R.string.error_cast_could_not_play_video, Toast.LENGTH_LONG)
					.show();
			Log.e(TAG, "received unhandled action to start activity: " + intent);
			setResult(RESULT_CANCELED);
			finish();
			return;
		}

		final String type = intent.resolveType(this);

		final LoaderManager lm = getSupportLoaderManager();

		setProgressBar(true);

		if (MediaProvider.TYPE_CASTMEDIA_DIR.equals(type)) {
			lm.initLoader(LOADER_CASTMEDIA_DIR, null, this);

		} else if (MediaProvider.TYPE_CASTMEDIA_ITEM.equals(type)) {
			lm.initLoader(LOADER_CASTMEDIA_ITEM, null, this);
		}

		setFullscreen(true);
		adjustForOrientation(getResources().getConfiguration());

	}

	@Override
	protected void onResume() {
		super.onResume();
	}

	@Override
	protected void onPause() {
		super.onPause();
		mVideoView.pause();
		// this prevents leaking the activity through the message queue
		mHandler.removeMessages(MSG_HIDE_TITLE);
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		adjustForOrientation(newConfig);

		getWindow().getDecorView().requestLayout();

	}

	private void adjustForOrientation(Configuration newConfig) {
		if (Configuration.ORIENTATION_PORTRAIT == newConfig.orientation) {
			mDescriptionView.setVisibility(View.VISIBLE);
			mTitleView.setVisibility(View.VISIBLE);
			mHandler.removeMessages(MSG_HIDE_TITLE);
		} else {
			mDescriptionView.setVisibility(View.INVISIBLE);
			if (mVideoView.isPlaying()) {
				hideTitleNow();
			}
		}
	}

	@Override
	public Loader<Cursor> onCreateLoader(int id, Bundle extra) {
		switch (id) {
			case LOADER_CASTMEDIA_DIR:
				return new CursorLoader(this, getIntent().getData(), CASTMEDIA_PROJECTION, null,
						null, null);

			case LOADER_CASTMEDIA_ITEM:
				return new CursorLoader(this, getIntent().getData(), CASTMEDIA_PROJECTION, null,
						null, null);
			default:
				return null;
		}
	}

	/*
	 * When the loader finishes, start playing. If it gets called again, but it's already playing,
	 * that's probably due to a synchronization going on in the background and it shouldn't be
	 * interrupted. If it's not successfully playing, then it probably makes sense to try playing
	 * again.
	 *
	 * @see android.support.v4.app.LoaderManager.LoaderCallbacks#onLoadFinished(android
	 * .support.v4.content.Loader, java.lang.Object)
	 */
	@Override
	public void onLoadFinished(Loader<Cursor> loader, Cursor c) {
		if (!mVideoView.isPlaying()) {
			c.moveToFirst();

			final String mimeType = c.getString(c.getColumnIndex(CastMedia._MIME_TYPE));

			if (!mimeType.startsWith("video/")) {
				Log.e(TAG, "mime type of content is not video:" + mimeType);
			}

            final Uri media = CastMedia.getMedia(this, c);

			final String title = c.getString(c.getColumnIndex(CastMedia._TITLE));

			if (title != null) {
				setTitle(title);
				showTitle();
			}

			final String description = c.getString(c.getColumnIndex(CastMedia._DESCRIPTION));
			if (description != null) {
				mDescriptionView.setText(description);
			}

			// TODO figure out how to put this on a non-UI thread. Unfortunately,
			// it looks like that will require using a MediaPlayer directly as this
			// method looks like it needs to be called on the UI thread.
			mVideoView.setVideoURI(media);
			mVideoView.start();
		}
	}

	@Override
	public void onLoaderReset(Loader<Cursor> loader) {

	}

	@Override
	public void setTitle(CharSequence title) {
		super.setTitle(title);
		mTitleView.setText(title);
	}

	private void hideTitle() {

		mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_HIDE_TITLE),
				Math.max(0, OSD_TIMEOUT - (System.currentTimeMillis() - mTitleShowStart)));
	}

	private void hideTitleNow() {
		if (mTitleView.getVisibility() != View.INVISIBLE) {
			mTitleView.startAnimation(AnimationUtils.loadAnimation(VideoPlayer.this,
					R.anim.fade_out));
			mTitleView.setVisibility(View.INVISIBLE);
		}
	}

	private void showTitle() {
		if (mTitleView.getVisibility() != View.VISIBLE) {
			mTitleView.startAnimation(AnimationUtils
					.loadAnimation(VideoPlayer.this, R.anim.fade_in));
			mTitleView.setVisibility(View.VISIBLE);
			mTitleShowStart = System.currentTimeMillis();
		}
	}

	public void setFullscreen(boolean fullscreen) {

		if (fullscreen) {
			getWindow().addFlags(
					WindowManager.LayoutParams.FLAG_FULLSCREEN
							| WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
							| WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON

			);
		} else {
			getWindow().clearFlags(
					WindowManager.LayoutParams.FLAG_FULLSCREEN
							| WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
							| WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON

			);
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            setFullscreenICS(fullscreen);
		}
	}

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    private void setFullscreenICS(boolean fullscreen) {
        try {
            final Method setSystemUiVisibility = View.class.getMethod("setSystemUiVisibility",
                    int.class);
            setSystemUiVisibility.invoke(getWindow().getDecorView(),
                    fullscreen ? View.SYSTEM_UI_FLAG_LOW_PROFILE : 0);
        } catch (final NoSuchMethodException e) {
            Log.e(TAG, "missing setSystemUiVisibility method, despite version checking", e);
        } catch (final IllegalArgumentException e) {
            Log.e(TAG, "reflection error", e);
        } catch (final IllegalAccessException e) {
            Log.e(TAG, "reflection error", e);
        } catch (final InvocationTargetException e) {
            Log.e(TAG, "reflection error", e);
        }
    }

	@Override
	public void onPrepared(MediaPlayer arg0) {
		setProgressBar(false);
		hideTitle();
		setFullscreen(true);
	}

	public void setProgressBar(boolean visible) {
		final View progress = findViewById(R.id.progress);

		final int newVisibility = visible ? View.VISIBLE : View.GONE;

		if (progress.getVisibility() != newVisibility) {
			progress.setVisibility(newVisibility);
			findViewById(R.id.progress_text).setVisibility(newVisibility);

			if (visible) {
				// keep the screen on and bright while the progress bar is going, so the user
				// doesn't become annoyed while waiting
				setFullscreen(true);
			}
		}
	}

	public void setProgressText(int resid) {
		((TextView) findViewById(R.id.progress_text)).setText(resid);
	}

	@Override
	public boolean onError(MediaPlayer arg0, int what, int extra) {
		setProgressBar(false);
		setFullscreen(false);

		return false;
	}

	@Override
	public void onCompletion(MediaPlayer arg0) {
		setFullscreen(false);
	}

	@Override
    public void onImageLoaded(int id, Uri imageUri, Drawable image) {
        ((ImageView) findViewById(id)).setImageDrawable(image);
	}
}
