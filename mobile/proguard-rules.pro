# R8 / ProGuard rules for DiPlay mobile application

# 1. Preserve JNI dynamic lookup, native methods, and JNI-instantiated exceptions
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.shilapi.xcertplay.transport.LinuxI2cNativeException {
    <init>(int, java.lang.String);
    *;
}

# 2. Preserve app_process entry points (invoked from shell via CLASSPATH=$apk app_process)
-keep class com.shilapi.xcertplay.transport.IphoneUsbGrantTool {
    public static void main(java.lang.String[]);
}
-keep class com.shilapi.xcertplay.network.CarHotspotStartMain {
    public static void main(java.lang.String[]);
}
-keep class com.shilapi.xcertplay.network.HotspotJoinRepairMain {
    public static void main(java.lang.String[]);
}
-keep class com.shilapi.xcertplay.network.WifiScanPauseMain {
    public static void main(java.lang.String[]);
}
-keep class com.shilapi.xcertplay.mfi.LocalMfiProbe {
    public static void main(java.lang.String[]);
}

# 3. Preserve Binder interfaces and IPC stubs for Flyme EAS and Car services
-keep class * extends android.os.Binder {
    *;
}
-keep interface * extends android.os.IInterface {
    *;
}

# 4. Standard attributes
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# 5. Suppress warnings for runtime/vendor provided classes and optional dependencies
-dontwarn com.ecarx.xui.adaptapi.**
-dontwarn org.bouncycastle.**
-dontwarn javax.jmdns.**
