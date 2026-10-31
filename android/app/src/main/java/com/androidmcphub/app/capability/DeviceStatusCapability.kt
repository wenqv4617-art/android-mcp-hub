package com.androidmcphub.app.capability

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import com.androidmcphub.app.protocol.ToolResultErrorCode
import org.json.JSONObject

/** 设备即时状态（原始值）。null 一律表示"系统没给"，不是 false/0。 */
data class DeviceStatus(
    val screenOn: Boolean?,
    val locked: Boolean?,
    val mediaVolume: Int?,
    val mediaVolumeMax: Int?,
    val powerSaveMode: Boolean?,
)

interface DeviceStatusSource {
    fun read(): DeviceStatus
}

/**
 * 真机实现：全部使用公开 API，不需要任何权限。
 *
 * `isInteractive` 表示"屏幕在交互"（常亮/正在使用），不是"屏幕物理点亮"；
 * `isKeyguardLocked` 表示"锁屏界面还在"。两者组合才是用户能理解的"亮屏且解锁"，
 * 这里分别返回，让调用方自己解释 —— 不在手机上替模型编一个"已解锁"的布尔值。
 */
class AndroidDeviceStatusSource(context: Context) : DeviceStatusSource {

    private val appContext = context.applicationContext

    override fun read(): DeviceStatus {
        val power = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguard = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        return DeviceStatus(
            screenOn = power?.isInteractive,
            locked = keyguard?.isKeyguardLocked,
            mediaVolume = try {
                audio?.getStreamVolume(AudioManager.STREAM_MUSIC)
            } catch (_: Throwable) {
                null
            },
            mediaVolumeMax = try {
                audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            } catch (_: Throwable) {
                null
            },
            powerSaveMode = power?.isPowerSaveMode,
        )
    }
}

/** Phase 3A：`device_get_status`（亮屏/锁屏/媒体音量/省电模式）。 */
class DeviceStatusCapability(
    private val source: DeviceStatusSource,
) : Capability {

    override val name: String = NAME

    override val metadata: CapabilityMetadata = METADATA

    override val allowedArguments: Set<String> = emptySet()

    override fun execute(arguments: JSONObject): CapabilityResult {
        val status = try {
            source.read()
        } catch (t: Throwable) {
            return CapabilityResult.Failure(
                ToolResultErrorCode.TOOL_FAILED,
                "读取设备状态失败（${t.javaClass.simpleName}）",
            )
        }
        val payload = JSONObject()
            .put("screen_on", status.screenOn ?: JSONObject.NULL)
            .put("locked", status.locked ?: JSONObject.NULL)
            .put("media_volume", status.mediaVolume ?: JSONObject.NULL)
            .put("media_volume_max", status.mediaVolumeMax ?: JSONObject.NULL)
            .put("power_save_mode", status.powerSaveMode ?: JSONObject.NULL)
        return CapabilityResult.Success(payload)
    }

    companion object {
        const val NAME = "device_get_status"

        val METADATA: CapabilityMetadata = CapabilityMetadata(
            name = NAME,
            displayName = "设备状态",
            description = "查询屏幕是否亮着/锁屏、媒体音量与省电模式。只读、不需要任何权限。",
            category = CapabilityCategory.Device,
            readOnly = true,
        )
    }
}
