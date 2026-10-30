package dev.pocket.agent;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.json.JSONObject;

final class PocketWebUi {
    interface Reply { void complete(JSONObject value, String error); }
    interface Requests { void handle(String path, JSONObject body, Reply reply) throws Exception; }
    final WebView view;
    private final Activity activity;
    private boolean closed;
    private Boolean keyboardVisible;
    private ValueCallback<Uri[]> fileChooser;
    PocketWebUi(Activity activity, Requests requests) {
        this.activity=activity; view=new WebView(activity);
        view.setBackgroundColor(activity.getColor(R.color.pocket_canvas));
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setDomStorageEnabled(true);
        view.getSettings().setAllowFileAccess(false);
        view.getSettings().setAllowContentAccess(true);
        view.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        view.addJavascriptInterface(new Object() {
            @JavascriptInterface public void request(String id,String path,String payload) {
                if(id==null||!id.matches("[0-9]{1,12}")||payload==null||payload.length()>300000)return;
                activity.runOnUiThread(()->{
                    if(closed)return;
                    try { requests.handle(path,new JSONObject(payload),(value,error)->respond(id,value,error)); }
                    catch(Exception failure) { respond(id,null,failure instanceof IllegalArgumentException||failure instanceof IllegalStateException ? failure.getMessage() : "Could not complete this action. Check your connection and try again."); }
                });
            }
        },"PocketNative");
        view.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView web,ValueCallback<Uri[]> callback,FileChooserParams params){
                if(fileChooser!=null)fileChooser.onReceiveValue(null);fileChooser=callback;
                try{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);activity.startActivityForResult(intent,64001);}
                catch(Exception unavailable){fileChooser=null;callback.onReceiveValue(null);}return true;
            }
        });
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView web,WebResourceRequest request) {
                Uri url=request.getUrl();
                if("https".equals(url.getScheme())&&"pocket.local".equals(url.getHost()))return false;
                if(request.hasGesture()&&"https".equals(url.getScheme())&&"auth.openai.com".equals(url.getHost()))
                    activity.startActivity(new Intent(Intent.ACTION_VIEW,url));
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView web,WebResourceRequest request) {
                Uri url=request.getUrl();String path=url.getPath();
                if(!"https".equals(url.getScheme())||!"pocket.local".equals(url.getHost())||path==null||!path.matches("/[a-zA-Z0-9.-]+"))return empty();
                try {
                    byte[] bytes;try(java.io.InputStream stream=activity.getAssets().open("ui"+path);java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()){
                        byte[] buffer=new byte[8192];int count;while((count=stream.read(buffer))!=-1)output.write(buffer,0,count);bytes=output.toByteArray();
                    }
                    String mime=(path.endsWith(".js")||path.endsWith(".mjs"))?"text/javascript":path.endsWith(".css")?"text/css":path.endsWith(".svg")?"image/svg+xml":"text/html";
                    if(path.equals("/index.html"))bytes=new String(bytes,StandardCharsets.UTF_8).replace("/app.js","/native-app.js").replace("</head>","<link rel=\"stylesheet\" href=\"/native.css\"></head>").getBytes(StandardCharsets.UTF_8);
                    return new WebResourceResponse(mime,"UTF-8",200,"OK",Map.of("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'none'; base-uri 'none'; form-action 'none'"),new ByteArrayInputStream(bytes));
                } catch(Exception missing) { return empty(); }
            }
        });
        view.loadUrl("https://pocket.local/index.html");
    }
    private static WebResourceResponse empty() { return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0])); }
    private void respond(String id,JSONObject value,String error) {
        // Snapshot reads complete on a worker; serialize there before posting
        // the small WebView dispatch to the UI thread.
        String script="window.pocketReply?.("+JSONObject.quote(id)+","+(value==null?"null":value.toString())+","+(error==null?"null":JSONObject.quote(error))+")";
        activity.runOnUiThread(()->{if(!closed)view.evaluateJavascript(script,null);});
    }
    void event(String type,JSONObject value) { if(!closed)view.evaluateJavascript("window.pocketEvent?.("+JSONObject.quote(type)+","+value+")",null); }
    void keyboardVisibility(boolean visible) {
        if(closed||keyboardVisible!=null&&keyboardVisible==visible)return;
        keyboardVisible=visible;
        view.evaluateJavascript("window.dispatchEvent(new CustomEvent('pocket-keyboard',{detail:{visible:"+visible+"}}))",null);
    }
    void fileResult(int result,Intent data){ValueCallback<Uri[]> callback=fileChooser;fileChooser=null;if(callback!=null)callback.onReceiveValue(result==Activity.RESULT_OK&&data!=null&&data.getData()!=null&&"content".equals(data.getData().getScheme())?new Uri[]{data.getData()}:null);}
    void close() { closed=true;if(fileChooser!=null){fileChooser.onReceiveValue(null);fileChooser=null;}view.removeJavascriptInterface("PocketNative");view.destroy(); }
}
