package xyz.justsoft.video_thumbnail;

import android.graphics.Bitmap.CompressFormat;
import java.io.FileNotFoundException;

import android.media.MediaCodec;
import android.view.Surface;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaFormat;
import android.media.MediaExtractor;
import java.nio.ByteBuffer;

import android.os.HandlerThread;
import android.graphics.SurfaceTexture;

import android.opengl.*;
import java.nio.FloatBuffer;
import android.annotation.SuppressLint;
import java.nio.ByteOrder;
import android.graphics.SurfaceTexture.OnFrameAvailableListener;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
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

class AV_GLHelper {

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final int EGL_OPENGL_ES2_BIT = 4;

    private SurfaceTexture mSurfaceTexture;
    private AV_TextureRender mTextureRender;

    private EGLDisplay mEglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mEglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface mEglSurface = EGL14.EGL_NO_SURFACE;

    public void init(SurfaceTexture st) {
        mSurfaceTexture = st;
        initGL();

        makeCurrent();
        mTextureRender = new AV_TextureRender();
    }

    private void initGL() {
        mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mEglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("eglGetdisplay failed : " +
                    GLUtils.getEGLErrorString(EGL14.eglGetError()));
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) {
            mEglDisplay = null;
            throw new RuntimeException("unable to initialize EGL14");
        }

        // Configure EGL for pbuffer and OpenGL ES 2.0.  We want enough RGB bits
        // to be able to tell if the frame is reasonable.
        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mEglDisplay, attribList, 0, configs, 0, configs.length,
                numConfigs, 0)) {
            throw new RuntimeException("unable to find RGB888+recordable ES2 EGL config");
        }

        // Configure context for OpenGL ES 2.0.
        int[] attrib_list = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        mEglContext = EGL14.eglCreateContext(mEglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                attrib_list, 0);
        AV_GLUtil.checkEglError("eglCreateContext");
        if (mEglContext == null) {
            throw new RuntimeException("null context");
        }

        // Create a window surface, and attach it to the Surface we received.
        int[] surfaceAttribs = {
                EGL14.EGL_NONE
        };
        mEglSurface = EGL14.eglCreateWindowSurface(mEglDisplay, configs[0], new Surface(mSurfaceTexture),
                surfaceAttribs, 0);
        AV_GLUtil.checkEglError("eglCreateWindowSurface");
        if (mEglSurface == null) {
            throw new RuntimeException("surface was null");
        }
    }

    public void release() {
        if (null != mSurfaceTexture)
            mSurfaceTexture.release();
    }

    public void makeCurrent() {
        if (!EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    public int createOESTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);

        int textureID = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureID);
        AV_GLUtil.checkEglError("glBindTexture textureID");

        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        AV_GLUtil.checkEglError("glTexParameter");

        return textureID;
    }

    public void drawFrame(SurfaceTexture st, int textureID) {
        st.updateTexImage();
        if (null != mTextureRender)
            mTextureRender.drawFrame(st, textureID);
    }

    public Bitmap readPixels(int width, int height) {
        ByteBuffer PixelBuffer = ByteBuffer.allocateDirect(4 * width * height);
        PixelBuffer.position(0);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, PixelBuffer);

        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        PixelBuffer.position(0);
        bmp.copyPixelsFromBuffer(PixelBuffer);

        return bmp;
    }
}

class AV_GLUtil {
    /**
     * Checks for EGL errors.
     */
    public static void checkEglError(String msg) {
        boolean failed = false;
        int error;
        while ((error = EGL14.eglGetError()) != EGL14.EGL_SUCCESS) {
            Log.e("TAG", msg + ": EGL error: 0x" + Integer.toHexString(error));
            failed = true;
        }
        if (failed) {
            throw new RuntimeException("EGL error encountered (see log)");
        }
    }   
}

class AV_TextureRender {
    private static final String TAG = "TextureRender";

    private static final int FLOAT_SIZE_BYTES = 4;
    private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
    private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
    private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;
    private final float[] mTriangleVerticesData = {
        // X, Y, Z, U, V
        -1.0f, -1.0f, 0, 0.f, 1.f,
         1.0f, -1.0f, 0, 1.f, 1.f,
        -1.0f,  1.0f, 0, 0.f, 0.f,
         1.0f,  1.0f, 0, 1.f, 0.f,
    };

    private FloatBuffer mTriangleVertices;

    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uSTMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "  gl_Position = uMVPMatrix * aPosition;\n" +
            "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +      // highp here doesn't seem to matter
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "  vec2 texcoord = vTextureCoord;\n" +
            "  vec3 normalColor = texture2D(sTexture, texcoord).rgb;\n" +
            "  normalColor = vec3(normalColor.r, normalColor.g, normalColor.b);\n" + 
            "  gl_FragColor = vec4(normalColor.r, normalColor.g, normalColor.b, 1); \n"+ 
            "}\n";

    private float[] mMVPMatrix = new float[16];
    private float[] mSTMatrix = new float[16];

    private int mProgram;
    private int muMVPMatrixHandle;
    private int muSTMatrixHandle;
    private int maPositionHandle;
    private int maTextureHandle;

    public AV_TextureRender() {
        mTriangleVertices = ByteBuffer.allocateDirect(
            mTriangleVerticesData.length * FLOAT_SIZE_BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mTriangleVertices.put(mTriangleVerticesData).position(0);

        Matrix.setIdentityM(mSTMatrix, 0);
        init();
    }

    public void drawFrame(SurfaceTexture st, int textureID) {
        AV_GLUtil.checkEglError("onDrawFrame start");
        st.getTransformMatrix(mSTMatrix);

        GLES20.glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);

        if (GLES20.glIsProgram( mProgram ) != true){
            reCreateProgram();
        }

        GLES20.glUseProgram(mProgram);
        AV_GLUtil.checkEglError("glUseProgram");

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureID);

        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
        AV_GLUtil.checkEglError("glVertexAttribPointer maPosition");
        GLES20.glEnableVertexAttribArray(maPositionHandle);
        AV_GLUtil.checkEglError("glEnableVertexAttribArray maPositionHandle");

        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
        GLES20.glVertexAttribPointer(maTextureHandle, 3, GLES20.GL_FLOAT, false,
            TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
        AV_GLUtil.checkEglError("glVertexAttribPointer maTextureHandle");
        GLES20.glEnableVertexAttribArray(maTextureHandle);
        AV_GLUtil.checkEglError("glEnableVertexAttribArray maTextureHandle");

        Matrix.setIdentityM(mMVPMatrix, 0);
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0);
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        AV_GLUtil.checkEglError("glDrawArrays");
        GLES20.glFinish();
    }

    /**
     * Initializes GL state.  Call this after the EGL surface has been created and made current.
     */
    public void init() {
        mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (mProgram == 0) {
            throw new RuntimeException("failed creating program");
        }
        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        AV_GLUtil.checkEglError("glGetAttribLocation aPosition");
        if (maPositionHandle == -1) {
            throw new RuntimeException("Could not get attrib location for aPosition");
        }
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
        AV_GLUtil.checkEglError("glGetAttribLocation aTextureCoord");
        if (maTextureHandle == -1) {
            throw new RuntimeException("Could not get attrib location for aTextureCoord");
        }

        muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        AV_GLUtil.checkEglError("glGetUniformLocation uMVPMatrix");
        if (muMVPMatrixHandle == -1) {
            throw new RuntimeException("Could not get attrib location for uMVPMatrix");
        }

        muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
        AV_GLUtil.checkEglError("glGetUniformLocation uSTMatrix");
        if (muSTMatrixHandle == -1) {
            throw new RuntimeException("Could not get attrib location for uSTMatrix");
        }
    }

    private void reCreateProgram() {
        mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (mProgram == 0) {
            throw new RuntimeException("failed creating program");
        }
        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        AV_GLUtil.checkEglError("glGetAttribLocation aPosition");
        if (maPositionHandle == -1) {
            throw new RuntimeException("Could not get attrib location for aPosition");
        }
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
        AV_GLUtil.checkEglError("glGetAttribLocation aTextureCoord");
        if (maTextureHandle == -1) {
            throw new RuntimeException("Could not get attrib location for aTextureCoord");
        }

        muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        AV_GLUtil.checkEglError("glGetUniformLocation uMVPMatrix");
        if (muMVPMatrixHandle == -1) {
            throw new RuntimeException("Could not get attrib location for uMVPMatrix");
        }

        muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
        AV_GLUtil.checkEglError("glGetUniformLocation uSTMatrix");
        if (muSTMatrixHandle == -1) {
            throw new RuntimeException("Could not get attrib location for uSTMatrix");
        }
    }

    private int loadShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        AV_GLUtil.checkEglError("glCreateShader type=" + shaderType);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Could not compile shader " + shaderType + ":");
            Log.e(TAG, " " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            shader = 0;
        }
        return shader;
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vertexShader == 0) {
            return 0;
        }
        int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (pixelShader == 0) {
            return 0;
        }

        int program = GLES20.glCreateProgram();
        AV_GLUtil.checkEglError("glCreateProgram");
        if (program == 0) {
            Log.e(TAG, "Could not create program");
        }
        GLES20.glAttachShader(program, vertexShader);
        AV_GLUtil.checkEglError("glAttachShader");
        GLES20.glAttachShader(program, pixelShader);
        AV_GLUtil.checkEglError("glAttachShader");
        GLES20.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link program: ");
            Log.e(TAG, GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        return program;
    }
}

class AV_FrameCapture {
    final static String TAG = "AV_FrameCapture";

    private HandlerThread mGLThread = null;
    private Handler mGLHandler = null;
    private AV_GLHelper mGLHelper = null;

    private int mDefaultTextureID = 10001;

    private int mWidth = 1920;
    private int mHeight = 1080;

    private String mPath = null;

    public AV_FrameCapture() {
        mGLHelper = new AV_GLHelper();
        mGLThread = new HandlerThread("AV_FrameCapture");

        mGLThread.start();
        mGLHandler = new Handler(mGLThread.getLooper());
    }

    public void setDataSource(String path) {
        mPath = path;
    }

    public void setTargetSize(int width, int height) {
        mWidth = width;
        mHeight = height;
    }

    public void init() {
        mGLHandler.post(new Runnable() {
            @Override
            public void run() {
                SurfaceTexture st = new SurfaceTexture(mDefaultTextureID);
                st.setDefaultBufferSize(mWidth, mHeight);
                mGLHelper.init(st);
            }
        });
    }

    public void release() {
        mGLHandler.post(new Runnable() {
            @Override
            public void run() {
                mGLHelper.release();
                mGLThread.quit();
            }
        });
    }

    private Object mWaitBitmap = new Object();
    private Bitmap mBitmap = null;

    public Bitmap getFrameAtTime(final long frameTime) {
        if (null == mPath || mPath.isEmpty()) {
            throw new RuntimeException("Illegal State");
        }

        mGLHandler.post(new Runnable() {
            @Override
            public void run() {
                getFrameAtTimeImpl(frameTime);
            }
        });

        synchronized (mWaitBitmap) {
            try {
                mWaitBitmap.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        return mBitmap;
    }

    @SuppressLint("SdCardPath")
    public void getFrameAtTimeImpl(long frameTime) {
        final int textureID = mGLHelper.createOESTexture();
        final SurfaceTexture st = new SurfaceTexture(textureID);
        final Surface surface = new Surface(st);
        final AV_VideoDecoder vd = new AV_VideoDecoder(mPath, surface);
        st.setOnFrameAvailableListener(new OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                Log.i(TAG, "onFrameAvailable");
                mGLHelper.drawFrame(st, textureID);
                mBitmap = mGLHelper.readPixels(mWidth, mHeight);
                synchronized (mWaitBitmap) {
                    mWaitBitmap.notify();
                }

                vd.release();
                st.release();
                surface.release();
            }
        });

        if (!vd.prepare(frameTime)) {
            mBitmap = null;
            synchronized (mWaitBitmap) {
                mWaitBitmap.notify();
            }
        }
    }
}

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
        android.graphics.Matrix matrix = new android.graphics.Matrix();
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
    
    private static final mFrameCapture = new AV_FrameCapture();
    public static Bitmap getFrameAtTimeByFrameCapture(String path, long time, int snapshot_width, int snapshot_height) {
        mFrameCapture.setDataSource(path);
        mFrameCapture.setTargetSize(snapshot_width, snapshot_height);
        mFrameCapture.init();
        return mFrameCapture.getFrameAtTime(time);
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
            String path = "";
            if (video.startsWith("/")) {
                retriever.setDataSource(video);
                path = video;
            } else if (video.startsWith("file://")) {
                retriever.setDataSource(video.substring(7));
                path = video.substring(7);
            } else {
                retriever.setDataSource(video, new HashMap<String, String>());
                path = video;
            }

            if (targetH != 0 || targetW != 0) {
                if (android.os.Build.VERSION.SDK_INT >= 27 && targetH != 0 && targetW != 0) {
                    // API Level 27
                    //bitmap = retriever.getScaledFrameAtTime(timeMs * 1000, 2, targetW, targetH);
                    bitmap = getFrameAtTimeByFrameCapture(path, timeMs * 1000, targetW, targetH);
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
