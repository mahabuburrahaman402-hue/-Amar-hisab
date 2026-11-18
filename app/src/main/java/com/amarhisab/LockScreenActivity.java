package com.amarhisab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.app.KeyguardManager;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.Toast;

import java.util.concurrent.Executor;

public class LockScreenActivity extends Activity {
    private int primary;
    private int dark;
    private TextView status;
    private boolean promptOpen = false;
    private static final int DEVICE_CREDENTIAL_REQUEST = 8041;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean blueNext = getSharedPreferences("hisab", MODE_PRIVATE)
            .getBoolean("lock_theme_next_blue", false);
        primary = blueNext ? Color.rgb(18, 105, 225) : Color.rgb(220, 35, 55);
        dark = blueNext ? Color.rgb(5, 47, 112) : Color.rgb(125, 8, 25);
        getSharedPreferences("hisab", MODE_PRIVATE).edit()
            .putBoolean("lock_theme_next_blue", !blueNext).apply();
        buildScreen();
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(28, 36, 28, 28);
        GradientDrawable bg = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[]{primary, Color.rgb(255,255,255), primary});
        root.setBackground(bg);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.amar_hisab_logo);
        logo.setContentDescription("আমার হিসাব লোগো");
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(-1, 116);
        logoParams.setMargins(0, 0, 0, 8);
        root.addView(logo, logoParams);
        TextView title = text("আমার হিসাব", 32, dark, true);
        root.addView(title);
        TextView english = text("AMAR HISAB", 16, dark, true);
        root.addView(english);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(20, 24, 20, 24);
        panel.setBackground(rounded(dark, 34));
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(-1, -2);
        panelParams.setMargins(0, 32, 0, 24);
        root.addView(panel, panelParams);

        TextView lock = text("🔒  App Locked", 25, Color.WHITE, true);
        panel.addView(lock);
        TextView message = text("আপনার ব্যবসার হিসাব দেখতে পরিচয় যাচাই করুন", 17,
            Color.WHITE, false);
        LinearLayout.LayoutParams msgParams = new LinearLayout.LayoutParams(-1, -2);
        msgParams.setMargins(0, 14, 0, 22);
        panel.addView(message, msgParams);

        TextView unlock = text("Unlock with", 20, Color.WHITE, true);
        panel.addView(unlock);

        Button biometric = makeButton("◉  Fingerprint / Face", Color.WHITE, dark);
        panel.addView(biometric, buttonParams());
        biometric.setOnClickListener(v -> authenticate());

        Button pin = makeButton("▦  Use PIN / ফোনের PIN", Color.WHITE, dark);
        panel.addView(pin, buttonParams());
        pin.setOnClickListener(v -> requestDeviceCredential());

        status = text("Authentication required", 15, Color.WHITE, true);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.setMargins(0, 16, 0, 0);
        panel.addView(status, statusParams);

        TextView footer = text("আপনার তথ্য সুরক্ষিত আছে", 15, dark, true);
        root.addView(footer);
        setContentView(root);
        // Open the system biometric/device-credential prompt immediately.
        // The visible buttons remain available if the prompt is dismissed.
        root.post(() -> authenticate());
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, 58);
        p.setMargins(0, 12, 0, 0);
        return p;
    }

    private Button makeButton(String label, int background, int foreground) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setTextColor(foreground);
        b.setAllCaps(false);
        b.setBackground(rounded(background, 28));
        return b;
    }

    private void authenticate() {
        if (promptOpen) return;
        if (Build.VERSION.SDK_INT < 28) {
            requestDeviceCredential();
            return;
        }
        try {
            Executor executor = command -> runOnUiThread(command);
            BiometricPrompt.Builder builder = new BiometricPrompt.Builder(this)
                .setTitle("আমার হিসাব আনলক করুন")
                .setSubtitle("ফোনে সেট করা Fingerprint বা Face দিয়ে যাচাই করুন");
            if (Build.VERSION.SDK_INT >= 30) {
                builder.setDeviceCredentialAllowed(true);
            } else {
                builder.setNegativeButton("Use PIN", executor, (dialog, which) ->
                    requestDeviceCredential());
            }
            promptOpen = true;
            builder.build().authenticate(new CancellationSignal(), executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        promptOpen = false;
                        setResult(RESULT_OK);
                        finish();
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        runOnUiThread(() -> status.setText("মিল পাওয়া যায়নি — আবার চেষ্টা করুন"));
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        promptOpen = false;
                        runOnUiThread(() -> status.setText("যাচাই হয়নি: " + errString));
                    }
                });
        } catch (Exception e) {
            promptOpen = false;
            status.setText("Fingerprint চালু হয়নি — PIN ব্যবহার করুন");
        }
    }

    private void requestDeviceCredential() {
        KeyguardManager keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard == null || !keyguard.isDeviceSecure()) {
            new AlertDialog.Builder(this)
                .setTitle("ফোনের Screen Lock প্রয়োজন")
                .setMessage("Fingerprint, Face বা PIN ব্যবহার করতে আগে ফোনে Screen Lock সেট করুন।")
                .setNegativeButton("ফিরে যান", (d, w) -> status.setText("Screen Lock সেট করা নেই"))
                .setPositiveButton("Settings খুলুন", (d, w) -> {
                    try { startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)); }
                    catch (Exception ignored) { }
                }).show();
            return;
        }
        Intent confirm = keyguard.createConfirmDeviceCredentialIntent(
            "আমার হিসাব আনলক করুন", "ফোনের PIN / Pattern / Password দিন");
        if (confirm == null) {
            status.setText("ফোনের PIN যাচাই চালু করা যায়নি");
            return;
        }
        startActivityForResult(confirm, DEVICE_CREDENTIAL_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == DEVICE_CREDENTIAL_REQUEST) {
            if (resultCode == RESULT_OK) {
                setResult(RESULT_OK);
                finish();
            } else {
                status.setText("Authentication সম্পন্ন হয়নি — আবার চেষ্টা করুন");
            }
        }
    }

    @Override
    public void onBackPressed() {
        Toast.makeText(this, "আনলক না করে হিসাব খোলা যাবে না", Toast.LENGTH_SHORT).show();
    }
}
