"""Training features: augmented clips (speed, volume, echo, noise, background conversation) → 16×96 embeddings."""
import glob, os, random
import numpy as np, soundfile as sf
from scipy.signal import fftconvolve, resample_poly
from openwakeword.utils import AudioFeatures

random.seed(11); np.random.seed(11)
W = os.path.dirname(os.path.abspath(__file__))
SR, LEN = 16000, 32000  # 2.0 s → exactly 16 embeddings

def load(p):
    a, _ = sf.read(p, dtype='float32'); return a

pos_files = sorted(glob.glob(os.path.join(W, 'clips', 'pos', '*.wav')))
adv_files = sorted(glob.glob(os.path.join(W, 'clips', 'adv', '*.wav')))
neg_files = sorted(glob.glob(os.path.join(W, 'clips', 'neg', '*.wav')))
babble = [load(p) for p in neg_files]

def colored_noise(n):
    kind = random.choice(['white', 'pink', 'brown'])
    x = np.random.randn(n).astype(np.float32)
    if kind != 'white':
        X = np.fft.rfft(x); f = np.arange(1, len(X) + 1)
        X /= (np.sqrt(f) if kind == 'pink' else f)
        x = np.fft.irfft(X, n).astype(np.float32)
    return x / (np.abs(x).max() + 1e-9)

def rir():
    t = random.uniform(0.15, 0.7); n = int(t * SR)
    h = np.random.randn(n).astype(np.float32) * np.exp(-np.linspace(0, random.uniform(4, 9), n)).astype(np.float32)
    h[0] = 1.0
    return h / np.abs(h).max()

def background(n):
    if random.random() < 0.5:
        return colored_noise(n)
    b = random.choice(babble)
    if len(b) < n: b = np.tile(b, n // len(b) + 1)
    s = random.randint(0, len(b) - n); return b[s:s + n].copy()

def augment(clip):
    if random.random() < 0.7:
        f = random.choice([0.88, 0.92, 0.96, 1.04, 1.08, 1.12])
        clip = resample_poly(clip, int(100 * f), 100).astype(np.float32)  # changes speed and pitch
    if random.random() < 0.4:
        clip = fftconvolve(clip, rir())[:len(clip)].astype(np.float32)
    clip = clip / (np.abs(clip).max() + 1e-9) * random.uniform(0.1, 0.9)
    return clip

def place(clip, end_aligned=True):
    """Clip inside 2 s with background; if end_aligned, it ends in the last 0-0.2 s (as it arrives live)."""
    out = np.zeros(LEN, np.float32)
    clip = clip[-LEN:]
    if end_aligned:
        end = LEN - random.randint(0, 3200)
    else:
        end = random.randint(len(clip), LEN)
    clip = clip[-end:]
    out[end - len(clip):end] = clip
    if random.random() < 0.85:
        snr = random.uniform(3, 25)
        bg = background(LEN)
        p_sig = np.mean(clip ** 2) + 1e-9; p_bg = np.mean(bg ** 2) + 1e-9
        out += bg * np.sqrt(p_sig / (p_bg * 10 ** (snr / 10)))
    peak = np.abs(out).max()
    if peak > 0.99: out *= 0.99 / peak
    return (out * 32767).astype(np.int16)

def build(files, reps, end_aligned, crop=False):
    X = []
    for p in files:
        a = load(p)
        for _ in range(reps):
            c = augment(a)
            if crop and len(c) > LEN:
                s = random.randint(0, len(c) - LEN); c = c[s:s + LEN]
            X.append(place(c, end_aligned if not crop else random.random() < 0.5))
    return np.stack(X)

F = AudioFeatures(inference_framework='onnx')

def embed(X):
    e = F.embed_clips(X, batch_size=256, ncpu=8)
    assert e.shape[1] == 16, e.shape
    return e.astype(np.float16)

# 10 % of the voices held out for evaluation (never seen in training)
voices = sorted({i // 14 for i in range(len(pos_files))})
random.shuffle(voices); test_voices = set(voices[: max(3, len(voices) // 10)])
pos_train = [p for i, p in enumerate(pos_files) if i // 14 not in test_voices]
pos_test = [p for i, p in enumerate(pos_files) if i // 14 in test_voices]

if __name__ == '__main__':
    print('pos train', len(pos_train), 'test', len(pos_test))
    np.save(os.path.join(W, 'f_pos_train.npy'), embed(build(pos_train, 8, True)))
    np.save(os.path.join(W, 'f_pos_test.npy'), embed(build(pos_test, 4, True)))
    random.shuffle(adv_files); cut = int(len(adv_files) * 0.85)
    np.save(os.path.join(W, 'f_adv_train.npy'), embed(build(adv_files[:cut], 6, True)))
    np.save(os.path.join(W, 'f_adv_test.npy'), embed(build(adv_files[cut:], 4, True)))
    np.save(os.path.join(W, 'f_neg_train.npy'), embed(build(neg_files, 6, False, crop=True)))
    # Noise / silence only
    noise = np.stack([place(np.zeros(1, np.float32), False) for _ in range(1500)])
    np.save(os.path.join(W, 'f_noise.npy'), embed(noise))
    print('ok')
