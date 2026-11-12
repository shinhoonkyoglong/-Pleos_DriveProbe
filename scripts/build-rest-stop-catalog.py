"""Build the offline catalog from the supplied official CP949/UTF-8 standard CSVs."""
import csv
import hashlib
import io
import json
import re
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sources = []
facilities = []
for filename, kind, name_key in (
    ("rest-areas.csv", "SERVICE_AREA", "휴게소명"),
    ("sleep-rest-areas.csv", "SLEEP_REST_AREA", "졸음쉼터명"),
):
    raw = (ROOT / "data/rest-stops" / filename).read_bytes()
    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        text = raw.decode("cp949")
    rows = list(csv.DictReader(io.StringIO(text)))
    sources.append(dict(file=filename, sha256=hashlib.sha256(raw).hexdigest(), rows=len(rows)))
    for index, row in enumerate(rows, 1):
        lat, lng = float(row["위도"]), float(row["경도"])
        assert 33 < lat < 39 and 124 < lng < 132, (filename, index, "Invalid Korean coordinate")
        date.fromisoformat(row["데이터기준일자"])
        name = row[name_key].strip()
        assert name and row["도로노선번호"] and row["도로노선명"], (filename, index, "Missing identity")
        direction = ""
        suffix = re.fullmatch(r"(.+?)\(([^()]+)\)", name)
        if suffix:
            name, direction = suffix.groups()
        directional_road = re.fullmatch(r"(.+?)기점\s*\+\s*(.+?)종점", row["도로노선방향"])
        if directional_road:
            direction = directional_road.group(2).strip()
        facilities.append(dict(kind=kind, sourceRow=index, baseName=name,
            roadNumber=row["도로노선번호"], roadName=row["도로노선명"], roadType=row["도로종류"],
            direction=direction, directionRaw=row["도로노선방향"],
            latitude=lat, longitude=lng, dataDate=row["데이터기준일자"], provider=row["제공기관명"],
            address=row.get("소재지도로명주소") or row.get("소재지지번주소") or ""))
out = ROOT / "app/src/main/assets/navigation/public-rest-stops.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(dict(schemaVersion=1, sources=sources, facilities=facilities),
    ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Generated {len(facilities)} facilities: " + str(out))
