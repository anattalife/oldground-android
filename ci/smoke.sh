#!/usr/bin/env bash
# Opens an anchored old map in each hunt-map view on an emulator and records whether the app survives.
set -u
P=com.brandonthomas.oldground
OUT=smoke
mkdir -p $OUT
adb install -r -g oldground.apk > $OUT/install.txt 2>&1 || { echo "INSTALL FAILED"; cat $OUT/install.txt; }
for perm in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do adb shell pm grant $P android.permission.$perm 2>/dev/null; done
adb emu geo fix -82.99450 39.96030 || true
# Seed one anchored map.
adb shell "run-as $P sh -c 'mkdir -p files/oldground/maps/ci1'"
adb shell "run-as $P sh -c 'cat > files/oldground/maps/ci1/original.jpg'" < ci/testmap.jpg
adb shell "run-as $P sh -c 'cat > files/oldground/data.json'" < ci/data.json
adb shell "run-as $P sh -c 'mkdir -p files/oldground/tracks'"
adb shell "run-as $P sh -c 'cat > files/oldground/tracks/t1.csv'" < ci/track.csv
adb shell "run-as $P sh -c 'cat > files/oldground/tracks/t2.csv'" < ci/track2.csv
adb shell "run-as $P sh -c 'mkdir -p files/oldground/photos'"
for f in ci/photos/*.jpg; do adb shell "run-as $P sh -c 'cat > files/oldground/photos/ci-$(basename $f)'" < $f; done
adb shell "run-as $P ls -l files/oldground files/oldground/maps/ci1"
# Public land service check (Columbus, Ohio area).
curl -sS -m 30 "https://services.arcgis.com/v01gqwM5QqNysAAi/arcgis/rest/services/Manager_Type_PADUS/FeatureServer/0/query?where=1%3D1&geometryType=esriGeometryEnvelope&inSR=4326&geometry=-83.06,39.91,-82.97,39.98&outFields=OBJECTID,Unit_Nm,Mang_Type,Pub_Access&returnGeometry=true&outSR=4326&maxAllowableOffset=0.00008&geometryPrecision=5&f=geojson" > $OUT/padus.json
python3 -c "
import json,sys
d=json.load(open('$OUT/padus.json'))
f=d.get('features',[])
print('PAD-US features:',len(f),'bytes:',len(open('$OUT/padus.json').read()),'top keys:',list(d.keys()))
for x in f[:6]: print(' ',x.get('id'),x.get('properties'))
" > $OUT/padus.txt 2>&1 || head -c 500 $OUT/padus.json > $OUT/padus.txt
adb logcat -c
: > $OUT/result.txt
shot() { # name, then extras for the app
  local name=$1; shift
  adb shell am force-stop $P
  sleep 1
  adb shell am start -W -n $P/.MainActivity "$@" > /dev/null
  adb emu geo fix -82.99450 39.96030 > /dev/null 2>&1 || true
  sleep ${WAIT:-22}
  if adb shell pidof $P > /dev/null; then echo "$name: running" >> $OUT/result.txt
  else echo "$name: CRASHED" >> $OUT/result.txt; fi
  if [ -n "${SCROLL:-}" ]; then adb shell input swipe 540 1900 540 $((1900 - SCROLL)) 600; sleep 3; fi
  adb exec-out screencap -p > $OUT/$name.png
}
WAIT=45 shot HOME
shot OVERLAY --es openMap ci1 --es mode OVERLAY
shot SWIPE --es openMap ci1 --es mode SWIPE
shot SPLIT --es openMap ci1 --es mode SPLIT
shot DRIVE --es openMap ci1 --es mode DRIVE
shot MAPONLY --es openMap none --es mode OVERLAY
shot LAYERS --es openMap ci1 --es mode SWIPE --es debug more
shot PINEDIT --es openMap ci1 --es mode SWIPE --es pin p1 --es debug pin
shot FINDEDIT --es openMap none --es mode OVERLAY --es pin p2 --es debug pin
shot FINDNEW --es openMap none --es mode OVERLAY --es pin p5 --es debug new
shot FINDCARD --es openMap none --es mode OVERLAY --es pin p2 --es debug card
shot FINDMAP --es openMap none --es mode OVERLAY --es pin p6 --es debug none
shot FINDOLD --es openMap ci1 --es mode OVERLAY --es pin p6 --es debug none
shot ANCHORS --es screen align --es openMap ci1
shot SITES --es screen sites
shot ALLFINDS --es screen finds
shot PINSTAB --es screen finds --es debug pins
SCROLL=900 shot COVERAGE --es screen site --es site s1
SCROLL=900 shot COVERSEL --es screen site --es site s1 --es debug visit
shot OLDMAPS --es screen old
shot SETTINGS --es screen settings
shot SITE --es screen site --es site s1
shot SITENOPHOTO --es screen site --es site s3
shot SITEEDIT --es screen site --es site s1 --es debug edit
shot VISIT --es screen visit --es site s1 --es day 2026-10-11
shot DRAW --es openMap none --es mode OVERLAY --es draw new --es debug drawdemo
shot MAPSITE --es openMap none --es mode OVERLAY --es site s1 --es debug none
shot OLDPICK --es openMap ci1 --es mode OVERLAY --es debug oldmaps
shot MODES --es openMap ci1 --es mode OVERLAY --es debug modes
shot PLACE --es openMap none --es mode OVERLAY --es site s1 --es debug place
shot SEARCH --es openMap none --es mode OVERLAY --es debug search
shot RECORDING --es openMap none --es mode OVERLAY --es site s1 --es startVisit 1 --es debug none
shot SITESREC --es screen sites
shot COMMUNITY --es screen community --es community demo
shot CFILTER --es screen community --es community demo --es debug filter
shot CPOST --es screen post --es post d1 --es community demo
shot CPOSTMENU --es screen post --es post d1 --es community demo --es debug menu
shot CVIDEO --es screen post --es post d2 --es community demo
shot CCOMPOSE --es screen compose --es community demo --es debug find
shot CCOMPOSEV --es screen compose --es community demo --es debug video
shot CJOIN --es screen join
shot CJOINNAME --es screen join --es community demo --es debug name
shot CSETTINGS --es screen settings --es community demo
shot CLIVE --es screen community
shot SITEPERM --es screen site --es site s2
shot SITESPERM --es screen sites
shot SLIP --es screen slip --es site s1 --es debug form
shot SLIPSIGN --es screen slip --es site s1 --es debug sign
shot HELP --es screen help
shot HELPTOPIC --es screen help --es topic old-maps
adb shell "run-as $P cat files/last-crash.txt" > $OUT/last-crash.txt 2>/dev/null || true
adb logcat -d -b crash > $OUT/crash.txt
adb logcat -d -v brief AndroidRuntime:E OldGround:V libc:F DEBUG:F '*:S' > $OUT/errors.txt
# What Google's map says about the key (authorization problems show up here).
adb logcat -d -v brief | grep -iE "Google Maps|API key|API_KEY|Authorization failure|not authorized|MapsInitializer|maps_api|Ensure that the" | sort | uniq -c | sort -rn | head -40 > $OUT/maps.txt
cat $OUT/result.txt
