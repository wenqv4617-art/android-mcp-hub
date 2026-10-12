package com.androidmcphub.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.androidmcphub.app.ui.theme.spacing

/**
 * 统一样式的单行输入框。
 *
 * 改版前 `OutlinedTextField` 在 `HubScreen.kt` 里出现了 10 次，每次都自带
 * `fillMaxWidth().padding(top = 8.dp)`；形状、内边距、辅助文案的排版各写各的。
 */
@Composable
fun HubTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder != null) {
            { Text(placeholder) }
        } else {
            null
        },
        supportingText = if (supportingText != null) {
            { Text(supportingText) }
        } else {
            null
        },
        singleLine = singleLine,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        trailingIcon = trailing,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = MaterialTheme.spacing.sm),
    )
}

/**
 * 数字输入框。
 *
 * 值仍然是**字符串**：表单里用户会一边输一边改，中途必然出现 "1"、"1."、"-" 这类
 * 半成品，直接用数字类型做双向绑定会把输入吃掉。解析统一发生在「保存/连接」时。
 */
@Composable
fun HubNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    decimal: Boolean = false,
    supportingText: String? = null,
) {
    HubTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        supportingText = supportingText,
        keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
        modifier = modifier,
    )
}

/**
 * 设备令牌输入框：默认打码，可临时明文查看，并可一键清除已落盘的令牌。
 *
 * 明文只活在**内存里**（[visible] 由 ViewModel 持有），不会因为"显示过一次"
 * 就被写进任何地方。
 */
@Composable
fun TokenField(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    HubTextField(
        value = value,
        onValueChange = onValueChange,
        label = "设备令牌",
        supportingText = supportingText,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailing = {
            TextButton(onClick = onToggleVisible) {
                Text(if (visible) "隐藏" else "显示")
            }
        },
        modifier = modifier,
    )
    TextButton(onClick = onClear) {
        Text("清除已保存的令牌")
    }
}
