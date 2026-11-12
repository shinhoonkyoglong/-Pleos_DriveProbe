#!/usr/bin/env bash
# 2번 '출발 전' 모듈(:departure-core, :departure-ui)의 이식성 기계 검사.
# 위반마다 "BLOCKER: <이유> <파일:라인>"을 출력하고 exit 1, 모두 통과하면 "OK" 한 줄만 출력한다.
# 사용: 저장소 루트에서 `bash scripts/check-departure.sh`
# 판단이 필요한 항목(포트 시그니처 설계, UX, 테스트 질 등)은 리뷰어가 직접 본다.

set -u
cd "$(dirname "$0")/.." || exit 2

CORE=departure-core
UI=departure-ui
CONTRACT=$CORE/src/main/kotlin/com/pleos/departure/port/CommonRepositoryContract.kt
# report 는 파이프라인(서브셸)에서 돌므로 실패 여부를 변수 대신 임시 파일로 남긴다
FAIL_MARK=$(mktemp)
rm -f "$FAIL_MARK"
trap 'rm -f "$FAIL_MARK"' EXIT

blocker() { echo "BLOCKER: $1 $2"; : > "$FAIL_MARK"; }

# grep 결과(파일:라인:내용)를 BLOCKER로 출력. $1 = 이유, 표준입력 = grep -n 출력
report() {
  local reason=$1 line
  while IFS= read -r line; do
    [ -n "$line" ] && blocker "$reason" "$(echo "$line" | cut -d: -f1,2)"
  done
}

src() { # build/ 를 제외한 소스 디렉터리 목록
  for d in "$@"; do [ -d "$d/src" ] && echo "$d/src"; done
}

# 1. core에 android/androidx import 금지
grep -rnE '^\s*import\s+(android|androidx)\.' $(src $CORE) 2>/dev/null \
  | report "departure-core에 Android import"

# 2. core/ui에 Pleos SDK·1번 앱 패키지(둘 다 ai.pleos.*) 참조 금지
#    우리 패키지 com.pleos.departure 는 허용(감독관 결정 11/09: SDK 패키지는 ai.pleos.*)
grep -rnE 'ai\.pleos' $(src $CORE $UI) 2>/dev/null \
  | report "core/ui에 Pleos SDK 또는 앱 패키지 참조"

# 3. core는 Kotlin/JVM 플러그인(Android 플러그인 아님)
if [ ! -f $CORE/build.gradle.kts ]; then
  blocker "departure-core/build.gradle.kts 없음" "$CORE/build.gradle.kts:0"
else
  grep -qE 'kotlin\("jvm"\)|libs\.plugins\.kotlin\.jvm|org\.jetbrains\.kotlin\.jvm' $CORE/build.gradle.kts \
    || blocker "departure-core가 kotlin(\"jvm\") 모듈이 아님" "$CORE/build.gradle.kts:1"
  grep -nE 'com\.android\.(library|application)|libs\.plugins\.android\.' $CORE/build.gradle.kts \
    | sed "s|^|$CORE/build.gradle.kts:|" | report "departure-core에 Android 플러그인"
fi

# 4. 1번 대응 Repository 인터페이스는 CommonRepositoryContract.kt 한 파일에만
grep -rnE '^\s*(fun\s+)?interface\s+\w*Repository\b' $(src $CORE $UI) 2>/dev/null \
  | grep -v "^$CONTRACT:" | report "CommonRepositoryContract.kt 밖의 Repository 인터페이스"

# 5. 임시 계약 파일에 TODO(1번-연동) 메모
if [ ! -f "$CONTRACT" ]; then
  blocker "임시 계약 파일 없음" "$CONTRACT:0"
else
  grep -q 'TODO(1번-연동)' "$CONTRACT" || blocker "임시 계약에 TODO(1번-연동) 메모 없음" "$CONTRACT:1"
fi

# (2번 단독 저장소의 6번 검사 — departure.adapter 전환·DepartureWiring — 은 이 저장소에 해당 없음. 조립은 DriveProbeApplication)

# 7. core/ui에 DI 애노테이션 금지
grep -rnE '@(Inject|Module|Provides|Binds|Singleton|InstallIn|HiltViewModel|HiltAndroidApp|AndroidEntryPoint|Component|Subcomponent|EntryPoint)\b' \
  $(src $CORE $UI) 2>/dev/null | report "core/ui에 DI 애노테이션"

if [ -e "$FAIL_MARK" ]; then exit 1; fi
echo "OK"
exit 0
