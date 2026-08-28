#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cerrno>
#include <climits>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <limits>
#include <memory>
#include <mutex>
#include <queue>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unordered_map>
#include <utility>
#include <vector>

#include "hnswlib/hnswlib.h"

namespace {

constexpr std::size_t kMaximumPathBytes = 4096;
constexpr std::size_t kMaximumDimension = 4096;
constexpr std::size_t kMaximumElements = 10000000;
constexpr std::size_t kMaximumM = 128;
constexpr std::size_t kMaximumEf = 1000000;
constexpr std::uint64_t kMaximumIndexBytes = 8ULL * 1024ULL * 1024ULL * 1024ULL;

struct NativeIndex {
    NativeIndex(std::size_t dimension_value, std::size_t maximum_elements, std::size_t m,
                std::size_t ef_construction, std::string root)
        : dimension(dimension_value), index_root(std::move(root)),
          space(std::make_unique<hnswlib::InnerProductSpace>(dimension_value)),
          index(std::make_unique<hnswlib::HierarchicalNSW<float>>(
              space.get(), maximum_elements, m, ef_construction)) {}

    NativeIndex(std::size_t dimension_value, std::size_t maximum_elements, std::string path,
                std::string root)
        : dimension(dimension_value), index_root(std::move(root)),
          space(std::make_unique<hnswlib::InnerProductSpace>(dimension_value)),
          index(std::make_unique<hnswlib::HierarchicalNSW<float>>(
              space.get(), path, false, maximum_elements, false)) {}

    const std::size_t dimension;
    const std::string index_root;
    std::unique_ptr<hnswlib::InnerProductSpace> space;
    std::unique_ptr<hnswlib::HierarchicalNSW<float>> index;
    std::mutex mutex;
};

std::mutex g_handles_mutex;
std::unordered_map<jlong, std::shared_ptr<NativeIndex>> g_handles;
std::atomic<jlong> g_next_handle{1};

class UtfChars final {
  public:
    UtfChars(JNIEnv *env, jstring value) : env_(env), value_(value) {
        if (value_ == nullptr) throw std::invalid_argument("HNSW path is null");
        chars_ = env_->GetStringUTFChars(value_, nullptr);
        if (chars_ == nullptr) throw std::runtime_error("Unable to read HNSW path");
    }

    ~UtfChars() {
        if (chars_ != nullptr) env_->ReleaseStringUTFChars(value_, chars_);
    }

    std::string str() const {
        const std::size_t length = std::strlen(chars_);
        if (length == 0 || length > kMaximumPathBytes) {
            throw std::invalid_argument("HNSW path length is invalid");
        }
        return std::string(chars_, length);
    }

  private:
    JNIEnv *env_;
    jstring value_;
    const char *chars_{nullptr};
};

void throw_java(JNIEnv *env, const char *class_name, const std::string &message) {
    if (env->ExceptionCheck()) return;
    jclass error_class = env->FindClass(class_name);
    if (error_class != nullptr) env->ThrowNew(error_class, message.c_str());
}

template <typename Result, typename Function>
Result jni_guard(JNIEnv *env, Result failure, Function &&function) {
    try {
        return function();
    } catch (const std::invalid_argument &error) {
        throw_java(env, "java/lang/IllegalArgumentException", error.what());
    } catch (const std::ios_base::failure &error) {
        throw_java(env, "java/io/IOException", error.what());
    } catch (const std::bad_alloc &) {
        throw_java(env, "java/lang/OutOfMemoryError", "HNSW allocation failed");
    } catch (const std::exception &error) {
        throw_java(env, "java/lang/IllegalStateException", error.what());
    } catch (...) {
        throw_java(env, "java/lang/IllegalStateException", "Unknown native HNSW failure");
    }
    return failure;
}

template <typename Function>
void jni_guard_void(JNIEnv *env, Function &&function) {
    (void)jni_guard<int>(env, 0, [&]() {
        function();
        return 1;
    });
}

std::shared_ptr<NativeIndex> require_handle(jlong handle) {
    if (handle <= 0) throw std::runtime_error("HNSW index handle is closed");
    std::lock_guard<std::mutex> lock(g_handles_mutex);
    auto found = g_handles.find(handle);
    if (found == g_handles.end()) throw std::runtime_error("HNSW index handle is closed");
    return found->second;
}

jlong register_handle(std::shared_ptr<NativeIndex> index) {
    jlong handle = g_next_handle.fetch_add(1);
    if (handle <= 0) throw std::runtime_error("HNSW handle space exhausted");
    std::lock_guard<std::mutex> lock(g_handles_mutex);
    g_handles.emplace(handle, std::move(index));
    return handle;
}

std::string canonical_existing_directory(const std::string &path) {
    char resolved[PATH_MAX];
    if (realpath(path.c_str(), resolved) == nullptr) {
        throw std::invalid_argument("HNSW index directory is unavailable");
    }
    struct stat status {};
    if (stat(resolved, &status) != 0 || !S_ISDIR(status.st_mode)) {
        throw std::invalid_argument("HNSW index directory is unavailable");
    }
    return std::string(resolved);
}

bool safe_file_name(const std::string &name) {
    if (name.empty() || name.size() > 128 || !std::isalnum(static_cast<unsigned char>(name.front()))) {
        return false;
    }
    return std::all_of(name.begin(), name.end(), [](unsigned char value) {
        return std::isalnum(value) || value == '.' || value == '_' || value == '-';
    });
}

std::string require_managed_path(const std::string &root, const std::string &candidate,
                                 bool must_exist) {
    if (candidate.empty() || candidate.size() > kMaximumPathBytes) {
        throw std::invalid_argument("HNSW index path length is invalid");
    }
    const std::size_t slash = candidate.find_last_of('/');
    if (slash == std::string::npos || !safe_file_name(candidate.substr(slash + 1))) {
        throw std::invalid_argument("HNSW index file name is invalid");
    }
    const std::string parent = canonical_existing_directory(candidate.substr(0, slash));
    if (parent != root) throw std::invalid_argument("HNSW path escapes its dedicated directory");

    struct stat link_status {};
    if (lstat(candidate.c_str(), &link_status) == 0 && S_ISLNK(link_status.st_mode)) {
        throw std::invalid_argument("HNSW index path must not be a symbolic link");
    }
    if (must_exist) {
        struct stat status {};
        if (stat(candidate.c_str(), &status) != 0 || !S_ISREG(status.st_mode) || status.st_size <= 0 ||
            static_cast<std::uint64_t>(status.st_size) > kMaximumIndexBytes) {
            throw std::invalid_argument("HNSW index file is unavailable");
        }
    }
    return candidate;
}

std::vector<float> normalized_vector(JNIEnv *env, jfloatArray values, std::size_t dimension) {
    if (values == nullptr || static_cast<std::size_t>(env->GetArrayLength(values)) != dimension) {
        throw std::invalid_argument("HNSW vector dimension mismatch");
    }
    std::vector<float> result(dimension);
    env->GetFloatArrayRegion(values, 0, static_cast<jsize>(dimension), result.data());
    if (env->ExceptionCheck()) throw std::runtime_error("Unable to read HNSW vector");
    double squared_norm = 0.0;
    for (float value : result) {
        if (!std::isfinite(value)) throw std::invalid_argument("HNSW vector contains a non-finite value");
        squared_norm += static_cast<double>(value) * static_cast<double>(value);
    }
    if (!std::isfinite(squared_norm) || squared_norm <= 0.0) {
        throw std::invalid_argument("HNSW vector norm must be positive");
    }
    const float inverse_norm = static_cast<float>(1.0 / std::sqrt(squared_norm));
    for (float &value : result) value *= inverse_norm;
    return result;
}

template <typename Value>
Value read_pod(std::ifstream &input) {
    Value value{};
    input.read(reinterpret_cast<char *>(&value), sizeof(value));
    if (!input) throw std::invalid_argument("Truncated HNSW index header");
    return value;
}

void validate_index_header(const std::string &path, std::size_t dimension,
                           std::size_t maximum_elements) {
    std::ifstream input(path, std::ios::binary);
    if (!input.is_open()) throw std::invalid_argument("Cannot open HNSW index");
    const std::size_t offset_level_zero = read_pod<std::size_t>(input);
    const std::size_t stored_maximum = read_pod<std::size_t>(input);
    const std::size_t stored_count = read_pod<std::size_t>(input);
    const std::size_t bytes_per_element = read_pod<std::size_t>(input);
    const std::size_t label_offset = read_pod<std::size_t>(input);
    const std::size_t data_offset = read_pod<std::size_t>(input);
    (void)read_pod<int>(input);
    (void)read_pod<hnswlib::tableint>(input);
    const std::size_t maximum_m = read_pod<std::size_t>(input);
    const std::size_t maximum_m_zero = read_pod<std::size_t>(input);
    const std::size_t m = read_pod<std::size_t>(input);
    const double multiplier = read_pod<double>(input);
    const std::size_t ef_construction = read_pod<std::size_t>(input);

    if (offset_level_zero != 0 || stored_count == 0 || stored_count > stored_maximum ||
        stored_maximum > maximum_elements || maximum_m == 0 || maximum_m > kMaximumM ||
        maximum_m_zero != maximum_m * 2 || m != maximum_m || ef_construction < m ||
        ef_construction > kMaximumEf || !std::isfinite(multiplier) || multiplier <= 0.0) {
        throw std::invalid_argument("Invalid or incompatible HNSW index header");
    }
    const std::size_t expected_data_offset =
        maximum_m_zero * sizeof(hnswlib::tableint) + sizeof(hnswlib::linklistsizeint);
    const std::size_t expected_label_offset = expected_data_offset + dimension * sizeof(float);
    const std::size_t expected_bytes = expected_label_offset + sizeof(hnswlib::labeltype);
    if (
        data_offset != expected_data_offset || label_offset != expected_label_offset ||
        bytes_per_element != expected_bytes) {
        throw std::invalid_argument("Invalid or incompatible HNSW index header");
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_minicpm_1v_1demo_rag_index_HnswNative_nativeCreate(
    JNIEnv *env, jobject, jstring index_directory, jint dimension, jint maximum_elements, jint m,
    jint ef_construction) {
    return jni_guard<jlong>(env, 0, [&]() {
        if (dimension <= 0 || dimension > static_cast<jint>(kMaximumDimension) || maximum_elements <= 0 ||
            maximum_elements > static_cast<jint>(kMaximumElements) || m < 2 ||
            m > static_cast<jint>(kMaximumM) || ef_construction < m ||
            ef_construction > static_cast<jint>(kMaximumEf)) {
            throw std::invalid_argument("Invalid HNSW construction parameters");
        }
        const std::string root = canonical_existing_directory(UtfChars(env, index_directory).str());
        return register_handle(std::make_shared<NativeIndex>(
            static_cast<std::size_t>(dimension), static_cast<std::size_t>(maximum_elements),
            static_cast<std::size_t>(m), static_cast<std::size_t>(ef_construction), root));
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_minicpm_1v_1demo_rag_index_HnswNative_nativeLoad(
    JNIEnv *env, jobject, jstring index_directory, jstring index_file, jint dimension,
    jint maximum_elements) {
    return jni_guard<jlong>(env, 0, [&]() {
        if (dimension <= 0 || dimension > static_cast<jint>(kMaximumDimension) || maximum_elements <= 0 ||
            maximum_elements > static_cast<jint>(kMaximumElements)) {
            throw std::invalid_argument("Invalid HNSW load parameters");
        }
        const std::string root = canonical_existing_directory(UtfChars(env, index_directory).str());
        const std::string path = require_managed_path(root, UtfChars(env, index_file).str(), true);
        std::shared_ptr<NativeIndex> loaded;
        try {
            validate_index_header(path, static_cast<std::size_t>(dimension),
                                  static_cast<std::size_t>(maximum_elements));
            loaded = std::make_shared<NativeIndex>(
                static_cast<std::size_t>(dimension), static_cast<std::size_t>(maximum_elements),
                path, root);
        } catch (const std::bad_alloc &) {
            throw;
        } catch (const std::exception &error) {
            throw std::ios_base::failure(
                std::string("Invalid HNSW index file: ") + error.what());
        }
        return register_handle(std::move(loaded));
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_minicpm_1v_1demo_rag_index_HnswNative_nativeAdd(
    JNIEnv *env, jobject, jlong handle, jlong chunk_id, jfloatArray vector) {
    jni_guard_void(env, [&]() {
        if (chunk_id < 0) throw std::invalid_argument("HNSW chunk ID must be non-negative");
        auto native = require_handle(handle);
        auto values = normalized_vector(env, vector, native->dimension);
        std::lock_guard<std::mutex> lock(native->mutex);
        const hnswlib::labeltype label = static_cast<hnswlib::labeltype>(chunk_id);
        if (native->index->label_lookup_.find(label) != native->index->label_lookup_.end()) {
            throw std::invalid_argument("Duplicate HNSW chunk ID");
        }
        native->index->addPoint(values.data(), label, false);
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_example_minicpm_1v_1demo_rag_index_HnswNative_nativeSearch(
    JNIEnv *env, jobject, jlong handle, jfloatArray query, jint top_k, jint ef_search) {
    return jni_guard<jobject>(env, nullptr, [&]() -> jobject {
        if (top_k <= 0 || ef_search < top_k || ef_search > static_cast<jint>(kMaximumEf)) {
            throw std::invalid_argument("Invalid HNSW search parameters");
        }
        auto native = require_handle(handle);
        auto values = normalized_vector(env, query, native->dimension);
        std::vector<std::pair<jlong, jfloat>> results;
        {
            std::lock_guard<std::mutex> lock(native->mutex);
            const std::size_t indexed_elements = native->index->cur_element_count.load();
            if (static_cast<std::size_t>(top_k) > indexed_elements) {
                throw std::invalid_argument("HNSW topK exceeds indexed elements");
            }
            native->index->setEf(static_cast<std::size_t>(ef_search));
            const std::size_t candidate_count = std::min(
                indexed_elements,
                std::max(static_cast<std::size_t>(top_k), static_cast<std::size_t>(ef_search)));
            auto queue = native->index->searchKnn(values.data(), candidate_count);
            while (!queue.empty()) {
                const auto item = queue.top();
                queue.pop();
                const float similarity = 1.0f - item.first;
                if (!std::isfinite(similarity) ||
                    item.second > static_cast<hnswlib::labeltype>(std::numeric_limits<jlong>::max())) {
                    throw std::runtime_error("Invalid native HNSW search result");
                }
                results.emplace_back(static_cast<jlong>(item.second), similarity);
            }
        }
        std::sort(results.begin(), results.end(), [](const auto &left, const auto &right) {
            return left.second != right.second ? left.second > right.second : left.first < right.first;
        });
        results.resize(static_cast<std::size_t>(top_k));

        jlongArray ids = env->NewLongArray(static_cast<jsize>(results.size()));
        jfloatArray scores = env->NewFloatArray(static_cast<jsize>(results.size()));
        if (ids == nullptr || scores == nullptr) throw std::bad_alloc();
        std::vector<jlong> id_values;
        std::vector<jfloat> score_values;
        id_values.reserve(results.size());
        score_values.reserve(results.size());
        for (const auto &result : results) {
            id_values.push_back(result.first);
            score_values.push_back(result.second);
        }
        env->SetLongArrayRegion(ids, 0, static_cast<jsize>(id_values.size()), id_values.data());
        env->SetFloatArrayRegion(scores, 0, static_cast<jsize>(score_values.size()), score_values.data());
        if (env->ExceptionCheck()) return nullptr;

        jclass result_class = env->FindClass(
            "com/example/minicpm_v_demo/rag/index/NativeHnswSearchResult");
        if (result_class == nullptr) return nullptr;
        jmethodID constructor = env->GetMethodID(result_class, "<init>", "([J[F)V");
        if (constructor == nullptr) return nullptr;
        return env->NewObject(result_class, constructor, ids, scores);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_minicpm_1v_1demo_rag_index_HnswNative_nativeSave(
    JNIEnv *env, jobject, jlong handle, jstring index_directory, jstring index_file) {
    jni_guard_void(env, [&]() {
        auto native = require_handle(handle);
        const std::string root = canonical_existing_directory(UtfChars(env, index_directory).str());
        if (root != native->index_root) throw std::invalid_argument("HNSW index directory mismatch");
        const std::string path = require_managed_path(root, UtfChars(env, index_file).str(), false);
        {
            std::lock_guard<std::mutex> lock(native->mutex);
            if (native->index->cur_element_count.load() == 0) {
                throw std::invalid_argument("Cannot save an empty HNSW index");
            }
            native->index->saveIndex(path);
        }
        struct stat status {};
        if (stat(path.c_str(), &status) != 0 || !S_ISREG(status.st_mode) || status.st_size <= 0 ||
            static_cast<std::uint64_t>(status.st_size) > kMaximumIndexBytes) {
            throw std::ios_base::failure("HNSW index save failed");
        }
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_minicpm_1v_1demo_rag_index_HnswNative_nativeClose(
    JNIEnv *env, jobject, jlong handle) {
    jni_guard_void(env, [&]() {
        if (handle <= 0) return;
        std::lock_guard<std::mutex> lock(g_handles_mutex);
        g_handles.erase(handle);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_minicpm_1v_1demo_rag_index_HnswNative_nativeActiveHandleCount(
    JNIEnv *env, jobject) {
    return jni_guard<jint>(env, -1, [&]() {
        std::lock_guard<std::mutex> lock(g_handles_mutex);
        if (g_handles.size() > static_cast<std::size_t>(std::numeric_limits<jint>::max())) {
            throw std::runtime_error("HNSW handle count overflow");
        }
        return static_cast<jint>(g_handles.size());
    });
}
