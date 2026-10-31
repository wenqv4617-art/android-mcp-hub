package com.androidmcphub.app.capability

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.androidmcphub.app.MainActivity
import com.androidmcphub.app.R
import com.androidmcphub.app.protocol.ToolResultErrorCode
import org.json.JSONObject

/** 一次设备控制动作的结果（来源层与能力层之间的契约）。 */
data class ControlOutcome(
    val ok: Boolean,
    /** 失败时的 [ToolResultErrorCode]；成功时为 null。 */
    val code: String? = null,
    val message: String? = null,
    val payload: JSONObject = JSONObject(),
) {
    companion object {
        fun success(payload: JSONObject = JSONObject()): ControlOutcome = ControlOutcome(true, payload = payload)
        fun denied(message: String): ControlOutcome =
            ControlOutcome(false, ToolResultErrorCode.PERMISSION_DENIED, message)

        fun unavailable(message: String): ControlOutcome =
            ControlOutcome(false, ToolResultErrorCode.TOOL_UNAVAILABLE, message)

        fun failed(message: String): ControlOutcome =
            ControlOutcome(false, ToolResultErrorCode.TOOL_FAILED, message)
    }
}

/**
 * 第二批次设备控制的来源。
 *
 * 抽成接口的目的与其它来源一致：让参数/权限/错误码的映射能在 JVM 单测里跑。
 * 真机实现在 [AndroidDeviceControlSource]。
 */
interface DeviceControlSource {
    fun setMediaVolume(volume: Int): ControlOutcome
    fun setTorch(enabled: Boolean): ControlOutcome
    fun launchApp(packageName: String): ControlOutcome
    fun openSettings(page: String): ControlOutcome
    fun postNotification(title: String, body: String): ControlOutcome
}

/**
 * 真机实现。所有动作都走**公开 API**，没有任何 shell / 反射 / 无障碍注入。
 *
 * 平台限制（如实返回，不假装成功）：
 *  * 手电筒需要 CAMERA 运行期权限（Android 6+），且系统可能随时关掉它；
 *  * 启动应用受 Android 10+ 后台启动限制影响，`startActivity` 不抛异常
 *    也不代表界面一定弹出来，所以返回值只说"已发出启动请求"；
 *  * 通知需要 POST_NOTIFICATIONS（Android 13+）。
 */
class AndroidDeviceControlSource(context: Context) : DeviceControlSource {

    private val appContext = context.applicationContext

    override fun setMediaVolume(volume: Int): ControlOutcome {
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ControlOutcome.unavailable("系统没有提供音频服务")
        return try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val effective = volume.coerceIn(0, max)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, effective, 0)
            ControlOutcome.success(
                JSONObject()
                    .put("media_volume", effective)
                    .put("media_volume_max", max)
                    .put("requested_volume", volume)
                    .put("clamped", effective != volume),
            )
        } catch (t: Throwable) {
            ControlOutcome.failed("调整媒体音量失败（${t.javaClass.simpleName}）")
        }
    }

    override fun setTorch(enabled: Boolean): ControlOutcome {
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return ControlOutcome.denied("未授予相机权限，无法控制手电筒。请在系统设置中允许相机权限后重试。")
        }
        val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ControlOutcome.unavailable("系统没有提供相机服务")
        return try {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ControlOutcome.unavailable("这台设备没有可用的闪光灯")
            manager.setTorchMode(cameraId, enabled)
            ControlOutcome.success(JSONObject().put("torch_on", enabled))
        } catch (t: Throwable) {
            ControlOutcome.failed("控制手电筒失败（${t.javaClass.simpleName}）")
        }
    }

    override fun launchApp(packageName: String): ControlOutcome {
        val intent = try {
            appContext.packageManager.getLaunchIntentForPackage(packageName)
        } catch (_: Throwable) {
            null
        } ?: return ControlOutcome.unavailable(
            "找不到可启动的应用 $packageName（未安装，或没有桌面入口）。",
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(intent)
            ControlOutcome.success(JSONObject().put("launch_requested", true).put("package_name", packageName))
        } catch (t: Throwable) {
            ControlOutcome.failed("启动应用失败（${t.javaClass.simpleName}）。Android 可能阻止了后台启动界面。")
        }
    }

    override fun openSettings(page: String): ControlOutcome {
        val intent = settingsIntent(page)
            ?: return ControlOutcome.unavailable("不支持跳转到这个设置页：$page")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(intent)
            ControlOutcome.success(JSONObject().put("opened", page))
        } catch (t: Throwable) {
            ControlOutcome.failed("打开设置页失败（${t.javaClass.simpleName}）")
        }
    }

    override fun postNotification(title: String, body: String): ControlOutcome {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return ControlOutcome.denied("未授予通知权限。请在系统设置 → 通知中允许 Android MCP Hub。")
        }
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return ControlOutcome.unavailable("系统没有提供通知服务")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "工具通知",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply { description = "由 MCP 工具主动发出的通知" },
                )
            }
            val pending = PendingIntent.getActivity(
                appContext,
                0,
                Intent(appContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
            ControlOutcome.success(JSONObject().put("posted", true))
        } catch (t: Throwable) {
            ControlOutcome.failed("发送通知失败（${t.javaClass.simpleName}）")
        }
    }

    private fun settingsIntent(page: String): Intent? = when (page) {
        "app_notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
        "app_details" -> Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", appContext.packageName, null),
        )
        "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
        "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
        "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        else -> null
    }

    private companion object {
        const val CHANNEL_ID = "hub_tool_notifications"
        const val NOTIFICATION_ID = 0x4D4350 // "MCP"
    }
}

/** Phase 3E：`device_set_volume`。 */
class SetVolumeCapability(private val source: DeviceControlSource) : Capability {
    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf("volume")

    override fun validate(arguments: JSONObject): String? =
        validated(arguments) { CapabilityArgs.intInRange(arguments, "volume", 0..1000) }.second

    override fun execute(arguments: JSONObject): CapabilityResult {
        val volume = try {
            CapabilityArgs.intInRange(arguments, "volume", 0..1000)
        } catch (problem: ArgumentProblem) {
            return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, problem.message ?: "参数不合法")
        }
        return source.setMediaVolume(volume).toResult()
    }

    companion object {
        const val NAME = "device_set_volume"
        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "调整媒体音量",
            description = "把媒体音量设为指定档位（0–最大值，超出会被夹到边界并如实说明）。只影响媒体流。",
            category = CapabilityCategory.Media,
            arguments = listOf(CapabilityArgument("volume", "integer", true, "目标音量档位（绝对值，0 表示静音）。")),
            readOnly = false,
        )
    }
}

/** Phase 3E：`device_set_flashlight`。需要相机运行期权限。 */
class FlashlightCapability(private val source: DeviceControlSource) : Capability {
    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf("on")

    override fun validate(arguments: JSONObject): String? =
        if (CapabilityArgs.optionalBoolean(arguments, "on") == null) "缺少参数 on（布尔值）" else null

    override fun execute(arguments: JSONObject): CapabilityResult {
        val on = CapabilityArgs.optionalBoolean(arguments, "on")
            ?: return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, "缺少参数 on（布尔值）")
        return source.setTorch(on).toResult()
    }

    companion object {
        const val NAME = "device_set_flashlight"
        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "手电筒",
            description = "打开或关闭手电筒。需要相机权限；没有闪光灯的设备会如实返回不可用。",
            category = CapabilityCategory.Device,
            arguments = listOf(CapabilityArgument("on", "boolean", true, "true 打开，false 关闭。")),
            readOnly = false,
            declaredPermissions = listOf(Manifest.permission.CAMERA),
            requiresRuntimePermission = true,
            permissionHint = "系统设置 → 应用 → Android MCP Hub → 权限 → 相机 → 允许",
        )
    }
}

/** Phase 3E：`device_open_app`。只能启动有桌面入口的应用。 */
class OpenAppCapability(private val source: DeviceControlSource) : Capability {
    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf("package_name")

    override fun validate(arguments: JSONObject): String? = validated(arguments) { parsePackage(arguments) }.second

    override fun execute(arguments: JSONObject): CapabilityResult {
        val packageName = try {
            parsePackage(arguments)
        } catch (problem: ArgumentProblem) {
            return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, problem.message ?: "参数不合法")
        }
        return source.launchApp(packageName).toResult()
    }

    private fun parsePackage(arguments: JSONObject): String {
        val value = CapabilityArgs.requiredString(arguments, "package_name", 128, allowBlank = false)
        if (!PACKAGE_PATTERN.matches(value)) throw ArgumentProblem("参数 package_name 不是合法的包名")
        return value
    }

    companion object {
        const val NAME = "device_open_app"

        /** Android 包名：字母开头，允许字母/数字/下划线/点，至少两段。 */
        val PACKAGE_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "打开应用",
            description = "按包名启动一个已安装、且有桌面入口的应用。受 Android 后台启动限制，不能保证界面一定弹出。",
            category = CapabilityCategory.Device,
            arguments = listOf(CapabilityArgument("package_name", "string", true, "应用包名，例如 com.android.settings。")),
            readOnly = false,
        )
    }
}

/** Phase 3E：`device_open_settings`。只开放白名单里的系统设置页。 */
class OpenSettingsCapability(private val source: DeviceControlSource) : Capability {
    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf("page")

    override fun validate(arguments: JSONObject): String? = validated(arguments) { parsePage(arguments) }.second

    override fun execute(arguments: JSONObject): CapabilityResult {
        val page = try {
            parsePage(arguments)
        } catch (problem: ArgumentProblem) {
            return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, problem.message ?: "参数不合法")
        }
        return source.openSettings(page).toResult()
    }

    private fun parsePage(arguments: JSONObject): String =
        CapabilityArgs.enumeration(arguments, "page", PAGES)

    companion object {
        const val NAME = "device_open_settings"
        val PAGES: Set<String> = setOf(
            "app_notifications", "app_details", "wifi", "bluetooth", "sound", "display", "battery",
        )

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "打开系统设置",
            description = "打开一个白名单内的系统设置页（本应用通知/详情、Wi-Fi、蓝牙、声音、显示、省电）。",
            category = CapabilityCategory.Device,
            arguments = listOf(
                CapabilityArgument(
                    "page", "string", true,
                    "设置页：app_notifications、app_details、wifi、bluetooth、sound、display、battery。",
                ),
            ),
            readOnly = false,
        )
    }
}

/** Phase 3E：`device_post_notification`。只发本应用自己的通知。 */
class PostNotificationCapability(private val source: DeviceControlSource) : Capability {
    override val name: String = NAME
    override val metadata: CapabilityMetadata = METADATA
    override val allowedArguments: Set<String> = setOf("title", "body")

    override fun validate(arguments: JSONObject): String? = validated(arguments) { parse(arguments) }.second

    override fun execute(arguments: JSONObject): CapabilityResult {
        val parsed = try {
            parse(arguments)
        } catch (problem: ArgumentProblem) {
            return CapabilityResult.Failure(ToolResultErrorCode.INVALID_ARGUMENTS, problem.message ?: "参数不合法")
        }
        return source.postNotification(parsed.first, parsed.second).toResult()
    }

    private fun parse(arguments: JSONObject): Pair<String, String> = Pair(
        CapabilityArgs.requiredString(arguments, "title", 100, allowBlank = false),
        CapabilityArgs.requiredString(arguments, "body", 500, allowBlank = false),
    )

    companion object {
        const val NAME = "device_post_notification"

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "发通知",
            description = "用 Android MCP Hub 自己的通知渠道发一条通知（标题 + 正文）。不会读取或修改其它应用的通知。",
            category = CapabilityCategory.Notification,
            arguments = listOf(
                CapabilityArgument("title", "string", true, "通知标题，最多 100 字。"),
                CapabilityArgument("body", "string", true, "通知正文，最多 500 字。"),
            ),
            readOnly = false,
            declaredPermissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
            requiresRuntimePermission = true,
            permissionHint = "系统设置 → 应用 → Android MCP Hub → 通知 → 允许通知",
        )
    }
}

/** 把来源层的 [ControlOutcome] 收口成分发器要的 [CapabilityResult]。 */
internal fun ControlOutcome.toResult(): CapabilityResult =
    if (ok) {
        CapabilityResult.Success(payload)
    } else {
        CapabilityResult.Failure(code ?: ToolResultErrorCode.TOOL_FAILED, message ?: "操作失败")
    }
