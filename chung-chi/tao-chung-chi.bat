@echo off
chcp 65001 >nul
REM ============================================================
REM  TAO CHUNG CHI HTTPS CHO SUNCLUB (khong can mo cong 80)
REM  - Tao 1 chung chi goc "SunClub CA" (cai len dien thoai / may tinh)
REM  - Tao keystore.p12 cho may chu, ky boi SunClub CA, dung ten thfgroup.ddns.net
REM  Chay file nay tren may chu (tu tim keytool trong Java da cai).
REM ============================================================
set DOMAIN=thfgroup.ddns.net
set PASS=
set CAPASS=
echo Nhap mat khau keystore (giong dong key-store-password trong application.properties).
set /p PASS=Bam Enter de dung MatKhau123: 
if "%PASS%"=="" set PASS=MatKhau123
echo.
echo Nhap mat khau cho chung chi goc (tu dat, GHI LAI de lan sau gia han).
set /p CAPASS=Bam Enter de dung SunClubCA2026: 
if "%CAPASS%"=="" set CAPASS=SunClubCA2026
echo.

cd /d "%~dp0"

REM ---- Tim keytool (di kem Java) ----
set KT=
for /f "delims=" %%i in ('where keytool 2^>nul') do if not defined KT set "KT=%%i"
if not defined KT if defined JAVA_HOME if exist "%JAVA_HOME%\bin\keytool.exe" set "KT=%JAVA_HOME%\bin\keytool.exe"
if not defined KT for /d %%d in ("%USERPROFILE%\.jdks\*" "C:\Program Files\Java\*" "C:\Program Files\Eclipse Adoptium\*" "C:\Program Files\Microsoft\*" "C:\Program Files\Zulu\*" "C:\Program Files\Amazon Corretto\*" "C:\Program Files\BellSoft\*" "C:\Program Files (x86)\Java\*") do if not defined KT if exist "%%~d\bin\keytool.exe" set "KT=%%~d\bin\keytool.exe"
if not defined KT for /d %%d in ("C:\Program Files\JetBrains\*" "%LOCALAPPDATA%\Programs\*" "%LOCALAPPDATA%\JetBrains\Toolbox\apps\*") do if not defined KT if exist "%%~d\jbr\bin\keytool.exe" set "KT=%%~d\jbr\bin\keytool.exe"
if not defined KT (
  echo Dang tim keytool trong may, doi mot chut...
  for /f "delims=" %%i in ('where /r "%USERPROFILE%" keytool.exe 2^>nul') do if not defined KT set "KT=%%i"
)
if not defined KT for /f "delims=" %%i in ('where /r "C:\Program Files" keytool.exe 2^>nul') do if not defined KT set "KT=%%i"
if not defined KT (
  echo KHONG TIM THAY keytool. May chua cai Java JDK.
  echo Cai Java 17 tai https://adoptium.net roi chay lai file nay.
  goto loi
)
echo Dung keytool: %KT%
echo.

if exist keystore.p12 ren keystore.p12 keystore-cu-%RANDOM%.p12
if exist ca.p12 (
  echo Da co ca.p12 - dung lai chung chi goc cu, dien thoai da cai khong phai cai lai.
) else (
  "%KT%" -genkeypair -alias sunclub-ca -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=SunClub CA, O=Thanh Hoa Ford, C=VN" -ext bc:c -keystore ca.p12 -storetype PKCS12 -storepass %CAPASS% || goto loi
)
"%KT%" -exportcert -alias sunclub-ca -keystore ca.p12 -storepass %CAPASS% -rfc -file sunclub-ca.cer || goto loi
"%KT%" -genkeypair -alias sunclub -keyalg RSA -keysize 2048 -validity 825 -dname "CN=%DOMAIN%, O=Thanh Hoa Ford, C=VN" -keystore keystore.p12 -storetype PKCS12 -storepass %PASS% || goto loi
"%KT%" -certreq -alias sunclub -keystore keystore.p12 -storepass %PASS% -file sunclub.csr || goto loi
"%KT%" -gencert -alias sunclub-ca -keystore ca.p12 -storepass %CAPASS% -infile sunclub.csr -outfile sunclub.cer -rfc -validity 825 -ext "SAN=dns:%DOMAIN%" -ext "EKU=serverAuth" -ext "KU=digitalSignature,keyEncipherment" || goto loi
"%KT%" -importcert -alias sunclub-ca -file sunclub-ca.cer -keystore keystore.p12 -storepass %PASS% -noprompt || goto loi
"%KT%" -importcert -alias sunclub -file sunclub.cer -keystore keystore.p12 -storepass %PASS% || goto loi
del sunclub.csr sunclub.cer
echo.
echo XONG. Da tao:
echo   keystore.p12   - chep vao src\main\resources (thay file cu)
echo   sunclub-ca.cer - chep vao src\main\resources\pwa  (de dien thoai tai ve cai)
echo   ca.p12         - GIU KIN, de lai lan sau gia han (825 ngay)
pause
exit /b 0
:loi
echo CO LOI - xem dong bao loi phia tren.
pause
exit /b 1
