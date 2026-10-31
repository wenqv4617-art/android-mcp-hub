package com.androidmcphub.app.ui.tools

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.androidmcphub.app.ui.model.ToolDescriptor

/**
 * 工具声明的系统权限 ↔ 界面状态。
 *
 * 界面**不猜**权限状态：每个工具声明的权限都真的去系统查一遍。
 * 特殊权限（使用情况访问）用 AppOps 查 —— 它与普通运行时权限是两套机制，
 * 用 `checkSelfPermission` 永远返回"未授予"，会把用户已经开好的权限说成没开。
 */
object ToolPermissions {

    /** 可以当场弹系统授权对话框的运行时权限。 */
    private val RUNTIME_REQUESTABLE = setOf(
        Manifest.permission.CAMERA,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    /** 只能在系统设置里手动开的特殊权限。 */
    private val SPECIAL = setOf(Manifest.permission.PACKAGE_USAGE_STATS)

    fun isGranted(context: Context, permission: String): Boolean = when (permission) {
        Manifest.permission.PACKAGE_USAGE_STATS -> hasUsageStatsAccess(context)
        Manifest.permission.POST_NOTIFICATIONS ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                check(context, permission)
        else -> check(context, permission)
    }

    /** 工具声明的权限是否全部满足。没有声明权限的工具永远视为满足。 */
    fun isGranted(context: Context, tool: ToolDescriptor): Boolean =
        tool.declaredPermissions.all { isGranted(context, it) }

    /**
     * 现在可以**弹窗申请**的权限。已经授予的不再申请；
     * 特殊权限不在这里（必须跳设置）。
     */
    fun requestable(tool: ToolDescriptor, context: Context? = null): List<String> =
        tool.declaredPermissions.filter { permission ->
            permission in RUNTIME_REQUESTABLE &&
                (context == null || !isGranted(context, permission))
        }

    fun hasSpecial(tool: ToolDescriptor): Boolean = tool.declaredPermissions.any { it in SPECIAL }

    /** 打开系统里对应的授权页；返回 false 表示这个工具没有特殊授权页。 */
    fun openSpecialSettings(context: Context, tool: ToolDescriptor): Boolean {
        val intent = when {
            tool.declaredPermissions.contains(Manifest.permission.PACKAGE_USAGE_STATS) ->
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

            else -> return false
        }
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Throwable) {
            // 个别 ROM 没有这个设置页：退到本应用详情页，用户还能从那里找权限。
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun check(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 使用情况访问是 AppOps 特殊权限，必须在 **本应用 uid** 上查。
     * 系统返回的默认模式在部分 ROM 上是 MODE_DEFAULT（不是 MODE_ALLOWED），
     * 这里严格要求 MODE_ALLOWED —— 宁可让按钮再点一次，也不虚报"已授权"。
     */
    fun hasUsageStatsAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        return try {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }
}
