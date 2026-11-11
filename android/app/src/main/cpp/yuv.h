#ifndef OPIRL_YUV_H
#define OPIRL_YUV_H

#include <stddef.h>
#include <stdint.h>

static inline uint32_t yuvClamp(int v) {
    return (uint32_t)(v < 0 ? 0 : v > 255 ? 255 : v);
}

static inline __attribute__((always_inline)) void yuyvRow(const uint8_t *restrict src, uint32_t *restrict dst,
                                                          int pairs, int cy, int crv, int cgu, int cgv,
                                                          int cbu) {
    for (int i = 0; i < pairs; i++) {
        const uint8_t *p = src + 4 * i;
        int u = p[1] - 128;
        int v = p[3] - 128;
        int y0 = (p[0] - 16) * cy + 2048;
        int y1 = (p[2] - 16) * cy + 2048;
        int r = crv * v;
        int g = cgu * u + cgv * v;
        int b = cbu * u;
        dst[2 * i] = yuvClamp((y0 + r) >> 12) | yuvClamp((y0 - g) >> 12) << 8 |
                     yuvClamp((y0 + b) >> 12) << 16 | 0xFF000000u;
        dst[2 * i + 1] = yuvClamp((y1 + r) >> 12) | yuvClamp((y1 - g) >> 12) << 8 |
                         yuvClamp((y1 + b) >> 12) << 16 | 0xFF000000u;
    }
}

static inline void yuyvToRgba(const uint8_t *src, size_t srcStride, uint8_t *dst, size_t dstStride, int width,
                              int height) {
    int pairs = width / 2;
    if (height >= 720) {
        for (int y = 0; y < height; y++) {
            yuyvRow(src + y * srcStride, (uint32_t *)(dst + y * dstStride), pairs, 4769, 7343, 873, 2183, 8652);
        }
    } else {
        for (int y = 0; y < height; y++) {
            yuyvRow(src + y * srcStride, (uint32_t *)(dst + y * dstStride), pairs, 4769, 6537, 1605, 3330, 8263);
        }
    }
}

#endif
