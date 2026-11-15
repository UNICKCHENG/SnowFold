package app.snowfold.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.snowfold.R
import app.snowfold.ui.theme.DesignTokens
import app.snowfold.ui.theme.SnowFoldType
import app.snowfold.ui.theme.appColors
import app.snowfold.ui.theme.appType
import java.time.LocalDate

/**
 * sheet 表单（mobile-layout §6）：一张卡片；行（最小高 52）：标签（正文，宽 96）
 * + 值/输入；分段控件独占一行（标签在上，控件满宽）。
 */
@Composable
fun FormCard(
    modifier: Modifier = Modifier,
    tag: String? = null,
    content: @Composable () -> Unit,
) {
    SfCard(modifier, tag) {
        Column { content() }
    }
}

/** 卡片内一行：左标签（宽 96）+ 右控件；行间 1px sep 分隔（从标签起始处）。 */
@Composable
fun FormRow(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column {
        if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) {
            Column(modifier.fillMaxWidth().padding(DesignTokens.pageMargin)) {
                Text(label, style = SnowFoldType.body.appType(), color = appColors().text)
                Spacer(Modifier.height(DesignTokens.space2))
                content()
            }
        } else {
            Row(
                modifier.fillMaxWidth().heightIn(min = DesignTokens.rowHeight)
                    .padding(horizontal = DesignTokens.pageMargin, vertical = DesignTokens.space1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = SnowFoldType.body.appType(), color = appColors().text, modifier = Modifier.width(96.dp))
                Box(Modifier.weight(1f)) { content() }
            }
        }
        RowDivider(startInset = DesignTokens.pageMargin)
    }
}

/** 卡片内独占一行的分段（标签在上、控件满宽）。 */
@Composable
fun FormSegmentedRow(
    label: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = DesignTokens.pageMargin, vertical = DesignTokens.space3)) {
        Text(label, style = SnowFoldType.body.appType(), color = appColors().text)
        Spacer(Modifier.height(DesignTokens.space2))
        SfSegmented(options, selected, onSelect, testTag = testTag)
    }
}

/** 卡片下方：错误行（warning-triangle 16 + 辅助 neg）与说明（辅助 text2）。 */
@Composable
fun FormFootnote(error: String?, help: String?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = DesignTokens.space2)) {
        if (error != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcon.warningTriangle, contentDescription = null, tint = appColors().neg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(error, style = SnowFoldType.caption.appType(), color = appColors().neg)
            }
        }
        if (help != null) {
            Text(
                help,
                style = SnowFoldType.caption.appType(),
                color = appColors().text2,
                modifier = Modifier.padding(top = if (error != null) DesignTokens.space1 else 0.dp),
            )
        }
    }
}

/** 错误模板取自冻结文案：整体错误为 error_validation。 */
@Composable
fun validationErrorText(): String = stringResource(R.string.error_validation)

/** 说明区：修正历史弹层底部的「修正不会新增快照」。 */
@Composable
fun historyNoteRow(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.history_note),
        style = SnowFoldType.caption.appType(),
        color = appColors().text2,
        modifier = modifier.padding(horizontal = DesignTokens.pageMargin, vertical = DesignTokens.space2),
    )
}
