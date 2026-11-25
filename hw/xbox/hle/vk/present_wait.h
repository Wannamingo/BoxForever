#ifndef HW_XBOX_HLE_VK_PRESENT_WAIT_H
#define HW_XBOX_HLE_VK_PRESENT_WAIT_H

#include <stdbool.h>
#include <stdint.h>
#include <vulkan/vulkan_core.h>

typedef struct HlePendingPresent {
    uint64_t value, key;
    uint32_t shader_gen;
    bool shader_fallback;
} HlePendingPresent;

/* The presenter owns the image until its render submit completes.
 * On timeout another refresh tries the same submit, without acquiring again. */
static inline VkResult hle_present_wait_pending(HlePendingPresent *pending, uint64_t timeout,
                                               VkResult (*wait)(uint64_t, uint64_t),
                                               void (*present)(bool, uint64_t, uint32_t, bool))
{
    if (!pending->value) {
        return VK_NOT_READY;
    }
    const VkResult r = wait(pending->value, timeout);
    if (r != VK_TIMEOUT) {
        pending->value = 0;
    }
    if (r == VK_SUCCESS) {
        present(true, pending->key, pending->shader_gen, pending->shader_fallback);
    }
    return r;
}

#endif
