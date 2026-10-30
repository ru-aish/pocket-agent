package dev.pocket.agent;

import android.app.ActivityOptions;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import dev.pocket.android.AndroidScreenBackend;
import dev.pocket.control.ControlBackend;
import dev.pocket.control.ControlException;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AccessibilityBridgeProvider extends ContentProvider {
    private static final long STALE_BRIDGE_FILE_MS=10*60_000L;
    private static final int MAX_BRIDGE_FILES=12;
    private static final int MAX_BRIDGE_FILE_BYTES=8*1024*1024;
    static String authority(android.content.Context context) {return context.getPackageName()+".accessibility-bridge";}
    static Uri uri(android.content.Context context) {return Uri.parse("content://"+authority(context));}
    @Override public boolean onCreate() {return true;}

    private static Bundle error(ControlException error) {
        Bundle out=new Bundle();out.putString("errorCode",error.code.name());out.putString("errorMessage",error.getMessage());return out;
    }
    private static Bundle error(Throwable error) {
        Bundle out=new Bundle();out.putString("errorCode",ControlException.Code.RESULT_UNKNOWN.name());
        out.putString("errorMessage","Accessibility bridge unavailable; observe current state before retrying");return out;
    }
    private static String nullable(Bundle extras,String key) {return extras.containsKey(key)?extras.getString(key):null;}
    private static ControlBackend.Action action(Bundle extras) {
        ControlBackend.Selector selector=null;
        if(extras.getBoolean("hasSelector"))selector=new ControlBackend.Selector(nullable(extras,"resourceId"),nullable(extras,"selectorText"),nullable(extras,"description"));
        return new ControlBackend.Action(ControlBackend.Kind.valueOf(extras.getString("kind")),extras.getString("snapshotId"),
            extras.containsKey("x")?extras.getInt("x"):null,extras.containsKey("y")?extras.getInt("y"):null,
            extras.containsKey("endX")?extras.getInt("endX"):null,extras.containsKey("endY")?extras.getInt("endY"):null,
            extras.getInt("durationMs"),nullable(extras,"text"),extras.getBoolean("replace"),nullable(extras,"key"),nullable(extras,"direction"),selector);
    }
    private AndroidScreenBackend backend(AgentAccessibility service,int displayId) {
        if(displayId<0)throw new IllegalArgumentException("Display ID required");
        return displayId==android.view.Display.DEFAULT_DISPLAY?service.backend():service.backend(displayId);
    }
    private synchronized File bridgeFile(String prefix,byte[] bytes) throws Exception {
        if(bytes==null||bytes.length>MAX_BRIDGE_FILE_BYTES)throw new IllegalStateException("Accessibility bridge payload exceeds limit");
        File dir=new File(getContext().getCacheDir(),"accessibility-bridge");
        if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("Could not create accessibility bridge cache");
        File[] existing=dir.listFiles();
        long cutoff=System.currentTimeMillis()-STALE_BRIDGE_FILE_MS;
        java.util.ArrayList<File> live=new java.util.ArrayList<>();
        if(existing!=null)for(File file:existing)if(file.isFile()) {
            if(file.lastModified()<cutoff)try {file.delete();}catch(RuntimeException ignored){}
            else live.add(file);
        }
        live.sort(java.util.Comparator.comparingLong(File::lastModified));
        while(live.size()>=MAX_BRIDGE_FILES) {
            File oldest=live.remove(0);
            try {oldest.delete();}catch(RuntimeException ignored){}
        }
        File file=File.createTempFile(prefix,".bin",dir);
        try(FileOutputStream output=new FileOutputStream(file)){output.write(bytes);output.getFD().sync();}
        return file;
    }
    private Bundle observation(ControlBackend.Observation observation) throws Exception {
        JSONObject payload=new JSONObject()
            .put("snapshotId",observation.snapshotId()).put("capturedAtMs",observation.capturedAtMs()).put("packageName",observation.packageName())
            .put("windowId",observation.windowId()).put("coordinateWidth",observation.coordinateWidth()).put("coordinateHeight",observation.coordinateHeight())
            .put("interactive",observation.interactive()).put("locked",observation.locked()).put("nodesTruncated",observation.nodesTruncated())
            .put("imageMime",observation.imageMime()).put("imageWidth",observation.imageWidth()).put("imageHeight",observation.imageHeight());
        JSONArray nodes=new JSONArray();
        for(ControlBackend.Node node:observation.nodes())nodes.put(new JSONObject()
            .put("index",node.index()).put("resourceId",node.resourceId()).put("text",node.text()).put("description",node.description()).put("role",node.role())
            .put("left",node.left()).put("top",node.top()).put("right",node.right()).put("bottom",node.bottom())
            .put("enabled",node.enabled()).put("focused",node.focused()).put("editable",node.editable()).put("clickable",node.clickable())
            .put("longClickable",node.longClickable()).put("scrollable",node.scrollable()));
        payload.put("nodes",nodes);
        byte[] image=observation.image();
        if(image!=null)payload.put("imageFile",bridgeFile("screen-",image).getAbsolutePath());
        File serialized=bridgeFile("observation-",payload.toString().getBytes(StandardCharsets.UTF_8));
        Bundle out=new Bundle();out.putString("payloadFile",serialized.getAbsolutePath());return out;
    }
    @Override public Bundle call(String method,String arg,Bundle extras) {
        try {
            AgentAccessibility service=AgentAccessibility.connected;
            if("status".equals(method)) {Bundle out=new Bundle();out.putBoolean("connected",service!=null);return out;}
            if("release".equals(method)) {
                if(service!=null&&extras!=null&&extras.getInt("displayId",-1)>0)service.release(extras.getInt("displayId"));
                return new Bundle();
            }
            if(service==null)throw new ControlException(ControlException.Code.PERMISSION_REQUIRED,"Pocket Agent accessibility is enabled but not currently bound");
            if(extras==null)throw new ControlException(ControlException.Code.INVALID_ARGUMENT,"Missing accessibility bridge arguments");
            if("startActivity".equals(method)) {
                int displayId=extras.getInt("displayId",-1);
                if(displayId!=android.view.Display.DEFAULT_DISPLAY)
                    throw new ControlException(ControlException.Code.INVALID_ARGUMENT,"Visible-screen launch requires the physical display");
                Intent source=(Intent)extras.getParcelable("intent");
                if(source==null)throw new ControlException(ControlException.Code.INVALID_ARGUMENT,"Missing launch intent");
                Intent intent=new Intent(source).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    ActivityOptions options=ActivityOptions.makeBasic();options.setLaunchDisplayId(displayId);
                    service.startActivity(intent,options.toBundle());
                } catch(SecurityException denied) {
                    throw new ControlException(ControlException.Code.PERMISSION_REQUIRED,"Android blocked opening content on the visible display");
                } catch(RuntimeException rejected) {
                    throw new ControlException(ControlException.Code.ACTION_REJECTED,"Android rejected opening content on the visible display");
                }
                return new Bundle();
            }
            AndroidScreenBackend backend=backend(service,extras.getInt("displayId",-1));
            if("observe".equals(method))
                return observation(backend.observe(extras.getBoolean("includeImage"),extras.getInt("maxImageDimension"),extras.getInt("maxNodes")));
            if("noteInteraction".equals(method)) {backend.noteInteraction(extras.getLong("eventTime"));return new Bundle();}
            if("validate".equals(method)) {backend.validateAction(action(extras));return new Bundle();}
            if("prepareKeyboardText".equals(method)) {backend.prepareKeyboardText(action(extras));return new Bundle();}
            if("execute".equals(method)) {
                ControlBackend.Result result=backend.execute(action(extras));Bundle out=new Bundle();out.putString("status",result.status());out.putString("message",result.message());return out;
            }
            throw new ControlException(ControlException.Code.INVALID_ARGUMENT,"Unknown accessibility bridge operation");
        } catch(ControlException error) {return error(error);}
        catch(Throwable error) {return error(error);}
    }

    @Override public String getType(Uri uri) {return null;}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder) {throw new UnsupportedOperationException();}
    @Override public Uri insert(Uri uri,ContentValues values) {throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] selectionArgs) {throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] selectionArgs) {throw new UnsupportedOperationException();}
}
