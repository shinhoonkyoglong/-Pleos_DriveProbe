/*
 * [반경 기능 UI 백업] 2026-11-14
 * 타겟 변경(노인 → 운전 종사자)으로 반경 경고 기능을 비활성화하면서 숨긴 코드.
 * 복구 방법:
 *   1. DriverConditionSettingsScreen.kt 에서 주석 해제
 *   2. DepartureDisplayMapper.kt 에서 radius = null → radius = if (discourages) asSecondary(radius) else radius 복구
 *   3. HomeScreen.kt 에서 subtitle "야간, 도로 조건" → "야간, 반경, 도로 조건" 복구
 */

// ─────────────────────────────────────────────────────────────
// [DriverConditionSettingsScreen.kt] DriverConditionSettingsContent 안에서 제거한 부분
// ─────────────────────────────────────────────────────────────

/*
// 1) DriverConditionSettingsContent 함수 파라미터에서 사용:
//    onIncreaseRadius: () -> Unit,
//    onDecreaseRadius: () -> Unit,
//    onRefreshLocation: () -> Unit,

// 2) 본문에서 제거한 두 Row:
RadiusRow(
    radiusKm = form.radiusKm,
    canDecrease = editable && form.canDecreaseRadius(),
    canIncrease = editable && form.canIncreaseRadius(),
    onDecrease = onDecreaseRadius,
    onIncrease = onIncreaseRadius
)

CenterRow(state = state, enabled = editable, onRefreshLocation = onRefreshLocation)
*/

// ─────────────────────────────────────────────────────────────
// [DriverConditionSettingsScreen.kt] RadiusRow 컴포저블 전체
// ─────────────────────────────────────────────────────────────

/*
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
*/

// ─────────────────────────────────────────────────────────────
// [DriverConditionSettingsScreen.kt] CenterRow 컴포저블 전체
// ─────────────────────────────────────────────────────────────

/*
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
*/

// ─────────────────────────────────────────────────────────────
// [DepartureDisplayMapper.kt] map() 에서 radius 카드 연결 복구
// DepartureSnapshot.Checked 처리 블록 안:
// ─────────────────────────────────────────────────────────────

/*
// 복구할 코드 (현재는 radius = null 로 대체됨):
val radius = radiusCard(snapshot.radius, snapshot.condition, ctx)
DepartureContent(
    ...
    radius = if (discourages) asSecondary(radius) else radius,
    ...
)
*/

// ─────────────────────────────────────────────────────────────
// [HomeScreen.kt] KdNavTile "내 운전 조건" subtitle 원래 값
// ─────────────────────────────────────────────────────────────

/*
// 복구: subtitle = "야간, 반경, 도로 조건"
*/
