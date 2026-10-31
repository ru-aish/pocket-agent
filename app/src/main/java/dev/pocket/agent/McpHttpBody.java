package dev.pocket.agent;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.nio.charset.StandardCharsets;
final class McpHttpBody {
    private static final int MAX_BODY=1_048_576,MAX_CHUNKS=4096,MAX_FRAMING_BYTES=64*1024;
    private static String readHeaderLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        for (int c; (c=input.read())!=-1;) {
            if (c=='\n') {
                byte[] line=bytes.toByteArray();
                int end=line.length>0 && line[line.length-1]=='\r'?line.length-1:line.length;
                return new String(line,0,end,StandardCharsets.US_ASCII);
            }
            if (bytes.size()>=8192) throw new IOException("Chunk header too large");
            bytes.write(c);
        }
        throw new IOException("Incomplete chunked request");
    }
    static byte[] readRequestBody(NanoHTTPD.IHTTPSession request) throws IOException {
        String encoding=request.getHeaders().getOrDefault("transfer-encoding","");
        boolean chunked=encoding.equalsIgnoreCase("chunked");
        String length=request.getHeaders().get("content-length");
        if (!encoding.isEmpty() && !chunked) throw new IOException("Unsupported transfer encoding");
        if (chunked && length!=null) throw new IOException("Conflicting request framing");
        InputStream input=request.getInputStream();
        if (!chunked) {
            int size;
            try {size=Integer.parseInt(length==null?"-1":length);}
            catch(NumberFormatException error){throw new IOException("Invalid Content-Length",error);}
            if (size<1||size>MAX_BODY) throw new IOException("Invalid body length");
            return readFully(input,size);
        }
        ByteArrayOutputStream result=new ByteArrayOutputStream();
        long framingBytes=0;
        int chunks=0;
        while (true) {
            if (++chunks>MAX_CHUNKS) throw new IOException("Too many chunks");
            String header=readHeaderLine(input);
            framingBytes+=header.length()+2L;
            if (framingBytes>MAX_FRAMING_BYTES) throw new IOException("Chunk framing too large");
            String sizeHex=header.split(";",2)[0].trim();
            if (sizeHex.isEmpty() || sizeHex.length()>8) throw new IOException("Invalid chunk length");
            long size;
            try {size=Long.parseLong(sizeHex,16);}
            catch(NumberFormatException error){throw new IOException("Invalid chunk length",error);}
            if (size<0||size>MAX_BODY-result.size()) throw new IOException("Body too large");
            if (size==0) {
                // Trailer headers must be finite, and never influence authorization.
                while (true) {
                    String trailer=readHeaderLine(input);
                    framingBytes+=trailer.length()+2L;
                    if (framingBytes>MAX_FRAMING_BYTES) throw new IOException("Chunk framing too large");
                    if (trailer.isEmpty()) return result.toByteArray();
                }
            }
            framingBytes+=2; // Required CRLF after each payload.
            if (framingBytes>MAX_FRAMING_BYTES) throw new IOException("Chunk framing too large");
            result.write(readFully(input,(int)size));
            if (input.read()!='\r'||input.read()!='\n') throw new IOException("Invalid chunk terminator");
        }
    }
    private static byte[] readFully(InputStream input,int size) throws IOException {
        byte[] bytes=new byte[size];int done=0;
        while (done<size) {
            int n=input.read(bytes,done,size-done);
            if (n<0) throw new IOException("Truncated body");
            if (n==0) continue;
            done+=n;
        }
        return bytes;
    }
}
