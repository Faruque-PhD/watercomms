#include <jni.h>
#include <string.h>
#include <fftw3.h>
#include <math.h>

#include <ostream>
#include <string>
#include <utility>
#include <vector>

#include <algorithm>
#include <cassert>
#include <iostream>
#include <limits>

#include <android/log.h>

std::string jstring2string(JNIEnv *env, jstring jStr);

// This class implements both a Viterbi Decoder and a Convolutional Encoder.
class ViterbiCodec {
public:
    ViterbiCodec(int constraint, const std::vector<int>& polynomials);

    std::string Encode(const std::string& bits) const;

    std::string Decode(const std::string& bits) const;

    int constraint() const { return constraint_; }

    const std::vector<int>& polynomials() const { return polynomials_; }

private:
    typedef std::vector<std::vector<int> > Trellis;

    int num_parity_bits() const;

    void InitializeOutputs();

    int NextState(int current_state, int input) const;

    std::string Output(int current_state, int input) const;

    int BranchMetric(const std::string& bits,
                     int source_state,
                     int target_state) const;

    std::pair<int, int> PathMetric(const std::string& bits,
                                   const std::vector<int>& prev_path_metrics,
                                   int state) const;

    void UpdatePathMetrics(const std::string& bits,
                           std::vector<int>* path_metrics,
                           Trellis* trellis) const;

    const int constraint_;
    const std::vector<int> polynomials_;

    std::vector<std::string> outputs_;
};

std::ostream& operator <<(std::ostream& os, const ViterbiCodec& codec);

int ReverseBits(int num_bits, int input);


namespace {

    int HammingDistance(const std::string& x, const std::string& y) {
        assert(x.size() == y.size());
        int distance = 0;
        for (int i = 0; i < (int)x.size(); i++) {
            distance += x[i] != y[i];
        }
        return distance;
    }

}  // namespace

std::ostream& operator <<(std::ostream& os, const ViterbiCodec& codec) {
    os << "ViterbiCodec(" << codec.constraint() << ", {";
    const std::vector<int>& polynomials = codec.polynomials();
    assert(!polynomials.empty());
    os << polynomials.front();
    for (int i = 1; i < (int)polynomials.size(); i++) {
        os << ", " << polynomials[i];
    }
    return os << "})";
}

int ReverseBits(int num_bits, int input) {
    assert(input < (1 << num_bits));
    int output = 0;
    while (num_bits-- > 0) {
        output = (output << 1) + (input & 1);
        input >>= 1;
    }
    return output;
}

ViterbiCodec::ViterbiCodec(int constraint, const std::vector<int>& polynomials)
        : constraint_(constraint), polynomials_(polynomials) {
    assert(!polynomials_.empty());
    for (int i = 0; i < (int)polynomials_.size(); i++) {
        assert(polynomials_[i] > 0);
        assert(polynomials_[i] < (1 << constraint_));
    }
    InitializeOutputs();
}

int ViterbiCodec::num_parity_bits() const {
    return polynomials_.size();
}

int ViterbiCodec::NextState(int current_state, int input) const {
    return (current_state >> 1) | (input << (constraint_ - 2));
}

std::string ViterbiCodec::Output(int current_state, int input) const {
    return outputs_.at(current_state | (input << (constraint_ - 1)));
}

std::string ViterbiCodec::Encode(const std::string& bits) const {
    std::string encoded;
    int state = 0;

    // Encode the message bits.
    for (int i = 0; i < (int)bits.size(); i++) {
        char c = bits[i];
        assert(c == '0' || c == '1');
        int input = c - '0';
        encoded += Output(state, input);
        state = NextState(state, input);
    }

    // Encode (constaint_ - 1) flushing bits.
    for (int i = 0; i < constraint_ - 1; i++) {
        encoded += Output(state, 0);
        state = NextState(state, 0);
    }

    return encoded;
}

void ViterbiCodec::InitializeOutputs() {
    outputs_.resize(1 << constraint_);
    for (int i = 0; i < (int)outputs_.size(); i++) {
        for (int j = 0; j < num_parity_bits(); j++) {
            // Reverse polynomial bits to make the convolution code simpler.
            int polynomial = ReverseBits(constraint_, polynomials_[j]);
            int input = i;
            int output = 0;
            for (int k = 0; k < constraint_; k++) {
                output ^= (input & 1) & (polynomial & 1);
                polynomial >>= 1;
                input >>= 1;
            }
            outputs_[i] += output ? "1" : "0";
        }
    }
}

int ViterbiCodec::BranchMetric(const std::string& bits,
                               int source_state,
                               int target_state) const {
    assert(bits.size() == (size_t)num_parity_bits());
    assert((target_state & ((1 << (constraint_ - 2)) - 1)) == source_state >> 1);
    const std::string output =
            Output(source_state, target_state >> (constraint_ - 2));

    return HammingDistance(bits, output);
}

std::pair<int, int> ViterbiCodec::PathMetric(
        const std::string& bits,
        const std::vector<int>& prev_path_metrics,
        int state) const {
    int s = (state & ((1 << (constraint_ - 2)) - 1)) << 1;
    int source_state1 = s | 0;
    int source_state2 = s | 1;

    int pm1 = prev_path_metrics[source_state1];
    if (pm1 < std::numeric_limits<int>::max()) {
        pm1 += BranchMetric(bits, source_state1, state);
    }
    int pm2 = prev_path_metrics[source_state2];
    if (pm2 < std::numeric_limits<int>::max()) {
        pm2 += BranchMetric(bits, source_state2, state);
    }

    if (pm1 <= pm2) {
        return std::make_pair(pm1, source_state1);
    } else {
        return std::make_pair(pm2, source_state2);
    }
}

void ViterbiCodec::UpdatePathMetrics(const std::string& bits,
                                     std::vector<int>* path_metrics,
                                     Trellis* trellis) const {
    std::vector<int> new_path_metrics(path_metrics->size());
    std::vector<int> new_trellis_column(1 << (constraint_ - 1));
    for (int i = 0; i < (int)path_metrics->size(); i++) {
        std::pair<int, int> p = PathMetric(bits, *path_metrics, i);
        new_path_metrics[i] = p.first;
        new_trellis_column[i] = p.second;
    }

    *path_metrics = new_path_metrics;
    trellis->push_back(new_trellis_column);
}

std::string ViterbiCodec::Decode(const std::string& bits) const {
    // Compute path metrics and generate trellis.
    Trellis trellis;
    std::vector<int> path_metrics(1 << (constraint_ - 1),
                                  std::numeric_limits<int>::max());
    path_metrics.front() = 0;
    for (int i = 0; i < (int)bits.size(); i += num_parity_bits()) {
        std::string current_bits(bits, i, num_parity_bits());
        // If some bits are missing, fill with trailing zeros.
        // This is not ideal but it is the best we can do.
        if (current_bits.size() < (size_t)num_parity_bits()) {
            current_bits.append(
                    std::string(num_parity_bits() - current_bits.size(), '0'));
        }
        UpdatePathMetrics(current_bits, &path_metrics, &trellis);
    }

    // Traceback.
    std::string decoded;
    int state = std::min_element(path_metrics.begin(), path_metrics.end()) -
                path_metrics.begin();
    for (int i = (int)trellis.size() - 1; i >= 0; i--) {
        decoded += state >> (constraint_ - 2) ? "1" : "0";
        state = trellis[i][state];
    }
    std::reverse(decoded.begin(), decoded.end());

    // Remove (constraint_ - 1) flushing bits.
    if (decoded.size() < (size_t)constraint_ - 1) return "";
    return decoded.substr(0, decoded.size() - constraint_ + 1);
}

std::string jstring2string(JNIEnv *env, jstring jStr) {
    if (!jStr)
        return "";

    const jclass stringClass = env->GetObjectClass(jStr);
    const jmethodID getBytes = env->GetMethodID(stringClass, "getBytes", "(Ljava/lang/String;)[B");
    const jbyteArray stringJbytes = (jbyteArray) env->CallObjectMethod(jStr, getBytes, env->NewStringUTF("UTF-8"));

    size_t length = (size_t) env->GetArrayLength(stringJbytes);
    jbyte* pBytes = env->GetByteArrayElements(stringJbytes, NULL);

    std::string ret = std::string((char *)pBytes, length);
    env->ReleaseByteArrayElements(stringJbytes, pBytes, JNI_ABORT);

    env->DeleteLocalRef(stringJbytes);
    env->DeleteLocalRef(stringClass);
    return ret;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_root_ffttest2_Utils_encode(JNIEnv *env, jclass clazz, jstring bits, jint poly1, jint poly2, jint constraint) {
    std::vector<int> polynomials;
    polynomials.push_back(poly1);
    polynomials.push_back(poly2);

    ViterbiCodec codec(constraint, polynomials);
    std::string decoded = codec.Encode(jstring2string(env,bits));

    return env->NewStringUTF(decoded.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_root_ffttest2_Utils_decode(JNIEnv *env, jclass clazz, jstring bits, jint poly1, jint poly2, jint constraint) {
    std::vector<int> polynomials;
    polynomials.push_back(poly1);
    polynomials.push_back(poly2);

    ViterbiCodec codec(constraint, polynomials);
    std::string decoded = codec.Decode(jstring2string(env,bits));

    return env->NewStringUTF(decoded.c_str());
}

extern "C"
JNIEXPORT jdoubleArray JNICALL
Java_com_example_root_ffttest2_Utils_fftnative_1double(JNIEnv *env, jobject thiz, jdoubleArray data,
                                                         jint N) {
    if (data == NULL || N <= 0) return NULL;
    fftw_complex *in , *out;
    fftw_plan p;

    jdouble *doubleArray = env->GetDoubleArrayElements(data, NULL);
    int datalen = env -> GetArrayLength(data);

    in = (fftw_complex*) fftw_malloc(sizeof(fftw_complex) * N);
    out = (fftw_complex*) fftw_malloc(sizeof(fftw_complex) * N);

    if (in == NULL || out == NULL) {
        if (in) fftw_free(in);
        if (out) fftw_free(out);
        env->ReleaseDoubleArrayElements(data, doubleArray, JNI_ABORT);
        return NULL;
    }

    for (int i = 0; i < N; i++) {
        in[i][0] = (i < datalen) ? doubleArray[i] : 0;
        in[i][1] = 0;
        out[i][0] = 0;
        out[i][1] = 0;
    }

    p = fftw_plan_dft_1d(N, in, out, FFTW_FORWARD, FFTW_ESTIMATE);
    fftw_execute(p);

    std::vector<jdouble> mag(N);
    for (int i = 0; i < N; i++) {
        double real = out[i][0];
        double imag = out[i][1];
        mag[i] = sqrt((real*real)+(imag*imag));
    }

    jdoubleArray result = env->NewDoubleArray(N);
    env->SetDoubleArrayRegion(result, 0, N, mag.data());

    fftw_destroy_plan(p);
    fftw_free(in); fftw_free(out);
    env->ReleaseDoubleArrayElements(data, doubleArray, JNI_ABORT);

    return result;
}

extern "C"
JNIEXPORT jdoubleArray JNICALL
Java_com_example_root_ffttest2_Utils_fftnative_1short(JNIEnv *env, jobject thiz, jshortArray data,
                                                        jint N) {
    if (data == NULL || N <= 0) return NULL;
    fftw_complex *in , *out;
    fftw_plan p;

    jshort *shortArray = env->GetShortArrayElements(data, NULL);
    int datalen = env -> GetArrayLength(data);

    in = (fftw_complex*) fftw_malloc(sizeof(fftw_complex) * N);
    out = (fftw_complex*) fftw_malloc(sizeof(fftw_complex) * N);

    if (in == NULL || out == NULL) {
        if (in) fftw_free(in);
        if (out) fftw_free(out);
        env->ReleaseShortArrayElements(data, shortArray, JNI_ABORT);
        return NULL;
    }

    for (int i = 0; i < N; i++) {
        in[i][0] = (i < datalen) ? shortArray[i] : 0;
        in[i][1] = 0;
        out[i][0] = 0;
        out[i][1] = 0;
    }

    p = fftw_plan_dft_1d(N, in, out, FFTW_FORWARD, FFTW_ESTIMATE);
    fftw_execute(p);

    std::vector<jdouble> mag(N);
    for (int i = 0; i < N; i++) {
        double real = out[i][0];
        double imag = out[i][1];
        mag[i] = sqrt((real*real)+(imag*imag));
    }

    jdoubleArray result = env->NewDoubleArray(N);
    env->SetDoubleArrayRegion(result, 0, N, mag.data());

    fftw_destroy_plan(p);
    fftw_free(in); fftw_free(out);
    env->ReleaseShortArrayElements(data, shortArray, JNI_ABORT);

    return result;
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_example_root_ffttest2_Utils_fftcomplexinoutnative_1double(JNIEnv *env, jobject thiz,
                                                                 jobjectArray data, jint N) {
    if (data == NULL || N <= 0) return NULL;

    jdoubleArray realar1 = (jdoubleArray) env->GetObjectArrayElement(data, 0);
    jdoubleArray imagar1 = (jdoubleArray) env->GetObjectArrayElement(data, 1);

    if (realar1 == NULL || imagar1 == NULL) return NULL;

    jdouble *real1 = env->GetDoubleArrayElements(realar1, NULL);
    jdouble *imag1 = env->GetDoubleArrayElements(imagar1, NULL);

    jint datalen = env -> GetArrayLength(realar1);

    fftw_complex *in , *out;
    fftw_plan p;

    in = (fftw_complex*) fftw_malloc(sizeof(fftw_complex) * N);
    out = (fftw_complex*) fftw_malloc(sizeof(fftw_complex) * N);

    if (in == NULL || out == NULL) {
        if (in) fftw_free(in);
        if (out) fftw_free(out);
        env->ReleaseDoubleArrayElements(realar1, real1, JNI_ABORT);
        env->ReleaseDoubleArrayElements(imagar1, imag1, JNI_ABORT);
        return NULL;
    }

    for (int i = 0; i < N; i++) {
        if (i < datalen) {
            in[i][0] = real1[i];
            in[i][1] = imag1[i];
        } else {
            in[i][0] = 0;
            in[i][1] = 0;
        }
        out[i][0] = 0;
        out[i][1] = 0;
    }

    p = fftw_plan_dft_1d(N, in, out, FFTW_FORWARD, FFTW_ESTIMATE);
    fftw_execute(p);

    std::vector<jdouble> real_v(N);
    std::vector<jdouble> imag_v(N);
    for (int i = 0; i < N; i++) {
        real_v[i] = out[i][0];
        imag_v[i] = out[i][1];
    }

    jdoubleArray realResult = env->NewDoubleArray(N);
    jdoubleArray imagResult = env->NewDoubleArray(N);
    env->SetDoubleArrayRegion(realResult, 0, N, real_v.data());
    env->SetDoubleArrayRegion(imagResult, 0, N, imag_v.data());

    jobjectArray outarray = env->NewObjectArray(2, env->FindClass("[D"), 0);
    env->SetObjectArrayElement(outarray, 0, realResult);
    env->SetObjectArrayElement(outarray, 1, imagResult);

    fftw_destroy_plan(p);
    fftw_free(in); fftw_free(out);
    env->ReleaseDoubleArrayElements(realar1, real1, JNI_ABORT);
    env->ReleaseDoubleArrayElements(imagar1, imag1, JNI_ABORT);

    return outarray;
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_example_root_ffttest2_Utils_fftcomplexoutnative_1double(JNIEnv *env, jobject thiz,
                                                                   jdoubleArray data, jint N) {
    if (data == NULL || N <= 0) return NULL;
    fftw_complex *in , *out;
    fftw_plan p;

    in = (fftw_complex*) fftw_malloc(sizeof(fftw_complex) * N);
    out = (fftw_complex*) fftw_malloc(sizeof(fftw_complex) * N);

    if (in == NULL || out == NULL) {
        if (in) fftw_free(in);
        if (out) fftw_free(out);
        return NULL;
    }

    for (int i = 0; i < N; i++) {
        in[i][0] = 0;
        in[i][1] = 0;
        out[i][0] = 0;
        out[i][1] = 0;
    }

    jdouble *doubleArray = env->GetDoubleArrayElements(data, NULL);
    int datalen = env -> GetArrayLength(data);
    for (int i = 0; i < std::min(datalen, N); i++) {
        in[i][0] = doubleArray[i];
    }

    p = fftw_plan_dft_1d(N, in, out, FFTW_FORWARD, FFTW_ESTIMATE);
    fftw_execute(p);

    std::vector<jdouble> real_v(N);
    std::vector<jdouble> imag_v(N);
    for (int i = 0; i < N; i++) {
        real_v[i] = out[i][0];
        imag_v[i] = out[i][1];
    }

    jdoubleArray realResult = env->NewDoubleArray(N);
    jdoubleArray imagResult = env->NewDoubleArray(N);
    env->SetDoubleArrayRegion(realResult, 0, N, real_v.data());
    env->SetDoubleArrayRegion(imagResult, 0, N, imag_v.data());

    jobjectArray outarray = env->NewObjectArray(2, env->FindClass("[D"), 0);
    env->SetObjectArrayElement(outarray, 0, realResult);
    env->SetObjectArrayElement(outarray, 1, imagResult);

    fftw_destroy_plan(p);
    fftw_free(in); fftw_free(out);
    env->ReleaseDoubleArrayElements(data, doubleArray, JNI_ABORT);

    return outarray;
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_example_root_ffttest2_Utils_fftcomplexoutnative_1short(JNIEnv *env, jobject thiz,
                                                                  jshortArray data, jint N) {
    if (data == NULL || N <= 0) return NULL;
    fftw_complex *in , *out;
    fftw_plan p;

    in = (fftw_complex*) fftw_malloc(sizeof(fftw_complex) * N);
    out = (fftw_complex*) fftw_malloc(sizeof(fftw_complex) * N);

    if (in == NULL || out == NULL) {
        if (in) fftw_free(in);
        if (out) fftw_free(out);
        return NULL;
    }

    for (int i = 0; i < N; i++) {
        in[i][0] = 0;
        in[i][1] = 0;
        out[i][0] = 0;
        out[i][1] = 0;
    }

    jshort *shortArray = env->GetShortArrayElements(data, NULL);
    int datalen = env -> GetArrayLength(data);
    for (int i = 0; i < std::min(datalen, N); i++) {
        in[i][0] = shortArray[i];
    }

    p = fftw_plan_dft_1d(N, in, out, FFTW_FORWARD, FFTW_ESTIMATE);
    fftw_execute(p);

    std::vector<jdouble> real_v(N);
    std::vector<jdouble> imag_v(N);
    for (int i = 0; i < N; i++) {
        real_v[i] = out[i][0];
        imag_v[i] = out[i][1];
    }

    jdoubleArray realResult = env->NewDoubleArray(N);
    jdoubleArray imagResult = env->NewDoubleArray(N);
    env->SetDoubleArrayRegion(realResult, 0, N, real_v.data());
    env->SetDoubleArrayRegion(imagResult, 0, N, imag_v.data());

    jobjectArray outarray = env->NewObjectArray(2, env->FindClass("[D"), 0);
    env->SetObjectArrayElement(outarray, 0, realResult);
    env->SetObjectArrayElement(outarray, 1, imagResult);

    fftw_destroy_plan(p);
    fftw_free(in); fftw_free(out);
    env->ReleaseShortArrayElements(data, shortArray, JNI_ABORT);

    return outarray;
}

extern "C"
JNIEXPORT jdoubleArray JNICALL
Java_com_example_root_ffttest2_Utils_ifftnative(JNIEnv *env, jobject thiz,
                                                  jobjectArray data) {
    if (data == NULL) return NULL;
    jdoubleArray realArr = (jdoubleArray) env->GetObjectArrayElement(data, 0);
    jdoubleArray imagArr = (jdoubleArray) env->GetObjectArrayElement(data, 1);

    if (realArr == NULL || imagArr == NULL) return NULL;

    jint N = env -> GetArrayLength(realArr);
    if (N <= 0) return NULL;

    fftw_complex *in , *out;
    fftw_plan p;

    in = (fftw_complex*) fftw_malloc(sizeof(fftw_complex) * N);
    out = (fftw_complex*) fftw_malloc(sizeof(fftw_complex) * N);

    if (in == NULL || out == NULL) {
        if (in) fftw_free(in);
        if (out) fftw_free(out);
        return NULL;
    }

    jdouble *realArray = env->GetDoubleArrayElements(realArr, NULL);
    jdouble *imagArray = env->GetDoubleArrayElements(imagArr, NULL);
    for (int i = 0; i < N; i++) {
        in[i][0] = realArray[i];
        in[i][1] = imagArray[i];
        out[i][0] = 0;
        out[i][1] = 0;
    }

    p = fftw_plan_dft_1d(N, in, out, FFTW_BACKWARD, FFTW_ESTIMATE);
    fftw_execute(p);

    int outlen = (N + 1) / 2;
    std::vector<jdouble> realout(outlen);
    int counter = 0;
    for (int i = 0; i < N; i+=2) {
        realout[counter++] = out[i][0];
    }

    jdoubleArray result = env->NewDoubleArray(outlen);
    env->SetDoubleArrayRegion(result, 0, outlen, realout.data());

    fftw_destroy_plan(p);
    fftw_free(in); fftw_free(out);
    env->ReleaseDoubleArrayElements(realArr, realArray, JNI_ABORT);
    env->ReleaseDoubleArrayElements(imagArr, imagArray, JNI_ABORT);

    return result;
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_example_root_ffttest2_Utils_ifftnative2(JNIEnv *env, jobject thiz,
                                                   jobjectArray data) {
    if (data == NULL) return NULL;
    jdoubleArray realArr = (jdoubleArray) env->GetObjectArrayElement(data, 0);
    jdoubleArray imagArr = (jdoubleArray) env->GetObjectArrayElement(data, 1);

    if (realArr == NULL || imagArr == NULL) return NULL;

    jint N = env -> GetArrayLength(realArr);
    if (N <= 0) return NULL;

    fftw_complex *in , *out;
    fftw_plan p;

    in = (fftw_complex*) fftw_malloc(sizeof(fftw_complex) * N);
    out = (fftw_complex*) fftw_malloc(sizeof(fftw_complex) * N);

    if (in == NULL || out == NULL) {
        if (in) fftw_free(in);
        if (out) fftw_free(out);
        return NULL;
    }

    jdouble *realArray = env->GetDoubleArrayElements(realArr, NULL);
    jdouble *imagArray = env->GetDoubleArrayElements(imagArr, NULL);
    for (int i = 0; i < N; i++) {
        in[i][0] = realArray[i];
        in[i][1] = imagArray[i];
        out[i][0] = 0;
        out[i][1] = 0;
    }

    p = fftw_plan_dft_1d(N, in, out, FFTW_BACKWARD, FFTW_ESTIMATE);
    fftw_execute(p);

    std::vector<jdouble> real2(N);
    std::vector<jdouble> imag2(N);
    for (int i = 0; i < N; i++) {
        real2[i] = out[i][0];
        imag2[i] = out[i][1];
    }

    jdoubleArray realResult = env->NewDoubleArray(N);
    jdoubleArray imagResult = env->NewDoubleArray(N);
    env->SetDoubleArrayRegion(realResult, 0, N, real2.data());
    env->SetDoubleArrayRegion(imagResult, 0, N, imag2.data());

    jobjectArray outarray = env->NewObjectArray(2, env->FindClass("[D"), 0);
    env->SetObjectArrayElement(outarray, 0, realResult);
    env->SetObjectArrayElement(outarray, 1, imagResult);

    fftw_destroy_plan(p);
    fftw_free(in); fftw_free(out);
    env->ReleaseDoubleArrayElements(realArr, realArray, JNI_ABORT);
    env->ReleaseDoubleArrayElements(imagArr, imagArray, JNI_ABORT);

    return outarray;
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_com_example_root_ffttest2_Utils_timesnative(
        JNIEnv *env,
        jclass,
        jobjectArray c1,
        jobjectArray c2) {

    if (c1 == NULL || c2 == NULL) return NULL;

    jdoubleArray realar1 = (jdoubleArray) env->GetObjectArrayElement(c1, 0);
    jdoubleArray imagar1 = (jdoubleArray) env->GetObjectArrayElement(c1, 1);
    jdoubleArray realar2 = (jdoubleArray) env->GetObjectArrayElement(c2, 0);
    jdoubleArray imagar2 = (jdoubleArray) env->GetObjectArrayElement(c2, 1);

    if (realar1 == NULL || imagar1 == NULL || realar2 == NULL || imagar2 == NULL) return NULL;

    jdouble *real1 = env->GetDoubleArrayElements(realar1, NULL);
    jdouble *imag1 = env->GetDoubleArrayElements(imagar1, NULL);
    jdouble *real2 = env->GetDoubleArrayElements(realar2, NULL);
    jdouble *imag2 = env->GetDoubleArrayElements(imagar2, NULL);

    jint N1 = env->GetArrayLength(realar1);
    jint N2 = env->GetArrayLength(realar2);
    jint N = std::min(N1, N2);

    std::vector<jdouble> real_v(N);
    std::vector<jdouble> imag_v(N);
    for (int i = 0; i < N; i++) {
        real_v[i] = real1[i]*real2[i]-imag1[i]*imag2[i];
        imag_v[i] = imag1[i]*real2[i]+real1[i]*imag2[i];
    }

    jdoubleArray realResult = env->NewDoubleArray(N);
    jdoubleArray imagResult = env->NewDoubleArray(N);
    env->SetDoubleArrayRegion(realResult, 0, N, real_v.data());
    env->SetDoubleArrayRegion(imagResult, 0, N, imag_v.data());

    jobjectArray outarray = env->NewObjectArray(2, env->FindClass("[D"), 0);
    env->SetObjectArrayElement(outarray, 0, realResult);
    env->SetObjectArrayElement(outarray, 1, imagResult);

    env->ReleaseDoubleArrayElements(realar1, real1, JNI_ABORT);
    env->ReleaseDoubleArrayElements(imagar1, imag1, JNI_ABORT);
    env->ReleaseDoubleArrayElements(realar2, real2, JNI_ABORT);
    env->ReleaseDoubleArrayElements(imagar2, imag2, JNI_ABORT);

    return outarray;
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_com_example_root_ffttest2_Utils_dividenative(
        JNIEnv *env,
        jclass,
        jobjectArray c1,
        jobjectArray c2) {

    if (c1 == NULL || c2 == NULL) return NULL;

    jdoubleArray realar1 = (jdoubleArray) env->GetObjectArrayElement(c1, 0);
    jdoubleArray imagar1 = (jdoubleArray) env->GetObjectArrayElement(c1, 1);
    jdoubleArray realar2 = (jdoubleArray) env->GetObjectArrayElement(c2, 0);
    jdoubleArray imagar2 = (jdoubleArray) env->GetObjectArrayElement(c2, 1);

    if (realar1 == NULL || imagar1 == NULL || realar2 == NULL || imagar2 == NULL) return NULL;

    jdouble *real1 = env->GetDoubleArrayElements(realar1, NULL);
    jdouble *imag1 = env->GetDoubleArrayElements(imagar1, NULL);
    jdouble *real2 = env->GetDoubleArrayElements(realar2, NULL);
    jdouble *imag2 = env->GetDoubleArrayElements(imagar2, NULL);

    jint N1 = env->GetArrayLength(realar1);
    jint N2 = env->GetArrayLength(realar2);
    jint N = std::min(N1, N2);

    std::vector<jdouble> real_v(N);
    std::vector<jdouble> imag_v(N);
    for (int i = 0; i < N; i++) {
        jdouble a = real1[i];
        jdouble b = imag1[i];
        jdouble c = real2[i];
        jdouble d = imag2[i];
        double denom = c*c+d*d;
        if (denom == 0) {
            real_v[i] = 0;
            imag_v[i] = 0;
        } else {
            real_v[i] = (a*c+b*d)/denom;
            imag_v[i] = (b*c-a*d)/denom;
        }
    }

    jdoubleArray realResult = env->NewDoubleArray(N);
    jdoubleArray imagResult = env->NewDoubleArray(N);
    env->SetDoubleArrayRegion(realResult, 0, N, real_v.data());
    env->SetDoubleArrayRegion(imagResult, 0, N, imag_v.data());

    jobjectArray outarray = env->NewObjectArray(2, env->FindClass("[D"), 0);
    env->SetObjectArrayElement(outarray, 0, realResult);
    env->SetObjectArrayElement(outarray, 1, imagResult);

    env->ReleaseDoubleArrayElements(realar1, real1, JNI_ABORT);
    env->ReleaseDoubleArrayElements(imagar1, imag1, JNI_ABORT);
    env->ReleaseDoubleArrayElements(realar2, real2, JNI_ABORT);
    env->ReleaseDoubleArrayElements(imagar2, imag2, JNI_ABORT);

    return outarray;
}

extern "C" JNIEXPORT void JNICALL Java_com_example_root_ffttest2_Utils_conjnative(
        JNIEnv *env,
        jclass,
        jobjectArray data) {
    if (data == NULL) return;
    jdoubleArray imagArr = (jdoubleArray) env->GetObjectArrayElement(data, 1);
    if (imagArr == NULL) return;
    jdouble *imagArray = env->GetDoubleArrayElements(imagArr, NULL);
    jint N = env -> GetArrayLength(imagArr);

    for (int i = 0; i < N; i++) {
        imagArray[i] = -imagArray[i];
    }
    env->ReleaseDoubleArrayElements(imagArr, imagArray, 0); // 0 to commit changes
}

extern "C" JNIEXPORT jdoubleArray JNICALL Java_com_example_root_ffttest2_Utils_fir(
        JNIEnv *env,
        jclass,
        jdoubleArray data,
        jdoubleArray h) {

    if (data == NULL || h == NULL) return NULL;

    jdouble *jdata = env->GetDoubleArrayElements(data, NULL);
    jdouble *jh = env->GetDoubleArrayElements(h, NULL);

    jint lenData = env->GetArrayLength(data);
    jint lenH = env->GetArrayLength(h);

    jint nconv = lenH+lenData-1;

    std::vector<jdouble> temp(nconv, 0);

    for (int n = 0; n < nconv; n++){
        jint kmin, kmax;
        kmin = (n >= lenH - 1) ? n - (lenH - 1) : 0;
        kmax = (n < lenData - 1) ? n : lenData - 1;

        for (int k = kmin; k <= kmax; k++) {
            temp[n] += jdata[k] * jh[n - k];
        }
    }

    jdoubleArray out = env->NewDoubleArray(nconv);
    env->SetDoubleArrayRegion(out, 0, nconv, temp.data());

    env->ReleaseDoubleArrayElements(data, jdata, JNI_ABORT);
    env->ReleaseDoubleArrayElements(h, jh, JNI_ABORT);

    return out;
}

extern "C" JNIEXPORT jdoubleArray JNICALL Java_com_example_root_ffttest2_Utils_bandpass(
        JNIEnv *env,
        jclass,
        jdoubleArray data) {

    if (data == NULL) return NULL;
    jint N = env->GetArrayLength(data);
    jdouble *input = env->GetDoubleArrayElements(data, NULL);

    jint m_numBiquads = 6;

    //bessel
    jdouble a1[] = {-0.8483132820723579,-1.8379673789503772,-0.9104634235790083,-1.7631232694140826,-0.7921543000958807,-1.9173321405089663};
    jdouble a2[] = {0.2834093153669302,0.8491194750058104,0.21989677363118537,0.7781746366113,0.4696113329720846,0.9252519876229148};
    jdouble b0[] = {0.0013832725795677246,1.0,1.0,1.0,1.0,1.0};
    jdouble b1[] = {0.002766545159135449,-2.0,2.0,-2.0,2.0,-2.0};
    jdouble b2[] = {0.0013832725795677246,1.0,1.0,1.0,1.0,1.0};

    jdouble m_v1[] = {0,0,0,0,0,0};
    jdouble m_v2[] = {0,0,0,0,0,0};

    for (int k = 0; k < N; k++) {
        jdouble in = input[k];

        for (int i = 0; i < m_numBiquads; i++) {
            jdouble w = in - a1[i] * m_v1[i] - a2[i] * m_v2[i];
            in = b0[i] * w + b1[i] * m_v1[i] + b2[i] * m_v2[i];

            m_v2[i] = m_v1[i];
            m_v1[i] = w;
        }
        input[k] = in;
    }

    jdoubleArray out = env->NewDoubleArray(N);
    env->SetDoubleArrayRegion(out, 0, N, input);
    env->ReleaseDoubleArrayElements(data, input, JNI_ABORT);

    return out;
}
