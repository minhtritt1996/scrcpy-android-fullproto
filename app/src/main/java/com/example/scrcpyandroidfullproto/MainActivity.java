package com.example.scrcpyandroidfullproto;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.scrcpy.adb.NativeAdbBridge;
import com.example.scrcpyandroidfullproto.view.AspectRatioSurfaceView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.tabs.TabLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public class MainActivity extends AppCompatActivity {
    private EditText deviceIpInput;
    private EditText devicePortInput;
    private EditText pairPortInput;
    private EditText pairCodeInput;
    private EditText localForwardPortInput;
    private TextView statusText;
    private AspectRatioSurfaceView videoSurface;
    private View statusPanel;
    private Button resolutionButton;
    private Button fpsButton;
    private SwitchMaterial autoClipboardSwitch;
    private Button disconnectStreamButton;
    private ImageButton navBackButton;
    private ImageButton navHomeButton;
    private ImageButton navRecentButton;
    private Button stretchToggleButton;
    private TabLayout controlTabs;
    private LinearLayout connectionPanel;
    private LinearLayout streamPanel;

    private FloatingActionButton exitButton;
    private ImageButton toggleControlsButton;
    private View controlOverlay;
    private LinearLayout navPill;
    private ScrcpyVideoClient videoClient;
    private ScrcpyControlClient controlClient;
    private volatile NativeAdbBridge adbBridge;
    private volatile int remoteWidth;
    private volatile int remoteHeight;
    private volatile int currentLocalForwardPort;
    private volatile boolean streamReady;
    private volatile int selectedMaxSize;
    private volatile int selectedMaxFps;
    private boolean stretchToFit;
    private final int[] sizeOptions = {0, 426, 640, 854, 1280, 1920};
    private final String[] sizeLabels = {
            "Auto",
            "240p",
            "360p",
            "480p",
            "720p",
            "1080p"
    };
    private final int[] fpsOptions = {0, 30, 45, 60};
    private final String[] fpsLabels = {"Auto", "30 fps", "45 fps", "60 fps"};
    private boolean controlsVisible = false;
    private boolean sessionConnected = false;
    private ClipboardManager clipboardManager;
    private boolean autoClipboardSyncEnabled = true;
    private boolean applyingRemoteClipboard;
    private long applyingRemoteClipboardUntilMs;
    private long lastClipboardSendMs;
    private String lastClipboardSentToDevice = "";
    private String lastClipboardReceivedFromDevice = "";
    private static final long REMOTE_CLIPBOARD_GUARD_MS = 800;
    private static final long CLIPBOARD_SEND_DEBOUNCE_MS = 200;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable clearRemoteClipboardGuard = () -> applyingRemoteClipboard = false;
    private final ClipboardManager.OnPrimaryClipChangedListener hostClipboardListener = this::onLocalClipboardChanged;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        deviceIpInput = findViewById(R.id.deviceIpInput);
        devicePortInput = findViewById(R.id.devicePortInput);
        pairPortInput = findViewById(R.id.pairPortInput);
        pairCodeInput = findViewById(R.id.pairCodeInput);
        localForwardPortInput = findViewById(R.id.localForwardPortInput);
        statusPanel = findViewById(R.id.statusPanel);
        statusText = findViewById(R.id.statusText);
        Button connectButton = findViewById(R.id.connectButton);
        Button disconnectButton = findViewById(R.id.disconnectButton);
        videoSurface = findViewById(R.id.videoSurface);
        resolutionButton = findViewById(R.id.resolutionButton);
        fpsButton = findViewById(R.id.fpsButton);
        autoClipboardSwitch = findViewById(R.id.autoClipboardSwitch);
        disconnectStreamButton = findViewById(R.id.disconnectStreamButton);
        navBackButton = findViewById(R.id.navBackButton);
        navHomeButton = findViewById(R.id.navHomeButton);
        navRecentButton = findViewById(R.id.navRecentsButton);
        stretchToggleButton = findViewById(R.id.stretchToggleButton);
        controlTabs = findViewById(R.id.controlTabs);
        connectionPanel = findViewById(R.id.connectionPanel);
        streamPanel = findViewById(R.id.streamPanel);
        exitButton = findViewById(R.id.exitButton);
        toggleControlsButton = findViewById(R.id.toggleControlsButton);
        controlOverlay = findViewById(R.id.controlOverlay);
        navPill = findViewById(R.id.navPill);
        videoSurface.setOnTouchListener(this::handleTouch);
        clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboardManager != null) {
            clipboardManager.addPrimaryClipChangedListener(hostClipboardListener);
        }
        autoClipboardSyncEnabled = autoClipboardSwitch.isChecked();
        autoClipboardSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            autoClipboardSyncEnabled = isChecked;
            if (isChecked) {
                requestRemoteClipboardSnapshot();
                statusText.setText("Auto clipboard sync enabled");
            } else {
                statusText.setText("Auto clipboard sync disabled");
            }
        });

        setupTabs();
        updatePanelsForSession(false);
        updateStretchButtonText();

        videoClient = new ScrcpyVideoClient(new ScrcpyVideoClient.Listener() {
            @Override
            public void onStatus(String text) {
                runOnUiThread(() -> {
                    statusText.setText(text);
                    if (text != null && text.contains("Decoder configured")) {
                        streamReady = true;
                        if (sessionConnected) {
                            updatePanelsForSession(true);
                        }
                    }
                });
            }

            @Override
            public void onError(String text, Throwable throwable) {
                handleStreamFailure(text);
            }

            @Override
            public void onResolutionChanged(int width, int height) {
                remoteWidth = width;
                remoteHeight = height;
                streamReady = true;
                runOnUiThread(() -> {
                    videoSurface.setAspectRatio(width, height);
                    if (sessionConnected) {
                        updatePanelsForSession(true);
                    }
                });
            }

            @Override
            public void onVideoSocketConnected() {
                int forwardPort = currentLocalForwardPort;
                if (forwardPort <= 0) {
                    return;
                }
                // Keep control socket aligned with the current video socket attempt.
                controlClient.stop();
                controlClient.start("127.0.0.1", forwardPort);
            }
        });

        controlClient = new ScrcpyControlClient(new ScrcpyControlClient.Listener() {
            @Override
            public void onStatus(String text) {
                if ("Control channel connected".equals(text) && autoClipboardSyncEnabled) {
                    requestRemoteClipboardSnapshot();
                }
                if (!sessionConnected) {
                    runOnUiThread(() -> statusText.setText(text));
                }
            }

            @Override
            public void onError(String text, Throwable throwable) {
                runOnUiThread(() -> statusText.setText(text));
            }

            @Override
            public void onClipboardText(String text) {
                runOnUiThread(() -> {
                    applyRemoteClipboardText(text);
                });
            }
        });

        connectButton.setOnClickListener(v -> connectAndStart());
        disconnectButton.setOnClickListener(v -> disconnectSession());
        resolutionButton.setOnClickListener(v -> showResolutionDialog());
        fpsButton.setOnClickListener(v -> showFpsDialog());
        disconnectStreamButton.setOnClickListener(v -> disconnectSession());
        navBackButton.setOnClickListener(v -> sendKeyEvent(KeyEvent.KEYCODE_BACK));
        navHomeButton.setOnClickListener(v -> sendKeyEvent(KeyEvent.KEYCODE_HOME));
        navRecentButton.setOnClickListener(v -> sendKeyEvent(KeyEvent.KEYCODE_APP_SWITCH));
        stretchToggleButton.setOnClickListener(v -> toggleStretchToFit());
        exitButton.setOnClickListener(v -> showDisconnectDialog());
        toggleControlsButton.setOnClickListener(v -> toggleControlsVisibility());
        setupMovableControls();
        setControlsVisible(true);
    }

    private void setupMovableControls() {
        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        setupDraggableNavButton(toggleControlsButton, touchSlop);
        setupDraggableNavButton(navBackButton, touchSlop);
        setupDraggableNavButton(navHomeButton, touchSlop);
        setupDraggableNavButton(navRecentButton, touchSlop);
    }

    private void setupDraggableNavButton(ImageButton button, int touchSlop) {
        button.setOnTouchListener(new View.OnTouchListener() {
            float downRawX;
            float downRawY;
            float startPillX;
            float startPillY;
            boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startPillX = navPill.getX();
                        startPillY = navPill.getY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                            dragging = true;
                        }
                        if (dragging) {
                            setClampedPosition(navPill, startPillX + dx, startPillY + dy);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragging) {
                            v.performClick();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void setClampedPosition(View target, float desiredX, float desiredY) {
        View parent = (View) target.getParent();
        if (parent == null) {
            return;
        }
        float maxX = Math.max(0f, parent.getWidth() - target.getWidth());
        float maxY = Math.max(0f, parent.getHeight() - target.getHeight());
        float clampedX = Math.max(0f, Math.min(desiredX, maxX));
        float clampedY = Math.max(0f, Math.min(desiredY, maxY));
        target.setX(clampedX);
        target.setY(clampedY);
    }

    private void setupTabs() {
        controlTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (sessionConnected) {
                    return;
                }
                if (tab.getPosition() == 0) {
                    connectionPanel.setVisibility(View.VISIBLE);
                    streamPanel.setVisibility(View.GONE);
                } else {
                    connectionPanel.setVisibility(View.GONE);
                    streamPanel.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
        TabLayout.Tab defaultTab = controlTabs.getTabAt(0);
        if (defaultTab != null) {
            defaultTab.select();
        }
    }

    private void updatePanelsForSession(boolean connected) {
        sessionConnected = connected;
        if (connected) {
            if (statusPanel != null) {
                statusPanel.setVisibility(streamReady ? View.GONE : View.VISIBLE);
            }
            controlTabs.setVisibility(View.GONE);
            connectionPanel.setVisibility(View.GONE);
            streamPanel.setVisibility(View.VISIBLE);
            if (navPill != null) {
                navPill.setVisibility(View.VISIBLE);
            }
            if (exitButton != null) {
                exitButton.setVisibility(View.GONE);
            }
            return;
        }
        if (statusPanel != null) {
            statusPanel.setVisibility(View.VISIBLE);
        }
        controlTabs.setVisibility(View.VISIBLE);
        connectionPanel.setVisibility(View.VISIBLE);
        streamPanel.setVisibility(View.GONE);
        TabLayout.Tab defaultTab = controlTabs.getTabAt(0);
        if (defaultTab != null) {
            defaultTab.select();
        }
    }

    private void disconnectSession() {
        videoClient.stop();
        controlClient.stop();
        currentLocalForwardPort = 0;
        streamReady = false;
        statusText.setText("Disconnected");
        showSystemUI();
        adbBridge = null;
        updatePanelsForSession(false);
        setControlsVisible(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (clipboardManager != null) {
            clipboardManager.removePrimaryClipChangedListener(hostClipboardListener);
        }
        mainHandler.removeCallbacks(clearRemoteClipboardGuard);
        videoClient.stop();
        controlClient.stop();
    }

    private void connectAndStart() {
        String deviceIp = deviceIpInput.getText().toString().trim();
        if (deviceIp.isEmpty()) {
            statusText.setText("Please enter Wi-Fi Debug device IP");
            return;
        }

        int devicePort = parsePort(devicePortInput.getText().toString().trim(), 5555, "Wi-Fi Debug Port");
        if (devicePort == -1) {
            return;
        }
        String pairPortRaw = pairPortInput.getText().toString().trim();
        String pairCode = pairCodeInput.getText().toString().trim();
        int parsedPairPort = -1;
        if (!pairPortRaw.isEmpty() || !pairCode.isEmpty()) {
            parsedPairPort = parsePort(pairPortRaw, -1, "Pairing Port");
            if (parsedPairPort == -1) {
                return;
            }
            if (pairCode.isEmpty()) {
                statusText.setText("Pairing Code is required when Pairing Port is set");
                return;
            }
        }
        final int pairPort = parsedPairPort;
        int localForwardPort = parsePort(localForwardPortInput.getText().toString().trim(), 7008, "Local Forward Port");
        if (localForwardPort == -1) {
            return;
        }
        streamReady = false;
        currentLocalForwardPort = localForwardPort;
        controlClient.stop();

        statusText.setText("Connecting Wi-Fi debug " + deviceIp + ":" + devicePort);

        new Thread(() -> {
            try {
                NativeAdbBridge adb = new NativeAdbBridge(getApplicationContext());
                adbBridge = adb;
                if (pairPort > 0) {
                    runOnUiThread(() -> statusText.setText("Pairing " + deviceIp + ":" + pairPort));
                    adb.pair(deviceIp, pairPort, pairCode);
                }
                adb.connect(deviceIp, devicePort);
                File localServerJar = copyServerFromAssets();
                adb.push(localServerJar.getAbsolutePath(), "/data/local/tmp/scrcpy-server.jar");
                int scid = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
                String scidHex = String.format(Locale.US, "%08x", scid);
                String remoteSocket = "localabstract:scrcpy_" + scidHex;
                adb.forward(localForwardPort, remoteSocket);
                String sizeArg = selectedMaxSize > 0 ? "max_size=" + selectedMaxSize + " " : "";
                String fpsArg = selectedMaxFps > 0 ? "max_fps=" + selectedMaxFps + " " : "";
                adb.shellAsync("CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 3.3.4 " +
                                "scid=" + scidHex + " " +
                                sizeArg +
                                fpsArg +
                                "tunnel_forward=true video=true audio=false control=true " +
                                "video_codec=h264 send_device_meta=false send_codec_meta=true send_frame_meta=true raw_stream=false " +
                                "cleanup=false send_dummy_byte=false",
                        new NativeAdbBridge.ShellResultListener() {
                            @Override
                            public void onCompleted(String output) {
                                if (output != null && !output.trim().isEmpty()) {
                                    runOnUiThread(() -> statusText.setText("Server exited: " + output));
                                }
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                String msg = throwable == null || throwable.getMessage() == null
                                        ? "unknown"
                                        : throwable.getMessage();
                                runOnUiThread(() -> statusText.setText("Server error: " + msg));
                            }
                        });

                runOnUiThread(() -> {
                    videoClient.start("127.0.0.1", localForwardPort,
                            videoSurface.getHolder().getSurface());
                    videoSurface.setStretchToFill(stretchToFit);
                    updatePanelsForSession(true);
                    hideSystemUI();
                    setControlsVisible(false);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    statusText.setText("ADB start error: " + t.getMessage());
                    updatePanelsForSession(false);
                });
                adbBridge = null;
                controlClient.stop();
                currentLocalForwardPort = 0;
                streamReady = false;
            }
        }, "connect-start").start();
    }

    private void handleStreamFailure(String status) {
        runOnUiThread(() -> {
            String message = (status == null || status.trim().isEmpty())
                    ? "Stream failed"
                    : status;
            statusText.setText(message);
            videoClient.stop();
            controlClient.stop();
            currentLocalForwardPort = 0;
            streamReady = false;
            showSystemUI();
            adbBridge = null;
            updatePanelsForSession(false);
            setControlsVisible(true);
        });
    }

    private int parsePort(String rawValue, int defaultValue, String fieldName) {
        String value = rawValue == null ? "" : rawValue.trim();
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) {
                statusText.setText(fieldName + " must be in range 1..65535");
                return -1;
            }
            return port;
        } catch (NumberFormatException e) {
            statusText.setText(fieldName + " is invalid");
            return -1;
        }
    }

    private File copyServerFromAssets() throws Exception {
        File outFile = new File(getFilesDir(), "scrcpy-server.jar");
        try (InputStream in = getAssets().open("scrcpy-server.jar");
             FileOutputStream out = new FileOutputStream(outFile, false)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
        return outFile;
    }

    private static final class RemotePoint {
        private final int x;
        private final int y;

        private RemotePoint(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    private boolean handleTouch(View v, MotionEvent event) {
        if (remoteWidth <= 0 || remoteHeight <= 0) {
            return false;
        }
        ScrcpyControlClient control = controlClient;
        if (control == null || !control.isReady()) {
            return true;
        }

        int actionMasked = event.getActionMasked();
        int actionIndex = event.getActionIndex();
        if (actionMasked == MotionEvent.ACTION_DOWN || actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            sendTouchPointerEvent(control, v, event, actionIndex, MotionEvent.ACTION_DOWN);
            return true;
        }
        if (actionMasked == MotionEvent.ACTION_MOVE) {
            int count = event.getPointerCount();
            for (int i = 0; i < count; i++) {
                sendTouchPointerEvent(control, v, event, i, MotionEvent.ACTION_MOVE);
            }
            return true;
        }
        if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_POINTER_UP) {
            sendTouchPointerEvent(control, v, event, actionIndex, MotionEvent.ACTION_UP);
            return true;
        }
        if (actionMasked == MotionEvent.ACTION_CANCEL) {
            int count = event.getPointerCount();
            for (int i = 0; i < count; i++) {
                sendTouchPointerEvent(control, v, event, i, MotionEvent.ACTION_UP);
            }
            return true;
        }
        return true;
    }

    private void sendTouchPointerEvent(ScrcpyControlClient control, View sourceView, MotionEvent event, int pointerIndex, int action) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) {
            return;
        }
        RemotePoint point = mapToRemotePoint(sourceView, event.getX(pointerIndex), event.getY(pointerIndex));
        if (point == null) {
            return;
        }
        long pointerId = event.getPointerId(pointerIndex);
        float pressure = action == MotionEvent.ACTION_UP ? 0f : event.getPressure(pointerIndex);
        control.sendTouchEvent(action, pointerId, point.x, point.y, remoteWidth, remoteHeight, pressure);
    }

    private RemotePoint mapToRemotePoint(View view, float localX, float localY) {
        int viewWidth = view.getWidth();
        int viewHeight = view.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0 || remoteWidth <= 0 || remoteHeight <= 0) {
            return null;
        }
        int mappedX = Math.round(localX / viewWidth * remoteWidth);
        int mappedY = Math.round(localY / viewHeight * remoteHeight);
        mappedX = Math.max(0, Math.min(remoteWidth - 1, mappedX));
        mappedY = Math.max(0, Math.min(remoteHeight - 1, mappedY));
        return new RemotePoint(mappedX, mappedY);
    }

    private void sendKeyEvent(int keyCode) {
        ScrcpyControlClient control = controlClient;
        if (control != null && control.isReady()) {
            control.sendKeyPress(keyCode);
            return;
        }

        NativeAdbBridge bridge = adbBridge;
        if (bridge == null) {
            return;
        }
        new Thread(() -> {
            try {
                bridge.shell("input keyevent " + keyCode);
            } catch (Throwable t) {
                runOnUiThread(() -> statusText.setText("Input error: " + t.getMessage()));
            }
        }, "scrcpy-keyevent").start();
    }

    private void toggleControlsVisibility() {
        setControlsVisible(!controlsVisible);
    }

    private void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        if (controlOverlay != null) {
            if (sessionConnected) {
                controlOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
            } else {
                controlOverlay.setVisibility(View.VISIBLE);
            }
        }
        if (navPill != null) {
            navPill.setVisibility(sessionConnected ? View.VISIBLE : View.GONE);
        }
        if (toggleControlsButton != null) {
            toggleControlsButton.setVisibility(sessionConnected ? View.VISIBLE : View.GONE);
        }
        if (exitButton != null) {
            exitButton.setVisibility(View.GONE);
        }
    }

    private void hideSystemUI() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void showSystemUI() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void showResolutionDialog() {
        int current = indexOfValue(sizeOptions, selectedMaxSize);
        new AlertDialog.Builder(this)
                .setTitle("Resolution")
                .setSingleChoiceItems(sizeLabels, current, (dialog, which) -> {
                    selectedMaxSize = sizeOptions[which];
                    resolutionButton.setText(sizeLabels[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showFpsDialog() {
        int current = indexOfValue(fpsOptions, selectedMaxFps);
        new AlertDialog.Builder(this)
                .setTitle("FPS")
                .setSingleChoiceItems(fpsLabels, current, (dialog, which) -> {
                    selectedMaxFps = fpsOptions[which];
                    fpsButton.setText(fpsLabels[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static int indexOfValue(int[] array, int value) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == value) {
                return i;
            }
        }
        return 0;
    }

    private void toggleStretchToFit() {
        stretchToFit = !stretchToFit;
        videoSurface.setStretchToFill(stretchToFit);
        updateStretchButtonText();
    }

    private void requestRemoteClipboardSnapshot() {
        if (!autoClipboardSyncEnabled) {
            return;
        }
        ScrcpyControlClient control = controlClient;
        if (control == null || !control.isReady()) {
            return;
        }
        control.requestDeviceClipboard();
    }

    private void onLocalClipboardChanged() {
        if (!autoClipboardSyncEnabled || !sessionConnected) {
            return;
        }
        ScrcpyControlClient control = controlClient;
        if (control == null || !control.isReady()) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (applyingRemoteClipboard && now < applyingRemoteClipboardUntilMs) {
            return;
        }
        if (now - lastClipboardSendMs < CLIPBOARD_SEND_DEBOUNCE_MS) {
            return;
        }
        String text = getHostClipboardText();
        if (text == null || text.isEmpty()) {
            return;
        }
        if (text.equals(lastClipboardSentToDevice)) {
            return;
        }
        if (text.equals(lastClipboardReceivedFromDevice) && now - lastClipboardSendMs < 1500) {
            return;
        }
        control.setDeviceClipboard(text, false);
        lastClipboardSentToDevice = text;
        lastClipboardSendMs = now;
    }

    private void applyRemoteClipboardText(String text) {
        if (!autoClipboardSyncEnabled) {
            return;
        }
        String remote = text == null ? "" : text;
        String current = getHostClipboardText();
        lastClipboardReceivedFromDevice = remote;
        if (remote.equals(current)) {
            return;
        }
        applyingRemoteClipboard = true;
        applyingRemoteClipboardUntilMs = SystemClock.uptimeMillis() + REMOTE_CLIPBOARD_GUARD_MS;
        mainHandler.removeCallbacks(clearRemoteClipboardGuard);
        setHostClipboardText(remote);
        mainHandler.postDelayed(clearRemoteClipboardGuard, REMOTE_CLIPBOARD_GUARD_MS);
    }

    private String getHostClipboardText() {
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager == null || !manager.hasPrimaryClip()) {
            return null;
        }
        ClipData clip = manager.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        return text == null ? null : text.toString();
    }

    private void setHostClipboardText(String text) {
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager == null) {
            return;
        }
        String safeText = text == null ? "" : text;
        manager.setPrimaryClip(ClipData.newPlainText("scrcpy-remote", safeText));
    }

    private void updateStretchButtonText() {
        stretchToggleButton.setText(stretchToFit ? R.string.button_letterbox : R.string.button_stretch);
    }

    private void showDisconnectDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_disconnect_title)
                .setMessage(R.string.dialog_disconnect_message)
                .setPositiveButton(R.string.dialog_yes, (dialog, which) -> {
                    videoClient.stop();
                    controlClient.stop();
                    currentLocalForwardPort = 0;
                    adbBridge = null;
                    finish();
                })
                .setNegativeButton(R.string.dialog_no, null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
    }
}
