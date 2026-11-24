/* An unavailable occlusion result cannot prove that a surface is hidden. */
#ifndef HLE_VIS_RESULT_H
#define HLE_VIS_RESULT_H
#include <stdbool.h>
#include <stdint.h>

#define HLE_VIS_FALLBACK_PIXELS (640u * 480u)

/* The reset command cannot cross the end of the pool or cover an unread slot.
 * An oldest slot equal to the pool size denotes an empty pending range. */
static inline uint32_t hle_vis_reset_span(uint32_t first, uint32_t oldest, uint32_t size, uint32_t block)
{
    uint32_t count = size - first;
    if (oldest < size) {
        const uint32_t distance = (oldest + size - first) % size;
        if (distance < count) {
            count = distance;
        }
    }
    return count < block ? count : block;
}

static inline uint32_t hle_vis_result(uint64_t samples, uint32_t divisor, bool complete)
{
    if (!complete) {
        return HLE_VIS_FALLBACK_PIXELS;
    }
    const uint64_t pixels = samples / (divisor ? divisor : 1);
    return pixels > UINT32_MAX ? UINT32_MAX : (uint32_t)pixels;
}
#endif
