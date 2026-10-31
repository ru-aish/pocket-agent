package dev.pocket.agent;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Same settings controller as the main UI, available from the original native settings. */
public final class ChatGptMcpActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private TextView status,code,expiry,url;
    private EditText token,origin;
    private CheckBox external;
    private JSONObject pairing=new JSONObject();
    private boolean closed,initialized;
    private int ticks;
    private final Runnable tick=new Runnable(){public void run(){if(closed)return;long seconds=Math.max(0,(pairing.optLong("expiresAt")-System.currentTimeMillis()+999)/1000);code.setText(seconds>0?pairing.optString("code"):"—");expiry.setText(seconds>0?"Expires in "+seconds/60+":"+String.format(java.util.Locale.ROOT,"%02d",seconds%60)+" · One use":"Start the connection to get a pairing code");if(++ticks%5==0)refresh();ui.postDelayed(this,1000);}};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(28,48,28,28);ScrollView scroll=new ScrollView(this);scroll.addView(body);setContentView(scroll);
        add(body,"Back",this::finish);label(body,"ChatGPT MCP",25);label(body,"Connect ChatGPT to this phone’s tools. Start tasks in PocketAgent after connecting the app in ChatGPT.",16);
        status=label(body,"Reading status…",16);url=label(body,"",16);url.setTextIsSelectable(true);add(body,"Copy MCP URL",()->action("copy",ChatGptMcpServer.object("what","url")));
        label(body,"Pairing code",19);code=label(body,"—",28);code.setTextIsSelectable(true);expiry=label(body,"",16);
        add(body,"Copy pairing code",()->action("copy",ChatGptMcpServer.object("what","code")));add(body,"Get a new code",()->action("rotate",new JSONObject()));
        label(body,"Add the URL as a custom app in ChatGPT with OAuth. Enter this five-minute, single-use code on the authorization page. Existing connections renew automatically.",16);
        add(body,"Start MCP connection",()->action("start",new JSONObject()));add(body,"Stop MCP connection",()->action("stop",new JSONObject()));add(body,"Revoke all connections",()->action("revoke",new JSONObject()));
        token=new EditText(this);token.setHint("ngrok token · blank keeps the saved token");token.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);token.setSaveEnabled(false);token.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);body.addView(token);
        origin=new EditText(this);origin.setHint("Public HTTPS gateway origin (optional)");body.addView(origin);
        external=new CheckBox(this);external.setText("Use the public gateway instead of ngrok");body.addView(external);
        label(body,"A public gateway must forward to this phone at 127.0.0.1:8768. With ngrok, PocketAgent creates the URL for you.",16);
        add(body,"Save connection address",()->{JSONObject input=ChatGptMcpServer.object("ngrokToken",token.getText().toString(),"publicOrigin",origin.getText().toString(),"externalGateway",external.isChecked());token.setText("");action("save",input);});
        refresh();ui.post(tick);
    }
    private TextView label(LinearLayout parent,String text,int size){TextView view=new TextView(this);view.setText(text);view.setTextSize(size);view.setPadding(0,12,0,12);parent.addView(view);return view;}
    private void add(LinearLayout parent,String text,Runnable task){Button button=new Button(this);button.setText(text);button.setOnClickListener(view->task.run());parent.addView(button);}
    private void action(String action,JSONObject input){
        if(action.equals("copy")){try{ChatGptMcpSettings.handle(this,action,input);Toast.makeText(this,"Copied",Toast.LENGTH_SHORT).show();}catch(Exception error){status.setText(error.getMessage());}return;}
        io.submit(()->{try{JSONObject result=ChatGptMcpSettings.handle(this,action,input);ui.post(()->{if(closed)return;if(action.equals("rotate"))pairing=result;refresh();});}catch(Exception error){ui.post(()->{if(!closed)status.setText(error.getMessage());});}});
    }
    private void refresh(){if(closed)return;io.submit(()->{
        try {
            JSONObject info=ChatGptMcpSettings.status(this),next=info.optString("stage").equals("online")?ChatGptMcpSettings.handle(this,"pairing",new JSONObject()):new JSONObject();
            ui.post(()->{if(closed)return;status.setText(info.optString("stage")+" · "+info.optInt("connections")+" authorized connections\n"+info.optString("diagnostic"));url.setText(info.optString("publicUrl"));pairing=next;if(!initialized){origin.setText(info.optString("publicOrigin"));external.setChecked(!info.optBoolean("tunnelConfigured"));initialized=true;}});
        } catch(Exception error){ui.post(()->{if(!closed)status.setText("Could not read MCP settings");});}
    });}
    @Override protected void onDestroy(){closed=true;ui.removeCallbacksAndMessages(null);io.shutdownNow();pairing=new JSONObject();if(token!=null)token.setText("");if(code!=null)code.setText("—");super.onDestroy();}
}
