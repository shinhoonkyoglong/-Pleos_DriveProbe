package ai.pleos.playground.sample.vehicle.ui.screen

import ai.pleos.playground.sample.vehicle.departure.DriverConditionDefaults
import ai.pleos.playground.sample.vehicle.departure.DriverConditionFormError
import ai.pleos.playground.sample.vehicle.departure.DriverConditionSettingsState
import ai.pleos.playground.sample.vehicle.departure.DriverConditionSettingsViewModel
import ai.pleos.playground.sample.vehicle.departure.LocationStatus
import ai.pleos.playground.sample.vehicle.departure.SaveResult
import ai.pleos.playground.sample.vehicle.departure.SaveStatus
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pleos.departure.model.DrivingRestriction
import java.util.Locale

// 고령 운전자 화면 기준: 본문 26sp 이상, 터치 영역 72dp 이상, 상태는 색 + 기호 + 문구로 함께 전달한다.
private val BodySize = 26.sp
private val TitleSize = 34.sp
private val MinTouch = 72.dp

// 고대비 안내 배경(글자는 모두 진한 색). 1번 테마 색은 바꾸지 않고 이 화면에서만 쓴다
private val InfoContainer = Color(0xFFE3EAFF)
private val OkContainer = Color(0xFFDFF3E8)
private val WarnContainer = Color(0xFFFFF0C2)
private val ErrorContainer = Color(0xFFFFDDDA)
private val StrongInk = Color(0xFF0B0D12)
private val ErrorInk = Color(0xFF8C1D18)

/**
 * 운전자 조건 설정 화면(2번 '출발 전', 작업 #17). 1번 API 탐색 화면 머리의 [운전 조건 설정] 버튼으로 들어온다.
 * 들어올 때마다 저장된 조건(없으면 기본값 + 현재 위치)으로 다시 채운다.
 */
@Composable
fun DriverConditionSettingsScreen(
    viewModel: DriverConditionSettingsViewModel,
    onBack: () -> Unit
) {
    LaunchedEffect(Unit) { viewModel.open() }
    BackHandler(onBack = onBack)
    val state = viewModel.state.collectAsStateWithLifecycle().value

    DriverConditionSettingsContent(
        state = state,
        onBack = onBack,
        onNoNightDrivingChange = viewModel::setNoNightDriving,
        onPreferTollFreeChange = viewModel::setPreferTollFree,
        onAvoidHighwayChange = viewModel::setAvoidHighway,
        onIncreaseRadius = viewModel::increaseRadius,
        onDecreaseRadius = viewModel::decreaseRadius,
        onRefreshLocation = viewModel::refreshLocation,
        onSave = viewModel::save
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
    onSave: () -> Unit
) {
    val editable = !state.editingLocked && !state.loading
    val form = state.form

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Start))
            .padding(start = AaosSystemRailInset),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp)
                .widthIn(max = 1100.dp)
                .testTag("driver-condition-settings"),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.heightIn(min = MinTouch),
                    border = BorderStroke(2.dp, StrongInk)
                ) {
                    Text("← 돌아가기", fontSize = BodySize, color = StrongInk)
                }
                Text(
                    text = "운전 조건 설정",
                    fontSize = TitleSize,
                    fontWeight = FontWeight.Bold,
                    color = StrongInk
                )
            }

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
                Text(
                    text = "! ${errorText(error)}",
                    fontSize = BodySize,
                    fontWeight = FontWeight.Bold,
                    color = ErrorInk
                )
            }

            Button(
                onClick = onSave,
                enabled = editable && !state.saving && state.locationStatus != LocationStatus.LOADING,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 88.dp)
                    .testTag("driver-condition-save")
            ) {
                Text(
                    text = if (state.saving) "저장하는 중…" else "저장",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** 화면 맨 위 안내 한 줄. 여러 상태가 겹치면 가장 중요한 것 하나만 보여 준다(한 화면 한 메시지). */
@Composable
private fun StatusBanner(state: DriverConditionSettingsState) {
    val (symbol, text, container) = when {
        state.loading -> Triple("…", "저장된 조건을 불러오는 중이에요", InfoContainer)
        state.editingLocked && state.drivingRestriction == DrivingRestriction.RESTRICTED ->
            Triple("⛔", "주행 중에는 설정을 바꿀 수 없어요", WarnContainer)
        state.editingLocked -> Triple("⛔", "차량 상태를 확인하는 중이라 아직 바꿀 수 없어요", WarnContainer)
        state.saveResult == SaveResult.FAILED -> Triple("✕", "저장하지 못했어요. [저장]을 다시 눌러 주세요", ErrorContainer)
        state.saveResult == SaveResult.SAVED -> Triple("✔", "저장했어요. 이제 이 조건으로 확인해요", OkContainer)
        state.loadFailed -> Triple("!", "저장된 조건을 읽지 못해 기본값을 보여 드려요. 저장하면 이 값으로 바뀌어요", ErrorContainer)
        state.saveStatus == SaveStatus.NOT_SAVED ->
            Triple("ⓘ", "아직 저장 안 됨 — 기본값을 채워 두었어요. [저장]을 눌러야 적용돼요", InfoContainer)
        state.saveStatus == SaveStatus.MODIFIED -> Triple("✎", "바꾼 내용이 아직 저장 안 됨", WarnContainer)
        else -> Triple("✔", "저장된 조건이에요", OkContainer)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("driver-condition-status"),
        shape = RoundedCornerShape(16.dp),
        color = container,
        border = BorderStroke(2.dp, StrongInk)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(symbol, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = StrongInk)
            Text(text, fontSize = BodySize, fontWeight = FontWeight.Bold, color = StrongInk)
        }
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 104.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = StrongInk)
                Text(description, fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(24.dp))
            Text(
                text = if (checked) "켜짐" else "꺼짐",
                fontSize = BodySize,
                fontWeight = FontWeight.Bold,
                color = StrongInk
            )
            Spacer(Modifier.width(24.dp))
            // 행 전체가 toggleable 이라 스위치 자체 클릭은 받지 않는다(onCheckedChange = null)
            Switch(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.scale(1.5f))
            Spacer(Modifier.width(16.dp))
        }
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
    SectionCard {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("허용 반경", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = StrongInk)
            Text(
                "중심에서 이 거리 안에서만 운전해요 (${formatKm(DriverConditionDefaults.RADIUS_STEP_KM)} km씩, 최대 ${formatKm(DriverConditionDefaults.MAX_RADIUS_KM)} km)",
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        StepButton(label = "−", description = "반경 줄이기", enabled = canDecrease, onClick = onDecrease)
        Text(
            text = radiusKm?.let { "${formatKm(it)} km" } ?: "없음",
            modifier = Modifier.widthIn(min = 180.dp).testTag("driver-condition-radius"),
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = StrongInk,
            textAlign = TextAlign.Center
        )
        StepButton(label = "+", description = "반경 늘리기", enabled = canIncrease, onClick = onIncrease)
    }
}

@Composable
private fun CenterRow(state: DriverConditionSettingsState, enabled: Boolean, onRefreshLocation: () -> Unit) {
    val form = state.form
    SectionCard {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("반경 중심", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = StrongInk)
            when {
                state.locationStatus == LocationStatus.LOADING ->
                    Text("현재 위치를 읽는 중이에요…", fontSize = BodySize, color = StrongInk)
                form.hasCenter -> {
                    val place = state.centerAddress?.let { "현재 위치: $it" } ?: "저장된 중심"
                    Text(place, fontSize = BodySize, fontWeight = FontWeight.Bold, color = StrongInk)
                    Text(
                        String.format(Locale.ROOT, "위도 %.4f, 경도 %.4f", form.centerLatitude, form.centerLongitude),
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.locationStatus == LocationStatus.FAILED) {
                        Text("! 지금 위치를 읽지 못해 원래 중심을 그대로 둬요", fontSize = 22.sp, color = ErrorInk)
                    }
                }
                state.locationStatus == LocationStatus.FAILED ->
                    Text(
                        "! 현재 위치를 읽지 못했어요 — 반경 확인은 중심이 있어야 동작해요",
                        fontSize = BodySize,
                        fontWeight = FontWeight.Bold,
                        color = ErrorInk,
                        modifier = Modifier.testTag("driver-condition-location-failed")
                    )
                else ->
                    Text("! 반경 중심이 없어요 — 반경 확인은 중심이 있어야 동작해요", fontSize = BodySize, color = ErrorInk)
            }
        }
        Spacer(Modifier.width(16.dp))
        OutlinedButton(
            onClick = onRefreshLocation,
            enabled = enabled && state.locationStatus != LocationStatus.LOADING,
            modifier = Modifier.heightIn(min = MinTouch),
            border = BorderStroke(2.dp, StrongInk)
        ) {
            val label = when {
                !form.hasCenter && state.locationStatus == LocationStatus.FAILED -> "다시 시도"
                form.hasCenter -> "지금 위치로 바꾸기"
                else -> "지금 위치로 정하기"
            }
            Text(label, fontSize = BodySize, color = StrongInk)
        }
    }
}

@Composable
private fun SectionCard(content: @Composable RowScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun StepButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(88.dp).semantics { contentDescription = description },
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(2.dp, if (enabled) StrongInk else MaterialTheme.colorScheme.onSurfaceVariant),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(label, fontSize = 40.sp, fontWeight = FontWeight.Bold)
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
