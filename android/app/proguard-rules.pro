# https://github.com/aws-amplify/aws-sdk-android/blob/master/Proguard.md
-keepnames class com.amazonaws.**
-keepnames class com.amazon.**
-keep class com.amazonaws.services.**.*Handler
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.logging.**
-dontwarn org.apache.http.**
-dontwarn com.amazonaws.http.**
-dontwarn com.amazonaws.metrics.**
