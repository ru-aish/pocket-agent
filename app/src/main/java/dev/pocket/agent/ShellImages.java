package dev.pocket.agent;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.Image;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import org.json.JSONObject;

final class ShellImages {
    static JSONObject encode(Bitmap original,int dimension) throws Exception {
        if(dimension<256||dimension>1600)throw new IllegalArgumentException("max_image_dimension must be 256..1600");
        double scale=Math.min(1d,(double)dimension/Math.max(original.getWidth(),original.getHeight()));
        Bitmap scaled=Bitmap.createScaledBitmap(original,Math.max(1,(int)(original.getWidth()*scale)),Math.max(1,(int)(original.getHeight()*scale)),true);
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();scaled.compress(Bitmap.CompressFormat.JPEG,75,bytes);
            // AIDL parcels JSON strings as UTF-16: base64 costs about 8/3 of JPEG bytes.
            // Leave room below Binder's shared 1 MiB buffer for the envelope and UI previews.
            if(bytes.size()>300_000)throw new IllegalArgumentException("Image exceeds Binder payload size; request a smaller max_image_dimension");
            return Json.obj("image_width",scaled.getWidth(),"image_height",scaled.getHeight(),"coordinate_width",original.getWidth(),"coordinate_height",original.getHeight(),
                "captured_at_ms",System.currentTimeMillis(),"images",Json.arr(Json.obj("mimeType","image/jpeg","data",Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP))));
        } finally {if(scaled!=original)scaled.recycle();}
    }
    static JSONObject read(String path,int dimension) throws Exception {
        if(dimension<256||dimension>1600)throw new IllegalArgumentException("max_image_dimension must be 256..1600");
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(path,bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IllegalArgumentException("Not a readable image file");
        BitmapFactory.Options options=new BitmapFactory.Options();
        while(Math.max(bounds.outWidth,bounds.outHeight)/Math.max(1,options.inSampleSize)>dimension*2)options.inSampleSize=Math.max(1,options.inSampleSize)*2;
        Bitmap bitmap=BitmapFactory.decodeFile(path,options);if(bitmap==null)throw new IllegalArgumentException("Cannot decode image");
        try {JSONObject result=encode(bitmap,dimension);result.put("coordinate_width",bounds.outWidth);result.put("coordinate_height",bounds.outHeight);result.put("path",path);return result;}
        finally {bitmap.recycle();}
    }
    static JSONObject frame(Image image,int dimension) throws Exception {
        Image.Plane plane=image.getPlanes()[0];int width=image.getWidth(),height=image.getHeight();
        Bitmap padded=Bitmap.createBitmap(plane.getRowStride()/plane.getPixelStride(),height,Bitmap.Config.ARGB_8888);
        try {
            java.nio.ByteBuffer pixels=plane.getBuffer();pixels.rewind();java.nio.ByteBuffer packed=java.nio.ByteBuffer.allocate(padded.getByteCount());packed.put(pixels);packed.rewind();padded.copyPixelsFromBuffer(packed);Bitmap cropped=Bitmap.createBitmap(padded,0,0,width,height);
            try{return encode(cropped,dimension);}finally {if(cropped!=padded)cropped.recycle();}
        }finally {padded.recycle();}
    }
}
