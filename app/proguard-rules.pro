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
# Release instrumentation exercises preview ownership and delayed dialog callbacks.
-keep class dev.pocket.agent.DisplayPreviewSurface { *; }
-keep class dev.pocket.agent.DisplayViewer** { *; }
-keep class dev.pocket.agent.AgentDisplayCoordinator** { *; }

-keep class dev.pocket.agent.CodexAccountAuth { *; }

# AndroidX instrumentation uses Kotlin classes also packaged by OkHttp.
# The test APK expects all runtime Kotlin types to remain linkable in the target.
-keep class kotlin.** { *; }

# Device instrumentation links these directly; R8 must retain them in the minified target APK.
-keep class dev.pocket.agent.VoiceTranscript { *; }
-keep class dev.pocket.agent.VoiceTextChunker { *; }
# Release androidTest R8 can reuse this target helper; preserve its ABI across the separately minified APKs.
-keepclassmembers class dev.pocket.agent.AccessibilityProxyBackend {
    static boolean connected(android.content.Context);
}
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
-keep class dev.pocket.agent.SubscriptionVoiceFailureStore { *; }
-keep class dev.pocket.agent.SubscriptionVoiceSettings { *; }
# The separately minified invitation device tests call this service status API.
-keepclassmembers class dev.pocket.agent.SubscriptionVoiceService {
    static boolean isActive();
}
-keep class dev.pocket.agent.SubscriptionVoiceContext { *; }
-keep class dev.pocket.agent.VoiceConversationInvitation** { *; }
-keep class dev.pocket.agent.SubscriptionVoiceAudioRoute** { *; }
-keep interface dev.pocket.agent.SubscriptionVoiceAudioRoute$Platform { *; }
-keep class dev.pocket.agent.SubscriptionVoiceOutputSpeech** { *; }
-keep interface dev.pocket.agent.SubscriptionVoiceOutputSpeech$* { *; }

# WebRTC's JNI entry points reference org.webrtc classes by their original names.
# Preserve JNI-visible classes/methods in the minified release APK.
-keep class org.webrtc.** { *; }
# Keep only JNI-called JniZero methods: the bundled AAR references an optional
# generated JniZeroJni class that it does not ship, so retaining all methods fails R8.
-keepclasseswithmembers class org.jni_zero.** {
    @org.jni_zero.CalledByNative <methods>;
}
-keepclasseswithmembers class org.jni_zero.** {
    @org.jni_zero.CalledByNativeUnchecked <methods>;
}

# WorkManager 2.10's Room dependency loads this no-argument constructor reflectively.
# R8 full mode no longer retains constructors from a class-only keep rule.
# https://android.googlesource.com/platform/frameworks/support/+/e4765e38e27a3f4d86188b996df97a52403dff46
-keep class androidx.work.impl.WorkDatabase_Impl { public <init>(); }

# AndroidX test runner / ActivityScenario transitively call Trace in the target APK.
# Removing this rule caused a release-instrumentation NoSuchMethodError in run 40200409651.
# Preserve the shared ABI across the separately minified target and test APKs.
-keep class androidx.tracing.Trace { *; }
-keep class dev.pocket.agent.AutonomyStore { *; }
-keep class dev.pocket.agent.RuntimeOwner { *; }

# Package-private notification helpers exercised by the separately compiled device suite.
-keepclassmembers class dev.pocket.agent.PhoneNotifications {
    static java.lang.String notificationText(android.os.Bundle);
    static java.lang.String clip(java.lang.String);
}

# Release device coverage checks timer request coalescing under a superseded attempt.
-keep class dev.pocket.agent.AutonomyScheduler$ArmRequests { *; }
