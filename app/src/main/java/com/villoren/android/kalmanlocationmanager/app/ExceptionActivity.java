package com.villoren.android.kalmanlocationmanager.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ApplicationErrorReport;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.text.Html;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.ScrollingMovementMethod;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.TextView;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Created by Edward Mukhutdinov (tujger@gmail.com)
 */
public class ExceptionActivity extends Activity {

    public static final String EXCEPTION = "exception";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        final AlertDialog dialog = new AlertDialog.Builder(ExceptionActivity.this).create();
        dialog.setTitle("Exception");

        @SuppressLint("InflateParams") View content = getLayoutInflater().inflate(R.layout.activity_exception, null);

        Throwable exception = (Throwable) getIntent().getSerializableExtra(EXCEPTION);

        ApplicationErrorReport.CrashInfo crashInfo = new ApplicationErrorReport.CrashInfo(exception);
        String exName = crashInfo.exceptionClassName;

        TextView text = (TextView) content.findViewById(R.id.tvException);

        CharSequence boldExName = createSpanned(exName, new StyleSpan(Typeface.BOLD));

        Spanned crashMessage;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            crashMessage = Html.fromHtml(getString(R.string.caught_an_exception_s,boldExName), Html.FROM_HTML_MODE_LEGACY);
        } else {
            //noinspection deprecation
            crashMessage = Html.fromHtml(getString(R.string.caught_an_exception_s,boldExName));
        }

        text.setText(crashMessage);

        EditText etExceptionTrace = (EditText) content.findViewById(R.id.etExceptionTrace);

        final StringWriter textException = new StringWriter();
        exception.printStackTrace(new PrintWriter(textException));

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        etExceptionTrace.setMaxHeight((int) (metrics.heightPixels/1.5));

        etExceptionTrace.setText(BuildConfig.VERSION_NAME + "." + BuildConfig.VERSION_CODE +"\n"+ textException.toString());
        etExceptionTrace.setMovementMethod(new ScrollingMovementMethod());

            if (Build.VERSION.SDK_INT >= 11) {
                etExceptionTrace.setRawInputType(InputType.TYPE_CLASS_TEXT);
                etExceptionTrace.setTextIsSelectable(true);
            } else {
                etExceptionTrace.setRawInputType(InputType.TYPE_NULL);
                etExceptionTrace.setFocusable(true);
            }

        dialog.setButton(DialogInterface.BUTTON_NEUTRAL, getString(R.string.report), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Intent intent = new Intent(Intent.ACTION_SEND);
                        intent.setData(Uri.parse("mailto:"));
                        intent.setType("text/plain");
                        intent.putExtra(Intent.EXTRA_EMAIL, new String[]{""});
                        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.uncaught_exception_in_application));
                        intent.putExtra(Intent.EXTRA_TEXT, textException.toString());
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
//                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);

                        startActivity(Intent.createChooser(intent, getString(R.string.send_report_about_an_error)));
                    }
                }).start();
                finish();
            }
        });

        dialog.setButton(DialogInterface.BUTTON_POSITIVE, getString(R.string.restart), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                moveTaskToBack(true);
                finish();
                startActivity(new Intent(ExceptionActivity.this, MainActivity.class));
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        });

        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, "Close", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                finish();
            }
        });

        dialog.setView(content);
        dialog.show();
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    private CharSequence createSpanned(String s, Object... spans) {
        SpannableStringBuilder sb = new SpannableStringBuilder(s);
        for (Object span : spans) {
            sb.setSpan(span, 0, sb.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }
}