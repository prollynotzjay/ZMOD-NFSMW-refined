package com.nfsmw.androidevolved;

import android.app.NativeActivity;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.content.Intent;
import android.net.Uri;
import android.Manifest;
import android.content.pm.PackageManager;
import android.widget.Toast;
import android.widget.TextView;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.graphics.Color;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.CheckBox;
import android.app.AlertDialog;
import android.text.InputFilter;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.util.DisplayMetrics;

/** Storage permission gateway for the fixed Internal storage/nfsmw directory. */
public final class GameActivity extends NativeActivity {
    static { System.loadLibrary("nfsmw_android"); }
    private boolean requested;
    private boolean foreground;
    private TextView status;
    private PopupWindow statusWindow;
    private PopupWindow controlsWindow;
    private RacingControlsView touchControls;
    private boolean statusHidden;
    private Runnable statusHideTask;
    private static native void nativeKey(int scan, boolean down);
    private static native boolean nativeDrivingControls();
    private static native int nativeGameState();
    private static native void nativeVirtualSteer(float steer);
    private static native boolean nativeGamepadUsed();
    private final boolean[] touchKeys=new boolean[256],sentKeys=new boolean[256];
    private boolean tiltLeft,tiltRight;
    private boolean padJoystick;
    private android.hardware.input.InputManager inputManager;
    private GyroSteering gyro;
    private float pwmAccumulator;
    private void sendCombinedKey(int scan) {
        boolean held=touchKeys[scan]||(scan==0xcb&&tiltLeft)||(scan==0xcd&&tiltRight);
        if(sentKeys[scan]!=held) {sentKeys[scan]=held;nativeKey(scan,held);}
    }
    private void setTouchKey(int scan,boolean down) {touchKeys[scan]=down;sendCombinedKey(scan);}
    private void setTiltKeys(boolean left,boolean right) {tiltLeft=left;tiltRight=right;sendCombinedKey(0xcb);sendCombinedKey(0xcd);}
    private android.content.SharedPreferences controlPrefs() {return getSharedPreferences("touch_layouts_v1",MODE_PRIVATE);}
    /** PS3-style output: the gyro drives the left stick of a virtual pad (applies from the next launch). */
    private boolean gyroPadWanted() {
        return controlPrefs().getBoolean("gyro.pad",false)&&RacingControlsView.GYRO.equals(RacingControlsView.readScheme(this));
    }
    /** Gyro value -1..1 to the game: the virtual pad's stick, or key pulses whose width follows the value. */
    private void onGyroSteer(float value) {
        boolean active=foreground&&hasWindowFocus()&&touchControls!=null&&touchControls.gyroActive();
        if(!active) {
            if(gyroPadWanted())nativeVirtualSteer(0);
            setTiltKeys(false,false);pwmAccumulator=0;return;
        }
        if(gyroPadWanted()) {nativeVirtualSteer(value);return;}
        float magnitude=Math.abs(value);
        if(magnitude>.92f) {setTiltKeys(value<0,value>0);return;}
        pwmAccumulator+=magnitude;
        boolean pulse=pwmAccumulator>=1f;if(pulse)pwmAccumulator-=1f;
        setTiltKeys(pulse&&value<0,pulse&&value>0);
    }
    private void updateGyro() {
        if(gyro==null)return;
        android.content.SharedPreferences prefs=controlPrefs();
        gyro.configure(prefs.getFloat("gyro.range",30f),prefs.getBoolean("gyro.invert",false));
        gyro.setActive(foreground&&RacingControlsView.GYRO.equals(RacingControlsView.readScheme(this)));
        if(!RacingControlsView.GYRO.equals(RacingControlsView.readScheme(this)))setTiltKeys(false,false);
    }
    private void showGyroOptions() {
        if(gyro==null||!gyro.available()) {Toast.makeText(this,tr("This device has no gyroscope"),Toast.LENGTH_LONG).show();return;}
        android.content.SharedPreferences prefs=controlPrefs();
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(24),dp(8),dp(24),dp(8));
        CheckBox invert=new CheckBox(this);invert.setText(tr("Invert direction"));invert.setChecked(prefs.getBoolean("gyro.invert",false));form.addView(invert);
        TextView label=new TextView(this);form.addView(label);
        android.widget.SeekBar range=new android.widget.SeekBar(this);range.setMax(45);range.setProgress(Math.round(prefs.getFloat("gyro.range",30f))-15);form.addView(range);
        label.setText(tr("Turn for full lock: %d°",range.getProgress()+15));
        range.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar bar,int value,boolean user) {label.setText(tr("Turn for full lock: %d°",value+15));}
            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {}
        });
        CheckBox pad=new CheckBox(this);pad.setText(tr("PS3 pad emulation: analog stick (experimental, next launch)"));pad.setChecked(prefs.getBoolean("gyro.pad",false));form.addView(pad);
        TextView help=new TextView(this);help.setText(tr("Hold the phone like a wheel. It recalibrates to your position when a race starts. Smaller angle = more sensitive. The pedals stay on the touch screen or are automatic."));form.addView(help);
        new AlertDialog.Builder(this).setTitle(tr("Gyro steering")).setView(form)
                .setPositiveButton(tr("Apply"),(dialog,which)->{
                    prefs.edit().putBoolean("gyro.invert",invert.isChecked()).putFloat("gyro.range",range.getProgress()+15f).putBoolean("gyro.pad",pad.isChecked()).apply();
                    updateGyro();gyro.recalibrate();
                }).setNeutralButton(tr("Recalibrate"),(dialog,which)->{if(gyro!=null)gyro.recalibrate();})
                .setNegativeButton(tr("Cancel"),null).show();
    }
    private final android.hardware.input.InputManager.InputDeviceListener gamepadListener=new android.hardware.input.InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id) {updateGamepads();}
        @Override public void onInputDeviceRemoved(int id) {updateGamepads();}
        @Override public void onInputDeviceChanged(int id) {updateGamepads();}
    };
    /**
     * Physical gamepads (Bluetooth or USB) drive the game; the touch layer hides while one is connected.
     * Only external devices count: built-in sensors and side keys of several MediaTek phones report
     * themselves as joysticks with X/Y axes, and their resting value used to steer the car by itself.
     * Pads in "keyboard" mode report only KEYBOARD but still have gamepad buttons.
     */
    private void updateGamepads() {
        boolean connected=false;
        java.util.ArrayList<Integer> accepted=new java.util.ArrayList<>();
        StringBuilder seen=new StringBuilder();
        for(int id:android.view.InputDevice.getDeviceIds()) {
            android.view.InputDevice device=android.view.InputDevice.getDevice(id);
            if(device==null||device.isVirtual())continue;
            int sources=device.getSources();
            boolean buttons=(sources&android.view.InputDevice.SOURCE_GAMEPAD)==android.view.InputDevice.SOURCE_GAMEPAD;
            // Some fingerprint sensors advertise JOYSTICK with only a Z axis.
            boolean stick=(sources&android.view.InputDevice.SOURCE_JOYSTICK)==android.view.InputDevice.SOURCE_JOYSTICK&&
                    device.getMotionRange(android.view.MotionEvent.AXIS_X)!=null&&
                    device.getMotionRange(android.view.MotionEvent.AXIS_Y)!=null;
            boolean keys=(sources&android.view.InputDevice.SOURCE_KEYBOARD)==android.view.InputDevice.SOURCE_KEYBOARD&&
                    device.getKeyboardType()!=android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC&&
                    device.hasKeys(android.view.KeyEvent.KEYCODE_BUTTON_A)[0];
            boolean pad=device.isExternal()&&(buttons||stick||keys);
            seen.append(seen.length()==0?"":"; ").append(device.getName()).append(" [").append(Integer.toHexString(sources))
                    .append(device.isExternal()?" external":" internal").append(pad?" pad":"").append(']');
            if(pad){connected=true;accepted.add(id);}
        }
        android.util.Log.i("NFSMW","Input devices: "+seen);
        // Only these devices may move the guest's stick or press its keys.
        int[] ids=new int[accepted.size()];for(int i=0;i<ids.length;++i)ids[i]=accepted.get(i);
        nativeGamepadDevices(ids);
        if(touchControls!=null)touchControls.setGamepadConnected(connected);
        // The DirectInput joystick is optional (launcher): by default the pad is
        // folded into the keyboard keys the game already uses.
        nativeGamepadConnected((connected&&padJoystick)||gyroPadWanted());
    }
    private final Runnable updateControlMode = new Runnable() {
        @Override public void run() {
            if(!foreground||touchControls==null)return;
            touchControls.setGameState(nativeGameState());
            if(touchControls.takeRaceStarted()&&gyro!=null)gyro.recalibrate();
            // Playing on with the pad after a touch revealed the controls hides them again.
            if(nativeGamepadUsed())touchControls.gamepadUsed();
            touchControls.postDelayed(this,250);
        }
    };
    private static native void nativeTypeText(String value, boolean replace);
    private static native void nativePointer(float x, float y, int phase);
    private static native void nativeLanguage(String language);
    private static native void nativeResolution(int width, int height);
    private static native void nativeFrameLimit(int framesPerSecond);
    private static native void nativeWidescreen(boolean enabled);
    private static native void nativeNetworkIdentity(long identity);
    private static native void nativeGamepadConnected(boolean connected);
    private static native void nativeGamepadDevices(int[] ids);
    private static native void nativeMinimapTop(boolean top);
    private static native void nativeBackBuffers(int count);
    private static native void nativeCpuTextures(boolean enabled);
    private static native void nativeGpuDriver(String hooks, String temp, String directory, String library);
    private void showFrameLimitOptions() {
        GameOptions.Option option=GameOptions.find(GameOptions.FPS);
        String saved=GameOptions.get(this,GameOptions.FPS);int selected=0;
        for(int i=0;i<option.values.length;i++)if(option.values[i].equals(saved))selected=i;
        new AlertDialog.Builder(this).setTitle(tr(option.title))
                .setSingleChoiceItems(Lang.t(this,option.labels),selected,(dialog,which)->{
                    GameOptions.set(this,GameOptions.FPS,option.values[which]);
                    nativeFrameLimit(Integer.parseInt(option.values[which]));dialog.dismiss();
                }).setNegativeButton(tr("Cancel"),null).show();
    }
    private String tr(String text,Object... args) {return Lang.t(this,text,args);}
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void showTextInput() {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint(tr("Name, e-mail or password"));
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        name.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        CheckBox replace = new CheckBox(this);
        replace.setText(tr("Replace the current name"));replace.setChecked(true);
        LinearLayout form = new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), 0, dp(20), 0);form.addView(name);form.addView(replace);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(tr("Type in the game"))
                .setView(form)
                .setNegativeButton(tr("Cancel"),null)
                .setPositiveButton(tr("Type"), null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String value = name.getText().toString();
            if (value.isEmpty() || !value.matches("[A-Za-z0-9 .,=/;_+?:!@#$%&*()-]{1,40}")) {
                name.setError(tr("Up to 40 characters: letters without accents, digits and . , - _ @ + ! # $ % & * ( ) = ? / ; :"));
                return;
            }
            nativeTypeText(value, replace.isChecked());
            dialog.dismiss();
        }));
        dialog.show();
        name.requestFocus();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }
    @Override protected void onCreate(Bundle saved) {
        // NetBIOS supplies the PC game's machine identity. Keep a random ID
        // private to this installation (backup is disabled), across upgrades
        // and both APK architectures. The shared game path is not a device ID.
        android.content.SharedPreferences network=getSharedPreferences("network_identity_v1",MODE_PRIVATE);
        long identity=network.getLong("id",0);
        if(identity==0){
            do{identity=java.util.UUID.randomUUID().getLeastSignificantBits();}while(identity==0);
            network.edit().putLong("id",identity).commit();
        }
        nativeNetworkIdentity(identity);
        super.onCreate(saved);
        inputManager=(android.hardware.input.InputManager)getSystemService(INPUT_SERVICE);
        gyro=new GyroSteering(this,this::onGyroSteer);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        android.view.Display display=getWindowManager().getDefaultDisplay();
        android.view.Display.Mode current=display.getMode();float refresh=60;
        for(android.view.Display.Mode mode:display.getSupportedModes())
            if(mode.getPhysicalWidth()==current.getPhysicalWidth()&&mode.getPhysicalHeight()==current.getPhysicalHeight())
                refresh=Math.max(refresh,mode.getRefreshRate());
        android.view.WindowManager.LayoutParams windowParams=getWindow().getAttributes();
        windowParams.preferredRefreshRate=Math.min(120,refresh);getWindow().setAttributes(windowParams);
        // Launcher options travel as extras; a direct start (adb, recents) falls back to the saved ones.
        Intent launch = getIntent();
        DisplayMetrics metrics = new DisplayMetrics(); getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        int[] size = GameOptions.renderSize(this, Math.max(metrics.widthPixels, metrics.heightPixels),
                Math.min(metrics.widthPixels, metrics.heightPixels));
        nativeResolution(launch.getIntExtra("renderWidth", size[0]), launch.getIntExtra("renderHeight", size[1]));
        String language = launch.getStringExtra("language");
        nativeLanguage(language != null ? language : GameOptions.language(this));
        nativeFrameLimit(launch.getIntExtra("frameCap", Integer.parseInt(GameOptions.get(this, GameOptions.FPS))));
        nativeWidescreen(launch.getBooleanExtra("widescreen", GameOptions.get(this, GameOptions.WIDESCREEN).equals("on")));
        nativeMinimapTop(launch.getBooleanExtra("minimapTop", GameOptions.get(this, GameOptions.MINIMAP).equals("top")));
        nativeBackBuffers(launch.getIntExtra("backBuffers", Integer.parseInt(GameOptions.get(this, GameOptions.LATENCY))));
        nativeCpuTextures(launch.getBooleanExtra("cpuTextures", GameOptions.get(this, GameOptions.TEXTURES).equals("cpu")));
        padJoystick = launch.getBooleanExtra("padJoystick", GameOptions.get(this, GameOptions.GAMEPAD).equals("directinput"));
        String[] loader = launch.getStringArrayExtra("driverLoader");
        if (loader == null || loader.length != 4) loader = GpuDrivers.loaderArguments(this);
        nativeGpuDriver(loader[0], loader[1], loader[2], loader[3]);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0xb0000000);
        status.setTextSize(18);
        status.setPadding(24, 16, 24, 16);
        status.setText("NFSMW Refined\nChecking internal storage/nfsmw…");
        // NativeActivity gives its main window surface to Vulkan; use a separate
        // non-interactive popup surface for the diagnostic text.
        statusWindow = new PopupWindow(status, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, false);
        statusWindow.setTouchable(false);
        touchControls = new RacingControlsView(this, this::setTouchKey, this::showTextInput,this::showGyroOptions,this::showFrameLimitOptions,this::updateGyro);
        touchControls.setPointer(GameActivity::nativePointer);
        controlsWindow = new PopupWindow(touchControls, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, false);
        controlsWindow.setClippingEnabled(false);
    }
    public void setStatus(String message) {
        runOnUiThread(() -> {
            if (statusHideTask != null) status.removeCallbacks(statusHideTask);
            if (message.contains("Starting the game (CRT entry)")) {
                status.setText("NFSMW Refined\n" + message);
                statusHideTask = () -> {statusHidden=true;if(statusWindow.isShowing())statusWindow.dismiss();};
                status.postDelayed(statusHideTask, 8000);
            } else {
                statusHidden=false;
                status.setText("NFSMW Refined\n" + message);
                if (!statusWindow.isShowing() && hasWindowFocus())
                    statusWindow.showAtLocation(getWindow().getDecorView(), Gravity.TOP | Gravity.START, 0, 0);
            }
        });
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if(!focused) {
            if(touchControls!=null)touchControls.releaseAll();
            setTiltKeys(false,false);
            if(controlsWindow!=null)controlsWindow.dismiss();
        }
        if(focused){
            if(Build.VERSION.SDK_INT>=30){
                android.view.WindowInsetsController insets=getWindow().getInsetsController();
                if(insets!=null){insets.hide(android.view.WindowInsets.Type.systemBars());
                    insets.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);}
            }else getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_FULLSCREEN |
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        if (focused && foreground && statusWindow != null && !statusHidden && !statusWindow.isShowing()) {
            statusWindow.showAtLocation(getWindow().getDecorView(), Gravity.TOP | Gravity.START, 0, 0);
        }
        if (focused && foreground && controlsWindow != null && !controlsWindow.isShowing()) {
            controlsWindow.showAtLocation(getWindow().getDecorView(), Gravity.TOP | Gravity.START, 0, 0);
        }
    }
    // LAN games announce and discover themselves with UDP broadcasts on port 9999;
    // without this lock many devices drop incoming Wi-Fi broadcasts to save power.
    private android.net.wifi.WifiManager.MulticastLock lanBroadcasts;
    private void holdLanBroadcasts(boolean hold) {
        if (lanBroadcasts == null) {
            android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifi == null) return;
            lanBroadcasts = wifi.createMulticastLock("nfsmw-lan");
            lanBroadcasts.setReferenceCounted(false);
        }
        if (hold) lanBroadcasts.acquire(); else lanBroadcasts.release();
    }
    @Override protected void onPause() {
        foreground=false;
        holdLanBroadcasts(false);
        updateGyro();
        if(inputManager!=null)inputManager.unregisterInputDeviceListener(gamepadListener);
        if (statusWindow != null) statusWindow.dismiss();
        if (touchControls != null) {touchControls.removeCallbacks(updateControlMode);touchControls.releaseAll();}
        if (controlsWindow != null) controlsWindow.dismiss();
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        foreground=true;
        holdLanBroadcasts(true);
        updateGyro();
        if(inputManager!=null){inputManager.registerInputDeviceListener(gamepadListener,null);updateGamepads();}
        if(touchControls!=null) {touchControls.removeCallbacks(updateControlMode);touchControls.post(updateControlMode);}
        boolean allowed = Build.VERSION.SDK_INT >= 30
                ? Environment.isExternalStorageManager()
                : checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        if (!allowed && !requested) {
            requested = true;
            Toast.makeText(this, tr("Allow file access to read the nfsmw folder in internal storage"), Toast.LENGTH_LONG).show();
            if (Build.VERSION.SDK_INT >= 30) {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } else {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            }
        }
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        // NativeActivity may stay resumed when a runtime permission dialog closes.
        if (code == 1 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) recreate();
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        // Each launch uses a fresh guest machine in the dedicated :game process.
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
