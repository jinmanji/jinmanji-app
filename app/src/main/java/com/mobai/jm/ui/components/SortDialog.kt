package com.mobai.jm.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 排序方式（收藏页 / 历史记录页共用） */
enum class SortField { TIME, TITLE }

data class SortPref(val mode: SortField, val desc: Boolean) {
    val key: String
        get() = (if (mode == SortField.TIME) "time" else "title") + (if (desc) "_desc" else "_asc")

    companion object {
        fun from(key: String): SortPref = when (key) {
            "time_asc" -> SortPref(SortField.TIME, false)
            "title_asc" -> SortPref(SortField.TITLE, false)
            "title_desc" -> SortPref(SortField.TITLE, true)
            else -> SortPref(SortField.TIME, true)
        }
    }
}

@Composable
fun SortDialog(
    current: SortPref,
    onDismiss: () -> Unit,
    onPick: (SortPref) -> Unit,
) {
    val options = listOf(
        "按时间排序 · 新的在前" to SortPref(SortField.TIME, true),
        "按时间排序 · 旧的在前" to SortPref(SortField.TIME, false),
        "按标题排序 · 正序" to SortPref(SortField.TITLE, false),
        "按标题排序 · 倒序" to SortPref(SortField.TITLE, true),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("排序方式") },
        text = {
            Column {
                options.forEach { (label, pref) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(pref) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == pref, onClick = { onPick(pref) })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
