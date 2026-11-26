/* Layout decisions for screen-space draws, in normalized guest coordinates. */
#ifndef HW_XBOX_HLE_VK_HORPLUS_LAYOUT_H
#define HW_XBOX_HLE_VK_HORPLUS_LAYOUT_H

#include <stdbool.h>

typedef struct HleHorplusBox {
    float left, top, right, bottom;
} HleHorplusBox;

enum { HLE_HORPLUS_LEFT = -1, HLE_HORPLUS_CENTER, HLE_HORPLUS_RIGHT, HLE_HORPLUS_KEEP };

static inline int hle_horplus_anchor(HleHorplusBox box, bool world, bool *panel)
{
    const float width = box.right - box.left, height = box.bottom - box.top;
    /* A full-width quad can be the scene composite. A tall or broad partial
     * panel can be a tile of a menu. Keep the original coordinates on this
     * target for the panel and the following text and decoration. */
    if (width < 0.82f &&
        ((width >= 0.10f && height >= 0.46f) || (width >= 0.38f && height >= 0.21f))) {
        *panel = true;
    }
    if (!world || *panel || width >= 0.38f || height >= 0.46f) {
        return HLE_HORPLUS_KEEP;
    }
    /* Give the whole draw one transform. A draw crossing a region boundary
     * may contain parts of one label, texture, or gauge. */
    const float center = (box.left + box.right) * 0.5f;
    if (center < 0.33f && box.right <= 0.40f) {
        return HLE_HORPLUS_LEFT;
    }
    if (center > 0.67f && box.left >= 0.60f) {
        return HLE_HORPLUS_RIGHT;
    }
    return box.left >= 0.40f && box.right <= 0.60f ? HLE_HORPLUS_CENTER : HLE_HORPLUS_KEEP;
}

#endif
