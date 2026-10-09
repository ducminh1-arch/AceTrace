@echo off
echo ========================================================
echo  AceTrace Phase 0 - Tao video test co frame in san
echo ========================================================

where ffmpeg >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Khong tim thay ffmpeg trong PATH he thong!
    echo Vui long cai dat ffmpeg hoac dat ffmpeg.exe vao thu muc nay.
    pause
    exit /b 1
)

echo [1/2] Dang tao test_60_portrait.mp4 (1080x1920, 60fps, 10s, in so frame, audio sine)...
ffmpeg -y -f lavfi -i "testsrc2=size=1080x1920:rate=60" -f lavfi -i "sine=frequency=440:duration=10" -t 10 -vf "drawtext=text='%%{frame_num}':start_number=0:x=40:y=40:fontsize=140:fontcolor=white:box=1:boxcolor=black" -c:v libx264 -pix_fmt yuv420p -g 120 -c:a aac -shortest test_60_portrait.mp4

echo [2/2] Dang tao test_30_land_longgop.mp4 (1920x1080, 30fps, 10s, GOP dai g=300, in so frame, audio sine)...
ffmpeg -y -f lavfi -i "testsrc2=size=1920x1080:rate=30" -f lavfi -i "sine=frequency=440:duration=10" -t 10 -vf "drawtext=text='%%{frame_num}':start_number=0:x=40:y=40:fontsize=140:fontcolor=white:box=1:boxcolor=black" -c:v libx264 -pix_fmt yuv420p -g 300 -bf 2 -c:a aac -shortest test_30_land_longgop.mp4

echo ========================================================
echo Hoan tat! Hai video test da duoc tao tai:
echo  1. test_60_portrait.mp4
echo  2. test_30_land_longgop.mp4
echo ========================================================
pause
