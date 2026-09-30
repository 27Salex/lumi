# Wake word training ("Hey Lumi" / "Oye Lumi")

How `app/src/main/assets/oww/oye_lumi.bin` was made. One small MLP (1536 → 128 → 64 → 1) scores the last 16
[openWakeWord](https://github.com/dscripka/openWakeWord) embeddings (≈1.3 s of audio) and fires on either phrase. The
app runs the same pipeline on the phone (`OyeLumiDetector`, `WakeClassifier`), and `WakeClassifierTest` checks that
Kotlin and PyTorch give the same scores.

## Steps

```bash
uv venv -p 3.12 env && uv pip install -p env torch numpy scipy soundfile edge-tts onnxruntime openwakeword \
    --extra-index-url https://download.pytorch.org/whl/cpu
python gen_es.py        # Spanish clips: "Oye Lumi", sound-alike traps, normal speech (edge-tts voices in voices_es.txt)
python gen_en.py        # English clips: "Hey Lumi" (47 English voices + multilingual), traps ("Hey Lucy"…), normal speech
python feat.py          # Spanish features: augmentation (speed, echo, noise, background talk) → 16×96 embeddings
python feat_v2.py       # English features + phone-mic augmentation + 3-second streaming test sets
python train_v2.py 8 v2 # train, evaluate per language, export lumi_v2.bin + parity cases
```

`train_v2.py` also expects data that is not in the repo (large):
- `acav_part.npy` / `acav_meta.npy`: precomputed openWakeWord features of general audio (the first ~3 GB of
  openWakeWord's ACAV100M negative set) used as negatives;
- `validation_set_features.npy`: openWakeWord's validation features (≈11 h of real audio), half for hard-negative
  mining and half to measure false wakes per hour;
- `f_pos2_train.npy`, `s_pos.npy`, `s_adv.npy`: a second batch of Spanish positives and the Spanish streaming test
  sets, built the same way as in `feat_v2.py`.

## Results (model v2, voices never seen in training)

| Threshold | "Oye Lumi" | "Hey Lumi" | False wakes per hour |
|---|---|---|---|
| 0.50 (Strict) | 69 % | 80 % | 0 |
| 0.35 (Normal) | ~76 % | ~85 % | ~0.1 |
| 0.25 (Relaxed) | ~81 % | ~88 % | ~0.5 |

All training voices are synthetic (edge-tts), so real voices may score lower; "Train my voice" (Voice Match) and the
"Relaxed" setting help. English sound-alike names ("Hey Lucy", "Hey Louie") are the weak spot.
