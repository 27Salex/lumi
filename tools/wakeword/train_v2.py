"""v2 wake word: one small MLP that fires on "Oye Lumi" AND "Hey Lumi". Same architecture/export as train.py (the app
loads the same format), trained on Spanish + English data with phone-mic augmentation, evaluated per language.
Usage: train_v2.py <negative weight> <tag> [positive multiplier]
"""
import os, json, struct, sys
NEGW = float(sys.argv[1]) if len(sys.argv) > 1 else 8.0
TAG = sys.argv[2] if len(sys.argv) > 2 else 'v2'
import numpy as np, torch, torch.nn as nn

torch.manual_seed(5); np.random.seed(5); torch.set_num_threads(6)
W = os.path.dirname(os.path.abspath(__file__))
L = lambda n: np.load(os.path.join(W, n)).astype(np.float32)

pos_es = np.concatenate([L('f_pos_train.npy'), L('f_pos2_train.npy'), L('f_pos_es_phone.npy')])
pos_en = L('f_pos_en_train.npy')
print('positives es', len(pos_es), 'en', len(pos_en))
ADV_EN = float(sys.argv[3]) if len(sys.argv) > 3 else 1.0
adv_es_arr = L('f_adv_train.npy'); n_adv_es = len(adv_es_arr)
adv = np.concatenate([adv_es_arr, L('f_adv_en_train.npy')])
neg = np.concatenate([L('f_neg_train.npy'), L('f_neg_en_train.npy'), L('f_noise.npy')])
off, n, *shape = np.load(os.path.join(W, 'acav_meta.npy'))
acav = np.memmap(os.path.join(W, 'acav_part.npy'), dtype=np.float16, mode='r', offset=int(off), shape=(int(n), 16, 96))
val_all = np.load(os.path.join(W, 'validation_set_features.npy')).astype(np.float32)
half = len(val_all) // 2
val_mine = np.lib.stride_tricks.sliding_window_view(val_all[:half], (16, 96))[:, 0]
val = val_all[half:]
hard = np.zeros((0, 16, 96), np.float32)


class Net(nn.Module):
    def __init__(self):
        super().__init__()
        self.f = nn.Sequential(nn.Flatten(), nn.Dropout(0.2), nn.Linear(16 * 96, 128), nn.ReLU(), nn.Dropout(0.3), nn.Linear(128, 64), nn.ReLU(), nn.Linear(64, 1))
    def forward(self, x): return self.f(x).squeeze(-1)


net = Net(); opt = torch.optim.AdamW(net.parameters(), lr=1e-3, weight_decay=1e-3)
STEPS = 10000
sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, STEPS)
bce = nn.BCEWithLogitsLoss(reduction='none')
T = lambda a: torch.from_numpy(np.ascontiguousarray(a, dtype=np.float32))


def batch():
    # Half the positives in Spanish, half in English, so neither phrase dominates
    ps = pos_es[np.random.randint(0, len(pos_es), 160)]; pe = pos_en[np.random.randint(0, len(pos_en), 160)]
    # ADV_EN > 1 over-samples the English traps ("Hey Lucy", "Hey Louie"…), the hardest negatives
    n_en = int(256 * ADV_EN / (1 + ADV_EN))
    ia = np.concatenate([np.random.randint(0, n_adv_es, 256 - n_en), np.random.randint(n_adv_es, len(adv), n_en)])
    iN = np.random.randint(0, len(neg), 160)
    iC = np.sort(np.random.choice(len(acav), 1024, replace=False))
    parts = [ps, pe, adv[ia], neg[iN], acav[iC].astype(np.float32)]
    if len(hard): parts.append(hard[np.random.randint(0, len(hard), 256)])
    x = np.concatenate(parts)
    y = np.concatenate([np.ones(320), np.zeros(len(x) - 320)])
    return T(x), T(y)


EVAL_PT = os.environ.get('EVAL_PT')  # evaluate an existing model on the same sets instead of training
if EVAL_PT:
    net.load_state_dict(torch.load(os.path.join(W, EVAL_PT)))
for step in range(0 if EVAL_PT else STEPS):
    x, y = batch()
    neg_w = 1 + (NEGW - 1) * min(1.0, step / (STEPS * 0.6))
    if step > 0 and step % 2000 == 0:
        net.eval()
        with torch.no_grad():
            sm = np.concatenate([torch.sigmoid(net(T(val_mine[i:i + 8192]))).numpy() for i in range(0, len(val_mine), 8192)])
        net.train()
        top = np.argsort(sm)[-4000:]
        hard = np.concatenate([hard, val_mine[top][sm[top] > 0.1]])[-20000:]
        print('hard negatives', len(hard), 'max', round(float(sm.max()), 3), flush=True)
    w = torch.where(y > 0, torch.tensor(1.0), torch.tensor(neg_w))
    loss = (bce(net(x), y) * w).mean()
    opt.zero_grad(); loss.backward(); opt.step(); sched.step()
    if step % 1000 == 0: print(step, round(loss.item(), 4), flush=True)

net.eval()


@torch.no_grad()
def score(a, bs=4096):
    return np.concatenate([torch.sigmoid(net(T(a[i:i + bs]))).numpy() for i in range(0, len(a), bs)])


win = np.lib.stride_tricks.sliding_window_view(val, (16, 96))[:, 0]
sv = score(win)
hours = len(val) * 0.08 / 3600


def fa_per_hour(th, patience=1):
    above = np.convolve((sv >= th).astype(int), np.ones(patience, int), 'valid') >= patience
    return (np.sum(above[1:] & ~above[:-1]) + int(above[0])) / hours


def stream_hit(S, th, patience=1):
    wins = np.lib.stride_tricks.sliding_window_view(S, (16, 96), axis=(1, 2))[:, :, 0]
    sc = score(wins.reshape(-1, 16, 96)).reshape(len(S), -1)
    return float(np.mean([(np.convolve((r >= th).astype(int), np.ones(patience, int), 'valid') >= patience).any() for r in sc]))


sets = {k: L(f'{k}.npy') for k in ['s_pos', 's_pos_es_phone', 's_pos_en', 's_adv', 's_adv_en']}
report = {}
for th in [0.2, 0.3, 0.4, 0.5, 0.6, 0.7]:
    r = {k: stream_hit(v, th) for k, v in sets.items()}
    r['fa_h'] = float(fa_per_hour(th))
    report[str(th)] = r
    print(f"th {th}: es {r['s_pos']:.3f} es-phone {r['s_pos_es_phone']:.3f} en {r['s_pos_en']:.3f} | traps es {r['s_adv']:.3f} en {r['s_adv_en']:.3f} | false/h {r['fa_h']:.2f}", flush=True)
if EVAL_PT:
    json.dump(report, open(os.path.join(W, f'report_{TAG}.json'), 'w'), indent=1)
    sys.exit(0)

layers = [m for m in net.f if isinstance(m, nn.Linear)]
with open(os.path.join(W, f'lumi_{TAG}.bin'), 'wb') as f:
    f.write(struct.pack('<i', len(layers)))
    for l in layers:
        wgt = l.weight.detach().numpy().astype('<f4'); b = l.bias.detach().numpy().astype('<f4')
        f.write(struct.pack('<ii', wgt.shape[1], wgt.shape[0])); f.write(wgt.tobytes()); f.write(b.tobytes())
pt, et = L('f_pos_test.npy'), L('f_pos_en_test.npy')
cases = [dict(x=pt[i].flatten().tolist(), y=float(score(pt[i:i + 1])[0])) for i in range(2)] + \
        [dict(x=et[i].flatten().tolist(), y=float(score(et[i:i + 1])[0])) for i in range(2)] + \
        [dict(x=L('f_adv_en_test.npy')[i].flatten().tolist(), y=float(score(L('f_adv_en_test.npy')[i:i + 1])[0])) for i in range(2)]
json.dump(cases, open(os.path.join(W, f'parity_{TAG}.json'), 'w'))
torch.save(net.state_dict(), os.path.join(W, f'lumi_{TAG}.pt'))
json.dump(report, open(os.path.join(W, f'report_{TAG}.json'), 'w'), indent=1)
print('ok', os.path.getsize(os.path.join(W, f'lumi_{TAG}.bin')), 'bytes')
