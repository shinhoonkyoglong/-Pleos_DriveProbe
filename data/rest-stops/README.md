# 공식 휴게시설 원본

사용자가 2026-11-12 제공한 공공데이터포털 표준데이터 CSV 원본이다. CP949 인코딩을 그대로 보존했다.

`.gitattributes`에서 이 CSV의 Git 줄바꿈 변환을 끈다. 원본 바이트의 SHA-256을 산출물에 기록하므로 인코딩·줄바꿈을 임의로 변환하지 않는다.

| 파일 | 원본 명칭 | 행 수 | 데이터 기준일 |
| --- | --- | --- | --- |
| rest-areas.csv | 전국휴게소정보표준데이터.csv | 215 | 2026-09-17 |
| sleep-rest-areas.csv | 전국졸음쉼터표준데이터.csv | 253 | 2026-02-25 |

출처: 한국도로공사 제공 공공데이터포털 표준데이터. [전국휴게소정보표준데이터](https://www.data.go.kr/data/15025446/standard.do), [공공데이터포털](https://www.data.go.kr/). 파일에 들어 있는 제공기관명·기준일은 변환 산출물에 보존한다.

`python scripts/build-rest-stop-catalog.py`로 앱의 UTF-8 JSON 자산 `app/src/main/assets/navigation/public-rest-stops.json`을 생성한다. 산출물에 입력 파일별 SHA-256과 행 수를 포함하며, 위경도·필수 항목·날짜가 잘못된 행은 조용히 버리지 않고 생성을 실패시킨다. 최신 원본을 교체한 뒤 이 명령으로 갱신한다.

이 데이터의 행 식별자·도로 번호는 내비 SDK POI ID가 아니다. 앱은 공식 시설 식별자와 좌표를 저장하고 내비 POI 필드는 비워 둔다.
