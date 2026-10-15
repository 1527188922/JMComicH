#!/bin/sh
# Gitee Go 构建脚本：编 debug 包（并可选发布到 Gitee 发行版）。
#
# 为什么放在脚本里而不是流水线 YAML 的 commands 里：
# 1) YAML 的普通标量（plain scalar）里 ": " 是非法的——之前把 `cut -d: -f2`
#    直接写进 commands，冒号后面跟了空格，Gitee 的 YAML 解析器报
#    "mapping values are not allowed here"；
# 2) 长 shell 一行还牵扯引号、超长行的可读性问题；放进版本库的脚本
#    改起来有 diff，也能本地先跑一遍。
#
# 流水线里只需要一行：sh tools/ci/gitee-build.sh
#
# 需要的流水线变量（都在「变量」里配，不配也能跑）：
#   GITEE_TOKEN  私人令牌（勾选 projects 权限）。不配 / 还是占位符 = 跳过发布
#   RELEASE_TAG  指定版本号，默认 v1.0.<构建号>-debug
#
# 想改成正式包：把下面的 :app:assembleDebug 换成 :app:assembleRelease，
# 并按 docs/BUILD.md 在流水线变量里补 KEYSTORE_BASE64 等四个变量（见 README 里的说明）。

set -e

# 让脚本从仓库根目录开始跑（本地 dry-run 也成立）
cd "$(dirname "$0")/../.."

echo "==> 1/4 准备构建环境"
# 构建机是 Linux，没有本机那个固定路径的 JDK：删掉 org.gradle.java.home 让 Gradle 用 JAVA_HOME
# （与 .github/workflows/android-ci.yml 的处理一致）
sed -i '/org.gradle.java.home/d' gradle.properties
# 上面删掉固定 JDK 后，守护进程 JDK 就由 gradle/gradle-daemon-jvm.properties 决定；那个文件
# （`gradlew updateDaemonJvm` 生成）把版本钉在 25 并带 api.foojay.io 的下载地址，Gradle 9 会照着
# 去拉一整个 JDK → 国内构建机拉不动，报
#   Unable to download toolchain matching the requirements ({languageVersion=25, ...})
# 后失败（2026-10-15 的失败就是这个）。仓库里已删除该文件，这里再兜底删一次，防止有人本地
# 跑过 updateDaemonJvm 后又把它提交上来。
rm -f gradle/gradle-daemon-jvm.properties
# gradlew 在仓库里没有可执行位（Windows 提交的）
chmod +x gradlew
# 构建容器只有 4GB 内存：Gradle 守护进程 3GB + Kotlin/KSP 编译进程默认还要 2GB，
# 很容易在打包前被 OOM kill（表现：日志里没有 Gradle 的 FAILURE 段、任务跑一半就断）。
# 这里把两者的堆都压到容器内存以内（只改本次构建的工作区副本，仓库里的值不动）。
{
  echo ''
  echo '# Gitee 构建容器内存较小，覆盖上面的默认值'
  echo 'org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8'
  echo 'kotlin.daemon.jvmargs=-Xmx1280m'
} >> gradle.properties
grep -E 'jvmargs|java.home' gradle.properties
# wrapper 默认从 services.gradle.org 拉 Gradle 发行包，国内构建机会一直卡在 "Downloading ..."：
# 临时把地址换成国内镜像（只改工作区里的文件，仓库里保持官方地址）。
# 镜像是同一份文件（与官方 Content-Length 一致），所以 distributionSha256Sum 校验不受影响。
# 腾讯云不通时换成 https://mirrors.huaweicloud.com/gradle/
sed -i 's#services.gradle.org/distributions#mirrors.cloud.tencent.com/gradle#' gradle/wrapper/gradle-wrapper.properties
grep distributionUrl gradle/wrapper/gradle-wrapper.properties

# 依赖也走国内镜像：dl.google.com / plugins.gradle.org 在国内拉不动。
# settings.gradle.kts 里这个变量为 1 时才把阿里云镜像排在官方仓库前面（本地/GitHub 不受影响）。
export JMCOMICH_USE_CN_MIRROR=1
export ANDROID_HOME=/root/android-sdk
export ANDROID_SDK_ROOT=/root/android-sdk

# Gitee 构建镜像自带一个全局 init 脚本 /root/.gradle/init.gradle，它会给**每个模块**
# 注入一个 maven 仓库；而本工程的 settings.gradle.kts 是 FAIL_ON_PROJECT_REPOS
# （禁止模块级仓库），两者冲突时 Gradle 直接失败：
#   Build was configured to prefer settings repositories over project repositories
#   but repository 'maven' was added by initialization script '/root/.gradle/init.gradle'
# 依赖源已经由上面的镜像变量接管，所以把镜像自带的这些 init 脚本停用掉。
for f in /root/.gradle/init.gradle /root/.gradle/init.d/*.gradle; do
  if [ -f "$f" ]; then
    mv "$f" "$f.disabled"
    echo "已停用构建镜像自带的 init 脚本：$f"
  fi
done

# 镜像里的 Android SDK 在只读挂载 /mnt/pipeline-tools/standard/android/sdk 下，
# AGP 要补装 build-tools/platform 时报 "The SDK directory is not writable"。
# 这里搭一个**可写**的 SDK：已有组件用软链接指过去（省几 GB 复制），
# 缺的（工程要 compileSdk 37 / build-tools 36）让 AGP 自己下到可写目录里。
IMAGE_SDK=/mnt/pipeline-tools/standard/android/sdk
if [ -d "$IMAGE_SDK" ]; then
  SDK="$HOME/android-sdk"
  mkdir -p "$SDK/platforms" "$SDK/build-tools"
  for kind in platforms build-tools; do
    for sub in "$IMAGE_SDK/$kind"/*; do
      [ -e "$sub" ] || continue
      ln -sfn "$sub" "$SDK/$kind/$(basename "$sub")"
    done
  done
  for sub in "$IMAGE_SDK"/*; do
    name=$(basename "$sub")
    case "$name" in
      platforms|build-tools) ;;
      *) ln -sfn "$sub" "$SDK/$name" ;;
    esac
  done
  export ANDROID_HOME="$SDK"
  export ANDROID_SDK_ROOT="$SDK"
  printf 'sdk.dir=%s\n' "$SDK" > local.properties
  echo "可写 SDK：$SDK（local.properties 已指向它）"

  # 工程要 compileSdk 37 / build-tools 36，镜像里没有这两个组件；AGP 会自动去
  # dl.google.com 补装，而国内构建机访问不了（会卡住/失败）。所以直接从腾讯云的
  # Android SDK 镜像（dl.google.com/android/repository 的完整镜像）把官方 zip 解进来。
  # 文件名取自镜像上的 repository2-3.xml（platforms;android-37.0、build-tools;36.0.0），
  # 以后升级这两个版本时改下面的常量即可。
  SDK_MIRROR=https://mirrors.cloud.tencent.com/AndroidSDK
  extract_zip() {
    mkdir -p "$2"
    if command -v unzip >/dev/null 2>&1; then
      unzip -q -o "$1" -d "$2"
    else
      (cd "$2" && jar xf "$1")
    fi
  }

  if grep -s -l '^AndroidVersion.ApiLevel=37' "$SDK"/platforms/*/source.properties >/dev/null 2>&1; then
    echo "已存在 Android Platform 37，跳过"
  else
    echo "从镜像安装 Android SDK Platform 37.0 ..."
    curl -sL --max-time 600 -o /tmp/platform-37.zip "$SDK_MIRROR/platform-37.0_r02.zip"
    rm -rf /tmp/platform-37 && extract_zip /tmp/platform-37.zip /tmp/platform-37
    # zip 里是一个 android-* 目录；AGP 是按 source.properties 认平台的，目录名保持原样即可
    first=$(ls -d /tmp/platform-37/*/ 2>/dev/null | head -n 1)
    if [ -z "$first" ]; then
      echo "解压 Platform 后没找到目录，补装失败"
      exit 1
    fi
    mv "$first" "$SDK/platforms/$(basename "$first")"
  fi

  if [ -d "$SDK/build-tools/36.0.0" ]; then
    echo "已存在 Android Build-Tools 36.0.0，跳过"
  else
    echo "从镜像安装 Android SDK Build-Tools 36.0.0 ..."
    curl -sL --max-time 600 -o /tmp/build-tools-36.zip "$SDK_MIRROR/build-tools_r36_linux.zip"
    rm -rf /tmp/build-tools-36 && extract_zip /tmp/build-tools-36.zip /tmp/build-tools-36
    # build-tools 的目录名必须是版本号：AGP 用目录名解析版本
    first=$(ls -d /tmp/build-tools-36/*/ 2>/dev/null | head -n 1)
    if [ -z "$first" ]; then
      echo "解压 Build-Tools 后没找到目录，补装失败"
      exit 1
    fi
    mv "$first" "$SDK/build-tools/36.0.0"
  fi

  echo "--- 补装后的组件 ---"
  ls "$SDK/platforms" 2>/dev/null || true
  ls "$SDK/build-tools" 2>/dev/null || true
fi

echo "==> 2/4 构建 debug 包"
# debug 由 Gradle 自动生成的 debug keystore 签名，不需要 keystore.properties
./gradlew --no-daemon :app:assembleDebug

echo "==> 3/4 整理产物"
APK=$(ls app/build/outputs/apk/debug/*.apk 2>/dev/null | head -n 1 || true)
if [ -z "$APK" ]; then
  echo "没有找到 debug APK，中止"
  exit 1
fi
TAG="${RELEASE_TAG:-}"
if [ -z "$TAG" ]; then
  TAG="v1.0.${GITEE_PIPELINE_BUILD_NUMBER}-debug"
fi
OUT="JMComicH-${TAG}.apk"
cp "$APK" "$OUT"
ls -lh "$OUT"

echo "==> 4/4 发布到 Gitee 发行版（可选）"
TOKEN="${GITEE_TOKEN:-}"
if [ -z "$TOKEN" ] || [ "$TOKEN" = "PASTE_YOUR_GITEE_TOKEN_HERE" ]; then
  echo "未配置 GITEE_TOKEN，跳过发布；APK 见本次构建的制品/归档"
  exit 0
fi

# 仓库的 owner/repo 直接从 git remote 里取，不用另配变量
REMOTE=$(git config --get remote.origin.url)
OWNER=$(echo "$REMOTE" | sed -E 's#.*[:/]([^/]+)/[^/]+(\.git)?$#\1#')
REPO=$(echo "$REMOTE" | sed -E 's#.*/([^/]+)(\.git)?$#\1#')
API="https://gitee.com/api/v5/repos/${OWNER}/${REPO}"

# 同 tag 的发行版已存在就复用，否则按当前提交新建（debug 包标记为预发布）
RELEASE_ID=$(curl -s "${API}/releases/tags/${TAG}?access_token=${TOKEN}" \
  | grep -o '"id":[0-9]*' | head -n 1 | cut -d: -f2)
if [ -z "$RELEASE_ID" ]; then
  RELEASE_ID=$(curl -s -X POST "${API}/releases" \
    --data-urlencode "access_token=${TOKEN}" \
    --data-urlencode "tag_name=${TAG}" \
    --data-urlencode "name=JMComicH ${TAG}" \
    --data-urlencode "body=$(git log -1 --pretty=%s)" \
    --data-urlencode "target_commitish=$(git rev-parse HEAD)" \
    --data-urlencode "prerelease=true" \
    | grep -o '"id":[0-9]*' | head -n 1 | cut -d: -f2)
fi
if [ -z "$RELEASE_ID" ]; then
  echo "发行版创建/查找失败：检查 GITEE_TOKEN 是否有 projects 权限"
  exit 1
fi

curl -s -X POST "${API}/releases/${RELEASE_ID}/attach_files" \
  -F "access_token=${TOKEN}" \
  -F "file=@${OUT}"
echo
echo "已发布：${TAG}（附件 ${OUT}）"