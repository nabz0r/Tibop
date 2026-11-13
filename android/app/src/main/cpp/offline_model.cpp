#include <jni.h>
#include <llama.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
constexpr int max_tokens = 384;
constexpr int max_choices = 8;
constexpr int model_load_failed = -2;
using Clock = std::chrono::steady_clock;

std::string utf(JNIEnv *env, jstring value) {
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::runtime_error("JNI string allocation failed");
    auto release = [&](const char *text) { env->ReleaseStringUTFChars(value, text); };
    std::unique_ptr<const char, decltype(release)> holder(chars, release);
    return std::string(chars);
}

int choose(const std::string &path, const std::vector<std::string> &prompts, int threads) {
    static const bool initialized = [] {
        llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
        llama_backend_init();
        return true;
    }();
    (void) initialized;

    auto model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.use_mmap = true;
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
        llama_model_load_from_file(path.c_str(), model_params), llama_model_free);
    if (!model) return model_load_failed;

    auto deadline = Clock::now() + std::chrono::seconds(15);
    auto params = llama_context_default_params();
    params.n_ctx = max_tokens;
    params.n_batch = max_tokens;
    params.n_ubatch = max_tokens;
    params.n_threads = params.n_threads_batch = std::clamp(threads, 1, 2);
    params.abort_callback = [](void *data) {
        return Clock::now() >= *static_cast<Clock::time_point *>(data);
    };
    params.abort_callback_data = &deadline;
    std::unique_ptr<llama_context, decltype(&llama_free)> context(
        llama_init_from_model(model.get(), params), llama_free);
    if (!context) throw std::runtime_error("Model context initialization failed");

    const auto *vocab = llama_model_get_vocab(model.get());
    auto answer_ids = [vocab](const std::string &answer) {
        std::vector<llama_token> ids;
        for (const auto &spelling : {answer, " " + answer}) {
            llama_token id;
            if (llama_tokenize(vocab, spelling.data(), static_cast<int>(spelling.size()),
                    &id, 1, false, false) == 1) ids.push_back(id);
        }
        if (ids.empty()) throw std::runtime_error("Model vocabulary has no supported answer");
        return ids;
    };
    const auto yes = answer_ids("Yes");
    const auto no = answer_ids("No");
    auto max_logit = [](const std::vector<llama_token> &ids, const float *logits) {
        float best = -std::numeric_limits<float>::infinity();
        for (llama_token id : ids) best = std::max(best, logits[id]);
        return best;
    };

    // Score each explanation in the same prompt position to avoid a choice-letter bias.
    int best = -1;
    float best_score = -std::numeric_limits<float>::infinity();
    for (size_t i = 0; i < prompts.size(); ++i) {
        const auto &prompt = prompts[i];
        std::array<llama_token, max_tokens> tokens{};
        const int count = llama_tokenize(vocab, prompt.data(), static_cast<int>(prompt.size()),
            tokens.data(), tokens.size(), false, true);
        if (count <= 0) throw std::runtime_error("Model prompt exceeds its token budget");
        llama_memory_clear(llama_get_memory(context.get()), false);
        if (llama_decode(context.get(), llama_batch_get_one(tokens.data(), count)) != 0) {
            throw std::runtime_error("Model inference failed or timed out");
        }

        const auto *logits = llama_get_logits_ith(context.get(), -1);
        if (!logits) throw std::runtime_error("Model returned no next-token probabilities");
        const float score = max_logit(yes, logits) - max_logit(no, logits);
        if (std::isfinite(score) && score > best_score) {
            best_score = score;
            best = static_cast<int>(i);
        }
    }
    if (best < 0) throw std::runtime_error("Model returned no supported choice");
    return best;
}
} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_fr_lettrine_game_OfflineMoveModel_chooseNative(
        JNIEnv *env, jclass, jstring model_path, jobjectArray candidates, jint threads) {
    try {
        const jsize count = env->GetArrayLength(candidates);
        if (count < 1 || count > max_choices) throw std::runtime_error("Invalid model choices");
        std::vector<std::string> prompts;
        for (jsize i = 0; i < count; ++i) {
            auto prompt = static_cast<jstring>(env->GetObjectArrayElement(candidates, i));
            if (!prompt) throw std::runtime_error("Invalid model prompt");
            prompts.push_back(utf(env, prompt));
            env->DeleteLocalRef(prompt);
        }
        return choose(utf(env, model_path), prompts, threads);
    } catch (const std::exception &error) {
        if (!env->ExceptionCheck()) {
            if (auto type = env->FindClass("java/io/IOException")) env->ThrowNew(type, error.what());
        }
        return -1;
    }
}
