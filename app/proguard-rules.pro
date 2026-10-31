# kotlinx.serialization (backup format)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class com.folio.reader.**$$serializer { *; }
-keepclassmembers class com.folio.reader.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# PdfBox-Android uses reflection and optional JP2/JBIG2 codecs
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.bouncycastle.**

# jsoup optional re2j dependency
-dontwarn com.google.re2j.**

# commonmark
-dontwarn org.commonmark.**

# Strip verbose logging from release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# LibreOffice document engine: JNI bridge classes are called from native code.
-keep class org.libreoffice.kit.** { *; }
-keep class com.folio.reader.data.office.OfficeConvertService { *; }
