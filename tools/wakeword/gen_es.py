"""Spanish data for the wake word (edge-tts): "Oye Lumi" positives, sound-alike traps and normal Spanish speech."""
import asyncio, io, os, random, sys
import numpy as np, soundfile as sf
from scipy.signal import resample_poly
import edge_tts

random.seed(99)
W = os.path.dirname(os.path.abspath(__file__))
POS = os.path.join(W, 'clips', 'pos'); NEG = os.path.join(W, 'clips', 'neg'); ADV = os.path.join(W, 'clips', 'adv')
for d in (POS, NEG, ADV): os.makedirs(d, exist_ok=True)

ES = [l.split()[0] for l in open(os.path.join(W, 'voices_es.txt'), encoding='utf-8') if l.strip()]

POS_TEXTS = ['Oye Lumi', 'Oye, Lumi', '¡Oye Lumi!', 'Oye Lumi.', 'Oye, Lumi.', 'Oye Lumi?', 'oye lumi']
# Close to "Oye Lumi" but must NOT trigger it
ADV_TEXTS = [
    'Oye Luis', 'Oye, Luisa', 'Hola Luna', 'Oye Lucía', 'Oye Lupe', 'Hoy lunes', 'Oye mira', 'Oye tú', 'Lumbre',
    'Luminoso', 'Oye, Lola', 'Oye Rumi', 'Oye Juli', 'Oye Lili', 'Hoy llueve', 'Oye lo mismo', 'Hola Lucas', 'Oye Luna',
    'Oye Lumen', 'Oye Mimi', 'Oye Lucho', 'Oye Lulú', 'Oye Luismi', 'Oye Yumi', 'Oye Humi', 'Oye, lo mío', 'Luego',
    'Hoy lo miro', 'Oye, una cosa', 'Oye, ¿vienes?', 'Oye, espera', 'Oye que', 'Lumía', 'Alumni', 'Oye, Lú', 'Oye Nuri',
    'Oye Lucio', 'Oye, Rubí', 'Oye Lomi', 'Hola, ¿qué tal?', 'Oiga, disculpe', 'Buenos días', 'Oye, ¿me oyes?',
    'Oye, ¿lo miras?', 'Oye, luego te llamo', 'Oye, Luisa, mira esto', 'Oye, ¿lo has visto?', 'Oye, ¿y Luis?',
]
NEG_TEXTS = [
    'Mañana tengo reunión a las diez', 'Pásame la sal, por favor', '¿Qué hora es?', 'Voy a comprar pan al supermercado',
    'Esta tarde vamos al cine', 'Me encanta esta canción', 'Llámame cuando llegues', 'Hace mucho calor hoy',
    '¿Has visto mis llaves?', 'El tren sale a las ocho', 'Tengo que terminar el informe', 'Vamos a cenar fuera',
    'No me acuerdo de su nombre', 'Luego hablamos con calma', 'Qué bien lo pasamos ayer', 'Ponme un café con leche',
    'El partido empieza en diez minutos', 'Mira qué foto más bonita', 'Mi hermano vive en Valencia', 'Hay mucho tráfico',
    '¿Quieres que te ayude?', 'Voy a sacar al perro', 'Me duele un poco la cabeza', 'Mañana llueve seguro',
    'Tenemos que hablar de las vacaciones', 'Ya casi hemos llegado', 'Cierra la ventana, que hace frío',
    'El jefe quiere verte', 'Lucía llega tarde otra vez', 'Luis me ha llamado esta mañana', 'La luna está preciosa hoy',
    'Me parece una idea genial', 'No sé qué hacer esta noche', 'Oye, ¿me pasas el móvil?', 'Oye, ¿qué haces?',
    'Hola, ¿cómo estás?', 'Buenas tardes a todos', '¿Dónde has dejado el coche?', 'Se me ha olvidado la cartera',
    'Lumbre y leña para la chimenea', 'El museo abre a las nueve', 'Hoy me toca cocinar a mí',
]


def to16k(mp3: bytes) -> np.ndarray:
    audio, sr = sf.read(io.BytesIO(mp3), dtype='float32')
    if audio.ndim > 1: audio = audio.mean(1)
    return resample_poly(audio, 16000, sr).astype(np.float32) if sr != 16000 else audio


sem = asyncio.Semaphore(8)


async def synth(text, voice, rate, pitch, path):
    if os.path.exists(path): return
    async with sem:
        for attempt in range(3):
            try:
                com = edge_tts.Communicate(text, voice, rate=f'{rate:+d}%', pitch=f'{pitch:+d}Hz')
                buf = b''
                async for chunk in com.stream():
                    if chunk['type'] == 'audio': buf += chunk['data']
                if not buf: raise RuntimeError('empty')
                sf.write(path, to16k(buf), 16000, subtype='PCM_16')
                return
            except Exception:
                await asyncio.sleep(1.5 * (attempt + 1))
        print('failed', voice, text, file=sys.stderr)


async def main():
    vs = await edge_tts.list_voices()
    ml = [v['ShortName'] for v in vs if 'Multilingual' in v['ShortName']]
    voices = ES + ml
    print(len(ES), 'Spanish voices +', len(ml), 'multilingual')
    jobs = []
    i = 0
    # Positives: each voice with varied text, speed and pitch (file names keep the order used for held-out voices)
    for v in voices:
        for _ in range(30 if v in ES else 16):
            t = random.choice(POS_TEXTS); r = random.choice([-30, -20, -10, -5, 0, 5, 10, 20, 30]); p = random.choice([-15, -8, 0, 8, 15])
            jobs.append(synth(t, v, r, p, os.path.join(POS, f'p{i:05d}.wav'))); i += 1
    # Traps: each phrase with 10 random voices
    j = 0
    for t in ADV_TEXTS:
        for v in random.sample(voices, 10):
            jobs.append(synth(t, v, random.choice([-15, 0, 10, 20]), random.choice([-8, 0, 8]), os.path.join(ADV, f'a{j:05d}.wav'))); j += 1
    # Normal speech: each sentence with 6 voices
    n = 0
    for t in NEG_TEXTS:
        for v in random.sample(voices, 6):
            jobs.append(synth(t, v, random.choice([-10, 0, 10, 20]), 0, os.path.join(NEG, f'n{n:05d}.wav'))); n += 1
    print('generating', len(jobs), 'clips')
    done = 0
    for f in asyncio.as_completed(jobs):
        await f; done += 1
        if done % 200 == 0: print(done, flush=True)

asyncio.run(main())
print('ok')
