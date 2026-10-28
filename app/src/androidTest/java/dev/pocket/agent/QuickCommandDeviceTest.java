package dev.pocket.agent;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Offline only: no microphone, provider credentials, calls or real message delivery. */
@RunWith(AndroidJUnit4.class)
public class QuickCommandDeviceTest {
    @Test public void shippedInt8EncoderAcceptsCompoundParaphrasesOnAndroid() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File model=File.createTempFile("quick-model-",".onnx",context.getCacheDir());
        try {
            try(java.io.InputStream source=context.getAssets().open("quickcommands/model.onnx")) {Files.copy(source,model.toPath(),StandardCopyOption.REPLACE_EXISTING);}
            try(QuickCommandEncoder encoder=new QuickCommandEncoder(model.getAbsolutePath(),context.getAssets().open("quickcommands/vocab.txt"),context.getAssets().open("quickcommands/prototypes.bin"))) {
                for(String text:List.of("Could you bring up Spotify and set a timer for ten minutes?","Call Priya","Text Rahul saying I will arrive at six","Turn on the torch","Set volume to 35 percent")) {
                    List<QuickCommandParser.Action> plan=QuickCommandParser.parse(text);assertFalse(text,plan.isEmpty());
                    for(QuickCommandParser.Action action:plan)assertTrue(text,encoder.accepts(action));
                }
                assertTrue(QuickCommandParser.parse("Do not call Priya").isEmpty());
            }
        } finally {model.delete();}
    }
    @Test public void everydayFactsAndReadOnlyDeviceInfoNeedNoModel() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try(NativeTools tools=new NativeTools(context)) {
            QuickCommandEngine engine=new QuickCommandEngine((tool,args)->tools.execute(tool,args,"quick-device-test"),action->{throw new AssertionError("Exact facts must not use the model");},System::currentTimeMillis);
            for(String question:List.of("What time is it?","What is the date?","How much battery is left?","Calculate 12 plus 35","Convert 5 miles to kilometers")) {
                QuickCommandEngine.Result result=engine.handle(question,()->true);assertFalse(result.fallback());assertFalse(result.speech().isEmpty());
            }
            JSONObject info=tools.execute("phone_device_info",Json.obj(),"quick-device-test");
            assertFalse(info.toString(),info.optBoolean("isError"));assertTrue(info.has("battery_percent"));assertTrue(info.has("network"));
        }
    }
    @Test public void contactSelectionNeverDispatchesUntilUnambiguous() {
        List<JSONObject> sends=new ArrayList<>();
        QuickCommandEngine engine=new QuickCommandEngine((tool,args)->{
            if(tool.equals("phone_capabilities"))return Json.obj("operation_prefix","20000-0123456789abcdef");
            if(tool.equals("phone_contacts"))return Json.obj("contacts",Json.arr(Json.obj("contact_id",1,"name","Rahul Shah","number","+919000001111"),Json.obj("contact_id",2,"name","Rahul Mehta","number","+919000002222")));
            if(tool.equals("phone_sms")){sends.add(args);return Json.obj("status","requested");}
            throw new AssertionError(tool);
        },action->true,System::currentTimeMillis);
        assertTrue(engine.handle("Text Rahul saying Hello!",()->true).awaiting());assertTrue(sends.isEmpty());
        assertTrue(engine.handle("first Rahul Mehta",()->true).awaiting());assertTrue(sends.isEmpty());
        assertFalse(engine.handle("first Rahul Shah",()->true).awaiting());assertEquals(1,sends.size());
        assertEquals("+919000001111",sends.get(0).optString("number"));assertEquals("Hello!",sends.get(0).optString("text"));
    }
}
