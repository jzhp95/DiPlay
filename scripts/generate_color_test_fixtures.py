#!/usr/bin/env python3
"""Generate small, owned SDR fixtures; no network or third-party media input."""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'common/src/main/assets/color-test'
GRAYS = [0, 4, 8, 12, 16, 20, 24, 32, 192, 208, 224, 232, 240, 248, 252, 255]
COLORS = [(v, v, v) for v in GRAYS] + [(255,0,0),(0,255,0),(0,0,255),(0,255,255),(255,0,255),(255,255,0),(128,128,128),(255,255,255)]

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    manifest = {'width':640, 'height':360, 'fps':10, 'patch_rgb':COLORS, 'fixtures':[]}
    with tempfile.TemporaryDirectory(prefix='diplay-color-') as tmp:
        image = Path(tmp) / 'reference.ppm'
        pixels = bytes(channel for y in range(360) for x in range(640) for channel in COLORS[(y//120)*8+x//80])
        image.write_bytes(b'P6\n640 360\n255\n'+pixels)
        for codec in ['h264', 'hevc']:
            for limited in [False, True]:
                name = f'{codec}_{"limited" if limited else "full"}'
                color_range = 'tv' if limited else 'pc'
                path = OUT / f'{name}.mp4'
                command = ['ffmpeg','-v','error','-y','-loop','1','-framerate','10','-i',str(image),'-t','1',
                    '-vf',f'scale=in_range=pc:out_range={color_range}:out_color_matrix=bt709,format=yuv420p',
                    '-c:v','libx264' if codec=='h264' else 'libx265','-preset','fast','-crf','10','-g','10','-bf','0',
                    '-color_range',color_range,'-colorspace','bt709','-color_primaries','bt709','-color_trc','bt709']
                if codec=='h264':
                    command += ['-x264-params',f'colorprim=bt709:transfer=bt709:colormatrix=bt709:fullrange={"off" if limited else "on"}']
                if codec=='hevc':
                    command += ['-x265-params',f'log-level=error:pools=1:frame-threads=1:range={"limited" if limited else "full"}:colorprim=bt709:transfer=bt709:colormatrix=bt709','-tag:v','hvc1']
                command += ['-movflags','+faststart',str(path)]
                subprocess.run(command, check=True)
                probe = json.loads(subprocess.check_output(['ffprobe','-v','error','-select_streams','v:0','-show_streams','-show_data','-of','json',str(path)]))['streams'][0]
                assert probe['color_range']==color_range, probe
                assert probe['color_space']=='bt709', probe
                hexdata = ''.join(line.split(':',1)[1].split('  ',1)[0].replace(' ','') for line in probe['extradata'].splitlines() if ':' in line)
                (OUT / f'{name}.csd').write_bytes(bytes.fromhex(hexdata))
                manifest['fixtures'].append({'asset':name, 'command':command[0:command.index(str(image))]+['<generated-reference.ppm>']+command[command.index(str(image))+1:-1]+[path.name],
                    'codec':probe['codec_name'], 'color_range':probe['color_range'], 'color_space':probe['color_space'],
                    'color_transfer':probe['color_transfer'], 'color_primaries':probe['color_primaries'],
                    'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    (OUT / 'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    print('Verified four SDR fixtures:', ', '.join(p.name for p in OUT.glob('*.mp4')))

if __name__=='__main__':
    main()
