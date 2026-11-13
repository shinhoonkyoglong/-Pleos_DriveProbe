package ai.pleos.playground.sample.vehicle.ui.screen

import ai.pleos.playground.sample.vehicle.departure.DriverConditionDefaults
import ai.pleos.playground.sample.vehicle.departure.DriverConditionFormError
import ai.pleos.playground.sample.vehicle.departure.DriverConditionSettingsState
import ai.pleos.playground.sample.vehicle.departure.DriverConditionSettingsViewModel
import ai.pleos.playground.sample.vehicle.departure.LocationStatus
import ai.pleos.playground.sample.vehicle.departure.SaveResult
import ai.pleos.playground.sample.vehicle.departure.SaveStatus
import ai.pleos.playground.sample.vehicle.ui.kd.Kd
import ai.pleos.playground.sample.vehicle.ui.kd.KdButton
import ai.pleos.playground.sample.vehicle.ui.kd.KdButtonStyle
import ai.pleos.playground.sample.vehicle.ui.kd.KdCard
import ai.pleos.playground.sample.vehicle.ui.kd.KdScreen
import ai.pleos.playground.sample.vehicle.ui.kd.KdTheme
import ai.pleos.playground.sample.vehicle.ui.kd.KdTone
import ai.pleos.playground.sample.vehicle.ui.kd.KdToneDot
import ai.pleos.playground.sample.vehicle.ui.kd.KdTopBar
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pleos.departure.model.DrivingRestriction
import java.util.Locale

/*
 * 화면 모양은 '계속운전' 디자인 시스템(ui/kd/KdTheme.kt)을 그대로 쓴다(2026-11-13 정리).
 * - 굵은 글씨(Bold) 없음: 항목 제목만 다른 화면 카드 제목과 같은 Kd.Title, 나머지는 보통 굵기
 * - 테두리 대신 면 색 차이로 구분. 색 테두리는 맨 위 상태 안내처럼 '상태를 알리는 카드'에만 옅게
 * - 상태는 색 + 기호(✓ ! ? i) + 글자로 함께 보여 준다(KdToneDot)
 * - 개발자 도구(1번 밝은 테마) 안에서 열려도 같은 모양이 되도록 KdTheme 으로 감싼다(다크/라이트 설정을 따른다)
 */

/**
 * 운전자 조건 설정 화면(2번 '출발 전', 작업 #17). '계속운전' 홈의 [내 운전 조건]과 개발자 도구에서 들어온다.
 * 들어올 때마다 저장된 조건(없으면 기본값 + 현재 위치)으로 다시 채운다.
 */
@Composable
fun DriverConditionSettingsScreen(
    viewModel: DriverConditionSettingsViewModel,
    onBack: () -> Unit
) {
    // 새로 들어왔을 때만 다시 채운다. Activity 재생성(주간/야간 모드 전환·글자 크기 변경 등 설정 변경) 때는 rememberSaveable 이 true 로 복원되어
    // ViewModel 에 남은 저장 전 입력을 지우지 않는다. 화면을 나갔다 다시 들어오면 false 로 새로 시작한다.
    var opened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.open(reload = !opened)
        opened = true
    }
    BackHandler(onBack = onBack)
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val naviSettingsFailed = viewModel.naviSettingsFailed.collectAsStateWithLifecycle().value

    DriverConditionSettingsContent(
        state = state,
        onBack = onBack,
        onNoNightDrivingChange = viewModel::setNoNightDriving,
        onPreferTollFreeChange = viewModel::setPreferTollFree,
        onAvoidHighwayChange = viewModel::setAvoidHighway,
        onIncreaseRadius = viewModel::increaseRadius,
        onDecreaseRadius = viewModel::decreaseRadius,
        onRefreshLocation = viewModel::refreshLocation,
        onSave = viewModel::save,
        onOpenNaviSettings = viewModel::openNaviSettings,
        naviSettingsFailed = naviSettingsFailed
    )
}

@Composable
fun DriverConditionSettingsContent(
    state: DriverConditionSettingsState,
    onBack: () -> Unit,
    onNoNightDrivingChange: (Boolean) -> Unit,
    onPreferTollFreeChange: (Boolean) -> Unit,
    onAvoidHighwayChange: (Boolean) -> Unit,
    onIncreaseRadius: () -> Unit,
    onDecreaseRadius: () -> Unit,
    onRefreshLocation: () -> Unit,
    onSave: () -> Unit,
    onOpenNaviSettings: () -> Unit = {},
    naviSettingsFailed: Boolean = false
) {
    val editable = !state.editingLocked && !state.loading
    val form = state.form

    KdTheme {
        KdScreen(scrollable = true, testTag = "driver-condition-settings") {
            KdTopBar("운전 조건 설정", onBack = onBack, backLabel = "← 돌아가기")

            StatusBanner(state)

            ToggleRow(
                title = "야간 운전 금지",
                description = "해가 진 뒤에는 운전하지 않아요. 출발 마감 시각을 알려 드려요",
                checked = form.noNightDriving,
                enabled = editable,
                onChange = onNoNightDrivingChange
            )
            ToggleRow(
                title = "무료도로 선호",
                description = "유료도로를 피하는 길로 안내해요",
                checked = form.preferTollFree,
                enabled = editable,
                onChange = onPreferTollFreeChange
            )
            // 2번 작업 #28: 앱이 내비 설정을 바꿀 수 없어(SDK 없음) 전역 '유료도로 우회'를 사용자가 한 번 켜 두도록 안내한다
            if (form.preferTollFree && editable) {
                NaviTollSettingHint(failed = naviSettingsFailed, onOpen = onOpenNaviSettings)
            }
            ToggleRow(
                title = "고속도로 회피",
                description = "고속도로를 되도록 피해요(내비 기능 한계로 완전히 피하지는 못할 수 있어요)",
                checked = form.avoidHighway,
                enabled = editable,
                onChange = onAvoidHighwayChange
            )

            RadiusRow(
                radiusKm = form.radiusKm,
                canDecrease = editable && form.canDecreaseRadius(),
                canIncrease = editable && form.canIncreaseRadius(),
                onDecrease = onDecreaseRadius,
                onIncrease = onIncreaseRadius
            )

            CenterRow(state = state, enabled = editable, onRefreshLocation = onRefreshLocation)

            state.errors.forEach { error ->
                Text(text = "! ${errorText(error)}", style = Kd.Body, color = Kd.Danger)
            }

            KdButton(
                text = if (state.saving) "저장하는 중…" else "저장",
                onClick = onSave,
                enabled = editable && !state.saving && state.locationStatus != LocationStatus.LOADING,
                modifier = Modifier.fillMaxWidth().testTag("driver-condition-save"),
            )
        }
    }
}

/** 화면 맨 위 안내 한 줄. 여러 상태가 겹치면 가장 중요한 것 하나만 보여 준다(한 화면 한 메시지). */
@Composable
private fun StatusBanner(state: DriverConditionSettingsState) {
    val (tone, text) = when {
        state.loading -> KdTone.INFO to "저장된 조건을 불러오는 중이에요"
        state.editingLocked && state.drivingRestriction == DrivingRestriction.RESTRICTED ->
            KdTone.CAUTION to "주행 중에는 설정을 바꿀 수 없어요"
        state.editingLocked -> KdTone.CAUTION to "차량 상태를 확인하는 중이라 아직 바꿀 수 없어요"
        state.saveResult == SaveResult.FAILED -> KdTone.DANGER to "저장하지 못했어요. [저장]을 다시 눌러 주세요"
        state.saveResult == SaveResult.SAVED -> KdTone.GOOD to "저장했어요. 이제 이 조건으로 확인해요"
        state.loadFailed -> KdTone.DANGER to "저장된 조건을 읽지 못해 기본값을 보여 드려요. 저장하면 이 값으로 바뀌어요"
        state.saveStatus == SaveStatus.NOT_SAVED ->
            KdTone.INFO to "아직 저장 안 됨 — 기본값을 채워 두었어요. [저장]을 눌러야 적용돼요"
        state.saveStatus == SaveStatus.MODIFIED -> KdTone.CAUTION to "바꾼 내용이 아직 저장 안 됨"
        else -> KdTone.GOOD to "저장된 조건이에요"
    }
    KdCard(
        modifier = Modifier.fillMaxWidth().testTag("driver-condition-status"),
        borderColor = tone.color,
        contentPadding = 18.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            KdToneDot(tone)
            Text(text, style = Kd.Body)
        }
    }
}

/** 설정 한 줄의 면(테두리 없음, 면 색으로 구분) */
@Composable
private fun SettingRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .clip(Kd.CardShape)
            .background(Kd.Surface)
            .padding(horizontal = 32.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        content = content
    )
}

/** 줄 왼쪽의 제목 + 설명 */
@Composable
private fun RowScope.RowLabel(title: String, description: String) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = Kd.Title)
        Text(description, style = Kd.BodyMuted)
    }
}

/** 행 전체가 눌리는 큰 켜기/끄기. 상태는 스위치 색과 함께 "켜짐/꺼짐" 글자로도 보여 준다. */
@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    SettingRow(
        modifier = Modifier
            .clip(Kd.CardShape)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
    ) {
        RowLabel(title, description)
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (checked) "켜짐" else "꺼짐",
            style = Kd.Body,
            color = if (checked) Kd.Ink else Kd.InkMuted,
        )
        Spacer(Modifier.width(8.dp))
        // 행 전체가 toggleable 이라 스위치 자체 클릭은 받지 않는다(onCheckedChange = null)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.scale(1.5f))
        Spacer(Modifier.width(16.dp))
    }
}

@Composable
private fun RadiusRow(
    radiusKm: Double?,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    SettingRow {
        RowLabel(
            "허용 반경",
            "중심에서 이 거리 안에서만 운전해요 (${formatKm(DriverConditionDefaults.RADIUS_STEP_KM)} km씩, 최대 ${formatKm(DriverConditionDefaults.MAX_RADIUS_KM)} km)"
        )
        StepButton(label = "−", description = "반경 줄이기", enabled = canDecrease, onClick = onDecrease)
        Text(
            text = radiusKm?.let { "${formatKm(it)} km" } ?: "없음",
            modifier = Modifier.widthIn(min = 180.dp).testTag("driver-condition-radius"),
            style = Kd.Numeral.copy(fontSize = 44.sp, lineHeight = 52.sp),
            textAlign = TextAlign.Center
        )
        StepButton(label = "+", description = "반경 늘리기", enabled = canIncrease, onClick = onIncrease)
    }
}

@Composable
private fun CenterRow(state: DriverConditionSettingsState, enabled: Boolean, onRefreshLocation: () -> Unit) {
    val form = state.form
    SettingRow {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("반경 중심", style = Kd.Title)
            when {
                state.locationStatus == LocationStatus.LOADING ->
                    Text("현재 위치를 읽는 중이에요…", style = Kd.Body)
                form.hasCenter -> {
                    val place = state.centerAddress?.let { "현재 위치: $it" } ?: "저장된 중심"
                    Text(place, style = Kd.Body)
                    Text(
                        String.format(Locale.ROOT, "위도 %.4f, 경도 %.4f", form.centerLatitude, form.centerLongitude),
                        style = Kd.BodyMuted
                    )
                    if (state.locationStatus == LocationStatus.FAILED) {
                        Text("! 지금 위치를 읽지 못해 원래 중심을 그대로 둬요", style = Kd.Body, color = Kd.Danger)
                    }
                }
                state.locationStatus == LocationStatus.FAILED ->
                    Text(
                        "! 현재 위치를 읽지 못했어요 — 반경 확인은 중심이 있어야 동작해요",
                        style = Kd.Body,
                        color = Kd.Danger,
                        modifier = Modifier.testTag("driver-condition-location-failed")
                    )
                else ->
                    Text("! 반경 중심이 없어요 — 반경 확인은 중심이 있어야 동작해요", style = Kd.Body, color = Kd.Danger)
            }
        }
        val label = when {
            !form.hasCenter && state.locationStatus == LocationStatus.FAILED -> "다시 시도"
            form.hasCenter -> "지금 위치로 바꾸기"
            else -> "지금 위치로 정하기"
        }
        KdButton(
            text = label,
            onClick = onRefreshLocation,
            enabled = enabled && state.locationStatus != LocationStatus.LOADING,
            style = KdButtonStyle.SECONDARY,
            minHeight = 80.dp,
        )
    }
}

/** 무료도로 선호가 켜져 있을 때: 내비 전역 '유료도로 우회'를 한 번 켜 두라는 안내 + [내비 설정 열기] */
@Composable
private fun NaviTollSettingHint(failed: Boolean, onOpen: () -> Unit) {
    KdCard(
        modifier = Modifier.fillMaxWidth().testTag("navi-toll-setting-hint"),
        background = Kd.AccentContainer,
        contentPadding = 18.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            KdToneDot(KdTone.INFO)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("내비 설정에서 '유료도로 우회'도 한 번 켜 두세요. 다음 경로부터 무료도로로 안내해요", style = Kd.Body)
                if (failed) {
                    Text(
                        "! 내비 설정을 열지 못했어요. 차량 설정 → 내비게이션 → 경로에서 '유료도로 우회'를 켜 주세요",
                        style = Kd.Body,
                        color = Kd.Danger
                    )
                }
            }
            KdButton(
                text = "내비 설정 열기",
                onClick = onOpen,
                style = KdButtonStyle.SECONDARY,
                minHeight = 80.dp,
                modifier = Modifier.testTag("open-navi-settings"),
            )
        }
    }
}

/** 반경 −/+ 버튼: 면 색 정사각 버튼(테두리 없음) */
@Composable
private fun StepButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.35f
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(Kd.ButtonShape)
            .background(Kd.SurfaceRaised.copy(alpha = alpha))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 44.sp, lineHeight = 48.sp, color = Kd.Ink.copy(alpha = alpha))
    }
}

private fun errorText(error: DriverConditionFormError): String = when (error) {
    DriverConditionFormError.RADIUS_MISSING -> "허용 반경을 정해 주세요"
    DriverConditionFormError.RADIUS_NOT_POSITIVE -> "허용 반경은 0 km보다 커야 해요"
    DriverConditionFormError.RADIUS_TOO_LARGE ->
        "허용 반경은 ${formatKm(DriverConditionDefaults.MAX_RADIUS_KM)} km 이하로 정해 주세요"
    DriverConditionFormError.CENTER_INCOMPLETE -> "반경 중심 좌표가 반쪽만 있어요. [지금 위치로 바꾸기]로 다시 정해 주세요"
    DriverConditionFormError.CENTER_OUT_OF_RANGE -> "반경 중심 좌표가 올바르지 않아요. [지금 위치로 바꾸기]로 다시 정해 주세요"
}

/** 정수면 "50", 아니면 소수 첫째 자리까지 "12.3" */
private fun formatKm(km: Double): String =
    if (km % 1.0 == 0.0) km.toLong().toString() else String.format(Locale.ROOT, "%.1f", km)
