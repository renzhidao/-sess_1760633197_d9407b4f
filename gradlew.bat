# 文件: gradlew.bat
@if "%DEBUG%" == "" @echo off
@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
SET DIRNAME=%~dp0
if "%DIRNAME%" == "" SET DIRNAME=.
SET APP_BASE_NAME=%~n0
SET APP_HOME=%DIRNAME%
if "%JAVA_HOME%" == "" (
  set JAVACMD=java.exe
) else (
  set JAVACMD="%JAVA_HOME%\bin\java.exe"
)
SET CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
%JAVACMD% ^
  -Dorg.gradle.appname=%APP_BASE_NAME% ^
  -classpath "%CLASSPATH%" ^
  org.gradle.wrapper.GradleWrapperMain %*