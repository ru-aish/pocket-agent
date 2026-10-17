-keep class com.caoccao.javet.** { *; }
-keep class dev.pocket.agent.RuntimeHost$NativeBridge { *; }
-keep class dev.pocket.agent.RuntimeHost { *; }
-keep class dev.pocket.agent.AgentService { *; }
-keep class dev.pocket.agent.CredentialStore { *; }
-keep class dev.pocket.agent.StateStore { *; }
-keep class dev.pocket.agent.NativeTools { *; }
-keep class dev.pocket.agent.Json { *; }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-keep class dev.pocket.agent.RuntimeHost$Bridge { *; }
-keep class dev.pocket.agent.MainActivity { *; }
-keep class dev.pocket.agent.AgentAccessibility { *; }
-keep class dev.pocket.agent.ShellDisplayService { *; }
-keep class dev.pocket.agent.IDisplayShell** { *; }

-keep class dev.pocket.agent.CodexAccountAuth { *; }

# AndroidX instrumentation uses Kotlin classes also packaged by OkHttp.
# The test APK expects all runtime Kotlin types to remain linkable in the target.
-keep class kotlin.** { *; }

# Device instrumentation links these directly; R8 must retain them in the minified target APK.
-keep class dev.pocket.agent.VoiceTranscript { *; }
-keep class dev.pocket.agent.VoiceTextChunker { *; }
-keep class dev.pocket.agent.VoiceCredentialStore { *; }
-keep class dev.pocket.agent.VoiceAnnouncement { *; }
-keep class dev.pocket.agent.VoiceListeningService { *; }
-keep class dev.pocket.agent.HybridTranscriptArbiter** { *; }
-keep class dev.pocket.agent.AndroidShortSpeechRecognizer** { *; }

-keep class dev.pocket.agent.MemoryStore { *; }

# ONNX Runtime uses Java classes referenced through JNI in release builds.
-keep class ai.onnxruntime.** { *; }
-keep class dev.pocket.agent.JarvisDetector { *; }

# Release instrumentation accesses these package APIs by their original names.
-keep class dev.pocket.agent.JarvisProfile** { *; }
-keep class dev.pocket.agent.DeepgramVoice** { *; }
-keep class dev.pocket.agent.PcmCaptureQueue { *; }
-keep class dev.pocket.agent.WakeWordService { *; }

# Release instrumentation and native voice transport share OkHttp WebSocket API types.
-keep class okhttp3.WebSocket { *; }
-keep class okhttp3.WebSocketListener { *; }
-keep class okhttp3.Request { *; }
-keep class okhttp3.Response { *; }
-keep class okhttp3.Request$Builder { *; }
-keep class okhttp3.Response$Builder { *; }
-keep interface okhttp3.WebSocket { *; }
-keep interface dev.pocket.agent.DeepgramVoice$SocketConnector { *; }
-keep class okio.ByteString { *; }
-keep class okio.ByteString$Companion { *; }

# Release instrumentation directly exercises persisted local wake tuning.
-keep class dev.pocket.agent.WakeWordSettings { *; }
