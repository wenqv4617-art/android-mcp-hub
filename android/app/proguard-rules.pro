# ---------------------------------------------------------------------------
# 骨架阶段 release 未开启混淆（isMinifyEnabled = false），本文件先占位。
# 将来开启 minify 时，以下规则已经把反射/序列化相关的入口保护好。
# ---------------------------------------------------------------------------

# 保留注解信息，避免 org.json / 反射相关的下游库行为变化
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions

# OkHttp 在 Android 上会尝试加载可选的 BouncyCastle / Conscrypt 实现，
# 缺失是正常的，不要因此报 warning 级噪音
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
