/* Layout decisions for screen-space draws, in normalized guest coordinates. */
#ifndef HW_XBOX_HLE_VK_HORPLUS_LAYOUT_H
#define HW_XBOX_HLE_VK_HORPLUS_LAYOUT_H

#include <math.h>
#include <stdbool.h>

typedef struct HleHorplusBox {
    float left, top, right, bottom;
} HleHorplusBox;

typedef struct HleHorplusLayout {
    HleHorplusBox last_tile;
    bool have_tile, tiled_panel;
    HleHorplusBox overlay;
    int overlay_anchor;
    bool have_overlay;
} HleHorplusLayout;

enum { HLE_HORPLUS_LEFT = -1, HLE_HORPLUS_CENTER, HLE_HORPLUS_RIGHT, HLE_HORPLUS_KEEP };

typedef struct HleHorplusBands {
    float left[3], right[3];
    unsigned used;
} HleHorplusBands;

static inline void hle_horplus_triangle(HleHorplusBands *bands, float left, float right)
{
    const float center = (left + right) * 0.5f;
    const unsigned i = center < 0.33f ? 0 : center > 0.67f ? 2 : 1;
    const unsigned bit = 1u << i;
    if (bands->used & bit) {
        bands->left[i] = fminf(bands->left[i], left);
        bands->right[i] = fmaxf(bands->right[i], right);
    } else {
        bands->left[i] = left;
        bands->right[i] = right;
        bands->used |= bit;
    }
}

/* Split a draw only at empty horizontal gaps. Every triangle then gets one
 * transform and the pieces within each part retain their spacing. */
static inline bool hle_horplus_split(const HleHorplusBands *bands, float cut[2], int anchor[3])
{
    if (!(bands->used & (bands->used - 1))) {
        return false;
    }
    unsigned n = 0;
    float prev_right = 0;
    for (unsigned i = 0; i < 3; i++) {
        if (!(bands->used & (1u << i))) {
            continue;
        }
        if (bands->right[i] - bands->left[i] > 0.37f || (n && bands->left[i] - prev_right < 0.06f)) {
            return false;
        }
        if (n) {
            cut[n - 1] = (prev_right + bands->left[i]) * 0.5f;
        }
        prev_right = bands->right[i];
        anchor[n++] = (int)i - 1;
    }
    if (n == 2) {
        cut[1] = 1.5f;
        anchor[2] = anchor[1];
    }
    return true;
}

static inline int hle_horplus_anchor(HleHorplusBox box, bool world, unsigned vertices,
                                    HleHorplusLayout *layout)
{
    /* Judge what is visible: HUD textures can extend past the viewport. */
    box.left = fmaxf(0.0f, box.left);
    box.top = fmaxf(0.0f, box.top);
    box.right = fminf(1.0f, box.right);
    box.bottom = fminf(1.0f, box.bottom);
    const float width = box.right - box.left, height = box.bottom - box.top;
    if (width <= 0 || height <= 0) {
        return HLE_HORPLUS_KEEP;
    }
    /* Require two adjacent, aligned tall pieces before treating the target's
     * remaining overlay as one tiled panel. A standalone effect or a scene
     * composite triangle can be large even on a frame with independent HUD. */
    if (vertices >= 4 && vertices <= 6 && width >= 0.10f && width < 0.60f && height >= 0.46f) {
        const HleHorplusBox prev = layout->last_tile;
        if (layout->have_tile && fabsf(box.top - prev.top) <= 0.025f &&
            fabsf(box.bottom - prev.bottom) <= 0.025f &&
            (fabsf(box.left - prev.right) <= 0.025f || fabsf(box.right - prev.left) <= 0.025f)) {
            layout->tiled_panel = true;
        }
        layout->last_tile = box;
        layout->have_tile = true;
    }
    if (!world || layout->tiled_panel || width >= 0.60f || height >= 0.46f) {
        layout->have_overlay = false;
        return HLE_HORPLUS_KEEP;
    }

    /* Consecutive pieces of a label or a horizontal panel can straddle the
     * edge/centre boundary. A common transform retains their spacing. */
    const HleHorplusBox prev = layout->overlay;
    const float left = fminf(box.left, prev.left), right = fmaxf(box.right, prev.right);
    const float overlap = fminf(box.bottom, prev.bottom) - fmaxf(box.top, prev.top);
    if (layout->have_overlay && right - left < 0.60f &&
        overlap >= 0.45f * fminf(height, prev.bottom - prev.top) &&
        fmaxf(box.left, prev.left) - fminf(box.right, prev.right) <= 0.018f) {
        layout->overlay = (HleHorplusBox){ left, fminf(box.top, prev.top), right, fmaxf(box.bottom, prev.bottom) };
        return layout->overlay_anchor;
    }
    const float center = (box.left + box.right) * 0.5f;
    const int anchor = center < 0.33f ? HLE_HORPLUS_LEFT :
                       center > 0.67f ? HLE_HORPLUS_RIGHT :
                       box.left >= 0.40f && box.right <= 0.60f ? HLE_HORPLUS_CENTER : HLE_HORPLUS_KEEP;
    layout->overlay = box;
    layout->overlay_anchor = anchor;
    layout->have_overlay = true;
    return anchor;
}

#endif
