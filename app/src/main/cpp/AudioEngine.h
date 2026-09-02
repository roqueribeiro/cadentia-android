#ifndef CADENTIA_AUDIO_ENGINE_H
#define CADENTIA_AUDIO_ENGINE_H

#include <oboe/Oboe.h>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>

namespace cadentia {

// Um buffer PCM estéreo intercalado (L,R,L,R...), pré-renderizado pelo :kit.
// Imutável depois de criado; compartilhado por shared_ptr para que uma voz
// tocando sobreviva a uma evicção do cache no lado Kotlin.
struct PcmBuffer {
    std::vector<float> interleaved; // channels * frames
    int32_t frames = 0;
    // 2 = estéreo intercalado (bateria, piano); 1 = mono (Cordas: a corda é
    // mono e o pan é da voz, então guardar em estéreo dobraria o cache à toa).
    int32_t channels = 2;
};

// O motor de saída: UM stream Oboe (48 kHz estéreo, baixa latência) com um
// mixer próprio de vozes. O relógio compartilhado é o contador de frames do
// stream — agendar é "toque no frame N", sample-accurate por construção.
//
// Regra dura de tempo real: o callback de áudio NUNCA aloca, trava mutex, faz
// I/O ou libera memória. Comandos entram por uma fila SPSC; buffers que
// terminaram saem por uma fila de liberação drenada por uma thread de fundo.
class AudioEngine : public oboe::AudioStreamDataCallback {
public:
    AudioEngine();
    ~AudioEngine();

    bool start();
    void stop();

    int32_t sampleRate() const { return mSampleRate; }
    int32_t framesPerBurst() const { return mFramesPerBurst.load(); }
    // Quantas vezes o callback não chegou a tempo desde o start (Oboe): a
    // medição honesta de "aumentou a latência?" no aparelho. -1 = sem stream.
    int32_t xrunCount() const;
    // Tamanho real do buffer do stream, em frames (latência estrutural = buffer / taxa).
    int32_t bufferSizeInFrames() const;
    // Relógio compartilhado: total de frames já entregues ao stream.
    int64_t nowFrames() const { return mFrameClock.load(std::memory_order_acquire); }

    // Registra/atualiza um buffer por id (lado não-áudio).
    void registerBuffer(int32_t id, const float* interleaved, int32_t frames, int32_t channels = 2);
    void releaseBuffer(int32_t id);

    // Agenda o buffer `id` para começar em `atFrame` (ou já, se <= agora),
    // com ganho `gain`, pan -1…1 (potência constante) e taxa de leitura
    // `rate` (1 = normal; 2 = uma oitava acima). Devolve um voiceTag > 0
    // para poder abafar, dobrar (bend) ou deslizar depois.
    int64_t schedule(int32_t id, int64_t atFrame, float gain, float pan = 0.0f, float rate = 1.0f);
    void damp(int64_t voiceTag, float overSeconds);
    void dampAll(float overSeconds);
    // Muda a taxa de uma voz que já toca: é o bend e o glissando do Cordas.
    void setVoiceRate(int64_t voiceTag, float rate);

    void setReverb(bool enabled, float mix);       // room do bus (bateria)
    void setDelay(bool enabled, float timeMs, float feedback, float mix);
    // O barramento elétrico do Cordas: drive cúbico + gabinete de guitarra
    // (passa-baixa 4200 Hz, realce 2100 Hz +4 dB, shelf 6000 Hz −14 dB),
    // ANTES do reverb. Não linear, então tem que ser ao vivo na mistura.
    void setDrive(bool enabled, float amount);
    // Volume mestre do bus (0…1), aplicado antes do limiter: o controle de
    // volume do painel do Cordas, ao vivo sobre o que já soa.
    void setMasterGain(float gain);

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;

private:
    static constexpr int kMaxVoices = 24;
    static constexpr int kCmdCapacity = 1024;
    static constexpr int kReleaseCapacity = 1024;

    enum class CmdType { Schedule, Damp, DampAll, Reverb, Delay, Rate, Drive, Master };
    struct Command {
        CmdType type;
        int64_t atFrame;
        int64_t voiceTag;
        float a, b, c;              // params (gain / mix / time / feedback / pan / rate)
        bool flag;
        std::shared_ptr<PcmBuffer> buffer;
    };

    struct Voice {
        std::shared_ptr<PcmBuffer> buffer;
        int64_t startFrame = 0;     // frame do stream em que começa
        int32_t pos = 0;            // frame lido dentro do buffer (taxa 1)
        double fpos = 0.0;          // posição fracionária quando a taxa != 1
        float rate = 1.0f;          // taxa de leitura (bend/glissando)
        float panL = 1.0f, panR = 1.0f;
        int64_t tag = 0;
        float gain = 1.0f;
        float damp = 1.0f;          // multiplicador atual do fade de release
        float dampStep = 0.0f;      // decremento por frame quando abafando
        bool active = false;
    };

    // Fila de comandos: 1 consumidor (áudio), N produtores (UI + coroutines);
    // produtores serializados por mutex, consumidor lock-free.
    Command mCmds[kCmdCapacity];
    std::atomic<uint32_t> mCmdHead{0}; // consumidor
    std::atomic<uint32_t> mCmdTail{0}; // produtor
    std::mutex mProducerMutex;
    bool pushCommand(Command&& c);
    void drainCommands();

    // Fila de liberação: áudio empurra buffers terminados; thread de fundo
    // drena e os destrói fora da thread de tempo real.
    std::shared_ptr<PcmBuffer> mRelease[kReleaseCapacity];
    std::atomic<uint32_t> mRelHead{0};
    std::atomic<uint32_t> mRelTail{0};
    std::thread mReleaseThread;
    std::atomic<bool> mReleaseRunning{false};
    void releaseLoop();
    void retire(std::shared_ptr<PcmBuffer>&& b);

    // Registro de buffers (lado não-áudio).
    std::unordered_map<int32_t, std::shared_ptr<PcmBuffer>> mBuffers;
    std::mutex mBufferMutex;

    Voice mVoices[kMaxVoices];
    int mNextVoice = 0;
    std::atomic<int64_t> mNextTag{1};

    std::shared_ptr<oboe::AudioStream> mStream;
    int32_t mSampleRate = 48000;
    std::atomic<int32_t> mFramesPerBurst{192};
    std::atomic<int64_t> mFrameClock{0};

    // Efeitos de bus (portados do AudioDSP: reverb Schroeder + delay simples).
    void renderBusEffects(float* out, int32_t numFrames);
    bool mReverbOn = false;
    float mReverbMix = 0.0f;
    bool mDelayOn = false;
    float mDelayMix = 0.0f;
    float mDelayFeedback = 0.0f;
    int32_t mDelaySamples = 0;
    std::vector<float> mDelayLineL, mDelayLineR;
    int32_t mDelayPos = 0;
    // 4 combs + 2 allpass por canal.
    struct Comb { std::vector<float> buf; int pos = 0; float store = 0.0f; float feedback; float damp1 = 0.2f, damp2 = 0.0f; };
    struct Allpass { std::vector<float> buf; int pos = 0; float feedback = 0.5f; };
    Comb mCombL[4], mCombR[4];
    Allpass mApL[2], mApR[2];
    void initReverb();
    float processReverb(Comb* combs, Allpass* aps, float in);

    // Barramento elétrico (Cordas): drive + gabinete, biquads RBJ por canal.
    struct Biquad {
        float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
        float x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        float process(float x) {
            const float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x; y2 = y1; y1 = y;
            return y;
        }
    };
    static Biquad lowpass(float frequency, float q, float sampleRate);
    static Biquad peaking(float frequency, float bandwidth, float gainDB, float sampleRate);
    static Biquad highShelf(float frequency, float gainDB, float sampleRate);
    float mMasterGain = 1.0f;
    bool mDriveOn = false;
    float mDriveMix = 0.0f;        // 0…1 (wet)
    Biquad mCabinet[2][3];         // [canal][lowpass, peaking, shelf]
    void initCabinet();

    // Limiter de pico no fim do bus (o mesmo papel do PeakLimiter da Apple).
    float mLimiterGain = 1.0f;
};

} // namespace cadentia

#endif
