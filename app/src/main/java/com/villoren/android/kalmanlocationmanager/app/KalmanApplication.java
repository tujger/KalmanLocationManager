package com.villoren.android.kalmanlocationmanager.app;

import android.app.Application;
import android.content.Intent;
import android.os.Process;

import static com.villoren.android.kalmanlocationmanager.app.ExceptionActivity.EXCEPTION;

/**
 * Created by eduardm on 017, 2/17/2017.
 */

public class KalmanApplication extends Application {
        @Override
        public void onCreate() {
            super.onCreate();

            new Thread(new Runnable() {
                @Override
                public void run() {
//                BackupAgent.requestBackup(OdysseyApplication.this);
                }
            }).start();

            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread paramThread, Throwable paramThrowable) {
                    paramThrowable.printStackTrace();

                    Intent intent = new Intent(KalmanApplication.this, ExceptionActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.putExtra(EXCEPTION, paramThrowable);
                    startActivity(intent);

                    android.os.Process.killProcess(Process.myPid());
                    System.exit(2);
                }
            });
        }
    }