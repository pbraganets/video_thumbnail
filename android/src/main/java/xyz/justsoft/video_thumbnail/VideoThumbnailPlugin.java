package xyz.justsoft.video_thumbnail;

import android.graphics.Bitmap.CompressFormat;
import java.io.FileNotFoundException;

import android.media.MediaCodec;
import android.view.Surface;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaFormat;
import android.media.MediaExtractor;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;

class AV_VideoDecoder {
    final static String TAG = "VideoDecoder";
    final static String VIDEO_MIME_PREFIX = "video/";

    private MediaExtractor mMediaExtractor = null;
    private MediaCodec mMediaCodec = null;

    private Surface mSurface = null;
    private String mPath = null;
    private int mVideoTrackIndex = -1;

    public AV_VideoDecoder(String path, Surface surface) {
        mPath = path;
        mSurface = surface;

        initCodec();
    }

    public boolean prepare(long time) {
        return decodeFrameAt(time);
    }

    public void startDecode() {
    }

    public void release() {
        if (null != mMediaCodec) {
            mMediaCodec.stop();
            mMediaCodec.release();
        }

        if (null != mMediaExtractor) {
            mMediaExtractor.release();
        }
    }

    private boolean initCodec() {
        Log.i(TAG, "initCodec");
        mMediaExtractor = new MediaExtractor();
        try {
            mMediaExtractor.setDataSource(mPath);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        int trackCount = mMediaExtractor.getTrackCount();
        for (int i = 0; i < trackCount; ++i) {
            MediaFormat mf = mMediaExtractor.getTrackFormat(i);
            String mime = mf.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith(VIDEO_MIME_PREFIX)) {
                mVideoTrackIndex = i;
                break;
            }
        }
        if (mVideoTrackIndex < 0)
            return false;

        mMediaExtractor.selectTrack(mVideoTrackIndex);
        MediaFormat mf = mMediaExtractor.getTrackFormat(mVideoTrackIndex);
        String mime = mf.getString(MediaFormat.KEY_MIME);
        try {
            mMediaCodec = MediaCodec.createDecoderByType(mime);
        } catch (IOException e) {
            e.printStackTrace();
        }

        mMediaCodec.configure(mf, mSurface, null, 0);
        mMediaCodec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
        mMediaCodec.start();
        Log.i(TAG, "initCodec end");

        return true;
    }

    private boolean mIsInputEOS = false;

    private boolean decodeFrameAt(long timeUs) {
        Log.i(TAG, "decodeFrameAt " + timeUs);
        mMediaExtractor.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

        mIsInputEOS = false;
        CodecState inputState = new CodecState();
        CodecState outState = new CodecState();
        boolean reachTarget = false;
        for (; ; ) {
            if (!inputState.EOS)
                handleCodecInput(inputState);

            if (inputState.outIndex < 0) {
                handleCodecOutput(outState);
                reachTarget = processOutputState(outState, timeUs);
            } else {
                reachTarget = processOutputState(inputState, timeUs);
            }

            if (true == reachTarget || outState.EOS) {
                Log.i(TAG, "decodeFrameAt " + timeUs + " reach target or EOS");
                break;
            }

            inputState.outIndex = -1;
            outState.outIndex = -1;
        }

        return reachTarget;
    }

    private boolean processOutputState(CodecState state, long timeUs) {
        if (state.outIndex < 0)
            return false;

        if (state.outIndex >= 0 && state.info.presentationTimeUs < timeUs) {
            Log.i(TAG, "processOutputState presentationTimeUs " + state.info.presentationTimeUs);
            mMediaCodec.releaseOutputBuffer(state.outIndex, false);
            return false;
        }

        if (state.outIndex >= 0) {
            Log.i(TAG, "processOutputState presentationTimeUs " + state.info.presentationTimeUs);
            mMediaCodec.releaseOutputBuffer(state.outIndex, true);
            return true;
        }

        return false;
    }

    private class CodecState {
        int outIndex = MediaCodec.INFO_TRY_AGAIN_LATER;
        BufferInfo info = new BufferInfo();
        boolean EOS = false;
    }

    private void handleCodecInput(CodecState state) {
        ByteBuffer[] inputBuffer = mMediaCodec.getInputBuffers();

        for (; !mIsInputEOS; ) {
            int inputBufferIndex = mMediaCodec.dequeueInputBuffer(10000);
            if (inputBufferIndex < 0)
                continue;

            ByteBuffer in = inputBuffer[inputBufferIndex];
            int readSize = mMediaExtractor.readSampleData(in, 0);
            long presentationTimeUs = mMediaExtractor.getSampleTime();
            int flags = mMediaExtractor.getSampleFlags();

            boolean EOS = !mMediaExtractor.advance();
            EOS |= (readSize <= 0);
            EOS |= ((flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) > 0);

            Log.i(TAG, "input presentationTimeUs " + presentationTimeUs + " isEOS " + EOS);

            if (EOS && readSize < 0)
                readSize = 0;

            if (readSize > 0 || EOS)
                mMediaCodec.queueInputBuffer(inputBufferIndex, 0, readSize, presentationTimeUs, flags | (EOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0));

            if (EOS) {
                state.EOS = true;
                mIsInputEOS = true;
                break;
            }

            state.outIndex = mMediaCodec.dequeueOutputBuffer(state.info, 10000);
            if (state.outIndex >= 0)
                break;
        }
    }

    private void handleCodecOutput(CodecState state) {
        state.outIndex = mMediaCodec.dequeueOutputBuffer(state.info, 10000);
        if (state.outIndex < 0) {
            return;
        }

        if ((state.info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            state.EOS = true;
            Log.i(TAG, "reach output EOS " + state.info.presentationTimeUs);
        }
    }
}

class AV_BitmapUtil {
    public static void saveBitmap(Bitmap bmp, String path) {
        try {
            FileOutputStream fos = new FileOutputStream(path);
            bmp.compress(CompressFormat.JPEG, 100, fos);
            fos.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static Bitmap flip(Bitmap src) {
        Matrix matrix = new Matrix();
        matrix.preScale(1.0f, -1.0f);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
    }
}

/**
 * VideoThumbnailPlugin
 */
public class VideoThumbnailPlugin implements MethodCallHandler {
    private static String TAG = "ThumbnailPlugin";
    private static final int HIGH_QUALITY_MIN_VAL = 70;

    private ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * Plugin registration.
     */
    public static void registerWith(Registrar registrar) {
        final MethodChannel channel = new MethodChannel(registrar.messenger(), "video_thumbnail");
        channel.setMethodCallHandler(new VideoThumbnailPlugin());
    }

    @Override
    public void onMethodCall(MethodCall call, final Result result) {
        final Map<String, Object> args = call.arguments();

        final String video = (String) args.get("video");
        final int format = (int) args.get("format");
        final int maxh = (int) args.get("maxh");
        final int maxw = (int) args.get("maxw");
        final int timeMs = (int) args.get("timeMs");
        final int quality = (int) args.get("quality");
        final String method = call.method;

        executor.execute(new Runnable() {
            @Override
            public void run() {
                Object thumbnail = null;
                boolean handled = false;
                Exception exc = null;

                try {
                    if (method.equals("file")) {
                        final String path = (String) args.get("path");
                        thumbnail = buildThumbnailFile(video, path, format, maxh, maxw, timeMs, quality);
                        handled = true;

                    } else if (method.equals("data")) {
                        thumbnail = buildThumbnailData(video, format, maxh, maxw, timeMs, quality);
                        handled = true;
                    }
                } catch (Exception e) {
                    exc = e;
                }

                onResult(result, thumbnail, handled, exc);
            }
        });
    }

    private static Bitmap.CompressFormat intToFormat(int format) {
        switch (format) {
            default:
            case 0:
                return Bitmap.CompressFormat.JPEG;
            case 1:
                return Bitmap.CompressFormat.PNG;
            case 2:
                return Bitmap.CompressFormat.WEBP;
        }
    }

    private static String formatExt(int format) {
        switch (format) {
            default:
            case 0:
                return new String("jpg");
            case 1:
                return new String("png");
            case 2:
                return new String("webp");
        }
    }

    private byte[] buildThumbnailData(String vidPath, int format, int maxh, int maxw, int timeMs, int quality) {
        Log.d(TAG, String.format("buildThumbnailData( format:%d, maxh:%d, maxw:%d, timeMs:%d, quality:%d )", format,
                maxh, maxw, timeMs, quality));
        Bitmap bitmap = createVideoThumbnail(vidPath, maxh, maxw, timeMs);
        if (bitmap == null)
            throw new NullPointerException();

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(intToFormat(format), quality, stream);
        bitmap.recycle();
        if (bitmap == null)
            throw new NullPointerException();
        return stream.toByteArray();
    }

    private String buildThumbnailFile(String vidPath, String path, int format, int maxh, int maxw, int timeMs,
            int quality) {
        Log.d(TAG, String.format("buildThumbnailFile( format:%d, maxh:%d, maxw:%d, timeMs:%d, quality:%d )", format,
                maxh, maxw, timeMs, quality));
        final byte bytes[] = buildThumbnailData(vidPath, format, maxh, maxw, timeMs, quality);
        final String ext = formatExt(format);
        final int i = vidPath.lastIndexOf(".");
        String fullpath = vidPath.substring(0, i + 1) + ext;

        if (path != null) {
            if (path.endsWith(ext)) {
                fullpath = path;
            } else {
                // try to save to same folder as the vidPath
                final int j = fullpath.lastIndexOf("/");

                if (path.endsWith("/")) {
                    fullpath = path + fullpath.substring(j + 1);
                } else {
                    fullpath = path + fullpath.substring(j);
                }
            }
        }

        try {
            FileOutputStream f = new FileOutputStream(fullpath);
            f.write(bytes);
            f.close();
            Log.d(TAG, String.format("buildThumbnailFile( written:%d )", bytes.length));
        } catch (java.io.IOException e) {
            e.getStackTrace();
            throw new RuntimeException(e);
        }
        return fullpath;
    }

    private void onResult(final Result result, final Object thumbnail, final boolean handled, final Exception e) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!handled) {
                    result.notImplemented();
                    return;
                }

                if (e != null) {
                    e.printStackTrace();
                    result.error("exception", e.getMessage(), null);
                    return;
                }

                result.success(thumbnail);
            }
        });
    }

    private static void runOnUiThread(Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(runnable);
    }

    /**
     * Create a video thumbnail for a video. May return null if the video is corrupt
     * or the format is not supported.
     *
     * @param video   the URI of video
     * @param targetH the max height of the thumbnail
     * @param targetW the max width of the thumbnail
     */
    public static Bitmap createVideoThumbnail(final String video, int targetH, int targetW, int timeMs) {
        Bitmap bitmap = null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if (video.startsWith("/")) {
                retriever.setDataSource(video);
            } else if (video.startsWith("file://")) {
                retriever.setDataSource(video.substring(7));
            } else {
                retriever.setDataSource(video, new HashMap<String, String>());
            }

            if (targetH != 0 || targetW != 0) {
                if (android.os.Build.VERSION.SDK_INT >= 27 && targetH != 0 && targetW != 0) {
                    // API Level 27
                    bitmap = retriever.getScaledFrameAtTime(timeMs * 1000, 2, targetW, targetH);
                } else {
                    bitmap = retriever.getFrameAtTime(timeMs * 1000, 2);
                    if (bitmap != null) {
                        int width = bitmap.getWidth();
                        int height = bitmap.getHeight();
                        if (targetW == 0) {
                            targetW = Math.round(((float) targetH / height) * width);
                        }
                        if (targetH == 0) {
                            targetH = Math.round(((float) targetW / width) * height);
                        }
                        Log.d(TAG, String.format("original w:%d, h:%d => %d, %d", width, height, targetW, targetH));
                        bitmap = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true);
                    }
                }
            } else {
                bitmap = retriever.getFrameAtTime(timeMs * 1000, 2);
            }
        } catch (IllegalArgumentException ex) {
            ex.printStackTrace();
        } catch (RuntimeException ex) {
            ex.printStackTrace();
        } finally {
            try {
                retriever.release();
            } catch (RuntimeException ex) {
                ex.printStackTrace();
            }
        }

        return bitmap;
    }
}
