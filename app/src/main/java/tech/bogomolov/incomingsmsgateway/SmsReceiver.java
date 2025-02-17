package tech.bogomolov.incomingsmsgateway;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.telephony.SmsMessage;

import androidx.core.content.ContextCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

import org.apache.commons.text.StringEscapeUtils;

public class SmsReceiver extends BroadcastReceiver {

    private Context context;

    @Override
    public void onReceive(Context context, Intent intent) {
        this.context = context;

        Bundle bundle = intent.getExtras();
        if (bundle == null) {
            return;
        }

        Object[] pdus = (Object[]) bundle.get("pdus");
        if (pdus == null || pdus.length == 0) {
            return;
        }

        StringBuilder content = new StringBuilder();
        final SmsMessage[] messages = new SmsMessage[pdus.length];
        for (int i = 0; i < pdus.length; i++) {
            messages[i] = SmsMessage.createFromPdu((byte[]) pdus[i]);
            content.append(messages[i].getDisplayMessageBody());
        }

        ArrayList<ForwardingConfig> configs = ForwardingConfig.getAll(context);
        String asterisk = context.getString(R.string.asterisk);

        String senderPhoneNumber = messages[0].getOriginatingAddress();
        String senderName = null;
        // Attempt to resolve sender name if `READ_CONTACTS` permission has been granted
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            try {
                senderName = getContactNameByPhoneNumber(senderPhoneNumber, context);
            }
            catch (java.lang.SecurityException se) {
                // READ_CONTACTS hasn't been granted, but we shouldn't've gotten here...
                assert true;
            }
        }

        if (senderName == null) {
            senderName = senderPhoneNumber;     // fallback to phone number
        }

        ForwardingConfig matchedConfig = null;
        for (ForwardingConfig config : configs) {
            if (senderPhoneNumber.equals(config.getSender()) || config.getSender().equals(asterisk)) {
                matchedConfig = config;
                break;
            }
        }

        if (matchedConfig == null) {
            return;
        }

        String messageContent = matchedConfig.getTemplate()
                .replaceAll("%from%", senderPhoneNumber)
                .replaceAll("%sentStamp%", String.valueOf(messages[0].getTimestampMillis()))
                .replaceAll("%receivedStamp%", String.valueOf(System.currentTimeMillis()))
                .replaceAll("%sim%", this.detectSim(bundle))
                // TODO since both `fromName` and `text` can contain arbitrary characters,
                // the latter replacement can overwrite text in the former
                //
                // Solution - don't name contacts with the substring `%text%` in them
                .replaceAll("%fromName%",
                        Matcher.quoteReplacement(StringEscapeUtils.escapeJson(senderName.toString())))
                .replaceAll("%text%",
                        Matcher.quoteReplacement(StringEscapeUtils.escapeJson(content.toString())));
        this.callWebHook(
                matchedConfig.getUrl(),
                messageContent,
                matchedConfig.getHeaders(),
                matchedConfig.getIgnoreSsl()
        );
    }

    protected void callWebHook(String url, String message, String headers, boolean ignoreSsl) {

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        Data data = new Data.Builder()
                .putString(WebHookWorkRequest.DATA_URL, url)
                .putString(WebHookWorkRequest.DATA_TEXT, message)
                .putString(WebHookWorkRequest.DATA_HEADERS, headers)
                .putBoolean(WebHookWorkRequest.DATA_IGNORE_SSL, ignoreSsl)
                .build();

        WorkRequest webhookWorkRequest =
                new OneTimeWorkRequest.Builder(WebHookWorkRequest.class)
                        .setConstraints(constraints)
                        .setBackoffCriteria(
                                BackoffPolicy.EXPONENTIAL,
                                OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
                                TimeUnit.MILLISECONDS
                        )
                        .setInputData(data)
                        .build();

        WorkManager
                .getInstance(this.context)
                .enqueue(webhookWorkRequest);

    }

    private String detectSim(Bundle bundle) {
        int slotId = -1;
        Set<String> keySet = bundle.keySet();
        for (String key : keySet) {
            switch (key) {
                case "phone":
                    slotId = bundle.getInt("phone", -1);
                    break;
                case "slot":
                    slotId = bundle.getInt("slot", -1);
                    break;
                case "simId":
                    slotId = bundle.getInt("simId", -1);
                    break;
                case "simSlot":
                    slotId = bundle.getInt("simSlot", -1);
                    break;
                case "slot_id":
                    slotId = bundle.getInt("slot_id", -1);
                    break;
                case "simnum":
                    slotId = bundle.getInt("simnum", -1);
                    break;
                case "slotId":
                    slotId = bundle.getInt("slotId", -1);
                    break;
                case "slotIdx":
                    slotId = bundle.getInt("slotIdx", -1);
                    break;
                case "android.telephony.extra.SLOT_INDEX":
                    slotId = bundle.getInt("android.telephony.extra.SLOT_INDEX", -1);
                    break;
                default:
                    if (key.toLowerCase().contains("slot") | key.toLowerCase().contains("sim")) {
                        String value = bundle.getString(key, "-1");
                        if (value.equals("0") | value.equals("1") | value.equals("2")) {
                            slotId = bundle.getInt(key, -1);
                        }
                    }
            }

            if (slotId != -1) {
                break;
            }
        }

        if (slotId == 0) {
            return "sim1";
        } else if (slotId == 1) {
            return "sim2";
        } else {
            return "undetected";
        }
    }

    private String getContactNameByPhoneNumber(String phoneNumber, Context context) {
        ContentResolver contentResolver = context.getContentResolver();
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
        String[] projection = {ContactsContract.PhoneLookup.DISPLAY_NAME};

        String contactName = null;
        Cursor cursor = contentResolver.query(uri, projection, null, null, null);

        if (cursor != null && cursor.moveToFirst()) {
            int nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME);
            contactName = cursor.getString(nameIndex);
            cursor.close();
        }

        return contactName;
    }
}
