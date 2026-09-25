@echo off
rem Launches the Forge dev server for 塔科夫Scav.
rem The shared Gradle home is the sibling project's, which already has every Forge/MC dependency
rem downloaded - pointing at a fresh one would re-download several GB.
set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.12"
set "JAVA_TOOL_OPTIONS=-Duser.language=en -Duser.country=US"
cd /d D:\deepseek\ArmedMobs
call gradlew.bat -g D:\deepseek\GirlsFrontline\.gradle-home runServer --console=plain
