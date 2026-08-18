// Activación de la pizarra: código del aparato y comprobación de la clave.
//
// Va en C y no en Kotlin a propósito: el bytecode de Kotlin se lee casi como el
// original al descompilar, mientras que esto hay que desensamblarlo en ARM.
// Además el secreto no aparece como texto en el binario (está guardado con XOR
// y se arma en memoria), así que un `strings` sobre la librería no lo saca.
//
// Debe coincidir EXACTAMENTE con el generador del panel (main.go del servidor
// OTA): mismo alfabeto, mismo prefijo "RB1|" y misma HMAC-SHA256.

#include <jni.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>

// ── SHA-256 ─────────────────────────────────────────────────────────────────
typedef struct {
    uint32_t state[8];
    uint64_t bitlen;
    uint8_t data[64];
    uint32_t datalen;
} sha256_ctx;

static const uint32_t K[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};

#define ROTR(x,n) (((x) >> (n)) | ((x) << (32-(n))))
#define CH(x,y,z) (((x) & (y)) ^ (~(x) & (z)))
#define MAJ(x,y,z) (((x) & (y)) ^ ((x) & (z)) ^ ((y) & (z)))
#define EP0(x) (ROTR(x,2) ^ ROTR(x,13) ^ ROTR(x,22))
#define EP1(x) (ROTR(x,6) ^ ROTR(x,11) ^ ROTR(x,25))
#define SIG0(x) (ROTR(x,7) ^ ROTR(x,18) ^ ((x) >> 3))
#define SIG1(x) (ROTR(x,17) ^ ROTR(x,19) ^ ((x) >> 10))

static void sha256_transform(sha256_ctx *ctx, const uint8_t data[]) {
    uint32_t a,b,c,d,e,f,g,h,t1,t2,m[64];
    int i,j;
    for (i = 0, j = 0; i < 16; ++i, j += 4)
        m[i] = (data[j] << 24) | (data[j+1] << 16) | (data[j+2] << 8) | data[j+3];
    for ( ; i < 64; ++i)
        m[i] = SIG1(m[i-2]) + m[i-7] + SIG0(m[i-15]) + m[i-16];
    a = ctx->state[0]; b = ctx->state[1]; c = ctx->state[2]; d = ctx->state[3];
    e = ctx->state[4]; f = ctx->state[5]; g = ctx->state[6]; h = ctx->state[7];
    for (i = 0; i < 64; ++i) {
        t1 = h + EP1(e) + CH(e,f,g) + K[i] + m[i];
        t2 = EP0(a) + MAJ(a,b,c);
        h = g; g = f; f = e; e = d + t1;
        d = c; c = b; b = a; a = t1 + t2;
    }
    ctx->state[0] += a; ctx->state[1] += b; ctx->state[2] += c; ctx->state[3] += d;
    ctx->state[4] += e; ctx->state[5] += f; ctx->state[6] += g; ctx->state[7] += h;
}

static void sha256_init(sha256_ctx *ctx) {
    ctx->datalen = 0; ctx->bitlen = 0;
    ctx->state[0] = 0x6a09e667; ctx->state[1] = 0xbb67ae85;
    ctx->state[2] = 0x3c6ef372; ctx->state[3] = 0xa54ff53a;
    ctx->state[4] = 0x510e527f; ctx->state[5] = 0x9b05688c;
    ctx->state[6] = 0x1f83d9ab; ctx->state[7] = 0x5be0cd19;
}

static void sha256_update(sha256_ctx *ctx, const uint8_t data[], size_t len) {
    for (size_t i = 0; i < len; ++i) {
        ctx->data[ctx->datalen] = data[i];
        ctx->datalen++;
        if (ctx->datalen == 64) {
            sha256_transform(ctx, ctx->data);
            ctx->bitlen += 512;
            ctx->datalen = 0;
        }
    }
}

static void sha256_final(sha256_ctx *ctx, uint8_t hash[]) {
    uint32_t i = ctx->datalen;
    if (ctx->datalen < 56) {
        ctx->data[i++] = 0x80;
        while (i < 56) ctx->data[i++] = 0x00;
    } else {
        ctx->data[i++] = 0x80;
        while (i < 64) ctx->data[i++] = 0x00;
        sha256_transform(ctx, ctx->data);
        memset(ctx->data, 0, 56);
    }
    ctx->bitlen += ctx->datalen * 8;
    for (int j = 0; j < 8; ++j)
        ctx->data[63-j] = (uint8_t)(ctx->bitlen >> (8*j));
    sha256_transform(ctx, ctx->data);
    for (i = 0; i < 4; ++i) {
        for (int j = 0; j < 8; ++j)
            hash[i + j*4] = (uint8_t)((ctx->state[j] >> (24 - i*8)) & 0xff);
    }
}

static void hmac_sha256(const uint8_t *key, size_t keylen,
                        const uint8_t *msg, size_t msglen, uint8_t out[32]) {
    uint8_t k[64]; memset(k, 0, 64);
    if (keylen > 64) {
        sha256_ctx c; sha256_init(&c); sha256_update(&c, key, keylen); sha256_final(&c, k);
    } else {
        memcpy(k, key, keylen);
    }
    uint8_t ipad[64], opad[64];
    for (int i = 0; i < 64; ++i) { ipad[i] = k[i] ^ 0x36; opad[i] = k[i] ^ 0x5c; }
    uint8_t inner[32];
    sha256_ctx c;
    sha256_init(&c); sha256_update(&c, ipad, 64); sha256_update(&c, msg, msglen); sha256_final(&c, inner);
    sha256_init(&c); sha256_update(&c, opad, 64); sha256_update(&c, inner, 32); sha256_final(&c, out);
    memset(k, 0, 64); memset(ipad, 0, 64); memset(opad, 0, 64);
}

// ── Secreto (guardado con XOR: no aparece como texto en el binario) ─────────
static const uint8_t SECRET_XOR[] = {
    0x40,0x4f,0x32,0x29,0x33,0x0a,0x74,0x03,0x0b,0x04,0x33,0xb7,0xfe,0xd1,0xd3,0xa9,
    0x93,0xfb,0xff,0x89,0xa9,0x9e,0x80,0x91,0xb1,0xa8,0x81,0xba,0xc8,0x59,0x40,0x36,
    0x26,0x45,0x7a,0x60,0x45,0x4c,0x4b,0x09,0x06,0x6c,0x38,0x5f
};
static const size_t SECRET_LEN = sizeof(SECRET_XOR);

// volatile a propósito: sin esto el compilador ve que los bytes y la máscara
// son constantes, hace el XOR él mismo al compilar y deja el secreto ESCRITO EN
// CLARO dentro del .so (comprobado). Al ser volatile no puede adelantar la
// cuenta y el secreto solo existe en memoria mientras se comprueba la clave.
static volatile uint8_t g_mask_base = 0x35;
static volatile uint8_t g_mask_step = 7;

static void unmask_secret(uint8_t *out) {
    uint8_t base = g_mask_base;
    uint8_t step = g_mask_step;
    for (size_t i = 0; i < SECRET_LEN; ++i)
        out[i] = (uint8_t)(SECRET_XOR[i] ^ (uint8_t)(base + (uint8_t)(i * step)));
}

static const char ALPHABET[] = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

// ── JNI ─────────────────────────────────────────────────────────────────────
extern "C" {

// Código que se muestra en la pizarra: 4 caracteres a partir del ANDROID_ID.
// No usa el secreto (es público: el cliente lo dicta por teléfono).
JNIEXPORT jstring JNICALL
Java_com_example_newdrawingapp_Activation_nativeDeviceCode(JNIEnv *env, jobject, jstring androidId) {
    const char *aid = env->GetStringUTFChars(androidId, nullptr);
    sha256_ctx c; sha256_init(&c);
    sha256_update(&c, (const uint8_t *)"RBDEV|", 6);
    sha256_update(&c, (const uint8_t *)aid, strlen(aid));
    uint8_t h[32]; sha256_final(&c, h);
    env->ReleaseStringUTFChars(androidId, aid);

    char out[5];
    for (int i = 0; i < 4; ++i) out[i] = ALPHABET[h[i] % 32];
    out[4] = '\0';
    return env->NewStringUTF(out);
}

// Comprueba la clave del código dado. Comparación en tiempo constante.
JNIEXPORT jboolean JNICALL
Java_com_example_newdrawingapp_Activation_nativeCheckKey(JNIEnv *env, jobject,
                                                          jstring code, jstring key) {
    const char *c_code = env->GetStringUTFChars(code, nullptr);
    const char *c_key = env->GetStringUTFChars(key, nullptr);

    uint8_t secret[64];
    unmask_secret(secret);

    char msg[64];
    snprintf(msg, sizeof(msg), "RB1|%s", c_code);

    uint8_t mac[32];
    hmac_sha256(secret, SECRET_LEN, (const uint8_t *)msg, strlen(msg), mac);
    memset(secret, 0, sizeof(secret));

    char expected[5];
    for (int i = 0; i < 4; ++i) expected[i] = ALPHABET[mac[i] % 32];
    expected[4] = '\0';

    unsigned char diff = (unsigned char)(strlen(c_key) != 4);
    for (int i = 0; i < 4 && c_key[i]; ++i)
        diff |= (unsigned char)(expected[i] ^ c_key[i]);

    env->ReleaseStringUTFChars(code, c_code);
    env->ReleaseStringUTFChars(key, c_key);
    memset(expected, 0, sizeof(expected));
    return diff == 0 ? JNI_TRUE : JNI_FALSE;
}

}
