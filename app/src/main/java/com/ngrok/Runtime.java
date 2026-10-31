package com.ngrok;

/** Android loader adapter for the upstream JNI classes; desktop extraction is unnecessary. */
final class Runtime {
    private static final Logger LOGGER=new Logger();
    static void load() {System.loadLibrary("ngrok_java");}
    static Logger getLogger() {return LOGGER;}
    static native void init(Logger logger);
    static final class Logger {
        public String getLevel() {return "ERROR";}
        public void log(String level,String target,String message) {
            // Deliberately omit SDK logs: an upstream diagnostic may contain account credentials.
        }
    }
}
