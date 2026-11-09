#ifndef HLE_RENDER_SCALE_H
#define HLE_RENDER_SCALE_H

#include <stdint.h>

/* Half steps: 2 is 1x, 3 is 1.5x. Round shared edges the same way in
 * scissor, clear and copy commands so adjacent regions stay adjacent. */
enum { HLE_SCALE_NATIVE = 2, HLE_SCALE_MAX = 8, HLE_SCALE_DYNAMIC_MAX = 4 };

static inline int32_t hle_scale_edge(int32_t edge, uint32_t steps)
{
    const int64_t scaled = (int64_t)edge * steps;
    return (int32_t)((scaled - (scaled < 0 && scaled % 2 != 0)) / 2);
}

static inline uint32_t hle_scale_guest_extent(uint32_t host_extent, uint32_t steps)
{
    return (uint32_t)(((uint64_t)host_extent * 2 + 1) / steps);
}

static inline unsigned hle_scale_parse(const char *text, const char **end)
{
    if (*text < '1' || *text > '4') {
        return 0;
    }
    unsigned steps = (unsigned)(*text++ - '0') * 2;
    if (*text == '.') {
        ++text;
        if (*text != '0' && *text != '5') {
            return 0;
        }
        steps += *text++ == '5';
    }
    *end = text;
    return steps <= HLE_SCALE_MAX ? steps : 0;
}

#endif
