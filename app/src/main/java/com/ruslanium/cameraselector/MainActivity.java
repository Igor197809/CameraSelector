package com.ruslanium.cameraselector;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 100;

    private TextureView textureView;
    private Spinner cameraSpinner;
    private Button photoButton;
    private Button videoButton;
    private TextView statusText;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewBuilder;
    private ImageReader imageReader;
    private MediaRecorder mediaRecorder;

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private final List<String> cameraIds = new ArrayList<>();
    private String selectedCameraId;
    private Size previewSize;
    private Size photoSize;
    private Size videoSize;

    private boolean isRecording = false;
    private Uri pendingVideoUri;
    private ParcelFileDescriptor pendingVideoPfd;
    private File legacyVideoFile;

    // Первый реально показанный кадр текущей камеры.
    // Если камера после него падает, сохраняем этот кадр как фото.
    private final Object emergencyFrameLock = new Object();
    private Bitmap emergencyPreviewBitmap;
    private boolean needEmergencyPreviewFrame = false;
    private boolean emergencyPreviewSaved = false;
    private String emergencyPreviewCameraId;

    // Защитный режим для камер, у которых Samsung Camera HAL
    // нестабильно работает в обычном режиме.
    private boolean safeCameraMode = false;
    private int cameraRetryCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        buildUi();
        requestNeededPermissions();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        final int basePadding = dp(12);
        root.setPadding(basePadding, basePadding, basePadding, basePadding);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int left;
            int top;
            int right;
            int bottom;

            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars =
                        insets.getInsets(android.view.WindowInsets.Type.systemBars());

                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }

            v.setPadding(
                    basePadding + left,
                    basePadding + top,
                    basePadding + right,
                    basePadding + bottom
            );

            return insets;
        });

        statusText = new TextView(this);
        statusText.setText("Разрешите доступ к камере");
        statusText.setTextSize(15f);
        statusText.setPadding(0, 0, 0, dp(8));
        root.addView(statusText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        cameraSpinner = new Spinner(this);
        root.addView(cameraSpinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        textureView = new TextureView(this);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        previewLp.topMargin = dp(8);
        previewLp.bottomMargin = dp(8);
        root.addView(textureView, previewLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);

        photoButton = new Button(this);
        photoButton.setText("ФОТО");
        photoButton.setEnabled(false);
        buttons.addView(photoButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        videoButton = new Button(this);
        videoButton.setText("● ВИДЕО");
        videoButton.setEnabled(false);
        LinearLayout.LayoutParams videoLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        videoLp.leftMargin = dp(8);
        buttons.addView(videoButton, videoLp);
        root.addView(buttons);

        setContentView(root);
        root.requestApplyInsets();

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (hasCameraPermission() && selectedCameraId != null) openSelectedCamera();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                captureEmergencyPreviewFrame();
            }
        });

        photoButton.setOnClickListener(v -> takePhoto());
        videoButton.setOnClickListener(v -> {
            if (isRecording) stopVideoRecording();
            else startVideoRecording();
        });
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void requestNeededPermissions() {
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.CAMERA);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);

        if (missing.isEmpty()) initializeCameras();
        else requestPermissions(missing.toArray(new String[0]), REQ_PERMISSIONS);
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAudioPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (hasCameraPermission()) initializeCameras();
            else statusText.setText("Без разрешения CAMERA приложение не сможет работать");
        }
    }

    private void initializeCameras() {
        try {
            cameraIds.clear();
            List<String> labels = new ArrayList<>();
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                String side;
                if (facing == null) side = "неизвестно";
                else if (facing == CameraCharacteristics.LENS_FACING_BACK) side = "задняя";
                else if (facing == CameraCharacteristics.LENS_FACING_FRONT) side = "передняя";
                else if (Build.VERSION.SDK_INT >= 23 && facing == CameraCharacteristics.LENS_FACING_EXTERNAL) side = "внешняя";
                else side = "неизвестно";

                String focalText = "";
                if (focal != null && focal.length > 0) {
                    StringBuilder sb = new StringBuilder(" • ");
                    for (int i = 0; i < focal.length; i++) {
                        if (i > 0) sb.append("/");
                        sb.append(String.format(Locale.US, "%.1f", focal[i]));
                    }
                    sb.append(" мм");
                    focalText = sb.toString();
                }
                cameraIds.add(id);
                labels.add("ID " + id + " • " + side + focalText);
            }

            if (cameraIds.isEmpty()) {
                statusText.setText("Камеры не найдены");
                return;
            }

            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_dropdown_item, labels);
            cameraSpinner.setAdapter(adapter);
            cameraSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (position < 0 || position >= cameraIds.size()) return;
                    String newId = cameraIds.get(position);
                    if (!newId.equals(selectedCameraId)) {
                        selectedCameraId = newId;
                        cameraRetryCount = 0;

                        // На S22 Ultra ID 0 пробуем сразу в консервативном режиме.
                        safeCameraMode = "0".equals(newId);

                        closeCamera();

                        if (textureView.isAvailable()) {
                            openSelectedCamera();
                        }
                    }
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
            selectedCameraId = cameraIds.get(0);
            cameraRetryCount = 0;
            safeCameraMode = "0".equals(selectedCameraId);
            statusText.setText("Выберите камеру из списка");
            if (textureView.isAvailable()) openSelectedCamera();
        } catch (CameraAccessException e) {
            showError("Ошибка списка камер: " + e.getMessage());
        }
    }

    private void openSelectedCamera() {
        if (!hasCameraPermission() ||
                selectedCameraId == null ||
                !textureView.isAvailable()) {
            return;
        }

        final String openingCameraId = selectedCameraId;

        prepareEmergencyPreviewFrame(openingCameraId);

        try {
            CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(openingCameraId);

            StreamConfigurationMap map =
                    characteristics.get(
                            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (map == null) {
                showError("Камера " + openingCameraId +
                        " не сообщает поддерживаемые размеры");
                return;
            }

            previewSize = choosePreviewSize(
                    map.getOutputSizes(SurfaceTexture.class),
                    safeCameraMode);

            photoSize = choosePhotoSize(
                    map.getOutputSizes(ImageFormat.JPEG));

            videoSize = chooseVideoSize(
                    map.getOutputSizes(MediaRecorder.class));

            if (previewSize == null ||
                    photoSize == null ||
                    videoSize == null) {

                showError("Не удалось подобрать режим камеры " +
                        openingCameraId);
                return;
            }

            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }

            statusText.setText(
                    "Открываю ID " + openingCameraId +
                    (safeCameraMode ? " [SAFE]…" : "…"));

            cameraManager.openCamera(
                    openingCameraId,
                    new CameraDevice.StateCallback() {

                        @Override
                        public void onOpened(CameraDevice camera) {
                            if (!openingCameraId.equals(selectedCameraId)) {
                                camera.close();
                                return;
                            }

                            cameraDevice = camera;
                            createPreviewSession();
                        }

                        @Override
                        public void onDisconnected(CameraDevice camera) {
                            saveEmergencyPreviewIfAvailable(
                                    "Камера " + openingCameraId +
                                    " отключилась");

                            camera.close();

                            if (cameraDevice == camera) {
                                cameraDevice = null;
                            }

                            scheduleSafeCameraRetry(
                                    openingCameraId,
                                    "камера отключилась");
                        }

                        @Override
                        public void onError(CameraDevice camera, int error) {
                            String errorText = cameraErrorText(error);

                            saveEmergencyPreviewIfAvailable(
                                    "ID " + openingCameraId +
                                    ": " + errorText);

                            camera.close();

                            if (cameraDevice == camera) {
                                cameraDevice = null;
                            }

                            if (!scheduleSafeCameraRetry(
                                    openingCameraId,
                                    errorText)) {

                                runOnUiThread(() ->
                                        showError(
                                                "ID " +
                                                openingCameraId +
                                                ": " +
                                                errorText));
                            }
                        }
                    },
                    backgroundHandler);

        } catch (Exception e) {
            saveEmergencyPreviewIfAvailable(
                    "Ошибка открытия ID " + openingCameraId);

            showError(
                    "Не удалось открыть ID " +
                    openingCameraId +
                    ": " +
                    e.getClass().getSimpleName() +
                    ": " +
                    e.getMessage());
        }
    }

    private void createPreviewSession() {
        if (cameraDevice == null ||
                selectedCameraId == null ||
                !textureView.isAvailable()) {
            return;
        }

        final String sessionCameraId = selectedCameraId;

        try {
            final CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(sessionCameraId);

            SurfaceTexture texture = textureView.getSurfaceTexture();

            if (texture == null) return;

            texture.setDefaultBufferSize(
                    previewSize.getWidth(),
                    previewSize.getHeight());

            final Surface previewSurface = new Surface(texture);

            previewBuilder =
                    cameraDevice.createCaptureRequest(
                            CameraDevice.TEMPLATE_PREVIEW);

            previewBuilder.addTarget(previewSurface);

            final int afMode =
                    chooseAfMode(characteristics, false);

            previewBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    afMode);

            previewBuilder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_IDLE);

            applyOneXZoom(
                    previewBuilder,
                    characteristics);

            cameraDevice.createCaptureSession(
                    Collections.singletonList(previewSurface),

                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(
                                CameraCaptureSession session) {

                            if (cameraDevice == null ||
                                    !sessionCameraId.equals(
                                            selectedCameraId)) {

                                session.close();
                                return;
                            }

                            captureSession = session;

                            try {
                                session.setRepeatingRequest(
                                        previewBuilder.build(),
                                        null,
                                        backgroundHandler);

                                // Для safe-mode запускаем автофокус сразу,
                                // вместо ожидания обычного CONTINUOUS_AF.
                                if (afMode ==
                                        CaptureRequest.CONTROL_AF_MODE_AUTO) {

                                    CaptureRequest.Builder focus =
                                            cameraDevice.createCaptureRequest(
                                                    CameraDevice.TEMPLATE_PREVIEW);

                                    focus.addTarget(previewSurface);

                                    focus.set(
                                            CaptureRequest.CONTROL_AF_MODE,
                                            CaptureRequest.CONTROL_AF_MODE_AUTO);

                                    focus.set(
                                            CaptureRequest.CONTROL_AF_TRIGGER,
                                            CaptureRequest.CONTROL_AF_TRIGGER_START);

                                    applyOneXZoom(
                                            focus,
                                            characteristics);

                                    session.capture(
                                            focus.build(),
                                            new CameraCaptureSession.CaptureCallback() {},
                                            backgroundHandler);
                                }

                                runOnUiThread(() -> {
                                    statusText.setText(
                                            "Камера ID " +
                                            sessionCameraId +
                                            " готова" +
                                            (safeCameraMode
                                                    ? " [SAFE]"
                                                    : ""));

                                    photoButton.setEnabled(true);
                                    videoButton.setEnabled(true);

                                    // Первый кадр уже сохранён как страховка.
                                    // Если камера проживёт ещё немного —
                                    // заменяем его более резким.
                                    textureView.postDelayed(
                                            thisActivity()::refreshEmergencyPreviewFrame,
                                            350);

                                    textureView.postDelayed(
                                            thisActivity()::refreshEmergencyPreviewFrame,
                                            800);
                                });

                            } catch (Exception e) {
                                saveEmergencyPreviewIfAvailable(
                                        "Ошибка preview ID " +
                                        sessionCameraId);

                                runOnUiThread(() ->
                                        showError(
                                                "Preview ID " +
                                                sessionCameraId +
                                                ": " +
                                                e.getClass().getSimpleName() +
                                                ": " +
                                                e.getMessage()));
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                CameraCaptureSession session) {

                            saveEmergencyPreviewIfAvailable(
                                    "Сбой preview ID " +
                                    sessionCameraId);

                            runOnUiThread(() ->
                                    showError(
                                            "Не удалось запустить preview ID " +
                                            sessionCameraId));
                        }
                    },
                    backgroundHandler);

        } catch (Exception e) {
            saveEmergencyPreviewIfAvailable(
                    "Ошибка preview ID " +
                    sessionCameraId);

            showError(
                    "Preview ID " +
                    sessionCameraId +
                    ": " +
                    e.getClass().getSimpleName() +
                    ": " +
                    e.getMessage());
        }
    }

    private MainActivity thisActivity() {
        return this;
    }

    private int chooseAfMode(
            CameraCharacteristics characteristics,
            boolean video) {

        int[] modes =
                characteristics.get(
                        CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);

        if (modes == null || modes.length == 0) {
            return CaptureRequest.CONTROL_AF_MODE_OFF;
        }

        if (safeCameraMode &&
                containsAfMode(
                        modes,
                        CaptureRequest.CONTROL_AF_MODE_AUTO)) {

            return CaptureRequest.CONTROL_AF_MODE_AUTO;
        }

        if (video &&
                containsAfMode(
                        modes,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {

            return CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
        }

        if (containsAfMode(
                modes,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {

            return CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        }

        if (containsAfMode(
                modes,
                CaptureRequest.CONTROL_AF_MODE_AUTO)) {

            return CaptureRequest.CONTROL_AF_MODE_AUTO;
        }

        return modes[0];
    }

    private boolean containsAfMode(
            int[] modes,
            int wanted) {

        for (int mode : modes) {
            if (mode == wanted) return true;
        }

        return false;
    }

    private void applyOneXZoom(
            CaptureRequest.Builder builder,
            CameraCharacteristics characteristics) {

        if (Build.VERSION.SDK_INT < 30) return;

        try {
            android.util.Range<Float> range =
                    characteristics.get(
                            CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);

            if (range != null &&
                    range.contains(1.0f)) {

                builder.set(
                        CaptureRequest.CONTROL_ZOOM_RATIO,
                        1.0f);
            }
        } catch (Exception ignored) {
        }
    }

    private String cameraErrorText(int error) {
        switch (error) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                return "ERROR_CAMERA_IN_USE (1)";

            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                return "ERROR_MAX_CAMERAS_IN_USE (2)";

            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                return "ERROR_CAMERA_DISABLED (3)";

            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                return "ERROR_CAMERA_DEVICE (4)";

            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                return "ERROR_CAMERA_SERVICE (5)";

            default:
                return "UNKNOWN_CAMERA_ERROR (" + error + ")";
        }
    }

    private boolean scheduleSafeCameraRetry(
            String cameraId,
            String reason) {

        if (!cameraId.equals(selectedCameraId)) {
            return false;
        }

        if (cameraRetryCount >= 1) {
            return false;
        }

        cameraRetryCount++;
        safeCameraMode = true;

        runOnUiThread(() ->
                statusText.setText(
                        "ID " +
                        cameraId +
                        ": " +
                        reason +
                        ". Повтор SAFE…"));

        Handler h = backgroundHandler;

        if (h == null) {
            return false;
        }

        h.postDelayed(() ->
                runOnUiThread(() -> {
                    if (cameraId.equals(selectedCameraId) &&
                            textureView.isAvailable() &&
                            cameraDevice == null) {

                        openSelectedCamera();
                    }
                }),
                700);

        return true;
    }

    private void takePhoto() {
        if (cameraDevice == null || isRecording || photoSize == null) return;

        photoButton.setEnabled(false);
        videoButton.setEnabled(false);
        statusText.setText("Снимаю фото…");

        closeCaptureSession();

        try {
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }

            imageReader = ImageReader.newInstance(
                    photoSize.getWidth(),
                    photoSize.getHeight(),
                    ImageFormat.JPEG,
                    2);

            final ImageReader reader = imageReader;
            reader.setOnImageAvailableListener(this::saveNextImage, backgroundHandler);

            cameraDevice.createCaptureSession(
                    Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null || imageReader != reader) {
                                session.close();
                                return;
                            }

                            captureSession = session;

                            try {
                                CaptureRequest.Builder still =
                                        cameraDevice.createCaptureRequest(
                                                CameraDevice.TEMPLATE_STILL_CAPTURE);

                                still.addTarget(reader.getSurface());
                                still.set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                still.set(
                                        CaptureRequest.JPEG_ORIENTATION,
                                        getJpegOrientation());

                                session.capture(
                                        still.build(),
                                        new CameraCaptureSession.CaptureCallback() {},
                                        backgroundHandler);

                            } catch (Exception e) {
                                saveEmergencyPreviewIfAvailable("Ошибка фото");
                                runOnUiThread(() ->
                                        showError("Ошибка фото: " + e.getMessage()));
                                restorePreviewAfterPhoto();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            session.close();
                            saveEmergencyPreviewIfAvailable("Сбой режима фото");
                            runOnUiThread(() ->
                                    showError("Камера не смогла включить режим фото"));
                            restorePreviewAfterPhoto();
                        }
                    },
                    backgroundHandler);

        } catch (Exception e) {
            saveEmergencyPreviewIfAvailable("Ошибка фото");
            showError("Ошибка фото: " + e.getMessage());
            restorePreviewAfterPhoto();
        }
    }

    private void saveNextImage(ImageReader reader) {
        Image image = null;

        try {
            image = reader.acquireNextImage();
            if (image == null) return;

            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            String name = "IMG_" + timestamp() + ".jpg";
            Uri uri = saveBytesToGallery(bytes, name, "image/jpeg", true);

            runOnUiThread(() -> {
                statusText.setText(
                        uri != null
                                ? "Фото сохранено: " + name
                                : "Не удалось сохранить фото");

                if (uri != null) {
                    Toast.makeText(
                            this,
                            "Фото сохранено",
                            Toast.LENGTH_SHORT
                    ).show();
                }
            });

        } catch (Exception e) {
            runOnUiThread(() ->
                    showError("Ошибка сохранения фото: " + e.getMessage()));

        } finally {
            if (image != null) image.close();
            restorePreviewAfterPhoto();
        }
    }

    private void restorePreviewAfterPhoto() {
        closeCaptureSession();

        if (imageReader != null) {
            try {
                imageReader.close();
            } catch (Exception ignored) {
            }
            imageReader = null;
        }

        runOnUiThread(() -> {
            photoButton.setEnabled(false);
            videoButton.setEnabled(false);

            if (cameraDevice != null && textureView.isAvailable()) {
                prepareEmergencyPreviewFrame(selectedCameraId);
                createPreviewSession();
            }
        });
    }

    private void prepareEmergencyPreviewFrame(String cameraId) {
        synchronized (emergencyFrameLock) {
            if (emergencyPreviewBitmap != null) {
                try {
                    emergencyPreviewBitmap.recycle();
                } catch (Exception ignored) {
                }
            }

            emergencyPreviewBitmap = null;
            emergencyPreviewCameraId = cameraId;
            emergencyPreviewSaved = false;
            needEmergencyPreviewFrame = true;
        }
    }

    private void captureEmergencyPreviewFrame() {
        synchronized (emergencyFrameLock) {
            if (!needEmergencyPreviewFrame ||
                    emergencyPreviewSaved ||
                    emergencyPreviewBitmap != null) {
                return;
            }
        }

        if (textureView == null || !textureView.isAvailable()) return;

        Bitmap bitmap;

        try {
            bitmap = textureView.getBitmap();
        } catch (Exception e) {
            return;
        }

        if (bitmap == null ||
                bitmap.getWidth() <= 0 ||
                bitmap.getHeight() <= 0) {
            if (bitmap != null) bitmap.recycle();
            return;
        }

        synchronized (emergencyFrameLock) {
            if (!needEmergencyPreviewFrame ||
                    emergencyPreviewSaved ||
                    emergencyPreviewBitmap != null) {

                bitmap.recycle();
                return;
            }

            emergencyPreviewBitmap = bitmap;
            needEmergencyPreviewFrame = false;
        }
    }

    private void refreshEmergencyPreviewFrame() {
        if (textureView == null ||
                !textureView.isAvailable()) {
            return;
        }

        synchronized (emergencyFrameLock) {
            if (emergencyPreviewSaved) return;
        }

        Bitmap fresh;

        try {
            fresh = textureView.getBitmap();
        } catch (Exception e) {
            return;
        }

        if (fresh == null ||
                fresh.getWidth() <= 0 ||
                fresh.getHeight() <= 0) {

            if (fresh != null) {
                fresh.recycle();
            }

            return;
        }

        synchronized (emergencyFrameLock) {
            if (emergencyPreviewSaved) {
                fresh.recycle();
                return;
            }

            Bitmap old = emergencyPreviewBitmap;
            emergencyPreviewBitmap = fresh;
            needEmergencyPreviewFrame = false;

            if (old != null && old != fresh) {
                try {
                    old.recycle();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void saveEmergencyPreviewIfAvailable(String reason) {
        final Bitmap bitmap;
        final String cameraId;

        synchronized (emergencyFrameLock) {
            if (emergencyPreviewSaved || emergencyPreviewBitmap == null) {
                // Если кадр уже был показан, но callback ошибки пришёл почти одновременно,
                // пробуем забрать TextureView ещё один раз с UI-потока.
                if (!emergencyPreviewSaved &&
                        textureView != null &&
                        textureView.isAvailable()) {

                    runOnUiThread(() -> {
                        captureEmergencyPreviewFrame();
                        saveEmergencyPreviewNow(reason);
                    });
                }
                return;
            }
        }

        saveEmergencyPreviewNow(reason);
    }

    private void saveEmergencyPreviewNow(String reason) {
        final Bitmap bitmap;
        final String cameraId;

        synchronized (emergencyFrameLock) {
            if (emergencyPreviewSaved || emergencyPreviewBitmap == null) return;

            emergencyPreviewSaved = true;
            bitmap = emergencyPreviewBitmap;
            emergencyPreviewBitmap = null;
            needEmergencyPreviewFrame = false;
            cameraId = emergencyPreviewCameraId;
        }

        new Thread(() -> {
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, bos);

                String safeCameraId = cameraId == null
                        ? "unknown"
                        : cameraId.replaceAll("[^A-Za-z0-9_-]", "_");

                String name =
                        "RECOVERED_CAM_" +
                        safeCameraId +
                        "_" +
                        timestamp() +
                        ".jpg";

                Uri uri = saveBytesToGallery(
                        bos.toByteArray(),
                        name,
                        "image/jpeg",
                        true
                );

                runOnUiThread(() -> {
                    if (uri != null) {
                        statusText.setText(
                                reason + ". Показанный кадр сохранён: " + name);

                        Toast.makeText(
                                this,
                                "Кадр камеры сохранён",
                                Toast.LENGTH_LONG
                        ).show();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(
                                this,
                                "Не удалось сохранить аварийный кадр: " +
                                        e.getMessage(),
                                Toast.LENGTH_LONG
                        ).show());

            } finally {
                try {
                    bitmap.recycle();
                } catch (Exception ignored) {
                }
            }
        }, "EmergencyCameraSave").start();
    }

    private void clearEmergencyPreviewFrame() {
        synchronized (emergencyFrameLock) {
            if (emergencyPreviewBitmap != null) {
                try {
                    emergencyPreviewBitmap.recycle();
                } catch (Exception ignored) {
                }
            }

            emergencyPreviewBitmap = null;
            emergencyPreviewCameraId = null;
            emergencyPreviewSaved = false;
            needEmergencyPreviewFrame = false;
        }
    }

    private Uri saveBytesToGallery(byte[] bytes, String name, String mime, boolean photo) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/CameraSelector");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection = photo ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            Uri uri = getContentResolver().insert(collection, values);
            if (uri == null) return null;
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) return null;
                os.write(bytes);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
            return uri;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "CameraSelector");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File file = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(bytes);
            }
            MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()}, new String[]{mime}, null);
            return Uri.fromFile(file);
        }
    }

    private void startVideoRecording() {
        if (cameraDevice == null || !textureView.isAvailable() || isRecording) return;
        if (!hasAudioPermission()) {
            Toast.makeText(this, "Для видео со звуком разрешите микрофон", Toast.LENGTH_LONG).show();
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERMISSIONS);
            return;
        }

        try {
            closeCaptureSession();
            prepareMediaRecorder();

            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null) return;
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(texture);
            Surface recorderSurface = mediaRecorder.getSurface();

            final CaptureRequest.Builder recordBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            recordBuilder.addTarget(previewSurface);
            recordBuilder.addTarget(recorderSurface);
            recordBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);

            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, recorderSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(recordBuilder.build(), null, backgroundHandler);
                                mediaRecorder.start();
                                isRecording = true;
                                runOnUiThread(() -> {
                                    cameraSpinner.setEnabled(false);
                                    photoButton.setEnabled(false);
                                    videoButton.setText("■ СТОП");
                                    statusText.setText("Идёт запись видео…");
                                });
                            } catch (Exception e) {
                                runOnUiThread(() -> showError("Не удалось начать запись: " + e.getMessage()));
                                abortVideoOutput();
                                createPreviewSession();
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession session) {
                            runOnUiThread(() -> showError("Камера не смогла включить режим видео"));
                            abortVideoOutput();
                            createPreviewSession();
                        }
                    }, backgroundHandler);
        } catch (Exception e) {
            showError("Ошибка запуска видео: " + e.getMessage());
            abortVideoOutput();
            createPreviewSession();
        }
    }

    private void prepareMediaRecorder() throws IOException, CameraAccessException {
        if (mediaRecorder != null) {
            try { mediaRecorder.release(); } catch (Exception ignored) {}
        }
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setVideoEncodingBitRate(8_000_000);
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
        mediaRecorder.setAudioEncodingBitRate(128_000);
        mediaRecorder.setAudioSamplingRate(44_100);
        mediaRecorder.setOrientationHint(getVideoOrientationHint());

        String name = "VID_" + timestamp() + ".mp4";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/CameraSelector");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            pendingVideoUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (pendingVideoUri == null) throw new IOException("MediaStore не создал файл");
            pendingVideoPfd = getContentResolver().openFileDescriptor(pendingVideoUri, "w");
            if (pendingVideoPfd == null) throw new IOException("Не удалось открыть файл видео");
            mediaRecorder.setOutputFile(pendingVideoPfd.getFileDescriptor());
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "CameraSelector");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Не удалось создать папку DCIM/CameraSelector");
            legacyVideoFile = new File(dir, name);
            mediaRecorder.setOutputFile(legacyVideoFile.getAbsolutePath());
        }
        mediaRecorder.prepare();
    }

    private void stopVideoRecording() {
        if (!isRecording) return;
        isRecording = false;
        try {
            if (captureSession != null) {
                try { captureSession.stopRepeating(); } catch (Exception ignored) {}
                try { captureSession.abortCaptures(); } catch (Exception ignored) {}
            }
            mediaRecorder.stop();
            mediaRecorder.reset();
            finishVideoOutput();
            Toast.makeText(this, "Видео сохранено", Toast.LENGTH_SHORT).show();
            statusText.setText("Видео сохранено");
        } catch (RuntimeException e) {
            abortVideoOutput();
            showError("Запись слишком короткая или произошла ошибка");
        } finally {
            cameraSpinner.setEnabled(true);
            photoButton.setEnabled(true);
            videoButton.setText("● ВИДЕО");
            createPreviewSession();
        }
    }

    private void finishVideoOutput() {
        try {
            if (pendingVideoPfd != null) pendingVideoPfd.close();
        } catch (IOException ignored) {}
        pendingVideoPfd = null;

        if (Build.VERSION.SDK_INT >= 29 && pendingVideoUri != null) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(pendingVideoUri, done, null, null);
        } else if (legacyVideoFile != null) {
            MediaScannerConnection.scanFile(this,
                    new String[]{legacyVideoFile.getAbsolutePath()}, new String[]{"video/mp4"}, null);
        }
        pendingVideoUri = null;
        legacyVideoFile = null;
    }

    private void abortVideoOutput() {
        try {
            if (mediaRecorder != null) mediaRecorder.reset();
        } catch (Exception ignored) {}
        try {
            if (pendingVideoPfd != null) pendingVideoPfd.close();
        } catch (IOException ignored) {}
        pendingVideoPfd = null;
        if (Build.VERSION.SDK_INT >= 29 && pendingVideoUri != null) {
            getContentResolver().delete(pendingVideoUri, null, null);
        } else if (legacyVideoFile != null && legacyVideoFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            legacyVideoFile.delete();
        }
        pendingVideoUri = null;
        legacyVideoFile = null;
        isRecording = false;
        runOnUiThread(() -> {
            cameraSpinner.setEnabled(true);
            photoButton.setEnabled(true);
            videoButton.setText("● ВИДЕО");
        });
    }

    private int getJpegOrientation() throws CameraAccessException {
        CameraCharacteristics c = cameraManager.getCameraCharacteristics(selectedCameraId);
        Integer sensor = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        int device = rotationToDegrees(getWindowManager().getDefaultDisplay().getRotation());
        int sensorDeg = sensor == null ? 0 : sensor;
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
            return (sensorDeg - device + 360) % 360;
        }
        return (sensorDeg + device) % 360;
    }

    private int getVideoOrientationHint() throws CameraAccessException {
        CameraCharacteristics c = cameraManager.getCameraCharacteristics(selectedCameraId);
        Integer sensor = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        int device = rotationToDegrees(getWindowManager().getDefaultDisplay().getRotation());
        int sensorDeg = sensor == null ? 0 : sensor;
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
            return (sensorDeg + device) % 360;
        }
        return (sensorDeg - device + 360) % 360;
    }

    private int rotationToDegrees(int rotation) {
        if (rotation == Surface.ROTATION_90) return 90;
        if (rotation == Surface.ROTATION_180) return 180;
        if (rotation == Surface.ROTATION_270) return 270;
        return 0;
    }

    private Size choosePreviewSize(
            Size[] sizes,
            boolean safeMode) {

        if (sizes == null || sizes.length == 0) {
            return null;
        }

        if (safeMode) {
            Size exact = findSize(sizes, 1280, 720);
            if (exact != null) return exact;

            exact = findSize(sizes, 960, 720);
            if (exact != null) return exact;

            exact = findSize(sizes, 640, 480);
            if (exact != null) return exact;
        }

        List<Size> list =
                new ArrayList<>(Arrays.asList(sizes));

        list.sort(
                Comparator.comparingLong(
                        x -> (long) x.getWidth() *
                                x.getHeight()));

        Size fallback = list.get(list.size() - 1);

        for (Size size : list) {
            if (size.getWidth() >= 1280 &&
                    size.getHeight() >= 720 &&
                    size.getWidth() <= 1920 &&
                    size.getHeight() <= 1080) {

                return size;
            }
        }

        for (Size size : list) {
            if (size.getWidth() <= 1920 &&
                    size.getHeight() <= 1080) {

                fallback = size;
            }
        }

        return fallback;
    }

    private Size findSize(
            Size[] sizes,
            int width,
            int height) {

        for (Size size : sizes) {
            if (size.getWidth() == width &&
                    size.getHeight() == height) {

                return size;
            }
        }

        return null;
    }

    private Size choosePhotoSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) {
            return null;
        }

        Size best = null;

        // Не лезем сразу в 50/108 МП режимы Samsung.
        // Для диагностической камеры выбираем максимум до ~12 МП.
        final long maxPixels = 12_500_000L;

        for (Size size : sizes) {
            long pixels =
                    (long) size.getWidth() *
                    size.getHeight();

            if (pixels <= maxPixels) {
                if (best == null ||
                        pixels >
                        (long) best.getWidth() *
                        best.getHeight()) {

                    best = size;
                }
            }
        }

        if (best != null) return best;

        return Collections.min(
                Arrays.asList(sizes),
                Comparator.comparingLong(
                        x -> (long) x.getWidth() *
                                x.getHeight()));
    }

    private Size chooseLargest(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        return Collections.max(Arrays.asList(sizes), Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()));
    }

    private Size chooseVideoSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = null;
        for (Size s : sizes) {
            int w = s.getWidth();
            int h = s.getHeight();
            if (w <= 1920 && h <= 1080) {
                if (best == null || (long) w * h > (long) best.getWidth() * best.getHeight()) best = s;
            }
        }
        if (best != null) return best;
        return sizes[0];
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
    }

    private void closeCaptureSession() {
        if (captureSession != null) {
            try { captureSession.close(); } catch (Exception ignored) {}
            captureSession = null;
        }
    }

    private void closeCamera() {
        if (isRecording) stopVideoRecording();
        closeCaptureSession();
        clearEmergencyPreviewFrame();
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        photoButton.setEnabled(false);
        videoButton.setEnabled(false);
    }

    private void showError(String message) {
        statusText.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void startBackgroundThread() {
        if (backgroundThread != null) return;
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread == null) return;
        backgroundThread.quitSafely();
        try { backgroundThread.join(); } catch (InterruptedException ignored) {}
        backgroundThread = null;
        backgroundHandler = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (hasCameraPermission() && cameraIds.isEmpty()) initializeCameras();
        else if (hasCameraPermission() && textureView.isAvailable() && selectedCameraId != null && cameraDevice == null) openSelectedCamera();
    }

    @Override
    protected void onPause() {
        if (isRecording) stopVideoRecording();
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mediaRecorder != null) {
            try { mediaRecorder.release(); } catch (Exception ignored) {}
            mediaRecorder = null;
        }
        super.onDestroy();
    }
}
