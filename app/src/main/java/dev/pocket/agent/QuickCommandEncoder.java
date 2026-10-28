package dev.pocket.agent;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Quantized MiniLM, masked mean pooling and normalized cosine prototypes. No text generation. */
final class QuickCommandEncoder implements AutoCloseable {
    static final int MAX_TOKENS=96, DIMENSIONS=384;
    private final OrtEnvironment environment=OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final Map<String,Integer> vocabulary=new HashMap<>();
    private final List<String> labels=new ArrayList<>();
    private final List<float[]> prototypes=new ArrayList<>();
    QuickCommandEncoder(String modelPath,InputStream vocab,InputStream examples) throws Exception {
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(vocab,StandardCharsets.UTF_8))) {
            String line;int index=0;while((line=reader.readLine())!=null)vocabulary.put(line,index++);
        }
        try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()) {
            options.setInterOpNumThreads(1);options.setIntraOpNumThreads(2);
            session=environment.createSession(modelPath,options);
        }
        try {
            if(examples!=null)try(DataInputStream data=new DataInputStream(examples)) {
                if(data.readInt()!=0x51434d31)throw new IllegalArgumentException("Unknown prototype format");
                int count=data.readInt();if(count<1||count>500)throw new IllegalArgumentException("Prototype count");
                for(int i=0;i<count;i++){labels.add(data.readUTF());float[] vector=new float[DIMENSIONS];for(int j=0;j<vector.length;j++)vector[j]=data.readFloat();prototypes.add(vector);}
            }
        }catch(Exception failure){session.close();throw failure;}
    }
    long[] tokenize(String text) {
        String clean=Normalizer.normalize(text.toLowerCase(Locale.ROOT),Normalizer.Form.NFD).replaceAll("\\p{M}","");
        clean=clean.replaceAll("[\\p{Cc}\\p{Cf}]"," ").replaceAll("([\\p{P}\\p{S}])"," $1 ");
        List<Long> ids=new ArrayList<>();ids.add(101L);
        for(String token:clean.trim().split("\\s+")) {
            if(token.isEmpty())continue;
            List<Long> pieces=new ArrayList<>();int start=0;
            if(token.length()>100)pieces.add(100L);
            else while(start<token.length()) {
                Integer found=null;int end=token.length();
                while(end>start){found=vocabulary.get((start==0?"":"##")+token.substring(start,end));if(found!=null)break;end--;}
                if(found==null){pieces.clear();pieces.add(100L);break;}
                pieces.add((long)found);start=end;
            }
            // Never classify a truncated command as if it were the entire request.
            if(ids.size()+pieces.size()>MAX_TOKENS-1)throw new IllegalArgumentException("Command too long");
            ids.addAll(pieces);
        }
        ids.add(102L);long[] result=new long[ids.size()];for(int i=0;i<result.length;i++)result[i]=ids.get(i);return result;
    }
    float[] embed(String text) throws Exception {
        long[] ids=tokenize(text),mask=new long[ids.length],types=new long[ids.length];java.util.Arrays.fill(mask,1);
        try(OnnxTensor input=OnnxTensor.createTensor(environment,new long[][]{ids});
            OnnxTensor attention=OnnxTensor.createTensor(environment,new long[][]{mask});
            OnnxTensor segments=OnnxTensor.createTensor(environment,new long[][]{types})) {
            Map<String,OnnxTensor> inputs=new HashMap<>();inputs.put("input_ids",input);inputs.put("attention_mask",attention);
            if(session.getInputNames().contains("token_type_ids"))inputs.put("token_type_ids",segments);
            try(OrtSession.Result result=session.run(inputs)) {
                float[][] tokens=((float[][][])result.get(0).getValue())[0];float[] pooled=new float[DIMENSIONS];
                for(float[] token:tokens)for(int i=0;i<pooled.length;i++)pooled[i]+=token[i]/tokens.length;
                double norm=0;for(float f:pooled)norm+=f*f;norm=Math.sqrt(norm);
                if(norm<=0||!Double.isFinite(norm))throw new IllegalStateException("Invalid embedding");
                for(int i=0;i<pooled.length;i++)pooled[i]/=(float)norm;
                return pooled;
            }
        }
    }
    record Ranking(String label,float score,float margin) {}
    Ranking rank(String text) throws Exception {
        float[] vector=embed(text);Map<String,Float> scores=new LinkedHashMap<>();
        for(int i=0;i<prototypes.size();i++){float dot=0;for(int j=0;j<vector.length;j++)dot+=vector[j]*prototypes.get(i)[j];scores.merge(labels.get(i),dot,Math::max);}
        String best="";float first=-1,second=-1;
        for(Map.Entry<String,Float> entry:scores.entrySet()){float score=entry.getValue();if(score>first){second=first;first=score;best=entry.getKey();}else second=Math.max(second,score);}
        return new Ranking(best,first,first-second);
    }
    boolean accepts(QuickCommandParser.Action action) throws Exception {
        Ranking r=rank(semanticText(action));return r.label().equals(action.intent())&&r.score()>=0.50f&&r.margin()>=0.035f;
    }
    static String semanticText(QuickCommandParser.Action action) {
        String text=action.source();
        // Dynamic names and dictated content must not dominate the action classifier.
        // Slot extraction already validated their exact spans before this representation.
        if(!action.get("text").isEmpty())text=text.replace(action.get("text"),"hello");
        if(!action.get("recipient").isEmpty())text=text.replace(action.get("recipient"),"Alex");
        if(!action.get("app").isEmpty())text=text.replace(action.get("app"),"Spotify");
        if(!action.get("url").isEmpty())text=text.replace(action.get("url"),"https://example.com");
        return text;
    }
    @Override public void close() throws Exception {session.close();}
}
