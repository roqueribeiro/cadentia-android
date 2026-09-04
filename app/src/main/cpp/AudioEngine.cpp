#include "AudioEngine.h"

#include <algorithm>
#include <chrono>
#include <cmath>

namespace cadentia {

namespace {
// Tamanhos dos combs/allpass do reverb Schroeder (Freeverb), em samples a
// 44,1 kHz — escalados para a taxa real na inicialização. Os mesmos papéis
// do room reverb do AudioDSP do iOS.
constexpr int kCombTunings[4] = {1116, 1188, 1277, 1356};
constexpr int kAllpassTunings[2] = {556, 441};
constexpr float kCombFeedback = 0.805f;
} // namespace

AudioEngine::AudioEngine() = default;

AudioEngine::~AudioEngine() {
    stop();
}

bool AudioEngine::start() {
    std::lock_guard<std::mutex> lock(mStreamMutex);
    if (mStream) return true;

    std::shared_ptr<oboe::AudioStream> stream = openStream();
    if (!stream) return false;

    mSampleRate = stream->getSampleRate();
    mFramesPerBurst.store(stream->getFramesPerBurst());

    initReverb();

    initCabinet();
    mDelayLineL.assign(static_cast<size_t>(mSampleRate) * 2, 0.0f);
    mDelayLineR.assign(static_cast<size_t>(mSampleRate) * 2, 0.0f);
    mDelayPos = 0;

    mReleaseRunning.store(true);
    mReleaseThread = std::thread(&AudioEngine::releaseLoop, this);

    if (stream->requestStart() != oboe::Result::OK) {
        stream->close();
        mReleaseRunning.store(false);
        if (mReleaseThread.joinable()) mReleaseThread.join();
        return false;
    }
    mStream = stream;
    return true;
}

std::shared_ptr<oboe::AudioStream> AudioEngine::openStream() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(2)
            // 48 kHz pedidos: se o aparelho novo for de outra taxa, o Oboe
            // converte e os buffers registrados continuam valendo.
            ->setSampleRate(48000)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setDataCallback(this)
            ->setErrorCallback(this);

    std::shared_ptr<oboe::AudioStream> stream;
    if (builder.openStream(stream) != oboe::Result::OK) {
        // EXCLUSIVE pode ser negado; o modo compartilhado ainda é low-latency.
        builder.setSharingMode(oboe::SharingMode::Shared);
        if (builder.openStream(stream) != oboe::Result::OK) return nullptr;
    }
    // Buffer no dobro do burst: o clássico equilíbrio latência × robustez.
    stream->setBufferSizeInFrames(stream->getFramesPerBurst() * 2);
    return stream;
}

// O stream morreu por baixo (fone ligado/desligado, Bluetooth, ligação): o
// Oboe já o fechou e chama aqui numa thread própria. Sem isto o app ficava
// mudo até ser reaberto — o iOS reencaminha sozinho no AVAudioSession.
// Reabrir aqui é o caminho documentado do Oboe. O relógio de frames e as
// vozes são nossos, não do stream: a música continua de onde estava.
void AudioEngine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    std::lock_guard<std::mutex> lock(mStreamMutex);
    if (!mStream) return; // `stop()` chegou antes: nada a reabrir.
    mStream.reset();
    std::shared_ptr<oboe::AudioStream> stream = openStream();
    if (!stream) {
        mRestartFailures.fetch_add(1);
        return;
    }
    mFramesPerBurst.store(stream->getFramesPerBurst());
    if (stream->requestStart() != oboe::Result::OK) {
        stream->close();
        mRestartFailures.fetch_add(1);
        return;
    }
    mStream = stream;
    mRestarts.fetch_add(1);
    (void)error;
}

void AudioEngine::stop() {
    {
        std::lock_guard<std::mutex> lock(mStreamMutex);
        if (mStream) {
            mStream->stop();
            mStream->close();
            mStream.reset();
        }
    }
    if (mReleaseRunning.exchange(false)) {
        if (mReleaseThread.joinable()) mReleaseThread.join();
    }
    // Fora do callback agora; limpar direto é seguro.
    for (auto& v : mVoices) v = Voice{};
    {
        std::lock_guard<std::mutex> lock(mBufferMutex);
        mBuffers.clear();
    }
    mCmdHead.store(0);
    mCmdTail.store(0);
    mRelHead.store(0);
    mRelTail.store(0);
    mFrameClock.store(0);
}

int32_t AudioEngine::xrunCount() const {
    std::lock_guard<std::mutex> lock(mStreamMutex);
    auto stream = mStream;
    if (!stream) return -1;
    auto result = stream->getXRunCount();
    return result ? result.value() : -1;
}

int32_t AudioEngine::bufferSizeInFrames() const {
    std::lock_guard<std::mutex> lock(mStreamMutex);
    auto stream = mStream;
    return stream ? stream->getBufferSizeInFrames() : 0;
}

void AudioEngine::registerBuffer(int32_t id, const float* interleaved, int32_t frames, int32_t channels) {
    auto buffer = std::make_shared<PcmBuffer>();
    buffer->frames = frames;
    buffer->channels = channels == 1 ? 1 : 2;
    buffer->interleaved.assign(
            interleaved, interleaved + static_cast<size_t>(frames) * static_cast<size_t>(buffer->channels));
    std::lock_guard<std::mutex> lock(mBufferMutex);
    mBuffers[id] = std::move(buffer);
}

void AudioEngine::releaseBuffer(int32_t id) {
    std::lock_guard<std::mutex> lock(mBufferMutex);
    mBuffers.erase(id);
    // Vozes que ainda tocam este buffer seguram o shared_ptr até o fim.
}

int64_t AudioEngine::schedule(int32_t id, int64_t atFrame, float gain, float pan, float rate) {
    std::shared_ptr<PcmBuffer> buffer;
    {
        std::lock_guard<std::mutex> lock(mBufferMutex);
        auto it = mBuffers.find(id);
        if (it == mBuffers.end()) return 0;
        buffer = it->second;
    }
    const int64_t tag = mNextTag.fetch_add(1);
    Command c{};
    c.type = CmdType::Schedule;
    c.atFrame = atFrame;
    c.voiceTag = tag;
    c.a = gain;
    c.b = std::min(1.0f, std::max(-1.0f, pan));
    c.c = std::min(8.0f, std::max(0.125f, rate));
    c.buffer = std::move(buffer);
    return pushCommand(std::move(c)) ? tag : 0;
}

void AudioEngine::setVoiceRate(int64_t voiceTag, float rate) {
    Command c{};
    c.type = CmdType::Rate;
    c.voiceTag = voiceTag;
    c.a = std::min(8.0f, std::max(0.125f, rate));
    pushCommand(std::move(c));
}

void AudioEngine::setDrive(bool enabled, float amount) {
    Command c{};
    c.type = CmdType::Drive;
    c.flag = enabled;
    c.a = std::min(1.0f, std::max(0.0f, amount));
    pushCommand(std::move(c));
}

void AudioEngine::setMasterGain(float gain) {
    Command c{};
    c.type = CmdType::Master;
    c.a = std::min(1.0f, std::max(0.0f, gain));
    pushCommand(std::move(c));
}

void AudioEngine::damp(int64_t voiceTag, float overSeconds) {
    Command c{};
    c.type = CmdType::Damp;
    c.voiceTag = voiceTag;
    c.a = overSeconds;
    pushCommand(std::move(c));
}

void AudioEngine::dampAll(float overSeconds) {
    Command c{};
    c.type = CmdType::DampAll;
    c.a = overSeconds;
    pushCommand(std::move(c));
}

void AudioEngine::setReverb(bool enabled, float mix) {
    Command c{};
    c.type = CmdType::Reverb;
    c.flag = enabled;
    c.a = mix;
    pushCommand(std::move(c));
}

void AudioEngine::setDelay(bool enabled, float timeMs, float feedback, float mix) {
    Command c{};
    c.type = CmdType::Delay;
    c.flag = enabled;
    c.a = timeMs;
    c.b = feedback;
    c.c = mix;
    pushCommand(std::move(c));
}

bool AudioEngine::pushCommand(Command&& c) {
    std::lock_guard<std::mutex> lock(mProducerMutex);
    const uint32_t tail = mCmdTail.load(std::memory_order_relaxed);
    const uint32_t head = mCmdHead.load(std::memory_order_acquire);
    if (tail - head >= kCmdCapacity) return false; // fila cheia: descarta, nunca trava
    mCmds[tail % kCmdCapacity] = std::move(c);
    mCmdTail.store(tail + 1, std::memory_order_release);
    return true;
}

void AudioEngine::drainCommands() {
    const uint32_t tail = mCmdTail.load(std::memory_order_acquire);
    uint32_t head = mCmdHead.load(std::memory_order_relaxed);
    while (head != tail) {
        Command& c = mCmds[head % kCmdCapacity];
        switch (c.type) {
            case CmdType::Schedule: {
                Voice& v = mVoices[mNextVoice];
                mNextVoice = (mNextVoice + 1) % kMaxVoices;
                if (v.active && v.buffer) retire(std::move(v.buffer)); // voz roubada
                v.buffer = std::move(c.buffer);
                v.startFrame = c.atFrame;
                v.pos = 0;
                v.fpos = 0.0;
                v.rate = c.c;
                {
                    // Pan de potência constante: -1 tudo à esquerda, 0 centro.
                    const float angle = (c.b + 1.0f) * 0.78539816f; // π/4
                    v.panL = std::cos(angle) * 1.41421356f;
                    v.panR = std::sin(angle) * 1.41421356f;
                }
                v.tag = c.voiceTag;
                v.gain = c.a;
                v.damp = 1.0f;
                v.dampStep = 0.0f;
                v.active = true;
                break;
            }
            case CmdType::Rate:
                for (auto& v : mVoices) {
                    if (v.active && v.tag == c.voiceTag) {
                        // Ao sair da taxa 1 a posição fracionária herda a inteira.
                        if (v.rate == 1.0f && c.a != 1.0f) v.fpos = static_cast<double>(v.pos);
                        v.rate = c.a;
                    }
                }
                break;
            case CmdType::Master:
                mMasterGain = c.a;
                break;
            case CmdType::Drive:
                mDriveOn = c.flag;
                // Mistura wet do drive: 10…90%, como o iOS (piso de 10%).
                mDriveMix = std::min(0.9f, std::max(0.1f, c.a * 0.9f));
                break;
            case CmdType::Damp:
                for (auto& v : mVoices) {
                    if (v.active && v.tag == c.voiceTag) {
                        v.dampStep = (c.a > 0)
                                ? 1.0f / (c.a * static_cast<float>(mSampleRate))
                                : 1.0f;
                    }
                }
                break;
            case CmdType::DampAll:
                for (auto& v : mVoices) {
                    if (v.active) {
                        v.dampStep = (c.a > 0)
                                ? 1.0f / (c.a * static_cast<float>(mSampleRate))
                                : 1.0f;
                    }
                }
                break;
            case CmdType::Reverb:
                mReverbOn = c.flag;
                mReverbMix = c.a;
                break;
            case CmdType::Delay:
                mDelayOn = c.flag;
                mDelayFeedback = c.b;
                mDelayMix = c.c;
                mDelaySamples = std::min<int32_t>(
                        static_cast<int32_t>(c.a * 0.001f * static_cast<float>(mSampleRate)),
                        static_cast<int32_t>(mDelayLineL.size()) - 1);
                break;
        }
        // Solta qualquer shared_ptr remanescente do slot fora daqui? Não:
        // Schedule moveu; os outros tipos não carregam buffer.
        c.buffer.reset();
        ++head;
        mCmdHead.store(head, std::memory_order_release);
    }
}

void AudioEngine::retire(std::shared_ptr<PcmBuffer>&& b) {
    const uint32_t tail = mRelTail.load(std::memory_order_relaxed);
    const uint32_t head = mRelHead.load(std::memory_order_acquire);
    if (tail - head >= kReleaseCapacity) {
        // Fila de liberação cheia: o pior caso é liberar aqui mesmo. Raro o
        // bastante para preferir um glitch improvável a vazar memória.
        b.reset();
        return;
    }
    mRelease[tail % kReleaseCapacity] = std::move(b);
    mRelTail.store(tail + 1, std::memory_order_release);
}

void AudioEngine::releaseLoop() {
    while (mReleaseRunning.load(std::memory_order_acquire)) {
        const uint32_t tail = mRelTail.load(std::memory_order_acquire);
        uint32_t head = mRelHead.load(std::memory_order_relaxed);
        while (head != tail) {
            mRelease[head % kReleaseCapacity].reset();
            ++head;
            mRelHead.store(head, std::memory_order_release);
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
    // Drena o resto ao sair.
    const uint32_t tail = mRelTail.load(std::memory_order_acquire);
    uint32_t head = mRelHead.load(std::memory_order_relaxed);
    while (head != tail) {
        mRelease[head % kReleaseCapacity].reset();
        ++head;
        mRelHead.store(head, std::memory_order_release);
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
        oboe::AudioStream* /*stream*/, void* audioData, int32_t numFrames) {
    auto* out = static_cast<float*>(audioData);
    std::fill(out, out + static_cast<size_t>(numFrames) * 2, 0.0f);

    drainCommands();

    const int64_t blockStart = mFrameClock.load(std::memory_order_relaxed);

    for (auto& v : mVoices) {
        if (!v.active || !v.buffer) continue;
        const PcmBuffer& buf = *v.buffer;

        for (int32_t i = 0; i < numFrames; ++i) {
            const int64_t frame = blockStart + i;
            if (frame < v.startFrame) continue;    // ainda não chegou a vez
            if (v.pos >= buf.frames) break;        // acabou dentro do bloco

            float g = v.gain * v.damp;
            if (v.dampStep > 0.0f) {
                v.damp -= v.dampStep;
                if (v.damp <= 0.0f) {
                    // Morreu abafada: NÃO lê mais nada deste buffer. A versão
                    // anterior fazia pos = frames e ainda lia interleaved[pos]
                    // — um frame além do fim. Quase sempre inofensivo; quando o
                    // vetor termina exatamente numa página do Scudo, é SIGSEGV
                    // na thread de áudio (achado no emulador, Gravador ao parar).
                    v.damp = 0.0f;
                    v.pos = buf.frames;
                    break;
                }
            }
            // `ch - 1` é o deslocamento do canal direito: 1 no estéreo, 0 no
            // mono (o mesmo sample nos dois lados; o pan da voz faz a imagem).
            const size_t ch = static_cast<size_t>(buf.channels);
            const float* s = buf.interleaved.data();
            if (v.rate == 1.0f) {
                const size_t base = static_cast<size_t>(v.pos) * ch;
                out[i * 2] += s[base] * g * v.panL;
                out[i * 2 + 1] += s[base + (ch - 1)] * g * v.panR;
                ++v.pos;
            } else {
                // Taxa != 1: leitura com interpolação linear entre dois frames.
                // É o varispeed do Cordas (bend, glissando, humanização de
                // ±0,25%). A posição inteira acompanha para o fim do buffer e
                // a evicção continuarem certos.
                const int32_t i0 = static_cast<int32_t>(v.fpos);
                if (i0 >= buf.frames) { v.pos = buf.frames; break; }
                const int32_t i1 = std::min(i0 + 1, buf.frames - 1);
                const float frac = static_cast<float>(v.fpos - static_cast<double>(i0));
                const size_t b0 = static_cast<size_t>(i0) * ch;
                const size_t b1 = static_cast<size_t>(i1) * ch;
                const float l = s[b0] * (1.0f - frac) + s[b1] * frac;
                const float r = s[b0 + (ch - 1)] * (1.0f - frac) + s[b1 + (ch - 1)] * frac;
                out[i * 2] += l * g * v.panL;
                out[i * 2 + 1] += r * g * v.panR;
                v.fpos += static_cast<double>(v.rate);
                v.pos = static_cast<int32_t>(v.fpos);
            }
        }

        if (v.pos >= buf.frames) {
            retire(std::move(v.buffer));
            v.active = false;
            v.tag = 0;
        }
    }

    renderBusEffects(out, numFrames);

    if (mMasterGain != 1.0f) {
        for (int32_t i = 0; i < numFrames * 2; ++i) out[i] *= mMasterGain;
    }

    // Limiter de pico no fim do bus (papel do PeakLimiter do iOS): ataque
    // instantâneo, release ~50 ms, teto -0,3 dBFS.
    const float ceiling = 0.966f;
    const float releasePerSample = 1.0f - std::exp(-1.0f / (0.05f * static_cast<float>(mSampleRate)));
    for (int32_t i = 0; i < numFrames; ++i) {
        const float peak = std::max(std::fabs(out[i * 2]), std::fabs(out[i * 2 + 1]));
        const float needed = (peak * mLimiterGain > ceiling) ? ceiling / peak : 1.0f;
        if (needed < mLimiterGain) {
            mLimiterGain = needed;
        } else {
            mLimiterGain += (1.0f - mLimiterGain) * releasePerSample;
        }
        out[i * 2] *= mLimiterGain;
        out[i * 2 + 1] *= mLimiterGain;
    }

    mFrameClock.store(blockStart + numFrames, std::memory_order_release);
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::initReverb() {
    const float scale = static_cast<float>(mSampleRate) / 44100.0f;
    for (int i = 0; i < 4; ++i) {
        const int n = static_cast<int>(std::lround(kCombTunings[i] * scale));
        mCombL[i].buf.assign(static_cast<size_t>(n), 0.0f);
        mCombR[i].buf.assign(static_cast<size_t>(n + 23), 0.0f); // stereo spread
        mCombL[i].pos = mCombR[i].pos = 0;
        mCombL[i].store = mCombR[i].store = 0.0f;
        mCombL[i].feedback = mCombR[i].feedback = kCombFeedback;
    }
    for (int i = 0; i < 2; ++i) {
        const int n = static_cast<int>(std::lround(kAllpassTunings[i] * scale));
        mApL[i].buf.assign(static_cast<size_t>(n), 0.0f);
        mApR[i].buf.assign(static_cast<size_t>(n + 23), 0.0f);
        mApL[i].pos = mApR[i].pos = 0;
    }
}

float AudioEngine::processReverb(Comb* combs, Allpass* aps, float in) {
    float acc = 0.0f;
    for (int i = 0; i < 4; ++i) {
        Comb& c = combs[i];
        const float output = c.buf[static_cast<size_t>(c.pos)];
        c.store = output * (1.0f - c.damp1) + c.store * c.damp1;
        c.buf[static_cast<size_t>(c.pos)] = in + c.store * c.feedback;
        if (++c.pos >= static_cast<int>(c.buf.size())) c.pos = 0;
        acc += output;
    }
    for (int i = 0; i < 2; ++i) {
        Allpass& a = aps[i];
        const float bufout = a.buf[static_cast<size_t>(a.pos)];
        const float output = -acc + bufout;
        a.buf[static_cast<size_t>(a.pos)] = acc + bufout * a.feedback;
        if (++a.pos >= static_cast<int>(a.buf.size())) a.pos = 0;
        acc = output;
    }
    return acc;
}

AudioEngine::Biquad AudioEngine::lowpass(float frequency, float q, float sampleRate) {
    Biquad f;
    const float w = 2.0f * 3.14159265f * frequency / sampleRate;
    const float alpha = std::sin(w) / (2.0f * q);
    const float cw = std::cos(w);
    const float a0 = 1.0f + alpha;
    f.b0 = ((1.0f - cw) / 2.0f) / a0; f.b1 = (1.0f - cw) / a0; f.b2 = f.b0;
    f.a1 = (-2.0f * cw) / a0; f.a2 = (1.0f - alpha) / a0;
    return f;
}

AudioEngine::Biquad AudioEngine::peaking(float frequency, float bandwidth, float gainDB, float sampleRate) {
    Biquad f;
    const float A = std::pow(10.0f, gainDB / 40.0f);
    const float w = 2.0f * 3.14159265f * frequency / sampleRate;
    const float sw = std::sin(w), cw = std::cos(w);
    // Q a partir da largura de banda em oitavas (a convenção do AVAudioUnitEQ).
    const float alpha = sw * std::sinh(0.6931472f / 2.0f * bandwidth * w / sw);
    const float a0 = 1.0f + alpha / A;
    f.b0 = (1.0f + alpha * A) / a0; f.b1 = (-2.0f * cw) / a0; f.b2 = (1.0f - alpha * A) / a0;
    f.a1 = (-2.0f * cw) / a0; f.a2 = (1.0f - alpha / A) / a0;
    return f;
}

AudioEngine::Biquad AudioEngine::highShelf(float frequency, float gainDB, float sampleRate) {
    Biquad f;
    const float A = std::pow(10.0f, gainDB / 40.0f);
    const float w = 2.0f * 3.14159265f * frequency / sampleRate;
    const float sw = std::sin(w), cw = std::cos(w);
    const float alpha = sw / 2.0f * std::sqrt((A + 1.0f / A) * (1.0f / 0.707f - 1.0f) + 2.0f);
    const float sqrtA2alpha = 2.0f * std::sqrt(A) * alpha;
    const float a0 = (A + 1.0f) - (A - 1.0f) * cw + sqrtA2alpha;
    f.b0 = (A * ((A + 1.0f) + (A - 1.0f) * cw + sqrtA2alpha)) / a0;
    f.b1 = (-2.0f * A * ((A - 1.0f) + (A + 1.0f) * cw)) / a0;
    f.b2 = (A * ((A + 1.0f) + (A - 1.0f) * cw - sqrtA2alpha)) / a0;
    f.a1 = (2.0f * ((A - 1.0f) - (A + 1.0f) * cw)) / a0;
    f.a2 = ((A + 1.0f) - (A - 1.0f) * cw - sqrtA2alpha) / a0;
    return f;
}

void AudioEngine::initCabinet() {
    const float sr = static_cast<float>(mSampleRate);
    for (int ch = 0; ch < 2; ++ch) {
        mCabinet[ch][0] = lowpass(4200.0f, 0.707f, sr);
        mCabinet[ch][1] = peaking(2100.0f, 0.7f, 4.0f, sr);
        mCabinet[ch][2] = highShelf(6000.0f, -14.0f, sr);
    }
}

void AudioEngine::renderBusEffects(float* out, int32_t numFrames) {
    if (mDriveOn) {
        // O `multiDistortedCubed` da Apple com preGain −6 dB: clip cúbico suave
        // em cima de uma entrada com ganho, misturado wet/dry, e o gabinete
        // depois — a ordem que um amplificador de verdade tem.
        const float pre = 0.5012f * 3.2f;   // −6 dB, e depois o drive
        for (int32_t i = 0; i < numFrames; ++i) {
            for (int ch = 0; ch < 2; ++ch) {
                const float dry = out[i * 2 + ch];
                float x = dry * pre;
                if (x > 1.0f) x = 1.0f; else if (x < -1.0f) x = -1.0f;
                const float wet = x - (x * x * x) / 3.0f;   // cúbico suave
                float mixed = dry * (1.0f - mDriveMix) + wet * 0.9f * mDriveMix;
                mixed = mCabinet[ch][0].process(mixed);
                mixed = mCabinet[ch][1].process(mixed);
                mixed = mCabinet[ch][2].process(mixed);
                out[i * 2 + ch] = mixed;
            }
        }
    }

    if (mDelayOn && mDelaySamples > 0) {
        const int32_t lineSize = static_cast<int32_t>(mDelayLineL.size());
        for (int32_t i = 0; i < numFrames; ++i) {
            int32_t readPos = mDelayPos - mDelaySamples;
            if (readPos < 0) readPos += lineSize;
            const float dl = mDelayLineL[static_cast<size_t>(readPos)];
            const float dr = mDelayLineR[static_cast<size_t>(readPos)];
            mDelayLineL[static_cast<size_t>(mDelayPos)] = out[i * 2] + dl * mDelayFeedback;
            mDelayLineR[static_cast<size_t>(mDelayPos)] = out[i * 2 + 1] + dr * mDelayFeedback;
            out[i * 2] += dl * mDelayMix;
            out[i * 2 + 1] += dr * mDelayMix;
            if (++mDelayPos >= lineSize) mDelayPos = 0;
        }
    }

    if (mReverbOn && mReverbMix > 0.0f) {
        for (int32_t i = 0; i < numFrames; ++i) {
            const float mono = (out[i * 2] + out[i * 2 + 1]) * 0.015f;
            out[i * 2] += processReverb(mCombL, mApL, mono) * mReverbMix;
            out[i * 2 + 1] += processReverb(mCombR, mApR, mono) * mReverbMix;
        }
    }
}

} // namespace cadentia
