#!/usr/bin/env bash
# 시연영상 전체 자동 촬영(Git Bash). 시나리오: docs/demo-video.md
#   1) powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Prepare-Demo.ps1   (매 테이크 준비)
#   2) bash scripts/record-demo.sh [이름]                                                (기본 이름 take)
# 결과: artifacts/demo-video/<이름>.webm(에뮬레이터 화면 녹화, 30fps) + <이름>-scenes.txt(장면 시작 시각, 자막용)
# 촬영 중에는 에뮬레이터 창을 건드리지 않는다. 좌표는 2560x1440 Pleos Connect 화면 기준.
set -u
cd "$(dirname "$0")/.."
NAME="${1:-take}"
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
P=ai.pleos.playground.sample.vehicle.diagnostic
mkdir -p artifacts/demo-video
VIDEO="$(cygpath -w "$PWD/artifacts/demo-video")\\$NAME.webm"
MARK="artifacts/demo-video/$NAME-scenes.txt"
: > "$MARK"
tap() { "$ADB" shell input tap "$1" "$2"; }
swipe_up() { "$ADB" shell input swipe 1700 1050 1700 450 900; }
START=$(date +%s)
mark() { local now; now=$(date +%s); printf '%02d:%02d  %s\n' $(((now-START)/60)) $(((now-START)%60)) "$1" | tee -a "$MARK"; }

# 이전 테이크의 주행·반응 스크립트가 남아 있거나 차가 움직이고 있으면 시작하지 않는다(중단한 테이크의 자식 프로세스가 계속 주행한 적이 있음)
LEFT=$(powershell -NoProfile -Command "@(Get-CimInstance Win32_Process | Where-Object { \$_.Name -eq 'powershell.exe' -and \$_.CommandLine -match 'Prepare-Demo\.ps1 -(Drive|React)|drive-sim\.ps1' }).Count" | tr -d '\r')
if [ "$LEFT" != "0" ]; then echo "이전 주행/반응 스크립트가 아직 실행 중입니다($LEFT개). 종료한 뒤 다시 실행하세요."; exit 1; fi
PORT=$("$ADB" forward tcp:0 tcp:8088 | tr -d '\r')
STATE=$(curl -s -m5 "http://127.0.0.1:$PORT/vehicle/state" | python -c "import json,sys;d=json.load(sys.stdin);print(((d['gear']['actual'] or {}).get('value')), d['controls']['speed_kph'])" 2>/dev/null)
"$ADB" forward --remove "tcp:$PORT" >/dev/null 2>&1
if [ "$STATE" != "Park 0" ]; then echo "Virtual Console이 P·속도 0이 아닙니다(현재: $STATE). 정차한 뒤 다시 실행하세요."; exit 1; fi

"$ADB" shell am start --user 10 -n $P/ai.pleos.playground.sample.vehicle.MainActivity >/dev/null
sleep 4
"$ADB" emu screenrecord start --time-limit 900 --fps 30 "$VIDEO" >/dev/null
START=$(date +%s)
mark "1 오프닝: 홈"; sleep 5

mark "2 운전 조건 설정"; tap 1890 1180; sleep 3
tap 2405 513; sleep 2          # 무료도로 선호 끄기(경로가 유료 고속도로)
tap 1693 763; sleep 2          # 저장
tap 990 162; sleep 2           # 돌아가기
mark "3 출발 판단 카드(출발 마감 시각)"; sleep 5

mark "3-1 출발 전 반응 체크 안내"; tap 1085 1180; sleep 5
mark "3-1 반응 체크 진행(자동 브레이크)"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Prepare-Demo.ps1 -React -TapStart >/dev/null 2>&1
sleep 2; mark "3-1 반응 체크 결과"; sleep 5
tap 2100 1220; sleep 3         # 확인 → 홈

mark "4 주행 도움 켜기"; tap 1490 1180; sleep 3
tap 1302 462; sleep 2          # 주행 도움 켜기
tap 1705 643; sleep 4          # 마이크: 앱 사용 중에만 허용

mark "5 휴게소 설정"; tap 1693 835; sleep 3
tap 1693 367; sleep 10         # 현재 경로의 휴게소 불러오기
tap 1100 618; sleep 3          # 기흥휴게소(부산방향)
tap 917 918; sleep 2           # 직접 확인 체크
tap 1693 1016                  # 확인 저장
sleep 18; mark "5 휴게소 확인됨"; sleep 4
tap 997 162; sleep 2; tap 955 162; sleep 3   # 홈으로

mark "6 출발(주행 시작 → 내비 화면)"
"$ADB" logcat -c
( timeout 300 "$ADB" logcat -s Intervention:I VoiceTest:I 2>/dev/null | while read -r line; do
    case "$line" in
      *"level=MILD"*) mark "7 피로 감지 → 공조 동작(MILD)";;
      *"demo scripted answer level=CAUTION"*) mark "8 음성 확인 → '괜찮아요' 자동 응답";;
      *"level=CAUTION"*) mark "8 음성 확인 완료(ANSWERED)";;
      *"doSpeak"*"기흥"*) mark "9 휴게소 제안 음성(기흥휴게소)";;
      *"demo scripted answer level=DANGER"*) mark "9 '안내해 줘' 자동 응답";;
      *"level=DANGER"*) mark "10 경유지 추가 완료";;
    esac
  done ) &
WATCH=$!
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Prepare-Demo.ps1 -Drive >/dev/null 2>&1
mark "11 정차·주행 요약"; sleep 9
kill $WATCH 2>/dev/null

"$ADB" shell input keyevent BACK; sleep 3   # 요약 닫기 → 홈
mark "12 내 기록"; tap 2300 1180; sleep 6
for i in 1 2 3 4; do swipe_up; sleep 4; done
mark "끝"; sleep 2
"$ADB" emu screenrecord stop >/dev/null
echo "저장: artifacts/demo-video/$NAME.webm, 장면 시각: $MARK"
