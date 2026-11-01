#!/usr/bin/env python3
from pathlib import Path
import argparse,hashlib,json,os,subprocess,zipfile

import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import hyperceiler_test_paths as test_paths

HERE=Path(__file__).resolve().parent;ROOT=test_paths.TEST_DATA_ROOT;CACHE=test_paths.BUILD_CACHE;JAVA=test_paths.JAVA_HOME / 'bin';KH=test_paths.KOTLIN_HOME;SDK=test_paths.ANDROID_HOME;BT=SDK/'build-tools/37.0.0';ANDROID=SDK/'platforms/android-37.0/android.jar';SOURCE=test_paths.SOURCE_ROOT / 'library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/systemui/controlcenter/media3/CustomBackgroundRendererOS4.kt';OTA=test_paths.ROM_ROOT / 'components/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk';NATIVE_SOURCE=SOURCE.with_name('CustomBackgroundDrawableOS4.kt');CONFIG=test_paths.SOURCE_ROOT / 'library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/utils/hookapi/systemui/controlcenter/media/MediaViewColorConfig.kt';CORE=test_paths.SOURCE_ROOT / 'app/src/main/cpp/targets/systemui';NDK=test_paths.ANDROID_NDK_HOME / 'toolchains/llvm/prebuilt/linux-x86_64/bin';NATIVE=test_paths.EXTRACTED_ROM_ROOT / 'system_ext/lib64/libMiMainColor.so';sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
import tempfile
def safe_output(raw):
 # --output 只能落在当前工作目录或系统临时目录内，且不得包含 .. 段
 parts=Path(raw).parts
 if '..' in parts:
  raise SystemExit('refusing --output with traversal segments: '+str(raw))
 p=Path(raw).expanduser().resolve()
 roots=[Path.cwd().resolve(),Path(tempfile.gettempdir()).resolve()]
 if p==Path(p.anchor) or not any(str(p).startswith(str(r)+os.sep) for r in roots):
  raise SystemExit('--output must stay under the working directory or the temp directory')
 return p

p=argparse.ArgumentParser();p.add_argument('--output',required=True,type=Path);a=p.parse_args();out=safe_output(a.output);out.mkdir(parents=True,exist_ok=False);inputs={str(q):sha(q)for q in [SOURCE,NATIVE_SOURCE,CONFIG,OTA,Path(__file__),HERE/'MainActivity.java',HERE/'AndroidManifest.xml',ANDROID,KH/'lib/kotlin-compiler.jar',KH/'lib/kotlin-stdlib.jar']};commands=[];(out/'classes').mkdir();(out/'dex').mkdir()
QUEUE=[]
def queue(cmd):
 QUEUE.append(list(map(str,cmd)))
def run():
 cmd=QUEUE.pop(0);commands.append(cmd);r=subprocess.run(cmd,capture_output=True,text=True,env=dict(os.environ,JAVA_HOME=str(JAVA.parent),PATH=str(JAVA)+os.pathsep+os.environ['PATH']));(out/'build.txt').open('a').write(r.stdout+r.stderr);assert r.returncode==0,r.stdout+r.stderr
queue([JAVA/'java','-cp',KH/'lib/kotlin-compiler.jar','org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-kotlin-home',KH,'-jvm-target','1.8','-classpath',ANDROID,'-d',out/'production.jar',SOURCE,NATIVE_SOURCE,CONFIG]);run()
(out/'BuildMetadata.java').write_text('package local.hyperceiler.backgroundprobe;final class BuildMetadata{static final String SOURCE_SHA="'+sha(SOURCE)+'", OTA_SHA="'+sha(OTA)+'", CONFIG_SHA="'+sha(CONFIG)+'", DRAWABLE_SHA="'+sha(NATIVE_SOURCE)+'";}')
queue([JAVA/'javac','--release','8','-proc:none','-cp',os.pathsep.join(map(str,[ANDROID,out/'production.jar',KH/'lib/kotlin-stdlib.jar'])),'-d',out/'classes',HERE/'MainActivity.java',out/'BuildMetadata.java']);run();queue([JAVA/'jar','--create','--file',out/'classes.jar','-C',out/'classes','.']);run()
queue([BT/'d8','--min-api','35','--lib',ANDROID,'--output',out/'dex',out/'classes.jar',out/'production.jar',KH/'lib/kotlin-stdlib.jar']);run();queue([BT/'aapt2','link','-I',ANDROID,'--manifest',HERE/'AndroidManifest.xml','-o',out/'resources.apk']);run()
with zipfile.ZipFile(out/'resources.apk')as z,zipfile.ZipFile(out/'unaligned.apk','w')as dest:
 for i in z.infolist():dest.writestr(i,z.read(i.filename))
 for dex in sorted((out/'dex').glob('*.dex')):dest.write(dex,dex.name,compress_type=zipfile.ZIP_DEFLATED)
 dest.write(OTA,'assets/ota-systemui.apk',compress_type=zipfile.ZIP_DEFLATED)
# Exact current private bridge; only its fixed file path is changed for this app probe.
queue([BT/'zipalign','-f','-P','16','4',out/'unaligned.apk',out/'aligned.apk']);run();queue([BT/'apksigner','sign','--ks',test_paths.TEST_DATA_ROOT / 'emulation/native-probe/build/test-only.keystore','--ks-key-alias','nativeprobe','--ks-pass','pass:android','--key-pass','pass:android','--out',out/'probe.apk',out/'aligned.apk']);run();queue([BT/'apksigner','verify','--verbose',out/'probe.apk']);run();report={'passed':all(sha(Path(q))==v for q,v in inputs.items()),'inputs':inputs,'commands':commands,'apk_sha256':sha(out/'probe.apk'),'device_status':'NOT_TESTED','scope':'Probe contains original full OTA APK DEX as analysis-only asset; complete current renderer/drawable Kotlin and test Activity; no vendor native library. Not a module release/phone installation.'};(out/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(report['passed'])
