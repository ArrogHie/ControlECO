# 本地构建入口：固定 JDK17 + 已解压的 Gradle 8.10.2，避免 gradlew 因沙箱写 ~/.gradle 的 .lck 失败。
# 用法： pwsh -File tools/gradle.ps1 :app:testDebugUnitTest --offline
$env:JAVA_HOME = "D:\Java\jdk-17.0.20.1+1"
$env:GRADLE_USER_HOME = "C:\Users\ArrogHie\.gradle"
$gradle = "C:\Users\ArrogHie\.gradle\wrapper\dists\gradle-8.10.2-bin\e0thjr3we83usdufs66z371ne\gradle-8.10.2\bin\gradle.bat"
& $gradle @args
exit $LASTEXITCODE
