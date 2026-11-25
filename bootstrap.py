"""Prepare verified native libraries without copying code from another project."""
from pathlib import Path
import argparse,hashlib,urllib.request,zipfile,shutil
root=Path(__file__).resolve().parent
p=argparse.ArgumentParser();p.add_argument('--apk',type=Path);a=p.parse_args()
apk=a.apk or root/'vendor/mpv-android-2026-09-17.apk'
expected='a502ea6b6357d1e48a3134f9bd848484990cdda18836bd8445523538d0f4a887'
if not apk.exists():
    apk.parent.mkdir(parents=True,exist_ok=True)
    with urllib.request.urlopen('https://github.com/mpv-android/mpv-android/releases/download/2026-09-17/app-default-universal-debug.apk',timeout=60) as r,apk.open('wb') as f:shutil.copyfileobj(r,f)
assert hashlib.sha256(apk.read_bytes()).hexdigest()==expected,'Upstream APK hash mismatch'
with zipfile.ZipFile(apk) as z:
    for item in z.infolist():
        parts=item.filename.split('/')
        if len(parts)==3 and parts[0]=='lib' and parts[1] in ('arm64-v8a','armeabi-v7a','x86_64') and parts[2].endswith('.so'):
            dest=root/'app/src/main/jniLibs'/parts[1]/parts[2];dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(z.read(item))
print('Verified native libraries ready. Build with Gradle; no external Lite/Windows checkout is needed.')
