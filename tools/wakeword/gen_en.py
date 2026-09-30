"""English data for the wake word: "Hey Lumi" positives, sound-alike traps and normal English speech (edge-tts)."""
import asyncio, io, os, random, sys
import numpy as np, soundfile as sf
from scipy.signal import resample_poly
import edge_tts

random.seed(2026)
W = os.path.dirname(os.path.abspath(__file__))
POS = os.path.join(W, 'clips', 'pos_en'); NEG = os.path.join(W, 'clips', 'neg_en'); ADV = os.path.join(W, 'clips', 'adv_en')
for d in (POS, NEG, ADV): os.makedirs(d, exist_ok=True)

POS_TEXTS = ['Hey Lumi', 'Hey, Lumi', 'Hey Lumi!', 'Hey Lumi.', 'Hey, Lumi.', 'Hey Lumi?', 'hey lumi', 'Hey Loomi']
ADV_TEXTS = [
    'Hey Lucy', 'Hey Louie', 'Hey Lou', 'Hey Luna', 'Hey Lumen', 'Hey Lily', 'Hey Jimmy', 'Hey Rumi', 'Hey Siri',
    'Hey Google', 'Hey you', 'Hey, look', 'Hey, listen', 'Hey Lucas', 'Hey Julie', 'Hey Tommy', 'Hey Lola', 'Hey Moony',
    'Hey Loopy', 'Hey Lenny', 'Hey Emmy', 'Hey Lulu', 'Hey Lizzie', 'Hey Loom', 'Hey, lovely', 'Hey, let me',
    'Hey, look at me', 'Hey, lunch?', 'Hey, luckily', 'Hey, loony', 'Hey, lumpy', 'Hi Lucy', 'Hey Louis', 'Hey Lumia',
    'Hey Portal', 'Hey there', 'Hey Mimi', 'Hey Lumos', 'Hey buddy', 'Hey, you know what', 'Hey, are you there?',
    'Hey, did you see that?', 'Hey, I need you', 'Okay Google', 'Alexa', 'Luminous', 'Illuminate', 'Hello Lucy',
]
NEG_TEXTS = [
    "I have a meeting at ten tomorrow", "Can you pass me the salt?", "What time is it?", "I'm going to the supermarket",
    "We're going to the cinema tonight", "I love this song", "Call me when you get home", "It's really hot today",
    "Have you seen my keys?", "The train leaves at eight", "I need to finish the report", "Let's eat out tonight",
    "I can't remember her name", "We'll talk about it later", "We had such a good time yesterday", "A latte, please",
    "The game starts in ten minutes", "Look at this picture", "My brother lives in Boston", "There's a lot of traffic",
    "Do you want me to help?", "I'm taking the dog out", "I have a bit of a headache", "It's going to rain tomorrow",
    "We need to talk about the holidays", "We're almost there", "Close the window, it's cold", "The boss wants to see you",
    "Lucy is late again", "Louie called me this morning", "The moon looks beautiful tonight", "That sounds like a great idea",
    "I don't know what to do tonight", "Hey, can you pass me my phone?", "Hey, what are you doing?", "Hi, how are you?",
    "Good afternoon everyone", "Where did you park the car?", "I forgot my wallet", "The museum opens at nine",
    "It's my turn to cook today", "Honestly I'm exhausted", "Let's play some music", "Turn the lights off please",
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
    en = sorted(v['ShortName'] for v in vs if v['Locale'].startswith('en-'))
    ml = sorted(v['ShortName'] for v in vs if 'Multilingual' in v['ShortName'] and not v['Locale'].startswith('en-'))
    es = [l.split()[0] for l in open(os.path.join(W, 'voices_es.txt'), encoding='utf-8') if l.strip()]
    open(os.path.join(W, 'voices_en.txt'), 'w').write('\n'.join(en))
    print(len(en), 'en voices,', len(ml), 'multilingual,', len(es), 'es voices')
    jobs = []
    i = 0
    # Positives: English voices × 36, multilingual × 12, a few Spanish voices × 6 (Spanish speakers may say "Hey Lumi")
    plan = [(v, 36) for v in en] + [(v, 12) for v in ml] + [(v, 6) for v in es]
    for v, reps in plan:
        for _ in range(reps):
            t = random.choice(POS_TEXTS); r = random.choice([-30, -20, -10, -5, 0, 5, 10, 20, 30]); p = random.choice([-15, -8, 0, 8, 15])
            jobs.append(synth(t, v, r, p, os.path.join(POS, f'p{i:05d}_{v}.wav'))); i += 1
    j = 0
    for t in ADV_TEXTS:
        for v in random.sample(en + ml, 12):
            jobs.append(synth(t, v, random.choice([-15, 0, 10, 20]), random.choice([-8, 0, 8]), os.path.join(ADV, f'a{j:05d}_{v}.wav'))); j += 1
    n = 0
    for t in NEG_TEXTS:
        for v in random.sample(en + ml, 6):
            jobs.append(synth(t, v, random.choice([-10, 0, 10, 20]), 0, os.path.join(NEG, f'n{n:05d}_{v}.wav'))); n += 1
    print('generating', len(jobs), 'clips', flush=True)
    done = 0
    for f in asyncio.as_completed(jobs):
        await f; done += 1
        if done % 500 == 0: print(done, flush=True)

asyncio.run(main())
print('ok')
