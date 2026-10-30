#!/usr/bin/env python3
"""Reproducible H1 fixtures. Reference pixels decoded independently by FFmpeg, never Android YUV code."""
import hashlib, json, shutil, subprocess, tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / 'common/src/main/assets/color-test'
LEVELS = [0, 4, 8, 12, 16, 20, 224, 228, 232, 234, 235, 236, 238, 240, 242, 244, 248, 252, 254, 255]

def main():
    ffmpeg = shutil.which('ffmpeg') or '/opt/homebrew/bin/ffmpeg'
    manifest = {'version': 1, 'referenceBasis': 'FFmpeg independent decode of actual encoded files; numerical hypotheses only, uncalibrated pass thresholds', 'width': 1920, 'height': 1080, 'fps': 30, 'files': {}}
    with tempfile.TemporaryDirectory() as tmp:
        for theme in ('light', 'dark'):
            strip = b''.join(bytes([v, v, v]) * 96 for v in LEVELS)
            base = 240 if theme == 'light' else 24
            background = bytes([base] * 3) * 1920
            rows = [strip] * 320
            for y in range(320, 1080):
                row = bytearray(background)
                for col in range(8):
                    v = (232 + col * 3) if theme == 'light' else (16 + col * 3)
                    if (y // 80) % 2: v = base
                    row[col*240*3:(col+1)*240*3] = bytes([v]*3) * 240
                rows.append(bytes(row))
            raw = Path(tmp) / (theme + '.rgb'); raw.write_bytes(b''.join(rows))
            for range_name, out_range in [('full','pc'),('limited','tv')]:
                name = f'h264_{theme}_{range_name}'; target = ROOT / (name + '.mp4')
                subprocess.run([ffmpeg, '-v','error','-y','-stream_loop','-1','-f','rawvideo','-pix_fmt','rgb24','-s','1920x1080','-r','30','-i',str(raw),'-vf',f'scale=in_range=pc:out_range={out_range}:out_color_matrix=bt709','-c:v','libx264','-preset','fast','-crf','12','-pix_fmt','yuv420p','-color_range',out_range,'-colorspace','bt709','-color_primaries','bt709','-color_trc','bt709','-x264-params','keyint=30:min-keyint=30:scenecut=0:bframes=0','-frames:v','45',str(target)], check=True)
                data = target.read_bytes(); pos = data.index(b'avcC'); length = int.from_bytes(data[pos-4:pos],'big')
                (ROOT / (name + '.csd')).write_bytes(data[pos+4:pos-4+length])
                decoded = subprocess.check_output([ffmpeg,'-v','error','-i',str(target),'-frames:v','1','-f','rawvideo','-pix_fmt','rgb24','-'])
                patches = []
                for i, level in enumerate(LEVELS):
                    vals = [decoded[(y*1920+x)*3+c] for y in range(80,240,16) for x in range(i*96+24,i*96+72,8) for c in range(3)]
                    patches.append({'nominal':level,'referenceMean':sum(vals)/len(vals),'rect':[i*96+24,80,48,160]})
                probe = json.loads(subprocess.check_output([shutil.which('ffprobe') or '/opt/homebrew/bin/ffprobe','-v','error','-count_frames','-select_streams','v:0','-show_entries','stream=nb_read_frames','-of','json',str(target)]))
                frame_count = int(probe['streams'][0]['nb_read_frames']); assert frame_count == 45
                manifest['files'][name] = {'frames':frame_count,'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data),'theme':theme,'range':range_name,'patches':patches}
    (ROOT / 'h1-reference.json').write_text(json.dumps(manifest,indent=2)+'\n')

if __name__ == '__main__': main()
