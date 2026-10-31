package dev.pocket.agent;

import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

final class ChatGptMcpSettings {
    static JSONObject status(Context context) throws Exception {
        ConnectorSecrets secrets=new ConnectorSecrets(context);
        return ChatGptMcpService.status().put("tunnelConfigured",!secrets.ngrok().isEmpty()).put("publicOrigin",secrets.read("publicOrigin"));
    }
    static JSONObject handle(Context context,String action,JSONObject input) throws Exception {
        ConnectorSecrets secrets=new ConnectorSecrets(context);
        switch(action){
            case "status" -> {return status(context);}
            case "pairing" -> {
                if(!ChatGptMcpService.status().optString("stage").equals("online"))throw new IllegalStateException("Start the MCP connection first");
                return ChatGptMcpService.authorization(context).pairing();
            }
            case "rotate" -> {ChatGptMcpService.authorization(context).rotatePairing();return handle(context,"pairing",input);}
            case "revoke" -> ChatGptMcpService.authorization(context).revoke();
            case "start" -> context.startForegroundService(new Intent(context,ChatGptMcpService.class).setAction(ChatGptMcpService.START));
            case "stop" -> context.stopService(new Intent(context,ChatGptMcpService.class));
            case "save" -> {
                if(!java.util.List.of("stopped","failed").contains(ChatGptMcpService.status().optString("stage")))throw new IllegalStateException("Stop the MCP connection before changing its address");
                String token=input.optString("ngrokToken","").trim(),origin=input.optString("publicOrigin","").trim().replaceAll("/$","");
                if(token.length()>4000||token.contains("\r")||token.contains("\n"))throw new IllegalArgumentException("Invalid tunnel token");
                if(!origin.isEmpty()){
                    java.net.URI uri=java.net.URI.create(origin);
                    if(origin.length()>2048||!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!uri.getPath().isEmpty())throw new IllegalArgumentException("Use an HTTPS public origin without a path, query or credentials");
                }
                if(input.optBoolean("externalGateway"))secrets.write("ngrok","");else if(!token.isEmpty())secrets.write("ngrok",token);
                secrets.write("publicOrigin",origin);
            }
            case "copy" -> {
                boolean code=input.optString("what").equals("code");
                String value=code?handle(context,"pairing",new JSONObject()).getString("code"):ChatGptMcpService.status().optString("publicUrl");
                if(value.isEmpty())throw new IllegalStateException("Start the MCP connection first");
                android.content.ClipData clip=android.content.ClipData.newPlainText(code?"PocketAgent pairing code":"PocketAgent MCP URL",value);
                if(code){android.os.PersistableBundle sensitive=new android.os.PersistableBundle();sensitive.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(sensitive);}
                context.getSystemService(android.content.ClipboardManager.class).setPrimaryClip(clip);
            }
            default -> throw new IllegalArgumentException("Unknown MCP settings action");
        }
        return status(context);
    }
}
