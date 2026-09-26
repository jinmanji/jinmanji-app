package com.mobai.jm.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 通用页码条：上一页 / 「第 X 页」/ 下一页
 * 点击中间页码可弹出输入框快速跳页。
 */
@Composable
fun PageBar(
    page: Int,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    pageCount: Int? = null,
    hasPrev: Boolean = page > 1,
    hasNext: Boolean = true,
) {
    var showJump by remember { mutableStateOf(false) }
    var jumpText by remember { mutableStateOf("") }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { if (hasPrev) onPageChange(page - 1) }, enabled = hasPrev) {
            Text("上一页")
        }
        TextButton(onClick = {
            jumpText = page.toString()
            showJump = true
        }) {
            Text(
                text = if (pageCount != null) "第 $page / $pageCount 页" else "第 $page 页",
                color = MaterialTheme.colorScheme.primary,
            )
        }
        TextButton(onClick = { if (hasNext) onPageChange(page + 1) }, enabled = hasNext) {
            Text("下一页")
        }
    }

    if (showJump) {
        AlertDialog(
            onDismissRequest = { showJump = false },
            title = { Text("跳转到页码") },
            text = {
                OutlinedTextField(
                    value = jumpText,
                    onValueChange = { v -> jumpText = v.filter { it.isDigit() }.take(4) },
                    singleLine = true,
                    label = { Text(if (pageCount != null) "1 ~ $pageCount" else "页码") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = jumpText.toIntOrNull()
                    if (n != null && n >= 1 && (pageCount == null || n <= pageCount)) {
                        showJump = false
                        if (n != page) onPageChange(n)
                    }
                }) { Text("跳转") }
            },
            dismissButton = {
                TextButton(onClick = { showJump = false }) { Text("取消") }
            },
        )
    }
}
