package dev.pocket.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import com.ngrok.Forwarder;
import com.ngrok.Session;
import java.net.URL;
import org.json.JSONObject;

public final class ChatGptMcpService extends Service {
    static final String START="dev.pocket.agent.MCP_START",STOP="dev.pocket.agent.MCP_STOP";
    private static volatile ConnectorAuth auth;
    static ConnectorAuth authorization(android.content.Context context) throws Exception {
        synchronized(ChatGptMcpService.class) {
            if(auth==null){ConnectorSecrets secrets=new ConnectorSecrets(context);auth=new ConnectorAuth(new ConnectorAuth.Store(){public String read() throws Exception{return secrets.read("oauth");}public void write(String value) throws Exception{secrets.write("oauth",value);}},System::currentTimeMillis);}
            return auth;
        }
    }
    private static volatile String stage="stopped", publicUrl="", diagnostic="";
    private volatile ChatGptMcpServer server;
    private volatile Session tunnel;
    private volatile Forwarder.Endpoint endpoint;
    private volatile boolean stopped;
    private Thread starter;

    static JSONObject status() {
        try { return new JSONObject().put("stage",stage).put("publicUrl",publicUrl).put("diagnostic",diagnostic).put("ready",stage.equals("online")&&auth!=null&&auth.connections()>0).put("connections",auth==null?0:auth.connections()).put("localUrl",stage.equals("stopped")||stage.equals("failed")?"":"http://127.0.0.1:8768/mcp"); }
        catch(Exception error) { return new JSONObject(); }
    }
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("chatgpt-mcp","ChatGPT MCP connection",NotificationManager.IMPORTANCE_LOW));
    }
    private Notification notification(String description) {
        PendingIntent open=PendingIntent.getActivity(this,51,new Intent(this,MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,62,new Intent(this,ChatGptMcpService.class).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"chatgpt-mcp").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("PocketAgent · ChatGPT MCP").setContentText(description).setContentIntent(open)
            .setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop connector",stop).build()).build();
    }
    private void notifyState(String message) {
        getSystemService(NotificationManager.class).notify(62,notification(message));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        String action=intent==null?"":intent.getAction();
        if(STOP.equals(action)) {stopSelf();return START_NOT_STICKY;}
        if(!START.equals(action))return START_NOT_STICKY;
        if(starter!=null) {
            diagnostic="MCP connection already active. Stop it before changing its address.";
            notifyState("MCP connection already active");
            return START_NOT_STICKY;
        }
        String startMessage="Starting ChatGPT MCP connection";
        if(android.os.Build.VERSION.SDK_INT>=34)startForeground(62,notification(startMessage),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(62,notification(startMessage));
        stopped=false;stage="starting";diagnostic="";publicUrl="";
        if(!AgentService.isRunning())
            startForegroundService(new Intent(this,AgentService.class).setAction(AgentService.ACTION_START));
        starter=new Thread(this::start,"pocket-chatgpt-mcp");
        starter.start();return START_NOT_STICKY;
    }
    private void start() {
        try {
            ConnectorSecrets secrets=new ConnectorSecrets(this);
            String authtoken=secrets.ngrok(),external=secrets.read("publicOrigin");
            boolean remote=!authtoken.trim().isEmpty();
            if(!remote&&external.isEmpty())throw new IllegalArgumentException("Save an ngrok token or public gateway origin first");
            if(remote&&!new java.io.File(getApplicationInfo().nativeLibraryDir,"libngrok_java.so").exists())
                throw new IllegalStateException("Wireless tunnel is supported only on ARM64 in this build");
            ConnectorAuth authorization=authorization(this);
            if(!remote)authorization.origin(external);
            ChatGptMcpServer ready=new ChatGptMcpServer(authorization);
            ready.start(8000,false);
            synchronized(this){if(stopped){ready.stop();return;}server=ready;stage="local";}
            notifyState("Phone MCP server ready");
            if(!remote){synchronized(this){if(stopped)return;publicUrl=external+"/mcp";stage="online";}notifyState("ChatGPT MCP gateway ready");return;}
            stage="connecting";
            System.loadLibrary("ngrok_java");
            Session session=Session.withAuthtoken(authtoken).addClientInfo("pocket-agent",BuildConfig.VERSION_NAME).connect();
            synchronized(this){if(stopped){session.close();return;}tunnel=session;}
            Forwarder.Endpoint forward=session.httpEndpoint().scheme(com.ngrok.Http.Scheme.HTTPS)
                .forward(new URL("http://127.0.0.1:8768"));
            synchronized(this) {
                if(stopped){forward.close();return;}
                endpoint=forward;
                URL address=new URL(forward.getUrl());
                if(!"https".equals(address.getProtocol()))throw new IllegalStateException("Tunnel is not HTTPS");
                String origin=forward.getUrl().replaceAll("/$","");authorization.origin(origin);publicUrl=origin+"/mcp";
                stage="online";
            }
            notifyState("ChatGPT MCP connected");
        } catch(Throwable error) {
            if(stopped)return;
            diagnostic="MCP connection failed at "+stage+". Check the saved tunnel token, public gateway and phone network.";
            stage="failed";publicUrl="";
            new android.os.Handler(getMainLooper()).post(this::stopSelf);
        }
    }
    @Override public void onDestroy() {
        synchronized(this) {
            stopped=true;stage="stopped";publicUrl="";
            if(server!=null){server.stop();server=null;}
        }
        Thread cleaner=new Thread(()->{
            try {if(endpoint!=null)endpoint.close();}catch(Exception ignored){}
            try {if(tunnel!=null)tunnel.close();}catch(Exception ignored){}
        },"pocket-chatgpt-mcp-cleanup");
        cleaner.start();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) {return null;}
}
