package com.amarhisab;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import android.text.*;
import org.json.*;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.text.Collator;
import java.util.*;

public class MainActivity extends Activity {

    private LinearLayout content, rows;
    private SharedPreferences prefs;
    private JSONArray records;
    private JSONArray partyNames;
    private JSONArray stockItems;
    private EditText party;
    private Spinner typeSpinner;
    private TextView totalView;
    private double grandTotal = 0;
    private byte[] pendingPdfBytes;
    private static final int SAVE_RENT_PDF = 7312;
    private static final int SAVE_RECEIPT_PDF = 7316;
    private static final int SAVE_PARTY_PDF = 7317;
    private static final int SAVE_SUMMARY_PDF = 7321;
    private static final int SAVE_STOCK_PDF = 7320;
    private static final int SAVE_BACKUP = 7313;
    private static final int IMPORT_BACKUP = 7314;
    private byte[] pendingBackupBytes;
    private JSONArray pendingEntryAttachments = new JSONArray();
    private static final int PICK_ENTRY_ATTACHMENTS = 7318;
    private static final int PICK_RECORD_ATTACHMENTS = 7319;
    private int activeAttachmentRecordIndex = -1;
    private static final long MAX_ATTACHMENT_BYTES = 5L * 1024L * 1024L;
    private static final long MAX_RECORD_ATTACHMENT_BYTES = 12L * 1024L * 1024L;
    private static final long MAX_BACKUP_ATTACHMENT_BYTES = 12L * 1024L * 1024L;
    private static final int MAX_RECORD_ATTACHMENTS = 20;
    private static final int APP_LOCK_REQUEST = 7315;
    private boolean lockInProgress = false;
    private boolean lockPassed = false;
    private boolean credentialFallbackStarted = false;
    private final DecimalFormat df = new DecimalFormat("0.##");
    private int blue = Color.rgb(25, 90, 180);
    private String activeUiModule = "dashboard";
    private boolean updateCheckStarted = false;
    private boolean updateDownloadInProgress = false;
    private java.io.File pendingUpdateFile;
    private static final long MAX_UPDATE_APK_BYTES = 100L * 1024L * 1024L;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("hisab", MODE_PRIVATE);
        blue = prefs.getInt("app_theme_color", Color.rgb(25, 90, 180));
        loadRecords();
        loadPartyNames();
        loadTrash();
        loadRentalUnits();
        loadStockItems();
        cleanupOrphanAttachmentGenerations();
        cleanupOrphanAttachments(records, trash);
        autoPostCurrentMonthRent();
        if (!prefs.contains("app_lock_enabled")) {
            prefs.edit().putBoolean("app_lock_enabled", false).apply();
        }
        if (prefs.getBoolean("app_lock_enabled", false)) {
            showLockScreen();
            return;
        }
        home();
        scheduleUpdateCheck();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs == null) return;
        if (prefs.getBoolean("app_lock_enabled", false) && !lockPassed && !lockInProgress) {
            showLockScreen();
            return;
        }
        continuePendingUpdateInstall();
    }

    private void scheduleUpdateCheck() {
        if (updateCheckStarted || content == null) return;
        updateCheckStarted = true;
        content.postDelayed(() -> checkForUpdates(false), 1500);
    }

    private void showLockScreen() {
        if (lockInProgress || lockPassed) return;
        lockInProgress = true;
        Intent lock = new Intent(this, LockScreenActivity.class);
        startActivityForResult(lock, APP_LOCK_REQUEST);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!lockInProgress) lockPassed = false;
    }

    private void requestAppAuthentication() {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            java.util.concurrent.Executor executor = command -> runOnUiThread(command);
            credentialFallbackStarted = false;
            android.hardware.biometrics.BiometricPrompt.Builder builder =
                new android.hardware.biometrics.BiometricPrompt.Builder(this)
                    .setTitle(ui("হিসাব খুলুন"))
                    .setSubtitle(ui("ফিঙ্গারপ্রিন্ট / মুখমণ্ডল বা ফোনের স্ক্রিন লক দিয়ে যাচাই করুন"));
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                builder.setDeviceCredentialAllowed(true);
            } else {
                builder.setNegativeButton(ui("ফোনের PIN ব্যবহার করুন"), executor,
                    (dialog, which) -> {
                        credentialFallbackStarted = true;
                        lockInProgress = false;
                        requestDeviceCredential();
                    });
            }
            lockInProgress = true;
            try {
                builder.build().authenticate(new android.os.CancellationSignal(), executor,
                    new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                            android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) {
                            lockInProgress = false;
                            lockPassed = true;
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            if (android.os.Build.VERSION.SDK_INT < 30) {
                                if (!credentialFallbackStarted) {
                                    credentialFallbackStarted = true;
                                    lockInProgress = false;
                                    requestDeviceCredential();
                                }
                                return;
                            }
                            lockInProgress = false;
                            finish();
                        }
                    });
            } catch (Exception e) {
                lockInProgress = false;
                requestDeviceCredential();
            }
        } else {
            requestDeviceCredential();
        }
    }

    private void requestDeviceCredential() {
        android.app.KeyguardManager keyguard =
            (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguard == null || !keyguard.isDeviceSecure()) {
            Toast.makeText(this, ui("অ্যাপ লক চালু করতে আগে ফোনে স্ক্রিন লক সেট করুন"),
                Toast.LENGTH_LONG).show();
            prefs.edit().putBoolean("app_lock_enabled", false).apply();
            return;
        }
        Intent confirm = keyguard.createConfirmDeviceCredentialIntent(
            ui("হিসাব খুলুন"), ui("ফোনের বায়োমেট্রিক বা স্ক্রিন লক দিয়ে যাচাই করুন"));
        if (confirm == null) {
            Toast.makeText(this, ui("ফোনের নিরাপত্তা যাচাই চালু করা যায়নি"),
                Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        lockInProgress = true;
        startActivityForResult(confirm, APP_LOCK_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == APP_LOCK_REQUEST) {
            lockInProgress = false;
            if (resultCode == RESULT_OK) {
                lockPassed = true;
                home();
                scheduleUpdateCheck();
                continuePendingUpdateInstall();
            } else {
                finish();
            }
            return;
        }
        if (requestCode == SAVE_BACKUP) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null &&
                pendingBackupBytes != null) {
                android.net.Uri backupUri = data.getData();
                try (java.io.OutputStream out = getContentResolver().openOutputStream(backupUri)) {
                    if (out == null) throw new java.io.IOException("Output stream unavailable");
                    out.write(pendingBackupBytes); out.flush();
                    Toast.makeText(this, ui("ব্যাকআপ ফাইল সংরক্ষণ হয়েছে"), Toast.LENGTH_LONG).show();
                    shareBackupUri(backupUri);
                } catch (Exception e) {
                    Toast.makeText(this, ui("ব্যাকআপ সংরক্ষণ হয়নি: ") + e.getMessage(), Toast.LENGTH_LONG).show();
                } finally { pendingBackupBytes = null; }
            } else {
                pendingBackupBytes = null;
            }
            return;
        }
        if (requestCode == PICK_ENTRY_ATTACHMENTS) {
            if (resultCode == RESULT_OK && data != null) {
                int added = appendAttachmentUris(data, pendingEntryAttachments);
                Toast.makeText(this, ui("সংযুক্ত নথি যোগ হয়েছে: ") + added,
                    Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode == PICK_RECORD_ATTACHMENTS) {
            if (resultCode == RESULT_OK && data != null &&
                activeAttachmentRecordIndex >= 0 &&
                activeAttachmentRecordIndex < records.length()) {
                JSONObject record = records.optJSONObject(activeAttachmentRecordIndex);
                if (record != null) {
                    JSONArray oldAttachments = record.optJSONArray("attachments");
                    try {
                        JSONArray updated = oldAttachments == null
                            ? new JSONArray() : new JSONArray(oldAttachments.toString());
                        int added = appendAttachmentUris(data, updated);
                        record.put("attachments", updated);
                        if (!prefs.edit().putString("records", records.toString()).commit()) {
                            if (oldAttachments == null) record.remove("attachments");
                            else record.put("attachments", oldAttachments);
                            throw new IllegalStateException("ডেটা সংরক্ষণ হয়নি");
                        }
                        Toast.makeText(this, ui("নতুন সংযুক্ত নথি যোগ হয়েছে: ") + added,
                            Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Toast.makeText(this, ui("সংযুক্ত নথি সংরক্ষণ হয়নি: ") + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                    }
                }
                manageRecordAttachments(activeAttachmentRecordIndex);
            } else {
                activeAttachmentRecordIndex = -1;
            }
            return;
        }
        if (requestCode == IMPORT_BACKUP && resultCode == RESULT_OK && data != null &&
            data.getData() != null) {
            importBackupFromUri(data.getData());
            return;
        }
        if ((requestCode == SAVE_RENT_PDF || requestCode == SAVE_RECEIPT_PDF ||
             requestCode == SAVE_PARTY_PDF || requestCode == SAVE_SUMMARY_PDF || requestCode == SAVE_STOCK_PDF) &&
            resultCode == RESULT_OK && data != null && data.getData() != null &&
            pendingPdfBytes != null) {
            try (java.io.OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out == null) throw new java.io.IOException("Output stream unavailable");
                out.write(pendingPdfBytes); out.flush();
                String savedMessage = requestCode == SAVE_RENT_PDF
                    ? "ভাড়ার PDF সংরক্ষণ হয়েছে"
                    : requestCode == SAVE_PARTY_PDF
                        ? "পার্টির স্টেটমেন্ট PDF সংরক্ষণ হয়েছে"
                        : requestCode == SAVE_SUMMARY_PDF
                            ? "মাসিক / বার্ষিক রিপোর্ট PDF সংরক্ষণ হয়েছে"
                            : requestCode == SAVE_STOCK_PDF
                            ? "স্টক রিপোর্ট PDF সংরক্ষণ হয়েছে"
                            : "রসিদ / ইনভয়েস PDF সংরক্ষণ হয়েছে";
                Toast.makeText(this, ui(savedMessage), Toast.LENGTH_LONG).show();
                sharePdfUri(data.getData(), requestCode == SAVE_RENT_PDF
                    ? "ভাড়ার হিসাবের PDF"
                    : requestCode == SAVE_PARTY_PDF
                        ? "পার্টির স্টেটমেন্ট PDF"
                        : requestCode == SAVE_SUMMARY_PDF
                            ? "মাসিক / বার্ষিক রিপোর্ট PDF"
                            : requestCode == SAVE_STOCK_PDF
                                ? "দোকানের স্টক রিপোর্ট PDF" : "রসিদ / ইনভয়েস PDF");
            } catch (Exception e) {
                Toast.makeText(this, ui("PDF সংরক্ষণ করা যায়নি: ") + e.getMessage(), Toast.LENGTH_LONG).show();
            } finally { pendingPdfBytes = null; }
        }
    }

    // Shares the saved PDF through Android's share sheet, including WhatsApp when installed.
    private void sharePdfUri(android.net.Uri uri, String description) {
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/pdf");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, ui(description));
            share.putExtra(Intent.EXTRA_TEXT, ui("আমার হিসাব অ্যাপ থেকে পাঠানো " + description));
            share.setClipData(ClipData.newUri(getContentResolver(), ui(description), uri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share,
                ui("WhatsApp-এ পাঠান অথবা অন্য অ্যাপে শেয়ার করুন")));
        } catch (Exception e) {
            Toast.makeText(this, ui("PDF শেয়ার অপশন খোলা যায়নি: ") + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void loadRecords() {
        try {
            records = new JSONArray(prefs.getString("records", "[]"));
        } catch (Exception e) {
            records = new JSONArray();
        }
    }

    private void saveRecords() {
        prefs.edit().putString("records", records.toString()).apply();
    }

    // Party profiles are stored separately so a party can exist before its first transaction.
    private void loadPartyNames() {
        try { partyNames = new JSONArray(prefs.getString("party_names", "[]")); }
        catch (Exception e) { partyNames = new JSONArray(); }
        boolean changed = false;
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record == null || isCashBankRecord(record) || isRentalLedgerRecord(record)) continue;
            String name = record.optString("party", "").trim();
            if (!name.isEmpty() && !hasPartyName(name)) {
                partyNames.put(name);
                changed = true;
            }
        }
        if (changed) savePartyNames();
    }

    private boolean hasPartyName(String name) {
        for (int i = 0; i < partyNames.length(); i++) {
            String saved = partyNames.optString(i, "").trim();
            if (saved.equalsIgnoreCase(name.trim())) return true;
        }
        return false;
    }

    private boolean savePartyNames() {
        return prefs.edit().putString("party_names", partyNames.toString()).commit();
    }

    private void addPartyDialog() {
        EditText nameField = field("নতুন পার্টির নাম লিখুন");
        new AlertDialog.Builder(this)
            .setTitle(ui("নতুন পার্টি / কাস্টমার"))
            .setMessage(ui("শুধু নাম লিখে সংরক্ষণ করুন। পরে এই পার্টির খাতায় লেনদেন যোগ করতে পারবেন।"))
            .setView(nameField)
            .setNegativeButton(ui("বাতিল"), null)
            .setPositiveButton(ui("সংরক্ষণ"), (dialog, which) -> {
                String name = nameField.getText().toString().trim();
                if (name.isEmpty()) {
                    Toast.makeText(this, ui("পার্টির নাম লিখুন"), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (hasPartyName(name)) {
                    Toast.makeText(this, ui("এই নামে পার্টি আগে থেকেই আছে"), Toast.LENGTH_SHORT).show();
                    return;
                }
                JSONArray previous = partyNames;
                try {
                    partyNames = new JSONArray(partyNames.toString());
                    partyNames.put(name);
                    if (!savePartyNames()) throw new IllegalStateException("ডেটা সংরক্ষণ হয়নি");
                    Toast.makeText(this, ui("পার্টি যোগ হয়েছে"), Toast.LENGTH_SHORT).show();
                    ledger();
                } catch (Exception e) {
                    partyNames = previous;
                    Toast.makeText(this, ui("পার্টি সংরক্ষণ হয়নি; আবার চেষ্টা করুন"), Toast.LENGTH_LONG).show();
                }
            }).show();
    }

    private String ui(String value) {
        if (!"en".equals(prefs.getString("language", "bn"))) return value;
        String[][] words = {
            {"লেখার ডিজাইন ও অক্ষর সেটিংস", "Text Design & Typography Settings"},
            {"কোন মডিউলের লেখা সাজাবেন?", "Choose a module to customize"},
            {"লেখার সেটিংস", "Text Settings"}, {"লেখার আকার", "Text Size"},
            {"লেখার ধরন", "Text Style"}, {"লেখার রং", "Text Color"},
            {"ডিফল্টে ফেরত", "Reset to Default"}, {"ফিরিয়ে দিন", "Reset"},
            {"খুব ছোট (12)", "Very Small (12)"}, {"ছোট (14)", "Small (14)"},
            {"মাঝারি (16)", "Medium (16)"}, {"বড় (18)", "Large (18)"},
            {"খুব বড় (22)", "Very Large (22)"},
            {"সাধারণ", "Regular"}, {"মোটা (Bold)", "Bold"},
            {"বাঁকা (Italic)", "Italic"}, {"মোটা ও বাঁকা", "Bold Italic"},
            {"গাঢ় কালো", "Dark"}, {"নীল", "Blue"}, {"সবুজ", "Green"},
            {"লাল", "Red"}, {"বেগুনি", "Purple"}, {"সাদা", "White"},
            {"হিসাব", "Hisab"}, {"আমার হিসাবের ড্যাশবোর্ড", "My Hisab Dashboard"},
            {"নতুন হিসাব", "New Entry"}, {"পার্টির হিসাব দেখুন", "Party Ledgers"},
            {"বাড়ি ভাড়া ব্যবস্থাপনা", "Rental Management"}, {"দোকানের স্টক / ক্রয়-বিক্রয়", "Shop Stock / Sales"},
            {"হিসাব ব্যবস্থাপনা / মুছুন", "Manage / Delete Entries"},
            {"রিসাইকেল বিন / ফেরত আনুন", "Recycle Bin / Restore"},
            {"ডেটা ব্যাকআপ সংরক্ষণ করুন", "Save and Share Data Backup"},
            {"ব্যাকআপ থেকে ডেটা ফিরিয়ে আনুন", "Restore Data Backup"},
            {"ড্যাশবোর্ড আপডেট", "Refresh Dashboard"},
            {"+ নতুন হিসাব যোগ করুন", "+ Add New Entry"},
            {"পার্টির হিসাব", "Party Ledger"}, {"বাড়িভাড়া আয়-ব্যয় রিপোর্ট", "Rental Income & Expense Report"},
            {"সমগ্র বাড়িভাড়া আয়-ব্যয় রিপোর্ট", "Full Rental Income & Expense Report"},
            {"PDF / রসিদ ডিজাইন ও স্বাক্ষর", "PDF / Receipt Design & Signature"},
            {"PDF ও রসিদ সেটিংস", "PDF & Receipt Settings"},
            {"PDF-এ দেখানোর নাম / স্বাক্ষর", "Name / Signature on PDF"},
            {"PDF / রসিদের রং", "PDF / Receipt Color"},
            {"নীল", "Blue"}, {"সবুজ", "Green"}, {"বেগুনি", "Purple"}, {"গাঢ় ধূসর", "Dark Gray"},
            {"স্বাক্ষর বা নাম (ঐচ্ছিক)", "Signature or Name (optional)"},
            {"সংরক্ষণ", "Save"}, {"বাতিল", "Cancel"}, {"ফিরে যান", "Back"},
            {"হিসাব সেভ করুন", "Save Entry"}, {"হিসাবের ধরন", "Entry Type"},
            {"পার্টি / কাস্টমারের নাম", "Party / Customer Name"},
            {"নাম লিখুন", "Enter name"}, {"পণ্যের নাম", "Item name"},
            {"পরিমাণ", "Quantity"}, {"প্রতি এককের দাম", "Rate per unit"},
            {"বিবরণ লিখুন", "Enter description"}, {"বিবরণ (ঐচ্ছিক)", "Description (optional)"},
            {"+ আরেকটি পণ্য যোগ করুন", "+ Add Another Item"},
            {"সমস্ত হিসাব", "All Accounts"}, {"মোট পাওনা", "Total Receivable"},
            {"মোট জমা", "Total Received"}, {"মোট খরচ", "Total Expenses"},
            {"মোট ব্যবসায়িক আয়", "Total Business Income"}, {"অবশিষ্ট পাওনা", "Outstanding Balance"},
            {"হিসাব ব্যবস্থাপনা", "Account Management"}, {"যে হিসাবটি মুছবেন সেটি নির্বাচন করুন। মুছলে আগে রিসাইকেল বিনে যাবে।", "Select an entry to delete. It will first be moved to the recycle bin."},
            {"রিসাইকেল বিনে সরান", "Move to Recycle Bin"}, {"নিশ্চিত করুন", "Confirm"}, {"এই হিসাবটি রিসাইকেল বিনে পাঠাবেন?", "Move this entry to the recycle bin?"},
            {"না", "No"}, {"হ্যাঁ, সরান", "Yes, Move"}, {"রিসাইকেল বিনে পাঠানো হয়েছে", "Moved to Recycle Bin"}, {"সংযুক্ত হিসাব", "Linked Entry"},
            {"এই হিসাবটি সংশ্লিষ্ট ভাড়া, স্টক বা জামানত মডিউল দ্বারা নিয়ন্ত্রিত। নিরাপত্তার জন্য এখানে সম্পাদনা বা মুছে ফেলা যাবে না; তবে রসিদ PDF তৈরি করা যাবে।", "This entry is controlled by its rental, stock or deposit module. To protect linked records, editing and deletion are disabled here, but a receipt PDF can be created."},
            {"রসিদ / ইনভয়েস PDF সংরক্ষণ", "Save Receipt / Invoice PDF"},
            {"হিসাব সম্পাদনা", "Edit Entry"}, {"পার্টির নাম", "Party Name"}, {"টাকার পরিমাণ", "Amount"}, {"বিবরণ", "Description"},
            {"+ নতুন পার্টি / কাস্টমার যোগ করুন", "+ Add New Party / Customer"}, {"নতুন পার্টির নাম লিখুন", "Enter new party name"},
            {"এই নামে পার্টি আগে থেকেই আছে", "A party with this name already exists"}, {"পার্টি যোগ হয়েছে", "Party added"},
            {"এখনো কোনো লেনদেন নেই। নতুন হিসাব যোগ করুন।", "No transactions yet. Add a new entry."},
            {"পার্টি / কাস্টমার তালিকা (A–Z)", "Party / Customer List (A–Z)"},
            {"সব পার্টির খাতা দেখুন", "View All Party Ledgers"},
            {"এখনো কোনো পার্টি যোগ করা হয়নি।", "No parties have been added yet."},
            {"পাওনা: ৳ ", "Due: BDT "}, {"জমা: ৳ ", "Credit: BDT "},
            {"এখনো কোনো লেনদেন নেই", "No transactions yet"},
            {"পরিবর্তন সংরক্ষণ", "Save Changes"}, {"রিসাইকেল বিন", "Recycle Bin"}, {"রিসাইকেল বিন খালি", "Recycle Bin is Empty"},
            {"মুছে ফেলা হিসাব", "Deleted Entries"}, {"ফেরত আনুন", "Restore"}, {"স্থায়ীভাবে মুছুন", "Delete Permanently"},
            {"স্থায়ীভাবে মুছবেন?", "Delete Permanently?"}, {"এই কাজ আর ফেরত নেওয়া যাবে না।", "This action cannot be undone."},
            {"দোকানের স্টক ব্যবস্থাপনা", "Shop Inventory Management"}, {"+ নতুন পণ্য / মাল ক্রয়", "+ Add Product / Purchase Stock"},
            {"পণ্য বিক্রি করুন", "Sell Product"}, {"এখনো কোনো পণ্য যোগ করা হয়নি", "No products have been added yet"},
            {"প্রতি পিস বিক্রয়মূল্য", "Selling Price per Unit"}, {"প্রতি পিস ক্রয়মূল্য", "Purchase Cost per Unit"},
            {"ক্রেতা / সরবরাহকারী (ঐচ্ছিক)", "Customer / Supplier (optional)"}, {"পণ্য বিক্রি", "Sell Product"}, {"পণ্য ক্রয়", "Purchase Stock"},
            {"বিক্রি সংরক্ষণ", "Save Sale"}, {"ক্রয় সংরক্ষণ", "Save Purchase"}, {"পর্যাপ্ত স্টক নেই", "Insufficient Stock"},
            {"নগদ বিক্রয়", "Cash Sale"}, {"স্টক ক্রয়", "Stock Purchase"}, {"স্টক বিক্রয়: ", "Stock Sale: "}, {"স্টক ক্রয়: ", "Stock Purchase: "},
            {"+ নতুন ভাড়াটিয়া / ইউনিট যোগ করুন", "+ Add Tenant / Unit"}, {"এখনো কোনো ইউনিট নেই", "No rental units yet"},
            {"ভাড়াটিয়া আছে", "Occupied"}, {"খালি", "Vacant"}, {"মাসিক ভাড়া (টাকা)", "Monthly Rent (BDT)"},
            {"অগ্রিম / জামানত (টাকা)", "Advance / Security Deposit (BDT)"}, {"নতুন বাড়ি / ফ্ল্যাট / দোকান", "New Building / Flat / Shop"},
            {"নতুন ভাড়াটিয়া বসান", "Assign New Tenant"}, {"ইউনিটের তথ্য সম্পাদনা", "Edit Unit Details"}, {"আগের ভাড়াটিয়ার ইতিহাস", "Previous Tenant History"},
            {"এই মাসের ভাড়া বকেয়া যোগ করুন", "Post This Month's Rent Due"}, {"ভাড়াটিয়ার খাতা দেখুন", "View Tenant Ledger"},
            {"ভাড়াটিয়ার ব্যক্তিগত লেনদেনের খাতা", "Tenant Personal Transaction Ledger"},
            {"ভাড়া জমা নিন (আংশিক / পূর্ণ)", "Collect Rent (Partial / Full)"}, {"ভাড়া রিপোর্ট", "Rental Report"},
            {"ইউটিলিটি / সার্ভিস চার্জ যোগ", "Add Utility / Service Charge"}, {"মেরামত / বাড়ির খরচ যোগ", "Add Repair / Property Expense"},
            {"অগ্রিম / জামানত পরিবর্তন", "Adjust Advance / Deposit"}, {"ভাড়া বৃদ্ধি / কমান", "Increase / Decrease Rent"},
            {"ভাড়াটিয়া চলে গেছেন / ইউনিট খালি করুন", "Tenant Moved Out / Vacate Unit"}, {"জামানতের লেনদেন দেখুন", "View Deposit Transactions"},
            {"ইউটিলিটি বিল জমা নিন", "Collect Utility Bill"}, {"ভাড়াটিয়া ওঠা-নামার ইতিহাস", "Tenant Move-in / Move-out History"},
            {"জামানত ফেরত দিন", "Refund Deposit"}, {"সমগ্র বাড়িভাড়া রিপোর্ট", "Full Rental Portfolio Report"},
            {"ভাড়া ব্যবস্থাপনায় ফিরুন", "Back to Rental Management"}, {"ভাড়াটিয়ার বিল যোগ", "Add Tenant Charge"}, {"হিসাবে যোগ", "Add to Ledger"},
            {"বাড়ি / ভবনের নাম", "Building Name"}, {"তলা", "Floor"}, {"ফ্ল্যাট / দোকান নম্বর", "Flat / Shop Number"},
            {"মাসিক ভাড়া", "Monthly Rent"}, {"জামানত", "Security Deposit"}, {"ভাড়া দেওয়ার শেষ তারিখ", "Rent Due Day"},
            {"ভাড়া", "Rent"}, {"বকেয়া", "Outstanding"}, {"আদায়", "Collected"}, {"মেরামত ও অন্যান্য খরচ", "Repairs & Other Expenses"},
            {"অ্যাপ লক / ফিঙ্গারপ্রিন্ট সেটিংস", "App Lock / Fingerprint Settings"},
            {"অ্যাপ চালু করলে ফোনের নিরাপত্তা যাচাই করুন", "Verify phone security when opening the app"},
            {"এটি ফোনের নিরাপদ বায়োমেট্রিক যাচাই ব্যবহার করবে। ফিঙ্গারপ্রিন্ট বা মুখমণ্ডল শনাক্তকরণ সমর্থিত ও চালু থাকলে ব্যবহার করা যাবে; নইলে ফোনের PIN / পাসওয়ার্ড দিয়ে যাচাই করুন।", "Uses your phone's built-in screen-lock verification. If fingerprint is configured, it may be used; otherwise your phone PIN / password is required."},
            {"অ্যাপ লক সেটিংস", "App Lock Settings"},
            {"অ্যাপ লক চালু করতে আগে ফোনে স্ক্রিন লক সেট করুন", "Set a screen lock on your phone before enabling app lock"},
            {"ফোনের বায়োমেট্রিক বা স্ক্রিন লক দিয়ে যাচাই করুন", "Verify with your phone biometrics or screen lock"},
            {"ফিঙ্গারপ্রিন্ট / মুখমণ্ডল বা ফোনের স্ক্রিন লক দিয়ে যাচাই করুন", "Verify with fingerprint / face recognition or your phone screen lock"},
            {"এটি ফোনের নিরাপদ বায়োমেট্রিক যাচাই ব্যবহার করবে। ফিঙ্গারপ্রিন্ট বা মুখমণ্ডল শনাক্তকরণ সমর্থিত ও চালু থাকলে ব্যবহার করা যাবে; নইলে ফোনের PIN / পাসওয়ার্ড দিয়ে যাচাই করুন।", "Uses your phone’s secure biometric authentication. Use fingerprint or face recognition if supported and enabled; otherwise authenticate with your phone PIN or password."},
            {"হিসাব খুলুন", "Unlock Hisab"},
            {"ফোনের বায়োমেট্রিক বা স্ক্রিন লক দিয়ে যাচাই করুন", "Verify with your phone fingerprint or screen lock"},
            {"ফোনের নিরাপত্তা যাচাই চালু করা যায়নি", "Could not start phone security verification"},
            {"অ্যাপ লক সেটিংস সংরক্ষণ হয়েছে", "App lock settings saved"},
            {"অ্যাপ লক সেটিংস সংরক্ষণ হয়নি", "Could not save app lock settings"},
            {"＋  ক্যাশ ইন", "＋  Cash In"},
            {"−  ক্যাশ আউট", "−  Cash Out"},
            {"হিসাবের সারসংক্ষেপ", "Account Summary"},
            {"দ্রুত কাজ", "Quick Actions"},
            {"নগদ ও ব্যাংক হিসাব", "Cash & Bank Accounts"},
            {"মাসিক / বার্ষিক হিসাব", "Monthly / Yearly Reports"},
            {"সেটিংস ও ডেটা সুরক্ষা", "Settings & Data Protection"},
            {"ড্যাশবোর্ডের রং পরিবর্তন", "Change Dashboard Color"},
            {"নগদ ব্যালেন্স", "Cash Balance"},
            {"ব্যাংক অ্যাকাউন্টসমূহ", "Bank Accounts"},
            {"এখনো কোনো ব্যাংক অ্যাকাউন্ট যোগ করা হয়নি", "No bank accounts added yet"},
            {"+ নতুন ব্যাংক অ্যাকাউন্ট যোগ করুন", "+ Add New Bank Account"},
            {"+ নগদ / ব্যাংক লেনদেন", "+ Cash / Bank Transaction"},
            {"সাম্প্রতিক নগদ ও ব্যাংক লেনদেন", "Recent Cash & Bank Transactions"},
            {"ব্যাংক / অ্যাকাউন্টের নাম", "Bank / Account Name"},
            {"প্রারম্ভিক ব্যালেন্স (না থাকলে ০)", "Opening Balance (enter 0 if none)"},
            {"এই মাস — ", "This Month — "},
            {"ব্যবসায়িক আয়: ৳ ", "Business Income: BDT "},
            {"এই বছর — ", "This Year — "},
            {"এই মাস (", "This Month ("},
            {"এই বছর (", "This Year ("},
            {"সর্বমোট ধার্য ভাড়া: ৳ ", "Total Rent Charged: BDT "},
            {"সর্বমোট ভাড়া আদায়: ৳ ", "Total Rent Collected: BDT "},
            {"সর্বমোট ভাড়ার বকেয়া: ৳ ", "Total Rent Outstanding: BDT "},
            {"ইউটিলিটি / সার্ভিস চার্জ ধার্য: ৳ ", "Utility / Service Charges: BDT "},
            {"ইউটিলিটি বিল আদায়: ৳ ", "Utility Bills Collected: BDT "},
            {"ইউটিলিটি বিল বকেয়া: ৳ ", "Utility Bills Outstanding: BDT "},
            {"মেরামত ও অন্যান্য খরচ (সর্বমোট): ৳ ", "Total Repairs & Other Expenses: BDT "},
            {"এই মাসের ইউটিলিটি চার্জ: ৳ ", "This Month's Utility Charges: BDT "},
            {"এই বছরের ইউটিলিটি চার্জ: ৳ ", "This Year's Utility Charges: BDT "},
            {"বর্তমান জামানত (সংরক্ষিত): ৳ ", "Current Security Deposits (Held): BDT "},
            {"জামানত আয় নয়; এটি আলাদা সংরক্ষিত হিসাব।", "Security deposits are not income; they are held separately."},

            {"হাতে স্বাক্ষর আঁকুন", "Draw Signature"},
            {"নিচের জায়গায় আঙুল দিয়ে নিজের স্বাক্ষর আঁকুন", "Draw your signature below using your finger"},
            {"আবার আঁকুন", "Draw Again"},
            {"টাকার হিসাব / ক্যালকুলেটর", "Amount / Calculator"},
            {"যেমন: 30 × 20 + 40 + 24", "Example: 30 × 20 + 40 + 24"},
            {"মোট: ৳ 0", "Total: BDT 0"},
            {"পণ্যের মোট: ৳ 0", "Item Subtotal: BDT 0"},
            {"প্রয়োজনে ছোট বিবরণ লিখুন", "Add a short description if needed"},
            {"ক্রয়-বিক্রয়ের সাম্প্রতিক ইতিহাস", "Recent Purchase & Sales History"},
            {"এখনো কোনো ক্রয়-বিক্রয়ের ইতিহাস নেই", "No purchase or sales history yet"},
            {"এখন জমা (খালি রাখলে পুরো বাকি)", "Paid Now (leave blank for full credit)"},
            {"জামানতের লেনদেন", "Deposit Transactions"},
            {"বাসা ছাড়ার সময় মোট বকেয়া (ভাড়া/চার্জ): ৳ ", "Total Due at Move-out (rent/charges): BDT "},
            {"ভাড়া — ধার্য: ৳ ", "Rent — Charged: BDT "},
            {"চার্জ — ধার্য: ৳ ", "Charges — Charged: BDT "},
            {"  চার্জ: ", "  Charge: "},
            {"  জামানত — ", "  Deposit — "},
            {"ভাড়া রিপোর্ট — ", "Rental Report — "},
            {"রসিদ / ইনভয়েস", "RECEIPT / INVOICE"},
            {"ছাড়ের টাকা (ঐচ্ছিক)", "Discount amount (optional)"},
            {"VAT / Tax (%) (ঐচ্ছিক)", "VAT / Tax (%) (optional)"},
            {"অতিরিক্ত চার্জের টাকা (ঐচ্ছিক)", "Additional charge (optional)"},
            {"Round-off সমন্বয় (+/- টাকা, ঐচ্ছিক)", "Round-off adjustment (+/- amount, optional)"},
            {"+ পণ্যের বিস্তারিত (ঐচ্ছিক)", "+ Item details (optional)"},
            {"− পণ্যের বিস্তারিত বন্ধ করুন", "− Hide item details"},
            {"সর্বমোট: ৳ ", "Grand total: BDT "},
            {"হিসাব সেভ হয়েছে", "Entry saved"},
            {"সেভ করা যায়নি: ", "Could not save: "},
            {"সঠিক টাকার পরিমাণ লিখুন", "Enter a valid amount"},
            {"পার্টির নাম লিখুন", "Enter party name"},
            {"পণ্যের নাম লিখুন", "Enter item name"},
            {"সঠিক পরিমাণ লিখুন", "Enter a valid quantity"},
            {"সঠিক একক দাম লিখুন", "Enter a valid unit price"},
            {"পণ্যের বিস্তারিত দিলে নাম, পরিমাণ ও দাম পূরণ করুন", "When adding item details, enter item name, quantity and rate"},
            {"ছাড়, VAT/Tax ও অতিরিক্ত চার্জের পরিমাণ যাচাই করুন", "Check the discount, VAT/Tax and additional charge amounts"},
            {"সঠিক হিসাব লিখুন", "Enter a valid calculation"},
            {"মোট: ৳ ", "Total: BDT "},
            {"এই পণ্যের মোট: ৳ ", "Item total: BDT "},
            {"ক্যালকুলেটর", "Calculator"},


            {"নগদ জমা", "Cash Deposit"},
            {"ব্যাংক জমা", "Bank Deposit"},
            {"ব্যাংক উত্তোলন", "Bank Withdrawal"},
            {"নগদ", "Cash"},
            {"নাম খুঁজুন", "Search name"},
            {"এখনো কোনো পার্টির হিসাব নেই", "No party accounts yet"},
            {"অবশিষ্ট: ৳ ", "Balance: BDT "},
            {"বছর", "Year"}, {"পরিমাণ", "Amount"}, {"দর", "Rate"},
            {"পণ্য", "Item"}, {"বিবরণ", "Description"}, {"মোট", "Total"},
            {"এই পার্টিকে SMS পাঠান", "Send SMS to this party"},
            {"আগে SMS সেটিংস চালু করুন", "Enable SMS in settings first"},
            {"SMS পাঠানোর নম্বর", "SMS recipient number"},
            {"মোবাইল নম্বর লিখুন", "Enter mobile number"},
            {"মেসেজ অ্যাপ খুলুন", "Open Messages App"},
            {"মেসেজ অ্যাপ খোলা যায়নি", "Could not open messaging app"},
            {"SMS পাঠানোর সেটিংস", "SMS Settings"},
            {"গ্রাহককে SMS পাঠানোর সুবিধা চালু রাখুন", "Enable customer SMS feature"},
            {"SMS নিজে থেকে পাঠানো হবে না। চালু করলে পরে রসিদ/বকেয়ার SMS পাঠানোর সময় আপনার অনুমতি নিয়ে ফোনের মেসেজ অ্যাপ খুলবে।", "SMS will not be sent automatically. When enabled, the phone's messaging app will open for your approval when sending a receipt or due reminder."},
            {"SMS সেটিংস", "SMS Settings"}, {"SMS সেটিংস সংরক্ষণ হয়েছে", "SMS settings saved"},
            {"SMS সেটিংস সংরক্ষণ হয়নি", "SMS settings could not be saved"},
            {"বর্তমান হিসাব, রিসাইকেল বিন, বাড়িভাড়া ও স্টকের ডেটা ব্যাকআপের ডেটা দিয়ে প্রতিস্থাপিত হবে। আগে বর্তমান ডেটার ব্যাকআপ রাখুন।", "Current accounts, recycle bin, rental and stock data will be replaced by the backup. Back up current data first."},
            {"এই মাস (", "This month ("}, {"এই বছর (", "This year ("},
            {"এই মাসের বাড়ির খরচ: ৳ ", "Property expenses this month: BDT "},
            {"এই বছরের ভাড়া আদায় থেকে বাড়ির খরচ বাদে: ৳ ", "This year's rent collected minus property expenses: BDT "},
            {"খরচের বিবরণ: মেরামত / রং / পরিষ্কার", "Expense details: repair / paint / cleaning"},
            {"বাড়ির মেরামত / খরচ", "Property Repair / Expense"},
            {"নতুন মোট অগ্রিম / জামানত", "New Total Advance / Deposit"},
            {"অগ্রিম / জামানত", "Advance / Deposit"}, {"বর্তমান জামানত: ৳ ", "Current Deposit: BDT "},
            {"এখনো জামানতের পরিবর্তনের ইতিহাস নেই।", "No deposit adjustment history yet."},
            {"কার্যকর হওয়ার মাস (YYYY-MM), যেমন 2026-12", "Effective month (YYYY-MM), e.g. 2026-12"},
            {"ভাড়াটিয়া: ", "Tenant: "}, {"ভাড়াটিয়া চলে গেছেন / ইউনিট খালি", "Tenant Moved Out / Vacate Unit"},
            {"পুরোনো ভাড়াটিয়ার লেনদেন ও বকেয়া মুছে যাবে না। ওঠা-নামার ইতিহাসে সংরক্ষিত হবে।", "The previous tenant's transactions and dues will be retained in the move-in / move-out history."},
            {"বর্তমান ভাড়াটিয়া: ", "Current Tenant: "}, {"বাড়ি / ভবন", "Building"},
            {"ধরন", "Type"}, {"ভাড়া দেওয়ার শেষ তারিখ (১-৩১)", "Rent due day (1–31)"},
            {"ইউটিলিটি / সার্ভিস চার্জ: ৳ ", "Utility / Service Charges: BDT "},
            {"ইউটিলিটি আদায়: ৳ ", "Utilities Collected: BDT "}, {"ইউটিলিটি বকেয়া: ৳ ", "Utility Balance Due: BDT "},
            {"ইউনিটের মেরামত / অন্যান্য খরচ: ৳ ", "Unit Repairs / Other Expenses: BDT "},
            {"মোট পাওনা: ৳ ", "Total Receivable: BDT "}, {"মোট জমা: ৳ ", "Total Received: BDT "},
            {"মোট ব্যবসায়িক আয়: ৳ ", "Total Business Income: BDT "}, {"মোট খরচ: ৳ ", "Total Expenses: BDT "},
            {"মোট পরিশোধ: ৳ ", "Total Payments: BDT "}, {"অবশিষ্ট পাওনা: ৳ ", "Outstanding Balance: BDT "},
            {"পাওনা", "Due"}, {"জমা", "Received"}, {"পরিশোধ", "Payment"}, {"খরচ", "Expense"}, {"আয়", "Income"},
            {"মোট পাওনা: ৳ ", "Total Due: BDT "}, {"মোট জমা: ৳ ", "Total Received: BDT "},
            {"মোট ব্যবসায়িক আয়: ৳ ", "Total Business Income: BDT "}, {"মোট খরচ: ৳ ", "Total Expenses: BDT "},
            {"মোট পরিশোধ: ৳ ", "Total Payments: BDT "}, {"অবশিষ্ট পাওনা: ৳ ", "Outstanding: BDT "},
            {"ভাষা: ", "Language: "}, {"বাংলা", "Bangla"}, {"পণ্যের হিসাব — পরিমাণ × একক দাম", "Item Calculation — Quantity × Unit Price"},
            {"সর্বমোট: ৳ 0", "Grand Total: BDT 0"}, {"এই পণ্যের মোট: ৳ 0", "Item Total: BDT 0"},
            {"এই পণ্যের মোট: ৳ ", "Item Total: BDT "}, {"সর্বমোট: ৳ ", "Grand Total: BDT "},
            {"পার্টির নাম লিখুন", "Enter Party Name"}, {"পরিমাণ ও দাম লিখুন", "Enter Quantity and Price"},
            {"হিসাব সেভ হয়েছে", "Entry Saved"}, {"সেভ করা যায়নি: ", "Could not save: "},

            {"বিক্রয় সংরক্ষণ", "Save Sale"},
            {"নাম খুঁজুন", "Search Name"}, {"এখনো কোনো পার্টির হিসাব নেই", "No party accounts yet"},
            {"\nঅবশিষ্ট: ৳ ", "\nBalance: BDT "}, {" (পাওনা)", " (Due)"}, {" (অতিরিক্ত জমা)", " (Credit Balance)"},
            {" — হিসাব", " — Ledger"}, {"অতিরিক্ত জমা: ৳ ", "Excess Received: BDT "},
            {"+ এই পার্টির নতুন হিসাব", "+ New Entry for This Party"}, {"পার্টির তালিকায় ফিরুন", "Back to Party List"},
            {"ভাড়া, স্টক ও জামানত-সংযুক্ত হিসাব সংশ্লিষ্ট মডিউল থেকেই পরিচালনা করতে হবে।", "Rental, stock and deposit-linked entries must be managed from their respective modules."},
            {"রসিদ PDF তৈরি করা যায়নি: ", "Could not create receipt PDF: "},
            {"ভাড়া, স্টক ও জামানত-সংযুক্ত এন্ট্রি সংশ্লিষ্ট মডিউল থেকেই পরিবর্তন করতে হবে।", "Rental, stock and deposit-linked entries must be changed from their respective modules."},
            {"পার্টির নাম ও সঠিক পরিমাণ দিন", "Enter a party name and valid amount"}, {"হিসাব সম্পাদনা হয়েছে", "Entry Updated"},
            {"পরিবর্তন সংরক্ষণ হয়নি", "Changes were not saved"}, {"ব্যাকআপ ফাইল সংরক্ষণ হয়েছে", "Backup File Saved"},
            {"ব্যাকআপ সংরক্ষণ হয়নি: ", "Backup could not be saved: "}, {"ব্যাকআপ তৈরি করা যায়নি: ", "Could not create backup: "},
            {"ফাইল খোলা যায়নি", "Could not open file"}, {"ব্যাকআপ ফাইল ২০ MB-এর বেশি", "Backup file exceeds 20 MB"},
            {"এটি সঠিক হিসাব ব্যাকআপ নয়", "This is not a valid Hisab backup"},
            {"ব্যাকআপ ফিরিয়ে আনবেন?", "Restore this backup?"}, {"হ্যাঁ, ফিরিয়ে আনুন", "Yes, Restore"},
            {"ডেটা ফিরিয়ে আনা যায়নি; বর্তমান ডেটা অপরিবর্তিত আছে", "Restore failed; current data remains unchanged"},
            {"ব্যাকআপের ডেটা ফিরিয়ে আনা হয়েছে", "Backup data restored"}, {"ব্যাকআপ পড়া যায়নি: ", "Could not read backup: "},
            {" — মজুত: ", " — Stock: "}, {" | গড় ক্রয়মূল্য: ৳ ", " | Average Cost: BDT "},
            {" | মজুত মূল্য: ৳ ", " | Inventory Value: BDT "}, {"মোট মজুতের ক্রয়মূল্য: ৳ ", "Total Inventory Cost: BDT "},
            {"মোট স্টক ক্রয়: ৳ ", "Total Stock Purchases: BDT "}, {"মোট স্টক বিক্রয়: ৳ ", "Total Stock Sales: BDT "},
            {"বিক্রিত পণ্যের ক্রয়মূল্য: ৳ ", "Cost of Goods Sold: BDT "}, {"মোট স্থূল লাভ: ৳ ", "Gross Profit: BDT "},
            {"ড্যাশবোর্ডে ফিরুন", "Back to Dashboard"}, {"পণ্যের নাম, পরিমাণ ও সঠিক দাম দিন", "Enter product, quantity and valid price"},
            {"বিক্রি ও স্টক আপডেট হয়েছে", "Sale and stock updated"}, {"ক্রয় ও স্টক আপডেট হয়েছে", "Purchase and stock updated"},
            {"\nধরন: ", "\nType: "}, {"ইউনিট", "Unit"}, {"\nমাসিক ভাড়া: ৳ ", "\nMonthly Rent: BDT "},
            {" | জামানত: ৳ ", " | Deposit: BDT "}, {"\nভাড়া দেওয়ার শেষ তারিখ: ", "\nRent Due Day: "},
            {" | বকেয়া: ৳ ", " | Outstanding: BDT "}, {"ভাড়াটিয়ার নাম (খালি থাকলে লিখুন: খালি)", "Tenant Name (enter Vacant if empty)"},
            {"ইউনিটের ধরন: ফ্ল্যাট / দোকান", "Unit Type: Flat / Shop"},
            {"প্রতি মাসে ভাড়া দেওয়ার শেষ তারিখ (১-৩১)", "Monthly Rent Due Day (1-31)"},
            {"ভাড়াটিয়া ওঠার তারিখ (DD-MM-YYYY)", "Tenant Move-in Date (DD-MM-YYYY)"},
            {"চুক্তির বিবরণ / মেয়াদ (ঐচ্ছিক)", "Agreement Details / Term (optional)"},
            {"ভাড়াটিয়া/খালি, ইউনিট, ভাড়া ও ১-৩১ তারিখ দিন", "Enter tenant/vacancy, unit, rent and due day (1-31)"},
            {"সংরক্ষণ করা যায়নি", "Could not save"}, {"ইউনিট খালি", "Vacate Unit"},
            {"অবশিষ্ট জামানত ফেরত দিন", "Refund Remaining Deposit"}, {"জামানতের ইতিহাস", "Deposit History"},
            {"মোট ইউনিট: ", "Total Units: "}, {" | ভাড়াটিয়া আছে: ", " | Occupied: "}, {" | খালি: ", " | Vacant: "},
            {"বর্তমান জামানত (সংরক্ষিত): ৳ ", "Current Deposits Held: BDT "},
            {"সর্বমোট ধার্য ভাড়া: ৳ ", "Total Rent Charged: BDT "}, {"সর্বমোট ভাড়া আদায়: ৳ ", "Total Rent Collected: BDT "},
            {"সর্বমোট ভাড়ার বকেয়া: ৳ ", "Total Rent Outstanding: BDT "},
            {"ইউটিলিটি / সার্ভিস চার্জ ধার্য: ৳ ", "Utility / Service Charges: BDT "},
            {"ইউটিলিটি বিল আদায়: ৳ ", "Utility Bills Collected: BDT "}, {"ইউটিলিটি বিল বকেয়া: ৳ ", "Utility Bills Outstanding: BDT "},
            {"মেরামত ও অন্যান্য খরচ (সর্বমোট): ৳ ", "Repairs and Other Expenses (Total): BDT "},
            {"জামানত আয় নয়; এটি আলাদা সংরক্ষিত হিসাব।", "Deposits are not income; they are held separately as liabilities."},
            {"চার্জের নাম: বিদ্যুৎ / পানি / গ্যাস / সার্ভিস", "Charge Name: Electricity / Water / Gas / Service"},
            {"সঠিক ভাড়াটিয়া ও টাকা দিন", "Enter a valid tenant and amount"}, {"বিল যোগ হয়নি", "Charge was not added"},
            {"এই ইউনিটে কোনো বকেয়া ইউটিলিটি বিল নেই।", "No outstanding utility bills for this unit."},
            {"জমার পরিমাণ", "Payment Amount"}, {"জমা সংরক্ষণ", "Save Payment"},
            {"সঠিক জমার পরিমাণ দিন", "Enter a valid payment amount"}, {"খরচের পরিমাণ", "Expense Amount"},
            {"খরচ সংরক্ষণ", "Save Expense"}, {"সঠিক খরচ লিখুন", "Enter a valid expense"},
            {"নতুন মাসিক ভাড়া", "New Monthly Rent"}, {"ভাড়া বৃদ্ধি / কমানো", "Increase / Decrease Rent"},
            {"ভাড়া ও YYYY-MM মাস সঠিকভাবে দিন", "Enter a valid rent and effective month (YYYY-MM)"},
            {"বাসা ছাড়ার তারিখ (DD-MM-YYYY)", "Move-out Date (DD-MM-YYYY)"},
            {"নতুন ভাড়াটিয়ার নাম", "New Tenant Name"}, {"ওঠার তারিখ (DD-MM-YYYY)", "Move-in Date (DD-MM-YYYY)"},
            {"নতুন ভাড়াটিয়ার জামানত (টাকা)", "New Tenant Deposit (BDT)"},
            {"নাম, ওঠার তারিখ ও সঠিক জামানত দিন", "Enter name, move-in date and valid deposit"},
            {"আগের ভাড়াটিয়ার ইতিহাস নেই।", "No previous tenant history."},
            {"ফেরত দেওয়ার মতো জামানত নেই", "No deposit available to refund"}, {"ফেরত দেওয়ার পরিমাণ", "Refund Amount"},
            {"জামানত ফেরত", "Deposit Refund"}, {"ফেরতের অঙ্ক বর্তমান জামানতের বেশি হতে পারবে না", "Refund cannot exceed the current deposit"},
            {"ইউনিট নম্বর ও ১-৩১ তারিখ দিন", "Enter unit number and due day (1-31)"},
            {"এই ইউনিটে কোনো বকেয়া মাসিক ভাড়া নেই। আগে মাসের ভাড়া যোগ করুন।", "No monthly rent due for this unit. Post the monthly rent first."},
            {"জমার পরিমাণ (টাকা)", "Payment Amount (BDT)"}, {"ভাড়া জমা নিন", "Collect Rent"},
            {"মোট মাস: ", "Total Months: "}, {"মোট ভাড়া: ৳ ", "Total Rent: BDT "},
            {"মোট ভাড়া জমা: ৳ ", "Total Rent Received: BDT "}, {"মোট ভাড়া বকেয়া: ৳ ", "Total Rent Outstanding: BDT "},
            {"ভাড়ার স্টেটমেন্ট PDF সংরক্ষণ", "Save Rental Statement PDF"},
            {"এই মাসের ভাড়া যোগ হয়েছে", "This month's rent has been posted"},
            {"ভাড়া যোগ হয়নি: আগে যোগ হয়েছে বা ইউনিট খালি", "Rent not posted: already posted or unit is vacant"},
            {"অ্যাকাউন্ট স্থানান্তর", "Account Transfer"},
            {"ব্যাংক অ্যাকাউন্ট তৈরি", "Create Bank Account"},
            {"ব্যাংক অ্যাকাউন্ট", "Bank Account"},
            {"নগদ উত্তোলন", "Cash Withdrawal"},
            {"ব্যাংক প্রারম্ভিক জমা", "Bank Opening Deposit"},
            {"হাতে নগদ: ৳ ", "Cash in Hand: BDT "},
            {"মোট ব্যাংক ব্যালেন্স: ৳ ", "Total Bank Balance: BDT "},
            {"ব্যাংকে মোট: ৳ ", "Total in Bank: BDT "},
            {"ব্যাংক অ্যাকাউন্ট যোগ হয়েছে", "Bank Account Added"},
            {"নতুন ব্যাংক অ্যাকাউন্ট", "New Bank Account"},
            {"সঠিক অ্যাকাউন্টের নাম ও ব্যালেন্স দিন", "Enter a valid account name and balance"},
            {"এই ব্যাংক অ্যাকাউন্ট আগে থেকেই আছে", "This bank account already exists"},
            {"প্রারম্ভিক ব্যালেন্স", "Opening Balance"},
            {"অ্যাকাউন্ট সংরক্ষণ হয়নি", "Account could not be saved"},
            {"উৎস ও গন্তব্য আলাদা হতে হবে", "Source and destination must be different"},
            {"ব্যাংক অ্যাকাউন্ট নির্বাচন করুন", "Select a bank account"},
            {"অ্যাকাউন্টে পর্যাপ্ত ব্যালেন্স নেই", "Insufficient account balance"},
            {"নগদ ব্যালেন্স পর্যাপ্ত নেই", "Insufficient cash balance"},
            {"নগদ ও ব্যাংক", "Cash & Bank"},
            {"লেনদেন সংরক্ষণ হয়েছে", "Transaction Saved"},
            {"লেনদেন সংরক্ষণ হয়নি", "Transaction Could Not Be Saved"},
            {"PDF সেটিংস সংরক্ষণ হয়েছে", "PDF Settings Saved"},
            {"সেটিংস সংরক্ষণ হয়নি", "Settings Could Not Be Saved"},
            {"হাতে স্বাক্ষর", "Handwritten Signature"},
            {"স্বাক্ষর সংরক্ষণ", "Save Signature"},
            {"আগে স্বাক্ষর আঁকুন", "Draw your signature first"},
            {"হাতে আঁকা স্বাক্ষর সংরক্ষণ হয়েছে", "Handwritten Signature Saved"},
            {"স্বাক্ষর সংরক্ষণ হয়নি", "Signature Could Not Be Saved"},
            {"হিসাব লিখুন: 30 × 20 + 40", "Enter calculation: 30 × 20 + 40"},
            {"পণ্যের তালিকা (চলমান)", "Item List (continued)"},
            {"পণ্যের মোট", "Item Subtotal"},
            {"ছাড়", "Discount"},
            {"অতিরিক্ত চার্জ", "Additional Charge"},
            {"রাউন্ড-অফ", "Round-off"},
            {"অনুমোদিত স্বাক্ষর", "Authorized Signature"},
            {"মুছে ফেলুন লিখুন", "Type DELETE"},
            {"এই কাজ আর ফেরত নেওয়া যাবে না। নিশ্চিত করতে নিচে হুবহু “মুছে ফেলুন” লিখুন।", "This cannot be undone. Type exactly “DELETE” below to confirm."},
            {"স্থায়ীভাবে মুছতে পারিনি; আগের ডেটা অক্ষত আছে", "Could not permanently delete; existing data is unchanged"},
            {"ব্যাকআপ ২০ MB-এর বেশি; ডেটা ছোট করে আবার চেষ্টা করুন", "Backup exceeds 20 MB; reduce the data and try again"},
            {"হিসাব অ্যাপের ডেটা ব্যাকআপ", "Hisab App Data Backup"},
            {"ব্যাকআপ WhatsApp / Google Drive-এ শেয়ার করুন", "Share Backup via WhatsApp / Google Drive"},
            {"শেয়ার অপশন খোলা যায়নি: ", "Could not open sharing options: "},
            {"ব্যাকআপের গঠন সঠিক নয়; বর্তমান ডেটা নিরাপদ রাখা হয়েছে", "Invalid backup structure; current data has been kept safe"},
            {"পণ্যের নাম, পরিমাণ ও সঠিক দাম দিন; অতিরিক্ত বড় সংখ্যাও গ্রহণযোগ্য নয়", "Enter a product name, quantity and valid price; very large values are not accepted"},
            {"জমার পরিমাণ শূন্য বা তার বেশি দিন", "Payment must be zero or greater"},
            {"এখন জমার টাকা মোট বিক্রয়মূল্যের বেশি হতে পারবে না", "The payment cannot exceed the total sale amount"},
            {"বিক্রয়ের হিসাব সঠিকভাবে করা যাচ্ছে না; স্টক অপরিবর্তিত আছে", "Could not calculate the sale; stock remains unchanged"},
            {"স্টকের পরিমাণ বা গড় ক্রয়মূল্য সীমার বাইরে; কোনো পরিবর্তন সংরক্ষণ হয়নি", "Stock quantity or average cost is out of range; no changes were saved"},
            {"বাড়ি ভাড়া বিল — ", "Rental Charge — "},
            {"ইউটিলিটি বিল জমা — ", "Utility Payment — "},
            {"জামানত বৃদ্ধি / অতিরিক্ত গ্রহণ", "Deposit Increase / Additional Collection"},
            {"জামানত সমন্বয় / ফেরত", "Deposit Adjustment / Refund"},
            {"আগের ভাড়াটিয়ার জামানত আগে ফেরত দিন; তারপর নতুন ভাড়াটিয়া বসান", "Refund the previous tenant's deposit before assigning a new tenant"},
            {"আগে বর্তমান ভাড়াটিয়ার বাসা ছাড়ার তথ্য সংরক্ষণ করুন", "Save the current tenant's move-out details first"},
            {"নতুন ভাড়াটিয়ার জামানত গ্রহণ", "Collect New Tenant Deposit"},
            {"জামানত ফেরত — ", "Deposit Refund — "},
            {"বাড়ি ভাড়ার স্টেটমেন্ট", "Rental Statement"},
            {"PDF তৈরি করা যায়নি: ", "Could not generate PDF: "},
            {"মাসিক ভাড়া — ", "Monthly Rent — "},
            {"বকেয়া ভাড়া সংরক্ষণ হয়নি; আগের হিসাব অক্ষত আছে", "Rent dues were not saved; previous accounts remain unchanged"},
            {"ভাড়া সংরক্ষণ হয়নি; আগের হিসাব অক্ষত আছে", "Rent was not saved; previous accounts remain unchanged"},
            {"ভাড়ার PDF সংরক্ষণ হয়েছে", "Rental PDF Saved"},
            {"রসিদ / ইনভয়েস PDF সংরক্ষণ হয়েছে", "Receipt / Invoice PDF Saved"},
            {"PDF সংরক্ষণ করা যায়নি: ", "Could not save PDF: "},
            {"নতুন পার্টি / কাস্টমার", "New Party / Customer"},
            {"শুধু নাম লিখে সংরক্ষণ করুন। পরে এই পার্টির খাতায় লেনদেন যোগ করতে পারবেন।", "Save the name only. You can add transactions to this party ledger later."},
            {"পার্টি সংরক্ষণ হয়নি; আবার চেষ্টা করুন", "Party could not be saved; please try again"},
            {"স্টক বিক্রির স্থূল লাভ: ৳ ", "Gross Profit from Stock Sales: BDT "},
            {"কমলা", "Orange"},
            {"গাঢ় নীল", "Dark Blue"},
            {"ড্যাশবোর্ডের রং নির্বাচন করুন", "Choose Dashboard Color"},
            {"নগদ / ব্যাংক লেনদেন", "Cash / Bank Transaction"},
            {"পার্টির নাম ও টাকার পরিমাণ দিন", "Enter party name and amount"},
            {"\nএখনো কোনো লেনদেন নেই", "\nNo transactions yet"},
            {"প্রিয় ", "Dear "},
            {", আপনার বর্তমান ", ", your current "},
            {"পাওনা ", "due balance "},
            {"অতিরিক্ত জমা ", "credit balance "},
            {"। — হিসাব", ". — Hisab"},
            {"রিসাইকেল বিনে সরানো যায়নি; আগের ডেটা অক্ষত আছে", "Could not move to recycle bin; existing data is unchanged"},
            {"পার্টি / গ্রাহক: ", "Party / Customer: "},
            {"তারিখ: ", "Date: "},
            {"ধরন: ", "Type: "},
            {"বিবরণ: ", "Description: "},
            {"পরিবর্তন সংরক্ষণ হয়নি; আগের হিসাব অক্ষত আছে", "Changes were not saved; previous records remain unchanged"},
            {"হিসাব ফেরত আনা যায়নি; আগের ডেটা অক্ষত আছে", "Could not restore entry; existing data is unchanged"},
            {"বিক্রয়", "Sales"},
            {"ক্রয়", "Purchases"},
            {"আগের স্টক তথ্য বা লেনদেনের অঙ্ক সঠিক নয়; কোনো পরিবর্তন সংরক্ষণ হয়নি", "Previous stock data or transaction amount is invalid; no changes were saved"},
            {"স্টক বিক্রির টাকা জমা: ", "Stock sale payment received: "},
            {"লেনদেন সংরক্ষণ হয়নি; আগের স্টক ও হিসাব অপরিবর্তিত আছে", "Transaction was not saved; previous stock and accounts remain unchanged"},
            {"লেনদেন সংরক্ষণ হয়নি: ", "Transaction could not be saved: "},
            {"ভাড়াটিয়া, ভবন, ইউনিট, সঠিক ভাড়া/জামানত ও ১-৩১ তারিখ দিন। খালি ইউনিটে জামানত ০ হতে হবে।", "Enter tenant, building, unit, valid rent/deposit and due day (1–31). Deposit must be 0 for a vacant unit."},
            {"এই ভবনে এই ইউনিট নম্বরটি আগে যোগ করা হয়েছে", "This unit number has already been added to this building"},
            {"নতুন ইউনিটে প্রাথমিক ভাড়াটিয়ার জামানত গ্রহণ", "Collect the initial tenant deposit for the new unit"},
            {"ইউনিট ও জামানত সংরক্ষণ হয়নি; আগের তথ্য অক্ষত আছে", "Unit and deposit were not saved; previous data is unchanged"},
            {"বিল সংরক্ষণ হয়নি; আগের হিসাব অক্ষত আছে", "Bill was not saved; previous accounts remain unchanged"},
            {"জমা সংরক্ষণ হয়নি; আগের হিসাব অক্ষত আছে", "Payment was not saved; previous accounts remain unchanged"},
            {"জমা সংরক্ষণ হয়নি", "Payment could not be saved"},
            {"খরচ সংরক্ষণ হয়নি; আগের হিসাব অক্ষত আছে", "Expense was not saved; previous accounts remain unchanged"},
            {"খরচ যোগ হয়নি", "Expense was not added"},
            {"সঠিক অঙ্ক দিন", "Enter a valid amount"},
            {"জামানত ও লেনদেন সংরক্ষণ হয়নি; আগের তথ্য অক্ষত আছে", "Deposit and transaction were not saved; previous data is unchanged"},
            {"ভাড়া পরিবর্তন সংরক্ষণ হয়নি; আগের তথ্য অক্ষত আছে", "Rent change was not saved; previous data is unchanged"},
            {"ভাড়া পরিবর্তন হয়নি", "Rent was not changed"},
            {"খালি করুন", "Vacate"},
            {"ইউনিট খালি করার তথ্য সংরক্ষণ হয়নি; আগের তথ্য অক্ষত আছে", "Vacating details were not saved; previous data is unchanged"},
            {"ইউনিট খালি করা যায়নি", "Could not vacate unit"},
            {"নতুন ভাড়াটিয়ার জামানত", "New Tenant Deposit"},
            {"নতুন ভাড়াটিয়া ও জামানত সংরক্ষণ হয়নি; আগের তথ্য অক্ষত আছে", "New tenant and deposit were not saved; previous data is unchanged"},
            {"নতুন ভাড়াটিয়া সংরক্ষণ হয়নি", "New tenant could not be saved"},
            {"তথ্য নেই", "No information"},
            {"জামানত পরিবর্তন", "Deposit Adjustment"},
            {"ফেরত সংরক্ষণ", "Save Refund"},
            {"ফেরত", "Refund"},
            {"এর জামানত ফেরত (আয় নয়, দায় পরিশোধ)", "deposit refund (not income; liability repayment)"},
            {"জামানত ফেরত সংরক্ষণ হয়নি; আগের হিসাব ও জামানত অক্ষত আছে", "Deposit refund was not saved; previous accounts and deposit remain unchanged"},
            {"জামানত ফেরত সংরক্ষণ হয়নি", "Deposit refund could not be saved"},
            {"আপডেট", "Update"},
            {"ভবনের নাম, ইউনিট নম্বর ও ১-৩১ তারিখ দিন", "Enter building name, unit number and due day (1–31)"},
            {"এই ভবনে এই ইউনিট নম্বরটি আগে থেকেই আছে", "This unit already exists in this building"},
            {"এই ভবন/ইউনিটে পুরোনো ভাড়ার হিসাব আছে। হিসাবের ইতিহাস ঠিক রাখতে ভবন বা ইউনিট নম্বর বদলানো যাবে না; নতুন ইউনিট যোগ করুন।", "This building/unit has rental history. To preserve the ledger, its building or unit number cannot be changed; add a new unit instead."},
            {"ইউনিটের পরিবর্তন সংরক্ষণ হয়নি; আগের তথ্য অক্ষত আছে", "Unit changes were not saved; previous data is unchanged"},
            {"আপডেট হয়নি", "Update failed"},
            {"ভাড়া জমা — ", "Rent Payment — "},
            {"ভাড়া জমা সংরক্ষণ হয়নি; আগের হিসাব অক্ষত আছে", "Rent payment was not saved; previous accounts remain unchanged"},
            {"ভাড়া জমা সংরক্ষণ হয়েছে", "Rent payment saved"},
            {"জমা সংরক্ষণ করা যায়নি", "Payment could not be saved"},
            {"নতুন বাড়ি / ফ্ল্যাট / দোকান", "New Building / Flat / Shop"},
            {"বাতিল", "Cancel"},
            {"সংরক্ষণ", "Save"},
            {"ভাড়াটিয়া, ভবন, ইউনিট, সঠিক ভাড়া/জামানত ও ১-৩১ তারিখ দিন। খালি ইউনিটে জামানত ০ হতে হবে।", "Enter tenant, building, unit, valid rent/deposit and a due day from 1–31. Deposit must be 0 for vacant units."},
            {"এই ভবনে এই ইউনিট নম্বরটি আগে যোগ করা হয়েছে", "This unit number has already been added to this building"},
            {"ইউনিট ও জামানত সংরক্ষণ হয়নি; আগের তথ্য অক্ষত আছে", "Unit and deposit were not saved; previous data is unchanged"},
            {"সংরক্ষণ করা যায়নি", "Could not save"},
            {"ইউনিট খালি", "Vacant Unit"},
            {"এই মাসের ভাড়া বকেয়া যোগ করুন", "Post This Month's Rent Due"},
            {"ভাড়াটিয়ার খাতা দেখুন", "View Tenant Ledger"},
            {"ভাড়া জমা নিন (আংশিক / পূর্ণ)", "Collect Rent (Partial / Full)"},
            {"ভাড়া রিপোর্ট", "Rent Report"},
            {"ইউটিলিটি / সার্ভিস চার্জ যোগ", "Add Utility / Service Charge"},
            {"মেরামত / বাড়ির খরচ যোগ", "Add Repair / Property Expense"},
            {"অগ্রিম / জামানত পরিবর্তন", "Adjust Advance / Deposit"},
            {"ভাড়া বৃদ্ধি / কমান", "Increase / Decrease Rent"},
            {"ভাড়াটিয়া চলে গেছেন / ইউনিট খালি করুন", "Tenant Moved Out / Vacate Unit"},
            {"ইউনিটের তথ্য সম্পাদনা", "Edit Unit Details"},
            {"জামানতের লেনদেন দেখুন", "View Deposit Transactions"},
            {"ইউটিলিটি বিল জমা নিন", "Collect Utility Bill"},
            {"ভাড়াটিয়া ওঠা-নামার ইতিহাস", "Tenant Move-in / Move-out History"},
            {"জামানত ফেরত দিন", "Refund Deposit"},
            {"সমগ্র বাড়িভাড়া রিপোর্ট", "Full Rental Portfolio Report"},
            {"ভাড়াটিয়ার বিল যোগ", "Add Tenant Charge"},
            {"হিসাবে যোগ", "Add to Ledger"},
            {"সঠিক ভাড়াটিয়া ও টাকা দিন", "Enter a valid tenant and amount"},
            {"বিল সংরক্ষণ হয়নি; আগের হিসাব অক্ষত আছে", "Bill was not saved; previous data is unchanged"},
            {"বিল যোগ হয়নি", "Could not add bill"},
            {"এই ইউনিটে কোনো বকেয়া ইউটিলিটি বিল নেই।", "There are no outstanding utility bills for this unit."},
            {"জমার পরিমাণ", "Payment amount"},
            {"জমা সংরক্ষণ", "Save Payment"},
            {"সঠিক জমার পরিমাণ দিন", "Enter a valid payment amount"},
            {"জমা সংরক্ষণ হয়নি; আগের হিসাব অক্ষত আছে", "Payment was not saved; previous data is unchanged"},
            {"জমা সংরক্ষণ হয়নি", "Payment could not be saved"},
            {"খরচের বিবরণ: মেরামত / রং / পরিষ্কার", "Expense details: repair / paint / cleaning"},
            {"খরচের পরিমাণ", "Expense amount"},
            {"বাড়ির মেরামত / খরচ", "Property Repair / Expense"},
            {"খরচ সংরক্ষণ", "Save Expense"},
            {"সঠিক খরচ লিখুন", "Enter a valid expense"},
            {"খরচ সংরক্ষণ হয়নি; আগের হিসাব অক্ষত আছে", "Expense was not saved; previous data is unchanged"},
            {"খরচ যোগ হয়নি", "Could not add expense"},
            {"নতুন মোট অগ্রিম / জামানত", "New Total Advance / Deposit"},
            {"অগ্রিম / জামানত", "Advance / Deposit"},
            {"সঠিক অঙ্ক দিন", "Enter a valid amount"},
            {"জামানত ও লেনদেন সংরক্ষণ হয়নি; আগের তথ্য অক্ষত আছে", "Deposit and transaction were not saved; previous data is unchanged"},
            {"ভাড়া বৃদ্ধি / কমানো", "Increase / Decrease Rent"},
            {"নতুন মাসিক ভাড়া", "New Monthly Rent"},
            {"কার্যকর হওয়ার মাস (YYYY-MM), যেমন 2026-12", "Effective month (YYYY-MM), e.g. 2026-12"},
            {"ভাড়া ও YYYY-MM মাস সঠিকভাবে দিন", "Enter a valid rent and month in YYYY-MM format"},
            {"ভাড়া পরিবর্তন সংরক্ষণ হয়নি; আগের তথ্য অক্ষত আছে", "Rent change was not saved; previous data is unchanged"},
            {"ভাড়া পরিবর্তন হয়নি", "Rent was not changed"},
            {"জামানতের লেনদেন", "Deposit Transactions"},
            {"বর্তমান জামানত: ৳ ", "Current Deposit: BDT "},
            {"এখনো জামানতের পরিবর্তনের ইতিহাস নেই।", "No deposit adjustment history yet."},
            {"বর্তমান জামানত (সংরক্ষিত): ৳ ", "Current Deposit (held): BDT "},
            {"সর্বমোট ধার্য ভাড়া: ৳ ", "Total Rent Charged: BDT "},
            {"সর্বমোট ভাড়া আদায়: ৳ ", "Total Rent Collected: BDT "},
            {"সর্বমোট ভাড়ার বকেয়া: ৳ ", "Total Rent Outstanding: BDT "},
            {"ইউটিলিটি / সার্ভিস চার্জ ধার্য: ৳ ", "Utility / Service Charges Billed: BDT "},
            {"ইউটিলিটি বিল আদায়: ৳ ", "Utility Bills Collected: BDT "},
            {"ইউটিলিটি বিল বকেয়া: ৳ ", "Outstanding Utility Bills: BDT "},
            {"মেরামত ও অন্যান্য খরচ (সর্বমোট): ৳ ", "Repairs & Other Expenses (Total): BDT "},
            {"জামানত আয় নয়; এটি আলাদা সংরক্ষিত হিসাব।", "Deposits are not income; they are tracked separately."},
            {"মোট: ৳ ", "Total: BDT "},
            {"হিসাব লিখুন: 30 × 20 + 40", "Enter a calculation: 30 × 20 + 40"},
            {"− পণ্যের বিস্তারিত বন্ধ করুন", "− Hide item details"},
            {"পণ্য: ", "Items: "},
            {"ছাড়: ", "Discount: "},
            {"VAT: ", "VAT: "},
            {"মুছে ফেলুন লিখুন", "Type DELETE to confirm"},
            {"ফেরত দেওয়ার পরিমাণ", "Refund amount"},
            {"আগের ভাড়াটিয়ার ইতিহাস", "Previous Tenant History"},
            {"অবশিষ্ট জামানত ফেরত দিন", "Refund Remaining Deposit"},
            {"জামানতের ইতিহাস", "Deposit History"},
            {"নতুন ভাড়াটিয়া বসান", "Assign New Tenant"},
            {"ভাড়াটিয়া ওঠার তারিখ (DD-MM-YYYY)", "Tenant Move-in Date (DD-MM-YYYY)"},
            {"চুক্তির বিবরণ / মেয়াদ (ঐচ্ছিক)", "Agreement Details / Term (optional)"},
            {"ভাড়াটিয়ার নাম (খালি থাকলে লিখুন: খালি)", "Tenant Name (enter Vacant if unoccupied)"},
            {"ইউনিটের ধরন: ফ্ল্যাট / দোকান", "Unit Type: Flat / Shop"},
            {"প্রতি মাসে ভাড়া দেওয়ার শেষ তারিখ (১-৩১)", "Monthly Rent Due Day (1–31)"},
            {"আগের ভাড়াটিয়ার লেনদেন ও বকেয়া মুছে যাবে না। ওঠা-নামার ইতিহাসে সংরক্ষিত হবে।", "Previous tenant transactions and dues will be retained in move-in / move-out history."},
            {"এই মাসের বাড়ির খরচ: ৳ ", "Property Expenses This Month: BDT "},
            {"এই বছরের খরচ: ৳ ", "Expenses This Year: BDT "},
            {"এই বছরের ভাড়া আদায় থেকে বাড়ির খরচ বাদে: ৳ ", "This Year's Rent Collected Less Property Expenses: BDT "},
            {"শুধু নাম লিখে সংরক্ষণ করুন। পরে এই পার্টির খাতায় লেনদেন যোগ করতে পারবেন।", "Save just the name. You can add transactions to this party ledger later."},
            {"ড্যাশবোর্ডের রং নির্বাচন করুন", "Choose Dashboard Color"},
            {"নতুন ব্যাংক অ্যাকাউন্ট", "New Bank Account"},
            {"সঠিক অ্যাকাউন্টের নাম ও ব্যালেন্স দিন", "Enter a valid account name and balance"},
            {"এই ব্যাংক অ্যাকাউন্ট আগে থেকেই আছে", "This bank account already exists"},
            {"ব্যাংক অ্যাকাউন্ট যোগ হয়েছে", "Bank account added"},
            {"অ্যাকাউন্ট সংরক্ষণ হয়নি", "Account could not be saved"},
            {"নগদ / ব্যাংক লেনদেন", "Cash / Bank Transaction"},
            {"উৎস ও গন্তব্য আলাদা হতে হবে", "Source and destination must be different"},
            {"ব্যাংক অ্যাকাউন্ট নির্বাচন করুন", "Select a bank account"},
            {"অ্যাকাউন্টে পর্যাপ্ত ব্যালেন্স নেই", "Insufficient account balance"},
            {"নগদ ব্যালেন্স পর্যাপ্ত নেই", "Insufficient cash balance"},
            {"লেনদেন সংরক্ষণ হয়েছে", "Transaction saved"},
            {"লেনদেন সংরক্ষণ হয়নি", "Transaction could not be saved"},
            {"হাতে স্বাক্ষর", "Drawn Signature"},
            {"স্বাক্ষর সংরক্ষণ", "Save Signature"},
            {"আগে স্বাক্ষর আঁকুন", "Draw your signature first"},
            {"হাতে আঁকা স্বাক্ষর সংরক্ষণ হয়েছে", "Handwritten signature saved"},
            {"হাতে আঁকা স্বাক্ষর সংরক্ষণ হয়নি", "Handwritten signature could not be saved"},
            {"রসিদ PDF তৈরি করা যায়নি: ", "Could not create receipt PDF: "},
            {"হিসাব সম্পাদনা হয়েছে", "Entry updated"},
            {"পরিবর্তন সংরক্ষণ হয়নি; আগের হিসাব অক্ষত আছে", "Changes were not saved; previous data is unchanged"},
            {"হিসাব ফেরত আনা যায়নি; আগের ডেটা অক্ষত আছে", "Could not restore entry; previous data is unchanged"},
            {"স্থায়ীভাবে মুছবেন?", "Delete Permanently?"},
            {"এই কাজ আর ফেরত নেওয়া যাবে না। নিশ্চিত করতে নিচে হুবহু “মুছে ফেলুন” লিখুন।", "This cannot be undone. Type DELETE exactly below to confirm."},
            {"স্থায়ীভাবে মুছতে পারিনি; আগের ডেটা অক্ষত আছে", "Could not permanently delete; previous data is unchanged"},
            {"ব্যাকআপ ২০ MB-এর বেশি; ডেটা ছোট করে আবার চেষ্টা করুন", "Backup exceeds 20 MB; reduce the data and try again"},
            {"ব্যাকআপ তৈরি করা যায়নি: ", "Could not create backup: "},
            {"শেয়ার অপশন খোলা যায়নি: ", "Could not open sharing options: "},
            {"ব্যাকআপ ফিরিয়ে আনবেন?", "Restore Backup?"},
            {"ডেটা ফিরিয়ে আনা যায়নি; বর্তমান ডেটা অপরিবর্তিত আছে", "Could not restore data; current data is unchanged"},
            {"ব্যাকআপের ডেটা ফিরিয়ে আনা হয়েছে", "Backup data restored"},
            {"ব্যাকআপ পড়া যায়নি: ", "Could not read backup: "},
            {"বিক্রি সংরক্ষণ", "Save Sale"},
            {"ক্রয় সংরক্ষণ", "Save Purchase"},
            {"বিক্রি ও স্টক আপডেট হয়েছে", "Sale and stock updated"},
            {"ক্রয় ও স্টক আপডেট হয়েছে", "Purchase and stock updated"},
            {"পণ্যের নাম, পরিমাণ ও সঠিক দাম দিন; অতিরিক্ত বড় সংখ্যাও গ্রহণযোগ্য নয়", "Enter a product name, quantity and valid price; excessively large values are not accepted"},
            {"জমার পরিমাণ শূন্য বা তার বেশি দিন", "Enter a payment amount of zero or more"},
            {"আগের স্টক তথ্য বা লেনদেনের অঙ্ক সঠিক নয়; কোনো পরিবর্তন সংরক্ষণ হয়নি", "Previous stock data or transaction amount is invalid; no changes were saved"},
            {"এখন জমার টাকা মোট বিক্রয়মূল্যের বেশি হতে পারবে না", "Payment now cannot exceed the total sale amount"},
            {"বিক্রয়ের হিসাব সঠিকভাবে করা যাচ্ছে না; স্টক অপরিবর্তিত আছে", "Could not calculate the sale correctly; stock is unchanged"},
            {"স্টকের পরিমাণ বা গড় ক্রয়মূল্য সীমার বাইরে; কোনো পরিবর্তন সংরক্ষণ হয়নি", "Stock quantity or average purchase cost is out of range; no changes were saved"},
            {"লেনদেন সংরক্ষণ হয়নি; আগের স্টক ও হিসাব অপরিবর্তিত আছে", "Transaction was not saved; previous stock and accounts are unchanged"},
            {"ভাড়াটিয়া চলে গেছেন / ইউনিট খালি", "Tenant Moved Out / Vacate Unit"},
            {"খালি করুন", "Vacate Unit"},
            {"ইউনিট খালি করার তথ্য সংরক্ষণ হয়নি; আগের তথ্য অক্ষত আছে", "Vacate details were not saved; previous data is unchanged"},
            {"ইউনিট খালি করা যায়নি", "Could not vacate unit"},
            {"আগের ভাড়াটিয়ার জামানত আগে ফেরত দিন; তারপর নতুন ভাড়াটিয়া বসান", "Refund the previous tenant's deposit before assigning a new tenant"},
            {"আগে বর্তমান ভাড়াটিয়ার বাসা ছাড়ার তথ্য সংরক্ষণ করুন", "Save the current tenant's move-out details first"},
            {"নাম, ওঠার তারিখ ও সঠিক জামানত দিন", "Enter a name, move-in date and valid deposit"},
            {"নতুন ভাড়াটিয়া ও জামানত সংরক্ষণ হয়নি; আগের তথ্য অক্ষত আছে", "New tenant and deposit were not saved; previous data is unchanged"},
            {"নতুন ভাড়াটিয়া সংরক্ষণ হয়নি", "New tenant could not be saved"},
            {"ফেরত দেওয়ার মতো জামানত নেই", "There is no deposit available to refund"},
            {"জামানত ফেরত", "Refund Deposit"},
            {"ফেরত সংরক্ষণ", "Save Refund"},
            {"ফেরতের অঙ্ক বর্তমান জামানতের বেশি হতে পারবে না", "Refund amount cannot exceed the current deposit"},
            {"জামানত ফেরত সংরক্ষণ হয়নি; আগের হিসাব ও জামানত অক্ষত আছে", "Deposit refund was not saved; previous accounts and deposit are unchanged"},
            {"জামানত ফেরত সংরক্ষণ হয়নি", "Deposit refund could not be saved"},
            {"ইউনিটের তথ্য সম্পাদনা", "Edit Unit Details"},
            {"আপডেট", "Update"},
            {"ভবনের নাম, ইউনিট নম্বর ও ১-৩১ তারিখ দিন", "Enter a building name, unit number and due day from 1–31"},
            {"এই ভবনে এই ইউনিট নম্বরটি আগে থেকেই আছে", "This unit number already exists in this building"},
            {"ইউনিটের পরিবর্তন সংরক্ষণ হয়নি; আগের তথ্য অক্ষত আছে", "Unit changes were not saved; previous data is unchanged"},
            {"আপডেট হয়নি", "Update failed"},
            {"এই ইউনিটে কোনো বকেয়া মাসিক ভাড়া নেই। আগে মাসের ভাড়া যোগ করুন।", "There is no outstanding monthly rent for this unit. Post the monthly rent first."},
            {"ভাড়া জমা নিন", "Collect Rent"},
            {"ভাড়া জমা সংরক্ষণ হয়নি; আগের হিসাব অক্ষত আছে", "Rent payment was not saved; previous accounts are unchanged"},
            {"ভাড়া জমা সংরক্ষণ হয়েছে", "Rent payment saved"},
            {"জমা সংরক্ষণ করা যায়নি", "Payment could not be saved"},
            {"বকেয়া ভাড়া সংরক্ষণ হয়নি; আগের হিসাব অক্ষত আছে", "Rent due was not saved; previous data is unchanged"},
            {"ভাড়া সংরক্ষণ হয়নি; আগের হিসাব অক্ষত আছে", "Rent was not saved; previous data is unchanged"},
            {"এই মাসের ভাড়া যোগ হয়েছে", "This month's rent was posted"},
            {"ভাড়া যোগ হয়নি: আগে যোগ হয়েছে বা ইউনিট খালি", "Rent was not posted: it may already exist or the unit is vacant"},
            {"দোকানের স্টক ব্যবস্থাপনা", "Shop Inventory Management"},
            {"+ নতুন পণ্য / মাল ক্রয়", "+ Add Product / Purchase Stock"},
            {"পণ্য বিক্রি করুন", "Sell Product"},
            {"এখনো কোনো পণ্য যোগ করা হয়নি", "No products have been added yet"},
            {"মজুত: ", "In Stock: "},
            {"গড় ক্রয়মূল্য: ৳ ", "Average Cost: BDT "},
            {"মজুত মূল্য: ৳ ", "Stock Value: BDT "},
            {"মোট মজুতের ক্রয়মূল্য: ৳ ", "Total Inventory Cost: BDT "},
            {"মোট স্টক ক্রয়: ৳ ", "Total Stock Purchases: BDT "},
            {"মোট স্টক বিক্রয়: ৳ ", "Total Stock Sales: BDT "},
            {"বিক্রিত পণ্যের ক্রয়মূল্য: ৳ ", "Cost of Goods Sold: BDT "},
            {"মোট স্থূল লাভ: ৳ ", "Total Gross Profit: BDT "},
            {"ক্রয়-বিক্রয়ের সাম্প্রতিক ইতিহাস", "Recent Purchase / Sales History"},
            {"এখনো কোনো ক্রয়-বিক্রয়ের ইতিহাস নেই", "No purchase or sales history yet"},
            {"পণ্যের নাম", "Product Name"},
            {"পরিমাণ", "Quantity"},
            {"প্রতি পিস বিক্রয়মূল্য", "Selling Price per Unit"},
            {"প্রতি পিস ক্রয়মূল্য", "Purchase Cost per Unit"},
            {"ক্রেতা / সরবরাহকারী (ঐচ্ছিক)", "Buyer / Supplier (optional)"},
            {"এখন জমা (খালি রাখলে পুরো বাকি)", "Payment Now (leave blank for full credit)"},
            {"পণ্য বিক্রি", "Sell Product"},
            {"পণ্য ক্রয়", "Purchase Product"},
            {"বিক্রি সংরক্ষণ", "Save Sale"},
            {"ক্রয় সংরক্ষণ", "Save Purchase"},
            {"বিক্রয় ও ক্রয়মূল্য: ", "Sale and Cost: "},
            {"পণ্যের মোট: ৳ ", "Items Total: BDT "},
            {"− পণ্যের বিস্তারিত বন্ধ করুন", "− Hide Item Details"},
            {"+ পণ্যের বিস্তারিত (ঐচ্ছিক)", "+ Item Details (optional)"},
            {"+ আরেকটি পণ্য যোগ করুন", "+ Add Another Product"},
            {"ছাড়ের টাকা (ঐচ্ছিক)", "Discount Amount (optional)"},
            {"VAT / Tax (%) (ঐচ্ছিক)", "VAT / Tax (%) (optional)"},
            {"অতিরিক্ত চার্জের টাকা (ঐচ্ছিক)", "Additional Charge (optional)"},
            {"Round-off সমন্বয় (+/- টাকা, ঐচ্ছিক)", "Round-off Adjustment (+/- amount, optional)"},
            {"বিবরণ (ঐচ্ছিক)", "Description (optional)"},
            {"প্রয়োজনে ছোট বিবরণ লিখুন", "Enter a short description if needed"},
            {"হিসাব সেভ করুন", "Save Entry"},
            {"নিচের জায়গায় আঙুল দিয়ে নিজের স্বাক্ষর আঁকুন", "Draw your signature below with your finger"},
            {"আবার আঁকুন", "Clear and Redraw"},
            {"স্বাক্ষর সংরক্ষণ হয়নি", "Signature could not be saved"},
            {"আগের ভাড়াটিয়ার ইতিহাস নেই।", "No previous tenant history."},
            {"জামানত — ", "Deposit — "},
            {"আগের: ৳ ", "Previous: BDT "},
            {"নতুন: ৳ ", "New: BDT "},
            {"ঠিক আছে", "OK"},
            {"ডেটা সংরক্ষণ হয়নি", "Data could not be saved"},
            {"হিসাব পাওয়া যায়নি", "Record not found"},
            {"স্থানান্তর: ", "Transfer: "},
            {"সর্বমোট: ৳ ", "Grand Total: BDT "},
            {"পণ্য: ", "Items: "},
            {"ছাড়: ", "Discount: "},
            {"বিক্রয়", "Sale"},
            {"ক্রয়", "Purchase"},
            {"পরিমাণ: ", "Quantity: "},
            {"মোট: ৳ ", "Total: BDT "},
            {"পণ্যের ক্রয়মূল্য: ৳ ", "Cost of Goods: BDT "},
            {"লাভ: ৳ ", "Profit: BDT "},
            {"বর্তমান ভাড়াটিয়া: ", "Current Tenant: "},
            {"ওঠার তারিখ: ", "Move-in Date: "},
            {"উঠেছেন: ", "Moved in: "},
            {"ছেড়েছেন: ", "Moved out: "},
            {"ভাড়া: ৳ ", "Rent: BDT "},
            {"জামানত: ৳ ", "Deposit: BDT "},
            {"চুক্তি: ", "Agreement: "},
            {"বাসা ছাড়ার সময় মোট বকেয়া (ভাড়া/চার্জ): ৳ ", "Total Outstanding at Move-out (Rent/Charges): BDT "},
            {"ভাড়া — ধার্য: ৳ ", "Rent — Due: BDT "},
            {"চার্জ — ধার্য: ৳ ", "Charges — Due: BDT "},
            {"জমা: ৳ ", "Paid: BDT "},
            {"বাকি: ৳ ", "Balance: BDT "},
            {"ভাড়া ধার্য ৳ ", "Rent Due BDT "},
            {"জমা ৳ ", "Paid BDT "},
            {"বাকি ৳ ", "Balance BDT "},
            {"চার্জ: ", "Charge: "},
            {"ধার্য ৳ ", "Due BDT "},
            {"পণ্যের মোট: ৳ ", "Items Total: BDT "},
            {"এই মাস (", "This Month ("},
            {"এই বছর (", "This Year ("},
            {") — ভাড়া ধার্য: ৳ ", ") — Rent Due: BDT "},
            {" | আদায়: ৳ ", " | Collected: BDT "},
            {" | বকেয়া: ৳ ", " | Outstanding: BDT "},
            {"এই মাসের বাড়ির খরচ: ৳ ", "Property Expenses This Month: BDT "},
            {" | এই বছরের খরচ: ৳ ", " | This Year\u2019s Expenses: BDT "},
            {"এই বছরের ভাড়া আদায় থেকে বাড়ির খরচ বাদে: ৳ ", "This Year\u2019s Rent Collected Minus Property Expenses: BDT "},
            {"সংরক্ষণ হয়নি", "Could not save"},
            {" | আগের: ৳ ", " | Previous: BDT "},
            {" | নতুন: ৳ ", " | New: BDT "},
            {" | পরিবর্তন: ", " | Change: "},
            {"ভাড়াটিয়া: ", "Tenant: "},
            {" | ইউনিট: ", " | Unit: "},
            {" | উঠেছেন: ", " | Moved in: "},
            {" | ছেড়েছেন: ", " | Moved out: "},
            {" | ভাড়া: ৳ ", " | Rent: BDT "},
            {" | জামানত: ৳ ", " | Deposit: BDT "},
            {" | চুক্তি: ", " | Agreement: "},
            {" | ভাড়া ৳ ", " | Rent BDT "},
            {" | জমা ৳ ", " | Paid BDT "},
            {" | বাকি ৳ ", " | Due BDT "},
            {"আপডেট চেক করুন", "Check for Updates"},
            {"পার্টির স্টেটমেন্ট PDF / WhatsApp", "Party Statement PDF / WhatsApp"},
            {"=  ফলাফল বসান", "= Insert Result"},
            {"মুছে ফেলুন লিখুন", "Type DELETE"},
            {"এই কাজ আর ফেরত নেওয়া যাবে না। নিশ্চিত করতে নিচে হুবহু “মুছে ফেলুন” লিখুন।", "This action cannot be undone. Type exactly “DELETE” below to confirm."},
            {"আমার হিসাব — পার্টি স্টেটমেন্ট", "My Hisab — Party Statement"},
            {"পার্টি: ", "Party: "},
            {"চলতি ব্যালেন্স: ৳ ", "Running balance: BDT "},
            {"মোট পাওনা এন্ট্রি: ৳ ", "Total due entries: BDT "},
            {"মোট জমা / পরিশোধ: ৳ ", "Total received / paid: BDT "},
            {"বর্তমান ব্যালেন্স: ৳ ", "Current balance: BDT "},
            {"অনুমোদিত স্বাক্ষর: ", "Authorized signature: "},
            {"অনুমোদিত স্বাক্ষর", "Authorized signature"},
            {"এখনো কোনো লেনদেন নেই।", "No transactions yet."},
            {"আমার হিসাব থেকে তৈরি", "Generated by My Hisab"},
            {"পৃষ্ঠা ", "Page "},
            {"অতিরিক্ত জমা", "Credit balance"},
            {"চলতি ব্যালেন্স: ৳ ", "Running balance: BDT "},
            {"মোট পাওনা এন্ট্রি: ৳ ", "Total due entries: BDT "},
            {"মোট জমা / পরিশোধ: ৳ ", "Total received / paid: BDT "},
            {"বর্তমান ব্যালেন্স: ৳ ", "Current balance: BDT "},
            {"অনুমোদিত স্বাক্ষর: ", "Authorized signature: "},
            {"আমার হিসাব — পার্টি স্টেটমেন্ট", "My Hisab — Party Statement"},
            {"পার্টি: ", "Party: "},
            {"এখনো কোনো লেনদেন নেই।", "No transactions yet."},
            {"মাসিক ভাড়া: ৳ ", "Monthly rent: BDT "},
            {"ভাড়া দেওয়ার শেষ তারিখ: ", "Rent due day: "},
            {"বকেয়া: ৳ ", "Outstanding: BDT "},
            {"ভাড়া ধার্য: ৳ ", "Rent due: BDT "},
            {"আদায়: ৳ ", "Collected: BDT "},
            {"আমার হিসাব অ্যাপ থেকে পাঠানো ", "Sent from My Hisab App "},
            {"ইনস্টল করা অ্যাপটি পাবলিক আপডেট তালিকার চেয়ে নতুন।", "Your installed app is newer than the public update feed."},
            {"নতুন সংস্করণের ডাউনলোড লিংক এখনো পাওয়া যায়নি।", "The download link for the newer version is not available yet."},
            {"আপডেটের ডাউনলোড লিংক বা SHA-256 যাচাই তথ্য সঠিক নয়।", "The update download link or SHA-256 verification data is invalid."},
            {"আমার হিসাব", "My Hisab"},
            {"ফোনের PIN ব্যবহার করুন", "Use Phone PIN"},
            {"পার্টির স্টেটমেন্ট PDF সংরক্ষণ হয়েছে", "Party statement PDF saved"},
            {"পার্টির স্টেটমেন্ট PDF", "Party Statement PDF"},
            {"WhatsApp-এ পাঠান অথবা অন্য অ্যাপে শেয়ার করুন", "Share via WhatsApp or another app"},
            {"PDF শেয়ার অপশন খোলা যায়নি: ", "Could not open PDF sharing: "},
            {"ব্যালেন্স: ৳ 0", "Balance: BDT 0"},
            {"ক্যাশ ইন — নগদ টাকা যোগ", "Cash In — Add Cash"},
            {"ক্যাশ আউট — নগদ টাকা খরচ", "Cash Out — Cash Expense"},
            {"হাতে নগদ পর্যাপ্ত নেই", "Insufficient cash on hand"},
            {"নগদ লেনদেন সংরক্ষণ হয়েছে", "Cash transaction saved"},
            {"পার্টির PDF তৈরি করা যায়নি: ", "Could not create party PDF: "},
            {"ভ্যাট / কর (", "VAT / Tax ("},
            {"ইউটিলিটি  ", "Utility  "},
            {"বাড়ির খরচ  ", "Property Expense  "},
            {"ভবন: ", "Building: "},
            {"তলা: ", "Floor: "},
            {"ইউটিলিটি ধার্য: ৳ ", "Utility Due: BDT "},
            {"বাড়ির খরচ: ৳ ", "Property Expenses: BDT "},
            {"আপনার অ্যাপটি সর্বশেষ সংস্করণে আছে।", "Your app is up to date."},
            {"নতুন আপডেট পাওয়া গেছে", "New Update Available"},
            {"নতুন সংস্করণ: ", "New version: "},
            {"নতুন সংস্করণ ডাউনলোড হচ্ছে", "Downloading the new version"},
            {"বর্তমান সংস্করণ: ", "Current version: "},
            {"পরে", "Later"},
            {"ডাউনলোড ও ইনস্টল", "Download and Install"},
            {"ডাউনলোড শুরু করা যায়নি: ", "Could not start download: "},
            {"ইনস্টল অনুমতি প্রয়োজন", "Installation permission required"},
            {"একবার এই অ্যাপের জন্য 'Allow from this source' চালু করুন।", "Enable 'Allow from this source' for this app once."},
            {"সেটিংস খুলুন", "Open Settings"},
            {"ইনস্টলার খোলা যায়নি: ", "Could not open installer: "},
            {"তৈরি: ", "Generated: "},
            {"মুছে ফেলুন", "Delete"},
            {"বাড়ি ভাড়া: ", "House Rent: "},
            {"বাড়ি ", "House "},
            {" / ইউনিট ", " / Unit "},
            {" এর জামানত ফেরত (আয় নয়, দায় পরিশোধ)", " deposit refund (not income; liability settlement)"},
            {"   ধার্য: ৳ ", "   Due: BDT "},
            {"   জমা: ৳ ", "   Paid: BDT "},
            {"   বকেয়া: ৳ ", "   Outstanding: BDT "},
            {"     পৃষ্ঠা ", "     Page "},
            {"সংযুক্ত নথি দেখুন / যোগ করুন", "View / Add Attachments"},
            {"সংযুক্ত নথি দেখুন", "View Attachments"},
            {"সংযুক্ত নথি", "Attachments"},
            {"লেনদেনের সংযুক্ত নথি", "Transaction Attachments"},
            {"এখনো কোনো নথি সংযুক্ত নেই", "No attachments yet"},
            {"+ মেমো / রসিদ / ভাউচার / বিল সংযুক্ত করুন", "+ Attach Memo / Receipt / Voucher / Bill"},
            {"+ আরও ছবি / PDF সংযুক্ত করুন", "+ Add More Images / PDFs"},
            {"রসিদ PDF-এর শেষে সংযুক্ত ছবি / PDF যোগ করুন", "Include attached images / PDFs at the end of receipt PDF"},
            {"সংযুক্ত নথি যোগ হয়েছে: ", "Attachments added: "},
            {"নতুন সংযুক্ত নথি যোগ হয়েছে: ", "New attachments added: "},
            {"একটি হিসাবের সঙ্গে সর্বোচ্চ ২০টি নথি যুক্ত করা যাবে", "You can attach up to 20 files to one transaction"},
            {"এই হিসাবের সংযুক্ত নথির সীমা ১২ MB পূর্ণ হয়েছে", "This transaction has reached the 12 MB attachment limit"},
            {"শুধু ছবি বা PDF ফাইল সংযুক্ত করা যাবে", "Only image or PDF files can be attached"},
            {"ফাইল সংযুক্ত হয়নি: ", "Attachment failed: "},
            {"নথি খোলা যায়নি: ", "Could not open attachment: "},
            {"এই হিসাবের সঙ্গে কোনো নথি নেই", "This transaction has no attachments"},
            {"সংযুক্ত নথি পাওয়া যায়নি", "An attached file is missing"},
            {"সংযুক্ত ছবি পড়া যায়নি", "Could not read the attached image"},
            {"সংযুক্ত নথি ফিরিয়ে আনা যায়নি: ", "Could not restore attachments: "},
            {"ব্যাকআপের সংযুক্ত নথি ১২ MB-এর বেশি", "Backup attachments exceed 12 MB"},
            {"রসিদ PDF-এর শেষে সংযুক্ত ছবি / PDF যোগ করুন", "Include attached images / PDFs at the end of receipt PDF"},
            {"⚙  সেটিংস", "Settings"},
            {"সেটিংস", "Settings"},
            {"অ্যাপের ভাষা, ডিজাইন, নিরাপত্তা ও ডেটা নিয়ন্ত্রণ", "Control language, design, security and data"},
            {"ডিজাইন ও ভাষা", "Design and Language"},
            {"ভাষা পরিবর্তন", "Change Language"},
            {"অ্যাপ ও হিসাবের সেটিংস", "App and Account Settings"},
            {"ডেটা সুরক্ষা", "Data Protection"},
            {"ড্যাশবোর্ডে ফিরে যান", "Back to Dashboard"},
            {"ভাড়াটিয়ার সম্পত্তির খাতা", "Tenant Property Ledger"},
            {"ব্যক্তিগত লেনদেনের খাতা (আলাদা)", "Personal Transaction Ledger (Separate)"},
            {"সম্পত্তি / মেরামত খরচ (ভাড়ার বকেয়া নয়)", "Property / Repair Expenses (Not Rent Due)"},
            {"ফেরতযোগ্য জামানতের আলাদা হিসাব", "Separate Refundable Security Deposit Ledger"},
            {"বর্তমান ফেরতযোগ্য জামানত: ৳ ", "Current Refundable Deposit: BDT "},
            {"জামানত গ্রহণ: ৳ ", "Deposit Received: BDT "},
            {"ফেরত: ৳ ", "Refunded: BDT "},
            {"মোট ভাড়া ও বিল বকেয়া: ৳ ", "Total Rent and Utility Due: BDT "},
            {"সম্পত্তির মোট খরচ: ৳ ", "Total Property Expenses: BDT "},
            {"স্টক রিপোর্ট PDF / WhatsApp", "Stock Report PDF / WhatsApp"},
            {"স্টক রিপোর্ট PDF তৈরি করা যায়নি: ", "Could not create stock report PDF: "},
            {"স্টক রিপোর্ট PDF সংরক্ষণ হয়েছে", "Stock report PDF saved"},
            {"দোকানের স্টক রিপোর্ট PDF", "Shop Stock Report PDF"},
            {"দোকানের স্টক ও লাভের রিপোর্ট", "Shop Stock and Profit Report"},
            {"মাসিক ভাড়া", "Monthly Rent"},
            {"সব", "All"},
            {"এখনো মাসিক ভাড়া ধার্য হয়নি", "No monthly rent has been posted"},
            {"কোনো ইউটিলিটি বিল নেই", "No utility bills"},
            {"এখনো কোনো মেরামত / সম্পত্তি খরচ নেই", "No property or repair expenses yet"},
            {"জামানতের কোনো লেনদেন নেই", "No security deposit transactions"},
            {"মোট ভাড়া ধার্য: ৳ ", "Total Rent Charged: BDT "},
            {"মোট ভাড়া জমা: ৳ ", "Total Rent Collected: BDT "},
            {"ভাড়া বকেয়া: ৳ ", "Outstanding Rent: BDT "},
            {"ইউটিলিটি বকেয়া: ৳ ", "Outstanding Utilities: BDT "},
            {"ধার্য: ৳ ", "Charged: BDT "},
            {"জমা: ৳ ", "Paid: BDT "},
            {"বাকি: ৳ ", "Due: BDT "},
            {"খরচ: ৳ ", "Expense: BDT "},
            {"ভবন: ", "Building: "},
            {"ইউনিট: ", "Unit: "},
            {"ভাড়াটিয়া: ", "Tenant: "},
            {"ফেরতযোগ্য নিরাপত্তা জামানত (টাকা)", "Refundable Security Deposit (BDT)"},
            {"ফেরতযোগ্য জামানত পরিবর্তন", "Adjust Refundable Security Deposit"},
            {"নতুন মোট ফেরতযোগ্য জামানত", "New Total Refundable Deposit"},
            {"ফেরতযোগ্য জামানত", "Refundable Security Deposit"},
            {"ইউটিলিটি / সার্ভিস চার্জ", "Utility / Service Charges"},
            {"অ্যাপ সম্পর্কে ও যোগাযোগ", "About the App & Contact"},
            {"ডেভেলপার ও যোগাযোগ", "Developer & Contact"},
            {"ফোন অপশন খোলা যায়নি", "Could not open phone app"},
            {"WhatsApp খোলা যায়নি", "Could not open WhatsApp"},
            {"ফোন করুন — 01754 36 21 29", "Call — 01754 36 21 29"},
            {"ড্যাশবোর্ডে ফিরে যান", "Back to Dashboard"}
        };
        for (String[] pair : words) if (pair[0].equals(value)) return pair[1];
        // Translate dynamic labels that append dates, names or amounts to a known Bengali prefix.
        for (String[] pair : words) {
            if (pair[0].length() >= 4 && value.startsWith(pair[0])) {
                return pair[1] + value.substring(pair[0].length());
            }
        }
        return value;
    }

    private String money(double amount) {
        return ("en".equals(prefs.getString("language", "bn")) ? "BDT " : "৳ ") +
            df.format(amount);
    }

    private String moduleStyleKey() { return "ui_style_" + activeUiModule + "_"; }

    private int moduleTextSize(int fallback) {
        return prefs.getInt(moduleStyleKey() + "size", fallback);
    }

    private int moduleTextColor(int fallback) {
        return prefs.getInt(moduleStyleKey() + "color", fallback);
    }

    private int moduleTypefaceStyle() {
        return prefs.getInt(moduleStyleKey() + "type", 0);
    }

    private void applyModuleTextStyle(TextView view, int fallbackSize, int fallbackColor) {
        view.setTextSize(moduleTextSize(fallbackSize));
        view.setTextColor(moduleTextColor(fallbackColor));
        int style = moduleTypefaceStyle();
        view.setTypeface(Typeface.DEFAULT, style == 1 ? Typeface.BOLD :
            style == 2 ? Typeface.ITALIC : style == 3 ? Typeface.BOLD_ITALIC : Typeface.NORMAL);
    }

    private TextView text(String value, int size) {
        TextView t = new TextView(this);
        t.setText(ui(value));
        applyModuleTextStyle(t, size, Color.rgb(35, 45, 65));
        t.setPadding(8, 10, 8, 10);
        return t;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(ui(hint));
        applyModuleTextStyle(e, 16, Color.rgb(35, 45, 65));
        e.setSingleLine(true);
        e.setPadding(14, 7, 14, 7);
        return e;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(ui(label));
        b.setTextColor(moduleTextColor(Color.WHITE));
        b.setTextSize(moduleTextSize(14));
        int style = moduleTypefaceStyle();
        b.setTypeface(Typeface.DEFAULT, style == 1 ? Typeface.BOLD :
            style == 2 ? Typeface.ITALIC : style == 3 ? Typeface.BOLD_ITALIC : Typeface.NORMAL);
        b.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(blue));
        return b;
    }

    private void setup(String title) {
        setup(title, false);
    }

    private void setup(String title, boolean showPartyFab) {
        String lower = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (lower.contains("ভাড়া") || lower.contains("rent") || lower.contains("বাড়ি")) activeUiModule = "rent";
        else if (lower.contains("স্টক") || lower.contains("stock") || lower.contains("ক্রয়")) activeUiModule = "stock";
        else if (lower.contains("নগদ") || lower.contains("ব্যাংক") || lower.contains("cash")) activeUiModule = "cashbank";
        else if (lower.contains("রিপোর্ট") || lower.contains("report") || lower.contains("সারসংক্ষেপ")) activeUiModule = "reports";
        else if (lower.contains("পার্টি") || lower.contains("খাতা") || lower.contains("ledger")) activeUiModule = "party";
        else if (lower.contains("হিসাব") || lower.contains("dashboard")) activeUiModule = "dashboard";
        else activeUiModule = "general";
        float density = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(244, 247, 253));

        TextView heading = text(title, 16);
        heading.setTextColor(Color.WHITE);
        heading.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(14, 10, 14, 10);
        android.graphics.drawable.GradientDrawable headerBg =
            new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {blue, Color.rgb(20, 48, 105)});
        headerBg.setCornerRadii(new float[] {
            0, 0, 0, 0, 18 * density, 18 * density, 18 * density, 18 * density
        });
        heading.setBackground(headerBg);
        heading.setElevation(3 * density);
        root.addView(heading, new LinearLayout.LayoutParams(-1, (int)(48 * density)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding((int)(14 * density), (int)(10 * density),
            (int)(14 * density), (int)(24 * density));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setGravity(Gravity.CENTER);
        bottomNav.setBackgroundColor(Color.WHITE);
        bottomNav.setElevation(8 * density);
        String[] navLabels = {"Parties", "Transactions", "Reports", "Settings"};
        Runnable[] navActions = {
            () -> ledger(),
            () -> manageRecords(),
            () -> summaryReport(),
            () -> openDashboardSettings()
        };
        for (int i = 0; i < navLabels.length; i++) {
            final int index = i;
            TextView item = new TextView(this);
            item.setText(navLabels[i]);
            item.setTextSize(11);
            item.setTextColor(Color.rgb(45, 65, 95));
            item.setGravity(Gravity.CENTER);
            item.setPadding(2, (int)(12 * density), 2, (int)(12 * density));
            item.setSingleLine(true);
            bottomNav.addView(item, new LinearLayout.LayoutParams(0, (int)(52 * density), 1));
            item.setOnClickListener(v -> navActions[index].run());
        }
        root.addView(bottomNav, new LinearLayout.LayoutParams(-1, (int)(52 * density)));

        if (showPartyFab) {
            android.widget.FrameLayout screen = new android.widget.FrameLayout(this);
            screen.addView(root, new android.widget.FrameLayout.LayoutParams(-1, -1));
            TextView addFab = new TextView(this);
            addFab.setText("+");
            addFab.setTextSize(32);
            addFab.setTextColor(Color.WHITE);
            addFab.setGravity(Gravity.CENTER);
            addFab.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            android.graphics.drawable.GradientDrawable fabBg =
                new android.graphics.drawable.GradientDrawable();
            fabBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            fabBg.setColor(Color.rgb(25, 112, 245));
            fabBg.setStroke((int)(1.5f * density), Color.rgb(112, 177, 255));
            addFab.setBackground(fabBg);
            addFab.setElevation(12 * density);
            addFab.setContentDescription(ui("নতুন পার্টি যোগ করুন"));
            int fabSize = (int)(56 * density);
            android.widget.FrameLayout.LayoutParams fabParams =
                new android.widget.FrameLayout.LayoutParams(fabSize, fabSize,
                    Gravity.BOTTOM | Gravity.END);
            fabParams.setMargins(0, 0, (int)(20 * density), (int)(68 * density));
            screen.addView(addFab, fabParams);
            addFab.setOnClickListener(v -> addPartyDialog());
            setContentView(screen);
        } else {
            setContentView(root);
        }
    }

    private boolean isRentalLedgerRecord(JSONObject record) {
        return record != null && AccountingMath.isRentalLedgerRecord(
            record.optBoolean("deposit_transaction"), record.optBoolean("deposit_refund"),
            record.optBoolean("rental_charge"), record.optBoolean("rental_expense"),
            record.optBoolean("rent_advance_received"), record.optBoolean("rent_advance_applied"),
            record.optBoolean("rent_advance_refund"), record.optString("rent_key"),
            record.optString("rent_payment_key"), record.optString("rental_charge_key"),
            record.optString("rental_charge_payment_key"),
            record.optString("deposit_transaction_key"));
    }

    private double partyBalance(String name) {
        return partyBalance(name, CategoryAccounting.FILTER_ALL);
    }

    private double partyBalance(String name, String selectedCategory) {
        double balance = 0;
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.optJSONObject(i);
            if (r == null || isCashBankRecord(r) || isRentalLedgerRecord(r) ||
                !name.equals(r.optString("party").trim()) ||
                !CategoryAccounting.belongsToCategory(recordCategoryId(r),
                    selectedCategory)) continue;
            balance = AccountingMath.applyPartyTransaction(
                balance, r.optString("type"), r.optDouble("amount", 0));
        }
        return balance;
    }

    private JSONArray trash;
    private JSONArray rentalUnits;

    private void loadTrash() {
        try { trash = new JSONArray(prefs.getString("trash", "[]")); }
        catch (Exception e) { trash = new JSONArray(); }
    }

    private boolean saveTrash() {
        return prefs.edit().putString("trash", trash.toString()).commit();
    }

    private boolean saveRecordsAndTrash() {
        return prefs.edit()
            .putString("records", records.toString())
            .putString("trash", trash.toString())
            .commit();
    }


    private boolean saveRentalState(JSONArray previousRecords, JSONArray previousUnits) {
        boolean saved = prefs.edit()
            .putString("records", records.toString())
            .putString("rental_units", rentalUnits.toString())
            .commit();
        if (!saved) {
            records = previousRecords;
            rentalUnits = previousUnits;
            return false;
        }
        return true;
    }

    // Security deposits are liabilities, not business income or operating expenses.
    private void addDepositTransaction(String tenant, JSONObject unit, double amount,
                                       boolean received, String note) throws Exception {
        if (!AccountingMath.isValidTransactionAmount(amount)) return;
        JSONObject transaction = new JSONObject();
        transaction.put("party", tenant);
        transaction.put("type", received ? "জামানত জমা" : "জামানত ফেরত");
        transaction.put("category_id", CategoryAccounting.CATEGORY_RENT);
        transaction.put("amount", amount);
        transaction.put("date", new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date()));
        transaction.put("note", note);
        transaction.put("deposit_transaction", true);
        transaction.put("rent_unit", unit.optString("unit"));
        transaction.put("rent_building", unit.optString("building"));
        transaction.put("deposit_transaction_key", "DEP-" + System.currentTimeMillis() + "-" + records.length());
        records.put(transaction);
    }

    private void loadRentalUnits() {
        try { rentalUnits = new JSONArray(prefs.getString("rental_units", "[]")); }
        catch (Exception e) { rentalUnits = new JSONArray(); }
    }

    private boolean saveRentalUnits(JSONArray previousUnits) {
        boolean saved = prefs.edit()
            .putString("rental_units", rentalUnits.toString())
            .commit();
        if (!saved) {
            rentalUnits = previousUnits;
            return false;
        }
        return true;
    }

    private void loadStockItems() {
        try { stockItems = new JSONArray(prefs.getString("stock_items", "[]")); }
        catch (Exception e) { stockItems = new JSONArray(); }
    }

    private void saveStockItems() {
        prefs.edit().putString("stock_items", stockItems.toString()).apply();
    }


    private TextView dashboardMetric(String label, double amount, boolean highlight) {
        TextView metric = new TextView(this);
        metric.setText(ui(label) + df.format(amount));
        applyModuleTextStyle(metric, highlight ? 21 : 17,
            highlight ? Color.WHITE : Color.rgb(35, 55, 85));
        metric.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        metric.setPadding(18, 15, 18, 15);
        android.graphics.drawable.GradientDrawable bg =
            new android.graphics.drawable.GradientDrawable();
        bg.setColor(highlight ? blue : Color.WHITE);
        bg.setCornerRadius(16 * getResources().getDisplayMetrics().density);
        bg.setStroke(1, highlight ? blue : Color.rgb(220, 229, 242));
        metric.setBackground(bg);
        metric.setElevation(2 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams lp =
            new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 6, 0, 6);
        metric.setLayoutParams(lp);
        return metric;
    }

    // Wide, readable dashboard banner: crop the existing illustration and render the name as live text.
    private View createWideBrandBanner() {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding((int)(10*density), (int)(7*density), (int)(14*density), (int)(7*density));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{Color.rgb(7,35,83), Color.rgb(17,66,125)});
        bg.setCornerRadius(14*density);
        bg.setStroke((int)(1.5f*density), Color.rgb(224,184,76));
        banner.setBackground(bg);
        LinearLayout.LayoutParams bannerParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (int)(82*density));
        bannerParams.setMargins((int)(4*density), (int)(3*density), (int)(4*density), (int)(6*density));
        banner.setLayoutParams(bannerParams);

        ImageView illustration = new ImageView(this);
        android.graphics.Bitmap source = android.graphics.BitmapFactory.decodeResource(
            getResources(), R.drawable.amar_hisab_logo);
        if (source != null) {
            int cropHeight = Math.max(1, (int)(source.getHeight() * 0.53f));
            android.graphics.Bitmap crop = android.graphics.Bitmap.createBitmap(
                source, 0, 0, source.getWidth(), cropHeight);
            illustration.setImageBitmap(crop);
            illustration.setScaleType(ImageView.ScaleType.CENTER_CROP);
            source.recycle();
        }
        illustration.setContentDescription("আমার হিসাবের লোগো");
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
            (int)(76*density), (int)(64*density));
        imageParams.setMargins(0, 0, (int)(13*density), 0);
        banner.addView(illustration, imageParams);

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setGravity(Gravity.CENTER_VERTICAL);
        TextView bengali = new TextView(this);
        bengali.setText("আমার হিসাব");
        bengali.setTextColor(Color.WHITE);
        bengali.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bengali.setTextSize(27);
        bengali.setSingleLine(true);
        words.addView(bengali);
        TextView english = new TextView(this);
        english.setText("AMAR HISAB");
        english.setTextColor(Color.rgb(246,207,100));
        english.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        english.setTextSize(12);
        english.setLetterSpacing(0.08f);
        words.addView(english);
        banner.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return banner;
    }

    private void home() {
        setup("আমার হিসাব");
        TextView brand = text("আমার হিসাব", 17);
        brand.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        brand.setGravity(Gravity.CENTER);
        brand.setPadding(4, 8, 4, 4);
        content.addView(brand);

        double due = 0, received = 0, expense = 0, paid = 0;
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.optJSONObject(i);
            if (r == null || isRentalLedgerRecord(r) || isCashBankRecord(r)) continue;
            double amount = r.optDouble("amount", 0);
            String type = r.optString("type");
            if ("পাওনা".equals(type)) due += amount;
            else if ("জমা".equals(type)) received += amount;
            else if ("খরচ".equals(type)) expense += amount;
            else if ("পরিশোধ".equals(type)) paid += amount;
        }
        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.VERTICAL);
        summary.setPadding(16, 14, 16, 14);
        android.graphics.drawable.GradientDrawable panel = new android.graphics.drawable.GradientDrawable();
        panel.setColor(Color.WHITE);
        panel.setCornerRadius(16 * getResources().getDisplayMetrics().density);
        summary.setBackground(panel);
        summary.setElevation(2 * getResources().getDisplayMetrics().density);
        TextView summaryTitle = text("হিসাবের সংক্ষিপ্ত অবস্থা", 16);
        summaryTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        summary.addView(summaryTitle);
        summary.addView(text("মোট পাওনা: ৳ " + df.format(due), 15));
        summary.addView(text("মোট জমা: ৳ " + df.format(received), 15));
        summary.addView(text("মোট খরচ: ৳ " + df.format(expense), 15));
        summary.addView(text("মোট পরিশোধ: ৳ " + df.format(paid), 15));
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(-1, -2);
        panelParams.setMargins(0, 10, 0, 14);
        content.addView(summary, panelParams);

        Button add = button("＋  নতুন হিসাব");
        content.addView(add);
        add.setOnClickListener(v -> entry(""));
        Button parties = button("পার্টির হিসাব দেখুন");
        content.addView(parties);
        parties.setOnClickListener(v -> ledger());
        Button rent = button("বাড়ি ভাড়ার হিসাব");
        content.addView(rent);
        rent.setOnClickListener(v -> rentScreen());
        Button stock = button("দোকানের স্টক");
        content.addView(stock);
        stock.setOnClickListener(v -> stockScreen());
        Button cash = button("নগদ / ব্যাংক");
        content.addView(cash);
        cash.setOnClickListener(v -> cashBankScreen());
    }

    private void showDeveloperInfo() {
        setup("অ্যাপ সম্পর্কে");
        content.addView(createWideBrandBanner());
        TextView credit = text("Developed with Love by", 19);
        credit.setGravity(Gravity.CENTER);
        credit.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        credit.setPadding(8, 22, 8, 8);
        content.addView(credit);
        TextView names = text("Ayan • Anaiya • Zayan\nআইয়ান • আনাইয়া • জায়ান", 20);
        names.setGravity(Gravity.CENTER);
        names.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        names.setTextColor(blue);
        names.setPadding(8, 8, 8, 22);
        content.addView(names);
        content.addView(text("ফোন ও WhatsApp: 01754 36 21 29", 17));
        Button call = button("ফোন করুন — 01754 36 21 29");
        content.addView(call);
        call.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_DIAL,
                    android.net.Uri.parse("tel:01754362129")));
            } catch (Exception e) {
                Toast.makeText(this, ui("ফোন অপশন খোলা যায়নি"), Toast.LENGTH_LONG).show();
            }
        });
        Button whatsapp = button("WhatsApp — 01754 36 21 29");
        content.addView(whatsapp);
        whatsapp.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://wa.me/8801754362129")));
            } catch (Exception e) {
                Toast.makeText(this, ui("WhatsApp খোলা যায়নি"), Toast.LENGTH_LONG).show();
            }
        });
        Button back = button("ড্যাশবোর্ডে ফিরে যান");
        content.addView(back);
        back.setOnClickListener(v -> home());
    }

    private void openDashboardSettings() {
        // Open a real, dedicated settings screen instead of a temporary popup.
        setup("সেটিংস");
        content.addView(createWideBrandBanner());
        content.addView(text("অ্যাপের ভাষা, ডিজাইন, নিরাপত্তা ও ডেটা নিয়ন্ত্রণ", 16));

        content.addView(text("অ্যাপ আপডেট", 19));
        Button update = button("নতুন আপডেট খুঁজুন");
        content.addView(update);
        update.setOnClickListener(v -> checkForUpdates(true));

        content.addView(text("ডিজাইন ও ভাষা", 19));
        Button typography = button("লেখার ডিজাইন ও অক্ষর সেটিংস");
        content.addView(typography);
        typography.setOnClickListener(v -> chooseModuleTypography());
        Button theme = button("ড্যাশবোর্ডের রং পরিবর্তন");
        content.addView(theme);
        theme.setOnClickListener(v -> chooseDashboardTheme());
        Button language = button("ভাষা পরিবর্তন");
        content.addView(language);
        language.setOnClickListener(v -> {
            String next = "en".equals(prefs.getString("language", "bn")) ? "bn" : "en";
            prefs.edit().putString("language", next).commit();
            openDashboardSettings();
        });

        content.addView(text("অ্যাপ ও হিসাবের সেটিংস", 19));
        Button pdf = button("PDF / রসিদ ডিজাইন ও স্বাক্ষর");
        content.addView(pdf);
        pdf.setOnClickListener(v -> pdfSettings());
        Button sms = button("SMS পাঠানোর সেটিংস");
        content.addView(sms);
        sms.setOnClickListener(v -> smsSettings());
        Button lock = button("অ্যাপ লক / ফিঙ্গারপ্রিন্ট সেটিংস");
        content.addView(lock);
        lock.setOnClickListener(v -> appLockSettings());

        content.addView(text("ডেটা সুরক্ষা", 19));
        Button manage = button("হিসাব ব্যবস্থাপনা / মুছুন");
        content.addView(manage);
        manage.setOnClickListener(v -> manageRecords());
        Button recycle = button("রিসাইকেল বিন / ফেরত আনুন");
        content.addView(recycle);
        recycle.setOnClickListener(v -> recycleBin());
        Button backup = button("ডেটা ব্যাকআপ সংরক্ষণ করুন");
        content.addView(backup);
        backup.setOnClickListener(v -> shareBackup());
        Button restore = button("ব্যাকআপ থেকে ডেটা ফিরিয়ে আনুন");
        content.addView(restore);
        restore.setOnClickListener(v -> chooseBackupToRestore());

        Button about = button("অ্যাপ সম্পর্কে ও যোগাযোগ");
        content.addView(about);
        about.setOnClickListener(v -> showDeveloperInfo());
        Button back = button("ড্যাশবোর্ডে ফিরে যান");
        content.addView(back);
        back.setOnClickListener(v -> home());
    }

    private void chooseModuleTypography() {
        final String[] moduleNames = {"ড্যাশবোর্ড", "পার্টি / কাস্টমার খাতা",
            "বাড়িভাড়া মডিউল", "দোকানের স্টক ও ক্রয়-বিক্রয়",
            "নগদ ও ব্যাংক হিসাব", "রিপোর্ট ও সারসংক্ষেপ", "অন্যান্য স্ক্রিন"};
        final String[] moduleKeys = {"dashboard", "party", "rent", "stock",
            "cashbank", "reports", "general"};
        new AlertDialog.Builder(this).setTitle(ui("কোন মডিউলের লেখা সাজাবেন?"))
            .setItems(moduleNames, (dialog, which) ->
                showModuleTypographyOptions(moduleKeys[which], moduleNames[which])).show();
    }

    private void showModuleTypographyOptions(String module, String title) {
        final String prefix = "ui_style_" + module + "_";
        final String[] options = {"লেখার আকার", "লেখার ধরন", "লেখার রং", "এই মডিউলের ডিজাইন ডিফল্টে ফেরত"};
        new AlertDialog.Builder(this).setTitle(ui(title) + " — " + ui("লেখার সেটিংস"))
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    final String[] sizes = {"খুব ছোট (12)", "ছোট (14)", "মাঝারি (16)", "বড় (18)", "খুব বড় (22)"};
                    final int[] values = {12, 14, 16, 18, 22};
                    int selected = 2;
                    int saved = prefs.getInt(prefix + "size", 16);
                    for (int i = 0; i < values.length; i++) if (values[i] == saved) selected = i;
                    new AlertDialog.Builder(this).setTitle(ui("লেখার আকার"))
                        .setSingleChoiceItems(sizes, selected, (d, index) -> {
                            prefs.edit().putInt(prefix + "size", values[index]).apply();
                            d.dismiss(); showModuleTypographyOptions(module, title);
                        }).setNegativeButton(ui("বাতিল"), null).show();
                } else if (which == 1) {
                    final String[] styles = {"সাধারণ", "মোটা (Bold)", "বাঁকা (Italic)", "মোটা ও বাঁকা"};
                    int selected = prefs.getInt(prefix + "type", 0);
                    new AlertDialog.Builder(this).setTitle(ui("লেখার ধরন"))
                        .setSingleChoiceItems(styles, selected, (d, index) -> {
                            prefs.edit().putInt(prefix + "type", index).apply();
                            d.dismiss(); showModuleTypographyOptions(module, title);
                        }).setNegativeButton(ui("বাতিল"), null).show();
                } else if (which == 2) {
                    final String[] colors = {"গাঢ় কালো", "নীল", "সবুজ", "লাল", "বেগুনি", "সাদা"};
                    final int[] values = {Color.rgb(35,45,65), Color.rgb(25,90,180),
                        Color.rgb(20,115,75), Color.rgb(190,45,55),
                        Color.rgb(112,72,170), Color.WHITE};
                    int selected = 0, saved = prefs.getInt(prefix + "color", Color.rgb(35,45,65));
                    for (int i = 0; i < values.length; i++) if (values[i] == saved) selected = i;
                    new AlertDialog.Builder(this).setTitle(ui("লেখার রং"))
                        .setSingleChoiceItems(colors, selected, (d, index) -> {
                            prefs.edit().putInt(prefix + "color", values[index]).apply();
                            d.dismiss(); showModuleTypographyOptions(module, title);
                        }).setNegativeButton(ui("বাতিল"), null).show();
                } else {
                    new AlertDialog.Builder(this).setTitle(ui("ডিফল্টে ফেরত"))
                        .setMessage(ui("এই মডিউলের লেখার আকার, ধরন ও রং আগের অবস্থায় ফিরবে।"))
                        .setNegativeButton(ui("বাতিল"), null)
                        .setPositiveButton(ui("ফিরিয়ে দিন"), (d, w) -> {
                            prefs.edit().remove(prefix + "size").remove(prefix + "type")
                                .remove(prefix + "color").apply();
                            showModuleTypographyOptions(module, title);
                        }).show();
                }
            }).setNegativeButton(ui("বন্ধ"), null).show();
    }

    private void chooseDashboardTheme() {
        final String[] names={ui("নীল"),ui("সবুজ"),ui("বেগুনি"),ui("কমলা"),ui("গাঢ় নীল")};
        final int[] colors={Color.rgb(25,90,180),Color.rgb(18,130,92),Color.rgb(112,72,170),
            Color.rgb(215,112,35),Color.rgb(35,54,92)};
        int selected=0; for(int i=0;i<colors.length;i++) if(colors[i]==blue) selected=i;
        new AlertDialog.Builder(this).setTitle(ui("ড্যাশবোর্ডের রং নির্বাচন করুন"))
            .setSingleChoiceItems(names,selected,(dialog,which)->{
                blue=colors[which]; prefs.edit().putInt("app_theme_color",blue).apply();
                dialog.dismiss(); home();
            }).setNegativeButton(ui("বাতিল"),null).show();
    }

    private TextView dashboardMetric(String label,double amount,int cardColor,int textColor) {
        TextView metric=new TextView(this); metric.setText(ui(label)+df.format(amount));
        applyModuleTextStyle(metric, 15, textColor);
        metric.setTypeface(Typeface.DEFAULT, moduleTypefaceStyle() == 2 ? Typeface.ITALIC :
            moduleTypefaceStyle() == 3 ? Typeface.BOLD_ITALIC : Typeface.BOLD);
        metric.setGravity(Gravity.CENTER_VERTICAL); metric.setPadding(13,14,13,14);
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
        bg.setColor(cardColor); bg.setCornerRadius(18*getResources().getDisplayMetrics().density);
        bg.setStroke(1,cardColor==Color.WHITE?Color.rgb(224,231,240):cardColor);
        metric.setBackground(bg);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);
        lp.setMargins(4,5,4,5); metric.setLayoutParams(lp); return metric;
    }

    // Cash and bank transactions are stored in the existing records array so
    // the current backup/restore format automatically includes them.
    private boolean isCashBankRecord(JSONObject r) {
        if (r == null) return false;
        String type = r.optString("type");
        return type.startsWith("নগদ ") || type.startsWith("ব্যাংক ") ||
            "ব্যাংক অ্যাকাউন্ট তৈরি".equals(type) || "অ্যাকাউন্ট স্থানান্তর".equals(type) ||
            "CATEGORY_CASHBOX_OPENING".equals(type) || "CATEGORY_CASHBOX_TRANSFER".equals(type);
    }

    private double cashBankBalance(String accountType, String accountName) {
        double balance = 0;
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.optJSONObject(i);
            String type = r.optString("type");
            double amount = r.optDouble("amount", 0);
            // Ordinary accounting receipts and payments default to cash. This keeps
            // party collections, business income/expenses, stock sales/purchases,
            // rental collections/expenses and deposit movements in the cash balance
            // without creating a second ledger record.
            if (!isCashBankRecord(r)) {
                if ("নগদ".equals(accountType)) {
                    double movement = 0;
                    if ("জমা".equals(type) || "আয়".equals(type) ||
                        "জামানত জমা".equals(type)) movement = amount;
                    else if ("পরিশোধ".equals(type) || "খরচ".equals(type) ||
                        "জামানত ফেরত".equals(type)) movement = -amount;
                    balance += movement;
                }
                continue;
            }
            String account = r.optString("cash_bank_account");
            String from = r.optString("transfer_from");
            String to = r.optString("transfer_to");
            if ("নগদ".equals(accountType)) {
                if ("নগদ জমা".equals(type) && "নগদ".equals(account)) balance += amount;
                else if ("নগদ উত্তোলন".equals(type) && "নগদ".equals(account)) balance -= amount;
                else if ("ব্যাংক জমা".equals(type)) balance -= amount;
                else if ("ব্যাংক উত্তোলন".equals(type)) balance += amount;
                else if ("অ্যাকাউন্ট স্থানান্তর".equals(type)) {
                    if ("নগদ".equals(from)) balance -= amount;
                    if ("নগদ".equals(to)) balance += amount;
                }
            } else if ("ব্যাংক".equals(accountType)) {
                if ("ব্যাংক প্রারম্ভিক জমা".equals(type) &&
                    accountName.equals(account)) balance += amount;
                else if ("ব্যাংক জমা".equals(type) && accountName.equals(account)) balance += amount;
                else if ("ব্যাংক উত্তোলন".equals(type) && accountName.equals(account)) balance -= amount;
                else if ("অ্যাকাউন্ট স্থানান্তর".equals(type)) {
                    if (accountName.equals(from)) balance -= amount;
                    if (accountName.equals(to)) balance += amount;
                }
            }
        }
        return Double.isFinite(balance) ? balance : 0;
    }

    private ArrayList<String> bankAccountNames() {
        TreeSet<String> names = new TreeSet<>(nameComparator());
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.optJSONObject(i);
            if (r == null) continue;
            String type = r.optString("type");
            if ("ব্যাংক অ্যাকাউন্ট তৈরি".equals(type) ||
                "ব্যাংক প্রারম্ভিক জমা".equals(type) ||
                "ব্যাংক জমা".equals(type) || "ব্যাংক উত্তোলন".equals(type)) {
                String name = r.optString("cash_bank_account").trim();
                if (!name.isEmpty()) names.add(name);
            }
            if ("অ্যাকাউন্ট স্থানান্তর".equals(type)) {
                String from = r.optString("transfer_from");
                String to = r.optString("transfer_to");
                if (!from.isEmpty() && !"নগদ".equals(from)) names.add(from);
                if (!to.isEmpty() && !"নগদ".equals(to)) names.add(to);
            }
        }
        return new ArrayList<>(names);
    }

    private double cashBankTotal() {
        double total = 0;
        for (String name : bankAccountNames()) total += cashBankBalance("ব্যাংক", name);
        return Double.isFinite(total) ? total : 0;
    }

    // Independent logical cashboxes by accounting category. These are separate
    // from physical cash/bank accounts; transfers never count as income or expense.
    private String[][] accountingCategories(boolean includeUnassigned) {
        ArrayList<String[]> result = new ArrayList<>();
        result.add(new String[]{CategoryAccounting.CATEGORY_PERSONAL, "ব্যক্তিগত খাতা"});
        result.add(new String[]{CategoryAccounting.CATEGORY_RENT, "ভাড়া খাতা"});
        result.add(new String[]{CategoryAccounting.CATEGORY_SHOP, "দোকানের ব্যবসা"});
        result.add(new String[]{CategoryAccounting.CATEGORY_OTHER, "অন্যান্য"});
        try {
            JSONArray custom = new JSONArray(prefs.getString("custom_ledger_categories", "[]"));
            for (int i = 0; i < custom.length() && result.size() < 30; i++) {
                JSONObject item = custom.optJSONObject(i);
                if (item == null) continue;
                String id = CategoryAccounting.normalizeCategoryId(item.optString("id"));
                String name = item.optString("name").trim();
                if (id.startsWith("custom_") && !name.isEmpty() && name.length() <= 32)
                    result.add(new String[]{id, name});
            }
        } catch (Exception ignored) {}
        if (includeUnassigned)
            result.add(new String[]{CategoryAccounting.CATEGORY_UNASSIGNED, "শ্রেণিবিহীন পুরোনো হিসাব"});
        return result.toArray(new String[0][]);
    }

    private String[][] cashboxCategories() {
        return accountingCategories(false);
    }

    private void addCustomCategoryDialog() {
        EditText name = field("নতুন খাতের নাম");
        new AlertDialog.Builder(this).setTitle(ui("নিজস্ব খাতা যোগ করুন"))
            .setView(name).setNegativeButton(ui("বাতিল"), null)
            .setPositiveButton(ui("যোগ করুন"), (d, w) -> {
                String label = name.getText().toString().trim();
                if (label.isEmpty() || label.length() > 32) {
                    Toast.makeText(this, ui("খাতের নাম ১ থেকে ৩২ অক্ষরের মধ্যে দিন"), Toast.LENGTH_LONG).show();
                    return;
                }
                JSONArray custom;
                try { custom = new JSONArray(prefs.getString("custom_ledger_categories", "[]")); }
                catch (Exception e) { custom = new JSONArray(); }
                for (int i = 0; i < custom.length(); i++) {
                    JSONObject item = custom.optJSONObject(i);
                    if (item != null && label.equalsIgnoreCase(item.optString("name").trim())) {
                        Toast.makeText(this, ui("এই নামে খাতা আগে আছে"), Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                if (custom.length() >= 20) {
                    Toast.makeText(this, ui("সর্বোচ্চ ২০টি নিজস্ব খাতা যোগ করা যাবে"), Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    JSONObject item = new JSONObject();
                    item.put("id", "custom_" + System.currentTimeMillis());
                    item.put("name", label);
                    custom.put(item);
                    if (!prefs.edit().putString("custom_ledger_categories", custom.toString()).commit())
                        throw new IllegalStateException("save failed");
                    categoryCashboxScreen();
                } catch (Exception e) {
                    Toast.makeText(this, ui("নতুন খাতা সংরক্ষণ হয়নি"), Toast.LENGTH_LONG).show();
                }
            }).show();
    }

    // Resolve the category for both newly tagged records and older rental records
    // that predate category_id. This keeps historical rent activity in rent reports.
    private String recordCategoryId(JSONObject record) {
        if (record == null) return CategoryAccounting.CATEGORY_UNASSIGNED;
        String categoryId = record.optString("category_id", "").trim();
        if (!categoryId.isEmpty()) return CategoryAccounting.normalizeCategoryId(categoryId);
        if (record.has("rent_payment_key") || record.has("rental_charge_key") ||
            record.has("rental_expense") || record.optBoolean("deposit_transaction")) {
            return CategoryAccounting.CATEGORY_RENT;
        }
        return CategoryAccounting.CATEGORY_UNASSIGNED;
    }

    private double categoryCashboxBalance(String categoryId) {
        double balance = 0;
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.optJSONObject(i);
            if (r == null) continue;
            String type = r.optString("type");
            double amount = r.optDouble("amount", 0);
            if (!Double.isFinite(amount) || amount < 0) continue;
            if ("CATEGORY_CASHBOX_OPENING".equals(type) &&
                categoryId.equals(r.optString("category_id"))) {
                balance += amount;
            } else if ("CATEGORY_CASHBOX_TRANSFER".equals(type)) {
                balance += CategoryAccounting.cashboxTransferDelta(categoryId,
                    r.optString("transfer_from_category"),
                    r.optString("transfer_to_category"), amount);
            } else if (!isCashBankRecord(r)) {
                if (!CategoryAccounting.belongsToCategory(recordCategoryId(r), categoryId)) continue;
                if ("জমা".equals(type) || "আয়".equals(type) || "জামানত জমা".equals(type))
                    balance += amount;
                else if ("পরিশোধ".equals(type) || "খরচ".equals(type) || "জামানত ফেরত".equals(type))
                    balance -= amount;
            }
            if (!Double.isFinite(balance)) return 0;
        }
        return balance;
    }

    private String categoryDisplayName(String categoryId) {
        for (String[] category : accountingCategories(true)) {
            if (category[0].equals(CategoryAccounting.normalizeCategoryId(categoryId)))
                return category[1];
        }
        return categoryId == null || categoryId.trim().isEmpty()
            ? "অজানা খাতা" : categoryId;
    }

    private void categoryCashboxScreen() {
        setup("খাতভিত্তিক টাকার বাক্স");
        content.addView(text("প্রতিটি খাতের টাকা আলাদা থাকবে। এক বাক্স থেকে অন্য বাক্সে পাঠানো টাকা আয় বা খরচ হিসেবে গণ্য হবে না।", 15));
        for (String[] category : cashboxCategories()) {
            content.addView(dashboardMetric(category[1] + ": ৳ ",
                categoryCashboxBalance(category[0]), true));
        }
        Button addCategory = button("+ নিজস্ব খাতা যোগ করুন");
        content.addView(addCategory);
        addCategory.setOnClickListener(v -> addCustomCategoryDialog());
        Button opening = button("প্রারম্ভিক ব্যালেন্স যোগ করুন");
        content.addView(opening);
        opening.setOnClickListener(v -> categoryCashboxOpeningDialog());
        Button transfer = button("এক খাত থেকে অন্য খাতে টাকা স্থানান্তর");
        content.addView(transfer);
        transfer.setOnClickListener(v -> categoryCashboxTransferDialog());
        content.addView(text("সাম্প্রতিক খাত স্থানান্তরের ইতিহাস", 18));
        int shown = 0;
        for (int i = records.length() - 1; i >= 0 && shown < 30; i--) {
            JSONObject r = records.optJSONObject(i);
            if (r == null || !"CATEGORY_CASHBOX_TRANSFER".equals(r.optString("type"))) continue;
            content.addView(text(r.optString("date") + " | " +
                categoryDisplayName(r.optString("transfer_from_category")) + " → " +
                categoryDisplayName(r.optString("transfer_to_category")) + " | ৳ " +
                money(r.optDouble("amount", 0)) +
                (r.optString("note").isEmpty() ? "" : "\n" + r.optString("note")), 15));
            shown++;
        }
        Button back = button("ফিরে যান");
        content.addView(back);
        back.setOnClickListener(v -> home());
    }

    private void categoryCashboxOpeningDialog() {
        String[][] categories = cashboxCategories();
        String[] labels = new String[categories.length];
        for (int i = 0; i < categories.length; i++) labels[i] = ui(categories[i][1]);
        Spinner category = new Spinner(this);
        category.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        EditText amount = field("প্রারম্ভিক ব্যালেন্স");
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.addView(category); form.addView(amount);
        new AlertDialog.Builder(this).setTitle(ui("প্রারম্ভিক ব্যালেন্স যোগ করুন"))
            .setView(form).setNegativeButton(ui("বাতিল"), null)
            .setPositiveButton(ui("সংরক্ষণ"), (d, w) -> {
                String id = categories[category.getSelectedItemPosition()][0];
                double value = number(amount.getText().toString());
                if (!CategoryAccounting.isValidOpeningBalance(value)) {
                    Toast.makeText(this, ui("সঠিক প্রারম্ভিক ব্যালেন্স দিন"), Toast.LENGTH_LONG).show();
                    return;
                }
                for (int i = 0; i < records.length(); i++) {
                    JSONObject old = records.optJSONObject(i);
                    if (old != null && "CATEGORY_CASHBOX_OPENING".equals(old.optString("type")) &&
                        id.equals(old.optString("category_id"))) {
                        Toast.makeText(this, ui("এই খাতের প্রারম্ভিক ব্যালেন্স আগে যোগ করা হয়েছে"), Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                JSONArray before = copyJsonArray(records);
                try {
                    JSONObject r = new JSONObject();
                    r.put("party", "খাতভিত্তিক টাকার বাক্স");
                    r.put("type", "CATEGORY_CASHBOX_OPENING");
                    r.put("category_id", id);
                    r.put("amount", value);
                    r.put("date", new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date()));
                    r.put("note", "প্রারম্ভিক ব্যালেন্স");
                    records.put(r);
                    if (!prefs.edit().putString("records", records.toString()).commit()) throw new IllegalStateException("save failed");
                    categoryCashboxScreen();
                } catch (Exception e) {
                    records = before;
                    Toast.makeText(this, ui("সংরক্ষণ করা যায়নি"), Toast.LENGTH_LONG).show();
                }
            }).show();
    }

    private void categoryCashboxTransferDialog() {
        String[][] categories = cashboxCategories();
        String[] labels = new String[categories.length];
        for (int i = 0; i < categories.length; i++) labels[i] = ui(categories[i][1]);
        Spinner from = new Spinner(this), to = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, labels);
        from.setAdapter(adapter); to.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, labels));
        EditText amount = field("টাকার পরিমাণ");
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText note = field("বিবরণ (ঐচ্ছিক)");
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.addView(text("যে খাত থেকে টাকা যাবে", 15)); form.addView(from);
        form.addView(text("যে খাতে টাকা যাবে", 15)); form.addView(to);
        form.addView(amount); form.addView(note);
        new AlertDialog.Builder(this).setTitle(ui("খাতের মধ্যে টাকা স্থানান্তর"))
            .setView(form).setNegativeButton(ui("বাতিল"), null)
            .setPositiveButton(ui("স্থানান্তর"), (d, w) -> {
                String source = categories[from.getSelectedItemPosition()][0];
                String target = categories[to.getSelectedItemPosition()][0];
                double value = number(amount.getText().toString());
                double sourceBalance = categoryCashboxBalance(source);
                if (!CategoryAccounting.isValidCashboxTransfer(source, target, value, sourceBalance)) {
                    Toast.makeText(this, ui("খাত নির্বাচন, টাকার পরিমাণ বা পর্যাপ্ত ব্যালেন্স সঠিক নয়"), Toast.LENGTH_LONG).show();
                    return;
                }
                JSONArray before = copyJsonArray(records);
                try {
                    JSONObject r = new JSONObject();
                    r.put("party", "খাতভিত্তিক টাকা স্থানান্তর");
                    r.put("type", "CATEGORY_CASHBOX_TRANSFER");
                    r.put("transfer_from_category", source);
                    r.put("transfer_to_category", target);
                    r.put("amount", value);
                    r.put("note", note.getText().toString().trim());
                    r.put("date", new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date()));
                    records.put(r);
                    if (!prefs.edit().putString("records", records.toString()).commit()) throw new IllegalStateException("save failed");
                    categoryCashboxScreen();
                } catch (Exception e) {
                    records = before;
                    Toast.makeText(this, ui("স্থানান্তর সংরক্ষণ হয়নি"), Toast.LENGTH_LONG).show();
                }
            }).show();
    }

    private void cashBankScreen() {
        setup("নগদ ও ব্যাংক হিসাব");
        content.addView(text("নগদ ব্যালেন্স", 19));
        content.addView(dashboardMetric("হাতে নগদ: ৳ ", cashBankBalance("নগদ", ""), true));
        ArrayList<String> accounts = bankAccountNames();
        content.addView(text("ব্যাংক অ্যাকাউন্টসমূহ", 19));
        if (accounts.isEmpty()) content.addView(text("এখনো কোনো ব্যাংক অ্যাকাউন্ট যোগ করা হয়নি", 16));
        for (String name : accounts) {
            content.addView(dashboardMetric(name + ": " +
                ("en".equals(prefs.getString("language", "bn")) ? "BDT " : "৳ "),
                cashBankBalance("ব্যাংক", name), false));
        }
        content.addView(dashboardMetric("মোট ব্যাংক ব্যালেন্স: ৳ ", cashBankTotal(), true));

        Button addAccount = button("+ নতুন ব্যাংক অ্যাকাউন্ট যোগ করুন");
        content.addView(addAccount);
        addAccount.setOnClickListener(v -> addBankAccountDialog());

        Button transaction = button("+ নগদ / ব্যাংক লেনদেন");
        content.addView(transaction);
        transaction.setOnClickListener(v -> cashBankTransactionDialog());

        content.addView(text("সাম্প্রতিক নগদ ও ব্যাংক লেনদেন", 19));
        int shown = 0;
        for (int i = records.length() - 1; i >= 0 && shown < 30; i--) {
            JSONObject r = records.optJSONObject(i);
            if (!isCashBankRecord(r) || "ব্যাংক অ্যাকাউন্ট তৈরি".equals(r.optString("type"))) continue;
            String detail = r.optString("date") + " | " + ui(r.optString("type")) +
                " | " + r.optString("cash_bank_account") + " | " +
                money(r.optDouble("amount", 0));
            if ("অ্যাকাউন্ট স্থানান্তর".equals(r.optString("type")))
                detail = r.optString("date") + " | " + ui("স্থানান্তর: ") +
                    r.optString("transfer_from") + " → " + r.optString("transfer_to") +
                    " | " + money(r.optDouble("amount", 0));
            if (!r.optString("note").isEmpty()) detail += "\n" + r.optString("note");
            content.addView(text(detail, 15));
            shown++;
        }
        Button back = button("ফিরে যান");
        content.addView(back);
        back.setOnClickListener(v -> home());
    }

    private void addBankAccountDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        EditText name = field("ব্যাংক / অ্যাকাউন্টের নাম");
        EditText opening = field("প্রারম্ভিক ব্যালেন্স (না থাকলে ০)");
        opening.setInputType( android.text.InputType.TYPE_CLASS_NUMBER |
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        form.addView(name); form.addView(opening);
        new AlertDialog.Builder(this).setTitle(ui("নতুন ব্যাংক অ্যাকাউন্ট"))
            .setView(form).setNegativeButton(ui("বাতিল"), null)
            .setPositiveButton(ui("সংরক্ষণ"), (dialog, which) -> {
                String account = name.getText().toString().trim();
                double amount = opening.getText().toString().trim().isEmpty() ? 0 :
                    number(opening.getText().toString());
                if (account.isEmpty() || !Double.isFinite(amount) || amount < 0) {
                    Toast.makeText(this, ui("সঠিক অ্যাকাউন্টের নাম ও ব্যালেন্স দিন"), Toast.LENGTH_LONG).show();
                    return;
                }
                for (String existing : bankAccountNames()) {
                    if (existing.equalsIgnoreCase(account)) {
                        Toast.makeText(this, ui("এই ব্যাংক অ্যাকাউন্ট আগে থেকেই আছে"), Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                JSONArray before = copyJsonArray(records);
                try {
                    JSONObject created = new JSONObject();
                    created.put("party", "ব্যাংক: " + account);
                    created.put("type", "ব্যাংক অ্যাকাউন্ট তৈরি");
                    created.put("cash_bank_account", account);
                    created.put("amount", 0);
                    created.put("date", new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date()));
                    records.put(created);
                    if (amount > 0) {
                        JSONObject openingRecord = new JSONObject();
                        openingRecord.put("party", "ব্যাংক: " + account);
                        openingRecord.put("type", "ব্যাংক প্রারম্ভিক জমা");
                        openingRecord.put("cash_bank_account", account);
                        openingRecord.put("amount", amount);
                        openingRecord.put("date", created.optString("date"));
                        openingRecord.put("note", "প্রারম্ভিক ব্যালেন্স");
                        records.put(openingRecord);
                    }
                    saveRecords();
                    Toast.makeText(this, ui("ব্যাংক অ্যাকাউন্ট যোগ হয়েছে"), Toast.LENGTH_SHORT).show();
                    cashBankScreen();
                } catch (Exception e) {
                    records = before;
                    Toast.makeText(this, ui("অ্যাকাউন্ট সংরক্ষণ হয়নি"), Toast.LENGTH_LONG).show();
                }
            }).show();
    }

    private void cashBankTransactionDialog() {
        ArrayList<String> choices = new ArrayList<>();
        choices.add("নগদ জমা");
        choices.add("নগদ উত্তোলন");
        choices.add("ব্যাংক জমা");
        choices.add("ব্যাংক উত্তোলন");
        choices.add("অ্যাকাউন্ট স্থানান্তর");
        ArrayList<String> banks = bankAccountNames();
        LinearLayout form = new LinearLayout(this);
        form.setPadding(18, 8, 18, 8);
        form.setOrientation(LinearLayout.VERTICAL);
        Spinner kind = new Spinner(this);
        ArrayList<String> displayChoices = new ArrayList<>();
        for (String choice : choices) displayChoices.add(ui(choice));
        kind.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, displayChoices));
        form.addView(kind);
        ArrayList<String> accountChoices = new ArrayList<>();
        accountChoices.add("নগদ");
        accountChoices.addAll(banks);
        Spinner account = new Spinner(this);
        ArrayList<String> displayAccounts = new ArrayList<>();
        for (String choice : accountChoices) displayAccounts.add(ui(choice));
        account.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, displayAccounts));
        form.addView(account);
        Spinner destination = new Spinner(this);
        destination.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, displayAccounts));
        form.addView(destination);
        EditText amount = field("টাকার পরিমাণ");
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText note = field("বিবরণ (ঐচ্ছিক)");
        form.addView(amount); form.addView(note);
        kind.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                boolean transfer = "অ্যাকাউন্ট স্থানান্তর".equals(choices.get(position));
                account.setVisibility(transfer || position >= 2 ? View.VISIBLE : View.GONE);
                destination.setVisibility(transfer ? View.VISIBLE : View.GONE);
                if (!transfer && position < 2) account.setSelection(0);
                else if (position >= 2 && !transfer && account.getSelectedItemPosition() == 0)
                    account.setSelection(1);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        new AlertDialog.Builder(this).setTitle(ui("নগদ / ব্যাংক লেনদেন")).setView(form)
            .setNegativeButton(ui("বাতিল"), null)
            .setPositiveButton(ui("সংরক্ষণ"), (dialog, which) -> {
                String type = choices.get(kind.getSelectedItemPosition());
                double value = number(amount.getText().toString());
                if (!AccountingMath.isValidTransactionAmount(value)) {
                    Toast.makeText(this, ui("সঠিক টাকার পরিমাণ লিখুন"), Toast.LENGTH_LONG).show();
                    return;
                }
                String selected = accountChoices.get(account.getSelectedItemPosition());
                String target = accountChoices.get(destination.getSelectedItemPosition());
                if ("অ্যাকাউন্ট স্থানান্তর".equals(type) && selected.equals(target)) {
                    Toast.makeText(this, ui("উৎস ও গন্তব্য আলাদা হতে হবে"), Toast.LENGTH_LONG).show();
                    return;
                }
                if ("ব্যাংক জমা".equals(type) || "ব্যাংক উত্তোলন".equals(type)) {
                    if ("নগদ".equals(selected)) {
                        Toast.makeText(this, ui("ব্যাংক অ্যাকাউন্ট নির্বাচন করুন"), Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                double sourceBalance = "নগদ".equals(selected) ?
                    cashBankBalance("নগদ", "") : cashBankBalance("ব্যাংক", selected);
                if (("নগদ উত্তোলন".equals(type) || "ব্যাংক উত্তোলন".equals(type) ||
                    "অ্যাকাউন্ট স্থানান্তর".equals(type)) && sourceBalance < value) {
                    Toast.makeText(this, ui("অ্যাকাউন্টে পর্যাপ্ত ব্যালেন্স নেই"), Toast.LENGTH_LONG).show();
                    return;
                }
                if ("ব্যাংক জমা".equals(type) && cashBankBalance("নগদ", "") < value) {
                    Toast.makeText(this, ui("নগদ ব্যালেন্স পর্যাপ্ত নেই"), Toast.LENGTH_LONG).show();
                    return;
                }
                JSONArray before = copyJsonArray(records);
                try {
                    JSONObject r = new JSONObject();
                    r.put("party", "নগদ ও ব্যাংক");
                    r.put("type", type);
                    r.put("amount", value);
                    r.put("cash_bank_account", selected);
                    if ("অ্যাকাউন্ট স্থানান্তর".equals(type)) {
                        r.put("transfer_from", selected);
                        r.put("transfer_to", target);
                    }
                    r.put("note", note.getText().toString().trim());
                    r.put("date", new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date()));
                    records.put(r);
                    saveRecords();
                    Toast.makeText(this, ui("লেনদেন সংরক্ষণ হয়েছে"), Toast.LENGTH_SHORT).show();
                    cashBankScreen();
                } catch (Exception e) {
                    records = before;
                    Toast.makeText(this, ui("লেনদেন সংরক্ষণ হয়নি"), Toast.LENGTH_LONG).show();
                }
            }).show();
    }

    // Dedicated cash-in/out form: these movements must not require or create a party ledger.
    private void cashQuickEntry(boolean cashIn) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(20, 8, 20, 8);
        EditText amount = field("টাকার পরিমাণ");
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText note = field("বিবরণ (ঐচ্ছিক)");
        form.addView(amount);
        form.addView(note);
        new AlertDialog.Builder(this)
            .setTitle(ui(cashIn ? "ক্যাশ ইন — নগদ টাকা যোগ" : "ক্যাশ আউট — নগদ টাকা খরচ"))
            .setView(form)
            .setNegativeButton(ui("বাতিল"), null)
            .setPositiveButton(ui("সংরক্ষণ"), (dialog, which) -> {
                double value = number(amount.getText().toString());
                if (!AccountingMath.isValidTransactionAmount(value)) {
                    Toast.makeText(this, ui("সঠিক টাকার পরিমাণ লিখুন"), Toast.LENGTH_LONG).show();
                    return;
                }
                if (!cashIn && cashBankBalance("নগদ", "") < value) {
                    Toast.makeText(this, ui("হাতে নগদ পর্যাপ্ত নেই"), Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    JSONObject record = new JSONObject();
                    record.put("party", "নগদ ও ব্যাংক");
                    record.put("type", cashIn ? "নগদ জমা" : "নগদ উত্তোলন");
                    record.put("amount", value);
                    record.put("cash_bank_account", "নগদ");
                    record.put("note", note.getText().toString().trim());
                    record.put("date", new SimpleDateFormat(
                        "dd-MM-yyyy", Locale.getDefault()).format(new Date()));
                    records.put(record);
                    saveRecords();
                    Toast.makeText(this, ui("নগদ লেনদেন সংরক্ষণ হয়েছে"), Toast.LENGTH_SHORT).show();
                    home();
                } catch (Exception e) {
                    Toast.makeText(this, ui("সংরক্ষণ করা যায়নি: ") + e.getMessage(),
                        Toast.LENGTH_LONG).show();
                }
            }).show();
    }

    private void summaryReport() {
        String[][] categories = accountingCategories(true);
        String[] ids = new String[categories.length + 1];
        String[] labels = new String[categories.length + 1];
        ids[0] = CategoryAccounting.FILTER_ALL;
        labels[0] = "সব খাতের সম্মিলিত হিসাব";
        for (int i = 0; i < categories.length; i++) {
            ids[i + 1] = categories[i][0];
            labels[i + 1] = categories[i][1];
        }
        new AlertDialog.Builder(this)
            .setTitle(ui("কোন খাতের মাসিক / বার্ষিক রিপোর্ট দেখবেন?"))
            .setItems(labels, (dialog, which) -> summaryReport(ids[which]))
            .setNegativeButton(ui("বাতিল"), null)
            .show();
    }

    private void summaryReport(String selectedCategory) {
        setup("মাসিক / বার্ষিক হিসাব");
        String selectedCategoryName = "সব খাতের সম্মিলিত হিসাব";
        if (!CategoryAccounting.FILTER_ALL.equals(selectedCategory)) {
            for (String[] category : accountingCategories(true)) {
                if (category[0].equals(selectedCategory)) {
                    selectedCategoryName = category[1];
                    break;
                }
            }
        }
        content.addView(text("নির্বাচিত খাতা: " + selectedCategoryName, 18));
        String currentMonth = new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date());
        String currentYear = currentMonth.substring(0, 4);
        double monthDue = 0, monthReceived = 0, monthIncome = 0, monthExpense = 0, monthPaid = 0;
        double yearDue = 0, yearReceived = 0, yearIncome = 0, yearExpense = 0, yearPaid = 0;
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.optJSONObject(i);
            if (r == null || !CategoryAccounting.belongsToCategory(
                recordCategoryId(r), selectedCategory)) continue;
            String period = monthFromDate(r.optString("date"));
            if (period.isEmpty()) continue;
            String type = r.optString("type");
            double amount = r.optDouble("amount", 0);
            boolean businessIncomeRecord =
                AccountingMath.isBusinessIncomeRecord(type, r.optBoolean("stock_sale"));
            if (currentMonth.equals(period)) {
                if ("পাওনা".equals(type)) monthDue += amount;
                if ("জমা".equals(type)) monthReceived += amount;
                if (businessIncomeRecord) monthIncome += amount;
                if ("খরচ".equals(type)) monthExpense += amount;
                if ("পরিশোধ".equals(type)) monthPaid += amount;
            }
            if (period.startsWith(currentYear)) {
                if ("পাওনা".equals(type)) yearDue += amount;
                if ("জমা".equals(type)) yearReceived += amount;
                if (businessIncomeRecord) yearIncome += amount;
                if ("খরচ".equals(type)) yearExpense += amount;
                if ("পরিশোধ".equals(type)) yearPaid += amount;
            }
        }
        content.addView(text("এই মাস — " + currentMonth, 20));
        content.addView(text("মোট পাওনা: ৳ " + df.format(monthDue), 17));
        content.addView(text("মোট জমা: ৳ " + df.format(monthReceived), 17));
        content.addView(text("ব্যবসায়িক আয়: ৳ " + df.format(monthIncome), 17));
        content.addView(text("মোট খরচ: ৳ " + df.format(monthExpense), 17));
        content.addView(text("মোট পরিশোধ: ৳ " + df.format(monthPaid), 17));
        double monthRemaining = AccountingMath.remainingReceivable(
            monthDue, monthReceived, monthPaid);
        double monthExcess = AccountingMath.excessPayment(
            monthDue, monthReceived, monthPaid);
        content.addView(text("অবশিষ্ট পাওনা: ৳ " + df.format(monthRemaining), 19));
        if (monthExcess > 0)
            content.addView(text("অতিরিক্ত জমা: ৳ " + df.format(monthExcess), 17));
        content.addView(text("এই বছর — " + currentYear, 20));
        content.addView(text("মোট পাওনা: ৳ " + df.format(yearDue), 17));
        content.addView(text("মোট জমা: ৳ " + df.format(yearReceived), 17));
        content.addView(text("ব্যবসায়িক আয়: ৳ " + df.format(yearIncome), 17));
        content.addView(text("মোট খরচ: ৳ " + df.format(yearExpense), 17));
        content.addView(text("মোট পরিশোধ: ৳ " + df.format(yearPaid), 17));
        double yearRemaining = AccountingMath.remainingReceivable(
            yearDue, yearReceived, yearPaid);
        double yearExcess = AccountingMath.excessPayment(
            yearDue, yearReceived, yearPaid);
        content.addView(text("অবশিষ্ট পাওনা: ৳ " + df.format(yearRemaining), 19));
        if (yearExcess > 0)
            content.addView(text("অতিরিক্ত জমা: ৳ " + df.format(yearExcess), 17));
        Button exportPdf = button("মাসিক / বার্ষিক রিপোর্ট PDF সংরক্ষণ করুন");
        content.addView(exportPdf);
        final String reportCategoryName = selectedCategoryName;
        final String reportMonth = currentMonth;
        final String reportYear = currentYear;
        final double[] reportValues = {monthDue, monthReceived, monthIncome, monthExpense,
            monthPaid, monthRemaining, monthExcess, yearDue, yearReceived, yearIncome,
            yearExpense, yearPaid, yearRemaining, yearExcess};
        exportPdf.setOnClickListener(v -> createSummaryReportPdf(
            reportCategoryName, reportMonth, reportYear, reportValues));
        Button changeCategory = button("অন্য খাতের রিপোর্ট দেখুন");
        content.addView(changeCategory);
        changeCategory.setOnClickListener(v -> summaryReport());
        Button back = button("ড্যাশবোর্ডে ফিরুন");
        content.addView(back);
        back.setOnClickListener(v -> home());
    }

    private void createSummaryReportPdf(String categoryName, String month, String year,
            double[] values) {
        android.graphics.pdf.PdfDocument pdf = new android.graphics.pdf.PdfDocument();
        try {
            boolean english = "en".equals(prefs.getString("language", "bn"));
            int accent = prefs.getInt("pdf_color", blue);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            String currency = english ? "BDT " : "৳ ";
            ArrayList<String> lines = new ArrayList<>();
            lines.add(ui("নির্বাচিত খাতা: ") + categoryName);
            lines.add(ui("এই মাস — ") + month);
            lines.add(ui("মোট পাওনা: ") + currency + df.format(values[0]));
            lines.add(ui("মোট জমা: ") + currency + df.format(values[1]));
            lines.add(ui("ব্যবসায়িক আয়: ") + currency + df.format(values[2]));
            lines.add(ui("মোট খরচ: ") + currency + df.format(values[3]));
            lines.add(ui("মোট পরিশোধ: ") + currency + df.format(values[4]));
            lines.add(ui("অবশিষ্ট পাওনা: ") + currency + df.format(values[5]));
            if (values[6] > 0) lines.add(ui("অতিরিক্ত জমা: ") + currency + df.format(values[6]));
            lines.add(" ");
            lines.add(ui("এই বছর — ") + year);
            lines.add(ui("মোট পাওনা: ") + currency + df.format(values[7]));
            lines.add(ui("মোট জমা: ") + currency + df.format(values[8]));
            lines.add(ui("ব্যবসায়িক আয়: ") + currency + df.format(values[9]));
            lines.add(ui("মোট খরচ: ") + currency + df.format(values[10]));
            lines.add(ui("মোট পরিশোধ: ") + currency + df.format(values[11]));
            lines.add(ui("অবশিষ্ট পাওনা: ") + currency + df.format(values[12]));
            if (values[13] > 0) lines.add(ui("অতিরিক্ত জমা: ") + currency + df.format(values[13]));

            android.graphics.pdf.PdfDocument.PageInfo info =
                new android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create();
            android.graphics.pdf.PdfDocument.Page page = pdf.startPage(info);
            Canvas canvas = page.getCanvas();
            canvas.drawColor(Color.WHITE);
            paint.setColor(accent);
            canvas.drawRect(0, 0, 595, 112, paint);
            try {
                android.graphics.Bitmap logo = android.graphics.BitmapFactory.decodeResource(
                    getResources(), R.drawable.amar_hisab_logo);
                if (logo != null) {
                    paint.setFilterBitmap(true);
                    canvas.drawBitmap(logo, null, new android.graphics.Rect(34, 20, 92, 78), paint);
                    logo.recycle();
                }
            } catch (Exception ignored) { }
            paint.setColor(Color.WHITE);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            paint.setTextSize(21);
            canvas.drawText(ui("আমার হিসাব"), 108, 46, paint);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            paint.setTextSize(13);
            canvas.drawText(ui("মাসিক / বার্ষিক রিপোর্ট"), 108, 70, paint);
            paint.setTextSize(9);
            canvas.drawText(new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
                .format(new Date()), 450, 48, paint);
            drawDeveloperCredit(canvas, paint, 108, 94, Color.WHITE);

            float y = 145;
            for (String line : lines) {
                if (line.trim().isEmpty()) { y += 12; continue; }
                boolean section = line.startsWith("এই মাস") || line.startsWith("এই বছর") ||
                    line.startsWith("This month") || line.startsWith("This year");
                if (section) {
                    paint.setColor(Color.rgb(235, 243, 250));
                    canvas.drawRoundRect(34, y - 19, 561, y + 5, 6, 6, paint);
                } else {
                    paint.setColor(Color.rgb(250, 252, 255));
                    canvas.drawRoundRect(34, y - 17, 561, y + 5, 5, 5, paint);
                }
                paint.setColor(section ? accent : Color.rgb(42, 52, 65));
                paint.setTypeface(section ? Typeface.create("sans-serif", Typeface.BOLD) :
                    Typeface.create("sans-serif", Typeface.NORMAL));
                paint.setTextSize(section ? 13 : 11);
                canvas.drawText(line.length() > 82 ? line.substring(0, 82) : line, 46, y, paint);
                y += 29;
            }
            paint.setColor(Color.GRAY);
            paint.setTextSize(8);
            drawSavedSignature(canvas, paint, 390, 730, 145, 34);
            String signature = prefs.getString("pdf_signature", "").trim();
            if (!signature.isEmpty()) {
                paint.setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
                paint.setTextSize(10);
                canvas.drawText(signature.length() > 26 ? signature.substring(0, 26) :
                    signature, 390, 778, paint);
            }
            canvas.drawLine(380, 785, 555, 785, paint);
            canvas.drawText(ui("অনুমোদিত স্বাক্ষর"), 390, 801, paint);
            canvas.drawLine(36, 812, 559, 812, paint);
            canvas.drawText(english ? "Generated by My Hisab" :
                "আমার হিসাব থেকে তৈরি", 40, 827, paint);
            pdf.finishPage(page);

            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            pdf.writeTo(buffer);
            pendingPdfBytes = buffer.toByteArray();
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/pdf");
            intent.putExtra(Intent.EXTRA_TITLE, "Monthly-Yearly-Report-" + year + "-" +
                month.substring(5) + ".pdf");
            startActivityForResult(intent, SAVE_SUMMARY_PDF);
        } catch (Exception e) {
            pendingPdfBytes = null;
            Toast.makeText(this, ui("রিপোর্ট PDF তৈরি করা যায়নি: ") + e.getMessage(),
                Toast.LENGTH_LONG).show();
        } finally {
            pdf.close();
        }
    }

    private void smsSettings() {
        CheckBox enabled = new CheckBox(this);
        enabled.setText(ui("গ্রাহককে SMS পাঠানোর সুবিধা চালু রাখুন"));
        enabled.setTextSize(16);
        enabled.setChecked(prefs.getBoolean("sms_enabled", false));

        TextView note = text("SMS নিজে থেকে পাঠানো হবে না। চালু করলে পরে রসিদ/বকেয়ার SMS পাঠানোর সময় আপনার অনুমতি নিয়ে ফোনের মেসেজ অ্যাপ খুলবে।", 14);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(20, 12, 20, 8);
        box.addView(enabled);
        box.addView(note);

        new AlertDialog.Builder(this)
            .setTitle(ui("SMS সেটিংস"))
            .setView(box)
            .setNegativeButton(ui("বাতিল"), null)
            .setPositiveButton(ui("সংরক্ষণ"), (dialog, which) -> {
                boolean saved = prefs.edit()
                    .putBoolean("sms_enabled", enabled.isChecked()).commit();
                Toast.makeText(this, ui(saved ? "SMS সেটিংস সংরক্ষণ হয়েছে" :
                    "SMS সেটিংস সংরক্ষণ হয়নি"), Toast.LENGTH_LONG).show();
            }).show();
    }

    private void appLockSettings() {
        CheckBox enabled = new CheckBox(this);
        enabled.setText(ui("অ্যাপ চালু করলে ফোনের নিরাপত্তা যাচাই করুন"));
        enabled.setTextSize(16);
        enabled.setChecked(prefs.getBoolean("app_lock_enabled", false));

        TextView note = text("এটি ফোনের নিরাপদ বায়োমেট্রিক যাচাই ব্যবহার করবে। ফিঙ্গারপ্রিন্ট বা মুখমণ্ডল শনাক্তকরণ সমর্থিত ও চালু থাকলে ব্যবহার করা যাবে; নইলে ফোনের PIN / পাসওয়ার্ড দিয়ে যাচাই করুন।", 14);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(20, 12, 20, 8);
        box.addView(enabled);
        box.addView(note);

        new AlertDialog.Builder(this)
            .setTitle(ui("অ্যাপ লক সেটিংস"))
            .setView(box)
            .setNegativeButton(ui("বাতিল"), null)
            .setPositiveButton(ui("সংরক্ষণ"), (dialog, which) -> {
                boolean turnOn = enabled.isChecked();
                android.app.KeyguardManager keyguard =
                    (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                if (turnOn && (keyguard == null || !keyguard.isDeviceSecure())) {
                    Toast.makeText(this, ui("অ্যাপ লক চালু করতে আগে ফোনে স্ক্রিন লক সেট করুন"),
                        Toast.LENGTH_LONG).show();
                    return;
                }
                boolean saved = prefs.edit().putBoolean("app_lock_enabled", turnOn).commit();
                Toast.makeText(this, ui(saved ? "অ্যাপ লক সেটিংস সংরক্ষণ হয়েছে" :
                    "অ্যাপ লক সেটিংস সংরক্ষণ হয়নি"), Toast.LENGTH_LONG).show();
            }).show();
    }

    private void pdfSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        EditText signature = field("PDF-এ দেখানোর নাম / স্বাক্ষর");
        signature.setText(prefs.getString("pdf_signature", ""));
        Spinner colors = new Spinner(this);
        String[] names = {ui("নীল"), ui("সবুজ"), ui("বেগুনি"), ui("গাঢ় ধূসর")};
        int[] values = {
            Color.rgb(25, 90, 180), Color.rgb(20, 125, 85),
            Color.rgb(105, 65, 155), Color.rgb(55, 65, 80)
        };
        colors.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, names));
        int savedColor = prefs.getInt("pdf_color", values[0]);
        for (int i = 0; i < values.length; i++) {
            if (values[i] == savedColor) { colors.setSelection(i); break; }
        }
        box.addView(text("PDF / রসিদের রং", 16));
        box.addView(colors);
        box.addView(text("স্বাক্ষর বা নাম (ঐচ্ছিক)", 16));
        box.addView(signature);
        CheckBox includeAttachments = new CheckBox(this);
        includeAttachments.setText(ui("রসিদ PDF-এর শেষে সংযুক্ত ছবি / PDF যোগ করুন"));
        includeAttachments.setChecked(prefs.getBoolean("pdf_include_attachments", false));
        box.addView(includeAttachments);
        Button drawSignature = button("হাতে স্বাক্ষর আঁকুন");
        box.addView(drawSignature);
        drawSignature.setOnClickListener(v -> showSignaturePad());
        new AlertDialog.Builder(this).setTitle(ui("PDF ও রসিদ সেটিংস")).setView(box)
            .setNegativeButton(ui("বাতিল"), null)
            .setPositiveButton(ui("সংরক্ষণ"), (d, w) -> {
                int index = Math.max(0, colors.getSelectedItemPosition());
                boolean saved = prefs.edit()
                    .putString("pdf_signature", signature.getText().toString().trim())
                    .putInt("pdf_color", values[index])
                    .putBoolean("pdf_include_attachments", includeAttachments.isChecked()).commit();
                Toast.makeText(this, ui(saved ? "PDF সেটিংস সংরক্ষণ হয়েছে" :
                    "সেটিংস সংরক্ষণ হয়নি"), Toast.LENGTH_LONG).show();
            }).show();
    }

    private void showSignaturePad() {
        SignaturePad pad = new SignaturePad(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(16, 12, 16, 8);
        box.addView(text("নিচের জায়গায় আঙুল দিয়ে নিজের স্বাক্ষর আঁকুন", 15));
        box.addView(pad, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (int)(190 * getResources().getDisplayMetrics().density)));
        Button clear = button("আবার আঁকুন");
        box.addView(clear);
        clear.setOnClickListener(v -> pad.clear());
        new AlertDialog.Builder(this).setTitle(ui("হাতে স্বাক্ষর"))
            .setView(box).setNegativeButton(ui("বাতিল"), null)
            .setPositiveButton(ui("স্বাক্ষর সংরক্ষণ"), (d, w) -> {
                String encoded = pad.exportPngBase64();
                if (encoded == null || encoded.isEmpty()) {
                    Toast.makeText(this, ui("আগে স্বাক্ষর আঁকুন"), Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean saved = prefs.edit().putString("pdf_signature_image", encoded).commit();
                Toast.makeText(this, ui(saved ? "হাতে আঁকা স্বাক্ষর সংরক্ষণ হয়েছে" :
                    "স্বাক্ষর সংরক্ষণ হয়নি"), Toast.LENGTH_LONG).show();
            }).show();
    }

    private void drawSavedSignature(android.graphics.Canvas canvas,
            android.graphics.Paint paint, float left, float top, float width, float height) {
        String encoded = prefs.getString("pdf_signature_image", "");
        if (encoded == null || encoded.isEmpty()) return;
        try {
            byte[] bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(
                bytes, 0, bytes.length);
            if (bitmap != null) {
                paint.setAlpha(255);
                canvas.drawBitmap(bitmap, null,
                    new android.graphics.RectF(left, top, left + width, top + height), paint);
                bitmap.recycle();
            }
        } catch (Exception ignored) { }
    }

    private static class SignaturePad extends View {
        private final android.graphics.Paint ink = new android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private boolean hasInk = false;
        SignaturePad(Context context) {
            super(context);
            ink.setColor(Color.BLACK);
            ink.setStyle(android.graphics.Paint.Style.STROKE);
            ink.setStrokeWidth(5f);
            ink.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            ink.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            setBackgroundColor(Color.WHITE);
        }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawPath(path, ink);
        }
        @Override public boolean onTouchEvent(android.view.MotionEvent event) {
            float x = event.getX(), y = event.getY();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    path.moveTo(x, y); hasInk = true; invalidate(); return true;
                case MotionEvent.ACTION_MOVE:
                    path.lineTo(x, y); invalidate(); return true;
                case MotionEvent.ACTION_UP:
                    path.lineTo(x, y); invalidate(); return true;
                default: return true;
            }
        }
        void clear() { path.reset(); hasInk = false; invalidate(); }
        String exportPngBase64() {
            if (!hasInk || getWidth() <= 0 || getHeight() <= 0) return "";
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                getWidth(), getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            draw(canvas);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) {
                bitmap.recycle(); return "";
            }
            String encoded = android.util.Base64.encodeToString(out.toByteArray(),
                android.util.Base64.NO_WRAP);
            bitmap.recycle();
            return encoded;
        }
    }

    private Spinner categorySpinner;

    private void entry(String presetName) {
        entry(presetName, CategoryAccounting.CATEGORY_PERSONAL);
    }

    private void entry(String presetName, String presetCategory) {
        pendingEntryAttachments = new JSONArray();
        setup("নতুন হিসাব");
        content.addView(text("পার্টি / কাস্টমারের নাম", 17));
        party = field("নাম লিখুন");
        party.setText(presetName);
        content.addView(party);

        content.addView(text("হিসাবের ধরন", 17));
        typeSpinner = new Spinner(this);
        String[] types = {"পাওনা", "জমা", "আয়", "খরচ", "পরিশোধ"};
        String[] displayTypes = new String[types.length];
        for (int i = 0; i < types.length; i++) displayTypes[i] = ui(types[i]);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_dropdown_item, displayTypes);
        typeSpinner.setAdapter(adapter);
        content.addView(typeSpinner);

        // Every new general transaction is explicitly assigned to one ledger category.
        content.addView(text("কোন হিসাবের খাতায় যাবে", 17));
        categorySpinner = new Spinner(this);
        String[][] entryCategories = accountingCategories(false);
        String[] categoryLabels = new String[entryCategories.length];
        for (int i = 0; i < entryCategories.length; i++)
            categoryLabels[i] = ui(entryCategories[i][1]);
        categorySpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, categoryLabels));
        content.addView(categorySpinner);
        for (int i = 0; i < entryCategories.length; i++) {
            if (entryCategories[i][0].equals(presetCategory)) {
                categorySpinner.setSelection(i);
                break;
            }
        }

        content.addView(text("টাকার হিসাব / ক্যালকুলেটর", 17));
        EditText amountField = field("যেমন: 30 × 20 + 40 + 24");
        // Use the in-app calculator keypad; the numeric soft keyboard hides operators
        // and can cover the keypad on smaller phones.
        amountField.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (android.os.Build.VERSION.SDK_INT >= 21) amountField.setShowSoftInputOnFocus(false);
        content.addView(amountField);
        TextView amountPreview = text("মোট: ৳ 0", 20);
        amountPreview.setTextColor(blue);
        content.addView(amountPreview);
        amountField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int before, int count) {
                try {
                    double result = evaluateExpression(s.toString());
                    amountPreview.setText(ui("মোট: ৳ ") + df.format(result));
                } catch (Exception ex) {
                    amountPreview.setText(ui("হিসাব লিখুন: 30 × 20 + 40"));
                }
            }
            @Override public void afterTextChanged(Editable e) {}
        });
        Button details = button("+ পণ্যের বিস্তারিত (ঐচ্ছিক)");
        content.addView(details);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setVisibility(View.GONE);
        content.addView(rows);

        Button addItem = button("+ আরেকটি পণ্য যোগ করুন");
        addItem.setVisibility(View.GONE);
        content.addView(addItem);
        addItem.setOnClickListener(v -> addRow());

        totalView = text("পণ্যের মোট: ৳ 0", 18);
        totalView.setTextColor(blue);
        totalView.setVisibility(View.GONE);
        content.addView(totalView);

        EditText discountField = field("ছাড়ের টাকা (ঐচ্ছিক)");
        EditText taxField = field("VAT / Tax (%) (ঐচ্ছিক)");
        EditText extraField = field("অতিরিক্ত চার্জের টাকা (ঐচ্ছিক)");
        EditText roundOffField = field("Round-off সমন্বয় (+/- টাকা, ঐচ্ছিক)");
        for (EditText input : new EditText[]{discountField, taxField, extraField, roundOffField}) {
            input.setInputType(8194);
            input.setVisibility(View.GONE);
            content.addView(input);
        }
        Runnable updateInvoiceTotal = () -> {
            calculate();
            double subtotal = grandTotal;
            double discount = number(discountField.getText().toString());
            double taxRate = number(taxField.getText().toString());
            double extra = number(extraField.getText().toString());
            double roundOff = number(roundOffField.getText().toString());
            double tax = Math.max(0, subtotal - discount) * taxRate / 100.0;
            double finalTotal = subtotal - discount + tax + extra + roundOff;
            if (totalView != null) totalView.setText(ui("সর্বমোট: ৳ ") + df.format(finalTotal) +
                "  (" + ui("পণ্য: ") + df.format(subtotal) + ", " + ui("ছাড়: ") + df.format(discount) +
                ", VAT: " + df.format(tax) + ")");
        };
        TextWatcher invoiceWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int before, int count) {
                updateInvoiceTotal.run();
            }
            @Override public void afterTextChanged(Editable e) {}
        };
        discountField.addTextChangedListener(invoiceWatcher);
        taxField.addTextChangedListener(invoiceWatcher);
        extraField.addTextChangedListener(invoiceWatcher);
        roundOffField.addTextChangedListener(invoiceWatcher);

        details.setOnClickListener(v -> {
            boolean show = rows.getVisibility() != View.VISIBLE;
            rows.setVisibility(show ? View.VISIBLE : View.GONE);
            addItem.setVisibility(show ? View.VISIBLE : View.GONE);
            totalView.setVisibility(show ? View.VISIBLE : View.GONE);
            for (EditText input : new EditText[]{discountField, taxField, extraField, roundOffField})
                input.setVisibility(show ? View.VISIBLE : View.GONE);
            details.setText(ui(show ? "− পণ্যের বিস্তারিত বন্ধ করুন" : "+ পণ্যের বিস্তারিত (ঐচ্ছিক)"));
            if (show && rows.getChildCount() == 0) addRow();
        });

        content.addView(text("বিবরণ (ঐচ্ছিক)", 16));
        EditText note = field("প্রয়োজনে ছোট বিবরণ লিখুন");
        content.addView(note);
        Button attach = button("+ মেমো / রসিদ / ভাউচার / বিল সংযুক্ত করুন");
        content.addView(attach);
        attach.setOnClickListener(v -> pickTransactionAttachments(PICK_ENTRY_ATTACHMENTS));

        // Keep the calculator at the bottom of the entry form, after all details
        // and immediately before the separate save action.
        addCalculatorKeypad(amountField, amountPreview);

        float density = getResources().getDisplayMetrics().density;
        Button save = button("সেভ");
        save.setTextSize(13);
        save.setAllCaps(false);
        save.setPadding((int)(22 * density), (int)(5 * density),
            (int)(22 * density), (int)(5 * density));
        save.setMinHeight(0);
        save.setMinimumHeight((int)(38 * density));
        save.setMinWidth(0);
        save.setMinimumWidth((int)(76 * density));
        android.graphics.drawable.GradientDrawable saveGlow =
            new android.graphics.drawable.GradientDrawable();
        saveGlow.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        saveGlow.setCornerRadius(24 * density);
        saveGlow.setColor(Color.rgb(25, 112, 245));
        saveGlow.setStroke((int)(1.5f * density), Color.rgb(112, 177, 255));
        save.setBackground(saveGlow);
        save.setTextColor(Color.WHITE);
        save.setElevation(9 * density);
        content.addView(save);
        save.setOnClickListener(v -> saveEntry(note, amountField, discountField, taxField, extraField, roundOffField));

        Button back = button("ফিরে যান");
        content.addView(back);
        back.setOnClickListener(v -> home());
    }

    private void addCalculatorKeypad(EditText amountField, TextView amountPreview) {
        content.addView(text("ক্যালকুলেটর", 16));
        String[][] keys = {
            {"7", "8", "9", "÷"},
            {"4", "5", "6", "×"},
            {"1", "2", "3", "−"},
            {"0", ".", "%", "+"},
            {"(", ")", "⌫", "C"}
        };
        for (String[] keyRow : keys) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (String key : keyRow) {
                Button keyButton = button(key);
                keyButton.setTextSize(18);
                keyButton.setAllCaps(false);
                keyButton.setMinHeight((int)(48 * getResources().getDisplayMetrics().density));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(3, 3, 3, 3);
                line.addView(keyButton, lp);
                keyButton.setOnClickListener(v -> {
                    Editable editable = amountField.getText();
                    int start = Math.max(0, amountField.getSelectionStart());
                    int end = Math.max(start, amountField.getSelectionEnd());
                    if ("C".equals(key)) {
                        amountField.setText("");
                    } else if ("⌫".equals(key)) {
                        if (start != end) editable.delete(start, end);
                        else if (start > 0) editable.delete(start - 1, start);
                        amountField.setSelection(Math.max(0, Math.min(start, amountField.length())));
                    } else {
                        editable.replace(start, end, key);
                        amountField.setSelection(Math.min(start + key.length(), amountField.length()));
                    }
                    amountField.requestFocus();
                });
            }
            content.addView(line);
        }
        Button equals = button("=  ফলাফল বসান");
        equals.setTextSize(18);
        equals.setAllCaps(false);
        equals.setMinHeight((int)(50 * getResources().getDisplayMetrics().density));
        content.addView(equals, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        equals.setOnClickListener(v -> {
            try {
                double value = evaluateExpression(amountField.getText().toString());
                if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid result");
                String result = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
                amountField.setText(result);
                amountField.setSelection(amountField.length());
                amountPreview.setText(ui("মোট: ৳ ") + df.format(value));
            } catch (Exception ex) {
                amountPreview.setText(ui("সঠিক হিসাব লিখুন"));
                Toast.makeText(this, ui("সঠিক হিসাব লিখুন"), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void addRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(8, 8, 8, 12);
        row.setBackgroundColor(Color.WHITE);

        EditText product = field("পণ্যের নাম");
        EditText qty = field("পরিমাণ");
        EditText rate = field("প্রতি এককের দাম");
        TextView line = text("এই পণ্যের মোট: ৳ 0", 17);

        row.addView(product);
        row.addView(qty);
        row.addView(rate);
        row.addView(line);
        rows.addView(row);

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int before, int count) {
                calculate();
            }
            @Override public void afterTextChanged(Editable e) {}
        };
        qty.addTextChangedListener(watcher);
        rate.addTextChangedListener(watcher);
    }

    private double number(String value) {
        return AccountingMath.parseAmount(value);
    }

    private double evaluateExpression(String raw) {
        return AccountingMath.evaluateExpression(raw);
    }

    private void calculate() {
        grandTotal = 0;
        if (rows == null) return;
        for (int i = 0; i < rows.getChildCount(); i++) {
            LinearLayout row = (LinearLayout) rows.getChildAt(i);
            EditText qty = (EditText) row.getChildAt(1);
            EditText rate = (EditText) row.getChildAt(2);
            TextView line = (TextView) row.getChildAt(3);
            String productName = ((EditText) row.getChildAt(0)).getText().toString().trim();
            double quantity = number(qty.getText().toString());
            double unitPrice = number(rate.getText().toString());
            double amount = AccountingMath.isValidLineItem(productName, quantity, unitPrice)
                ? AccountingMath.lineTotal(quantity, unitPrice) : 0;
            line.setText(ui("এই পণ্যের মোট: ৳ ") + df.format(amount));
            grandTotal += amount;
        }
        if (totalView != null) totalView.setText(ui("পণ্যের মোট: ৳ ") + df.format(grandTotal));
    }

    private void saveEntry(EditText note, EditText amountField, EditText discountField,
                           EditText taxField, EditText extraField, EditText roundOffField) {
        String name = party.getText().toString().trim();
        if (name.isEmpty()) {
            party.setError(ui("পার্টির নাম লিখুন"));
            return;
        }

        boolean hasItemDetails = false;
        for (int i = 0; i < rows.getChildCount(); i++) {
            LinearLayout row = (LinearLayout) rows.getChildAt(i);
            String productName = ((EditText) row.getChildAt(0)).getText().toString().trim();
            String quantityText = ((EditText) row.getChildAt(1)).getText().toString().trim();
            String priceText = ((EditText) row.getChildAt(2)).getText().toString().trim();
            if (productName.isEmpty() && quantityText.isEmpty() && priceText.isEmpty()) continue;
            hasItemDetails = true;
            double quantity = number(quantityText);
            double unitPrice = number(priceText);
            if (!AccountingMath.isValidLineItem(productName, quantity, unitPrice)) {
                if (productName.isEmpty()) ((EditText) row.getChildAt(0)).setError(ui("পণ্যের নাম লিখুন"));
                else if (quantity <= 0 || !Double.isFinite(quantity))
                    ((EditText) row.getChildAt(1)).setError(ui("সঠিক পরিমাণ লিখুন"));
                else ((EditText) row.getChildAt(2)).setError(ui("সঠিক একক দাম লিখুন"));
                Toast.makeText(this, ui("পণ্যের বিস্তারিত দিলে নাম, পরিমাণ ও দাম পূরণ করুন"),
                    Toast.LENGTH_LONG).show();
                return;
            }
        }

        calculate();
        double subtotal;
        try {
            subtotal = hasItemDetails ? grandTotal : evaluateExpression(amountField.getText().toString());
        } catch (Exception ex) {
            subtotal = 0;
        }
        double discount = number(discountField.getText().toString());
        double taxRate = number(taxField.getText().toString());
        double extraCharge = number(extraField.getText().toString());
        double roundOff = number(roundOffField.getText().toString());
        if (!Double.isFinite(discount) || !Double.isFinite(taxRate) ||
            !Double.isFinite(extraCharge) || !Double.isFinite(roundOff) ||
            discount < 0 || discount > subtotal || taxRate < 0 || taxRate > 100 ||
            extraCharge < 0) {
            Toast.makeText(this, ui("ছাড়, VAT/Tax ও অতিরিক্ত চার্জের পরিমাণ যাচাই করুন"), Toast.LENGTH_LONG).show();
            return;
        }
        double taxAmount = Math.max(0, subtotal - discount) * taxRate / 100.0;
        double entryAmount = subtotal - discount + taxAmount + extraCharge + roundOff;
        if (entryAmount <= 0 || !Double.isFinite(entryAmount)) {
            amountField.setError(ui("সঠিক টাকার পরিমাণ লিখুন"));
            Toast.makeText(this, ui("পার্টির নাম ও টাকার পরিমাণ দিন"), Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            JSONObject record = new JSONObject();
            record.put("party", name);
            String[][] availableCategories = accountingCategories(false);
            int categoryPosition = categorySpinner == null ? 0 : categorySpinner.getSelectedItemPosition();
            record.put("category_id", availableCategories[Math.max(0,
                Math.min(availableCategories.length - 1, categoryPosition))][0]);
            record.put("type", new String[]{"পাওনা", "জমা", "আয়", "খরচ", "পরিশোধ"}[typeSpinner.getSelectedItemPosition()]);
            record.put("amount", entryAmount);
            record.put("subtotal", subtotal);
            record.put("discount", discount);
            record.put("tax_rate", taxRate);
            record.put("tax_amount", taxAmount);
            record.put("additional_charge", extraCharge);
            record.put("round_off", roundOff);
            record.put("note", note.getText().toString().trim());
            record.put("date", new SimpleDateFormat(
                "dd-MM-yyyy", Locale.getDefault()).format(new Date()));

            JSONArray items = new JSONArray();
            for (int i = 0; i < rows.getChildCount(); i++) {
                LinearLayout row = (LinearLayout) rows.getChildAt(i);
                String product = ((EditText) row.getChildAt(0)).getText().toString().trim();
                double qty = number(((EditText) row.getChildAt(1)).getText().toString());
                double rate = number(((EditText) row.getChildAt(2)).getText().toString());
                if (AccountingMath.isValidLineItem(product, qty, rate)) {
                    JSONObject item = new JSONObject();
                    item.put("product", product);
                    item.put("quantity", qty);
                    item.put("rate", rate);
                    item.put("total", AccountingMath.lineTotal(qty, rate));
                    items.put(item);
                }
            }
            record.put("items", items);
            record.put("attachments", pendingEntryAttachments);
            showEntrySavePreview(record);
        } catch (Exception e) {
            Toast.makeText(this, ui("সেভ করা যায়নি: ") + e.getMessage(),
                Toast.LENGTH_SHORT).show();
        }
    }

    private void showEntrySavePreview(JSONObject record) {
        try {
            StringBuilder preview = new StringBuilder();
            preview.append(ui("পার্টির নাম")).append(": ")
                .append(record.optString("party")).append("\n\n");
            preview.append(ui("হিসাবের ধরন")).append(": ")
                .append(ui(record.optString("type"))).append("\n");
            preview.append(ui("টাকার পরিমাণ")).append(": ৳ ")
                .append(df.format(record.optDouble("amount"))).append("\n");
            preview.append(ui("সাবটোটাল")).append(": ৳ ")
                .append(df.format(record.optDouble("subtotal"))).append("\n");
            preview.append(ui("ছাড়")).append(": ৳ ")
                .append(df.format(record.optDouble("discount"))).append("\n");
            preview.append("VAT / Tax: ৳ ")
                .append(df.format(record.optDouble("tax_amount"))).append("\n");
            preview.append(ui("অতিরিক্ত চার্জ")).append(": ৳ ")
                .append(df.format(record.optDouble("additional_charge"))).append("\n");
            preview.append(ui("বিবরণ")).append(": ")
                .append(record.optString("note", "—")).append("\n");
            preview.append(ui("তারিখ")).append(": ")
                .append(record.optString("date")).append("\n");
            JSONArray items = record.optJSONArray("items");
            if (items != null && items.length() > 0) {
                preview.append("\n").append(ui("পণ্যের বিস্তারিত")).append(":\n");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    preview.append("• ").append(item.optString("product"))
                        .append(" — ").append(df.format(item.optDouble("quantity")))
                        .append(" × ৳ ").append(df.format(item.optDouble("rate")))
                        .append(" = ৳ ").append(df.format(item.optDouble("total"))).append("\n");
                }
            }
            JSONArray attachments = record.optJSONArray("attachments");
            if (attachments != null && attachments.length() > 0) {
                preview.append("\n").append(ui("সংযুক্ত নথি")).append(": ")
                    .append(attachments.length());
            }

            new AlertDialog.Builder(this)
                .setTitle(ui("সেভ করার আগে হিসাব যাচাই করুন"))
                .setMessage(preview.toString())
                .setNegativeButton(ui("সংশোধন করুন"), (dialog, which) -> dialog.dismiss())
                .setPositiveButton(ui("চূড়ান্ত সেভ"), null)
                .setCancelable(true)
                .create();
            AlertDialog review = new AlertDialog.Builder(this)
                .setTitle(ui("সেভ করার আগে হিসাব যাচাই করুন"))
                .setMessage(preview.toString())
                .setNegativeButton(ui("সংশোধন করুন"), (dialog, which) -> dialog.dismiss())
                .setPositiveButton(ui("চূড়ান্ত সেভ"), null)
                .create();
            review.setOnShowListener(dialog -> {
                Button finalSave = review.getButton(AlertDialog.BUTTON_POSITIVE);
                if (finalSave != null) {
                    finalSave.setOnClickListener(v -> {
                        try {
                            records.put(record);
                            saveRecords();
                            review.dismiss();
                            Toast.makeText(this, ui("হিসাব সেভ হয়েছে"), Toast.LENGTH_LONG).show();
                            home();
                        } catch (Exception e) {
                            Toast.makeText(this, ui("সেভ করা যায়নি: ") + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });
            review.show();
        } catch (Exception e) {
            Toast.makeText(this, ui("হিসাব যাচাইয়ের প্রিভিউ তৈরি হয়নি: ") + e.getMessage(),
                Toast.LENGTH_LONG).show();
        }
    }

    private void ledger() {
        ledger(CategoryAccounting.FILTER_ALL);
    }

    private void ledger(String initialCategory) {
        setup("পার্টির হিসাব", true);
        final float density = getResources().getDisplayMetrics().density;
        content.addView(text("কোন খাতার পার্টি দেখবেন", 16));
        Spinner ledgerCategory = new Spinner(this);
        String[][] ledgerCategories = accountingCategories(true);
        String[] ledgerCategoryLabels = new String[ledgerCategories.length + 1];
        String[] ledgerCategoryIds = new String[ledgerCategories.length + 1];
        ledgerCategoryLabels[0] = ui("সব খাতা");
        ledgerCategoryIds[0] = CategoryAccounting.FILTER_ALL;
        for (int i = 0; i < ledgerCategories.length; i++) {
            ledgerCategoryLabels[i + 1] = ui(ledgerCategories[i][1]);
            ledgerCategoryIds[i + 1] = ledgerCategories[i][0];
        }
        ledgerCategory.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, ledgerCategoryLabels));
        int initialPosition = 0;
        for (int i = 0; i < ledgerCategoryIds.length; i++)
            if (ledgerCategoryIds[i].equals(initialCategory)) initialPosition = i;
        ledgerCategory.setSelection(initialPosition);
        content.addView(ledgerCategory);

        EditText search = field("নাম খুঁজুন");
        content.addView(search);

        // Quick alphabet filters for English and Bangla party names.
        final String[] selectedInitial = {""};
        final Runnable[] refreshListHolder = {null};
        LinearLayout alphabetRows = new LinearLayout(this);
        alphabetRows.setOrientation(LinearLayout.VERTICAL);
        content.addView(alphabetRows);
        String[][] alphabets = {
            {"সব", "A","B","C","D","E","F","G","H","I","J","K","L","M",
                "N","O","P","Q","R","S","T","U","V","W","X","Y","Z"},
            {"অ","আ","ই","ঈ","উ","ঊ","ঋ","এ","ঐ","ও","ঔ","ক","খ","গ","ঘ","ঙ",
                "চ","ছ","জ","ঝ","ঞ","ট","ঠ","ড","ঢ","ণ","ত","থ","দ","ধ","ন",
                "প","ফ","ব","ভ","ম","য","র","ল","শ","ষ","স","হ"}
        };
        for (String[] alphabet : alphabets) {
            android.widget.HorizontalScrollView alphabetScroll =
                new android.widget.HorizontalScrollView(this);
            alphabetScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout alphabetLine = new LinearLayout(this);
            alphabetLine.setOrientation(LinearLayout.HORIZONTAL);
            for (String letter : alphabet) {
                Button letterButton = button(letter);
                letterButton.setMinWidth(0);
                letterButton.setPadding(12, 5, 12, 5);
                alphabetLine.addView(letterButton);
                letterButton.setOnClickListener(v -> {
                    selectedInitial[0] = "সব".equals(letter) ? "" : letter;
                    refreshListHolder[0].run();
                });
            }
            alphabetScroll.addView(alphabetLine);
            alphabetRows.addView(alphabetScroll);
        }

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);

        Runnable refreshList = () -> {
            list.removeAllViews();
            TreeSet<String> names = new TreeSet<>(nameComparator());
            for (int i = 0; i < partyNames.length(); i++) {
                String savedName = partyNames.optString(i, "").trim();
                if (!savedName.isEmpty()) names.add(savedName);
            }
            for (int i = 0; i < records.length(); i++) {
                JSONObject r = records.optJSONObject(i);
                if (r == null || isCashBankRecord(r) || isRentalLedgerRecord(r)) continue;
                String n = r.optString("party", "").trim();
                if (!n.isEmpty()) names.add(n);
            }

            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            if (names.isEmpty()) {
                list.addView(text("এখনো কোনো পার্টির হিসাব নেই", 18));
                return;
            }
            String selectedCategory = ledgerCategoryIds[ledgerCategory.getSelectedItemPosition()];
            for (String name : names) {
                if (!query.isEmpty() &&
                    !name.toLowerCase(Locale.ROOT).contains(query)) continue;
                if (!selectedInitial[0].isEmpty() &&
                    !name.regionMatches(true, 0, selectedInitial[0], 0,
                        selectedInitial[0].length())) continue;
                boolean hasTransactions = false;
                for (int i = 0; i < records.length(); i++) {
                    JSONObject record = records.optJSONObject(i);
                    if (record != null && !isCashBankRecord(record) && !isRentalLedgerRecord(record) &&
                        name.equals(record.optString("party").trim()) &&
                        CategoryAccounting.belongsToCategory(recordCategoryId(record),
                            selectedCategory)) {
                        hasTransactions = true;
                        break;
                    }
                }
                if (!hasTransactions && !CategoryAccounting.FILTER_ALL.equals(selectedCategory)) continue;
                double balance = partyBalance(name, selectedCategory);
                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.HORIZONTAL);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setPadding((int)(14 * density), (int)(13 * density),
                    (int)(14 * density), (int)(13 * density));
                item.setBackgroundResource(android.R.drawable.list_selector_background);
                TextView partyName = text(name, 16);
                partyName.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                item.addView(partyName, new LinearLayout.LayoutParams(0, -2, 1));
                String balanceLabel = hasTransactions
                    ? "৳ " + df.format(Math.abs(balance)) +
                        ui(balance >= 0 ? " পাওনা" : " জমা")
                    : ui("লেনদেন নেই");
                TextView partyBalanceText = text(balanceLabel, 14);
                partyBalanceText.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
                partyBalanceText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                partyBalanceText.setTextColor(balance > 0 ? Color.rgb(198, 55, 55) :
                    (balance < 0 ? Color.rgb(35, 145, 90) : Color.DKGRAY));
                item.addView(partyBalanceText,
                    new LinearLayout.LayoutParams(-2, -2));
                list.addView(item, new LinearLayout.LayoutParams(-1, -2));
                View divider = new View(this);
                divider.setBackgroundColor(Color.rgb(232, 235, 240));
                list.addView(divider, new LinearLayout.LayoutParams(-1, (int)(1 * density)));
                item.setOnClickListener(v -> partyDetails(name, selectedCategory));
            }
        };
        refreshListHolder[0] = refreshList;
        refreshList.run();
        ledgerCategory.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                                 int position, long id) {
                refreshList.run();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int before, int count) {
                refreshList.run();
            }
            @Override public void afterTextChanged(Editable e) {}
        });

        Button back = button("ফিরে যান");
        content.addView(back);
        back.setOnClickListener(v -> home());
    }

    private Comparator<String> nameComparator() {
        final Collator bangla = Collator.getInstance(new Locale("bn", "BD"));
        final Collator english = Collator.getInstance(Locale.ENGLISH);
        return (a, b) -> {
            boolean aBangla = containsBangla(a);
            boolean bBangla = containsBangla(b);
            if (aBangla && bBangla) {
                int result = bangla.compare(a, b);
                return result != 0 ? result : a.compareTo(b);
            }
            if (!aBangla && !bBangla) {
                int result = english.compare(a, b);
                return result != 0 ? result : a.compareTo(b);
            }
            return aBangla ? -1 : 1;
        };
    }

    private boolean containsBangla(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '\u0980' && c <= '\u09FF') return true;
        }
        return false;
    }

    private void partyDetails(String name) {
        partyDetails(name, CategoryAccounting.FILTER_ALL);
    }

    private void partyDetails(String name, String selectedCategory) {
        setup(name + " — " + ui("পার্টির হিসাব"));
        double balance = partyBalance(name, selectedCategory);

        content.addView(text(
            ui(balance >= 0 ? "অবশিষ্ট পাওনা: ৳ " : "অতিরিক্ত জমা: ৳ ") +
            df.format(Math.abs(balance)), 21));

        Button statementPdf = button("পার্টির স্টেটমেন্ট PDF / WhatsApp");
        content.addView(statementPdf);
        statementPdf.setOnClickListener(v -> createPartyStatementPdf(name, selectedCategory));

        if (prefs.getBoolean("sms_enabled", false)) {
            final double smsBalance = balance;
            Button sms = button("এই পার্টিকে SMS পাঠান");
            content.addView(sms);
            sms.setOnClickListener(v -> sendPartyBalanceSms(name, smsBalance));
        }

        boolean hasTransactions = false;
        for (int i = records.length() - 1; i >= 0; i--) {
            JSONObject r = records.optJSONObject(i);
            if (r == null || isCashBankRecord(r) || isRentalLedgerRecord(r) ||
                !name.equals(r.optString("party")) ||
                !CategoryAccounting.belongsToCategory(recordCategoryId(r),
                    selectedCategory)) continue;
            hasTransactions = true;
            String detail = r.optString("date") + " | " +
                ui(r.optString("type")) + " | " +
                money(r.optDouble("amount", 0));
            if (!r.optString("note", "").isEmpty()) {
                detail += "\n" + r.optString("note");
            }
            JSONArray items = r.optJSONArray("items");
            if (items != null) {
                for (int j = 0; j < items.length(); j++) {
                    JSONObject item = items.optJSONObject(j);
                    if (item == null) continue;
                    detail += "\n" + item.optString("product") + " : " +
                        df.format(item.optDouble("quantity", 0)) + " × " +
                        df.format(item.optDouble("rate", 0)) + " = " +
                        money(item.optDouble("total", 0));
                }
            }
            content.addView(text(detail, 16));
            View divider = new View(this);
            divider.setBackgroundColor(Color.LTGRAY);
            content.addView(divider, new LinearLayout.LayoutParams(-1, 1));
        }

        if (!hasTransactions) content.addView(text("এখনো কোনো লেনদেন নেই। নতুন হিসাব যোগ করুন।", 16));

        Button add = button("+ এই পার্টির নতুন হিসাব");
        content.addView(add);
        add.setOnClickListener(v -> entry(name,
            CategoryAccounting.FILTER_ALL.equals(selectedCategory)
                ? CategoryAccounting.CATEGORY_PERSONAL : selectedCategory));

        Button back = button("পার্টির তালিকায় ফিরুন");
        content.addView(back);
    
        back.setOnClickListener(v -> ledger(selectedCategory));
    }

    private void createPartyStatementPdf(String name, String selectedCategory) {
        android.graphics.pdf.PdfDocument pdf = new android.graphics.pdf.PdfDocument();
        try {
            boolean english = "en".equals(prefs.getString("language", "bn"));
            int accent = prefs.getInt("pdf_color", blue);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            paint.setTextSize(10);
            ArrayList<String> lines = new ArrayList<>();
            double running = 0, totalDue = 0, totalPaid = 0;
            for (int i = 0; i < records.length(); i++) {
                JSONObject r = records.optJSONObject(i);
                if (r == null || isCashBankRecord(r) || isRentalLedgerRecord(r) ||
                    !name.equals(r.optString("party").trim()) ||
                    !CategoryAccounting.belongsToCategory(recordCategoryId(r),
                        selectedCategory)) continue;
                String type = r.optString("type", "");
                double amount = r.optDouble("amount", 0);
                running = AccountingMath.applyPartyTransaction(running, type, amount);
                if ("পাওনা".equals(type)) totalDue += amount;
                if ("জমা".equals(type) || "পরিশোধ".equals(type)) totalPaid += amount;
                String currency = english ? "BDT " : "৳ ";
                lines.add(r.optString("date") + "  |  " + ui(type) + "  |  " +
                    currency + df.format(amount));
                lines.add(ui("চলতি ব্যালেন্স: ৳ ") + df.format(Math.abs(running)) +
                    (running >= 0 ? (english ? " (Due)" : " (পাওনা)") :
                        (english ? " (Credit)" : " (অতিরিক্ত জমা)")));
                String note = r.optString("note", "").trim();
                if (!note.isEmpty()) lines.add(ui("বিবরণ: ") + note);
                JSONArray items = r.optJSONArray("items");
                if (items != null) {
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject item = items.optJSONObject(j);
                        if (item == null) continue;
                        lines.add(item.optString("product") + " | " +
                            df.format(item.optDouble("quantity", 0)) + " × " +
                            df.format(item.optDouble("rate", 0)) + " = " +
                            currency + df.format(item.optDouble("total", 0)));
                    }
                }
                lines.add(" ");
            }
            if (lines.isEmpty()) lines.add(ui("এখনো কোনো লেনদেন নেই।"));
            lines.add(ui("মোট পাওনা এন্ট্রি: ৳ ") + df.format(totalDue));
            lines.add(ui("মোট জমা / পরিশোধ: ৳ ") + df.format(totalPaid));
            lines.add(ui("বর্তমান ব্যালেন্স: ৳ ") + df.format(Math.abs(running)) +
                (running >= 0 ? (english ? " (Due)" : " (পাওনা)") :
                    (english ? " (Credit)" : " (অতিরিক্ত জমা)")));

            ArrayList<String> wrapped = new ArrayList<>();
            paint.setTextSize(10);
            for (String line : lines) {
                if (line.isEmpty()) { wrapped.add(" "); continue; }
                String remaining = line;
                while (!remaining.isEmpty()) {
                    int count = paint.breakText(remaining, true, 485, null);
                    if (count <= 0) count = Math.min(1, remaining.length());
                    wrapped.add(remaining.substring(0, count));
                    remaining = remaining.substring(count);
                }
            }

            final int linesPerPage = 32;
            int pageNo = 0;
            for (int startLine = 0; startLine < wrapped.size(); startLine += linesPerPage) {
                pageNo++;
                android.graphics.pdf.PdfDocument.PageInfo info =
                    new android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, pageNo).create();
                android.graphics.pdf.PdfDocument.Page page = pdf.startPage(info);
                Canvas canvas = page.getCanvas();

                paint.setColor(Color.rgb(247, 250, 254));
                canvas.drawColor(Color.WHITE);
                canvas.drawRect(0, 0, 595, 112, paint);
                paint.setColor(accent);
                canvas.drawRect(0, 0, 595, 108, paint);
                try {
                    android.graphics.Bitmap logo = android.graphics.BitmapFactory.decodeResource(
                        getResources(), R.drawable.amar_hisab_logo);
                    if (logo != null) {
                        paint.setFilterBitmap(true);
                        canvas.drawBitmap(logo, null, new android.graphics.Rect(34, 20, 92, 78), paint);
                        logo.recycle();
                    }
                } catch (Exception ignored) { }
                paint.setColor(Color.WHITE);
                paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                paint.setTextSize(21);
                canvas.drawText(ui("আমার হিসাব"), 108, 46, paint);
                paint.setTextSize(13);
                paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                canvas.drawText(ui("পার্টি স্টেটমেন্ট"), 108, 70, paint);
                paint.setTextSize(9);
                canvas.drawText((english ? "STATEMENT  |  Page " : "স্টেটমেন্ট  |  পৃষ্ঠা ") +
                    pageNo, 420, 48, paint);
                drawDeveloperCredit(canvas, paint, 108, 94, Color.WHITE);

                paint.setColor(Color.rgb(235, 243, 250));
                canvas.drawRoundRect(30, 122, 565, 164, 10, 10, paint);
                paint.setColor(Color.rgb(35, 48, 65));
                paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                paint.setTextSize(12);
                canvas.drawText(ui("পার্টি: ") + name, 44, 148, paint);
                paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                paint.setTextSize(9);
                canvas.drawText(ui("তারিখ: ") + new SimpleDateFormat("dd-MM-yyyy",
                    Locale.getDefault()).format(new Date()), 390, 148, paint);

                int endLine = Math.min(startLine + linesPerPage, wrapped.size());
                int summaryStart = Math.max(0, wrapped.size() - 3);
                float y = 188;
                for (int i = startLine; i < endLine; i++) {
                    String line = wrapped.get(i);
                    if (line.trim().isEmpty()) { y += 7; continue; }
                    boolean summary = i >= summaryStart;
                    if (summary) {
                        paint.setColor(Color.rgb(235, 243, 250));
                        canvas.drawRoundRect(34, y - 13, 561, y + 6, 5, 5, paint);
                    } else if ((i - startLine) % 2 == 0) {
                        paint.setColor(Color.rgb(250, 252, 255));
                        canvas.drawRoundRect(34, y - 13, 561, y + 6, 5, 5, paint);
                    }
                    paint.setColor(summary ? accent : Color.rgb(42, 52, 65));
                    paint.setTypeface(summary
                        ? Typeface.create("sans-serif", Typeface.BOLD)
                        : Typeface.create("sans-serif", Typeface.NORMAL));
                    paint.setTextSize(summary ? 10 : 9);
                    canvas.drawText(line, 44, y, paint);
                    y += 16;
                }

                boolean lastPage = endLine >= wrapped.size();
                if (lastPage) {
                    paint.setColor(Color.rgb(70, 80, 92));
                    paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                    paint.setTextSize(9);
                    drawSavedSignature(canvas, paint, 390, 730, 145, 34);
                    String signature = prefs.getString("pdf_signature", "").trim();
                    if (!signature.isEmpty()) {
                        paint.setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
                        canvas.drawText(signature.length() > 26
                            ? signature.substring(0, 26) : signature, 390, 778, paint);
                    }
                    canvas.drawLine(380, 785, 555, 785, paint);
                    paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                    canvas.drawText(ui("অনুমোদিত স্বাক্ষর"), 390, 801, paint);
                }
                paint.setColor(Color.GRAY);
                paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                paint.setTextSize(8);
                canvas.drawLine(36, 812, 559, 812, paint);
                canvas.drawText((english ? "Generated by My Hisab  |  Page " :
                    "আমার হিসাব থেকে তৈরি  |  পৃষ্ঠা ") + pageNo, 40, 827, paint);
                pdf.finishPage(page);
            }

            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            pdf.writeTo(buffer);
            pendingPdfBytes = buffer.toByteArray();
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/pdf");
            String safeName = name.replaceAll("[^A-Za-z0-9_-]", "_");
            intent.putExtra(Intent.EXTRA_TITLE, "Party-Statement-" + safeName + ".pdf");
            startActivityForResult(intent, SAVE_PARTY_PDF);
        } catch (Exception e) {
            pendingPdfBytes = null;
            Toast.makeText(this, ui("পার্টির PDF তৈরি করা যায়নি: ") + e.getMessage(),
                Toast.LENGTH_LONG).show();
        } finally {
            pdf.close();
        }
    }

    private void sendPartyBalanceSms(String name, double balance) {
        if (!prefs.getBoolean("sms_enabled", false)) {
            Toast.makeText(this, ui("আগে SMS সেটিংস চালু করুন"), Toast.LENGTH_SHORT).show();
            return;
        }
        EditText phone = field("মোবাইল নম্বর লিখুন");
        new AlertDialog.Builder(this)
            .setTitle(ui("SMS পাঠানোর নম্বর"))
            .setView(phone)
            .setNegativeButton(ui("বাতিল"), null)
            .setPositiveButton(ui("মেসেজ অ্যাপ খুলুন"), (dialog, which) -> {
                String number = phone.getText().toString().trim();
                if (number.isEmpty()) {
                    Toast.makeText(this, ui("মোবাইল নম্বর লিখুন"), Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean english = "en".equals(prefs.getString("language", "bn"));
                String amount = df.format(Math.abs(balance));
                String body = english
                    ? "Dear " + name + ", your current " +
                        (balance >= 0 ? "due balance is BDT " : "credit balance is BDT ") + amount +
                        ". — Hisab"
                    : "প্রিয় " + name + ", আপনার বর্তমান " +
                        (balance >= 0 ? "পাওনা " : "অতিরিক্ত জমা ") + "৳ " + amount +
                        "। — হিসাব";
                try {
                    Intent intent = new Intent(Intent.ACTION_SENDTO,
                        android.net.Uri.parse("smsto:" + number));
                    intent.putExtra("sms_body", body);
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, ui("মেসেজ অ্যাপ খোলা যায়নি"), Toast.LENGTH_LONG).show();
                }
            }).show();
    }

    private void manageRecords() {
        setup("হিসাব ব্যবস্থাপনা");
        content.addView(text("যে হিসাবটি মুছবেন সেটি নির্বাচন করুন। মুছলে আগে রিসাইকেল বিনে যাবে।", 16));
        for (int i = records.length() - 1; i >= 0; i--) {
            final int index = i;
            JSONObject r = records.optJSONObject(i);
            if (r == null) continue;
            String label = r.optString("party") + " | " + ui(r.optString("type")) +
                " | " + money(r.optDouble("amount", 0)) +
                " | " + r.optString("date");
            Button item = button(label);
            item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            content.addView(item);
            item.setOnClickListener(v -> recordActions(index));
        }
        Button back = button("ফিরে যান");
        content.addView(back);
        back.setOnClickListener(v -> home());
    }

    private boolean isModuleLinkedRecord(JSONObject record) {
        return record != null && (
            !record.optString("rent_key").isEmpty() ||
            !record.optString("rent_payment_key").isEmpty() ||
            !record.optString("rental_charge_key").isEmpty() ||
            !record.optString("rental_charge_payment_key").isEmpty() ||
            !record.optString("stock_sale_payment_key").isEmpty() ||
            !record.optString("stock_item").isEmpty() ||
            record.optBoolean("deposit_refund") ||
            record.optBoolean("deposit_transaction"));
    }

    private void recordActions(int index) {
        JSONObject item = records.optJSONObject(index);
        if (item == null) return;
        if (isModuleLinkedRecord(item)) {
            new AlertDialog.Builder(this)
                .setTitle(ui("সংযুক্ত হিসাব"))
                .setMessage(ui("এই হিসাবটি সংশ্লিষ্ট ভাড়া, স্টক বা জামানত মডিউল দ্বারা নিয়ন্ত্রিত। নিরাপত্তার জন্য এখানে সম্পাদনা বা মুছে ফেলা যাবে না; তবে রসিদ PDF ও সংযুক্ত নথি দেখা যাবে।"))
                .setItems(new String[]{ui("রসিদ / ইনভয়েস PDF সংরক্ষণ"),
                    ui("সংযুক্ত নথি দেখুন / যোগ করুন")}, (dialog, which) -> {
                        if (which == 0) exportReceiptPdf(item);
                        else manageRecordAttachments(index);
                    })
                .setNegativeButton(ui("বাতিল"), null)
                .show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(ui("হিসাব ব্যবস্থাপনা"))
            .setItems(new String[]{ui("সংযুক্ত নথি দেখুন / যোগ করুন"),
                ui("রসিদ / ইনভয়েস PDF সংরক্ষণ"), ui("হিসাব সম্পাদনা"),
                ui("রিসাইকেল বিনে সরান")}, (dialog, which) -> {
                if (which == 0) { manageRecordAttachments(index); return; }
                if (which == 1) { exportReceiptPdf(item); return; }
                if (which == 2) { editRecord(index); return; }
                new AlertDialog.Builder(this)
                    .setTitle(ui("নিশ্চিত করুন"))
                    .setMessage(ui("এই হিসাবটি রিসাইকেল বিনে পাঠাবেন?"))
                    .setNegativeButton(ui("না"), null)
                    .setPositiveButton(ui("হ্যাঁ, সরান"), (d, w) -> {
                        JSONObject current = records.optJSONObject(index);
                        if (current == null) return;
                        JSONArray oldRecords = records;
                        JSONArray oldTrash = trash;
                        try {
                            records = new JSONArray(records.toString());
                            trash = new JSONArray(trash.toString());
                            trash.put(records.optJSONObject(index));
                            records.remove(index);
                            if (!saveRecordsAndTrash()) throw new IllegalStateException("ডেটা সংরক্ষণ হয়নি");
                            Toast.makeText(this, ui("রিসাইকেল বিনে পাঠানো হয়েছে"), Toast.LENGTH_SHORT).show();
                            manageRecords();
                        } catch (Exception e) {
                            records = oldRecords;
                            trash = oldTrash;
                            Toast.makeText(this, ui("রিসাইকেল বিনে সরানো যায়নি; আগের ডেটা অক্ষত আছে"), Toast.LENGTH_LONG).show();
                        }
                    }).show();
            }).show();
    }

    private void drawDeveloperCredit(android.graphics.Canvas canvas,
            android.graphics.Paint paint, float x, float y, int color) {
        paint.setColor(color);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(8);
        canvas.drawText("Developed with Love by Ayan • Anaiya • Zayan", x, y, paint);
    }

    private int drawReceiptHeader(android.graphics.Canvas canvas,
            android.graphics.Paint paint, int pageNo) {
        int accent = prefs.getInt("pdf_color", blue);
        paint.setColor(accent);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(21);
        try {
            android.graphics.Bitmap logo = android.graphics.BitmapFactory.decodeResource(
                getResources(), R.drawable.amar_hisab_logo);
            if (logo != null) {
                android.graphics.Rect dst = new android.graphics.Rect(40, 24, 94, 78);
                paint.setFilterBitmap(true);
                canvas.drawBitmap(logo, null, dst, paint);
                logo.recycle();
            }
        } catch (Exception ignored) { }
        canvas.drawText(ui("আমার হিসাব"), 106, 48, paint);
        paint.setTextSize(14);
        canvas.drawText(ui("রসিদ / ইনভয়েস"), 106, 70, paint);
        paint.setTextSize(10);
        paint.setTypeface(Typeface.DEFAULT);
        canvas.drawText(ui("পৃষ্ঠা ") + pageNo, 500, 48, paint);
        drawDeveloperCredit(canvas, paint, 106, 91, accent);
        paint.setColor(accent);
        canvas.drawLine(40, 98, 555, 98, paint);
        return 128;
    }

    private int drawReceiptWrapped(android.graphics.Canvas canvas,
            android.graphics.Paint paint, String value, int y) {
        String textValue = value == null ? "" : value;
        int maxChars = 78;
        while (!textValue.isEmpty()) {
            int cut = Math.min(maxChars, textValue.length());
            if (cut < textValue.length()) {
                int space = textValue.lastIndexOf(' ', cut);
                if (space > 25) cut = space;
            }
            canvas.drawText(textValue.substring(0, cut), 40, y, paint);
            textValue = textValue.substring(cut).trim();
            y += 18;
            if (y > 755) break;
        }
        return y;
    }

    private void exportReceiptPdf(JSONObject record) {
        android.graphics.pdf.PdfDocument pdf = new android.graphics.pdf.PdfDocument();
        try {
            int accent = prefs.getInt("pdf_color", blue);
            android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.BLACK);
            paint.setTextSize(11);
            paint.setTypeface(Typeface.DEFAULT);

            int pageNo = 1;
            android.graphics.pdf.PdfDocument.PageInfo info =
                new android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, pageNo).create();
            android.graphics.pdf.PdfDocument.Page page = pdf.startPage(info);
            android.graphics.Canvas canvas = page.getCanvas();
            int y = drawReceiptHeader(canvas, paint, pageNo);

            boolean english = "en".equals(prefs.getString("language", "bn"));
            String partyLabel = english ? "Party / Customer: " : "পার্টি / গ্রাহক: ";
            String dateLabel = english ? "Date: " : "তারিখ: ";
            String typeLabel = english ? "Type: " : "ধরন: ";
            String noteLabel = english ? "Description: " : "বিবরণ: ";
            y = drawReceiptWrapped(canvas, paint, partyLabel + record.optString("party"), y);
            y = drawReceiptWrapped(canvas, paint, dateLabel + record.optString("date"), y);
            y = drawReceiptWrapped(canvas, paint, typeLabel + ui(record.optString("type")), y);
            String note = record.optString("note", "").trim();
            if (!note.isEmpty()) y = drawReceiptWrapped(canvas, paint, noteLabel + note, y);
            y += 12;

            android.graphics.Paint tableFill = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
            tableFill.setColor(accent);
            canvas.drawRoundRect(40, y - 19, 555, y + 9, 7, 7, tableFill);
            paint.setColor(Color.WHITE);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText(english ? "Item" : "পণ্য", 50, y, paint);
            canvas.drawText(english ? "Qty" : "পরিমাণ", 290, y, paint);
            canvas.drawText(english ? "Rate" : "দর", 385, y, paint);
            canvas.drawText(english ? "Amount" : "মোট", 475, y, paint);
            y += 31;

            paint.setColor(Color.BLACK);
            paint.setTypeface(Typeface.DEFAULT);
            JSONArray items = record.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    if (y > 735) {
                        paint.setTextSize(9);
                        paint.setColor(Color.GRAY);
                        canvas.drawText((english ? "Generated: " : "তৈরি: ") +
                            new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
                                .format(new Date()), 40, 820, paint);
                        pdf.finishPage(page);
                        pageNo++;
                        info = new android.graphics.pdf.PdfDocument.PageInfo.Builder(
                            595, 842, pageNo).create();
                        page = pdf.startPage(info);
                        canvas = page.getCanvas();
                        y = drawReceiptHeader(canvas, paint, pageNo) + 8;
                        paint.setColor(accent);
                        paint.setTypeface(Typeface.DEFAULT_BOLD);
                        canvas.drawText(english ? "Continued items" : "পণ্যের তালিকা (চলমান)",
                            40, y, paint);
                        y += 25;
                        paint.setColor(Color.BLACK);
                        paint.setTypeface(Typeface.DEFAULT);
                        paint.setTextSize(11);
                    }
                    String label = item.optString("product",
                        english ? "Item" : "পণ্য");
                    if (label.length() > 35) label = label.substring(0, 35);
                    canvas.drawText(label, 40, y, paint);
                    canvas.drawText(df.format(item.optDouble("quantity", 0)), 290, y, paint);
                    canvas.drawText(df.format(item.optDouble("rate", 0)), 385, y, paint);
                    canvas.drawText(df.format(item.optDouble("total", 0)), 475, y, paint);
                    paint.setColor(Color.rgb(226, 231, 239));
                    canvas.drawLine(40, y + 8, 555, y + 8, paint);
                    paint.setColor(Color.BLACK);
                    y += 25;
                }
            }

            if (y > 720) {
                paint.setTextSize(9);
                paint.setColor(Color.GRAY);
                canvas.drawText("Generated: " +
                    new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
                        .format(new Date()), 40, 820, paint);
                pdf.finishPage(page);
                pageNo++;
                info = new android.graphics.pdf.PdfDocument.PageInfo.Builder(
                    595, 842, pageNo).create();
                page = pdf.startPage(info);
                canvas = page.getCanvas();
                y = drawReceiptHeader(canvas, paint, pageNo) + 25;
            } else {
                y += 18;
            }

            // Show the invoice calculation transparently on the receipt.
            double savedAmount = record.optDouble("amount", 0);
            double subtotal = record.has("subtotal")
                ? record.optDouble("subtotal", savedAmount) : savedAmount;
            double discount = record.optDouble("discount", 0);
            double taxRate = record.optDouble("tax_rate", 0);
            double taxAmount = record.has("tax_amount")
                ? record.optDouble("tax_amount", 0)
                : Math.max(0, subtotal - discount) * taxRate / 100.0;
            double extraCharge = record.optDouble("additional_charge", 0);
            double roundOff = record.optDouble("round_off", 0);
            boolean hasAdjustments = record.has("subtotal") &&
                (discount != 0 || taxRate != 0 || extraCharge != 0 || roundOff != 0);
            if (hasAdjustments) {
                if (y > 680) {
                    paint.setTextSize(9);
                    paint.setColor(Color.GRAY);
                    canvas.drawText("Generated: " +
                        new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
                            .format(new Date()), 40, 820, paint);
                    pdf.finishPage(page);
                    pageNo++;
                    info = new android.graphics.pdf.PdfDocument.PageInfo.Builder(
                        595, 842, pageNo).create();
                    page = pdf.startPage(info);
                    canvas = page.getCanvas();
                    y = drawReceiptHeader(canvas, paint, pageNo) + 35;
                }
                paint.setTypeface(Typeface.DEFAULT);
                paint.setTextSize(11);
                paint.setColor(Color.DKGRAY);
                String subtotalLabel = english ? "Subtotal" : "পণ্যের মোট";
                String discountLabel = english ? "Discount" : "ছাড়";
                String taxText = english ? "VAT / Tax (" : "ভ্যাট / কর (";
                String extraLabel = english ? "Additional charge" : "অতিরিক্ত চার্জ";
                String roundLabel = english ? "Round-off" : "রাউন্ড-অফ";
                canvas.drawText(subtotalLabel + ": " + df.format(subtotal), 330, y, paint); y += 18;
                if (discount != 0) {
                    canvas.drawText(discountLabel + ": -" + df.format(discount), 330, y, paint); y += 18;
                }
                if (taxRate != 0 || taxAmount != 0) {
                    canvas.drawText(taxText + df.format(taxRate) + "%): " +
                        df.format(taxAmount), 330, y, paint); y += 18;
                }
                if (extraCharge != 0) {
                    canvas.drawText(extraLabel + ": " + df.format(extraCharge), 330, y, paint); y += 18;
                }
                if (roundOff != 0) {
                    canvas.drawText(roundLabel + ": " +
                        (roundOff > 0 ? "+" : "") + df.format(roundOff), 330, y, paint); y += 18;
                }
                y += 8;
            } else {
                y += 18;
            }
            if (y > 770) {
                paint.setTextSize(9);
                paint.setColor(Color.GRAY);
                canvas.drawText("Generated: " +
                    new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
                        .format(new Date()), 40, 820, paint);
                pdf.finishPage(page);
                pageNo++;
                info = new android.graphics.pdf.PdfDocument.PageInfo.Builder(
                    595, 842, pageNo).create();
                page = pdf.startPage(info);
                canvas = page.getCanvas();
                y = drawReceiptHeader(canvas, paint, pageNo) + 70;
            }
            android.graphics.Paint totalPanel = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
            totalPanel.setColor(Color.rgb(239, 246, 252));
            canvas.drawRoundRect(315, y - 25, 555, y + 20, 9, 9, totalPanel);
            paint.setColor(accent);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(17);
            String totalLabel = english ? "TOTAL: BDT " : "সর্বমোট: ৳ ";
            canvas.drawText(totalLabel + df.format(savedAmount),
                330, y + 3, paint);
            y += 65;

            String signature = prefs.getString("pdf_signature", "").trim();
            boolean hasSignatureImage =
                !prefs.getString("pdf_signature_image", "").isEmpty();
            if (!signature.isEmpty() || hasSignatureImage) {
                if (y > 755) {
                    paint.setTextSize(9);
                    paint.setColor(Color.GRAY);
                    canvas.drawText("Generated: " +
                        new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
                            .format(new Date()), 40, 820, paint);
                    pdf.finishPage(page);
                    pageNo++;
                    info = new android.graphics.pdf.PdfDocument.PageInfo.Builder(
                        595, 842, pageNo).create();
                    page = pdf.startPage(info);
                    canvas = page.getCanvas();
                    y = drawReceiptHeader(canvas, paint, pageNo) + 70;
                }
                drawSavedSignature(canvas, paint, 390, y - 48, 150, 42);
                paint.setTextSize(12);
                paint.setColor(Color.BLACK);
                if (!signature.isEmpty()) {
                    canvas.drawText(signature, 390, y + 2, paint);
                    y += 20;
                }
                canvas.drawLine(380, y + 8, 555, y + 8, paint);
                canvas.drawText(english ? "Authorized signature" : "অনুমোদিত স্বাক্ষর",
                    390, y + 27, paint);
            }

            paint.setTextSize(9);
            paint.setColor(Color.GRAY);
            canvas.drawText("Generated: " +
                new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
                    .format(new Date()), 40, 820, paint);
            pdf.finishPage(page);
            if (prefs.getBoolean("pdf_include_attachments", false))
                appendReceiptAttachments(pdf, record, pageNo + 1);

            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            pdf.writeTo(buffer);
            pendingPdfBytes = buffer.toByteArray();
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/pdf");
            intent.putExtra(Intent.EXTRA_TITLE,
                "Receipt-" + record.optString("party", "Customer") + ".pdf");
            startActivityForResult(intent, SAVE_RECEIPT_PDF);
        } catch (Exception e) {
            pendingPdfBytes = null;
            Toast.makeText(this, ui("রসিদ PDF তৈরি করা যায়নি: ") + e.getMessage(),
                Toast.LENGTH_LONG).show();
        } finally {
            pdf.close();
        }
    }

    private java.io.File attachmentRootDirectory() throws java.io.IOException {
        java.io.File root = new java.io.File(getFilesDir(), "transaction_attachments");
        if (!root.exists() && !root.mkdirs())
            throw new java.io.IOException("সংযুক্ত নথির ফোল্ডার তৈরি করা যায়নি");
        if (!root.isDirectory())
            throw new java.io.IOException("সংযুক্ত নথির ফোল্ডার ব্যবহার করা যাচ্ছে না");
        return root;
    }

    private java.io.File attachmentDirectory() throws java.io.IOException {
        String generation = prefs == null ? "" :
            prefs.getString("attachment_generation", "");
        if (!AttachmentGenerationStore.isValidGeneration(generation)) {
            if (prefs != null) prefs.edit().remove("attachment_generation").apply();
            generation = "";
        }
        return AttachmentGenerationStore.directory(attachmentRootDirectory(), generation);
    }

    private void cleanupOrphanAttachmentGenerations() {
        try {
            AttachmentGenerationStore.cleanupOrphanGenerations(
                new java.io.File(getFilesDir(), "transaction_attachments"),
                prefs.getString("attachment_generation", ""));
        } catch (Exception ignored) { }
    }

    private java.io.File attachmentFile(String id) throws java.io.IOException {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,80}"))
            throw new java.io.IOException("সংযুক্ত নথির পরিচয় সঠিক নয়");
        return new java.io.File(attachmentDirectory(), id);
    }

    private void pickTransactionAttachments(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }

    private String attachmentDisplayName(android.net.Uri uri) {
        String name = null;
        try (android.database.Cursor cursor = getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Exception ignored) { }
        if (name == null || name.trim().isEmpty()) name = "Attachment";
        return name.replaceAll("[\\r\\n]", " ").trim();
    }

    private long attachmentBytes(JSONArray attachments) {
        long total = 0;
        if (attachments == null) return 0;
        for (int i = 0; i < attachments.length(); i++) {
            JSONObject item = attachments.optJSONObject(i);
            if (item != null) total += Math.max(0, item.optLong("size", 0));
        }
        return total;
    }

    private int appendAttachmentUris(Intent data, JSONArray target) {
        int added = 0;
        android.content.ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                android.net.Uri uri = clip.getItemAt(i).getUri();
                if (uri != null && copyAttachment(uri, target)) added++;
            }
        } else if (data.getData() != null && copyAttachment(data.getData(), target)) {
            added++;
        }
        return added;
    }

    private boolean copyAttachment(android.net.Uri uri, JSONArray target) {
        if (target.length() >= MAX_RECORD_ATTACHMENTS) {
            Toast.makeText(this, ui("একটি হিসাবের সঙ্গে সর্বোচ্চ ২০টি নথি যুক্ত করা যাবে"),
                Toast.LENGTH_LONG).show();
            return false;
        }
        long currentBytes = attachmentBytes(target);
        if (currentBytes >= MAX_RECORD_ATTACHMENT_BYTES) {
            Toast.makeText(this, ui("এই হিসাবের সংযুক্ত নথির সীমা ১২ MB পূর্ণ হয়েছে"),
                Toast.LENGTH_LONG).show();
            return false;
        }
        String mime = getContentResolver().getType(uri);
        String name = attachmentDisplayName(uri);
        if (mime == null) mime = name.toLowerCase(Locale.ROOT).endsWith(".pdf")
            ? "application/pdf" : "";
        if (!mime.startsWith("image/") && !"application/pdf".equalsIgnoreCase(mime)) {
            Toast.makeText(this, ui("শুধু ছবি বা PDF ফাইল সংযুক্ত করা যাবে"),
                Toast.LENGTH_LONG).show();
            return false;
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        java.io.File output = null;
        try {
            output = attachmentFile(id);
            long copied = 0;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(output)) {
                if (in == null) throw new java.io.IOException("ফাইল খোলা যায়নি");
                byte[] chunk = new byte[8192];
                int count;
                while ((count = in.read(chunk)) != -1) {
                    copied += count;
                    if (copied > MAX_ATTACHMENT_BYTES ||
                        currentBytes + copied > MAX_RECORD_ATTACHMENT_BYTES)
                        throw new java.io.IOException("ফাইল ৫ MB বা হিসাবের মোট ১২ MB সীমা ছাড়িয়েছে");
                    out.write(chunk, 0, count);
                }
                out.flush();
            }
            JSONObject metadata = new JSONObject();
            metadata.put("id", id);
            metadata.put("name", name);
            metadata.put("mimeType", mime);
            metadata.put("size", copied);
            metadata.put("addedAt", System.currentTimeMillis());
            target.put(metadata);
            return true;
        } catch (Exception e) {
            if (output != null) output.delete();
            Toast.makeText(this, ui("ফাইল সংযুক্ত হয়নি: ") + e.getMessage(),
                Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void manageRecordAttachments(int index) {
        if (index < 0 || index >= records.length()) return;
        JSONObject record = records.optJSONObject(index);
        if (record == null) return;
        activeAttachmentRecordIndex = index;
        JSONArray attachments = record.optJSONArray("attachments");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(12, 8, 12, 8);
        if (attachments == null || attachments.length() == 0) {
            box.addView(text("এখনো কোনো নথি সংযুক্ত নেই", 15));
        } else {
            for (int i = 0; i < attachments.length(); i++) {
                JSONObject item = attachments.optJSONObject(i);
                if (item == null) continue;
                final JSONObject selected = item;
                Button view = button((i + 1) + ". " + item.optString("name", "Attachment") +
                    " (" + df.format(item.optDouble("size", 0) / 1024.0) + " KB)");
                box.addView(view);
                view.setOnClickListener(v -> previewTransactionAttachment(selected));
            }
        }
        Button add = button("+ আরও ছবি / PDF সংযুক্ত করুন");
        box.addView(add);
        final AlertDialog[] attachmentDialog = new AlertDialog[1];
        add.setOnClickListener(v -> {
            if (attachmentDialog[0] != null) attachmentDialog[0].dismiss();
            activeAttachmentRecordIndex = index;
            pickTransactionAttachments(PICK_RECORD_ATTACHMENTS);
        });
        attachmentDialog[0] = new AlertDialog.Builder(this)
            .setTitle(ui("লেনদেনের সংযুক্ত নথি"))
            .setView(box)
            .setPositiveButton(ui("বন্ধ করুন"), null)
            .create();
        attachmentDialog[0].show();
    }

    private void showRecordAttachments(JSONObject record) {
        JSONArray attachments = record == null ? null : record.optJSONArray("attachments");
        if (attachments == null || attachments.length() == 0) {
            Toast.makeText(this, ui("এই হিসাবের সঙ্গে কোনো নথি নেই"), Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[attachments.length()];
        for (int i = 0; i < attachments.length(); i++) {
            JSONObject item = attachments.optJSONObject(i);
            names[i] = item == null ? "Attachment" : item.optString("name", "Attachment");
        }
        new AlertDialog.Builder(this).setTitle(ui("সংযুক্ত নথি"))
            .setItems(names, (dialog, which) ->
                previewTransactionAttachment(attachments.optJSONObject(which)))
            .setNegativeButton(ui("বন্ধ করুন"), null).show();
    }

    private void previewTransactionAttachment(JSONObject attachment) {
        if (attachment == null) return;
        try {
            java.io.File file = attachmentFile(attachment.optString("id"));
            if (!file.isFile()) throw new java.io.IOException("ফাইলটি পাওয়া যায়নি");
            android.graphics.Bitmap bitmap;
            if (attachment.optString("mimeType").startsWith("image/")) {
                bitmap = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
            } else {
                try (android.os.ParcelFileDescriptor descriptor =
                         android.os.ParcelFileDescriptor.open(file,
                             android.os.ParcelFileDescriptor.MODE_READ_ONLY);
                     android.graphics.pdf.PdfRenderer renderer =
                         new android.graphics.pdf.PdfRenderer(descriptor)) {
                    if (renderer.getPageCount() == 0) throw new java.io.IOException("PDF-এ পৃষ্ঠা নেই");
                    android.graphics.pdf.PdfRenderer.Page page = renderer.openPage(0);
                    try {
                        bitmap = android.graphics.Bitmap.createBitmap(900, 1200,
                            android.graphics.Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(Color.WHITE);
                        page.render(bitmap, null, null,
                            android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    } finally { page.close(); }
                }
            }
            if (bitmap == null) throw new java.io.IOException("ছবি পড়া যায়নি");
            ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            ScrollView scroll = new ScrollView(this);
            scroll.addView(image);
            new AlertDialog.Builder(this)
                .setTitle(attachment.optString("name", "Attachment"))
                .setView(scroll)
                .setPositiveButton(ui("বন্ধ করুন"), (dialog, which) -> bitmap.recycle())
                .setOnCancelListener(dialog -> bitmap.recycle())
                .show();
        } catch (Exception e) {
            Toast.makeText(this, ui("নথি খোলা যায়নি: ") + e.getMessage(),
                Toast.LENGTH_LONG).show();
        }
    }

    private void deleteRecordAttachments(JSONObject record) {
        if (record == null) return;
        JSONArray attachments = record.optJSONArray("attachments");
        if (attachments == null) return;
        for (int i = 0; i < attachments.length(); i++) {
            JSONObject item = attachments.optJSONObject(i);
            if (item == null) continue;
            try { attachmentFile(item.optString("id")).delete(); }
            catch (Exception ignored) { }
        }
    }

    private JSONArray buildBackupAttachments() throws Exception {
        JSONArray result = new JSONArray();
        HashSet<String> seen = new HashSet<>();
        long totalBytes = 0;
        for (JSONArray source : new JSONArray[]{records, trash}) {
            for (int i = 0; i < source.length(); i++) {
                JSONObject record = source.optJSONObject(i);
                if (record == null) continue;
                JSONArray attachments = record.optJSONArray("attachments");
                if (attachments == null) continue;
                for (int j = 0; j < attachments.length(); j++) {
                    JSONObject item = attachments.optJSONObject(j);
                    if (item == null) continue;
                    String id = item.optString("id", "");
                    if (id.isEmpty() || seen.contains(id)) continue;
                    java.io.File file = attachmentFile(id);
                    if (!file.isFile()) throw new java.io.IOException(
                        "সংযুক্ত নথি পাওয়া যায়নি: " + item.optString("name"));
                    totalBytes += file.length();
                    if (totalBytes > MAX_BACKUP_ATTACHMENT_BYTES)
                        throw new java.io.IOException("ব্যাকআপের সংযুক্ত নথি ১২ MB-এর বেশি");
                    byte[] bytes = new byte[(int) file.length()];
                    try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                        int offset = 0;
                        while (offset < bytes.length) {
                            int count = in.read(bytes, offset, bytes.length - offset);
                            if (count < 0) break;
                            offset += count;
                        }
                        if (offset != bytes.length) throw new java.io.IOException("নথি সম্পূর্ণ পড়া যায়নি");
                    }
                    JSONObject saved = new JSONObject();
                    saved.put("id", id);
                    saved.put("data", android.util.Base64.encodeToString(bytes,
                        android.util.Base64.NO_WRAP));
                    result.put(saved);
                    seen.add(id);
                }
            }
        }
        return result;
    }

    private static boolean isValidBackupAttachments(JSONArray attachments) {
        if (attachments == null || attachments.length() > 500) return false;
        HashSet<String> ids = new HashSet<>();
        long total = 0;
        try {
            for (int i = 0; i < attachments.length(); i++) {
                JSONObject item = attachments.optJSONObject(i);
                if (item == null) return false;
                String id = item.optString("id", "");
                String data = item.optString("data", "");
                if (!id.matches("[A-Za-z0-9_-]{1,80}") || ids.contains(id) || data.isEmpty())
                    return false;
                byte[] bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT);
                if (bytes.length > MAX_ATTACHMENT_BYTES) return false;
                total += bytes.length;
                if (total > MAX_BACKUP_ATTACHMENT_BYTES) return false;
                ids.add(id);
            }
            return true;
        } catch (Exception e) { return false; }
    }

    private static boolean attachmentReferencesValid(JSONArray records,
            JSONArray deletedRecords, JSONArray backupAttachments) {
        HashSet<String> available = new HashSet<>();
        if (backupAttachments != null) {
            for (int i = 0; i < backupAttachments.length(); i++) {
                JSONObject item = backupAttachments.optJSONObject(i);
                if (item != null) available.add(item.optString("id"));
            }
        }
        for (JSONArray source : new JSONArray[]{records, deletedRecords}) {
            if (source == null) continue;
            for (int i = 0; i < source.length(); i++) {
                JSONObject record = source.optJSONObject(i);
                if (record == null) continue;
                JSONArray attachments = record.optJSONArray("attachments");
                if (attachments == null) continue;
                for (int j = 0; j < attachments.length(); j++) {
                    JSONObject item = attachments.optJSONObject(j);
                    if (item == null || !available.contains(item.optString("id"))) return false;
                }
            }
        }
        return true;
    }

    private String stageBackupAttachments(JSONArray attachments) throws Exception {
        java.util.LinkedHashMap<String, byte[]> decoded = new java.util.LinkedHashMap<>();
        if (attachments != null) {
            for (int i = 0; i < attachments.length(); i++) {
                JSONObject item = attachments.optJSONObject(i);
                if (item == null) throw new java.io.IOException("ব্যাকআপের নথি সঠিক নয়");
                String id = item.optString("id", "");
                if (!id.matches("[A-Za-z0-9_-]{1,80}") || decoded.containsKey(id))
                    throw new java.io.IOException("ব্যাকআপে সংযুক্ত নথির পরিচয় সঠিক নয়");
                byte[] bytes = android.util.Base64.decode(item.optString("data"),
                    android.util.Base64.DEFAULT);
                if (bytes.length == 0)
                    throw new java.io.IOException("ব্যাকআপে খালি সংযুক্ত ফাইল রয়েছে");
                decoded.put(id, bytes);
            }
        }
        return AttachmentGenerationStore.stage(attachmentRootDirectory(), decoded);
    }

    private void restorePreviousBackupPreferences(String generation, String language,
            boolean smsEnabled, boolean appLockEnabled, String signature,
            String signatureImage, int pdfColor, boolean includeAttachments) {
        prefs.edit()
            .putString("records", records.toString())
            .putString("party_names", partyNames.toString())
            .putString("trash", trash.toString())
            .putString("rental_units", rentalUnits.toString())
            .putString("stock_items", stockItems.toString())
            .putString("attachment_generation", generation)
            .putString("language", language)
            .putBoolean("sms_enabled", smsEnabled)
            .putBoolean("app_lock_enabled", appLockEnabled)
            .putString("pdf_signature", signature)
            .putString("pdf_signature_image", signatureImage)
            .putInt("pdf_color", pdfColor)
            .putBoolean("pdf_include_attachments", includeAttachments)
            .apply();
    }

    private void cleanupOrphanAttachments(JSONArray activeRecords, JSONArray deletedRecords) {
        HashSet<String> referenced = new HashSet<>();
        for (JSONArray source : new JSONArray[]{activeRecords, deletedRecords}) {
            for (int i = 0; i < source.length(); i++) {
                JSONObject record = source.optJSONObject(i);
                if (record == null) continue;
                JSONArray attachments = record.optJSONArray("attachments");
                if (attachments == null) continue;
                for (int j = 0; j < attachments.length(); j++) {
                    JSONObject item = attachments.optJSONObject(j);
                    if (item != null) referenced.add(item.optString("id"));
                }
            }
        }
        try {
            java.io.File[] files = attachmentDirectory().listFiles();
            if (files == null) return;
            for (java.io.File file : files) {
                if (!referenced.contains(file.getName())) file.delete();
            }
        } catch (Exception ignored) { }
    }

    private void appendReceiptAttachments(android.graphics.pdf.PdfDocument pdf,
            JSONObject record, int firstPageNumber) throws Exception {
        JSONArray attachments = record.optJSONArray("attachments");
        if (attachments == null || attachments.length() == 0) return;
        int pageNumber = firstPageNumber;
        int pagesAdded = 0;
        android.graphics.Paint paint = new android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < attachments.length(); i++) {
            JSONObject item = attachments.optJSONObject(i);
            if (item == null) continue;
            if (pagesAdded >= 20)
                throw new java.io.IOException("সংযুক্ত নথির পৃষ্ঠা সীমা ২০টি");
            java.io.File file = attachmentFile(item.optString("id"));
            if (!file.isFile()) throw new java.io.IOException("সংযুক্ত নথি পাওয়া যায়নি");
            String mime = item.optString("mimeType");
            if (mime.startsWith("image/")) {
                android.graphics.Bitmap bitmap =
                    android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
                if (bitmap == null) throw new java.io.IOException("সংযুক্ত ছবি পড়া যায়নি");
                android.graphics.pdf.PdfDocument.Page page = pdf.startPage(
                    new android.graphics.pdf.PdfDocument.PageInfo.Builder(
                        595, 842, pageNumber++).create());
                android.graphics.Canvas canvas = page.getCanvas();
                canvas.drawColor(Color.WHITE);
                paint.setColor(Color.DKGRAY);
                paint.setTextSize(10);
                canvas.drawText(item.optString("name", "Attachment"), 40, 32, paint);
                android.graphics.RectF destination = new android.graphics.RectF(35, 48, 560, 810);
                float scale = Math.min(destination.width() / bitmap.getWidth(),
                    destination.height() / bitmap.getHeight());
                float width = bitmap.getWidth() * scale;
                float height = bitmap.getHeight() * scale;
                android.graphics.RectF fitted = new android.graphics.RectF(
                    destination.centerX() - width / 2, destination.centerY() - height / 2,
                    destination.centerX() + width / 2, destination.centerY() + height / 2);
                paint.setFilterBitmap(true);
                canvas.drawBitmap(bitmap, null, fitted, paint);
                pdf.finishPage(page);
                bitmap.recycle();
                pagesAdded++;
            } else if ("application/pdf".equalsIgnoreCase(mime)) {
                try (android.os.ParcelFileDescriptor descriptor =
                         android.os.ParcelFileDescriptor.open(file,
                             android.os.ParcelFileDescriptor.MODE_READ_ONLY);
                     android.graphics.pdf.PdfRenderer renderer =
                         new android.graphics.pdf.PdfRenderer(descriptor)) {
                    for (int p = 0; p < renderer.getPageCount(); p++) {
                        if (pagesAdded >= 20)
                            throw new java.io.IOException("সংযুক্ত PDF-এর পৃষ্ঠা সীমা ২০টি");
                        android.graphics.pdf.PdfRenderer.Page source = renderer.openPage(p);
                        try {
                            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                                1190, 1684, android.graphics.Bitmap.Config.ARGB_8888);
                            bitmap.eraseColor(Color.WHITE);
                            source.render(bitmap, null, null,
                                android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                            android.graphics.pdf.PdfDocument.Page page = pdf.startPage(
                                new android.graphics.pdf.PdfDocument.PageInfo.Builder(
                                    595, 842, pageNumber++).create());
                            page.getCanvas().drawBitmap(bitmap, null,
                                new android.graphics.Rect(0, 0, 595, 842), paint);
                            pdf.finishPage(page);
                            bitmap.recycle();
                            pagesAdded++;
                        } finally { source.close(); }
                    }
                }
            }
        }
    }

    private void editRecord(int index) {
        JSONObject record = records.optJSONObject(index);
        if (record == null) return;
        if (isModuleLinkedRecord(record)) {
            Toast.makeText(this, ui("ভাড়া, স্টক ও জামানত-সংযুক্ত এন্ট্রি সংশ্লিষ্ট মডিউল থেকেই পরিবর্তন করতে হবে।"), Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        EditText name = field("পার্টির নাম");
        name.setText(record.optString("party"));
        EditText amount = field("টাকার পরিমাণ");
        amount.setInputType(8194);
        amount.setText(df.format(record.optDouble("amount", 0)));
        EditText note = field("বিবরণ");
        note.setText(record.optString("note"));
        Spinner type = new Spinner(this);
        String[] types = {"পাওনা", "জমা", "খরচ", "পরিশোধ", "আয়"};
        String[] displayTypes = new String[types.length];
        for (int i = 0; i < types.length; i++) displayTypes[i] = ui(types[i]);
        type.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, displayTypes));
        int selected = Arrays.asList(types).indexOf(record.optString("type"));
        type.setSelection(Math.max(0, selected));
        box.addView(name); box.addView(type); box.addView(amount); box.addView(note);
        new AlertDialog.Builder(this).setTitle(ui("হিসাব সম্পাদনা")).setView(box)
            .setNegativeButton(ui("বাতিল"), null)
            .setPositiveButton(ui("পরিবর্তন সংরক্ষণ"), (d,w) -> {
                String partyName = name.getText().toString().trim();
                double value = number(amount.getText().toString());
                if (partyName.isEmpty() || !AccountingMath.isValidTransactionAmount(value)) {
                    Toast.makeText(this, ui("পার্টির নাম ও সঠিক পরিমাণ দিন"), Toast.LENGTH_SHORT).show();
                    return;
                }
                JSONArray oldRecords = records;
                try {
                    JSONArray updatedRecords = new JSONArray(records.toString());
                    JSONObject updatedRecord = updatedRecords.optJSONObject(index);
                    if (updatedRecord == null) throw new IllegalStateException("হিসাব পাওয়া যায়নি");
                    updatedRecord.put("party", partyName);
                    updatedRecord.put("type", types[type.getSelectedItemPosition()]);
                    updatedRecord.put("amount", value);
                    updatedRecord.put("note", note.getText().toString().trim());
                    updatedRecord.put("edited_at", System.currentTimeMillis());
                    if (!prefs.edit().putString("records", updatedRecords.toString()).commit())
                        throw new IllegalStateException("ডেটা সংরক্ষণ হয়নি");
                    records = updatedRecords;
                    Toast.makeText(this, ui("হিসাব সম্পাদনা হয়েছে"), Toast.LENGTH_SHORT).show();
                    manageRecords();
                } catch (Exception e) {
                    records = oldRecords;
                    Toast.makeText(this, ui("পরিবর্তন সংরক্ষণ হয়নি; আগের হিসাব অক্ষত আছে"), Toast.LENGTH_LONG).show();
                }
            }).show();
    }

    private void recycleBin() {
        setup("রিসাইকেল বিন");
        if (trash.length() == 0) content.addView(text("রিসাইকেল বিন খালি", 18));
        for (int i = trash.length() - 1; i >= 0; i--) {
            final int index = i;
            JSONObject r = trash.optJSONObject(i);
            if (r == null) continue;
            Button item = button(r.optString("party") + " | " + ui(r.optString("type")) +
                " | " + money(r.optDouble("amount", 0)));
            content.addView(item);
            item.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(ui("মুছে ফেলা হিসাব"))
                .setItems(new String[]{ui("সংযুক্ত নথি দেখুন"), ui("ফেরত আনুন"),
                    ui("স্থায়ীভাবে মুছুন")}, (d, choice) -> {
                    if (choice == 0) {
                        showRecordAttachments(r);
                    } else if (choice == 1) {
                        JSONArray oldRecords = records;
                        JSONArray oldTrash = trash;
                        try {
                            records = new JSONArray(records.toString());
                            trash = new JSONArray(trash.toString());
                            records.put(trash.optJSONObject(index));
                            trash.remove(index);
                            if (!saveRecordsAndTrash()) throw new IllegalStateException("ডেটা সংরক্ষণ হয়নি");
                            recycleBin();
                        } catch (Exception e) {
                            records = oldRecords;
                            trash = oldTrash;
                            Toast.makeText(this, ui("হিসাব ফেরত আনা যায়নি; আগের ডেটা অক্ষত আছে"), Toast.LENGTH_LONG).show();
                        }
                    } else if (choice == 2) {
                        final String requiredConfirmation = "DELETE";
                        EditText confirmation = new EditText(this);
                        confirmation.setSingleLine(true);
                        confirmation.setHint("Type DELETE");
                        AlertDialog permanentDialog = new AlertDialog.Builder(this)
                            .setTitle(ui("স্থায়ীভাবে মুছবেন?"))
                            .setMessage(ui("এই কাজ আর ফেরত নেওয়া যাবে না। নিশ্চিত করতে নিচে হুবহু “DELETE” লিখুন।"))
                            .setView(confirmation)
                            .setNegativeButton(ui("বাতিল"), null)
                            .setPositiveButton(ui("স্থায়ীভাবে মুছুন"), null)
                            .create();
                        permanentDialog.setOnShowListener(ignored -> {
                            Button deleteButton = permanentDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                            deleteButton.setEnabled(false);
                            confirmation.addTextChangedListener(new TextWatcher() {
                                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                                    deleteButton.setEnabled(requiredConfirmation.equals(s.toString().trim()));
                                }
                                @Override public void afterTextChanged(Editable s) {}
                            });
                            deleteButton.setOnClickListener(view -> {
                                JSONArray oldTrash = trash;
                                try {
                                    if (index < 0 || index >= trash.length() || trash.optJSONObject(index) == null)
                                        throw new IllegalStateException("হিসাব পাওয়া যায়নি");
                                    trash = new JSONArray(trash.toString());
                                    JSONObject permanentlyDeleted = trash.optJSONObject(index);
                                    trash.remove(index);
                                    if (!saveTrash()) throw new IllegalStateException("ডেটা সংরক্ষণ হয়নি");
                                    deleteRecordAttachments(permanentlyDeleted);
                                    permanentDialog.dismiss();
                                    recycleBin();
                                } catch (Exception e) {
                                    trash = oldTrash;
                                    Toast.makeText(this, ui("স্থায়ীভাবে মুছতে পারিনি; আগের ডেটা অক্ষত আছে"), Toast.LENGTH_LONG).show();
                                }
                            });
                        });
                        permanentDialog.show();
                    }
                }).show());
        }
        Button back = button("ফিরে যান");
        content.addView(back);
        back.setOnClickListener(v -> home());
    }

    private void shareBackup() {
        try {
            JSONObject backup = new JSONObject();
            backup.put("app", "Amar Hisab");
            backup.put("version", 5);
            backup.put("createdAt", System.currentTimeMillis());
            JSONObject settings = new JSONObject();
            settings.put("language", prefs.getString("language", "bn"));
            settings.put("sms_enabled", prefs.getBoolean("sms_enabled", false));
            settings.put("app_lock_enabled", prefs.getBoolean("app_lock_enabled", false));
            settings.put("pdf_signature", prefs.getString("pdf_signature", ""));
            settings.put("pdf_signature_image", prefs.getString("pdf_signature_image", ""));
            settings.put("pdf_color", prefs.getInt("pdf_color", Color.rgb(25, 90, 180)));
            settings.put("pdf_include_attachments",
                prefs.getBoolean("pdf_include_attachments", false));
            backup.put("settings", settings);
            backup.put("records", records);
            backup.put("partyNames", partyNames);
            backup.put("trash", trash);
            backup.put("rentalUnits", rentalUnits);
            backup.put("stockItems", stockItems);
            backup.put("attachments", buildBackupAttachments());
            pendingBackupBytes = backup.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (pendingBackupBytes.length > 20 * 1024 * 1024) {
                pendingBackupBytes = null;
                Toast.makeText(this, ui("ব্যাকআপ ২০ MB-এর বেশি; ডেটা ছোট করে আবার চেষ্টা করুন"),
                    Toast.LENGTH_LONG).show();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "Amar-Hisab-Backup-" +
                new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".json");
            startActivityForResult(intent, SAVE_BACKUP);
        } catch (Exception e) {
            pendingBackupBytes = null;
            Toast.makeText(this, ui("ব্যাকআপ তৈরি করা যায়নি: ") + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // Share the saved backup with compatible apps such as WhatsApp or Drive.
    private void shareBackupUri(android.net.Uri uri) {
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/json");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, ui("Hisab Data Backup"));
            share.putExtra(Intent.EXTRA_TEXT, ui("হিসাব অ্যাপের ডেটা ব্যাকআপ"));
            share.setClipData(ClipData.newUri(getContentResolver(), ui("Hisab Backup"), uri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, ui("ব্যাকআপ WhatsApp / Google Drive-এ শেয়ার করুন")));
        } catch (Exception e) {
            Toast.makeText(this, ui("শেয়ার অপশন খোলা যায়নি: ") + e.getMessage(),
                Toast.LENGTH_LONG).show();
        }
    }

    private void chooseBackupToRestore() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, IMPORT_BACKUP);
    }

    private static boolean isValidBackupArray(JSONArray array) {
        if (array == null || array.length() > 100000) return false;
        for (int i = 0; i < array.length(); i++) {
            if (array.optJSONObject(i) == null) return false;
        }
        return true;
    }

    private static boolean isValidStringArray(JSONArray array) {
        if (array == null || array.length() > 100000) return false;
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (!(value instanceof String) || ((String) value).trim().isEmpty()) return false;
        }
        return true;
    }

    private void importBackupFromUri(android.net.Uri uri) {
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("ফাইল খোলা যায়নি");
                byte[] chunk = new byte[8192];
                int count;
                while ((count = in.read(chunk)) != -1) {
                    if (buffer.size() + count > 20 * 1024 * 1024)
                        throw new java.io.IOException("ব্যাকআপ ফাইল ২০ MB-এর বেশি");
                    buffer.write(chunk, 0, count);
                }
            }
            JSONObject backup = new JSONObject(new String(buffer.toByteArray(),
                java.nio.charset.StandardCharsets.UTF_8));
            JSONArray candidateRecords = backup.optJSONArray("records");
            JSONArray candidatePartyNames = backup.optJSONArray("partyNames");
            if (candidatePartyNames == null) candidatePartyNames = new JSONArray();
            // Older backups may predate the recycle-bin, rental or stock modules.
            // Missing optional module arrays are restored as empty collections.
            JSONArray candidateTrash = backup.optJSONArray("trash");
            if (candidateTrash == null) candidateTrash = new JSONArray();
            JSONArray candidateUnits = backup.optJSONArray("rentalUnits");
            if (candidateUnits == null) candidateUnits = new JSONArray();
            JSONArray candidateStock = backup.optJSONArray("stockItems");
            if (candidateStock == null) candidateStock = new JSONArray();
            JSONArray candidateAttachments = backup.optJSONArray("attachments");
            if (candidateAttachments == null) candidateAttachments = new JSONArray();
            if (!"Amar Hisab".equals(backup.optString("app")) ||
                !isValidBackupArray(candidateRecords) ||
                !isValidStringArray(candidatePartyNames) ||
                !isValidBackupArray(candidateTrash) ||
                !isValidBackupArray(candidateUnits) ||
                !isValidBackupArray(candidateStock) ||
                !isValidBackupAttachments(candidateAttachments) ||
                !attachmentReferencesValid(candidateRecords, candidateTrash, candidateAttachments)) {
                throw new IllegalArgumentException("ব্যাকআপের গঠন সঠিক নয়; বর্তমান ডেটা নিরাপদ রাখা হয়েছে");
            }
            JSONArray restoredRecords = candidateRecords;
            JSONArray restoredPartyNames = candidatePartyNames;
            JSONArray restoredTrash = candidateTrash;
            JSONArray restoredUnits = candidateUnits;
            JSONArray restoredStock = candidateStock;
            final JSONArray finalStock = restoredStock;
            final JSONObject restoredSettings = backup.optJSONObject("settings");
            final JSONArray restoredAttachments = candidateAttachments;
            final String previousAttachmentGeneration = prefs.getString("attachment_generation", "");
            final String previousLanguage = prefs.getString("language", "bn");
            final boolean previousSmsEnabled = prefs.getBoolean("sms_enabled", false);
            final boolean previousAppLockEnabled = prefs.getBoolean("app_lock_enabled", false);
            final String previousSignature = prefs.getString("pdf_signature", "");
            final String previousSignatureImage = prefs.getString("pdf_signature_image", "");
            final int previousPdfColor = prefs.getInt("pdf_color", Color.rgb(25, 90, 180));
            final boolean previousPdfIncludeAttachments =
                prefs.getBoolean("pdf_include_attachments", false);
            new AlertDialog.Builder(this)
                .setTitle(ui("ব্যাকআপ ফিরিয়ে আনবেন?"))
                .setMessage(ui("বর্তমান হিসাব, রিসাইকেল বিন, বাড়িভাড়া ও স্টকের ডেটা ব্যাকআপের ডেটা দিয়ে প্রতিস্থাপিত হবে। আগে বর্তমান ডেটার ব্যাকআপ রাখুন।"))
                .setNegativeButton(ui("বাতিল"), null)
                .setPositiveButton(ui("হ্যাঁ, ফিরিয়ে আনুন"), (dialog, which) -> {
                    String stagedGeneration = null;
                    boolean committed = false;
                    try {
                        stagedGeneration = stageBackupAttachments(restoredAttachments);
                        SharedPreferences.Editor editor = prefs.edit()
                            .putString("records", restoredRecords.toString())
                            .putString("party_names", restoredPartyNames.toString())
                            .putString("trash", restoredTrash.toString())
                            .putString("rental_units", restoredUnits.toString())
                            .putString("stock_items", finalStock.toString())
                            .putString("attachment_generation", stagedGeneration);
                        if (restoredSettings != null) {
                            String restoredLanguage = restoredSettings.optString(
                                "language", previousLanguage);
                            if (!"bn".equals(restoredLanguage) && !"en".equals(restoredLanguage))
                                restoredLanguage = "bn";
                            editor.putString("language", restoredLanguage)
                                .putBoolean("sms_enabled", restoredSettings.optBoolean(
                                    "sms_enabled", previousSmsEnabled))
                                .putBoolean("app_lock_enabled", restoredSettings.optBoolean(
                                    "app_lock_enabled", previousAppLockEnabled))
                                .putString("pdf_signature", restoredSettings.optString(
                                    "pdf_signature", previousSignature))
                                .putString("pdf_signature_image", restoredSettings.optString(
                                    "pdf_signature_image", previousSignatureImage))
                                .putInt("pdf_color", restoredSettings.optInt(
                                    "pdf_color", previousPdfColor))
                                .putBoolean("pdf_include_attachments", restoredSettings.optBoolean(
                                    "pdf_include_attachments", false));
                        }
                        if (!editor.commit())
                            throw new java.io.IOException("ডেটা সংরক্ষণ করা যায়নি");
                        committed = true;
                    } catch (Exception restoreError) {
                        if (!committed) {
                            restorePreviousBackupPreferences(previousAttachmentGeneration,
                                previousLanguage, previousSmsEnabled, previousAppLockEnabled,
                                previousSignature, previousSignatureImage, previousPdfColor,
                                previousPdfIncludeAttachments);
                            if (stagedGeneration != null) {
                                try {
                                    AttachmentGenerationStore.deleteGeneration(
                                        new java.io.File(getFilesDir(), "transaction_attachments"),
                                        stagedGeneration);
                                } catch (Exception ignored) { }
                            }
                        }
                        Toast.makeText(this, ui("ডেটা ফিরিয়ে আনা যায়নি; বর্তমান ডেটা অক্ষত রাখা হয়েছে: ") +
                            restoreError.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    try {
                        AttachmentGenerationStore.deleteGeneration(
                            new java.io.File(getFilesDir(), "transaction_attachments"),
                            previousAttachmentGeneration);
                    } catch (Exception ignored) { }
                    records = restoredRecords;
                    partyNames = restoredPartyNames;
                    loadPartyNames();
                    trash = restoredTrash;
                    rentalUnits = restoredUnits;
                    stockItems = finalStock;
                    cleanupOrphanAttachmentGenerations();
                    cleanupOrphanAttachments(records, trash);
                    Toast.makeText(this, ui("ব্যাকআপের ডেটা ফিরিয়ে আনা হয়েছে"), Toast.LENGTH_LONG).show();
                    home();
                }).show();
        } catch (Exception e) {
            Toast.makeText(this, ui("ব্যাকআপ পড়া যায়নি: ") + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }


    private JSONObject findStockItem(String name) {
        for (int i = 0; i < stockItems.length(); i++) {
            JSONObject item = stockItems.optJSONObject(i);
            if (item != null && name.equalsIgnoreCase(item.optString("name").trim())) return item;
        }
        return null;
    }

    private void stockScreen() {
        setup("দোকানের স্টক ব্যবস্থাপনা");
        Button purchase = button("+ নতুন পণ্য / মাল ক্রয়");
        content.addView(purchase);
        purchase.setOnClickListener(v -> stockTransaction(false));
        Button sale = button("পণ্য বিক্রি করুন");
        content.addView(sale);
        sale.setOnClickListener(v -> stockTransaction(true));
        Button stockPdf = button("স্টক রিপোর্ট PDF / WhatsApp");
        content.addView(stockPdf);
        stockPdf.setOnClickListener(v -> createStockReportPdf());
        double stockValue = 0, totalSales = 0, totalCostOfSales = 0, grossProfit = 0, totalPurchases = 0;
        for (int i = 0; i < records.length(); i++) {
            JSONObject transaction = records.optJSONObject(i);
            if (transaction == null || transaction.optString("stock_item").isEmpty()) continue;
            boolean stockSale = transaction.optBoolean("stock_sale") ||
                (!transaction.optBoolean("stock_purchase") && "আয়".equals(transaction.optString("type")));
            boolean stockPurchase = transaction.optBoolean("stock_purchase") ||
                "খরচ".equals(transaction.optString("type"));
            if (stockSale) {
                totalSales += transaction.optDouble("amount", 0);
                totalCostOfSales += transaction.optDouble("cost_of_goods", 0);
                grossProfit += transaction.optDouble("gross_profit", 0);
            } else if (stockPurchase) {
                totalPurchases += transaction.optDouble("amount", 0);
            }
        }
        if (stockItems.length() == 0) content.addView(text("এখনো কোনো পণ্য যোগ করা হয়নি", 16));
        for (int i = 0; i < stockItems.length(); i++) {
            JSONObject item = stockItems.optJSONObject(i);
            if (item == null) continue;
            double qty = item.optDouble("quantity", 0), cost = item.optDouble("cost", 0);
            stockValue += qty * cost;
            content.addView(text(item.optString("name") + " — " + ui("মজুত: ") + df.format(qty) +
                " | " + ui("গড় ক্রয়মূল্য: ৳ ") + df.format(cost) + " | " +
                ui("মজুত মূল্য: ৳ ") + df.format(qty * cost), 16));
        }
        content.addView(text(ui("মোট মজুতের ক্রয়মূল্য: ৳ ") + df.format(stockValue), 19));
        content.addView(text(ui("মোট স্টক ক্রয়: ৳ ") + df.format(totalPurchases), 17));
        content.addView(text(ui("মোট স্টক বিক্রয়: ৳ ") + df.format(totalSales), 17));
        content.addView(text(ui("বিক্রিত পণ্যের ক্রয়মূল্য: ৳ ") + df.format(totalCostOfSales), 17));
        content.addView(text(ui("মোট স্থূল লাভ: ৳ ") + df.format(grossProfit), 20));
        content.addView(text(ui("ক্রয়-বিক্রয়ের সাম্প্রতিক ইতিহাস"), 19));
        int shownTransactions = 0;
        for (int i = records.length() - 1; i >= 0 && shownTransactions < 20; i--) {
            JSONObject transaction = records.optJSONObject(i);
            if (transaction == null || transaction.optString("stock_item").isEmpty()) continue;
            String type = transaction.optString("type");
            boolean isSale = transaction.optBoolean("stock_sale") ||
                (!transaction.optBoolean("stock_purchase") && "আয়".equals(type));
            double amount = transaction.optDouble("amount", 0);
            double transactionQty = transaction.optDouble("stock_quantity", 0);
            String detail = transaction.optString("date", "") + " | " +
                (isSale ? ui("বিক্রয়") : ui("ক্রয়")) + " | " +
                transaction.optString("stock_item") + " | " + ui("পরিমাণ: ") +
                df.format(transactionQty) + " | " + ui("মোট: ৳ ") + df.format(amount);
            if (isSale) {
                detail += " | " + ui("পণ্যের ক্রয়মূল্য: ৳ ") +
                    df.format(transaction.optDouble("cost_of_goods", 0)) +
                    " | " + ui("লাভ: ৳ ") + df.format(transaction.optDouble("gross_profit", 0));
            }
            detail += " | " + transaction.optString("party", "");
            content.addView(text(detail, 15));
            shownTransactions++;
        }
        if (shownTransactions == 0)
            content.addView(text(ui("এখনো কোনো ক্রয়-বিক্রয়ের ইতিহাস নেই"), 15));
        Button back = button("ড্যাশবোর্ডে ফিরুন");
        content.addView(back); back.setOnClickListener(v -> home());
    }

    private void createStockReportPdf() {
        android.graphics.pdf.PdfDocument pdf = new android.graphics.pdf.PdfDocument();
        try {
            boolean english = "en".equals(prefs.getString("language", "bn"));
            int accent = prefs.getInt("pdf_color", blue);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ArrayList<String[]> rows = new ArrayList<>();
            double stockValue = 0, totalSales = 0, totalCostOfSales = 0;
            double grossProfit = 0, totalPurchases = 0;
            for (int i = 0; i < stockItems.length(); i++) {
                JSONObject item = stockItems.optJSONObject(i);
                if (item == null) continue;
                double qty = item.optDouble("quantity", 0);
                double cost = item.optDouble("cost", 0);
                stockValue += qty * cost;
                rows.add(new String[]{english ? "STOCK" : "মজুত",
                    item.optString("name"), df.format(qty),
                    df.format(cost), df.format(qty * cost), ""});
            }
            for (int i = 0; i < records.length(); i++) {
                JSONObject r = records.optJSONObject(i);
                if (r == null || r.optString("stock_item").isEmpty()) continue;
                boolean sale = r.optBoolean("stock_sale") ||
                    (!r.optBoolean("stock_purchase") && "আয়".equals(r.optString("type")));
                boolean purchase = r.optBoolean("stock_purchase") || "খরচ".equals(r.optString("type"));
                double amount = r.optDouble("amount", 0);
                double costOfGoods = r.optDouble("cost_of_goods", 0);
                double profit = r.optDouble("gross_profit", 0);
                if (sale) {
                    totalSales += amount; totalCostOfSales += costOfGoods; grossProfit += profit;
                } else if (purchase) totalPurchases += amount;
                rows.add(new String[]{sale ? (english ? "SALE" : "বিক্রয়") :
                    (english ? "PURCHASE" : "ক্রয়"), r.optString("stock_item"),
                    df.format(r.optDouble("stock_quantity", 0)),
                    r.optString("date", ""), df.format(amount),
                    sale ? df.format(profit) : "—"});
            }

            int pageNo = 0, rowIndex = 0;
            boolean summaryPending = true;
            SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault());
            while (rowIndex < rows.size() || summaryPending) {
                pageNo++;
                android.graphics.pdf.PdfDocument.PageInfo info =
                    new android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, pageNo).create();
                android.graphics.pdf.PdfDocument.Page page = pdf.startPage(info);
                Canvas canvas = page.getCanvas();
                canvas.drawColor(Color.WHITE);
                paint.setColor(accent);
                canvas.drawRoundRect(28, 24, 567, 108, 14, 14, paint);
                try {
                    android.graphics.Bitmap logo = android.graphics.BitmapFactory.decodeResource(
                        getResources(), R.drawable.amar_hisab_logo);
                    if (logo != null) {
                        paint.setFilterBitmap(true);
                        canvas.drawBitmap(logo, null, new android.graphics.Rect(43, 38, 91, 86), paint);
                        logo.recycle();
                    }
                } catch (Exception ignored) { }
                paint.setColor(Color.WHITE);
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                paint.setTextSize(22);
                canvas.drawText(ui("আমার হিসাব"), 105, 57, paint);
                paint.setTextSize(12);
                canvas.drawText(english ? "SHOP STOCK & PROFIT REPORT" : "দোকানের স্টক ও লাভের রিপোর্ট",
                    105, 80, paint);
                paint.setTypeface(Typeface.DEFAULT);
                paint.setTextSize(9);
                canvas.drawText((english ? "PAGE " : "পৃষ্ঠা ") + pageNo, 500, 96, paint);
                drawDeveloperCredit(canvas, paint, 105, 96, Color.WHITE);

                paint.setColor(Color.rgb(241, 245, 250));
                canvas.drawRoundRect(30, 120, 565, 183, 9, 9, paint);
                paint.setColor(Color.rgb(45, 55, 72));
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                paint.setTextSize(10);
                canvas.drawText(english ? "REPORT SUMMARY" : "রিপোর্টের সারসংক্ষেপ", 43, 140, paint);
                paint.setTypeface(Typeface.DEFAULT);
                paint.setTextSize(9);
                canvas.drawText((english ? "Inventory value: BDT " : "বর্তমান মজুত মূল্য: ৳ ") +
                    df.format(stockValue) + "   " +
                    (english ? "Purchases: " : "মোট ক্রয়: ৳ ") + df.format(totalPurchases), 43, 158, paint);
                canvas.drawText((english ? "Sales: BDT " : "মোট বিক্রয়: ৳ ") +
                    df.format(totalSales) + "   " +
                    (english ? "Cost of sales: " : "বিক্রিত পণ্যের ক্রয়মূল্য: ৳ ") +
                    df.format(totalCostOfSales) + "   " +
                    (english ? "Gross profit: " : "স্থূল লাভ: ৳ ") + df.format(grossProfit), 43, 174, paint);

                int tableTop = 197;
                int[] x = new int[]{38, 115, 260, 330, 420, 500};
                paint.setColor(accent);
                canvas.drawRoundRect(34, tableTop, 561, tableTop + 29, 5, 5, paint);
                paint.setColor(Color.WHITE);
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                paint.setTextSize(8);
                String[] headings = english
                    ? new String[]{"TYPE", "PRODUCT", "QTY", "COST/DATE", "AMOUNT/VALUE", "PROFIT"}
                    : new String[]{"ধরন", "পণ্য", "পরিমাণ", "দর/তারিখ", "মোট/মূল্য", "লাভ"};
                for (int col = 0; col < headings.length; col++)
                    canvas.drawText(headings[col], x[col] + 3, tableTop + 18, paint);
                int y = tableTop + 29;
                paint.setTypeface(Typeface.DEFAULT);
                paint.setTextSize(8);
                while (rowIndex < rows.size() && y + 23 <= 746) {
                    String[] row = rows.get(rowIndex++);
                    paint.setColor((rowIndex & 1) == 0 ? Color.rgb(243, 247, 251) : Color.WHITE);
                    canvas.drawRect(34, y, 561, y + 23, paint);
                    paint.setColor(Color.rgb(45, 55, 72));
                    for (int col = 0; col < row.length; col++) {
                        String value = row[col] == null ? "" : row[col];
                        int max = col == 1 ? 22 : 13;
                        if (value.length() > max) value = value.substring(0, max - 1) + "…";
                        canvas.drawText(value, x[col] + 3, y + 15, paint);
                    }
                    paint.setColor(Color.rgb(225, 231, 239));
                    canvas.drawLine(34, y + 23, 561, y + 23, paint);
                    y += 23;
                }
                if (rowIndex >= rows.size() && summaryPending) {
                    paint.setColor(Color.rgb(241, 245, 250));
                    canvas.drawRoundRect(34, y + 8, 561, y + 48, 7, 7, paint);
                    paint.setColor(Color.rgb(45, 55, 72));
                    paint.setTypeface(Typeface.DEFAULT_BOLD);
                    paint.setTextSize(9);
                    canvas.drawText((english ? "GROSS PROFIT: BDT " : "মোট স্থূল লাভ: ৳ ") +
                        df.format(grossProfit), 47, y + 33, paint);
                    summaryPending = false;
                }
                String signature = prefs.getString("pdf_signature", "").trim();
                if (rowIndex >= rows.size()) {
                    drawSavedSignature(canvas, paint, 390, 748, 145, 34);
                    if (!signature.isEmpty()) {
                        paint.setColor(accent);
                        paint.setTypeface(Typeface.DEFAULT_BOLD);
                        paint.setTextSize(10);
                        canvas.drawText(signature, 390, 795, paint);
                        paint.setTypeface(Typeface.DEFAULT);
                    }
                }
                paint.setColor(Color.GRAY);
                paint.setTextSize(8);
                canvas.drawText((english ? "Generated by Amar Hisab" : "আমার হিসাব থেকে তৈরি") +
                    "  |  " + dateFormat.format(new Date()) + "  |  " +
                    (english ? "Page " : "পৃষ্ঠা ") + pageNo, 38, 820, paint);
                pdf.finishPage(page);
            }
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            pdf.writeTo(buffer);
            pendingPdfBytes = buffer.toByteArray();
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/pdf");
            intent.putExtra(Intent.EXTRA_TITLE, "Amar-Hisab-Stock-Report.pdf");
            startActivityForResult(intent, SAVE_STOCK_PDF);
        } catch (Exception e) {
            pendingPdfBytes = null;
            Toast.makeText(this, ui("স্টক রিপোর্ট PDF তৈরি করা যায়নি: ") + e.getMessage(),
                Toast.LENGTH_LONG).show();
        } finally {
            pdf.close();
        }
    }

    private void stockTransaction(boolean sale) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        EditText name = field("পণ্যের নাম");
        EditText qty = field("পরিমাণ");
        EditText price = field(sale ? "প্রতি পিস বিক্রয়মূল্য" : "প্রতি পিস ক্রয়মূল্য");
        EditText partyName = field("ক্রেতা / সরবরাহকারী (ঐচ্ছিক)");
        EditText paidField = field("এখন জমা (খালি রাখলে পুরো বাকি)");
        qty.setInputType(8194); price.setInputType(8194); paidField.setInputType(8194);
        box.addView(name); box.addView(qty); box.addView(price); box.addView(partyName);
        if (sale) box.addView(paidField);
        new AlertDialog.Builder(this).setTitle(ui(sale ? "পণ্য বিক্রি" : "পণ্য ক্রয়"))
            .setView(box).setNegativeButton(ui("বাতিল"), null)
            .setPositiveButton(ui(sale ? "বিক্রি সংরক্ষণ" : "ক্রয় সংরক্ষণ"), (d,w) -> {
                String product = name.getText().toString().trim();
                double quantity = number(qty.getText().toString());
                double unitPrice = number(price.getText().toString());
                if (!AccountingMath.isValidLineItem(product, quantity, unitPrice)) {
                    Toast.makeText(this, ui("পণ্যের নাম, পরিমাণ ও সঠিক দাম দিন; অতিরিক্ত বড় সংখ্যাও গ্রহণযোগ্য নয়"), Toast.LENGTH_SHORT).show(); return;
                }
                JSONObject item = findStockItem(product);
                String customer = partyName.getText().toString().trim();
                double requestedPaid = paidField.getText().toString().trim().isEmpty()
                    ? 0 : number(paidField.getText().toString());
                if (sale && (!Double.isFinite(requestedPaid) || requestedPaid < 0)) {
                    Toast.makeText(this, ui("জমার পরিমাণ শূন্য বা তার বেশি দিন"), Toast.LENGTH_SHORT).show(); return;
                }
                if (sale && (item == null || item.optDouble("quantity", 0) < quantity)) {
                    Toast.makeText(this, ui("পর্যাপ্ত স্টক নেই"), Toast.LENGTH_LONG).show(); return;
                }
                JSONArray stockBefore = null;
                JSONArray recordsBefore = null;
                try {
                    // Keep a rollback copy so stock and its accounting entry are saved together.
                    stockBefore = new JSONArray(stockItems.toString());
                    recordsBefore = new JSONArray(records.toString());
                    if (item == null) {
                        item = new JSONObject(); item.put("name", product);
                        item.put("quantity", 0); item.put("cost", 0); stockItems.put(item);
                    }
                    double oldQty = item.optDouble("quantity", 0);
                    double oldCost = item.optDouble("cost", 0);
                    double total = AccountingMath.lineTotal(quantity, unitPrice);
                    if (!Double.isFinite(oldQty) || !Double.isFinite(oldCost) ||
                        oldQty < 0 || oldCost < 0 || (sale && oldCost <= 0) ||
                        !Double.isFinite(total) || total <= 0) {
                        records = recordsBefore;
                        stockItems = stockBefore;
                        Toast.makeText(this, ui("আগের স্টক তথ্য বা লেনদেনের অঙ্ক সঠিক নয়; কোনো পরিবর্তন সংরক্ষণ হয়নি"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    JSONObject record = new JSONObject();
                    record.put("party", customer.isEmpty() ?
                        (sale ? "নগদ বিক্রয়" : "স্টক ক্রয়") : customer);
                    double paidNow = sale ? (customer.isEmpty() ? total : requestedPaid) : 0;
                    if (sale && (!Double.isFinite(paidNow) || paidNow > total)) {
                        records = recordsBefore; stockItems = stockBefore;
                        Toast.makeText(this, ui("এখন জমার টাকা মোট বিক্রয়মূল্যের বেশি হতে পারবে না"), Toast.LENGTH_SHORT).show(); return;
                    }
                    record.put("type", sale ? "পাওনা" : "খরচ");
                    record.put("category_id", CategoryAccounting.CATEGORY_SHOP);
                    record.put("amount", total);
                    if (sale) record.put("stock_sale", true);
                    else record.put("stock_purchase", true);
                    record.put("note", (sale ? "স্টক বিক্রয়: " : "স্টক ক্রয়: ") + product +
                        " (" + df.format(quantity) + " × " + df.format(unitPrice) + ")");
                    record.put("stock_item", product);
                    record.put("stock_quantity", quantity);
                    record.put("date", new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date()));
                    if (sale) {
                        double costOfGoods = AccountingMath.costOfGoodsSold(quantity, oldCost);
                        double profit = AccountingMath.grossProfit(total, costOfGoods);
                        double remainingQty = oldQty - quantity;
                        if (!Double.isFinite(costOfGoods) || !Double.isFinite(profit) ||
                            !Double.isFinite(remainingQty) || remainingQty < 0) {
                            records = recordsBefore;
                            stockItems = stockBefore;
                            Toast.makeText(this, ui("বিক্রয়ের হিসাব সঠিকভাবে করা যাচ্ছে না; স্টক অপরিবর্তিত আছে"), Toast.LENGTH_LONG).show();
                            return;
                        }
                        item.put("quantity", remainingQty);
                        record.put("cost_of_goods", costOfGoods);
                        record.put("gross_profit", profit);
                    } else {
                        double newQty = oldQty + quantity;
                        double averageCost = AccountingMath.weightedAverageCost(oldQty, oldCost,
                            quantity, unitPrice);
                        if (!Double.isFinite(newQty) || newQty <= 0 ||
                            !Double.isFinite(averageCost) || averageCost <= 0) {
                            records = recordsBefore;
                            stockItems = stockBefore;
                            Toast.makeText(this, ui("স্টকের পরিমাণ বা গড় ক্রয়মূল্য সীমার বাইরে; কোনো পরিবর্তন সংরক্ষণ হয়নি"), Toast.LENGTH_LONG).show();
                            return;
                        }
                        item.put("quantity", newQty); item.put("cost", averageCost);
                    }
                    records.put(record);
                    if (sale && paidNow > 0) {
                        JSONObject payment = new JSONObject();
                        payment.put("party", record.optString("party"));
                        payment.put("type", "জমা");
                        payment.put("category_id", CategoryAccounting.CATEGORY_SHOP);
                        payment.put("amount", paidNow);
                        payment.put("note", "স্টক বিক্রির টাকা জমা: " + product);
                        payment.put("date", record.optString("date"));
                        payment.put("stock_sale_payment_key", "STK-" + System.currentTimeMillis() + "-" + records.length());
                        payment.put("stock_sale_item", product);
                        records.put(payment);
                    }
                    boolean saved = prefs.edit()
                        .putString("records", records.toString())
                        .putString("stock_items", stockItems.toString())
                        .commit();
                    if (!saved) {
                        records = recordsBefore;
                        stockItems = stockBefore;
                        Toast.makeText(this, ui("লেনদেন সংরক্ষণ হয়নি; আগের স্টক ও হিসাব অপরিবর্তিত আছে"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(this, ui(sale ? "বিক্রি ও স্টক আপডেট হয়েছে" : "ক্রয় ও স্টক আপডেট হয়েছে"), Toast.LENGTH_LONG).show();
                    stockScreen();
                } catch (Exception e) {
                    if (recordsBefore != null) records = recordsBefore;
                    if (stockBefore != null) stockItems = stockBefore;
                    Toast.makeText(this, ui("লেনদেন সংরক্ষণ হয়নি: ") + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }).show();
    }

    private void rentScreen() {
        setup("বাড়ি ভাড়া ব্যবস্থাপনা");
        Button add = button("+ নতুন ভাড়াটিয়া / ইউনিট যোগ করুন");
        content.addView(add);
        add.setOnClickListener(v -> addRentalUnit());
        if (rentalUnits.length() == 0) content.addView(text("এখনো কোনো ইউনিট নেই", 16));
        for (int i = 0; i < rentalUnits.length(); i++) {
            JSONObject unit = rentalUnits.optJSONObject(i);
            if (unit == null) continue;
            String tenant = unit.optString("tenant");
            String label = unit.optString("building") + " / " + unit.optString("floor") +
                " — " + tenant + " — " + unit.optString("unit") +
                "\n" + ui("ধরন: ") + unit.optString("kind", ui("ইউনিট")) + " | " +
                ui(unit.optBoolean("occupied", true) ? "ভাড়াটিয়া আছে" : "খালি") +
                "\n" + ui("মাসিক ভাড়া: ৳ ") + df.format(unit.optDouble("rent", 0)) +
                " | " + ui("জামানত: ৳ ") + df.format(unit.optDouble("deposit", 0)) +
                "\n" + ui("ভাড়া দেওয়ার শেষ তারিখ: ") + unit.optInt("due_day", 10) +
                " | " + ui("বকেয়া: ৳ ") + df.format(rentUnitBalance(unit));
            Button card = button(label);
            content.addView(card);
            card.setOnClickListener(v -> rentalActions(unit));
        }
        Button report = button("সমগ্র বাড়িভাড়া আয়-ব্যয় রিপোর্ট");
        content.addView(report);
        report.setOnClickListener(v -> rentalPortfolioReport());
        Button back = button("ফিরে যান");
        content.addView(back);
        back.setOnClickListener(v -> home());
    }

    private void addRentalUnit() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        EditText tenant = field("ভাড়াটিয়ার নাম (খালি থাকলে লিখুন: খালি)");
        EditText building = field("বাড়ি / ভবনের নাম");
        EditText floor = field("তলা");
        EditText unit = field("ফ্ল্যাট / দোকান নম্বর");
        EditText kind = field("ইউনিটের ধরন: ফ্ল্যাট / দোকান");
        EditText rent = field("মাসিক ভাড়া (টাকা)");
        EditText deposit = field("ফেরতযোগ্য নিরাপত্তা জামানত (টাকা)");
        EditText dueDay = field("প্রতি মাসে ভাড়া দেওয়ার শেষ তারিখ (১-৩১)");
        EditText moveIn = field("ভাড়াটিয়া ওঠার তারিখ (DD-MM-YYYY)");
        moveIn.setText(new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date()));
        EditText agreement = field("চুক্তির বিবরণ / মেয়াদ (ঐচ্ছিক)");
        rent.setInputType(8194); deposit.setInputType(8194); dueDay.setInputType(2);
        box.addView(tenant); box.addView(building); box.addView(floor); box.addView(unit);
        box.addView(kind); box.addView(rent); box.addView(deposit); box.addView(dueDay);
        box.addView(moveIn); box.addView(agreement);
        new AlertDialog.Builder(this).setTitle(ui("নতুন বাড়ি / ফ্ল্যাট / দোকান")).setView(box)
            .setNegativeButton(ui("বাতিল"), null).setPositiveButton(ui("সংরক্ষণ"), (d, w) -> {
                String name = tenant.getText().toString().trim();
                boolean vacant = AccountingMath.isVacantTenantName(name);
                if (vacant) name = "খালি";
                String buildingName = building.getText().toString().trim();
                String place = unit.getText().toString().trim();
                double amount = number(rent.getText().toString());
                double depositAmount = number(deposit.getText().toString());
                int day = (int) number(dueDay.getText().toString());
                if (name.isEmpty() || buildingName.isEmpty() || place.isEmpty() ||
                    !AccountingMath.isValidRentalSetup(amount, depositAmount, day) ||
                    (vacant && depositAmount > 0)) {
                    Toast.makeText(this, ui("ভাড়াটিয়া, ভবন, ইউনিট, সঠিক ভাড়া/জামানত ও ১-৩১ তারিখ দিন। খালি ইউনিটে জামানত ০ হতে হবে।"), Toast.LENGTH_LONG).show();
                    return;
                }
                if (rentalUnitExists(buildingName, place, null)) {
                    Toast.makeText(this, ui("এই ভবনে এই ইউনিট নম্বরটি আগে যোগ করা হয়েছে"), Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    JSONObject item = new JSONObject();
                    item.put("tenant", name); item.put("building", buildingName);
                    item.put("floor", floor.getText().toString().trim()); item.put("unit", place);
                    item.put("kind", kind.getText().toString().trim()); item.put("rent", amount);
                    item.put("deposit", depositAmount); item.put("due_day", day);
                    item.put("occupied", !vacant);
                    item.put("move_in_date", vacant ? "" : moveIn.getText().toString().trim());
                    item.put("agreement_note", agreement.getText().toString().trim());
                    item.put("created_at", System.currentTimeMillis());
                    JSONArray recordsBefore = new JSONArray(records.toString());
                    JSONArray unitsBefore = copyJsonArray(rentalUnits);
                    rentalUnits.put(item);
                    if (depositAmount > 0 && !vacant)
                        addDepositTransaction(name, item, depositAmount, true,
                            "নতুন ইউনিটে প্রাথমিক ভাড়াটিয়ার জামানত গ্রহণ");
                    if (!saveRentalState(recordsBefore, unitsBefore)) {
                        Toast.makeText(this, ui("ইউনিট ও জামানত সংরক্ষণ হয়নি; আগের তথ্য অক্ষত আছে"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    rentScreen();
                } catch (Exception e) {
                    Toast.makeText(this, ui("সংরক্ষণ করা যায়নি"), Toast.LENGTH_SHORT).show();
                }
            }).show();
    }

    private void rentalActions(JSONObject unit) {
        if (!unit.optBoolean("occupied", true) || "খালি".equals(unit.optString("tenant"))) {
            new AlertDialog.Builder(this).setTitle(ui("ইউনিট খালি"))
                .setItems(new String[]{ui("নতুন ভাড়াটিয়া বসান"), ui("ইউনিটের তথ্য সম্পাদনা"), ui("আগের ভাড়াটিয়ার ইতিহাস"),
                    ui("অবশিষ্ট জামানত ফেরত দিন"), ui("জামানতের ইতিহাস")}, (d,w) -> {
                    if (w == 0) assignTenant(unit);
                    else if (w == 1) editRentalUnit(unit);
                    else if (w == 2) tenancyHistory(unit);
                    else if (w == 3) refundDeposit(unit);
                    else depositHistory(unit);
                }).show();
            return;
        }
        String tenant = unit.optString("tenant");
        String[] actions = {"এই মাসের ভাড়া বকেয়া যোগ করুন", "ভাড়াটিয়ার খাতা দেখুন",
            "ভাড়া জমা নিন (আংশিক / পূর্ণ)", "ভাড়া রিপোর্ট", "ইউটিলিটি / সার্ভিস চার্জ যোগ",
            "মেরামত / বাড়ির খরচ যোগ", "ফেরতযোগ্য জামানত পরিবর্তন", "ভাড়া বৃদ্ধি / কমান",
            "ভাড়াটিয়া চলে গেছেন / ইউনিট খালি করুন", "ইউনিটের তথ্য সম্পাদনা", "জামানতের লেনদেন দেখুন", "ইউটিলিটি বিল জমা নিন",
            "ভাড়াটিয়া ওঠা-নামার ইতিহাস", "জামানত ফেরত দিন"};
        for (int i = 0; i < actions.length; i++) actions[i] = ui(actions[i]);
        new AlertDialog.Builder(this).setTitle(tenant + " — " + unit.optString("unit"))
            .setItems(actions, (d, w) -> {
                if (w == 0) postMonthlyRent(unit);
                else if (w == 1) tenantRentalLedger(unit);
                else if (w == 2) collectRent(unit);
                else if (w == 3) rentReport(unit);
                else if (w == 4) addRentalCharge(unit);
                else if (w == 5) addRentalExpense(unit);
                else if (w == 6) changeDeposit(unit);
                else if (w == 7) changeRent(unit);
                else if (w == 8) vacateUnit(unit);
                else if (w == 9) editRentalUnit(unit);
                else if (w == 10) depositHistory(unit);
                else if (w == 11) collectRentalCharge(unit);
                else if (w == 12) tenancyHistory(unit);
                else refundDeposit(unit);
            }).show();
    }

    private double rentUnitBalance(JSONObject unit) {
        String tenant = unit.optString("tenant"), unitName = unit.optString("unit");
        String building = unit.optString("building");
        double balance = 0;
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.optJSONObject(i);
            if (r == null || !tenant.equals(r.optString("party")) ||
                !unitName.equals(r.optString("rent_unit")) ||
                !building.equals(r.optString("rent_building"))) continue;
            if (!r.optString("rent_key").isEmpty() || r.optBoolean("rental_charge"))
                balance += r.optDouble("amount", 0);
            String chargePaymentKey = r.optString("rental_charge_payment_key");
            if (!chargePaymentKey.isEmpty()) {
                for (int j = 0; j < records.length(); j++) {
                    JSONObject charge = records.optJSONObject(j);
                    if (charge != null && AccountingMath.matchesRentalRecordLink(chargePaymentKey,
                        charge.optString("rental_charge_key"), tenant, unitName, building,
                        charge.optString("party"), charge.optString("rent_unit"),
                        charge.optString("rent_building"))) {
                        balance -= r.optDouble("amount", 0); break;
                    }
                }
            }
            String paymentKey = r.optString("rent_payment_key");
            if (!paymentKey.isEmpty()) {
                for (int j = 0; j < records.length(); j++) {
                    JSONObject due = records.optJSONObject(j);
                    if (due != null && AccountingMath.matchesRentalRecordLink(paymentKey,
                        due.optString("rent_key"), tenant, unitName, building,
                        due.optString("party"), due.optString("rent_unit"),
                        due.optString("rent_building"))) {
                        balance -= r.optDouble("amount", 0); break;
                    }
                }
            }
        }
        return Math.max(0, balance);
    }

    private String monthFromDate(String value) {
        return AccountingMath.monthFromDate(value);
    }

    private void rentalPortfolioReport() {
        setup("সমগ্র বাড়িভাড়া রিপোর্ট");
        String currentMonth = new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date());
        String currentYear = currentMonth.substring(0, 4);
        double rentDue = 0, rentPaid = 0, charges = 0, chargePaid = 0, expenses = 0, deposits = 0;
        double monthDue = 0, monthPaid = 0, monthDuePaid = 0, yearDue = 0, yearPaid = 0, yearDuePaid = 0;
        double monthCharges = 0, monthChargePaid = 0, monthChargeDuePaid = 0;
        double yearCharges = 0, yearChargePaid = 0, yearChargeDuePaid = 0;
        double monthExpense = 0, yearExpense = 0;
        int occupied = 0, vacant = 0;
        for (int i = 0; i < rentalUnits.length(); i++) {
            JSONObject u = rentalUnits.optJSONObject(i);
            if (u == null) continue;
            if (u.optBoolean("occupied", true)) occupied++; else vacant++;
            double deposit = u.optDouble("deposit", 0);
            if (Double.isFinite(deposit) && deposit > 0) deposits += deposit;
        }
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.optJSONObject(i);
            if (r == null) continue;
            String period = monthFromDate(r.optString("date"));
            double amount = r.optDouble("amount", 0);
            if (!Double.isFinite(amount) || amount < 0) continue;

            String key = r.optString("rent_key");
            if (!key.isEmpty()) {
                String dueMonth = key.length() >= 7 ? key.substring(0, 7) : "";
                rentDue += amount;
                double paidForThisDue = 0;
                for (int j = 0; j < records.length(); j++) {
                    JSONObject payment = records.optJSONObject(j);
                    if (payment != null && key.equals(payment.optString("rent_payment_key")) &&
                        AccountingMath.sameRentalUnit(r.optString("party"),
                            r.optString("rent_unit"), r.optString("rent_building"),
                            payment.optString("party"), payment.optString("rent_unit"),
                            payment.optString("rent_building"))) {
                        double paid = payment.optDouble("amount", 0);
                        if (Double.isFinite(paid) && paid > 0) paidForThisDue += paid;
                    }
                }
                if (currentMonth.equals(dueMonth)) {
                    monthDue += amount;
                    monthDuePaid += paidForThisDue;
                }
                if (dueMonth.startsWith(currentYear)) {
                    yearDue += amount;
                    yearDuePaid += paidForThisDue;
                }
            }

            // Collections belong to the month/year when money was actually received,
            // not the original rent month. This keeps cash-flow reports accurate for arrears.
            if (!r.optString("rent_payment_key").isEmpty()) {
                rentPaid += amount;
                if (currentMonth.equals(period)) monthPaid += amount;
                if (period.startsWith(currentYear)) yearPaid += amount;
            }
            if (r.optBoolean("rental_charge")) {
                charges += amount;
                if (currentMonth.equals(period)) monthCharges += amount;
                if (period.startsWith(currentYear)) yearCharges += amount;
                String chargeKey = r.optString("rental_charge_key");
                double paidForCharge = 0;
                if (!chargeKey.isEmpty()) {
                    for (int j = 0; j < records.length(); j++) {
                        JSONObject payment = records.optJSONObject(j);
                        if (payment != null && chargeKey.equals(payment.optString("rental_charge_payment_key")) &&
                            AccountingMath.sameRentalUnit(r.optString("party"),
                                r.optString("rent_unit"), r.optString("rent_building"),
                                payment.optString("party"), payment.optString("rent_unit"),
                                payment.optString("rent_building"))) {
                            double paid = payment.optDouble("amount", 0);
                            if (Double.isFinite(paid) && paid > 0) paidForCharge += paid;
                        }
                    }
                }
                if (currentMonth.equals(period)) monthChargeDuePaid += paidForCharge;
                if (period.startsWith(currentYear)) yearChargeDuePaid += paidForCharge;
            }
            if (!r.optString("rental_charge_payment_key").isEmpty()) {
                chargePaid += amount;
                if (currentMonth.equals(period)) monthChargePaid += amount;
                if (period.startsWith(currentYear)) yearChargePaid += amount;
            }
            if (r.optBoolean("rental_expense")) {
                expenses += amount;
                if (currentMonth.equals(period)) monthExpense += amount;
                if (period.startsWith(currentYear)) yearExpense += amount;
            }
        }
        content.addView(text(ui("মোট ইউনিট: ") + rentalUnits.length() + " | " +
            ui("ভাড়াটিয়া আছে: ") + occupied + " | " + ui("খালি: ") + vacant, 17));
        content.addView(text("বর্তমান জামানত (সংরক্ষিত): ৳ " + df.format(deposits), 18));
        content.addView(text("সর্বমোট ধার্য ভাড়া: ৳ " + df.format(rentDue), 18));
        content.addView(text("সর্বমোট ভাড়া আদায়: ৳ " + df.format(rentPaid), 18));
        content.addView(text("সর্বমোট ভাড়ার বকেয়া: ৳ " + df.format(Math.max(0, rentDue-rentPaid)), 18));
        content.addView(text("ইউটিলিটি / সার্ভিস চার্জ ধার্য: ৳ " + df.format(charges), 18));
        content.addView(text("ইউটিলিটি বিল আদায়: ৳ " + df.format(chargePaid), 18));
        content.addView(text("ইউটিলিটি বিল বকেয়া: ৳ " + df.format(Math.max(0, charges-chargePaid)), 18));
        content.addView(text("মেরামত ও অন্যান্য খরচ (সর্বমোট): ৳ " + df.format(expenses), 18));
        content.addView(text(ui("এই মাস (") + currentMonth + ") — " +
            ui("ভাড়া ধার্য: ৳ ") + df.format(monthDue) + " | " +
            ui("আদায়: ৳ ") + df.format(monthPaid) + " | " +
            ui("বকেয়া: ৳ ") + df.format(Math.max(0, monthDue-monthDuePaid)), 17));
        content.addView(text(ui("এই বছর (") + currentYear + ") — " +
            ui("ভাড়া ধার্য: ৳ ") + df.format(yearDue) + " | " +
            ui("আদায়: ৳ ") + df.format(yearPaid) + " | " +
            ui("বকেয়া: ৳ ") + df.format(Math.max(0, yearDue-yearDuePaid)), 17));
        content.addView(text(ui("এই মাসের ইউটিলিটি চার্জ: ৳ ") + df.format(monthCharges) +
            " | " + ui("আদায়: ৳ ") + df.format(monthChargePaid) +
            " | " + ui("বকেয়া: ৳ ") + df.format(Math.max(0, monthCharges-monthChargeDuePaid)), 17));
        content.addView(text(ui("এই বছরের ইউটিলিটি চার্জ: ৳ ") + df.format(yearCharges) +
            " | " + ui("আদায়: ৳ ") + df.format(yearChargePaid) +
            " | " + ui("বকেয়া: ৳ ") + df.format(Math.max(0, yearCharges-yearChargeDuePaid)), 17));
        content.addView(text(ui("এই মাসের বাড়ির খরচ: ৳ ") + df.format(monthExpense) +
            " | " + ui("এই বছরের খরচ: ৳ ") + df.format(yearExpense), 17));
        content.addView(text("এই বছরের ভাড়া আদায় থেকে বাড়ির খরচ বাদে: ৳ " + df.format(yearPaid-yearExpense), 20));
        content.addView(text("জামানত আয় নয়; এটি আলাদা সংরক্ষিত হিসাব।", 14));
        Button back = button(ui("ভাড়া ব্যবস্থাপনায় ফিরুন"));
        content.addView(back); back.setOnClickListener(v -> rentScreen());
    }

    private void addRentalCharge(JSONObject unit) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        EditText title = field("চার্জের নাম: বিদ্যুৎ / পানি / গ্যাস / সার্ভিস");
        EditText amount = field("টাকার পরিমাণ"); amount.setInputType(8194);
        box.addView(title); box.addView(amount);
        new AlertDialog.Builder(this).setTitle(ui("ভাড়াটিয়ার বিল যোগ")).setView(box)
            .setNegativeButton(ui("বাতিল"), null).setPositiveButton(ui("হিসাবে যোগ"), (d,w) -> {
                double value = number(amount.getText().toString());
                if (value <= 0 || unit.optString("tenant").isEmpty()) {
                    Toast.makeText(this, ui("সঠিক ভাড়াটিয়া ও টাকা দিন"), Toast.LENGTH_SHORT).show(); return;
                }
                try {
                    JSONArray recordsBefore = new JSONArray(records.toString());
                    JSONArray unitsBefore = new JSONArray(rentalUnits.toString());
                    JSONObject r = new JSONObject(); r.put("party", unit.optString("tenant"));
                    r.put("type", "পাওনা"); r.put("amount", value);
                    r.put("note", "বাড়ি ভাড়া বিল — " + unit.optString("building") + " / " +
                        unit.optString("unit") + " — " + title.getText().toString().trim());
                    r.put("date", new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date()));
                    r.put("rental_charge", true); r.put("category_id", CategoryAccounting.CATEGORY_RENT); r.put("rent_unit", unit.optString("unit"));
                    r.put("rent_building", unit.optString("building"));
                    r.put("rental_charge_key", "CHG-" + System.currentTimeMillis());
                    records.put(r);
                    if (!saveRentalState(recordsBefore, unitsBefore)) {
                        Toast.makeText(this, ui("বিল সংরক্ষণ হয়নি; আগের হিসাব অক্ষত আছে"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    rentScreen();
                } catch (Exception e) { Toast.makeText(this, ui("বিল যোগ হয়নি"), Toast.LENGTH_SHORT).show(); }
            }).show();
    }

    private void collectRentalCharge(JSONObject unit) {
        String tenant = unit.optString("tenant"), unitName = unit.optString("unit");
        String building = unit.optString("building");
        ArrayList<String> keys = new ArrayList<>(), labels = new ArrayList<>();
        ArrayList<Double> balances = new ArrayList<>();
        for (int i = 0; i < records.length(); i++) {
            JSONObject charge = records.optJSONObject(i);
            if (charge == null || !charge.optBoolean("rental_charge") ||
                !tenant.equals(charge.optString("party")) ||
                !unitName.equals(charge.optString("rent_unit")) ||
                !building.equals(charge.optString("rent_building"))) continue;
            String key = charge.optString("rental_charge_key");
            if (key.isEmpty()) continue;
            double paid = 0;
            for (int j = 0; j < records.length(); j++) {
                JSONObject p = records.optJSONObject(j);
                if (p != null && key.equals(p.optString("rental_charge_payment_key")) &&
                    AccountingMath.sameRentalUnit(tenant, unitName, building,
                        p.optString("party"), p.optString("rent_unit"), p.optString("rent_building")))
                    paid += p.optDouble("amount", 0);
            }
            double due = charge.optDouble("amount", 0), balance = Math.max(0, due-paid);
            if (balance > 0) {
                keys.add(key); balances.add(balance);
                labels.add(charge.optString("note") + " — " + ui("বাকি ৳ ") + df.format(balance));
            }
        }
        if (keys.isEmpty()) {
            new AlertDialog.Builder(this).setMessage(ui("এই ইউনিটে কোনো বকেয়া ইউটিলিটি বিল নেই।"))
                .setPositiveButton(ui("ঠিক আছে"), null).show(); return;
        }
        Spinner choices = new Spinner(this);
        choices.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        EditText amount = field("জমার পরিমাণ"); amount.setInputType(8194);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.addView(choices); box.addView(amount);
        new AlertDialog.Builder(this).setTitle(ui("ইউটিলিটি বিল জমা নিন")).setView(box)
            .setNegativeButton(ui("বাতিল"), null).setPositiveButton(ui("জমা সংরক্ষণ"), (d,w) -> {
                int index = choices.getSelectedItemPosition();
                double paid = number(amount.getText().toString());
                if (index < 0 || index >= balances.size() || paid <= 0 || paid > balances.get(index)) {
                    Toast.makeText(this, ui("সঠিক জমার পরিমাণ দিন"), Toast.LENGTH_SHORT).show(); return;
                }
                try {
                    JSONArray recordsBefore = new JSONArray(records.toString());
                    JSONArray unitsBefore = new JSONArray(rentalUnits.toString());
                    JSONObject payment = new JSONObject();
                    payment.put("party", tenant); payment.put("type", "জমা");
                    payment.put("amount", paid); payment.put("category_id", CategoryAccounting.CATEGORY_RENT);
                    payment.put("rental_charge_payment_key", keys.get(index));
                    payment.put("rent_unit", unitName); payment.put("rent_building", building);
                    payment.put("note", "ইউটিলিটি বিল জমা — " + unitName);
                    payment.put("date", new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date()));
                    records.put(payment);
                    if (!saveRentalState(recordsBefore, unitsBefore)) {
                        Toast.makeText(this, ui("জমা সংরক্ষণ হয়নি; আগের হিসাব অক্ষত আছে"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    rentScreen();
                } catch (Exception e) { Toast.makeText(this, ui("জমা সংরক্ষণ হয়নি"), Toast.LENGTH_SHORT).show(); }
            }).show();
    }

    private void addRentalExpense(JSONObject unit) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        EditText title = field("খরচের বিবরণ: মেরামত / রং / পরিষ্কার");
        EditText amount = field("খরচের পরিমাণ"); amount.setInputType(8194);
        box.addView(title); box.addView(amount);
        new AlertDialog.Builder(this).setTitle(ui("বাড়ির মেরামত / খরচ")).setView(box)
            .setNegativeButton(ui("বাতিল"), null).setPositiveButton(ui("খরচ সংরক্ষণ"), (d,w) -> {
                double value = number(amount.getText().toString());
                if (value <= 0) { Toast.makeText(this, ui("সঠিক খরচ লিখুন"), Toast.LENGTH_SHORT).show(); return; }
                try {
                    JSONArray recordsBefore = new JSONArray(records.toString());
                    JSONArray unitsBefore = new JSONArray(rentalUnits.toString());
                    JSONObject r = new JSONObject(); r.put("party", "বাড়ি ভাড়া: " + unit.optString("building") + " / " + unit.optString("unit"));
                    r.put("type", "খরচ"); r.put("amount", value);
                    r.put("note", title.getText().toString().trim());
                    r.put("date", new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date()));
                    r.put("rental_expense", true);
                    r.put("category_id", CategoryAccounting.CATEGORY_RENT);
                    r.put("rent_unit", unit.optString("unit"));
                    r.put("rent_building", unit.optString("building"));
                    records.put(r);
                    if (!saveRentalState(recordsBefore, unitsBefore)) {
                        Toast.makeText(this, ui("খরচ সংরক্ষণ হয়নি; আগের হিসাব অক্ষত আছে"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    rentScreen();
                } catch (Exception e) { Toast.makeText(this, ui("খরচ যোগ হয়নি"), Toast.LENGTH_SHORT).show(); }
            }).show();
    }

    private void changeDeposit(JSONObject unit) {
        EditText value = field("নতুন মোট ফেরতযোগ্য জামানত");
        value.setInputType(8194); value.setText(df.format(unit.optDouble("deposit", 0)));
        new AlertDialog.Builder(this).setTitle(ui("ফেরতযোগ্য জামানত")).setView(value)
            .setNegativeButton(ui("বাতিল"), null).setPositiveButton(ui("সংরক্ষণ"), (d,w) -> {
                double amount = number(value.getText().toString());
                if (!Double.isFinite(amount) || amount < 0) {
                    Toast.makeText(this, ui("সঠিক অঙ্ক দিন"), Toast.LENGTH_SHORT).show(); return;
                }
                double previous = unit.optDouble("deposit", 0);
                try {
                    JSONArray recordsBefore = new JSONArray(records.toString());
                    JSONArray unitsBefore = copyJsonArray(rentalUnits);
                    JSONArray history = unit.optJSONArray("deposit_history");
                    if (history == null) history = new JSONArray();
                    JSONObject movement = new JSONObject();
                    movement.put("date", new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date()));
                    movement.put("old_amount", previous); movement.put("new_amount", amount);
                    movement.put("change", amount - previous);
                    history.put(movement);
                    unit.put("deposit_history", history);
                    unit.put("deposit", amount);
                    double difference = amount - previous;
                    String depositTenant = AccountingMath.depositOwner(
                        unit.optString("tenant"), unit.optString("deposit_owner"));
                    if (difference != 0 && !depositTenant.trim().isEmpty() &&
                        !"খালি".equals(depositTenant)) {
                        addDepositTransaction(depositTenant, unit, Math.abs(difference),
                            difference > 0, difference > 0
                                ? "জামানত বৃদ্ধি / অতিরিক্ত গ্রহণ"
                                : "জামানত সমন্বয় / ফেরত");
                    }
                    // Keep the departing tenant's archived tenancy snapshot in sync
                    // when their retained deposit is adjusted while the unit is vacant.
                    if ("খালি".equals(unit.optString("tenant"))) {
                        JSONArray tenancyHistory = unit.optJSONArray("tenancy_history");
                        if (tenancyHistory != null) {
                            for (int i = tenancyHistory.length() - 1; i >= 0; i--) {
                                JSONObject lastTenancy = tenancyHistory.optJSONObject(i);
                                if (lastTenancy != null &&
                                    depositTenant.equals(lastTenancy.optString("tenant"))) {
                                    lastTenancy.put("deposit", amount);
                                    lastTenancy.put("deposit_history", new JSONArray(history.toString()));
                                    break;
                                }
                            }
                        }
                    }
                    if (!saveRentalState(recordsBefore, unitsBefore)) {
                        Toast.makeText(this, ui("জামানত ও লেনদেন সংরক্ষণ হয়নি; আগের তথ্য অক্ষত আছে"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    rentScreen();
                } catch (Exception e) { Toast.makeText(this, ui("সংরক্ষণ হয়নি"), Toast.LENGTH_SHORT).show(); }
            }).show();
    }

    private void depositHistory(JSONObject unit) {
        setup("জামানতের লেনদেন");
        content.addView(text("বর্তমান জামানত: ৳ " + df.format(unit.optDouble("deposit", 0)), 20));
        JSONArray history = unit.optJSONArray("deposit_history");
        if (history == null || history.length() == 0) {
            content.addView(text("এখনো জামানতের পরিবর্তনের ইতিহাস নেই।", 16));
        } else {
            for (int i = history.length() - 1; i >= 0; i--) {
                JSONObject item = history.optJSONObject(i);
                if (item == null) continue;
                double change = item.optDouble("change", 0);
                content.addView(text(item.optString("date") + " | আগের: ৳ " +
                    df.format(item.optDouble("old_amount", 0)) + " | নতুন: ৳ " +
                    df.format(item.optDouble("new_amount", 0)) + " | পরিবর্তন: " +
                    (change >= 0 ? "+" : "") + df.format(change), 16));
            }
        }
        Button back = button("ফিরে যান");
        content.addView(back); back.setOnClickListener(v -> rentalActions(unit));
    }

    private void changeRent(JSONObject unit) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        EditText value = field("নতুন মাসিক ভাড়া"); value.setInputType(8194);
        value.setText(df.format(unit.optDouble("rent", 0)));
        EditText effective = field("কার্যকর হওয়ার মাস (YYYY-MM), যেমন 2026-12");
        box.addView(value); box.addView(effective);
        new AlertDialog.Builder(this).setTitle(ui("ভাড়া বৃদ্ধি / কমানো")).setView(box)
            .setNegativeButton(ui("বাতিল"), null).setPositiveButton(ui("সংরক্ষণ"), (d,w) -> {
                double amount = number(value.getText().toString());
                String month = effective.getText().toString().trim();
                if (amount <= 0 || !AccountingMath.isValidYearMonth(month)) {
                    Toast.makeText(this, ui("ভাড়া ও YYYY-MM মাস সঠিকভাবে দিন"), Toast.LENGTH_LONG).show(); return;
                }
                try {
                    JSONArray unitsBefore = copyJsonArray(rentalUnits);
                    JSONArray schedule = unit.optJSONArray("rent_schedule");
                    if (schedule == null) schedule = new JSONArray();
                    boolean hasInitial = false;
                    for (int i = 0; i < schedule.length(); i++) {
                        JSONObject item = schedule.optJSONObject(i);
                        if (item != null && "0000-00".equals(item.optString("from"))) hasInitial = true;
                    }
                    if (!hasInitial) {
                        JSONObject initial = new JSONObject();
                        initial.put("from", "0000-00");
                        initial.put("amount", unit.optDouble("rent", 0));
                        schedule.put(initial);
                    }
                    JSONObject revision = new JSONObject();
                    revision.put("from", month); revision.put("amount", amount);
                    boolean replaced = false;
                    for (int i = 0; i < schedule.length(); i++) {
                        JSONObject item = schedule.optJSONObject(i);
                        if (item != null && month.equals(item.optString("from"))) {
                            schedule.put(i, revision); replaced = true; break;
                        }
                    }
                    if (!replaced) schedule.put(revision);
                    unit.put("rent_schedule", schedule);
                    String currentMonth = new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date());
                    unit.put("rent", rentForMonth(unit, currentMonth));
                    unit.put("rent_effective", month);
                    if (!saveRentalUnits(unitsBefore)) {
                        Toast.makeText(this, ui("ভাড়া পরিবর্তন সংরক্ষণ হয়নি; আগের তথ্য অক্ষত আছে"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    rentScreen();
                } catch (Exception e) { Toast.makeText(this, ui("ভাড়া পরিবর্তন হয়নি"), Toast.LENGTH_SHORT).show(); }
            }).show();
    }

    private JSONObject buildTenancyClosingStatement(JSONObject unit) throws JSONException {
        String tenant = unit.optString("tenant");
        String unitName = unit.optString("unit");
        String building = unit.optString("building");
        JSONArray rentItems = new JSONArray();
        JSONArray chargeItems = new JSONArray();
        double rentDueTotal = 0, rentPaidTotal = 0, rentOutstandingTotal = 0;
        double chargeDueTotal = 0, chargePaidTotal = 0, chargeOutstandingTotal = 0;

        for (int i = 0; i < records.length(); i++) {
            JSONObject due = records.optJSONObject(i);
            if (due == null || !AccountingMath.sameRentalUnit(tenant, unitName, building,
                due.optString("party"), due.optString("rent_unit"), due.optString("rent_building"))) continue;

            String rentKey = due.optString("rent_key");
            if (!rentKey.isEmpty()) {
                ArrayList<Double> payments = new ArrayList<>();
                for (int j = 0; j < records.length(); j++) {
                    JSONObject payment = records.optJSONObject(j);
                    if (payment != null && AccountingMath.matchesRentalRecordLink(rentKey,
                        payment.optString("rent_payment_key"), tenant, unitName, building,
                        payment.optString("party"), payment.optString("rent_unit"),
                        payment.optString("rent_building"))) {
                        double amount = payment.optDouble("amount", 0);
                        if (Double.isFinite(amount) && amount > 0) payments.add(amount);
                    }
                }
                double[] values = new double[payments.size()];
                for (int j = 0; j < payments.size(); j++) values[j] = payments.get(j);
                double dueAmount = due.optDouble("amount", 0);
                if (!Double.isFinite(dueAmount) || dueAmount < 0) dueAmount = 0;
                double paidAmount = AccountingMath.sumRentalPayments(values);
                JSONObject item = new JSONObject();
                item.put("month", rentKey.length() >= 7 ? rentKey.substring(0, 7) : rentKey);
                item.put("due", dueAmount);
                item.put("paid", paidAmount);
                item.put("balance", AccountingMath.rentalOutstanding(dueAmount, values));
                rentItems.put(item);
                rentDueTotal += dueAmount;
                rentPaidTotal += paidAmount;
                rentOutstandingTotal += AccountingMath.rentalOutstanding(dueAmount, values);
            }

            if (due.optBoolean("rental_charge")) {
                String chargeKey = due.optString("rental_charge_key");
                ArrayList<Double> payments = new ArrayList<>();
                if (!chargeKey.isEmpty()) {
                    for (int j = 0; j < records.length(); j++) {
                        JSONObject payment = records.optJSONObject(j);
                        if (payment != null && chargeKey.equals(payment.optString("rental_charge_payment_key")) &&
                            AccountingMath.sameRentalUnit(tenant, unitName, building,
                                payment.optString("party"), payment.optString("rent_unit"),
                                payment.optString("rent_building"))) {
                            double amount = payment.optDouble("amount", 0);
                            if (Double.isFinite(amount) && amount > 0) payments.add(amount);
                        }
                    }
                }
                double[] values = new double[payments.size()];
                for (int j = 0; j < payments.size(); j++) values[j] = payments.get(j);
                double dueAmount = due.optDouble("amount", 0);
                if (!Double.isFinite(dueAmount) || dueAmount < 0) dueAmount = 0;
                double paidAmount = AccountingMath.sumRentalPayments(values);
                JSONObject item = new JSONObject();
                item.put("description", due.optString("note"));
                item.put("date", due.optString("date"));
                item.put("due", dueAmount);
                item.put("paid", paidAmount);
                item.put("balance", AccountingMath.rentalOutstanding(dueAmount, values));
                chargeItems.put(item);
                chargeDueTotal += dueAmount;
                chargePaidTotal += paidAmount;
                chargeOutstandingTotal += AccountingMath.rentalOutstanding(dueAmount, values);
            }
        }

        JSONObject statement = new JSONObject();
        statement.put("rent_items", rentItems);
        statement.put("charge_items", chargeItems);
        statement.put("rent_due", rentDueTotal);
        statement.put("rent_paid", rentPaidTotal);
        statement.put("rent_balance", rentOutstandingTotal);
        statement.put("charge_due", chargeDueTotal);
        statement.put("charge_paid", chargePaidTotal);
        statement.put("charge_balance", chargeOutstandingTotal);
        statement.put("closing_balance", rentOutstandingTotal + chargeOutstandingTotal);
        return statement;
    }

    private void vacateUnit(JSONObject unit) {
        String currentTenant = unit.optString("tenant");
        EditText moveOut = field("বাসা ছাড়ার তারিখ (DD-MM-YYYY)");
        moveOut.setText(new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date()));
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text(ui("ভাড়াটিয়া: ") + currentTenant + " | " +
            ui("ইউনিট: ") + unit.optString("unit"), 16));
        box.addView(moveOut);
        new AlertDialog.Builder(this).setTitle(ui("ভাড়াটিয়া চলে গেছেন / ইউনিট খালি"))
            .setMessage(ui("পুরোনো ভাড়াটিয়ার লেনদেন ও বকেয়া মুছে যাবে না। ওঠা-নামার ইতিহাসে সংরক্ষিত হবে।"))
            .setView(box).setNegativeButton(ui("বাতিল"), null).setPositiveButton(ui("খালি করুন"), (d,w) -> {
                if (currentTenant.isEmpty() || "খালি".equals(currentTenant)) return;
                try {
                    JSONArray unitsBefore = copyJsonArray(rentalUnits);
                    JSONArray history = unit.optJSONArray("tenancy_history");
                    if (history == null) history = new JSONArray();
                    JSONObject old = new JSONObject();
                    old.put("tenant", currentTenant);
                    old.put("move_in", unit.optString("move_in_date"));
                    old.put("move_out", moveOut.getText().toString().trim());
                    old.put("rent", unit.optDouble("rent", 0));
                    // Snapshot the departing tenant's unpaid rent and rental charges.
                    // The original ledger entries remain attached to that tenant.
                    JSONObject closingStatement = buildTenancyClosingStatement(unit);
                    old.put("closing_statement", closingStatement);
                    old.put("closing_balance", closingStatement.optDouble("closing_balance", 0));
                    old.put("closing_rent_due", closingStatement.optDouble("rent_due", 0));
                    old.put("closing_rent_paid", closingStatement.optDouble("rent_paid", 0));
                    old.put("closing_rent_balance", closingStatement.optDouble("rent_balance", 0));
                    old.put("closing_charge_due", closingStatement.optDouble("charge_due", 0));
                    old.put("closing_charge_paid", closingStatement.optDouble("charge_paid", 0));
                    old.put("closing_charge_balance", closingStatement.optDouble("charge_balance", 0));
                    old.put("deposit", unit.optDouble("deposit", 0));
                    old.put("agreement", unit.optString("agreement_note"));
                    JSONArray oldDepositHistory = unit.optJSONArray("deposit_history");
                    old.put("deposit_history", oldDepositHistory == null ?
                        new JSONArray() : new JSONArray(oldDepositHistory.toString()));
                    history.put(old);
                    unit.put("tenancy_history", history);
                    unit.put("deposit_owner", currentTenant);
                    unit.put("tenant", "খালি"); unit.put("occupied", false);
                    unit.put("move_out_date", moveOut.getText().toString().trim());
                    unit.put("move_in_date", "");
                    if (!saveRentalUnits(unitsBefore)) {
                        Toast.makeText(this, ui("ইউনিট খালি করার তথ্য সংরক্ষণ হয়নি; আগের তথ্য অক্ষত আছে"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    rentScreen();
                } catch (Exception e) { Toast.makeText(this, ui("ইউনিট খালি করা যায়নি"), Toast.LENGTH_SHORT).show(); }
            }).show();
    }

    private void assignTenant(JSONObject unit) {
        if (unit.optDouble("deposit", 0) > 0) {
            Toast.makeText(this, ui("আগের ভাড়াটিয়ার জামানত আগে ফেরত দিন; তারপর নতুন ভাড়াটিয়া বসান"), Toast.LENGTH_LONG).show(); return;
        }
        if (unit.optBoolean("occupied", true) && !"খালি".equals(unit.optString("tenant"))) {
            Toast.makeText(this, ui("আগে বর্তমান ভাড়াটিয়ার বাসা ছাড়ার তথ্য সংরক্ষণ করুন"), Toast.LENGTH_LONG).show(); return;
        }
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        EditText name = field("নতুন ভাড়াটিয়ার নাম");
        EditText moveIn = field("ওঠার তারিখ (DD-MM-YYYY)");
        moveIn.setText(new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date()));
        EditText deposit = field("নতুন ভাড়াটিয়ার জামানত (টাকা)"); deposit.setInputType(8194);
        EditText agreement = field("চুক্তির বিবরণ / মেয়াদ (ঐচ্ছিক)");
        box.addView(name); box.addView(moveIn); box.addView(deposit); box.addView(agreement);
        new AlertDialog.Builder(this).setTitle(ui("নতুন ভাড়াটিয়া বসান")).setView(box)
            .setNegativeButton(ui("বাতিল"), null).setPositiveButton(ui("সংরক্ষণ"), (d,w) -> {
                String tenant = name.getText().toString().trim();
                double amount = number(deposit.getText().toString());
                if (tenant.isEmpty() || AccountingMath.isVacantTenantName(tenant) || !Double.isFinite(amount) ||
                    amount < 0 || moveIn.getText().toString().trim().isEmpty()) {
                    Toast.makeText(this, ui("নাম, ওঠার তারিখ ও সঠিক জামানত দিন"), Toast.LENGTH_SHORT).show(); return;
                }
                try {
                    JSONArray recordsBefore = copyJsonArray(records);
                    JSONArray unitsBefore = copyJsonArray(rentalUnits);
                    unit.put("tenant", tenant); unit.put("occupied", true);
                    unit.put("move_in_date", moveIn.getText().toString().trim());
                    unit.put("deposit", amount);
                    JSONArray newDepositHistory = new JSONArray();
                    if (amount > 0) {
                        JSONObject receivedDeposit = new JSONObject();
                        receivedDeposit.put("date", new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date()));
                        receivedDeposit.put("old_amount", 0);
                        receivedDeposit.put("new_amount", amount);
                        receivedDeposit.put("change", amount);
                        receivedDeposit.put("type", "নতুন ভাড়াটিয়ার জামানত");
                        newDepositHistory.put(receivedDeposit);
                    }
                    unit.put("deposit_history", newDepositHistory);
                    unit.put("deposit_owner", "");
                    unit.put("agreement_note", agreement.getText().toString().trim());
                    if (amount > 0)
                        addDepositTransaction(tenant, unit, amount, true,
                            "নতুন ভাড়াটিয়ার জামানত গ্রহণ");
                    if (!saveRentalState(recordsBefore, unitsBefore)) {
                        Toast.makeText(this, ui("নতুন ভাড়াটিয়া ও জামানত সংরক্ষণ হয়নি; আগের তথ্য অক্ষত আছে"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    rentScreen();
                } catch (Exception e) { Toast.makeText(this, ui("নতুন ভাড়াটিয়া সংরক্ষণ হয়নি"), Toast.LENGTH_SHORT).show(); }
            }).show();
    }

    private void tenancyHistory(JSONObject unit) {
        setup("ভাড়াটিয়া ওঠা-নামার ইতিহাস");
        content.addView(text(ui("বর্তমান ভাড়াটিয়া: ") + unit.optString("tenant") +
            " | " + ui("ওঠার তারিখ: ") + unit.optString("move_in_date", ui("তথ্য নেই")), 17));
        JSONArray history = unit.optJSONArray("tenancy_history");
        if (history == null || history.length() == 0) content.addView(text("আগের ভাড়াটিয়ার ইতিহাস নেই।", 16));
        else for (int i = history.length()-1; i >= 0; i--) {
            JSONObject item = history.optJSONObject(i); if (item == null) continue;
            content.addView(text(item.optString("tenant") + " | " + ui("উঠেছেন: ") + item.optString("move_in") +
                " | " + ui("ছেড়েছেন: ") + item.optString("move_out") + " | " + ui("ভাড়া: ৳ ") +
                df.format(item.optDouble("rent", 0)) + " | " + ui("জামানত: ৳ ") +
                df.format(item.optDouble("deposit", 0)) + " | " + ui("চুক্তি: ") + item.optString("agreement"), 16));
            content.addView(text(ui("বাসা ছাড়ার সময় মোট বকেয়া (ভাড়া/চার্জ): ৳ ") +
                df.format(item.optDouble("closing_balance", 0)), 15));
            content.addView(text(ui("ভাড়া — ধার্য: ৳ ") +
                df.format(item.optDouble("closing_rent_due", 0)) + " | " + ui("জমা: ৳ ") +
                df.format(item.optDouble("closing_rent_paid", 0)) + " | " + ui("বাকি: ৳ ") +
                df.format(item.optDouble("closing_rent_balance", 0)), 14));
            content.addView(text(ui("চার্জ — ধার্য: ৳ ") +
                df.format(item.optDouble("closing_charge_due", 0)) + " | " + ui("জমা: ৳ ") +
                df.format(item.optDouble("closing_charge_paid", 0)) + " | " + ui("বাকি: ৳ ") +
                df.format(item.optDouble("closing_charge_balance", 0)), 14));
            JSONObject closingStatement = item.optJSONObject("closing_statement");
            if (closingStatement != null) {
                JSONArray monthlyRent = closingStatement.optJSONArray("rent_items");
                if (monthlyRent != null) for (int j = 0; j < monthlyRent.length(); j++) {
                    JSONObject month = monthlyRent.optJSONObject(j);
                    if (month == null) continue;
                    content.addView(text("  " + month.optString("month") +
                        " | " + ui("ভাড়া ধার্য ৳ ") + df.format(month.optDouble("due", 0)) +
                        " | " + ui("জমা ৳ ") + df.format(month.optDouble("paid", 0)) +
                        " | " + ui("বাকি ৳ ") + df.format(month.optDouble("balance", 0)), 13));
                }
                JSONArray oldCharges = closingStatement.optJSONArray("charge_items");
                if (oldCharges != null) for (int j = 0; j < oldCharges.length(); j++) {
                    JSONObject charge = oldCharges.optJSONObject(j);
                    if (charge == null) continue;
                    content.addView(text("  " + ui("চার্জ: ") + charge.optString("description") +
                        " | " + ui("ধার্য ৳ ") + df.format(charge.optDouble("due", 0)) +
                        " | " + ui("জমা ৳ ") + df.format(charge.optDouble("paid", 0)) +
                        " | " + ui("বাকি ৳ ") + df.format(charge.optDouble("balance", 0)), 13));
                }
            }
            JSONArray oldDepositHistory = item.optJSONArray("deposit_history");
            if (oldDepositHistory != null) {
                for (int j = oldDepositHistory.length() - 1; j >= 0; j--) {
                    JSONObject movement = oldDepositHistory.optJSONObject(j);
                    if (movement == null) continue;
                    String movementType = movement.optString("type", "জামানত পরিবর্তন");
                    content.addView(text("  " + ui("জামানত — ") + movement.optString("date") + " | " +
                        ui(movementType) + " | " + ui("আগের: ৳ ") +
                        df.format(movement.optDouble("old_amount", 0)) + " | " + ui("নতুন: ৳ ") +
                        df.format(movement.optDouble("new_amount", 0)), 14));
                }
            }
        }
        Button back = button("ফিরে যান"); content.addView(back);
        back.setOnClickListener(v -> rentalActions(unit));
    }

    private void refundDeposit(JSONObject unit) {
        double current = unit.optDouble("deposit", 0);
        if (current <= 0) {
            Toast.makeText(this, ui("ফেরত দেওয়ার মতো জামানত নেই"), Toast.LENGTH_SHORT).show(); return;
        }
        EditText amount = field(ui("ফেরত দেওয়ার পরিমাণ")); amount.setInputType(8194); amount.setText(df.format(current));
        new AlertDialog.Builder(this).setTitle(ui("জামানত ফেরত")).setMessage(ui("বর্তমান জামানত: ৳ ") + df.format(current))
            .setView(amount).setNegativeButton(ui("বাতিল"), null).setPositiveButton(ui("ফেরত সংরক্ষণ"), (d,w) -> {
                double refund = number(amount.getText().toString());
                if (refund <= 0 || refund > current) {
                    Toast.makeText(this, ui("ফেরতের অঙ্ক বর্তমান জামানতের বেশি হতে পারবে না"), Toast.LENGTH_LONG).show(); return;
                }
                try {
                    JSONArray recordsBefore = new JSONArray(records.toString());
                    JSONArray unitsBefore = new JSONArray(rentalUnits.toString());
                    String depositTenant = AccountingMath.depositOwner(
                        unit.optString("tenant"), unit.optString("deposit_owner"));
                    JSONArray history = unit.optJSONArray("deposit_history");
                    if (history == null) history = new JSONArray();
                    JSONObject movement = new JSONObject();
                    movement.put("date", new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date()));
                    movement.put("old_amount", current); movement.put("new_amount", current-refund);
                    movement.put("change", -refund); movement.put("type", "ফেরত");
                    history.put(movement); unit.put("deposit_history", history);
                    unit.put("deposit", current-refund);
                    if (current - refund <= 0) unit.put("deposit_owner", "");
                    if ("খালি".equals(unit.optString("tenant"))) {
                        JSONArray tenancyHistory = unit.optJSONArray("tenancy_history");
                        if (tenancyHistory != null) {
                            for (int i = tenancyHistory.length() - 1; i >= 0; i--) {
                                JSONObject archivedTenancy = tenancyHistory.optJSONObject(i);
                                if (archivedTenancy != null &&
                                    depositTenant.equals(archivedTenancy.optString("tenant"))) {
                                    archivedTenancy.put("deposit", current-refund);
                                    archivedTenancy.put("deposit_history", new JSONArray(history.toString()));
                                    break;
                                }
                            }
                        }
                    }
                    JSONObject cashOut = new JSONObject();
                    // Keep the exact tenant and property identity so the refund
                    // appears in that tenant's deposit statement and rental ledger.
                    cashOut.put("party", depositTenant);
                    cashOut.put("rent_unit", unit.optString("unit"));
                    cashOut.put("rent_building", unit.optString("building"));
                    cashOut.put("type", "জামানত ফেরত"); cashOut.put("amount", refund);
                    cashOut.put("category_id", CategoryAccounting.CATEGORY_RENT);
                    cashOut.put("deposit_transaction", true);
                    cashOut.put("deposit_transaction_key", "DEP-REFUND-" + System.currentTimeMillis());
                    cashOut.put("note", "বাড়ি " + unit.optString("building") + " / ইউনিট " +
                        unit.optString("unit") + " — " + depositTenant + " এর জামানত ফেরত (আয় নয়, দায় পরিশোধ)");
                    cashOut.put("date", new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date()));
                    cashOut.put("deposit_refund", true);
                    records.put(cashOut);
                    if (!saveRentalState(recordsBefore, unitsBefore)) {
                        Toast.makeText(this, ui("জামানত ফেরত সংরক্ষণ হয়নি; আগের হিসাব ও জামানত অক্ষত আছে"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    rentScreen();
                } catch (Exception e) { Toast.makeText(this, ui("জামানত ফেরত সংরক্ষণ হয়নি"), Toast.LENGTH_SHORT).show(); }
            }).show();
    }

    private boolean rentalUnitExists(String building, String unitName, JSONObject excludedUnit) {
        for (int i = 0; i < rentalUnits.length(); i++) {
            JSONObject existing = rentalUnits.optJSONObject(i);
            if (existing == null || existing == excludedUnit) continue;
            if (AccountingMath.sameRentalIdentity(building, unitName,
                existing.optString("building"), existing.optString("unit"))) return true;
        }
        return false;
    }

    private boolean hasRentalHistoryForUnit(String building, String unitName) {
        String expenseParty = "বাড়ি ভাড়া: " + building + " / " + unitName;
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record == null) continue;
            boolean sameUnit = unitName.equals(record.optString("rent_unit")) &&
                building.equals(record.optString("rent_building"));
            boolean unitExpense = record.optBoolean("rental_expense") &&
                expenseParty.equals(record.optString("party"));
            if (sameUnit || unitExpense) return true;
        }
        return false;
    }

    private void editRentalUnit(JSONObject unit) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        EditText building = field("বাড়ি / ভবন"); building.setText(unit.optString("building"));
        EditText floor = field("তলা"); floor.setText(unit.optString("floor"));
        EditText place = field("ফ্ল্যাট / দোকান নম্বর"); place.setText(unit.optString("unit"));
        EditText kind = field("ধরন"); kind.setText(unit.optString("kind"));
        EditText due = field("ভাড়া দেওয়ার শেষ তারিখ (১-৩১)"); due.setInputType(2); due.setText(String.valueOf(unit.optInt("due_day", 10)));
        box.addView(building); box.addView(floor); box.addView(place); box.addView(kind); box.addView(due);
        new AlertDialog.Builder(this).setTitle(ui("ইউনিটের তথ্য সম্পাদনা")).setView(box)
            .setNegativeButton(ui("বাতিল"), null).setPositiveButton(ui("আপডেট"), (d,w) -> {
                int day = (int) number(due.getText().toString());
                String newBuilding = building.getText().toString().trim();
                String newUnitName = place.getText().toString().trim();
                if (newBuilding.isEmpty() || newUnitName.isEmpty() || day < 1 || day > 31) {
                    Toast.makeText(this, ui("ভবনের নাম, ইউনিট নম্বর ও ১-৩১ তারিখ দিন"), Toast.LENGTH_SHORT).show(); return;
                }
                if (rentalUnitExists(newBuilding, newUnitName, unit)) {
                    Toast.makeText(this, ui("এই ভবনে এই ইউনিট নম্বরটি আগে থেকেই আছে"), Toast.LENGTH_LONG).show();
                    return;
                }
                boolean identityChanged = !newBuilding.equals(unit.optString("building")) ||
                    !newUnitName.equals(unit.optString("unit"));
                if (identityChanged && hasRentalHistoryForUnit(
                    unit.optString("building"), unit.optString("unit"))) {
                    Toast.makeText(this,
                        ui("এই ভবন/ইউনিটে পুরোনো ভাড়ার হিসাব আছে। হিসাবের ইতিহাস ঠিক রাখতে ভবন বা ইউনিট নম্বর বদলানো যাবে না; নতুন ইউনিট যোগ করুন।"),
                        Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    JSONArray unitsBefore = copyJsonArray(rentalUnits);
                    unit.put("building", newBuilding);
                    unit.put("floor", floor.getText().toString().trim());
                    unit.put("unit", newUnitName);
                    unit.put("kind", kind.getText().toString().trim()); unit.put("due_day", day);
                    if (!saveRentalUnits(unitsBefore)) {
                        Toast.makeText(this, ui("ইউনিটের পরিবর্তন সংরক্ষণ হয়নি; আগের তথ্য অক্ষত আছে"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    rentScreen();
                } catch (Exception e) { Toast.makeText(this, ui("আপডেট হয়নি"), Toast.LENGTH_SHORT).show(); }
            }).show();
    }

    private void collectRent(JSONObject unit) {
        String tenant = unit.optString("tenant");
        String unitName = unit.optString("unit");
        ArrayList<String> keys = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Double> balances = new ArrayList<>();
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.optJSONObject(i);
            if (r == null || !AccountingMath.sameRentalUnit(tenant, unitName,
                unit.optString("building"), r.optString("party"), r.optString("rent_unit"),
                r.optString("rent_building"))) continue;
            String key = r.optString("rent_key");
            if (key.isEmpty()) continue;
            double due = r.optDouble("amount", 0);
            ArrayList<Double> partialPayments = new ArrayList<>();
            for (int j = 0; j < records.length(); j++) {
                JSONObject p = records.optJSONObject(j);
                if (p != null && AccountingMath.matchesRentalRecordLink(key,
                    p.optString("rent_payment_key"), tenant, unitName, unit.optString("building"),
                    p.optString("party"), p.optString("rent_unit"), p.optString("rent_building")))
                    partialPayments.add(p.optDouble("amount", 0));
            }
            double[] paymentAmounts = new double[partialPayments.size()];
            for (int j = 0; j < partialPayments.size(); j++)
                paymentAmounts[j] = partialPayments.get(j);
            double balance = AccountingMath.rentalOutstanding(due, paymentAmounts);
            if (balance > 0) {
                keys.add(key); balances.add(balance);
                labels.add(key + " — " + ui("বাকি ৳ ") + df.format(balance));
            }
        }
        if (keys.isEmpty()) {
            new AlertDialog.Builder(this).setMessage(ui("এই ইউনিটে কোনো বকেয়া মাসিক ভাড়া নেই। আগে মাসের ভাড়া যোগ করুন।"))
                .setPositiveButton(ui("ঠিক আছে"), null).show();
            return;
        }
        Spinner months = new Spinner(this);
        months.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        EditText amount = field("জমার পরিমাণ (টাকা)");
        amount.setInputType(8194);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(24, 12, 24, 4);
        box.addView(months); box.addView(amount);
        new AlertDialog.Builder(this).setTitle(ui("ভাড়া জমা নিন"))
            .setView(box).setNegativeButton(ui("বাতিল"), null)
            .setPositiveButton(ui("জমা সংরক্ষণ"), (d, w) -> {
                int index = months.getSelectedItemPosition();
                double paid = number(amount.getText().toString());
                if (index < 0 || paid <= 0 || paid > balances.get(index)) {
                    Toast.makeText(this, ui("সঠিক জমার পরিমাণ দিন"), Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    JSONArray recordsBefore = new JSONArray(records.toString());
                    JSONArray unitsBefore = new JSONArray(rentalUnits.toString());
                    JSONObject payment = new JSONObject();
                    payment.put("party", tenant);
                    payment.put("type", "জমা");
                    payment.put("amount", paid);
                    payment.put("rent_payment_key", keys.get(index));
                    payment.put("category_id", CategoryAccounting.CATEGORY_RENT);
                    payment.put("rent_unit", unitName);
                    payment.put("rent_building", unit.optString("building"));
                    payment.put("note", "ভাড়া জমা — " + unit.optString("building") + " / " + unitName + " — " + keys.get(index));
                    payment.put("date", new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date()));
                    records.put(payment);
                    if (!saveRentalState(recordsBefore, unitsBefore)) {
                        Toast.makeText(this, ui("ভাড়া জমা সংরক্ষণ হয়নি; আগের হিসাব অক্ষত আছে"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(this, ui("ভাড়া জমা সংরক্ষণ হয়েছে"), Toast.LENGTH_SHORT).show();
                    rentScreen();
                } catch (Exception e) {
                    Toast.makeText(this, ui("জমা সংরক্ষণ করা যায়নি"), Toast.LENGTH_SHORT).show();
                }
            }).show();
    }

    private void exportRentPdf(JSONObject unit) {
        android.graphics.pdf.PdfDocument pdf = new android.graphics.pdf.PdfDocument();
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(11);
        String tenant = unit.optString("tenant");
        String unitName = unit.optString("unit");
        String building = unit.optString("building");
        boolean english = "en".equals(prefs.getString("language", "bn"));
        ArrayList<String> rows = new ArrayList<>();
        double totalRentDue = 0, totalRentPaid = 0;
        double totalUtilityDue = 0, totalUtilityPaid = 0, totalExpenses = 0;

        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.optJSONObject(i);
            if (r == null) continue;
            boolean sameUnit = unitName.equals(r.optString("rent_unit")) &&
                building.equals(r.optString("rent_building"));
            boolean propertyExpense = AccountingMath.sameRentalExpense(building, unitName,
                r.optString("party"), r.optString("rent_building"), r.optString("rent_unit"),
                r.optBoolean("rental_expense"));
            if (!sameUnit && !propertyExpense) continue;
            boolean tenantRecord = tenant.equals(r.optString("party"));
            String rentKey = tenantRecord ? r.optString("rent_key") : "";
            if (!rentKey.isEmpty()) {
                double due = r.optDouble("amount", 0), paid = 0;
                for (int j = 0; j < records.length(); j++) {
                    JSONObject p = records.optJSONObject(j);
                    if (p != null && rentKey.equals(p.optString("rent_payment_key")) &&
                        AccountingMath.sameRentalUnit(tenant, unitName, building,
                            p.optString("party"), p.optString("rent_unit"), p.optString("rent_building")))
                        paid += p.optDouble("amount", 0);
                }
                totalRentDue += due;
                totalRentPaid += paid;
                rows.add(english
                    ? "RENT  " + rentKey.substring(0, Math.min(7, rentKey.length())) +
                        "   Due: BDT " + df.format(due) + "   Paid: BDT " + df.format(paid) +
                        "   Balance: BDT " + df.format(Math.max(0, due - paid))
                    : "ভাড়া  " + rentKey.substring(0, Math.min(7, rentKey.length())) +
                        "   ধার্য: ৳ " + df.format(due) + "   জমা: ৳ " + df.format(paid) +
                        "   বকেয়া: ৳ " + df.format(Math.max(0, due - paid)));
            }
            if (tenantRecord && r.optBoolean("rental_charge")) {
                String chargeKey = r.optString("rental_charge_key");
                double due = r.optDouble("amount", 0), paid = 0;
                for (int j = 0; j < records.length(); j++) {
                    JSONObject p = records.optJSONObject(j);
                    if (p != null && chargeKey.equals(p.optString("rental_charge_payment_key")) &&
                        AccountingMath.sameRentalUnit(tenant, unitName, building,
                            p.optString("party"), p.optString("rent_unit"), p.optString("rent_building")))
                        paid += p.optDouble("amount", 0);
                }
                totalUtilityDue += due;
                totalUtilityPaid += paid;
                rows.add(english
                    ? "UTILITY  " + r.optString("note") + "   Due: BDT " + df.format(due) +
                        "   Paid: BDT " + df.format(paid) + "   Balance: BDT " + df.format(Math.max(0, due - paid))
                    : "ইউটিলিটি  " + r.optString("note") + "   ধার্য: ৳ " + df.format(due) +
                        "   জমা: ৳ " + df.format(paid) + "   বকেয়া: ৳ " + df.format(Math.max(0, due - paid)));
            }
            if (propertyExpense) {
                totalExpenses += r.optDouble("amount", 0);
                rows.add(english
                    ? "PROPERTY EXPENSE  " + r.optString("date") + "  " + r.optString("note") +
                        "  BDT " + df.format(r.optDouble("amount", 0))
                    : "বাড়ির খরচ  " + r.optString("date") + "  " + r.optString("note") +
                        "  ৳ " + df.format(r.optDouble("amount", 0)));
            }
        }

        int pageNumber = 0;
        int rowIndex = 0;
        boolean summaryPending = true;
        boolean hasPdfSignature = !prefs.getString("pdf_signature", "").trim().isEmpty() ||
            !prefs.getString("pdf_signature_image", "").isEmpty();
        try {
            while (rowIndex < rows.size() || summaryPending) {
                pageNumber++;
                android.graphics.pdf.PdfDocument.PageInfo info =
                    new android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, pageNumber).create();
                android.graphics.pdf.PdfDocument.Page page = pdf.startPage(info);
                android.graphics.Canvas canvas = page.getCanvas();
                int rentAccent = prefs.getInt("pdf_color", Color.rgb(25, 90, 180));
                paint.setColor(rentAccent);
                canvas.drawRect(0, 0, 595, 112, paint);
                try {
                    android.graphics.Bitmap logo = android.graphics.BitmapFactory.decodeResource(
                        getResources(), R.drawable.amar_hisab_logo);
                    if (logo != null) {
                        paint.setFilterBitmap(true);
                        canvas.drawBitmap(logo, null, new android.graphics.Rect(34, 20, 92, 78), paint);
                        logo.recycle();
                    }
                } catch (Exception ignored) { }
                paint.setColor(Color.WHITE);
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                paint.setTextSize(21);
                canvas.drawText(ui("আমার হিসাব"), 108, 46, paint);
                paint.setTextSize(13);
                canvas.drawText(ui("বাড়ি ভাড়ার স্টেটমেন্ট"), 108, 70, paint);
                paint.setTypeface(Typeface.DEFAULT);
                paint.setTextSize(9);
                canvas.drawText((english ? "RENT STATEMENT  |  Page " : "ভাড়া স্টেটমেন্ট  |  পৃষ্ঠা ") +
                    pageNumber, 420, 48, paint);
                drawDeveloperCredit(canvas, paint, 108, 94, Color.WHITE);
                paint.setColor(Color.rgb(235, 243, 250));
                canvas.drawRoundRect(30, 124, 565, 205, 10, 10, paint);
                paint.setColor(Color.BLACK);
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                paint.setTextSize(10);
                canvas.drawText((english ? "Building: " : "ভবন: ") + building +
                    "   " + (english ? "Floor: " : "তলা: ") + unit.optString("floor"), 44, 148, paint);
                canvas.drawText((english ? "Unit: " : "ইউনিট: ") + unitName + " (" +
                    unit.optString("kind") + ")   " + (english ? "Tenant: " : "ভাড়াটিয়া: ") + tenant,
                    44, 168, paint);
                paint.setTypeface(Typeface.DEFAULT);
                paint.setTextSize(9);
                canvas.drawText((english ? "Monthly rent: BDT " : "মাসিক ভাড়া: ৳ ") +
                    df.format(unit.optDouble("rent", 0)) +
                    "   " + (english ? "Deposit: BDT " : "জামানত: ৳ ") +
                    df.format(unit.optDouble("deposit", 0)) +
                    "   " + (english ? "Due day: " : "ভাড়া দেওয়ার দিন: ") +
                    unit.optInt("due_day", 10), 44, 188, paint);
                paint.setColor(rentAccent);
                canvas.drawLine(36, 218, 559, 218, paint);
                paint.setColor(Color.BLACK);
                paint.setTextSize(9);
                int y = 238;
                int rowsOnPage = 0;
                while (rowIndex < rows.size() && y <= 750) {
                    String line = rows.get(rowIndex++);
                    if (line.length() > 105) line = line.substring(0, 102) + "...";
                    canvas.drawText(line, 36, y, paint);
                    y += 17;
                    rowsOnPage++;
                }
                // Keep enough vertical space for the signature block on the summary page.
                int summaryLimit = hasPdfSignature ? 630 : 700;
                if (rowIndex >= rows.size() && summaryPending && y <= summaryLimit) {
                    y += 16;
                    paint.setTypeface(Typeface.DEFAULT_BOLD);
                    paint.setTextSize(11);
                    canvas.drawText(english ? "RENT SUMMARY" : "ভাড়া সারসংক্ষেপ", 36, y, paint);
                    y += 18;
                    canvas.drawText((english ? "Due: BDT " : "ধার্য: ৳ ") + df.format(totalRentDue) +
                        (english ? "   Paid: BDT " : "   জমা: ৳ ") + df.format(totalRentPaid) +
                        (english ? "   Balance: BDT " : "   বকেয়া: ৳ ") +
                        df.format(Math.max(0, totalRentDue-totalRentPaid)), 36, y, paint);
                    y += 18;
                    canvas.drawText((english ? "UTILITY DUE: BDT " : "ইউটিলিটি ধার্য: ৳ ") +
                        df.format(totalUtilityDue) +
                        (english ? "   PAID: BDT " : "   জমা: ৳ ") + df.format(totalUtilityPaid) +
                        (english ? "   BALANCE: BDT " : "   বকেয়া: ৳ ") +
                        df.format(Math.max(0, totalUtilityDue-totalUtilityPaid)), 36, y, paint);
                    y += 18;
                    canvas.drawText((english ? "PROPERTY EXPENSES: BDT " : "বাড়ির খরচ: ৳ ") +
                        df.format(totalExpenses), 36, y, paint);
                    paint.setTypeface(Typeface.DEFAULT);
                    summaryPending = false;
                }
                if (!summaryPending) {
                    String signature = prefs.getString("pdf_signature", "").trim();
                    paint.setColor(Color.DKGRAY);
                    paint.setTextSize(9);
                    canvas.drawLine(390, 775, 545, 775, paint);
                    drawSavedSignature(canvas, paint, 390, 716, 145, 45);
                    if (!signature.isEmpty()) {
                        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
                        canvas.drawText(signature.length() > 26 ? signature.substring(0, 26) : signature,
                            392, 766, paint);
                        paint.setTypeface(Typeface.DEFAULT);
                    }
                    canvas.drawText(english ? "Authorized signature" : "অনুমোদিত স্বাক্ষর", 390, 790, paint);
                }
                paint.setTextSize(8);
                canvas.drawText((english ? "Generated: " : "তৈরি: ") +
                    new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(new Date()) +
                    (english ? "     Page " : "     পৃষ্ঠা ") + pageNumber, 36, 815, paint);
                pdf.finishPage(page);
                if (rowIndex >= rows.size() && summaryPending) continue;
            }
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            pdf.writeTo(buffer);
            pendingPdfBytes = buffer.toByteArray();
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/pdf");
            intent.putExtra(Intent.EXTRA_TITLE, "Rent-Statement-" + unitName + ".pdf");
            startActivityForResult(intent, SAVE_RENT_PDF);
        } catch (Exception e) {
            Toast.makeText(this, ui("PDF তৈরি করা যায়নি: ") + e.getMessage(), Toast.LENGTH_LONG).show();
            pendingPdfBytes = null;
        } finally {
            pdf.close();
        }
    }

    private void tenantRentalLedger(JSONObject unit) {
        String tenant = unit.optString("tenant");
        String building = unit.optString("building");
        String unitName = unit.optString("unit");
        setup("ভাড়াটিয়ার সম্পত্তির খাতা");
        content.addView(text(ui("ভবন: ") + building + " | " + ui("ইউনিট: ") +
            unitName + " | " + ui("ভাড়াটিয়া: ") + tenant, 17));

        // Keep the tenant's property ledger separate from their ordinary personal ledger.
        // Personal entries use the personal cashbox/category and never alter rent balances.
        Button personalLedger = button("ভাড়াটিয়ার ব্যক্তিগত লেনদেনের খাতা");
        content.addView(personalLedger);
        personalLedger.setOnClickListener(v -> {
            if (tenant.trim().isEmpty() || "খালি".equals(tenant)) {
                Toast.makeText(this, ui("এই ইউনিটে বর্তমানে কোনো ভাড়াটিয়া নেই"),
                    Toast.LENGTH_SHORT).show();
                return;
            }
            partyDetails(tenant, CategoryAccounting.CATEGORY_PERSONAL);
        });

        double rentDueTotal = 0, rentPaidTotal = 0;
        double chargeDueTotal = 0, chargePaidTotal = 0;
        double propertyExpenses = 0, depositReceived = 0, depositRefunded = 0;
        int rentMonths = 0, chargeCount = 0, expenseCount = 0, depositCount = 0;
        content.addView(text("মাসিক ভাড়া", 19));
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record == null || !tenant.equals(record.optString("party")) ||
                !unitName.equals(record.optString("rent_unit")) ||
                !building.equals(record.optString("rent_building"))) continue;
            String rentKey = record.optString("rent_key");
            if (rentKey.isEmpty()) continue;
            double due = record.optDouble("amount", 0), paid = 0;
            for (int j = 0; j < records.length(); j++) {
                JSONObject payment = records.optJSONObject(j);
                if (payment != null && AccountingMath.matchesRentalRecordLink(
                    rentKey, payment.optString("rent_payment_key"), tenant, unitName, building,
                    payment.optString("party"), payment.optString("rent_unit"),
                    payment.optString("rent_building")))
                    paid += payment.optDouble("amount", 0);
            }
            rentDueTotal += due;
            rentPaidTotal += paid;
            rentMonths++;
            content.addView(text(rentKey + " | " + ui("ধার্য: ৳ ") + df.format(due) +
                " | " + ui("জমা: ৳ ") + df.format(paid) + " | " +
                ui("বাকি: ৳ ") + df.format(Math.max(0, due - paid)), 16));
        }
        if (rentMonths == 0) content.addView(text("এখনো মাসিক ভাড়া ধার্য হয়নি", 15));

        content.addView(text("ইউটিলিটি / সার্ভিস চার্জ", 19));
        for (int i = 0; i < records.length(); i++) {
            JSONObject charge = records.optJSONObject(i);
            if (charge == null || !charge.optBoolean("rental_charge") ||
                !tenant.equals(charge.optString("party")) ||
                !unitName.equals(charge.optString("rent_unit")) ||
                !building.equals(charge.optString("rent_building"))) continue;
            String key = charge.optString("rental_charge_key");
            double due = charge.optDouble("amount", 0), paid = 0;
            for (int j = 0; j < records.length(); j++) {
                JSONObject payment = records.optJSONObject(j);
                if (payment != null && key.equals(payment.optString("rental_charge_payment_key")) &&
                    tenant.equals(payment.optString("party")) &&
                    unitName.equals(payment.optString("rent_unit")) &&
                    building.equals(payment.optString("rent_building")))
                    paid += payment.optDouble("amount", 0);
            }
            chargeDueTotal += due;
            chargePaidTotal += paid;
            chargeCount++;
            content.addView(text(charge.optString("date") + " | " +
                charge.optString("note") + " | " + ui("ধার্য: ৳ ") + df.format(due) +
                " | " + ui("জমা: ৳ ") + df.format(paid) + " | " +
                ui("বাকি: ৳ ") + df.format(Math.max(0, due - paid)), 15));
        }
        if (chargeCount == 0) content.addView(text("কোনো ইউটিলিটি বিল নেই", 15));

        content.addView(text("সম্পত্তি / মেরামত খরচ (ভাড়ার বকেয়া নয়)", 19));
        for (int i = 0; i < records.length(); i++) {
            JSONObject expense = records.optJSONObject(i);
            if (expense == null || !AccountingMath.sameRentalExpense(
                building, unitName, expense.optString("party"),
                expense.optString("rent_building"), expense.optString("rent_unit"),
                expense.optBoolean("rental_expense"))) continue;
            double amount = expense.optDouble("amount", 0);
            propertyExpenses += amount;
            expenseCount++;
            content.addView(text(expense.optString("date") + " | " +
                expense.optString("note") + " | " + ui("খরচ: ৳ ") + df.format(amount), 15));
        }
        if (expenseCount == 0) content.addView(text("এখনো কোনো মেরামত / সম্পত্তি খরচ নেই", 15));

        content.addView(text("ফেরতযোগ্য জামানতের আলাদা হিসাব", 19));
        for (int i = 0; i < records.length(); i++) {
            JSONObject movement = records.optJSONObject(i);
            if (movement == null ||
                !unitName.equals(movement.optString("rent_unit")) ||
                !building.equals(movement.optString("rent_building")) ||
                !tenant.equals(movement.optString("party")) ||
                (!movement.optBoolean("deposit_transaction") &&
                    !movement.optBoolean("deposit_refund"))) continue;
            double amount = movement.optDouble("amount", 0);
            if ("জামানত জমা".equals(movement.optString("type")))
                depositReceived += amount;
            else if ("জামানত ফেরত".equals(movement.optString("type")) ||
                movement.optBoolean("deposit_refund"))
                depositRefunded += amount;
            depositCount++;
            content.addView(text(movement.optString("date") + " | " +
                movement.optString("note") + " | " +
                ui(movement.optString("type")) + " | ৳ " + df.format(amount), 15));
        }
        if (depositCount == 0) content.addView(text("জামানতের কোনো লেনদেন নেই", 15));

        double rentBalance = Math.max(0, rentDueTotal - rentPaidTotal);
        double chargeBalance = Math.max(0, chargeDueTotal - chargePaidTotal);
        content.addView(text(ui("মোট ভাড়া ধার্য: ৳ ") + df.format(rentDueTotal), 17));
        content.addView(text(ui("মোট ভাড়া জমা: ৳ ") + df.format(rentPaidTotal), 17));
        content.addView(text(ui("ভাড়া বকেয়া: ৳ ") + df.format(rentBalance), 18));
        content.addView(text(ui("ইউটিলিটি বকেয়া: ৳ ") + df.format(chargeBalance), 17));
        content.addView(text(ui("মোট ভাড়া ও বিল বকেয়া: ৳ ") +
            df.format(rentBalance + chargeBalance), 20));
        content.addView(text(ui("সম্পত্তির মোট খরচ: ৳ ") + df.format(propertyExpenses), 17));
        content.addView(text(ui("জামানত গ্রহণ: ৳ ") + df.format(depositReceived) +
            " | " + ui("ফেরত: ৳ ") + df.format(depositRefunded), 16));
        content.addView(text(ui("বর্তমান ফেরতযোগ্য জামানত: ৳ ") +
            df.format(unit.optDouble("deposit", 0)), 17));

        Button pdf = button("ভাড়ার স্টেটমেন্ট PDF সংরক্ষণ");
        content.addView(pdf);
        pdf.setOnClickListener(v -> exportRentPdf(unit));
        Button back = button("ভাড়া ব্যবস্থাপনায় ফিরুন");
        content.addView(back);
        back.setOnClickListener(v -> rentScreen());
    }

    private void rentReport(JSONObject unit) {
        String tenant = unit.optString("tenant");
        String unitName = unit.optString("unit");
        String building = unit.optString("building");
        setup(ui("ভাড়া রিপোর্ট — ") + unitName);
        double totalDue = 0, totalPaid = 0;
        int months = 0;
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.optJSONObject(i);
            if (r == null || !tenant.equals(r.optString("party")) ||
                !unitName.equals(r.optString("rent_unit")) ||
                !building.equals(r.optString("rent_building"))) continue;
            String key = r.optString("rent_key");
            if (key.isEmpty()) continue;
            double due = r.optDouble("amount", 0), paid = 0;
            for (int j = 0; j < records.length(); j++) {
                JSONObject p = records.optJSONObject(j);
                if (p != null && AccountingMath.matchesRentalRecordLink(key,
                    p.optString("rent_payment_key"), tenant, unitName, building,
                    p.optString("party"), p.optString("rent_unit"), p.optString("rent_building")))
                    paid += p.optDouble("amount", 0);
            }
            totalDue += due; totalPaid += paid; months++;
            content.addView(text(key + " | " + ui("ভাড়া ৳ ") + df.format(due) +
                " | " + ui("জমা ৳ ") + df.format(paid) + " | " + ui("বাকি ৳ ") +
                df.format(Math.max(0, due - paid)), 16));
        }
        double utilityDue = 0, utilityPaid = 0, unitExpenses = 0;
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.optJSONObject(i);
            if (r == null) continue;
            boolean sameUnit = unitName.equals(r.optString("rent_unit")) &&
                building.equals(r.optString("rent_building"));
            boolean propertyExpense = AccountingMath.sameRentalExpense(building, unitName,
                r.optString("party"), r.optString("rent_building"), r.optString("rent_unit"),
                r.optBoolean("rental_expense"));
            if (!sameUnit && !propertyExpense) continue;
            if (tenant.equals(r.optString("party")) && r.optBoolean("rental_charge"))
                utilityDue += r.optDouble("amount", 0);
            String chargePaymentKey = r.optString("rental_charge_payment_key");
            if (tenant.equals(r.optString("party")) && !chargePaymentKey.isEmpty())
                utilityPaid += r.optDouble("amount", 0);
            if (propertyExpense) {
                unitExpenses += r.optDouble("amount", 0);
            }
        }
        content.addView(text(ui("মোট মাস: ") + months, 17));
        content.addView(text(ui("মোট ভাড়া: ৳ ") + df.format(totalDue), 19));
        content.addView(text(ui("মোট ভাড়া জমা: ৳ ") + df.format(totalPaid), 19));
        content.addView(text(ui("মোট ভাড়া বকেয়া: ৳ ") + df.format(Math.max(0, totalDue - totalPaid)), 21));
        content.addView(text(ui("ইউটিলিটি / সার্ভিস চার্জ: ৳ ") + df.format(utilityDue), 17));
        content.addView(text(ui("ইউটিলিটি আদায়: ৳ ") + df.format(utilityPaid), 17));
        content.addView(text(ui("ইউটিলিটি বকেয়া: ৳ ") + df.format(Math.max(0, utilityDue - utilityPaid)), 17));
        content.addView(text(ui("ইউনিটের মেরামত / অন্যান্য খরচ: ৳ ") + df.format(unitExpenses), 17));
        Button pdfButton = button(ui("ভাড়ার স্টেটমেন্ট PDF সংরক্ষণ"));
        content.addView(pdfButton); pdfButton.setOnClickListener(v -> exportRentPdf(unit));
        Button back = button("ভাড়া ব্যবস্থাপনায় ফিরুন");
        content.addView(back); back.setOnClickListener(v -> rentScreen());
    }

    private double rentForMonth(JSONObject unit, String month) {
        JSONArray schedule = unit.optJSONArray("rent_schedule");
        if (schedule == null) return unit.optDouble("rent", 0);
        String[] effectiveMonths = new String[schedule.length()];
        double[] amounts = new double[schedule.length()];
        for (int i = 0; i < schedule.length(); i++) {
            JSONObject item = schedule.optJSONObject(i);
            effectiveMonths[i] = item == null ? "" : item.optString("from", "");
            amounts[i] = item == null ? 0 : item.optDouble("amount", 0);
        }
        return AccountingMath.rentAtMonth(unit.optDouble("rent", 0), month,
            effectiveMonths, amounts);
    }

    private boolean postMonthlyRentInternal(JSONObject unit, String month) {
        String tenant = unit.optString("tenant");
        if (tenant.trim().isEmpty() || "খালি".equals(tenant) ||
            !unit.optBoolean("occupied", true) || unit.optDouble("rent", 0) <= 0) return false;
        String building = unit.optString("building");
        String unitName = unit.optString("unit");
        String key = month + "|" + building + "|" + unitName + "|" + tenant;
        String legacyKey = month + "|" + unitName + "|" + tenant;
        for (int i = 0; i < records.length(); i++) {
            JSONObject existing = records.optJSONObject(i);
            if (existing != null && AccountingMath.sameMonthlyRentEntry(
                existing.optString("rent_key"), key, legacyKey,
                existing.optString("rent_building"), building)) return false;
        }
        try {
            JSONObject r = new JSONObject();
            r.put("party", tenant); r.put("type", "পাওনা");
            r.put("amount", rentForMonth(unit, month));
            r.put("note", "মাসিক ভাড়া — " + building + " / " + unitName + " — " + month);
            r.put("date", new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date()));
            r.put("rent_key", key); r.put("rent_unit", unitName); r.put("rent_building", building);
            r.put("category_id", CategoryAccounting.CATEGORY_RENT);
            records.put(r);
            return true;
        } catch (Exception e) { return false; }
    }

    private JSONArray copyJsonArray(JSONArray source) {
        try {
            return new JSONArray(source.toString());
        } catch (Exception e) {
            return source;
        }
    }

    private void autoPostCurrentMonthRent() {
        String currentMonth = new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date());
        JSONArray recordsBefore = copyJsonArray(records);
        JSONArray unitsBefore = copyJsonArray(rentalUnits);
        boolean changed = false;
        for (int i = 0; i < rentalUnits.length(); i++) {
            JSONObject unit = rentalUnits.optJSONObject(i);
            if (unit == null || !unit.optBoolean("occupied", true) ||
                unit.optString("tenant").trim().isEmpty() ||
                "খালি".equals(unit.optString("tenant"))) continue;

            // Backfill only from a known move-in date. Older records without a
            // move-in date start at the current month to avoid inventing arrears.
            String moveIn = unit.optString("move_in_date", "").trim();
            String firstMonth = currentMonth;
            if (!moveIn.isEmpty()) {
                try {
                    SimpleDateFormat input = new SimpleDateFormat("dd-MM-yyyy", Locale.US);
                    input.setLenient(false);
                    Date parsed = input.parse(moveIn);
                    if (parsed != null) {
                        String moveInMonth = new SimpleDateFormat("yyyy-MM", Locale.US).format(parsed);
                        if (moveInMonth.compareTo(currentMonth) > 0) continue;
                        firstMonth = moveInMonth;
                    }
                } catch (Exception ignored) {
                    // Invalid legacy dates are not used to create historical charges.
                }
            }

            try {
                SimpleDateFormat monthFormat = new SimpleDateFormat("yyyy-MM", Locale.US);
                monthFormat.setLenient(false);
                Calendar cursor = Calendar.getInstance(Locale.US);
                cursor.setTime(monthFormat.parse(firstMonth));
                Calendar end = Calendar.getInstance(Locale.US);
                end.setTime(monthFormat.parse(currentMonth));
                int monthsChecked = 0;
                while (!cursor.after(end) && monthsChecked < 120) {
                    String month = monthFormat.format(cursor.getTime());
                    if (postMonthlyRentInternal(unit, month)) changed = true;
                    cursor.add(Calendar.MONTH, 1);
                    monthsChecked++;
                }
            } catch (Exception ignored) {
                // Keep startup safe if a legacy date cannot be interpreted.
            }
        }
        if (changed && !saveRentalState(recordsBefore, unitsBefore)) {
            Toast.makeText(this, ui("বকেয়া ভাড়া সংরক্ষণ হয়নি; আগের হিসাব অক্ষত আছে"), Toast.LENGTH_LONG).show();
        }
    }

    private void postMonthlyRent(JSONObject unit) {
        String month = new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date());
        JSONArray recordsBefore = copyJsonArray(records);
        JSONArray unitsBefore = copyJsonArray(rentalUnits);
        if (postMonthlyRentInternal(unit, month)) {
            if (!saveRentalState(recordsBefore, unitsBefore)) {
                Toast.makeText(this, ui("ভাড়া সংরক্ষণ হয়নি; আগের হিসাব অক্ষত আছে"), Toast.LENGTH_LONG).show();
                rentScreen();
                return;
            }
            Toast.makeText(this, ui("এই মাসের ভাড়া যোগ হয়েছে"), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, ui("ভাড়া যোগ হয়নি: আগে যোগ হয়েছে বা ইউনিট খালি"), Toast.LENGTH_SHORT).show();
        }
        rentScreen();
    }


    private void checkForUpdates(boolean showNoUpdate) {
        if (showNoUpdate) Toast.makeText(this, ui("আপডেট খোঁজা হচ্ছে..."), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            java.net.HttpURLConnection c = null;
            try {
                c = (java.net.HttpURLConnection) new java.net.URL(
                    "https://raw.githubusercontent.com/mahabuburrahaman402-hue/Amar-hisab/main/latest.json").openConnection();
                c.setConnectTimeout(12000);
                c.setReadTimeout(12000);
                c.setRequestProperty("Cache-Control", "no-cache");
                if (c.getResponseCode() != 200) throw new java.io.IOException("Update metadata unavailable");
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream in = c.getInputStream()) {
                    byte[] buffer = new byte[4096]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (out.size() + n > 256 * 1024) throw new java.io.IOException("Metadata too large");
                        out.write(buffer, 0, n);
                    }
                }
                org.json.JSONObject j = new org.json.JSONObject(out.toString("UTF-8"));
                long latest = j.optLong("versionCode", 0);
                String name = j.optString("versionName", "");
                String apk = j.optString("apkUrl", "");
                String hash = j.optString("sha256", "").toLowerCase(java.util.Locale.US);
                String notes = j.optString("releaseNotes", "");
                android.net.Uri u = android.net.Uri.parse(apk);
                String path = u.getPath() == null ? "" : u.getPath();
                boolean trusted = "https".equalsIgnoreCase(u.getScheme()) &&
                    "github.com".equalsIgnoreCase(u.getHost()) &&
                    path.startsWith("/mahabuburrahaman402-hue/Amar-hisab/releases/download/");
                if (latest <= 0 || !trusted || !hash.matches("[0-9a-f]{64}") || name.trim().isEmpty())
                    throw new java.io.IOException("Invalid update metadata");
                long current = 0;
                try {
                    android.content.pm.PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
                    current = android.os.Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
                } catch (Exception ignored) {}
                final long installed = current;
                if (latest <= current) {
                    if (showNoUpdate) runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle(ui("আপডেট")).setMessage(ui("আপনার অ্যাপটি সর্বশেষ সংস্করণে আছে।"))
                        .setPositiveButton(ui("ঠিক আছে"), null).show());
                } else {
                    runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle(ui("নতুন আপডেট পাওয়া গেছে"))
                        .setMessage(ui("নতুন সংস্করণ: ") + name + "\n" + ui("বর্তমান সংস্করণ: ") + installed +
                            (notes.isEmpty() ? "" : "\n\n" + notes) +
                            "\n\n" + ui("আপডেট করুন চাপলে APK এই অ্যাপের ভেতরে ডাউনলোড ও যাচাই হবে। ইনস্টলের আগে Android-এর অনুমতি ও নিশ্চিতকরণ লাগবে।"))
                        .setNegativeButton(ui("পরে"), null)
                        .setPositiveButton(ui("আপডেট করুন"), (d, w) -> downloadAndInstallUpdate(apk, hash))
                        .show());
                }
            } catch (Exception e) {
                if (showNoUpdate) runOnUiThread(() -> Toast.makeText(this,
                    ui("আপডেট খোঁজা যায়নি। ইন্টারনেট সংযোগ পরীক্ষা করুন।"), Toast.LENGTH_LONG).show());
            } finally { if (c != null) c.disconnect(); }
        }).start();
    }

    private void downloadAndInstallUpdate(String apkUrl, String expectedHash) {
        if (updateDownloadInProgress) return;
        updateDownloadInProgress = true;
        ProgressDialog dialog = new ProgressDialog(this);
        dialog.setTitle(ui("আপডেট ডাউনলোড হচ্ছে"));
        dialog.setMessage(ui("অনুগ্রহ করে অপেক্ষা করুন..."));
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        dialog.setIndeterminate(true);
        dialog.setCancelable(false);
        dialog.show();
        new Thread(() -> {
            java.net.HttpURLConnection c = null;
            java.io.File part = null;
            try {
                c = (java.net.HttpURLConnection) new java.net.URL(apkUrl).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "AmarHisab-Android-Updater");
                int code = c.getResponseCode();
                String host = c.getURL().getHost().toLowerCase(java.util.Locale.US);
                boolean safeHost = host.equals("github.com") || host.endsWith(".githubusercontent.com");
                if (code != 200 || !"https".equalsIgnoreCase(c.getURL().getProtocol()) || !safeHost)
                    throw new java.io.IOException("Untrusted APK download host");
                long length = c.getContentLengthLong();
                if (length > MAX_UPDATE_APK_BYTES) throw new java.io.IOException("APK too large");
                java.io.File dir = new java.io.File(getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("Cannot create cache");
                part = new java.io.File(dir, "update.apk.part");
                java.io.File apk = new java.io.File(dir, "update.apk");
                long total = 0;
                try (java.io.InputStream in = c.getInputStream();
                     java.io.FileOutputStream out = new java.io.FileOutputStream(part)) {
                    byte[] buffer = new byte[16384]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        total += n;
                        if (total > MAX_UPDATE_APK_BYTES) throw new java.io.IOException("APK too large");
                        out.write(buffer, 0, n);
                        if (length > 0) {
                            int pct = (int)Math.min(100, total * 100 / length);
                            runOnUiThread(() -> { dialog.setIndeterminate(false); dialog.setProgress(pct); });
                        }
                    }
                    out.getFD().sync();
                }
                if (total == 0 || (length > 0 && total != length)) throw new java.io.IOException("Incomplete APK");
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                try (java.io.InputStream in = new java.io.FileInputStream(part)) {
                    byte[] buffer = new byte[16384]; int n;
                    while ((n = in.read(buffer)) != -1) md.update(buffer, 0, n);
                }
                StringBuilder actual = new StringBuilder();
                for (byte b : md.digest()) actual.append(String.format(java.util.Locale.US, "%02x", b & 255));
                if (!actual.toString().equals(expectedHash)) throw new java.io.IOException("APK SHA-256 mismatch");
                if (apk.exists() && !apk.delete()) throw new java.io.IOException("Cannot replace cached APK");
                if (!part.renameTo(apk)) throw new java.io.IOException("Cannot finish APK file");
                runOnUiThread(() -> {
                    dialog.dismiss(); updateDownloadInProgress = false;
                    pendingUpdateFile = apk;
                    Toast.makeText(this, ui("ডাউনলোড ও যাচাই সম্পন্ন হয়েছে"), Toast.LENGTH_LONG).show();
                    continuePendingUpdateInstall();
                });
            } catch (Exception e) {
                if (part != null && part.exists()) part.delete();
                runOnUiThread(() -> {
                    dialog.dismiss(); updateDownloadInProgress = false;
                    new AlertDialog.Builder(this).setTitle(ui("আপডেট হয়নি"))
                        .setMessage(ui("ডাউনলোড বা যাচাই ব্যর্থ: ") + e.getMessage())
                        .setPositiveButton(ui("ঠিক আছে"), null).show();
                });
            } finally { if (c != null) c.disconnect(); }
        }).start();
    }

    private void continuePendingUpdateInstall() {
        if (pendingUpdateFile == null || !pendingUpdateFile.isFile()) return;
        if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:" + getPackageName())));
                Toast.makeText(this, ui("এই অ্যাপ থেকে ইনস্টলের অনুমতি চালু করে ফিরে আসুন"), Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, ui("ইনস্টল অনুমতির সেটিংস খোলা যায়নি"), Toast.LENGTH_LONG).show();
            }
            return;
        }
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", pendingUpdateFile);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
            pendingUpdateFile = null;
        } catch (Exception e) {
            Toast.makeText(this, ui("ইনস্টল শুরু করা যায়নি: ") + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
