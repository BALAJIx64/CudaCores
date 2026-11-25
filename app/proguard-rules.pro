# GuardianGrid Proguard Rules
-keepattributes *Annotation*
-dontwarn java.lang.invoke.**

# Keep TensorFlow Lite runtime classes invoked reflectively by ScreamDistressClassifier
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# Keep WorkManager worker instantiated reflectively by WorkManager
-keep class com.guardiangrid.modules.communication.retry.EmergencyDeliveryWorker { *; }

# Keep core models & interfaces
-keep class com.guardiangrid.core.models.** { *; }
