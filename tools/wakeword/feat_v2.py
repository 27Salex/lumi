"""v2 features: English "Hey Lumi" data + phone-microphone augmentation (band-pass, clipping, far-field levels).

Reuses feat.py (same augmentation, backgrounds and Spanish held-out voices) and adds:
  f_pos_en_train / f_pos_en_test, f_adv_en_train / f_adv_en_test, f_neg_en_train,
  f_pos_es_phone (extra phone-style copies of the Spanish training positives),
  s_pos_en / s_adv_en / s_pos_es_phone (3-second streaming test sets).
"""
import glob, os, random
import numpy as np
from scipy.signal import butter, sosfilt
import feat as F

random.seed(21); np.random.seed(21)
W = F.W
voice_of = lambda p: os.path.basename(p)[:-4].split('_', 1)[1]

pos_en = sorted(glob.glob(os.path.join(W, 'clips', 'pos_en', '*.wav')))
adv_en = sorted(glob.glob(os.path.join(W, 'clips', 'adv_en', '*.wav')))
neg_en = sorted(glob.glob(os.path.join(W, 'clips', 'neg_en', '*.wav')))
F.babble.extend(F.load(p) for p in neg_en)  # English conversation as background noise too

# English held-out voices: 10 % of the English voices (never seen in training)
en_voices = sorted({voice_of(p) for p in pos_en if voice_of(p).startswith('en-')})
random.shuffle(en_voices)
test_voices = set(en_voices[: max(4, len(en_voices) // 10)])
pos_en_train = [p for p in pos_en if voice_of(p) not in test_voices]
pos_en_test = [p for p in pos_en if voice_of(p) in test_voices]


def phone(clip):
    """What a phone mic does to far or quiet speech: band-limited, sometimes clipped, often much quieter."""
    lo = random.uniform(80, 350); hi = random.uniform(3400, 7600)
    clip = sosfilt(butter(2, [lo, hi], btype='band', fs=F.SR, output='sos'), clip).astype(np.float32)
    if random.random() < 0.3:
        drive = random.uniform(1.5, 4.0)
        clip = np.tanh(clip * drive) / np.tanh(drive)
    clip = clip / (np.abs(clip).max() + 1e-9) * random.uniform(0.03, 0.8)
    return clip.astype(np.float32)


def augment2(clip):
    clip = F.augment(clip)
    return phone(clip) if random.random() < 0.6 else clip


def build(files, reps, end_aligned, crop=False):
    X = []
    for p in files:
        a = F.load(p)
        for _ in range(reps):
            c = augment2(a)
            if crop and len(c) > F.LEN:
                s = random.randint(0, len(c) - F.LEN); c = c[s:s + F.LEN]
            X.append(F.place(c, end_aligned if not crop else random.random() < 0.5))
    return np.stack(X)


LEN3 = 48000


def place3(clip):
    out = np.zeros(LEN3, np.float32)
    clip = clip[:LEN3 - 16000]
    out[16000:16000 + len(clip)] = clip
    if random.random() < 0.85:
        bg = F.background(LEN3); snr = random.uniform(5, 25)
        p_sig = np.mean(clip ** 2) + 1e-9; p_bg = np.mean(bg ** 2) + 1e-9
        out += bg * np.sqrt(p_sig / (p_bg * 10 ** (snr / 10)))
    peak = np.abs(out).max()
    if peak > 0.99: out *= 0.99 / peak
    return (out * 32767).astype(np.int16)


def stream(files, reps):
    return np.stack([place3(augment2(F.load(p))) for p in files for _ in range(reps)])


def save(name, X):
    e = F.embed(X) if X.ndim == 2 and X.shape[1] == F.LEN else F.F.embed_clips(X, batch_size=256, ncpu=8).astype(np.float16)
    np.save(os.path.join(W, name), e)
    print(name, e.shape, flush=True)


if __name__ == '__main__':
    print('en voices', len(en_voices), 'held out', sorted(test_voices))
    print('pos_en train', len(pos_en_train), 'test', len(pos_en_test), 'adv', len(adv_en), 'neg', len(neg_en), flush=True)
    random.shuffle(adv_en); cut = int(len(adv_en) * 0.85)
    save('f_pos_en_train.npy', build(pos_en_train, 5, True))
    save('f_pos_en_test.npy', build(pos_en_test, 4, True))
    save('f_adv_en_train.npy', build(adv_en[:cut], 5, True))
    save('f_adv_en_test.npy', build(adv_en[cut:], 4, True))
    save('f_neg_en_train.npy', build(neg_en, 5, False, crop=True))
    save('f_pos_es_phone.npy', build(F.pos_train, 3, True))
    # Streaming test sets (phrase inside 3 s, like live audio)
    save('s_pos_en.npy', stream(pos_en_test, 4))
    save('s_adv_en.npy', stream(adv_en[cut:], 3))
    save('s_pos_es_phone.npy', stream(F.pos_test, 4))
    open(os.path.join(W, 'test_voices_en.txt'), 'w').write('\n'.join(sorted(test_voices)))
    print('ok')
