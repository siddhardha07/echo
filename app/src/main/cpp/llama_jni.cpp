#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include "llama.h"

#define LOG_TAG "EchoLlama"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global state
static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static llama_sampler* g_sampler = nullptr;
static std::mutex g_generate_mutex;  // Ensure only one generation at a time
static std::atomic<bool> g_cancel_generation{false};  // Cancellation flag

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_echo_core_ai_LlamaCppEngine_nativeInit(JNIEnv* env, jobject, jstring model_path) {
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGD("Loading model from: %s", path);
    
    try {
        // Initialize backend
        llama_backend_init();
        
        // Load model
        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0; // CPU only for now
        
        g_model = llama_load_model_from_file(path, model_params);
        if (!g_model) {
            LOGE("Failed to load model");
            env->ReleaseStringUTFChars(model_path, path);
            return JNI_FALSE;
        }
        
        // Create context (optimized for maximum speed)
        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = 2048;  // Larger context for full responses
        ctx_params.n_batch = 512;
        ctx_params.n_threads = 8;
        ctx_params.n_threads_batch = 8;
        
        g_ctx = llama_new_context_with_model(g_model, ctx_params);
        if (!g_ctx) {
            LOGE("Failed to create context");
            llama_free_model(g_model);
            g_model = nullptr;
            env->ReleaseStringUTFChars(model_path, path);
            return JNI_FALSE;
        }
        
        // Create sampler (optimized for speed)
        auto sparams = llama_sampler_chain_default_params();
        g_sampler = llama_sampler_chain_init(sparams);
        llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(0.2f));  // Very low temp = faster
        llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(10));  // Minimal top-k = fastest
        llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        
        LOGD("Model loaded successfully");
        env->ReleaseStringUTFChars(model_path, path);
        return JNI_TRUE;
        
    } catch (const std::exception& e) {
        LOGE("Exception: %s", e.what());
        env->ReleaseStringUTFChars(model_path, path);
        return JNI_FALSE;
    }
}

JNIEXPORT jstring JNICALL
Java_com_echo_core_ai_LlamaCppEngine_nativeGenerate(JNIEnv* env, jobject, jstring prompt_str, jint max_tokens) {
    if (!g_model || !g_ctx || !g_sampler) {
        LOGE("Model not initialized");
        return env->NewStringUTF("Error: Model not initialized");
    }
    
    const char* prompt = env->GetStringUTFChars(prompt_str, nullptr);
    LOGD("Generating response for: %s", prompt);
    
    try {
        // Get vocab from model
        const llama_vocab* vocab = llama_model_get_vocab(g_model);
        
        // Tokenize prompt with correct parameters
        const int n_ctx = llama_n_ctx(g_ctx);
        std::vector<llama_token> tokens(n_ctx);
        const int n_tokens = llama_tokenize(
            vocab,
            prompt,
            strlen(prompt),
            tokens.data(),
            tokens.size(),
            true,  // add_special
            true   // parse_special
        );
        
        if (n_tokens < 0 || n_tokens >= n_ctx) {
            LOGE("Tokenization failed or prompt too long");
            env->ReleaseStringUTFChars(prompt_str, prompt);
            return env->NewStringUTF("Error: Prompt too long");
        }
        
        tokens.resize(n_tokens);
        
        // Create batch  
        llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
        
        // Decode prompt
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Failed to decode prompt");
            env->ReleaseStringUTFChars(prompt_str, prompt);
            return env->NewStringUTF("Error: Failed to decode");
        }
        
        // Generate response
        std::string response;
        int n_generated = 0;
        
        while (n_generated < max_tokens) {
            llama_token new_token = llama_sampler_sample(g_sampler, g_ctx, -1);
            
            // Check for EOS using correct API
            if (llama_vocab_is_eog(vocab, new_token)) {
                break;
            }
            
            // Accept the token (efficient internal update)
            llama_sampler_accept(g_sampler, new_token);
            
            // Convert token to text with correct parameters
            char buf[256];
            int n = llama_token_to_piece(
                vocab,
                new_token,
                buf,
                sizeof(buf),
                0,      // lstrip
                true    // special
            );
            
            if (n > 0) {
                response.append(buf, n);
            }
            
            // Decode single token for next iteration
            batch = llama_batch_get_one(&new_token, 1);
            
            if (llama_decode(g_ctx, batch) != 0) {
                break;
            }
            
            n_generated++;
        }
        
        env->ReleaseStringUTFChars(prompt_str, prompt);
        
        LOGD("Generated %d tokens", n_generated);
        return env->NewStringUTF(response.c_str());
        
    } catch (const std::exception& e) {
        LOGE("Exception during generation: %s", e.what());
        env->ReleaseStringUTFChars(prompt_str, prompt);
        return env->NewStringUTF("Error: Exception during generation");
    }
}

JNIEXPORT jstring JNICALL
Java_com_echo_core_ai_LlamaCppEngine_nativeGenerateStreaming(
    JNIEnv* env, 
    jobject obj,
    jstring prompt_str, 
    jint max_tokens,
    jobject callback
) {
    // Lock to ensure only one generation at a time
    std::lock_guard<std::mutex> lock(g_generate_mutex);
    
    // Reset cancellation flag
    g_cancel_generation.store(false);
    
    if (!g_model || !g_ctx || !g_sampler) {
        LOGE("Model not initialized");
        return env->NewStringUTF("Error: Model not initialized");
    }
    
    const char* prompt = env->GetStringUTFChars(prompt_str, nullptr);
    
    try {
        const llama_vocab* vocab = llama_model_get_vocab(g_model);
        
        // Tokenize prompt
        const int n_ctx = llama_n_ctx(g_ctx);
        std::vector<llama_token> tokens(n_ctx);
        const int n_tokens = llama_tokenize(
            vocab, prompt, strlen(prompt),
            tokens.data(), tokens.size(),
            true, true
        );
        
        if (n_tokens < 0 || n_tokens >= n_ctx) {
            env->ReleaseStringUTFChars(prompt_str, prompt);
            return env->NewStringUTF("Error: Prompt too long");
        }
        
        tokens.resize(n_tokens);
        
        // Decode prompt
        llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
        if (llama_decode(g_ctx, batch) != 0) {
            env->ReleaseStringUTFChars(prompt_str, prompt);
            return env->NewStringUTF("Error: Failed to decode");
        }
        
        // Get callback method
        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID invokeMethod = env->GetMethodID(callbackClass, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
        
        std::string response;
        int n_generated = 0;
        
        while (n_generated < max_tokens) {
            // Check cancellation flag
            if (g_cancel_generation.load()) {
                LOGD("Generation cancelled by user");
                break;
            }
            
            llama_token new_token = llama_sampler_sample(g_sampler, g_ctx, -1);
            
            if (llama_vocab_is_eog(vocab, new_token)) {
                break;
            }
            
            llama_sampler_accept(g_sampler, new_token);
            
            char buf[256];
            int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
            
            if (n > 0) {
                std::string token_text(buf, n);
                response.append(token_text);
                
                // Stream token to callback
                jstring jtoken = env->NewStringUTF(token_text.c_str());
                env->CallObjectMethod(callback, invokeMethod, jtoken);
                env->DeleteLocalRef(jtoken);
            }
            
            batch = llama_batch_get_one(&new_token, 1);
            if (llama_decode(g_ctx, batch) != 0) {
                break;
            }
            
            n_generated++;
        }
        
        env->ReleaseStringUTFChars(prompt_str, prompt);
        return env->NewStringUTF(response.c_str());
        
    } catch (const std::exception& e) {
        LOGE("Exception: %s", e.what());
        env->ReleaseStringUTFChars(prompt_str, prompt);
        return env->NewStringUTF("Error: Exception during generation");
    }
}

JNIEXPORT void JNICALL
Java_com_echo_core_ai_LlamaCppEngine_nativeCleanup(JNIEnv*, jobject) {
    LOGD("Cleaning up");
    
    if (g_sampler) {
        llama_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    
    llama_backend_free();
}

JNIEXPORT jboolean JNICALL
Java_com_echo_core_ai_LlamaCppEngine_nativeIsLoaded(JNIEnv*, jobject) {
    return (g_model != nullptr && g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_echo_core_ai_LlamaCppEngine_nativeCancelGeneration(JNIEnv*, jobject) {
    LOGD("Cancelling generation");
    g_cancel_generation.store(true);
}

} // extern "C"
