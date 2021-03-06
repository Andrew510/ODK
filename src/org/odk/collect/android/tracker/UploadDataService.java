package org.odk.collect.android.tracker;

import java.io.File;
import java.util.Date;

import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.util.Zip4jConstants;

import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicHttpResponse;

import org.odk.collect.android.R;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.telephony.TelephonyManager;
import android.util.Log;

public class UploadDataService extends Service {
	private static final String TAG = "UploadDataService";
	private static final String SERVER_URI = "http://184.169.166.200:61245/api/traces";
	private static final String FILE_NAME = "tracker.txt";
	
	private Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
	private PowerManager.WakeLock wakeLock;
	private WifiManager.WifiLock wifiLock;

	@Override
	public void onCreate(){
		Log.d(TAG,"UploadDataService creates");
		Utils.log(new Date(), TAG, "UploadDataService creates");
		super.onCreate();

		PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
		wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UploadDataService");  
		wakeLock.acquire();

		WifiManager wm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
		wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "UploadDataService");
		wifiLock.acquire();
	}

	@Override
	public int onStartCommand(Intent in, int flags, int startId){
		switch (Utils.networkState(this)){
		case Utils.NO_CONNECTION:
			Log.d(TAG,"UploadDataService NO_CONNECTION");
			Utils.log(new Date(), TAG, "UploadDataService NO_CONNECTION");
			Utils.retryLater(this,SetTimeTrigger.class, 3600);
			sendWiFiNotification();
			sendDataPlanNotification();
			stopSelf();
			break;
		case Utils.WAIT_FOR_WIFI:
			Log.d(TAG, "UploadDataService WAIT_FOR_WIFI");
			Utils.log(new Date(), TAG, "UploadDataService WAIT_FOR_WIFI");
			Utils.retryLater(this,SetTimeTrigger.class, 10);
			break;
		case Utils.HAS_CONNECTION:
			Log.d(TAG, "UploadDataService HAS_CONNECTION");
			Utils.log(new Date(), TAG, "UploadDataService HAS_CONNECTION");
			new Upload().execute();
			stopSelf();
			break;
		default:
			stopSelf();
			break;
		}
		return START_STICKY;
	}

	private class Upload extends AsyncTask<Void, Void, Void> {

		@Override
		protected Void doInBackground(Void... arg0) {
			Context context = getApplicationContext();
			SharedPreferences mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
			String user = mSharedPreferences.getString("username","user");
			TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
			String phoneID = telephonyManager.getDeviceId();
			Log.d(TAG, "User/PhoneId: " + user + "/" + phoneID);
			Utils.log(new Date(), TAG, "User/PhoneId: " + user + "/" + phoneID);
			String response = post(user, phoneID);
			if (response.equals("FAILURE")) {
				Log.d(TAG, "Upload fails. Retry....");
				Utils.log(new Date(), TAG, "Upload fails. Retry....");
				Utils.retryLater(context,SetTimeTrigger.class, 3600);
			}
			else if (response.equals("SUCCESS")) {
				File logFile = new File(Environment.getExternalStorageDirectory(), FILE_NAME);
				logFile.delete();
				Log.d(TAG, "Upload success");
				Utils.log(new Date(), TAG, "Upload success");
				Intent respIntent = new Intent(context, UploadReceiver.class);
				respIntent.putExtra("RESP", response);
				context.sendBroadcast(respIntent);
			}
			return null;
		}	
	}
	
	private String post(String user, String phoneID) {
		try {
			HttpClient httpClient = new DefaultHttpClient();
			HttpPost httpPost = new HttpPost(SERVER_URI);
			MultipartEntity multEntity = new MultipartEntity();
			BasicHttpResponse httpResponse = null;
			File logFile = new File(Environment.getExternalStorageDirectory(), FILE_NAME);
			if (logFile.exists()) {
				double fileLength = logFile.length()/1024;
				Log.d(TAG, "File size: " + fileLength);
				Utils.log(new Date(), TAG, "File size: " + fileLength);
				
				String zipPath = zipFile(logFile);
				Log.d(TAG, "Zip File:" + zipPath);
				Utils.log(new Date(), TAG, "Zip File:" + zipPath);
				
				File zipFile = new File(zipPath);
				double zipLength = zipFile.length()/1024;
				Log.d(TAG, "Zip size: " + zipLength);
				Utils.log(new Date(), TAG, "Zip size: " + zipLength);
				
				multEntity.addPart("user", new StringBody(user));
				multEntity.addPart("phone_id", new StringBody(phoneID));
				multEntity.addPart("file", new FileBody(zipFile, "application/zip"));
				httpPost.setEntity(multEntity);
				Log.d(TAG, "Executing httpPost");
				Utils.log(new Date(), TAG, "Executing httpPost");
				httpResponse = (BasicHttpResponse) httpClient.execute(httpPost);
				Log.d(TAG, httpResponse.getStatusLine().toString()+", "+
						httpResponse.getProtocolVersion().toString());
				Utils.log(new Date(), TAG, httpResponse.getStatusLine().toString()+", "+
						httpResponse.getProtocolVersion().toString());
				boolean isDeleted = zipFile.delete();
				Log.d(TAG, "Zipfile is deleted:" + isDeleted);
				Utils.log(new Date(), TAG, "Zipfile is deleted:" + isDeleted);
				if (httpResponse.getStatusLine().getStatusCode() == 200) {
					return "SUCCESS";
				}
				else {
					return "FAILURE";
				}
			}
		} catch (Exception e) {
			Utils.log(new Date(), TAG, e.getMessage());
			e.printStackTrace();
			return "FAILURE";
		}
		return "UNKNOWN";
	}

	private String zipFile(File file) {
		String path = Environment.getExternalStorageDirectory().getAbsolutePath() + "/tracker_" + System.currentTimeMillis() + ".zip";
		try {
			ZipFile zipFile = new ZipFile(path);
			ZipParameters parameters = new ZipParameters();
			parameters.setCompressionMethod(Zip4jConstants.COMP_DEFLATE);
			parameters.setCompressionLevel(Zip4jConstants.DEFLATE_LEVEL_NORMAL); 
			parameters.setEncryptFiles(true);
			parameters.setEncryptionMethod(Zip4jConstants.ENC_METHOD_STANDARD);
			parameters.setAesKeyStrength(Zip4jConstants.AES_STRENGTH_128);
			parameters.setPassword("ucberkeley");
			zipFile.addFile(file, parameters);
			Log.d(TAG, "Is Encrypted: " + zipFile.isEncrypted());
			Utils.log(new Date(), TAG, "Is Encrypted: " + zipFile.isEncrypted());
		} catch (Exception e) {
			Utils.log(new Date(), TAG, e.getMessage());
			e.printStackTrace();
		}
		return path;
	}
	
	private void sendWiFiNotification() {
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(getApplicationContext());

        builder.setContentTitle("ODK Tracker")
               .setContentText("Please turn on Wifi")
               .setSmallIcon(R.drawable.study_logo)
               .setContentIntent(getContentIntent("WIFI"))
               .setSound(sound);

        NotificationManager notifyManager = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        notifyManager.notify(1, builder.build());
    }
	
	private void sendDataPlanNotification() {
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(getApplicationContext());

        builder.setContentTitle("ODK Tracker")
               .setContentText("Please turn on data plan")
               .setSmallIcon(R.drawable.study_logo)
               .setContentIntent(getContentIntent("DATA_PLAN"))
               .setSound(sound);

        NotificationManager notifyManager = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        notifyManager.notify(0, builder.build());
    }
	
    private PendingIntent getContentIntent(String networkType) {
    	Intent intent = null;
    	if (networkType.equals("WIFI")) {
    		intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
    	}
    	else {
    		intent = new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS);
    	}
        return PendingIntent.getActivity(getApplicationContext(), 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

	@Override
	public IBinder onBind(Intent arg0) {
		return null;
	}

	@Override
	public void onDestroy(){
		releaseLocks();
		super.onDestroy();
	}
	
	private void releaseLocks(){
		wakeLock.release();
		wifiLock.release();
	}
}