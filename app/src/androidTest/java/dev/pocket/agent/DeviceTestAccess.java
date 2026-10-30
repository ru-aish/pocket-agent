package dev.pocket.agent;
import android.app.UiAutomation;
import android.content.Context;
import android.provider.Settings;
import androidx.test.platform.app.InstrumentationRegistry;
/** Test-only permission provisioning; no exported production entrypoint. */
final class DeviceTestAccess {
 private static boolean accessibilityConnected(Context c) {
  try {
   android.os.Bundle out=c.getContentResolver().call(
    android.net.Uri.parse("content://"+c.getPackageName()+".accessibility-bridge"),
    "status",null,new android.os.Bundle());
   return out!=null&&out.getBoolean("connected");
  }catch(RuntimeException unavailable){return false;}
 }
 static void ensureAccessibility() throws Exception {
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
  if(accessibilityConnected(c))return;
  UiAutomation automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
  automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
  try {
   android.content.ComponentName component=new android.content.ComponentName(c,AgentAccessibility.class);
   String name=component.flattenToString();
   String current=Settings.Secure.getString(c.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
   java.util.LinkedHashSet<String> others=new java.util.LinkedHashSet<>();
   if(current!=null)for(String part:current.split(":"))if(!component.equals(android.content.ComponentName.unflattenFromString(part))&&!part.isEmpty())others.add(part);
   Settings.Secure.putString(c.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",others));
   // Settings observers run asynchronously. Let the disabled state clear Android's crashed-service record.
   Thread.sleep(300);
   others.add(name);Settings.Secure.putString(c.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",others));
   Settings.Secure.putInt(c.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,1);
  }finally{automation.dropShellPermissionIdentity();}
  long deadline=System.currentTimeMillis()+15000;
  while(!accessibilityConnected(c)&&System.currentTimeMillis()<deadline)Thread.sleep(100);
  if(!accessibilityConnected(c))throw new AssertionError("Accessibility did not bind after test provisioning");
 }
}
