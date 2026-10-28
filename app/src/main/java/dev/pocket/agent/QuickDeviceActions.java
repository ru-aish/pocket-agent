package dev.pocket.agent;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.StatFs;
import android.provider.Settings;
import dev.pocket.control.ControlException;
import org.json.JSONObject;
import static dev.pocket.control.ControlException.Code.*;

/** Public Android APIs for everyday queries and controls; no UI automation. */
final class QuickDeviceActions {
    static JSONObject info(Context context) throws Exception {
        Intent battery=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level=battery==null?-1:battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=battery==null?-1:battery.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
        int status=battery==null?-1:battery.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
        ConnectivityManager connectivity=context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities net=connectivity==null?null:connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        AudioManager audio=context.getSystemService(AudioManager.class);
        int brightness=Settings.System.getInt(context.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,-1);
        StatFs disk=new StatFs(context.getFilesDir().getAbsolutePath());
        return Json.obj("battery_percent",level>=0&&scale>0?Math.round(100f*level/scale):null,
            "charging",status==BatteryManager.BATTERY_STATUS_CHARGING||status==BatteryManager.BATTERY_STATUS_FULL,
            "internet_validated",net!=null&&net.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            "network",net==null?"offline":net.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":net.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"mobile data":net.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?"Ethernet":"other",
            "media_volume_percent",audio==null?null:Math.round(100f*audio.getStreamVolume(AudioManager.STREAM_MUSIC)/Math.max(1,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))),
            "brightness_percent",brightness<0?null:Math.round(100f*brightness/255),
            "available_storage_bytes",disk.getAvailableBytes());
    }
    static JSONObject volume(Context context,JSONObject args) throws Exception {
        AudioManager audio=context.getSystemService(AudioManager.class);
        if(audio==null||audio.isVolumeFixed())throw new ControlException(UNSUPPORTED,"This device has fixed or unavailable media volume");
        String action=args.getString("action");
        if(action.equals("set")) {
            if(!args.has("percent"))throw new ControlException(INVALID_ARGUMENT,"Media volume percentage required");
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,Math.round(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)*args.getInt("percent")/100f),0);
        }else {
            if(args.has("percent"))throw new ControlException(INVALID_ARGUMENT,"Percentage is only valid for set");
            int direction=switch(action){case "up"->AudioManager.ADJUST_RAISE;case "down"->AudioManager.ADJUST_LOWER;case "mute"->AudioManager.ADJUST_MUTE;case "unmute"->AudioManager.ADJUST_UNMUTE;default->throw new ControlException(INVALID_ARGUMENT,"Unknown volume action");};
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,direction,0);
        }
        return Json.obj("status","verified","media_volume_index",audio.getStreamVolume(AudioManager.STREAM_MUSIC),"muted",audio.isStreamMute(AudioManager.STREAM_MUSIC));
    }
    static JSONObject brightness(Context context,int percent) throws Exception {
        if(!Settings.System.canWrite(context))throw new ControlException(PERMISSION_REQUIRED,"Allow Modify system settings in Pocket Agent Settings first");
        // Automatic brightness can immediately override manual values. Make the requested
        // mode explicit and return observed values rather than asserting visible luminance.
        if(!Settings.System.putInt(context.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS_MODE,Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            ||!Settings.System.putInt(context.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,Math.round(percent*255/100f)))
            throw new ControlException(RESULT_UNKNOWN,"Brightness write was not confirmed; inspect settings before repeating");
        return Json.obj("status","verified","brightness_percent",Math.round(100f*Settings.System.getInt(context.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS)/255),"mode","manual");
    }
    static JSONObject torch(Context context,boolean enabled) throws Exception {
        if(context.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)throw new ControlException(PERMISSION_REQUIRED,"Grant Camera permission for flashlight control");
        CameraManager cameras=context.getSystemService(CameraManager.class);
        if(cameras!=null)for(String id:cameras.getCameraIdList()) {
            CameraCharacteristics c=cameras.getCameraCharacteristics(id);
            if(Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))&&Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(c.get(CameraCharacteristics.LENS_FACING))) {
                cameras.setTorchMode(id,enabled);return Json.obj("status","requested","enabled",enabled,"message","Flashlight requested; another camera user or process termination may override it");
            }
        }
        throw new ControlException(UNSUPPORTED,"No rear flashlight available");
    }
    static Intent settingsIntent(String screen) throws Exception {
        String action=switch(screen){case "wifi"->Settings.ACTION_WIFI_SETTINGS;case "bluetooth"->Settings.ACTION_BLUETOOTH_SETTINGS;case "display"->Settings.ACTION_DISPLAY_SETTINGS;case "sound"->Settings.ACTION_SOUND_SETTINGS;case "location"->Settings.ACTION_LOCATION_SOURCE_SETTINGS;case "airplane"->Settings.ACTION_AIRPLANE_MODE_SETTINGS;case "battery"->Intent.ACTION_POWER_USAGE_SUMMARY;default->throw new ControlException(INVALID_ARGUMENT,"Unsupported settings screen");};
        return new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
