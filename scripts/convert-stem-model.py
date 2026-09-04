#!/usr/bin/env python3
"""
Converte o htdemucs para ONNX, no formato que o `OnnxStemBackend` espera.

Espelho do `scripts/convert-stem-model.py` do cadentia-ios (que gera Core ML):
o MESMO corte do modelo, as MESMAS entradas e saídas, para os dois apps
tocarem a mesma separação. O modelo pronto NÃO fica no repo (~160 MB fp32 /
~80 MB fp16): este script é a receita.

Por que um wrapper em vez de converter o modelo direto: o htdemucs começa com
uma STFT e termina com uma iSTFT, e nem Core ML nem ONNX Runtime Mobile lidam
bem com tensores complexos. Cortamos o modelo nos dois pontos onde números
complexos aparecem e convertemos só o miolo real, que contém 100% dos pesos
treinados. As transformadas vivem no app (`kit/StemSpectrogram.kt`, paridade
< 1e-5 com o PyTorch nas fixtures).

    app:  onda -> STFT -> real/imag como canais ---+
                                                   +--> [ONNX] --+
    app:  onda ------------------------------------+             |
    app:  espectro -> complexo -> iSTFT -> + saída temporal <-----+

Uso (no container de build; os pesos vêm de dl.fbaipublicfiles.com):
    python3 -m venv ~/stemenv && ~/stemenv/bin/pip install \
        torch==2.5.1 torchaudio==2.5.1 --index-url https://download.pytorch.org/whl/cpu
    ~/stemenv/bin/pip install demucs==4.0.1 onnx onnxruntime onnxscript
    ~/stemenv/bin/python scripts/convert-stem-model.py saida/separator.onnx [--fp16]

Saída: `separator.onnx` (+ `separator.onnx.sha256` e `separator.json` com as
formas), e a verificação numérica ONNX Runtime × PyTorch impressa no fim.
"""
import hashlib
import json
import sys
import time
from pathlib import Path

import numpy as np
import torch
from torch import nn


class HTDemucsCore(nn.Module):
    """O miolo real do HTDemucs: espectrograma entra, espectrograma sai.

    Copiado de demucs.htdemucs.HTDemucs.forward, sem o `_spec` do início e sem
    o `_mask`/`_ispec` do fim, que são exatamente as partes complexas. Idêntico
    ao wrapper do iOS.
    """

    def __init__(self, model):
        super().__init__()
        self.m = model

    def forward(self, mix, mag):
        """
        mix: (B, 2, L)          a onda crua, para o ramo temporal
        mag: (B, 4, Fq, T)      STFT de mix, real e imaginário como canais

        devolve
        spec: (B, S*4, Fq, T)   espectro previsto, achatado para rank 4
        wave: (B, S*2, L)       saída do ramo temporal, já desnormalizada
        """
        m = self.m
        x = mag
        B, C, Fq, T = x.shape

        mean = x.mean(dim=(1, 2, 3), keepdim=True)
        std = x.std(dim=(1, 2, 3), keepdim=True)
        x = (x - mean) / (1e-5 + std)

        xt = mix
        meant = xt.mean(dim=(1, 2), keepdim=True)
        stdt = xt.std(dim=(1, 2), keepdim=True)
        xt = (xt - meant) / (1e-5 + stdt)

        saved, saved_t, lengths, lengths_t = [], [], [], []
        for idx, encode in enumerate(m.encoder):
            lengths.append(x.shape[-1])
            inject = None
            if idx < len(m.tencoder):
                lengths_t.append(xt.shape[-1])
                tenc = m.tencoder[idx]
                xt = tenc(xt)
                if not tenc.empty:
                    saved_t.append(xt)
                else:
                    inject = xt
            x = encode(x, inject)
            if idx == 0 and m.freq_emb is not None:
                frs = torch.arange(x.shape[-2], device=x.device)
                emb = m.freq_emb(frs).t()[None, :, :, None].expand_as(x)
                x = x + m.freq_emb_scale * emb
            saved.append(x)

        if m.crosstransformer:
            if m.bottom_channels:
                b, c, f, t = x.shape
                x = x.reshape(b, c, f * t)
                x = m.channel_upsampler(x)
                x = x.reshape(b, -1, f, t)
                xt = m.channel_upsampler_t(xt)

            x, xt = m.crosstransformer(x, xt)

            if m.bottom_channels:
                b, c, f, t = x.shape
                x = x.reshape(b, c, f * t)
                x = m.channel_downsampler(x)
                x = x.reshape(b, -1, f, t)
                xt = m.channel_downsampler_t(xt)

        for idx, decode in enumerate(m.decoder):
            skip = saved.pop(-1)
            x, pre = decode(x, skip, lengths.pop(-1))
            offset = m.depth - len(m.tdecoder)
            if idx >= offset:
                tdec = m.tdecoder[idx - offset]
                length_t = lengths_t.pop(-1)
                if tdec.empty:
                    pre = pre[:, :, 0]
                    xt, _ = tdec(pre, None, length_t)
                else:
                    skip = saved_t.pop(-1)
                    xt, _ = tdec(xt, skip, length_t)

        S = len(m.sources)
        x = x.view(B, S, -1, Fq, T)
        x = x * std[:, None] + mean[:, None]
        x = x.reshape(B, S * x.shape[2], Fq, T)

        xt = xt.view(B, S, -1, mix.shape[-1])
        xt = xt * stdt[:, None] + meant[:, None]
        xt = xt.reshape(B, S * xt.shape[2], mix.shape[-1])
        return x, xt


class ChunkedAttention(nn.Module):
    """A MESMA atenção do `nn.MultiheadAttention` (pesos, escala, softmax,
    projeção), com as consultas divididas em blocos.

    Por que: o crosstransformer atende 2688 tokens espectrais contra 2688, e
    cada camada materializa [8, 2688, 2688] em float32 (231 MB) duas vezes
    (scores e softmax). Exportado direto, o ONNX Runtime chegou a 2,4 GB de
    pico por janela e o Android matou o app (lowmemorykiller no emulador de
    4 GB, 04/09). Em blocos de `chunks` consultas o pico da atenção cai na
    mesma proporção; o resultado é idêntico ao bit de arredondamento
    (conferido contra o original no fim do script).
    """

    def __init__(self, mha: nn.MultiheadAttention, chunks: int):
        super().__init__()
        assert mha.batch_first and mha._qkv_same_embed_dim
        self.num_heads = mha.num_heads
        self.embed_dim = mha.embed_dim
        self.head_dim = mha.embed_dim // mha.num_heads
        self.chunks = chunks
        w = mha.in_proj_weight
        b = mha.in_proj_bias
        e = self.embed_dim
        self.q_proj = nn.Linear(e, e)
        self.k_proj = nn.Linear(e, e)
        self.v_proj = nn.Linear(e, e)
        with torch.no_grad():
            self.q_proj.weight.copy_(w[:e]); self.q_proj.bias.copy_(b[:e])
            self.k_proj.weight.copy_(w[e:2 * e]); self.k_proj.bias.copy_(b[e:2 * e])
            self.v_proj.weight.copy_(w[2 * e:]); self.v_proj.bias.copy_(b[2 * e:])
        self.out_proj = mha.out_proj

    def forward(self, query, key, value, attn_mask=None, key_padding_mask=None, need_weights=False, is_causal=False):
        assert attn_mask is None and key_padding_mask is None
        B, S, _ = query.shape
        T = key.shape[1]
        N, D = self.num_heads, self.head_dim
        q = self.q_proj(query).view(B, S, N, D).transpose(1, 2)  # B N S D
        k = self.k_proj(key).view(B, T, N, D).transpose(1, 2)
        v = self.v_proj(value).view(B, T, N, D).transpose(1, 2)
        scale = 1.0 / (D ** 0.5)
        kt = k.transpose(-2, -1)
        outs = []
        step = (S + self.chunks - 1) // self.chunks
        for start in range(0, S, step):
            qc = q[:, :, start:start + step]
            scores = torch.matmul(qc, kt) * scale
            probs = torch.softmax(scores, dim=-1)
            outs.append(torch.matmul(probs, v))
        ctx = torch.cat(outs, dim=2).transpose(1, 2).reshape(B, S, self.embed_dim)
        return self.out_proj(ctx), None


class ChunkedConv2d(nn.Module):
    """O MESMO Conv2d 3×3 (pesos, padding 1), calculado em blocos ao longo do
    tempo com um quadro de borda de cada lado.

    Por que: o ONNX Runtime faz convolução por im2col, e o `rewrite` do
    decoder.3 (48→96 canais, 3×3 sobre 512×336) pede uma matriz de 48·9 ×
    512·336 floats: 297 MB de uma vez, só de área de trabalho. Em `chunks`
    blocos de tempo a área cai na mesma proporção, e o resultado é idêntico
    (a borda de um quadro é exatamente o que a janela 3×3 alcança).
    """

    def __init__(self, conv: nn.Conv2d, chunks: int):
        super().__init__()
        assert conv.kernel_size == (3, 3) and conv.padding == (1, 1) and conv.stride == (1, 1) and conv.groups == 1
        self.conv = conv
        self.chunks = chunks

    def forward(self, x):
        W = x.shape[-1]
        padded = torch.nn.functional.pad(x, (1, 1, 0, 0))
        step = (W + self.chunks - 1) // self.chunks
        outs = []
        for start in range(0, W, step):
            end = min(start + step, W)
            piece = padded[..., start:end + 2]
            outs.append(torch.nn.functional.conv2d(piece, self.conv.weight, self.conv.bias, stride=1, padding=(1, 0)))
        return torch.cat(outs, dim=-1)


def chunk_convs(model, chunks: int) -> int:
    """Troca cada Conv2d 3×3 (os `rewrite` dos decoders) pela versão em blocos."""
    swapped = 0
    for name, module in list(model.named_modules()):
        for child_name, child in list(module.named_children()):
            if isinstance(child, nn.Conv2d) and child.kernel_size == (3, 3):
                setattr(module, child_name, ChunkedConv2d(child, chunks))
                swapped += 1
    return swapped


def chunk_attention(model, chunks: int) -> int:
    """Troca cada MultiheadAttention do crosstransformer pela versão em blocos."""
    swapped = 0
    for layer in model.crosstransformer.layers:
        for name in ("self_attn", "cross_attn"):
            mha = getattr(layer, name, None)
            if isinstance(mha, nn.MultiheadAttention):
                setattr(layer, name, ChunkedAttention(mha, chunks))
                swapped += 1
    for layer in model.crosstransformer.layers_t:
        for name in ("self_attn", "cross_attn"):
            mha = getattr(layer, name, None)
            if isinstance(mha, nn.MultiheadAttention):
                setattr(layer, name, ChunkedAttention(mha, chunks))
                swapped += 1
    return swapped


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    chunks = 8
    for a in sys.argv[1:]:
        if a.startswith("--chunks="):
            chunks = int(a.split("=", 1)[1])
    fp16 = "--fp16" in sys.argv
    if len(args) < 1:
        print(__doc__)
        return 2
    destino = Path(args[0])
    destino.parent.mkdir(parents=True, exist_ok=True)

    from demucs.pretrained import get_model

    sub = get_model("htdemucs").models[0].eval()
    comprimento = int(sub.segment * sub.samplerate)  # 343980, o que ele treinou

    # Mesma troca do iOS: a atenção em eval vira o kernel fundido, que não
    # exporta; em train roda o caminho decomposto. Dropout zerado antes, e o
    # modo só nos módulos de atenção (diferença ~1e-6, verificada no iOS).
    for modulo in sub.modules():
        if isinstance(modulo, nn.Dropout):
            modulo.p = 0.0
        if isinstance(modulo, nn.MultiheadAttention):
            modulo.dropout = 0.0
            modulo.train()

    # NÃO chamar `.eval()` no núcleo depois disto: ele voltaria a atenção para
    # o kernel fundido (`_native_multi_head_attention`), que o ONNX não exporta.
    nucleo = HTDemucsCore(sub)
    for p in nucleo.parameters():
        p.requires_grad_(False)

    # Traçar com áudio de verdade, não com zeros: um sinal nulo percorre
    # ramos diferentes e o traçado sai errado sem avisar.
    torch.manual_seed(0)
    onda = torch.randn(1, 2, comprimento) * 0.1
    with torch.no_grad():
        mag = sub._magnitude(sub._spec(onda))
    print(f"traçando com {comprimento} amostras e espectro {tuple(mag.shape)}")

    # O esperado sai do modelo ORIGINAL (atenção do PyTorch); só depois a
    # atenção vira blocos, e a comparação no fim prova que nada mudou.
    with torch.no_grad():
        esperado_spec, esperado_wave = nucleo(onda, mag)
    trocadas = chunk_attention(sub, chunks)
    convs = chunk_convs(sub, chunks)
    print(f"atenção em {chunks} blocos em {trocadas} módulos; {convs} convoluções 3×3 em blocos")
    with torch.no_grad():
        bloco_spec, bloco_wave = nucleo(onda, mag)
    for nome, a, b in [("spec", esperado_spec, bloco_spec), ("wave", esperado_wave, bloco_wave)]:
        rel = float(((a - b) ** 2).sum().sqrt() / a.norm().clamp_min(1e-12)) * 100
        print(f"  atenção em blocos × original, {nome}: {rel:.6f}%")
        if rel > 0.01:
            print("  ATENÇÃO: a atenção em blocos divergiu do original")
            return 1

    inicio = time.time()
    with torch.no_grad():
        torch.onnx.export(
            nucleo,
            (onda, mag),
            str(destino),
            input_names=["mix", "mag"],
            output_names=["spec", "wave"],
            opset_version=17,
            do_constant_folding=True,
            # PRESERVE: o exportador põe o modelo em eval por padrão, e isso
            # desfaz o `.train()` da atenção e volta ao kernel fundido.
            training=torch.onnx.TrainingMode.PRESERVE,
            dynamo=False,
        )
    print(f"exportado em {time.time() - inicio:.0f} s: {destino} ({destino.stat().st_size / 1e6:.1f} MB)")

    import onnx

    modelo = onnx.load(str(destino))
    onnx.checker.check_model(modelo)

    if fp16:
        from onnxconverter_common import float16

        modelo16 = float16.convert_float_to_float16(modelo, keep_io_types=True)
        destino16 = destino.with_name(destino.stem + "-fp16.onnx")
        onnx.save(modelo16, str(destino16))
        print(f"fp16: {destino16} ({destino16.stat().st_size / 1e6:.1f} MB)")

    # Conferir que o convertido responde o mesmo que o original.
    import onnxruntime as ort

    for caminho in [destino] + ([destino.with_name(destino.stem + "-fp16.onnx")] if fp16 else []):
        sessao = ort.InferenceSession(str(caminho), providers=["CPUExecutionProvider"])
        inicio = time.time()
        saida = sessao.run(None, {"mix": onda.numpy(), "mag": mag.numpy()})
        print(f"{caminho.name}: inferência CPU de uma janela em {time.time() - inicio:.1f} s")
        pior = 0.0
        for nome, esperado, obtido in [("spec", esperado_spec, saida[0]), ("wave", esperado_wave, saida[1])]:
            alvo = esperado.numpy()
            obtido = np.asarray(obtido).reshape(alvo.shape)
            relativo = np.sqrt(((obtido - alvo) ** 2).sum() / max((alvo**2).sum(), 1e-12)) * 100
            pior = max(pior, relativo)
            print(f"  {nome}: erro relativo {relativo:.4f}%  forma {alvo.shape}")
        if pior > 2.0:
            print(f"  ATENÇÃO: {caminho.name} divergiu demais ({pior:.2f}%), não use este modelo")
            return 1
        digest = hashlib.sha256(caminho.read_bytes()).hexdigest()
        caminho.with_suffix(caminho.suffix + ".sha256").write_text(digest + "\n")
        print(f"  sha256 {digest}")

    (destino.parent / "separator.json").write_text(
        json.dumps(
            {
                "model": "htdemucs",
                "segment": comprimento,
                "sampleRate": int(sub.samplerate),
                "sources": list(sub.sources),
                "inputs": {"mix": [1, 2, comprimento], "mag": list(mag.shape)},
                "outputs": {"spec": list(esperado_spec.shape), "wave": list(esperado_wave.shape)},
                "opset": 17,
            },
            indent=2,
        )
        + "\n"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
