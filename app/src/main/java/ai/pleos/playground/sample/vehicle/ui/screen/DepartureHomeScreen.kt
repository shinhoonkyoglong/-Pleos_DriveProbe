package ai.pleos.playground.sample.vehicle.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleos.departure.ui.DepartureHomeCard
import com.pleos.departure.ui.DepartureScreen
import com.pleos.departure.ui.state.DepartureScreenModel

/*
 * 앱 첫 화면(작업 #25). 팀 UI 구성 제안(2번 저장소 docs/pleos/ui-structure-proposal.md) 중 2번 몫만 넣었다:
 * '오늘 운전해도 되나요?' 카드 1장 + 큰 버튼 2개. 다른 파트 카드는 각 파트가 정해서 추가한다(자리를 미리 만들지 않음).
 * 고령 운전자 기준: 버튼 높이 96dp, 글자 28sp 이상, 어두운 바탕(2번 화면과 같은 고대비).
 */

private val HomeBackground = Color(0xFF000000)
private val ButtonBlue = Color(0xFF90CAF9)

@Composable
fun DepartureHomeScreen(
    model: DepartureScreenModel,
    onOpenDepartureDetail: () -> Unit,
    onOpenDriverCondition: () -> Unit,
    onOpenApiExplorer: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HomeBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(32.dp)
            .testTag("home"),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("DriveProbe", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        DepartureHomeCard(model = model, onOpen = onOpenDepartureDetail, modifier = Modifier.testTag("home-departure-card"))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onOpenDriverCondition,
                colors = ButtonDefaults.buttonColors(containerColor = ButtonBlue, contentColor = Color.Black),
                modifier = Modifier.weight(1f).heightIn(min = 96.dp).testTag("home-driver-condition"),
            ) { Text("운전 조건 설정", fontSize = 30.sp, fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = onOpenApiExplorer,
                modifier = Modifier.weight(1f).heightIn(min = 96.dp).testTag("home-api-explorer"),
            ) { Text("API 탐색기 (개발용)", fontSize = 28.sp, color = Color.White) }
        }
    }
}

/** 홈 카드를 누르면 여는 '출발 전' 확인 전체 화면. 같은 [model]을 쓴다 */
@Composable
fun DepartureDetailScreen(
    model: DepartureScreenModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HomeBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.padding(start = 32.dp, top = 16.dp).heightIn(min = 72.dp).testTag("departure-back"),
        ) { Text("← 홈으로", fontSize = 28.sp, color = Color.White) }
        DepartureScreen(model = model, modifier = Modifier.weight(1f))
    }
}
