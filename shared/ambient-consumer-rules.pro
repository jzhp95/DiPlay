# Entry point launched by app_process from the installed application classpath.
-keep class com.shilapi.xcertplay.transport.IphoneUsbGrantTool {
    public static void main(java.lang.String[]);
}

# Preserve JNI-instantiated exception classes and constructors
-keep class com.shilapi.xcertplay.transport.LinuxI2cNativeException {
    <init>(int, java.lang.String);
    *;
}
