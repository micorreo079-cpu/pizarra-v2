# Reglas de ofuscación (R8).
#
# Objetivo: que al descompilar la app no se lea como el código original, para
# que localizar la comprobación de licencia cueste trabajo. Se ofusca y se
# optimiza, pero hay cosas que NO se pueden tocar o la pizarra deja de
# funcionar:
#
#  1. Los métodos nativos (JNI): la librería en C los busca por el nombre EXACTO
#     de la clase y del método (Java_com_example_newdrawingapp_Activation_...).
#     Si R8 los renombra se rompe el enlace: fallaría la activación y, peor aún,
#     se perdería el refresco rápido de la tinta de la Sony.
#  2. Las clases que declaran esos métodos nativos.
#  3. Los componentes del manifest, que Android crea por su nombre.
#
# Las clases de Android que se usan por reflexión (ImageView.invalidate de Sony,
# createRfcommSocket) son del sistema: R8 no las renombra, no hace falta regla.

# --- 1 y 2: JNI intacto ---
-keepclasseswithmembernames class * {
    native <methods>;
}
# Se conserva el nombre de la clase y el de sus métodos NATIVOS (los busca el
# código en C), pero el resto de miembros SÍ se renombra: así no quedan a la
# vista nombres como isActivated o tryActivate señalando dónde está la
# comprobación de licencia.
-keepclasseswithmembernames class com.example.newdrawingapp.Activation {
    native <methods>;
}
-keepclasseswithmembernames class com.example.newdrawingapp.SonySystemUtil {
    native <methods>;
}
-keepclasseswithmembernames class com.example.newdrawingapp.SonySystemUtil$* {
    native <methods>;
}

# --- 3: componentes declarados en el manifest ---
-keep class com.example.newdrawingapp.SplashActivity
-keep class com.example.newdrawingapp.MainActivity
-keep class com.example.newdrawingapp.ClientActivity
-keep class com.example.newdrawingapp.ActivationActivity
-keep class com.example.newdrawingapp.PasswordActivity
-keep class com.example.newdrawingapp.SerialVerifyActivity
-keep class com.example.newdrawingapp.BootReceiver

# --- Enums (values/valueOf se resuelven por reflexión) ---
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Vistas personalizadas infladas desde XML ---
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# --- Parcelables ---
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Sin nombres de fichero ni números de línea: información de más para quien
# descompile. (Para depurar una release, volver a poner
# -keepattributes SourceFile,LineNumberTable.)
-renamesourcefileattribute SourceFile
