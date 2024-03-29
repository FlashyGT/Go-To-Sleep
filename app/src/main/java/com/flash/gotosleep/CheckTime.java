package com.flash.gotosleep;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.flash.gotosleep.ui.main.TimeFragment;

import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@RequiresApi(api = Build.VERSION_CODES.KITKAT_WATCH)
public class CheckTime extends Service {

    private static final int NOTIF_ID = 1;
    private static final String NOTIF_CHANNEL_ID = "Main";

    private TimeFragment timeFragment = new TimeFragment();
    private Display display;

    private int brightness = 0;
    private int startBrightness = -1;
    private boolean adaptiveBrightnessWasOn = false;

    public static final String ADAPTIVE_BRIGHTNESS = "adaptiveBrightness";
    public static final String START_BRIGHTNESS = "startBrightness";

    private int secondsToDelay = 0;

    private int currentPreset = 1;

    private Vibrator vibrator;
    private AudioManager audioManager;

    private boolean vibratorSwitched = false;
    private boolean muteSoundSwitched = false;
    private boolean screenFlashSwitched = false;

    private ScheduledExecutorService scheduleTaskExecutor = Executors.newScheduledThreadPool(2);
    private ScheduledFuture<?> scheduledFuture;
    private ScheduledFuture<?> activeFuture;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, final int startId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            secondsToDelay = 60 - OffsetDateTime.now().getSecond();
        }else {
            String currentSeconds = new SimpleDateFormat("ss", Locale.getDefault()).format(new Date());
            secondsToDelay = 60 - Integer.parseInt(currentSeconds);
        }

        //generateTests();

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        if (MainActivity.controlValue != 0) {
            MainActivity.controlValue++;
        }

        scheduledFuture = scheduleTaskExecutor.scheduleAtFixedRate(new Runnable() {
            @RequiresApi(api = Build.VERSION_CODES.R)
            public void run() {
                if (MainActivity.stopExecution) {
                    MainActivity.controlValue--;
                    scheduledFuture.cancel(true);

                    if (activeFuture != null) {
                        activeFuture.cancel(true);
                    }

                    if (muteSoundSwitched) {
                        muteAllSound(false);
                    }

                    if (MainActivity.controlValue == 0) {
                        return;
                    }
                }

                if (MainActivity.controlValue > 1) {
                    MainActivity.controlValue--;
                    scheduledFuture.cancel(true);

                    if (activeFuture != null) {
                        activeFuture.cancel(true);
                    }

                    return;
                }
                else if (MainActivity.controlValue == 1) {
                    scheduledFuture.cancel(true);
                    startNewExecution();
                    return;
                }

                if (MainActivity.controlValue == 0) {
                    MainActivity.controlValue++;
                }

                loadData();

                display = getDisplay();

                if (screenFlashSwitched) {
                    try {
                        startBrightness = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
                        saveBrightnessData();
                    } catch (Settings.SettingNotFoundException e) {
                        e.printStackTrace();
                    }
                }

                if (checkTimeInRange()) {
                    try {
                        if (screenFlashSwitched && Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE) == 1) {
                            android.provider.Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                            adaptiveBrightnessWasOn = true;
                            saveBrightnessData();
                        }
                    } catch (Settings.SettingNotFoundException e) {
                        e.printStackTrace();
                    }

                    if (muteSoundSwitched) {
                        muteAllSound(true);
                    }

                    activeBehaviour();

                }else {
                    loadBrightnessData();

                    if (muteSoundSwitched) {
                        muteAllSound(false);
                    }

                    if (screenFlashSwitched) {
                        if (adaptiveBrightnessWasOn) {
                            android.provider.Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
                            adaptiveBrightnessWasOn = false;
                            saveBrightnessData();
                        }

                        if (startBrightness != -1) {
                            android.provider.Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, startBrightness);
                        }
                    }
                }

                Log.d("delay", "" + secondsToDelay);

            }
        }, 0, secondsToDelay, TimeUnit.SECONDS);

        createNotificationChannel();
        startForeground();

        return super.onStartCommand(intent, flags, startId);
    }

    public void startNewExecution() {
        secondsToDelay = 60;

        scheduledFuture = scheduleTaskExecutor.scheduleAtFixedRate(new Runnable() {
            public void run() {
                if (MainActivity.stopExecution) {
                    scheduledFuture.cancel(true);

                    if (activeFuture != null) {
                        activeFuture.cancel(true);
                    }

                    MainActivity.controlValue = 0;
                    return;
                }

                if (checkTimeInRange()) {
                    try {
                        if (screenFlashSwitched && Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE) == 1) {
                            android.provider.Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                            adaptiveBrightnessWasOn = true;
                            saveBrightnessData();
                        }
                    } catch (Settings.SettingNotFoundException e) {
                        e.printStackTrace();
                    }

                    if (muteSoundSwitched) {
                        muteAllSound(true);
                    }

                    activeBehaviour();
                }else {
                    loadBrightnessData();

                    try {
                        if (startBrightness != Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS)
                                && (Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) != 255
                                && Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) != 0)
                        ) {
                            startBrightness = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
                        }
                    } catch (Settings.SettingNotFoundException e) {
                        e.printStackTrace();
                    }

                    if (muteSoundSwitched) {
                        muteAllSound(false);
                    }

                    if (screenFlashSwitched) {
                        if (adaptiveBrightnessWasOn) {
                            android.provider.Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
                            adaptiveBrightnessWasOn = false;
                            saveBrightnessData();
                        }

                        if (startBrightness != -1) {
                            android.provider.Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, startBrightness);
                        }
                    }
                }

                Log.d("delay", "" + secondsToDelay);

            }
        }, 0, secondsToDelay, TimeUnit.SECONDS);
    }

    private void loadData () {
        SharedPreferences sharedPreferences = getSharedPreferences(MainActivity.SHARED_PREFS, MODE_PRIVATE);

        currentPreset = sharedPreferences.getInt(MainActivity.ACTIVE_PRESET, 1);

        timeFragment.t1Hour = sharedPreferences.getInt(timeFragment.t1HourTemp + currentPreset, 0);
        timeFragment.t1Minute = sharedPreferences.getInt(timeFragment.t1MinTemp + currentPreset, 0);
        timeFragment.t2Hour = sharedPreferences.getInt(timeFragment.t2HourTemp + currentPreset, 6);
        timeFragment.t2Minute = sharedPreferences.getInt(timeFragment.t2MinTemp + currentPreset, 0);
        vibratorSwitched = sharedPreferences.getBoolean(MainActivity.vibrateSwitchTemp + currentPreset, false);
        muteSoundSwitched = sharedPreferences.getBoolean(MainActivity.muteSoundSwitchTemp + currentPreset, false);
        screenFlashSwitched = sharedPreferences.getBoolean(MainActivity.screenFlashSwitchTemp + currentPreset, false);
    }

    private void loadBrightnessData () {
        SharedPreferences sharedPreferences = getSharedPreferences(MainActivity.SHARED_PREFS, MODE_PRIVATE);

        startBrightness = sharedPreferences.getInt(START_BRIGHTNESS, -1);
        adaptiveBrightnessWasOn = sharedPreferences.getBoolean(ADAPTIVE_BRIGHTNESS, false);
    }

    private void saveBrightnessData () {
        SharedPreferences sharedPreferences = getSharedPreferences(MainActivity.SHARED_PREFS, MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();

        editor.putBoolean(ADAPTIVE_BRIGHTNESS, adaptiveBrightnessWasOn);
        editor.putInt(START_BRIGHTNESS, startBrightness);

        editor.apply();
    }

    private void activeBehaviour() {
        if (activeFuture == null) {
            activeFuture = scheduleTaskExecutor.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    if (checkTimeInRange()) {
                        if (display.getState() == Display.STATE_OFF && MainActivity.stopExecution) {
                            activeFuture.cancel(true);
                            activeFuture = null;
                            return;
                        }

                        try {
                            if (MainActivity.stopExecution && screenFlashSwitched) {
                                loadBrightnessData();

                                if (Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE) == 0 && adaptiveBrightnessWasOn) {
                                    android.provider.Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
                                    adaptiveBrightnessWasOn = false;
                                    saveBrightnessData();
                                }

                                if (startBrightness != Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS)) {
                                    android.provider.Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, startBrightness);
                                }
                            }
                        } catch (Settings.SettingNotFoundException e) {
                            e.printStackTrace();
                        }

                        if (muteSoundSwitched) {
                            if (MainActivity.stopExecution) {
                                muteAllSound(false);
                            }else {
                                muteAllSound(true);
                            }
                        }

                        if (display.getState() == Display.STATE_ON && !MainActivity.stopExecution) {
                            if (vibratorSwitched) {
                                Log.d("123", "vibrate");
                                vibrator.vibrate(1000);
                            }

                            if (screenFlashSwitched) {
                                if (brightness == 0) {
                                    brightness = 255;
                                }else {
                                    brightness = 0;
                                }

                                android.provider.Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, brightness);
                            }
                        }
                    }else {
                        android.provider.Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, startBrightness);
                        activeFuture.cancel(true);
                        activeFuture = null;
                    }
                }
            },0,2, TimeUnit.SECONDS);
        }
    }

    private void muteAllSound(boolean muteSound) {
        if (muteSound) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0);
                audioManager.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_MUTE, 0);
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
                audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0);
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0);
            } else {
                audioManager.setStreamMute(AudioManager.STREAM_NOTIFICATION, true);
                audioManager.setStreamMute(AudioManager.STREAM_ALARM, true);
                audioManager.setStreamMute(AudioManager.STREAM_MUSIC, true);
                audioManager.setStreamMute(AudioManager.STREAM_RING, true);
                audioManager.setStreamMute(AudioManager.STREAM_SYSTEM, true);
            }
        }else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0);
                    audioManager.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_UNMUTE, 0);
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE,0);
                    audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0);
                    audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0);
                }
            } else {
                if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                    audioManager.setStreamMute(AudioManager.STREAM_NOTIFICATION, false);
                    audioManager.setStreamMute(AudioManager.STREAM_ALARM, false);
                    audioManager.setStreamMute(AudioManager.STREAM_MUSIC, false);
                    audioManager.setStreamMute(AudioManager.STREAM_RING, false);
                    audioManager.setStreamMute(AudioManager.STREAM_SYSTEM, false);
                }
            }
        }

    }

    private void generateTests () {
        int min = 0;
        int hourMax = 23;
        int minuteMax = 59;

        for (int i = 0; i < 100; i++) {
            checkTimeInRangeTest(
                    new Random().nextInt((hourMax - min) + 1) + min,
                    new Random().nextInt((minuteMax - min) + 1) + min,
                    new Random().nextInt((hourMax - min) + 1) + min,
                    new Random().nextInt((minuteMax - min) + 1) + min,
                    new Random().nextInt((hourMax - min) + 1) + min,
                    new Random().nextInt((minuteMax - min) + 1) + min);
        }
    }

    private void checkTimeInRangeTest (int t1Hour, int t1Minute, int t2Hour, int t2Minute, int testCurrentHour, int testCurrentMin) {
        if ((t1Hour <= testCurrentHour || t2Hour >= testCurrentHour && t1Hour > t2Hour || t1Hour == t2Hour)
                && (t2Hour >= testCurrentHour || t2Hour < t1Hour || t1Hour == t2Hour)
                && (t1Minute <= testCurrentMin && t1Hour != t2Hour || t1Hour < testCurrentHour || t2Hour < t1Hour && t1Hour != testCurrentHour
                    || t1Hour == t2Hour && testCurrentHour == t1Hour && t2Minute > testCurrentMin || t1Hour == t2Hour && testCurrentHour != t1Hour && t2Minute < t1Minute)
                && (t2Minute >= testCurrentMin && t1Hour != t2Hour || t2Hour > testCurrentHour || t2Hour < t1Hour && t2Hour != testCurrentHour
                    || t1Hour == t2Hour && testCurrentHour == t1Hour && t1Minute < testCurrentMin || t1Hour == t2Hour && testCurrentHour != t1Hour && t2Minute < t1Minute))
        {
            Log.d("test", t1Hour + ":" + t1Minute + " " + testCurrentHour + ":" + testCurrentMin + " " + t2Hour + ":" + t2Minute + "    TRUE");
        }else {
            Log.d("test", t1Hour + ":" + t1Minute + " " + testCurrentHour + ":" + testCurrentMin + " " + t2Hour + ":" + t2Minute + "    FALSE");
        }
    }

    private boolean checkTimeInRange () {
        loadData();

        int currentHour;
        int currentMin;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            currentHour = OffsetDateTime.now().getHour();
            currentMin = OffsetDateTime.now().getMinute();
        }else {
            String currentTime = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
            String[] separated = currentTime.split(":");

            currentHour = Integer.parseInt(separated[0]);
            currentMin = Integer.parseInt(separated[1]);
        }

        if ((timeFragment.t1Hour <= currentHour || timeFragment.t2Hour >= currentHour && timeFragment.t1Hour > timeFragment.t2Hour || timeFragment.t1Hour == timeFragment.t2Hour)
                && (timeFragment.t2Hour >= currentHour || timeFragment.t2Hour < timeFragment.t1Hour || timeFragment.t1Hour == timeFragment.t2Hour)
                && (timeFragment.t1Minute <= currentMin && timeFragment.t1Hour != timeFragment.t2Hour || timeFragment.t1Hour < currentHour || timeFragment.t2Hour < timeFragment.t1Hour && timeFragment.t1Hour != currentHour
                    || timeFragment.t1Hour == timeFragment.t2Hour && currentHour == timeFragment.t1Hour && timeFragment.t2Minute >= currentMin
                    || timeFragment.t1Hour == timeFragment.t2Hour && currentHour != timeFragment.t1Hour && timeFragment.t2Minute < timeFragment.t1Minute)
                && (timeFragment.t2Minute >= currentMin && timeFragment.t1Hour != timeFragment.t2Hour || timeFragment.t2Hour > currentHour || timeFragment.t2Hour < timeFragment.t1Hour && timeFragment.t2Hour != currentHour
                    || timeFragment.t1Hour == timeFragment.t2Hour && currentHour == timeFragment.t1Hour && timeFragment.t1Minute <= currentMin
                    || timeFragment.t1Hour == timeFragment.t2Hour && currentHour != timeFragment.t1Hour && timeFragment.t2Minute < timeFragment.t1Minute))
        {
            return true;
        }else {
            return false;
        }
    }

    private void startForeground() {
        Intent notificationIntent = new Intent(this, MainActivity.class);

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, 0);

        startForeground(NOTIF_ID, new NotificationCompat.Builder(this,
                NOTIF_CHANNEL_ID) // don't forget create a notification channel first
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setColor(ContextCompat.getColor(this, R.color.black))
                .setContentText("Checking time")
                .setContentIntent(pendingIntent)
                .build());
    }

    private void createNotificationChannel () {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Check time";
            int importance = NotificationManager.IMPORTANCE_MIN;
            NotificationChannel channel = new NotificationChannel(NOTIF_CHANNEL_ID, name, importance);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
}

