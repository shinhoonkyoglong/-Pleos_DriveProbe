#!/bin/bash
# usage: run.sh <tag> <steps> <waitsec>
A=${ADB:-adb}   # adb 경로(예: <Android SDK>/platform-tools/adb.exe)를 ADB 환경변수로 지정
cd "$(dirname "$0")"; mkdir -p logs
$A logcat -c
$A shell am start -S --user current -n ai.probe.pleos/.ProbeActivity --es steps "$2" >/dev/null
sleep $3
$A logcat -d -v time > logs/$1-full.txt
grep PleosProbe logs/$1-full.txt | sed 's/^.*PleosProbe *: //' | cut -c1-450 > logs/$1-probe.txt
grep -E "NavigationUseCase|navi_engine" logs/$1-full.txt | grep -v "mapmatching" > logs/$1-navi.txt
cat logs/$1-probe.txt
