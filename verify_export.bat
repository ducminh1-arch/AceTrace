@echo off
if "%~1"=="" (
    echo Cach dung: verify_export.bat ^<duong_dan_file_out.mp4^> [duong_dan_file_goc.mp4]
    exit /b 1
)

set OUT_FILE=%~1
set SRC_FILE=%~2

echo ========================================================
echo  Kiem chung file xuat: %OUT_FILE%
echo ========================================================

echo --- 1. Thong so video (codec, kich thuoc, fps, thoi luong, rotation) ---
ffprobe -v error -show_entries stream=codec_type,codec_name,width,height,r_frame_rate,avg_frame_rate,duration:stream_side_data=rotation -of json "%OUT_FILE%"

echo --- 2. Dem so frame that cua file xuat ---
ffprobe -v error -count_frames -select_streams v:0 -show_entries stream=nb_read_frames -of csv=p=0 "%OUT_FILE%"

if not "%SRC_FILE%"=="" (
    echo --- Dem so frame that cua file goc ---
    ffprobe -v error -count_frames -select_streams v:0 -show_entries stream=nb_read_frames -of csv=p=0 "%SRC_FILE%"
)

echo --- 3. Trich xuat cac frame kiem chung (0, 1, 59, 60, 100, 299, 599) ---
ffmpeg -y -i "%OUT_FILE%" -vf "select=eq(n\,0)" -vframes 1 frame_0.png
ffmpeg -y -i "%OUT_FILE%" -vf "select=eq(n\,1)" -vframes 1 frame_1.png
ffmpeg -y -i "%OUT_FILE%" -vf "select=eq(n\,59)" -vframes 1 frame_59.png
ffmpeg -y -i "%OUT_FILE%" -vf "select=eq(n\,60)" -vframes 1 frame_60.png
ffmpeg -y -i "%OUT_FILE%" -vf "select=eq(n\,100)" -vframes 1 frame_100.png

echo Hoan tat kiem tra! Kiem tra cac file anh frame_*.png de so sanh so in tren video va text 'OVL f='.
